package com.example.claudewidget

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class UpdateWidgetWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    private val sessions by lazy { SessionStore.get(applicationContext) }

    companion object {
        private const val TAG = "UpdateWidgetWorker"

        /**
         * Unique name for tap-to-refresh runs, so repeated taps don't queue up behind each other.
         * The app's Usage tab watches it to show "Refreshing...".
         */
        const val REFRESH_NOW_WORK = "ClaudeWidgetRefreshNow"

        /**
         * Tries per run, counting the first. A failure to reach the server (usually a Wi-Fi to
         * mobile handover) is retried with backoff; after the last try the widget keeps its
         * previous readings and waits for the next refresh.
         */
        private const val MAX_ATTEMPTS = 4
        private const val BACKOFF_SECONDS = 15L

        /** Periodic work should wait for connectivity instead of waking up just to fail. */
        private val networkConstraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        private fun periodicRequest(intervalMinutes: Long) =
            PeriodicWorkRequestBuilder<UpdateWidgetWorker>(intervalMinutes, TimeUnit.MINUTES)
                .setConstraints(networkConstraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build()

        /**
         * Manual refreshes intentionally have no WorkManager network constraint. During Wi-Fi ->
         * cellular handoff, Android's constraint/validation state can lag behind a connection that
         * apps can already use. Starting immediately lets OkHttp use the current default network; a
         * genuine handoff failure still comes back as IOException and uses the normal retry/backoff.
         */
        fun runNow(context: Context) = enqueueNow(context, ExistingWorkPolicy.REPLACE)

        /**
         * For the app's own refreshes (opening it, and its timer while open). Unlike [runNow], a
         * refresh that is already running or waiting to retry is left to finish instead of restarted.
         */
        fun refreshIfIdle(context: Context) = enqueueNow(context, ExistingWorkPolicy.KEEP)

        private fun enqueueNow(context: Context, policy: ExistingWorkPolicy) {
            val request = OneTimeWorkRequestBuilder<UpdateWidgetWorker>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(REFRESH_NOW_WORK, policy, request)
        }

        fun runNowClaude(context: Context) {
            runNow(context)
        }

        fun runNowChatGpt(context: Context) {
            runNow(context)
        }

        /** Schedule periodic updates AND run once now */
        fun enqueueWork(context: Context) {
            runNow(context)
            schedulePeriodic(context)
        }

        /**
         * Registers the periodic refresh at the saved interval, without running one now. UPDATE
         * keeps an existing schedule's timing and only swaps in the current request, which is how
         * installs from before the network constraint pick it up when the app is opened.
         */
        fun schedulePeriodic(context: Context) {
            val prefs = context.getSharedPreferences("ClaudeWidgetPrefs", Context.MODE_PRIVATE)
            val intervalMin = prefs.getLong("refresh_interval_minutes", 15L)

            if (intervalMin > 0L) {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    "ClaudeWidgetUpdate",
                    ExistingPeriodicWorkPolicy.UPDATE,
                    periodicRequest(intervalMin)
                )
            }
        }

        /** Re-schedule periodic work with a new interval */
        fun rescheduleWork(context: Context, intervalMinutes: Long) {
            val prefs = context.getSharedPreferences("ClaudeWidgetPrefs", Context.MODE_PRIVATE)
            prefs.edit().putLong("refresh_interval_minutes", intervalMinutes).apply()

            if (intervalMinutes <= 0L) {
                // "Never" — cancel any existing periodic work
                WorkManager.getInstance(context).cancelUniqueWork("ClaudeWidgetUpdate")
                Log.d(TAG, "Cancelled periodic work (manual only)")
            } else {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    "ClaudeWidgetUpdate",
                    ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
                    periodicRequest(intervalMinutes)
                )
                Log.d(TAG, "Rescheduled periodic work to every ${intervalMinutes}m")
            }
        }
    }

    private fun formatResetTime(isoString: String, logName: String): String {
        if (isoString.isEmpty() || isoString == "null") return "Unknown"
        try {
            val resetTime = ZonedDateTime.parse(isoString, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            val now = ZonedDateTime.now(java.time.ZoneOffset.UTC)
            var duration = Duration.between(now, resetTime)

            if (duration.isNegative) return "Resets now"

            val days = duration.toDays()
            duration = duration.minusDays(days)
            val hours = duration.toHours()
            duration = duration.minusHours(hours)
            val minutes = duration.toMinutes()

            return if (days > 0) "Resets in ${days}d ${hours}h"
            else if (hours > 0) "Resets in ${hours}h ${minutes}m"
            else "Resets in ${minutes}m"
        } catch (e: Exception) {
            AppLog.w(applicationContext, logName, "Failed to parse reset time", e)
            return "Unknown"
        }
    }

    private fun parseIsoResetEpochMs(isoString: String, logName: String): Long {
        if (isoString.isEmpty() || isoString == "null") return 0L
        return try {
            ZonedDateTime.parse(isoString, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                .toInstant()
                .toEpochMilli()
        } catch (e: Exception) {
            AppLog.w(applicationContext, logName, "Failed to parse reset timestamp", e)
            0L
        }
    }

    private fun resolveEpochResetMs(resetAt: Long, fallbackSeconds: Long): Long {
        val nowMs = System.currentTimeMillis()
        val nowSec = nowMs / 1000
        return when {
            resetAt > nowSec -> resetAt * 1000L
            fallbackSeconds > 0 -> nowMs + fallbackSeconds * 1000L
            else -> 0L
        }
    }

    private fun formatEpochResetTime(resetAt: Long, fallbackSeconds: Long): String {
        // No absolute reset and no relative fallback is genuinely unknown, not "Resets now".
        if (resetAt <= 0L && fallbackSeconds <= 0L) return "Unknown"

        val nowSec = System.currentTimeMillis() / 1000
        val remaining = if (resetAt > nowSec) {
            resetAt - nowSec
        } else if (fallbackSeconds > 0) {
            fallbackSeconds
        } else {
            0L
        }

        if (remaining <= 0) return "Resets now"

        val days = remaining / 86400
        val hours = (remaining % 86400) / 3600
        val mins = (remaining % 3600) / 60

        return when {
            days > 0 -> "Resets in ${days}d ${hours}h"
            hours > 0 -> "Resets in ${hours}h ${mins}m"
            else -> "Resets in ${mins}m"
        }
    }

    private fun prefs() = applicationContext.getSharedPreferences("ClaudeWidgetPrefs", Context.MODE_PRIVATE)

    /** Redraws the widgets and the notification that show [account]. */
    private fun publish(account: String) {
        if (Accounts.serviceOf(account) == Accounts.CLAUDE) ClaudeWidgetProvider.updateAllWidgets(applicationContext)
        else ChatGptWidgetProvider.updateAllWidgets(applicationContext)
        QuotaNotifications.updateAccount(applicationContext, account)
    }

    /** Shows an explicit Error state for [session]'s account and records [detail] in the in-app log. */
    private fun setErrorState(session: SessionStore.Snapshot, message: String, detail: String, error: Throwable? = null) {
        sessions.ifCurrent(session) {
            val account = session.account
            AppLog.e(applicationContext, Accounts.logName(account), detail, error)
            val p = Accounts.prefix(account)
            prefs().edit()
                .putString("${p}session_pct", "Error")
                .putString("${p}session_reset", "Error")
                .putInt("${p}session_prog", 0)
                .putLong("${p}session_reset_epoch_ms", 0L)
                .putString("${p}weekly_pct", "Error")
                .putString("${p}weekly_reset", "Error")
                .putInt("${p}weekly_prog", 0)
                .putLong("${p}weekly_reset_epoch_ms", 0L)
                .remove("${p}fable_prog")
                .remove("${p}fable_reset")
                .putString(Accounts.errorKey(account), message)
                .apply()
            publish(account)
        }
    }

    /** How one account's refresh went. OFFLINE (the server couldn't be reached) is worth retrying. */
    private enum class Outcome { UPDATED, FAILED, OFFLINE }

    /** Whether a failure now will be retried, so it's logged as a warning and no "Error" is shown yet. */
    private val willRetry get() = runAttemptCount < MAX_ATTEMPTS - 1

    /**
     * The server couldn't be reached (no connection, DNS failure, timeout). That's usually a
     * network handover that clears up by itself, so the last good readings stay on the widget
     * with "Offline" and the time they're from, instead of being replaced with "Error".
     */
    private fun setOfflineState(session: SessionStore.Snapshot, error: IOException): Outcome {
        var outcome = Outcome.UPDATED
        sessions.ifCurrent(session) { outcome = setOfflineStateCurrent(session, error) }
        return outcome
    }

    private fun setOfflineStateCurrent(session: SessionStore.Snapshot, error: IOException): Outcome {
        val account = session.account
        val name = Accounts.logName(account)
        val prefix = Accounts.prefix(account)
        val prefs = prefs()
        val hasReading = prefs.getString("${prefix}session_pct", null)?.endsWith("% used") == true

        if (!willRetry && !hasReading) {
            // Out of retries and nothing worth keeping on the widget, so show the error
            setErrorState(session, "Offline — tap refresh", "Couldn't reach the server", error)
            return Outcome.OFFLINE
        }

        if (willRetry) AppLog.w(applicationContext, name, "Couldn't reach the server, will retry", error)
        else AppLog.e(applicationContext, name, "Couldn't reach the server, keeping the last readings", error)

        if (hasReading) {
            val updatedAt = prefs.getString("${prefix}updated_at", null)
            prefs.edit()
                .putString("${prefix}last_update", if (updatedAt != null) "Offline · $updatedAt" else "Offline")
                .apply()
        }
        // Redraw either way, so a tap's "Refreshing..." gives way to the saved readings
        publish(account)
        return Outcome.OFFLINE
    }

    /** Error bodies are untrusted and can contain credentials; retain only the status code. */
    private fun httpSummary(code: Int, @Suppress("UNUSED_PARAMETER") body: String?) = "HTTP $code"

    private fun nowTimestamp(): String {
        return SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
    }

    override suspend fun doWork(): Result {
        val prefs = prefs()
        val defaultUa = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Mobile Safari/537.36"
        val client = SessionHttp.client()

        var offline = false
        for (service in Accounts.SERVICES) {
            for (account in Accounts.list(prefs, service)) {
                // A newer tap-to-refresh replaced this run; it will refresh every account itself
                if (isStopped) return Result.success()
                val session = sessions.snapshot(account)
                val outcome = when {
                    !session.isLoggedIn -> {
                        QuotaNotifications.cancel(applicationContext, account)
                        Outcome.UPDATED
                    }
                    service == Accounts.CLAUDE -> updateClaude(client, prefs, defaultUa, session)
                    else -> updateChatGpt(client, prefs, defaultUa, session)
                }
                if (outcome == Outcome.OFFLINE) offline = true
            }
            // Redraw every widget, so one showing a signed-out account doesn't stay stuck on "Refreshing..."
            if (service == Accounts.CLAUDE) ClaudeWidgetProvider.updateAllWidgets(applicationContext)
            else ChatGptWidgetProvider.updateAllWidgets(applicationContext)
        }

        // Retrying refreshes every account, which is harmless for the ones that already worked
        return if (offline && willRetry) Result.retry() else Result.success()
    }

    private fun updateClaude(
        client: OkHttpClient,
        prefs: SharedPreferences,
        defaultUa: String,
        session: SessionStore.Snapshot
    ): Outcome {
        val cookies = session.cookies?.takeIf { it.isNotEmpty() } ?: return Outcome.UPDATED
        val account = session.account
        val name = Accounts.logName(account)
        val p = Accounts.prefix(account)
        val ua = session.userAgent ?: defaultUa

        try {
            Log.d(TAG, "Fetching Claude organizations...")
            val orgRequest = Request.Builder()
                .url("https://claude.ai/api/organizations")
                .header("Cookie", cookies)
                .header("User-Agent", ua)
                .header("Accept", "application/json")
                .header("Referer", "https://claude.ai/")
                .build()

            val orgResponse = client.newCall(orgRequest).execute()
            val orgBody = orgResponse.use { SessionHttp.readBody(it) }
            if (orgResponse.code == 401 || orgResponse.code == 403) {
                setErrorState(session, "Session expired — tap to log in",
                    "Organizations request rejected (${httpSummary(orgResponse.code, orgBody)})")
                return Outcome.FAILED
            }
            if (!orgResponse.isSuccessful || orgBody.isNullOrEmpty()) {
                setErrorState(session, "Server error — tap refresh",
                    "Organizations request failed (${httpSummary(orgResponse.code, orgBody)})")
                return Outcome.FAILED
            }

            val orgArray = JSONArray(orgBody)
            if (orgArray.length() == 0) {
                setErrorState(session, "No org found", "Organizations request returned no organizations")
                return Outcome.FAILED
            }
            val orgId = orgArray.getJSONObject(0).getString("uuid")

            require(orgId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Invalid organization identifier" }
            Log.d(TAG, "Fetching Claude usage...")
            val usageRequest = Request.Builder()
                .url("https://claude.ai/api/organizations/$orgId/usage")
                .header("Cookie", cookies)
                .header("User-Agent", ua)
                .header("Accept", "application/json")
                .header("Referer", "https://claude.ai/")
                .build()

            val usageResponse = client.newCall(usageRequest).execute()
            val usageBody = usageResponse.use { SessionHttp.readBody(it) }
            if (usageResponse.code == 401 || usageResponse.code == 403) {
                setErrorState(session, "Session expired — tap to log in",
                    "Usage request rejected (${httpSummary(usageResponse.code, usageBody)})")
                return Outcome.FAILED
            }
            if (!usageResponse.isSuccessful || usageBody.isNullOrEmpty()) {
                setErrorState(session, "Server error — tap refresh",
                    "Usage request failed (${httpSummary(usageResponse.code, usageBody)})")
                return Outcome.FAILED
            }

            val usage = ClaudeUsage.parse(JSONObject(usageBody))
            val sessionProg = usage.session?.percent ?: 0
            val sessionPct = "$sessionProg% used"
            var sessionReset = usage.session?.let { formatResetTime(it.resetsAt, name) } ?: "No limit"
            var sessionResetEpochMs = usage.session?.let { parseIsoResetEpochMs(it.resetsAt, name) } ?: 0L
            val weeklyProg = usage.weekly?.percent ?: 0
            val weeklyPct = "$weeklyProg% used"
            val weeklyReset = usage.weekly?.let { formatResetTime(it.resetsAt, name) } ?: "No limit"
            val weeklyResetEpochMs = usage.weekly?.let { parseIsoResetEpochMs(it.resetsAt, name) } ?: 0L
            val fableReset = usage.fable?.let { formatResetTime(it.resetsAt, name) }

            // Weekly exhaustion takes precedence over the session window because a session reset
            // cannot restore usable quota while the weekly window is exhausted. Mirror the weekly
            // reset on the session row so both rows point to the next time quota becomes usable.
            // Otherwise, a fresh unused session with weekly capacity remaining is simply Ready.
            if (weeklyProg >= 100) {
                sessionReset = weeklyReset
                sessionResetEpochMs = weeklyResetEpochMs
            } else if (usage.session != null && sessionProg == 0) {
                // Claude may explicitly report a fresh 0%-used session while omitting resets_at.
                // The usage state is still known: the user can use the session now.
                sessionReset = "Ready"
            }

            val updatedAt = nowTimestamp()
            sessions.ifCurrent(session) {
                val editor = prefs.edit()
                    .putString("${p}session_pct", sessionPct)
                    .putString("${p}session_reset", sessionReset)
                    .putInt("${p}session_prog", sessionProg)
                    .putLong("${p}session_reset_epoch_ms", sessionResetEpochMs)
                    .putString("${p}weekly_pct", weeklyPct)
                    .putString("${p}weekly_reset", weeklyReset)
                    .putInt("${p}weekly_prog", weeklyProg)
                    .putLong("${p}weekly_reset_epoch_ms", weeklyResetEpochMs)
                    .putString("${p}fable_reset", fableReset)
                    .putString("${p}last_update", "Updated $updatedAt")
                    .putString("${p}updated_at", updatedAt)
                    .putLong("last_successful_refresh_epoch_ms", System.currentTimeMillis())
                    .remove(Accounts.errorKey(account))
                if (usage.fable != null) editor.putInt("${p}fable_prog", usage.fable.percent)
                else editor.remove("${p}fable_prog")
                editor.apply()

                publish(account)
                val fableStatus = usage.fable?.let { ", Fable ${it.percent}% used" }.orEmpty()
                AppLog.i(applicationContext, name, "Updated: session $sessionPct, weekly $weeklyPct$fableStatus")
            }
            return Outcome.UPDATED

        } catch (e: IOException) {
            return setOfflineState(session, e)
        } catch (e: Exception) {
            setErrorState(session, "Unexpected response — tap refresh", "Refresh failed", e)
            return Outcome.FAILED
        }
    }

    private fun updateChatGpt(
        client: OkHttpClient,
        prefs: SharedPreferences,
        defaultUa: String,
        session: SessionStore.Snapshot
    ): Outcome {
        val cookies = session.cookies
        var currentToken = session.accessToken
        val account = session.account
        val name = Accounts.logName(account)
        val p = Accounts.prefix(account)
        val chatGptUa = session.userAgent ?: defaultUa

        try {
            // If no token or we have cookies, try fetching/refreshing token
            if (currentToken.isNullOrEmpty() && !cookies.isNullOrEmpty()) {
                currentToken = refreshChatGptToken(client, cookies, chatGptUa, name)
                if (!currentToken.isNullOrEmpty()) {
                    if (!sessions.updateToken(session, currentToken)) return Outcome.UPDATED
                }
            }

            if (currentToken.isNullOrEmpty()) {
                setErrorState(session, "Tap widget to log in", "No access token, and the saved session couldn't get a new one")
                return Outcome.FAILED
            }

            Log.d(TAG, "Fetching ChatGPT usage...")
            var usageRequest = buildChatGptRequest(currentToken, chatGptUa, cookies)
            var usageResponse = client.newCall(usageRequest).execute()

            // If 401 and we have cookies, attempt token refresh once
            if (usageResponse.code == 401 && !cookies.isNullOrEmpty()) {
                AppLog.i(applicationContext, name, "Access token rejected (HTTP 401), getting a new one")
                usageResponse.close()
                val refreshedToken = refreshChatGptToken(client, cookies, chatGptUa, name)
                if (!refreshedToken.isNullOrEmpty()) {
                    currentToken = refreshedToken
                    if (!sessions.updateToken(session, refreshedToken)) return Outcome.UPDATED
                    usageRequest = buildChatGptRequest(currentToken, chatGptUa, cookies)
                    usageResponse = client.newCall(usageRequest).execute()
                } else {
                    setErrorState(session, "Session expired — tap to log in", "Saved session could not refresh the access token")
                    return Outcome.FAILED
                }
            }

            val body = usageResponse.use { SessionHttp.readBody(it) }
            if (usageResponse.code == 401 || usageResponse.code == 403) {
                setErrorState(session, "Session expired — tap to log in",
                    "Usage request rejected (${httpSummary(usageResponse.code, body)})")
                return Outcome.FAILED
            }
            if (!usageResponse.isSuccessful || body.isNullOrEmpty()) {
                setErrorState(session, "Server error — tap refresh",
                    "Usage request failed (${httpSummary(usageResponse.code, body)})")
                return Outcome.FAILED
            }

            val json = JSONObject(body)
            val rateLimit = json.optJSONObject("rate_limit")

            var sessionPct = "0% used"
            var sessionProg = 0
            var sessionReset = "No limit"
            var sessionResetEpochMs = 0L
            var sessionLimitPresent = false
            var weeklyPct = "0% used"
            var weeklyProg = 0
            var weeklyReset = "No limit"
            var weeklyResetEpochMs = 0L

            if (rateLimit != null) {
                val primary = rateLimit.optJSONObject("primary_window")
                if (primary != null) {
                    sessionLimitPresent = true
                    sessionProg = primary.optInt("used_percent", 0)
                    sessionPct = "$sessionProg% used"
                    val resetAt = primary.optLong("reset_at", 0L)
                    val resetSecs = primary.optLong("reset_after_seconds", 0L)
                    sessionReset = formatEpochResetTime(resetAt, resetSecs)
                    sessionResetEpochMs = resolveEpochResetMs(resetAt, resetSecs)
                }

                val secondary = rateLimit.optJSONObject("secondary_window")
                if (secondary != null) {
                    weeklyProg = secondary.optInt("used_percent", 0)
                    weeklyPct = "$weeklyProg% used"
                    val resetAt = secondary.optLong("reset_at", 0L)
                    val resetSecs = secondary.optLong("reset_after_seconds", 0L)
                    weeklyReset = formatEpochResetTime(resetAt, resetSecs)
                    weeklyResetEpochMs = resolveEpochResetMs(resetAt, resetSecs)
                }
            }

            // Match Claude: weekly exhaustion takes precedence over the otherwise-active session
            // window. Mirror the weekly reset on the session row because that is the next reset
            // that can actually restore usable quota. Otherwise, an unused session is Ready.
            if (weeklyProg >= 100) {
                sessionReset = weeklyReset
                sessionResetEpochMs = weeklyResetEpochMs
            } else if (sessionLimitPresent && sessionProg == 0) {
                // A present 0%-used window is usable now even if a reset timestamp is omitted.
                sessionReset = "Ready"
            }

            val updatedAt = nowTimestamp()
            sessions.ifCurrent(session) {
                prefs.edit()
                    .putString("${p}session_pct", sessionPct)
                    .putString("${p}session_reset", sessionReset)
                    .putInt("${p}session_prog", sessionProg)
                    .putLong("${p}session_reset_epoch_ms", sessionResetEpochMs)
                    .putString("${p}weekly_pct", weeklyPct)
                    .putString("${p}weekly_reset", weeklyReset)
                    .putInt("${p}weekly_prog", weeklyProg)
                    .putLong("${p}weekly_reset_epoch_ms", weeklyResetEpochMs)
                    .putString("${p}last_update", "Updated $updatedAt")
                    .putString("${p}updated_at", updatedAt)
                    .putLong("last_successful_refresh_epoch_ms", System.currentTimeMillis())
                    .remove(Accounts.errorKey(account))
                    .apply()

                publish(account)
                AppLog.i(applicationContext, name, "Updated: session $sessionPct, weekly $weeklyPct")
            }
            return Outcome.UPDATED

        } catch (e: IOException) {
            return setOfflineState(session, e)
        } catch (e: Exception) {
            setErrorState(session, "Unexpected response — tap refresh", "Refresh failed", e)
            return Outcome.FAILED
        }
    }

    private fun buildChatGptRequest(token: String, ua: String, cookies: String?): Request {
        val builder = Request.Builder()
            .url("https://chatgpt.com/backend-api/wham/usage")
            .header("Authorization", "Bearer $token")
            .header("User-Agent", ua)
            .header("Accept", "application/json")
            .header("Referer", "https://chatgpt.com/")
        if (!cookies.isNullOrEmpty()) {
            builder.header("Cookie", cookies)
        }
        return builder.build()
    }

    /**
     * A new access token from the saved session, or null when the session is logged out or
     * rejected. Throws IOException when the server can't be reached, which isn't a logged-out
     * session and shouldn't be shown as one.
     */
    private fun refreshChatGptToken(client: OkHttpClient, cookies: String, ua: String, logName: String): String? {
        try {
            SessionHttp.chatGptSessionCall(client, cookies, ua).execute().use { resp ->
            val body = SessionHttp.readBody(resp)
            if (resp.isSuccessful && !body.isNullOrEmpty()) {
                val token = SessionHttp.accessToken(body)
                if (token != null) {
                    AppLog.i(applicationContext, logName, "Got a new access token from the saved session")
                    return token
                }
                // A logged-out session answers 200 with no accessToken
                AppLog.w(applicationContext, logName, "Saved session has no access token (logged out or expired)")
            } else {
                AppLog.w(applicationContext, logName, "Session request failed (${httpSummary(resp.code, body)})")
            }
            }
        } catch (e: JSONException) {
            AppLog.w(applicationContext, logName, "Session request failed", e)
        }
        return null
    }
}
