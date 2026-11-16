package com.example.claudewidget

import android.app.PendingIntent
import android.content.Context
import android.content.SharedPreferences
import android.widget.RemoteViews

/** Click actions shared by the Claude and ChatGPT widgets. */
internal object WidgetClicks {
    private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    /**
     * The refresh button refreshes. Tapping the body refreshes too, or opens the app on the widget's
     * account when "Open App" is chosen or the last refresh failed. Request codes are per account
     * ([Accounts.slot]) because widgets of the same service can show different accounts, and intent
     * extras alone don't make PendingIntents distinct.
     */
    fun bind(context: Context, views: RemoteViews, prefs: SharedPreferences, account: String) {
        val slot = Accounts.slot(account)
        val refresh = PendingIntent.getBroadcast(
            context, 1000 + slot,
            QuotaNotifications.receiverIntent(context, QuotaNotifications.ACTION_REFRESH, account), FLAGS
        )
        views.setOnClickPendingIntent(R.id.btn_refresh, refresh)

        val isError = prefs.getString("${Accounts.prefix(account)}session_pct", "--") == "Error"
        if (isError || prefs.getString("tap_action", "refresh") == "open_app") {
            val open = PendingIntent.getActivity(
                context, 1100 + slot, MainActivity.openAccountIntent(context, account), FLAGS
            )
            views.setOnClickPendingIntent(R.id.widget_root, open)
        } else {
            views.setOnClickPendingIntent(R.id.widget_root, refresh)
        }
    }
}
