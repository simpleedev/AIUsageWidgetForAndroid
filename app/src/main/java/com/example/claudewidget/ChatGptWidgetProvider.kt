package com.example.claudewidget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.RemoteViews

class ChatGptWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_REFRESH_CHATGPT = "com.example.claudewidget.ACTION_REFRESH_CHATGPT"

        fun updateAllWidgets(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, ChatGptWidgetProvider::class.java))
            for (id in ids) {
                updateAppWidget(context, mgr, id)
            }
        }

        fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            status: String? = null
        ) {
            val prefs = context.getSharedPreferences("ClaudeWidgetPrefs", Context.MODE_PRIVATE)
            val account = Accounts.widgetAccount(prefs, appWidgetId, Accounts.CHATGPT)
            val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
            val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 110)
            val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110)
            appWidgetManager.updateAppWidget(appWidgetId,
                createViews(context, prefs, account, minWidth, minHeight, status))
        }

        /**
         * Views for [account]'s widget at least [minWidth] x [minHeight] dp. The app's Usage tab
         * shows these same views, so it always matches the home screen widget.
         */
        internal fun createViews(
            context: Context,
            prefs: SharedPreferences,
            account: String,
            minWidth: Int,
            minHeight: Int,
            status: String? = null
        ): RemoteViews {
            val layoutId = if (minWidth >= 180 || minHeight < 80) R.layout.chatgpt_widget_layout_wide else R.layout.chatgpt_widget_layout
            val views = RemoteViews(context.packageName, layoutId)
            WidgetClicks.bind(context, views, prefs, account)
            views.setTextViewText(R.id.tv_title, Accounts.displayName(prefs, account))

            // ---- Bind data ----
            val p = Accounts.prefix(account)
            val showLeft = UsageDisplay.mode(prefs, "chatgpt") == "left"

            if (status == null) {
                UsageDisplay.bind(views, R.id.tv_session_pct, R.id.pb_session,
                    prefs.getString("${p}session_pct", "--"), prefs.getInt("${p}session_prog", 0), showLeft)
                UsageDisplay.bind(views, R.id.tv_weekly_pct, R.id.pb_weekly,
                    prefs.getString("${p}weekly_pct", "--"), prefs.getInt("${p}weekly_prog", 0), showLeft)
            }
            views.setTextViewText(R.id.tv_session_reset, status ?: prefs.getString("${p}session_reset", "Tap refresh"))
            views.setTextViewText(R.id.tv_weekly_reset, status ?: prefs.getString("${p}weekly_reset", "Tap refresh"))
            views.setTextViewText(R.id.tv_last_update, status ?: prefs.getString("${p}last_update", "Not yet refreshed"))
            return views
        }

        /** Show a "Refreshing..." state (or another [status], like "Waiting for network...") on the widget immediately */
        fun showRefreshingState(context: Context, status: String = "Refreshing...") {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, ChatGptWidgetProvider::class.java))
            for (id in ids) {
                updateAppWidget(context, mgr, id, status)
            }
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, id)
        }
    }

    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        updateAppWidget(context, appWidgetManager, appWidgetId)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        Accounts.unbindWidgets(context.getSharedPreferences("ClaudeWidgetPrefs", Context.MODE_PRIVATE), appWidgetIds)
    }
}
