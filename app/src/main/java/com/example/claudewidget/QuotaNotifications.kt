package com.example.claudewidget

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Optional persistent quota notifications, one per account (see [Accounts]).
 *
 * Android 14+ allows users to swipe away many notifications marked ongoing. A delete intent is
 * attached so an enabled quota notification is posted again if that happens. The setting in the
 * app is the authoritative way to turn an account's notification off.
 */
object QuotaNotifications {
    const val ACTION_REFRESH = "com.example.claudewidget.ACTION_NOTIFICATION_REFRESH"
    const val ACTION_REPOST = "com.example.claudewidget.ACTION_NOTIFICATION_REPOST"
    /** The account id ("claude", "chatgpt-2"). Older PendingIntents carry [EXTRA_SERVICE] instead. */
    const val EXTRA_ACCOUNT = "account"
    const val EXTRA_SERVICE = "service"

    private const val CHANNEL_ID = "quota_status"
    private const val NOTIFICATION_ID_BASE = 4101

    /** The first accounts keep the keys used before multiple accounts ("claude_notification_enabled"). */
    fun prefKey(account: String) = when (account) {
        "claude", "chatgpt" -> "${account}_notification_enabled"
        else -> "${Accounts.prefix(account)}notification_enabled"
    }

    fun isEnabled(context: Context, account: String): Boolean =
        prefs(context).getBoolean(prefKey(account), false)

    fun setEnabled(context: Context, account: String, enabled: Boolean) {
        prefs(context).edit().putBoolean(prefKey(account), enabled).apply()
        if (enabled) {
            updateAccount(context, account)
            if (needsResetTimestamp(context, account)) UpdateWidgetWorker.runNow(context)
        } else {
            cancel(context, account)
        }
    }

    /** Re-post enabled notifications after app start/update, if notification permission is available. */
    fun restoreEnabled(context: Context) {
        val accounts = Accounts.all(prefs(context))
        accounts.forEach { updateAccount(context, it) }

        // 1.1.3 stored only relative reset text. After upgrading, fetch once so enabled
        // notifications get an exact absolute reset timestamp without waiting for the next interval.
        if (accounts.any { needsResetTimestamp(context, it) }) {
            UpdateWidgetWorker.runNow(context)
        }
    }

    fun cancel(context: Context, account: String) {
        NotificationManagerCompat.from(context).cancel(notificationId(account))
    }

    /** Drops a removed account's notification and its setting, so a later account with the same id starts off. */
    fun forget(context: Context, account: String) {
        cancel(context, account)
        prefs(context).edit().remove(prefKey(account)).apply()
    }

    /**
     * Updates an account's notification from the same saved quota values used by its widget.
     * If the option is off, permission is missing, or the account is logged out, no notification
     * is posted.
     */
    fun updateAccount(context: Context, account: String) {
        if (!isEnabled(context, account)) return
        if (!canPostNotifications(context)) return

        val prefs = prefs(context)
        if (!SessionStore.get(context).isLoggedIn(account)) {
            cancel(context, account)
            return
        }

        ensureChannel(context)

        val service = Accounts.serviceOf(account)
        val prefix = Accounts.prefix(account)
        val showLeft = UsageDisplay.mode(prefs, service) == "left"
        val sessionUsed = prefs.getInt("${prefix}session_prog", 0)
        val weeklyUsed = prefs.getInt("${prefix}weekly_prog", 0)
        val sessionText = UsageDisplay.text(
            prefs.getString("${prefix}session_pct", "--"),
            sessionUsed,
            showLeft
        )
        val weeklyText = UsageDisplay.text(
            prefs.getString("${prefix}weekly_pct", "--"),
            weeklyUsed,
            showLeft
        )
        val sessionReset = prefs.getString("${prefix}session_reset", "Tap refresh") ?: "Tap refresh"
        val weeklyReset = prefs.getString("${prefix}weekly_reset", "Tap refresh") ?: "Tap refresh"
        val name = Accounts.displayName(prefs, account)
        val title = notificationTitle(prefs, prefix, weeklyUsed, name)

        val fableUsed = if (service == "claude") prefs.getInt("${prefix}fable_prog", -1) else -1
        val fableText = if (fableUsed >= 0) UsageDisplay.text("$fableUsed% used", fableUsed, showLeft) else null
        val compact = buildString {
            append("Session $sessionText · Weekly $weeklyText")
            if (fableText != null) append(" · Fable $fableText")
        }
        val expanded = buildString {
            append("Session: $sessionText — $sessionReset")
            append('\n')
            append("Weekly: $weeklyText — $weeklyReset")
            if (fableText != null) {
                val fableReset = prefs.getString("${prefix}fable_reset", "Unknown")
                append("\nFable: $fableText — $fableReset")
            }
        }

        notifySafely(context,
            notificationId(account),
            baseBuilder(context, account)
                .setContentTitle(title)
                .setContentText(compact)
                .setStyle(NotificationCompat.BigTextStyle().bigText(expanded))
                .build()
        )
    }

    /** Keeps the old readings visible while clearly showing that a manual refresh is running. */
    fun showRefreshing(context: Context, account: String) {
        if (!isEnabled(context, account) || !canPostNotifications(context)) return
        val prefs = prefs(context)
        if (!SessionStore.get(context).isLoggedIn(account)) return

        ensureChannel(context)
        val name = Accounts.displayName(prefs, account)
        val prefix = Accounts.prefix(account)
        val weeklyUsed = prefs.getInt("${prefix}weekly_prog", 0)
        val title = notificationTitle(prefs, prefix, weeklyUsed, name)
        notifySafely(context,
            notificationId(account),
            baseBuilder(context, account)
                .setContentTitle(title)
                .setContentText("Refreshing quota…")
                .build()
        )
    }

    private fun notificationTitle(
        prefs: android.content.SharedPreferences,
        prefix: String,
        weeklyUsed: Int,
        name: String
    ): String {
        // A fresh, unused session is already usable, so there is no meaningful reset to advertise.
        if (weeklyUsed < 100 && prefs.getString("${prefix}session_reset", null) == "Ready") {
            return "$name - Ready"
        }
        return "$name - Next Reset: ${nextResetLabel(prefs, prefix, weeklyUsed)}"
    }

    /**
     * Weekly exhaustion takes priority because the session reset cannot restore usable quota while
     * the weekly window is still exhausted. Otherwise the session window is the next useful reset.
     */
    private fun nextResetLabel(
        prefs: android.content.SharedPreferences,
        prefix: String,
        weeklyUsed: Int
    ): String {
        val selectedPctKey = if (weeklyUsed >= 100) "${prefix}weekly_pct" else "${prefix}session_pct"
        val selectedResetKey = if (weeklyUsed >= 100) "${prefix}weekly_reset" else "${prefix}session_reset"
        val selectedPct = prefs.getString(selectedPctKey, null)
        val selectedReset = prefs.getString(selectedResetKey, null)

        // Never cover a genuine service state with an old/stale timestamp.
        if (selectedPct == "Error" || selectedReset == "Error") return "Error"
        if (selectedReset == "Unknown") return "Unknown"

        val resetEpochMs = nextResetEpochMs(prefs, prefix, weeklyUsed)
        if (resetEpochMs <= 0L) {
            return when (selectedReset) {
                "No limit" -> "No limit"
                "Resets now" -> "Resets now"
                "Ready" -> "Ready"
                else -> "Refresh needed"
            }
        }

        val zone = ZoneId.systemDefault()
        val reset = Instant.ofEpochMilli(resetEpochMs).atZone(zone)
        val now = ZonedDateTime.now(zone)
        val pattern = if (reset.toLocalDate() == now.toLocalDate()) {
            "h:mm a"
        } else {
            "EEE h:mm a"
        }
        return reset.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
    }

    private fun nextResetEpochMs(
        prefs: android.content.SharedPreferences,
        prefix: String,
        weeklyUsed: Int
    ): Long = if (weeklyUsed >= 100) {
        prefs.getLong("${prefix}weekly_reset_epoch_ms", 0L)
    } else {
        prefs.getLong("${prefix}session_reset_epoch_ms", 0L)
    }

    private fun needsResetTimestamp(context: Context, account: String): Boolean {
        if (!isEnabled(context, account)) return false
        val prefs = prefs(context)
        if (!SessionStore.get(context).isLoggedIn(account)) return false
        val prefix = Accounts.prefix(account)
        val weeklyUsed = prefs.getInt("${prefix}weekly_prog", 0)
        if (weeklyUsed < 100 && prefs.getString("${prefix}session_reset", null) == "Ready") return false
        return nextResetEpochMs(prefs, prefix, weeklyUsed) <= 0L
    }

    fun canPostNotifications(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private fun notifySafely(context: Context, id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (e: SecurityException) {
            // Permission can be revoked between the check and the actual notification operation.
            AppLog.w(context, "Notifications", "Notification permission unavailable", e)
        }
    }

    /**
     * Intent extras don't make PendingIntents distinct, so each account gets its own request codes
     * (base + [Accounts.slot]). The first accounts keep the codes used before multiple accounts.
     */
    private fun baseBuilder(context: Context, account: String): NotificationCompat.Builder {
        val service = Accounts.serviceOf(account)
        val slot = Accounts.slot(account)
        val openPending = PendingIntent.getActivity(
            context,
            4401 + slot,
            MainActivity.openAccountIntent(context, account),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val refreshPending = PendingIntent.getBroadcast(
            context,
            4201 + slot,
            receiverIntent(context, ACTION_REFRESH, account),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val repostPending = PendingIntent.getBroadcast(
            context,
            4301 + slot,
            receiverIntent(context, ACTION_REPOST, account),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(if (service == "chatgpt") R.drawable.ic_chatgpt else R.drawable.ic_claude)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(openPending)
            .setDeleteIntent(repostPending)
            .addAction(R.drawable.ic_refresh, "Refresh", refreshPending)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Quota status",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Persistent Claude and ChatGPT quota status"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    /** A refresh or repost action for the private [QuotaNotificationReceiver]. */
    fun receiverIntent(context: Context, action: String, account: String): Intent =
        Intent(context, QuotaNotificationReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_ACCOUNT, account)
        }

    /** 4101 and 4102 for the first Claude and ChatGPT accounts, as before multiple accounts. */
    private fun notificationId(account: String) = NOTIFICATION_ID_BASE + Accounts.slot(account)

    private fun prefs(context: Context) =
        context.getSharedPreferences("ClaudeWidgetPrefs", Context.MODE_PRIVATE)

}
