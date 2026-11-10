package com.example.claudewidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import androidx.core.os.BundleCompat

class ClaudeWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_REFRESH = "com.example.claudewidget.ACTION_REFRESH"
        private const val MIN_FABLE_HEIGHT_DP = 110
        private const val MIN_FABLE_RESET_HEIGHT_DP = 124
        private const val MIN_WIDE_FABLE_HEIGHT_DP = 210
        /** RemoteViews accepts at most 16 sized layouts. */
        private const val MAX_SIZED_VIEWS = 16

        fun updateAllWidgets(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, ClaudeWidgetProvider::class.java))
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
            val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
            appWidgetManager.updateAppWidget(appWidgetId, createViews(context, prefs, options, status))
        }

        internal fun createViews(
            context: Context,
            prefs: SharedPreferences,
            options: Bundle,
            status: String? = null
        ): RemoteViews {
            // Android 12+ launchers list every size the widget is shown at (typically portrait and
            // landscape). Give each its own views, so a 4x2 widget that is 210dp tall in portrait
            // isn't laid out for its 108dp landscape height.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val sizes = BundleCompat.getParcelableArrayList(
                    options, AppWidgetManager.OPTION_APPWIDGET_SIZES, SizeF::class.java
                )?.distinct()
                if (!sizes.isNullOrEmpty() && sizes.size <= MAX_SIZED_VIEWS) {
                    return RemoteViews(sizes.associateWith {
                        createViews(context, prefs, it.width.toInt(), it.height.toInt(), status)
                    })
                }
            }
            // Older launchers only report the smallest width and height the widget may get.
            val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 110)
            val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110)
            return createViews(context, prefs, minWidth, minHeight, status)
        }

        /** Views for a widget at least [minWidth] x [minHeight] dp. */
        internal fun createViews(
            context: Context,
            prefs: SharedPreferences,
            minWidth: Int,
            minHeight: Int,
            status: String? = null
        ): RemoteViews {
            val fableUsed = prefs.getInt("fable_prog", -1)
            val showFable = fableUsed >= 0 && minHeight >= MIN_FABLE_HEIGHT_DP
            val useWide = if (showFable) {
                minWidth >= 180 && minHeight >= MIN_WIDE_FABLE_HEIGHT_DP
            } else {
                minWidth >= 180 || minHeight < 80
            }
            val layoutId = if (useWide) R.layout.widget_layout_wide else R.layout.widget_layout

            val views = RemoteViews(context.packageName, layoutId)

            // ---- Refresh button ----
            val refreshIntent = Intent(context, QuotaNotificationReceiver::class.java).apply {
                action = QuotaNotifications.ACTION_REFRESH
                putExtra(QuotaNotifications.EXTRA_SERVICE, "claude")
            }
            val refreshPending = PendingIntent.getBroadcast(
                context, 0, refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.btn_refresh, refreshPending)

            // ---- Tap widget body ----
            val isError = prefs.getString("session_pct", "--") == "Error"
            val tapAction = prefs.getString("tap_action", "refresh")
            
            if (isError || tapAction == "open_app") {
                // Open app to Claude tab
                val openAppPending = PendingIntent.getActivity(
                    context, 1, MainActivity.openTabIntent(context, "claude"),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.widget_root, openAppPending)
            } else {
                // Refresh on body tap
                val refreshIntent2 = Intent(context, QuotaNotificationReceiver::class.java).apply {
                    action = QuotaNotifications.ACTION_REFRESH
                    putExtra(QuotaNotifications.EXTRA_SERVICE, "claude")
                }
                val refreshPending2 = PendingIntent.getBroadcast(
                    context, 2, refreshIntent2,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.widget_root, refreshPending2)
            }

            // ---- Bind data ----
            val showLeft = UsageDisplay.mode(prefs, "claude") == "left"

            if (status == null) {
                UsageDisplay.bind(views, R.id.tv_session_pct, R.id.pb_session,
                    prefs.getString("session_pct", "--"), prefs.getInt("session_prog", 0), showLeft)
                UsageDisplay.bind(views, R.id.tv_weekly_pct, R.id.pb_weekly,
                    prefs.getString("weekly_pct", "--"), prefs.getInt("weekly_prog", 0), showLeft)
            }
            views.setTextViewText(R.id.tv_session_reset, status ?: prefs.getString("session_reset", "Tap refresh"))
            views.setTextViewText(R.id.tv_weekly_reset, status ?: prefs.getString("weekly_reset", "Tap refresh"))
            views.setTextViewText(R.id.tv_last_update, status ?: prefs.getString("last_update", "Not yet refreshed"))

            val fableVisibility = if (showFable) View.VISIBLE else View.GONE
            views.setViewVisibility(R.id.fable_gap, fableVisibility)
            views.setViewVisibility(R.id.fable_row, fableVisibility)
            if (showFable) {
                UsageDisplay.bind(views, R.id.tv_fable_pct, R.id.pb_fable, "$fableUsed% used", fableUsed, showLeft)
                views.setTextViewText(R.id.tv_fable_reset, prefs.getString("fable_reset", "Unknown"))
                views.setViewVisibility(R.id.tv_fable_reset,
                    if (minHeight >= MIN_FABLE_RESET_HEIGHT_DP) View.VISIBLE else View.GONE)
            }

            return views
        }

        /** Show a "Refreshing..." state (or another [status], like "Waiting for network...") on the widget immediately */
        fun showRefreshingState(context: Context, status: String = "Refreshing...") {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, ClaudeWidgetProvider::class.java))
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
        // Re-render with the correct layout when the user resizes the widget
        updateAppWidget(context, appWidgetManager, appWidgetId)
    }

}
