package com.example.claudewidget

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UsageTabTest {
    @Test fun usageTabShowsTheWidgetOfEachSignedInAccountAndOpensIt() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("ClaudeWidgetPrefs", Context.MODE_PRIVATE)
        val sessions = SessionStore.get(context)
        sessions.clear("claude")
        sessions.clear("chatgpt")
        prefs.edit().clear()
            // No background refresh with the synthetic session, and no refresh on open
            .putLong("refresh_interval_minutes", 0L)
            .putLong("last_successful_refresh_epoch_ms", System.currentTimeMillis())
            .commit()

        val work = Accounts.add(prefs, "claude")!!
        Accounts.setLabel(prefs, work, "Work")
        val p = Accounts.prefix(work)
        prefs.edit().putString("${p}session_pct", "42% used").putInt("${p}session_prog", 42)
            .putString("${p}weekly_pct", "7% used").putInt("${p}weekly_prog", 7).commit()
        sessions.save(work, "sessionKey=synthetic-work", null, "UA")

        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            instrumentation.runOnMainSync {
                // Only the signed-in account gets a card; the signed-out first Claude account and ChatGPT don't
                val cards = activity.findViewById<LinearLayout>(R.id.usage_cards)
                assertEquals(1, cards.childCount)
                val card = cards.getChildAt(0)
                assertEquals("Claude · Work", card.findViewById<TextView>(R.id.tv_title).text.toString())
                assertEquals("42% used", card.findViewById<TextView>(R.id.tv_session_pct).text.toString())
                assertEquals("7% used", card.findViewById<TextView>(R.id.tv_weekly_pct).text.toString())

                card.performClick()
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.account_bar).visibility)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.successLayout).visibility)
                assertEquals("Work", activity.findViewById<TextView>(R.id.tv_account_label).text.toString())
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.btn_remove_account).visibility)
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            sessions.clear(work)
            prefs.edit().clear().commit()
        }
    }
}
