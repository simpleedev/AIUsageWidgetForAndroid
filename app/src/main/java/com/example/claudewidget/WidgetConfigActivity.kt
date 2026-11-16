package com.example.claudewidget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * Picks which account a widget shows. The launcher opens this when a widget is added and, on
 * Android 12+, when the user reconfigures one. With a single account there's nothing to choose,
 * so it confirms straight away and adding a widget works as it did before multiple accounts.
 */
class WidgetConfigActivity : AppCompatActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Backing out of the picker cancels adding the widget (or leaves a reconfigured one as it was)
        setResult(Activity.RESULT_CANCELED)

        appWidgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // This activity is exported for the launcher, so only accept this app's own widgets
        val provider = AppWidgetManager.getInstance(this).getAppWidgetInfo(appWidgetId)?.provider
        if (provider == null || provider.packageName != packageName) {
            finish()
            return
        }
        val service = when (provider.className) {
            ChatGptWidgetProvider::class.java.name -> Accounts.CHATGPT
            ClaudeWidgetProvider::class.java.name -> Accounts.CLAUDE
            else -> { finish(); return }
        }

        val prefs = getSharedPreferences("ClaudeWidgetPrefs", Context.MODE_PRIVATE)
        val accounts = Accounts.list(prefs, service)
        if (accounts.size == 1) {
            choose(accounts.first())
            return
        }
        val sessions = SessionStore.get(this)
        val labels = accounts.map { id ->
            val label = Accounts.label(prefs, id)
            if (sessions.isLoggedIn(id)) label else "$label (not signed in)"
        }.toTypedArray()
        val current = accounts.indexOf(Accounts.widgetAccount(prefs, appWidgetId, service))

        AlertDialog.Builder(this)
            .setTitle("${Accounts.serviceName(service)} account for this widget")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                dialog.dismiss()
                choose(accounts[which])
            }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun choose(account: String) {
        val prefs = getSharedPreferences("ClaudeWidgetPrefs", Context.MODE_PRIVATE)
        Accounts.bindWidget(prefs, appWidgetId, account)
        // A widget with a configuration activity isn't sent its first update, so draw it here
        val mgr = AppWidgetManager.getInstance(this)
        if (Accounts.serviceOf(account) == Accounts.CHATGPT) ChatGptWidgetProvider.updateAppWidget(this, mgr, appWidgetId)
        else ClaudeWidgetProvider.updateAppWidget(this, mgr, appWidgetId)
        AppLog.i(this, Accounts.logName(account), "Widget set to show this account")
        setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        finish()
    }
}
