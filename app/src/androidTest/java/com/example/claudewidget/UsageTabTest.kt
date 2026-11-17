package com.example.claudewidget

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UsageTabTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val prefs = context.getSharedPreferences("ClaudeWidgetPrefs", Context.MODE_PRIVATE)
    private val sessions = SessionStore.get(context)

    /** Signs in a synthetic Claude account named "Work", opens the app on the Usage tab and runs [block]. */
    private fun withWorkAccount(extraPrefs: SharedPreferences.Editor.() -> Unit = {}, block: (MainActivity) -> Unit) {
        sessions.clear("claude")
        sessions.clear("chatgpt")
        prefs.edit().clear()
            // No background refresh with the synthetic session, and no refresh on open
            .putLong("refresh_interval_minutes", 0L)
            .putLong("last_successful_refresh_epoch_ms", System.currentTimeMillis())
            .apply { extraPrefs() }
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
            instrumentation.runOnMainSync { block(activity) }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            sessions.clear(work)
            prefs.edit().clear().commit()
        }
    }

    @Test fun usageTabShowsTheWidgetOfEachSignedInAccountAndOpensIt() = withWorkAccount { activity ->
        // Only the signed-in account gets a card; the signed-out first Claude account and ChatGPT don't
        val cards = activity.findViewById<LinearLayout>(R.id.usage_cards)
        assertEquals(1, cards.childCount)
        val card = cards.getChildAt(0)
        assertEquals("Claude · Work", card.findViewById<TextView>(R.id.tv_title).text.toString())
        assertEquals("42% used", card.findViewById<TextView>(R.id.tv_session_pct).text.toString())
        assertEquals("7% used", card.findViewById<TextView>(R.id.tv_weekly_pct).text.toString())
        assertTrue(activity.findViewById<TextView>(R.id.tv_usage_hint).text.startsWith("Tap a card"))

        card.performClick()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.account_bar).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.successLayout).visibility)
        assertEquals("Work", activity.findViewById<TextView>(R.id.tv_account_label).text.toString())
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.btn_remove_account).visibility)
    }

    @Test fun tappingACardDoesNothingWhenTheSettingIsOff() = withWorkAccount(
        extraPrefs = { putBoolean("usage_card_opens_account", false) }
    ) { activity ->
        val card = activity.findViewById<LinearLayout>(R.id.usage_cards).getChildAt(0)
        assertFalse(card.isClickable)
        assertFalse(card.hasOnClickListeners())
        assertFalse(activity.findViewById<TextView>(R.id.tv_usage_hint).text.contains("Tap a card"))

        card.performClick()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.usageLayout).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.account_bar).visibility)
        // The card's refresh button keeps working
        assertTrue(card.findViewById<View>(R.id.btn_refresh).hasOnClickListeners())
    }

    @Test fun settingsCheckboxTurnsCardTapOffAndOn() = withWorkAccount { activity ->
        activity.findViewById<View>(R.id.tab_settings).performClick()
        val box = activity.findViewById<CheckBox>(R.id.cb_usage_card_opens_account)
        assertTrue(box.isChecked)

        box.performClick()
        assertFalse(prefs.getBoolean("usage_card_opens_account", true))
        activity.findViewById<View>(R.id.tab_usage).performClick()
        assertFalse(activity.findViewById<LinearLayout>(R.id.usage_cards).getChildAt(0).isClickable)

        activity.findViewById<View>(R.id.tab_settings).performClick()
        assertFalse(box.isChecked)
        box.performClick()
        assertTrue(prefs.getBoolean("usage_card_opens_account", false))
        activity.findViewById<View>(R.id.tab_usage).performClick()
        assertTrue(activity.findViewById<LinearLayout>(R.id.usage_cards).getChildAt(0).hasOnClickListeners())
    }
}
