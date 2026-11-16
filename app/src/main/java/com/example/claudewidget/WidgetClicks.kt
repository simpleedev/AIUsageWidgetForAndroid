package com.example.claudewidget

import android.app.PendingIntent
import android.content.Context
import android.content.SharedPreferences
import android.widget.RemoteViews

/** Click actions shared by the Claude, ChatGPT and All Accounts widgets. */
internal object WidgetClicks {
    private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    /** Request code for opening the Usage tab. Accounts use 1000-1019 (refresh) and 1100-1119 (open). */
    private const val OPEN_USAGE_REQUEST = 1200

    /**
     * The refresh button refreshes. Tapping the body refreshes too, or opens the app on the widget's
     * account when "Open App" is chosen or the last refresh failed. Request codes are per account
     * ([Accounts.slot]) because widgets of the same service can show different accounts, and intent
     * extras alone don't make PendingIntents distinct.
     */
    fun bind(context: Context, views: RemoteViews, prefs: SharedPreferences, account: String) {
        views.setOnClickPendingIntent(R.id.btn_refresh, refresh(context, account))
        views.setOnClickPendingIntent(R.id.widget_root, tap(context, prefs, account))
    }

    /** Refreshes every account; [account] names the widget or card it came from. */
    fun refresh(context: Context, account: String): PendingIntent = PendingIntent.getBroadcast(
        context, 1000 + Accounts.slot(account),
        QuotaNotifications.receiverIntent(context, QuotaNotifications.ACTION_REFRESH, account), FLAGS
    )

    /** What tapping [account]'s widget or card does: refresh, or open the app on it (see [bind]). */
    fun tap(context: Context, prefs: SharedPreferences, account: String): PendingIntent {
        val isError = prefs.getString("${Accounts.prefix(account)}session_pct", "--") == "Error"
        return if (isError || opensApp(prefs)) {
            PendingIntent.getActivity(context, 1100 + Accounts.slot(account),
                MainActivity.openAccountIntent(context, account), FLAGS)
        } else {
            refresh(context, account)
        }
    }

    /** Whether the "Widget Tap Action" setting is "Open App". */
    fun opensApp(prefs: SharedPreferences) = prefs.getString("tap_action", "refresh") == "open_app"

    /** Opens the app on its Usage tab. */
    fun openUsage(context: Context): PendingIntent = PendingIntent.getActivity(
        context, OPEN_USAGE_REQUEST, MainActivity.openUsageIntent(context), FLAGS
    )
}
