package com.example.claudewidget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

/** Handles taps/actions and restores an enabled quota notification if the user swipes it away. */
class QuotaNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Widgets and notifications from before multiple accounts name only the service, which
        // is also the id of its first account.
        val account = (intent.getStringExtra(QuotaNotifications.EXTRA_ACCOUNT)
            ?: intent.getStringExtra(QuotaNotifications.EXTRA_SERVICE))
            ?.takeIf { Accounts.isValid(it) }
            ?: return

        when (intent.action) {
            QuotaNotifications.ACTION_REFRESH -> {
                // One refresh updates every account, so every widget and notification shows it.
                ClaudeWidgetProvider.showRefreshingState(context)
                ChatGptWidgetProvider.showRefreshingState(context)
                AllAccountsWidgetProvider.showRefreshingState(context)
                val prefs = context.getSharedPreferences("ClaudeWidgetPrefs", Context.MODE_PRIVATE)
                Accounts.all(prefs).forEach { QuotaNotifications.showRefreshing(context, it) }
                // Manual work intentionally runs immediately rather than waiting on WorkManager's
                // network constraint, which can lag behind a Wi-Fi -> cellular handoff.
                UpdateWidgetWorker.runNow(context)
            }
            QuotaNotifications.ACTION_REPOST -> {
                // Android 14+ can allow ongoing notifications to be swiped away. Give System UI a
                // moment to finish the dismissal, then put it back if the option is still enabled.
                val result = goAsync()
                val appContext = context.applicationContext
                Handler(Looper.getMainLooper()).postDelayed({
                    QuotaNotifications.updateAccount(appContext, account)
                    result.finish()
                }, 500L)
            }
        }
    }
}
