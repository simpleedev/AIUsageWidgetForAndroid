package com.example.claudewidget

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.claudewidget.AllAccountsLayout.Style
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

/**
 * The All Accounts widgets built the way the launcher gets them (RemoteViews) and laid out at real
 * sizes, which checks AllAccountsLayout's height estimates against the actual layouts and fonts.
 */
@RunWith(AndroidJUnit4::class)
class AllAccountsWidgetTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private lateinit var prefs: SharedPreferences

    @Before fun setUp() {
        prefs = context.getSharedPreferences("test_all_accounts_widget", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    @After fun tearDown() {
        prefs.edit().clear().commit()
    }

    private fun reading(account: String, session: Int, weekly: Int, fable: Int? = null) {
        val p = Accounts.prefix(account)
        val editor = prefs.edit()
            .putString("${p}session_pct", "$session% used").putInt("${p}session_prog", session)
            .putString("${p}session_reset", "Resets in 2h 13m")
            .putString("${p}weekly_pct", "$weekly% used").putInt("${p}weekly_prog", weekly)
            .putString("${p}weekly_reset", "Resets in 6d 23h")
            .putString("${p}last_update", "Updated 3:41 PM")
        if (fable != null) editor.putInt("${p}fable_prog", fable).putString("${p}fable_reset", "Resets in 4d 2h")
        editor.commit()
    }

    /** Two Claude accounts (one with Fable) and two ChatGPT accounts (one whose refresh failed). */
    private fun fourAccounts(): List<String> {
        val claude2 = Accounts.add(prefs, Accounts.CLAUDE)!!
        val chatgpt2 = Accounts.add(prefs, Accounts.CHATGPT)!!
        Accounts.setLabel(prefs, claude2, "Work")
        reading(Accounts.CLAUDE, 34, 61, fable = 22)
        reading(claude2, 100, 100)
        reading(Accounts.CHATGPT, 12, 45)
        val p = Accounts.prefix(chatgpt2)
        prefs.edit().putString("${p}session_pct", "Error").putString("${p}weekly_pct", "Error")
            .putString(Accounts.errorKey(chatgpt2), "Session expired — tap to log in").commit()
        return Accounts.all(prefs)
    }

    private fun layOut(accounts: List<String>, widthDp: Int, heightDp: Int, style: Style, status: String? = null): View {
        val root = AllAccountsWidgetProvider.createViews(context, prefs, accounts, widthDp, heightDp, status, style)
            .apply(context, null)
        val density = context.resources.displayMetrics.density
        root.measure(
            View.MeasureSpec.makeMeasureSpec((widthDp * density).roundToInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((heightDp * density).roundToInt(), View.MeasureSpec.EXACTLY))
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        return root
    }

    private fun entries(view: View): List<ViewGroup> =
        if (view.id == R.id.card_root) listOf(view as ViewGroup)
        else (view as? ViewGroup)?.let { group -> (0 until group.childCount).flatMap { entries(group.getChildAt(it)) } }.orEmpty()

    /** The height an entry's content takes: its padding and every visible child that isn't a spacer. */
    private fun contentHeight(entry: ViewGroup): Int {
        var height = entry.paddingTop + entry.paddingBottom
        for (i in 0 until entry.childCount) {
            val child = entry.getChildAt(i)
            val params = child.layoutParams as LinearLayout.LayoutParams
            if (child.visibility != View.VISIBLE || params.weight > 0f) continue
            height += child.measuredHeight + params.topMargin + params.bottomMargin
        }
        return height
    }

    @Test fun everyShownAccountFitsAtCommonSizes() {
        val accounts = fourAccounts()
        val sizes = listOf(340 to 400, 300 to 340, 340 to 280, 250 to 250, 640 to 230, 640 to 110, 340 to 110)
        instrumentation.runOnMainSync {
            for (style in Style.values()) for ((width, height) in sizes) for (count in 1..accounts.size) {
                // The card widget can't be made shorter than 4x3 (180dp); the list can go down to 4x2
                if (style == Style.CARDS && height < 180) continue
                val shown = accounts.take(count)
                val where = "$style, $count accounts at ${width}x$height dp"
                val root = layOut(shown, width, height, style)
                val cards = entries(root)
                val more = root.findViewById<TextView>(R.id.tv_all_more)
                val status = root.findViewById<TextView>(R.id.tv_all_status).text.toString()
                assertTrue(where, cards.isNotEmpty())
                if (cards.size < count) {
                    // On a line of its own, or in the header when there's no room for the line
                    val hidden = "+${count - cards.size} more"
                    if (more.visibility == View.VISIBLE) assertEquals(where, "$hidden · tap to see all", more.text.toString())
                    else assertTrue("$where: $status", status.startsWith("$hidden · "))
                } else {
                    assertEquals(where, count, cards.size)
                    assertEquals(where, View.GONE, more.visibility)
                }
                // 1 px of rounding either way
                for (card in cards) {
                    assertTrue("$where: ${contentHeight(card)} px of content in a ${card.height} px entry",
                        contentHeight(card) <= card.height + 1)
                }
                val list = root.findViewById<ViewGroup>(
                    if (style == Style.LIST) R.id.all_accounts_lines else R.id.all_accounts_cards)
                val lastRow = list.getChildAt(list.childCount - 1)
                assertTrue("$where: rows end at ${lastRow.bottom} of ${list.height} px", lastRow.bottom <= list.height + 1)
            }
        }
    }

    @Test fun cardsAndListShowTheSameReadings() {
        val accounts = fourAccounts()
        instrumentation.runOnMainSync {
            for (style in Style.values()) {
                val cards = entries(layOut(accounts, 340, 600, style))
                assertEquals(4, cards.size)
                fun text(card: View, id: Int) = card.findViewById<TextView>(id).text.toString()

                // Titles name the account once a service has several; the icon names the service
                assertEquals(listOf("Account 1", "Work", "Account 1", "Account 2"), cards.map { text(it, R.id.tv_title) })
                assertEquals("34% used", text(cards[0], R.id.tv_session_pct))
                // ChatGPT shows usage left by default
                assertEquals("88% left", text(cards[2], R.id.tv_session_pct))
                assertEquals(View.VISIBLE, cards[0].findViewById<View>(R.id.fable_row).visibility)
                assertEquals(View.GONE, cards[1].findViewById<View>(R.id.fable_row).visibility)
                // Only the account with a problem has a status line
                assertEquals(View.GONE, cards[0].findViewById<View>(R.id.tv_last_update).visibility)
                assertEquals("Session expired — tap to log in", text(cards[3], R.id.tv_last_update))
                // The list's narrow column drops "Resets in"
                assertEquals(if (style == Style.LIST) "2h 13m" else "Resets in 2h 13m", text(cards[0], R.id.tv_session_reset))
            }
        }
    }

    @Test fun refreshingKeepsTheReadingsAndAnEmptyWidgetSaysWhy() {
        val accounts = fourAccounts()
        instrumentation.runOnMainSync {
            for (style in Style.values()) {
                val refreshing = layOut(accounts, 340, 400, style, status = "Refreshing...")
                assertEquals("Refreshing...", refreshing.findViewById<TextView>(R.id.tv_all_status).text.toString())
                assertEquals("34% used", entries(refreshing)[0].findViewById<TextView>(R.id.tv_session_pct).text.toString())

                val empty = layOut(emptyList(), 340, 400, style)
                assertEquals(View.VISIBLE, empty.findViewById<View>(R.id.tv_all_empty).visibility)
                assertEquals(View.GONE, empty.findViewById<View>(R.id.all_accounts_cards).visibility)
                assertEquals(View.GONE, empty.findViewById<View>(R.id.all_accounts_lines).visibility)
                assertEquals(View.GONE, empty.findViewById<View>(R.id.btn_all_refresh).visibility)
                assertTrue(entries(empty).isEmpty())
            }
        }
    }

    @Test fun widgetOptionsWithoutSizesStillBuild() {
        val accounts = fourAccounts()
        instrumentation.runOnMainSync {
            for (style in Style.values()) {
                // A launcher that hasn't reported a size yet
                val root = AllAccountsWidgetProvider.createViews(context, prefs, accounts, Bundle(), style = style)
                    .apply(context, null)
                assertTrue(entries(root).isNotEmpty())
            }
        }
    }
}
