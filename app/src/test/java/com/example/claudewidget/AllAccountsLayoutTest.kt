package com.example.claudewidget

import com.example.claudewidget.AllAccountsLayout.Card
import com.example.claudewidget.AllAccountsLayout.Density
import com.example.claudewidget.AllAccountsLayout.Style
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AllAccountsLayoutTest {

    private fun cards(count: Int, fable: Boolean = false) = List(count) { Card(fable = fable) }

    @Test fun oneOrTwoAccountsGetLargeCardsInATypical4x4() {
        assertEquals(AllAccountsLayout.Plan(1, Density.LARGE, 1), AllAccountsLayout.plan(cards(1), 250, 250))
        assertEquals(AllAccountsLayout.Plan(1, Density.LARGE, 2), AllAccountsLayout.plan(cards(2), 340, 400))
    }

    @Test fun fourAccountsShareTwoColumnsInATypical4x4() {
        assertEquals(AllAccountsLayout.Plan(2, Density.NORMAL, 4), AllAccountsLayout.plan(cards(4), 340, 400))
    }

    @Test fun aWideShortWidgetPutsCardsSideBySide() {
        val plan = AllAccountsLayout.plan(cards(2), 700, 230)
        assertEquals(2, plan.columns)
        assertEquals(2, plan.shown)
    }

    @Test fun aNarrowWidgetNeverSplitsIntoColumns() {
        for (count in 1..20) {
            assertEquals(1, AllAccountsLayout.plan(cards(count), 250, 500).columns)
        }
    }

    @Test fun largerTextNeedsDenserCards() {
        assertEquals(Density.LARGE, AllAccountsLayout.plan(cards(2), 340, 400, fontScale = 1f).density)
        assertEquals(Density.NORMAL, AllAccountsLayout.plan(cards(2), 340, 400, fontScale = 1.3f).density)
    }

    @Test fun aFableRowOrStatusLineMakesACardTaller() {
        for (density in Density.values()) {
            val plain = AllAccountsLayout.cardHeight(Card(), density)
            assertTrue(AllAccountsLayout.cardHeight(Card(fable = true), density) > plain)
            val status = AllAccountsLayout.cardHeight(Card(status = true), density)
            // A list entry's status shares its title's line
            if (density == Density.LIST) assertEquals(plain, status, 0f) else assertTrue(status > plain)
        }
        assertTrue(AllAccountsLayout.cardHeight(Card(), Density.LARGE) > AllAccountsLayout.cardHeight(Card(), Density.NORMAL))
        assertTrue(AllAccountsLayout.cardHeight(Card(), Density.NORMAL) > AllAccountsLayout.cardHeight(Card(), Density.COMPACT))
        assertTrue(AllAccountsLayout.cardHeight(Card(), Density.COMPACT) > AllAccountsLayout.cardHeight(Card(), Density.LIST))
    }

    @Test fun accountsThatDontFitAreLeftForMore() {
        val plan = AllAccountsLayout.plan(cards(20), 250, 250)
        assertEquals(Density.COMPACT, plan.density)
        assertTrue("${plan.shown} shown", plan.shown in 1 until 20)
    }

    @Test fun everyPlanFitsTheAccountsItShows() {
        for (style in Style.values()) for (count in 1..20) for (fable in listOf(false, true))
            for (width in 180..900 step 40) for (height in 100..900 step 40) {
                val shapes = cards(count, fable)
                val plan = AllAccountsLayout.plan(shapes, width, height, style = style)
                val where = "$style: $count accounts (fable $fable) at ${width}x$height: $plan"
                assertTrue(where, plan.columns in 1..count && plan.shown in 1..count)
                assertEquals(where, style == Style.LIST, plan.density == Density.LIST)
                // Never more columns than accounts, or the first row would have a hole in it
                assertTrue(where, plan.columns <= plan.shown)
                val gap = AllAccountsLayout.gap(style)
                val rows = (plan.shown + plan.columns - 1) / plan.columns
                val available = height - 2 * AllAccountsLayout.PADDING - 20f - (if (style == Style.LIST) 6 else 8)
                val densest = if (style == Style.LIST) Density.LIST else Density.COMPACT
                // Fable rows only go when they'd cost an account
                if (!fable) assertTrue(where, plan.fable)
                val shown = Card(fable = fable && plan.fable)
                val needed = AllAccountsLayout.cardHeight(shown, plan.density)
                if (plan.shown < count) {
                    assertEquals(where, densest, plan.density)
                } else if (available >= AllAccountsLayout.cardHeight(Card(), densest)) {
                    // Everything is shown, so it must all fit: list entries one after another,
                    // cards in rows of equal height
                    if (style == Style.LIST) assertTrue(where, rows * needed + gap * (rows - 1) <= available)
                    else assertTrue(where, (available - gap * (rows - 1)) / rows >= needed)
                }
            }
    }

    @Test fun aWidgetTooShortForAnyCardStillShowsOne() {
        assertEquals(AllAccountsLayout.Plan(1, Density.COMPACT, 1), AllAccountsLayout.plan(cards(1), 250, 90))
        assertEquals(AllAccountsLayout.Plan(1, Density.COMPACT, 1), AllAccountsLayout.plan(cards(5), 250, 90))
    }

    // ---- List style ----

    @Test fun theListFitsSixAccountsInOneColumnWhereCardsNeedTwo() {
        assertEquals(AllAccountsLayout.Plan(1, Density.LIST, 6), AllAccountsLayout.plan(cards(6), 340, 400, style = Style.LIST))
        assertEquals(2, AllAccountsLayout.plan(cards(6), 340, 400).columns)
    }

    @Test fun theListFitsOneAccountIn4x2() {
        assertEquals(AllAccountsLayout.Plan(1, Density.LIST, 1), AllAccountsLayout.plan(cards(1), 340, 110, style = Style.LIST))
    }

    @Test fun aListTooShortForTheMoreLinePutsItInTheHeader() {
        assertEquals(AllAccountsLayout.Plan(1, Density.LIST, 1, moreInHeader = true),
            AllAccountsLayout.plan(cards(2), 340, 110, style = Style.LIST))
        // With room for the line, it gets one
        assertEquals(false, AllAccountsLayout.plan(cards(12), 340, 400, style = Style.LIST).moreInHeader)
    }

    @Test fun fableRowsGoBeforeAnAccountIsCutOff() {
        // One Claude account with Fable doesn't fit a 4x2 list; without Fable it does
        assertEquals(AllAccountsLayout.Plan(1, Density.LIST, 1, fable = false),
            AllAccountsLayout.plan(cards(1, fable = true), 340, 110, style = Style.LIST))
        // Where it fits, Fable stays
        assertEquals(AllAccountsLayout.Plan(1, Density.LIST, 1),
            AllAccountsLayout.plan(cards(1, fable = true), 340, 250, style = Style.LIST))
    }

    @Test fun listEntriesOnlyTakeTheHeightTheyNeed() {
        // Three plain entries and a taller Fable one fit where four Fable-height rows wouldn't
        val mixed = listOf(Card(fable = true), Card(), Card(), Card())
        val height = 2 * AllAccountsLayout.PADDING + 20 + 6 +
            (AllAccountsLayout.cardHeight(Card(fable = true), Density.LIST) + 3 * AllAccountsLayout.cardHeight(Card(), Density.LIST) + 3).toInt() + 1
        assertEquals(AllAccountsLayout.Plan(1, Density.LIST, 4), AllAccountsLayout.plan(mixed, 340, height, style = Style.LIST))
    }

    @Test fun aWideShortListSplitsIntoColumns() {
        assertEquals(AllAccountsLayout.Plan(2, Density.LIST, 4), AllAccountsLayout.plan(cards(4), 640, 230, style = Style.LIST))
    }

    @Test fun longListsLeaveTheRestForMore() {
        val plan = AllAccountsLayout.plan(cards(12), 340, 400, style = Style.LIST)
        assertEquals(1, plan.columns)
        assertTrue("${plan.shown} shown", plan.shown in 1 until 12)
    }

    @Test fun listResetTimesNeedRoom() {
        assertTrue(AllAccountsLayout.listShowsResets(340, 1))
        assertTrue(AllAccountsLayout.listShowsResets(640, 2))
        assertFalse(AllAccountsLayout.listShowsResets(250, 1))
        assertFalse(AllAccountsLayout.listShowsResets(300, 1, fontScale = 1.5f))
    }

    @Test fun listResetTimesDropTheirPrefix() {
        assertEquals("2h 13m", AllAccountsWidgetProvider.shortReset("Resets in 2h 13m"))
        assertEquals("6d 23h", AllAccountsWidgetProvider.shortReset("Resets in 6d 23h"))
        assertEquals("Now", AllAccountsWidgetProvider.shortReset("Resets now"))
        assertEquals("Ready", AllAccountsWidgetProvider.shortReset("Ready"))
        assertEquals("--", AllAccountsWidgetProvider.shortReset(null))
    }

    // ---- Card text ----

    @Test fun cardsOnlyShowAStatusLineWhenSomethingIsWrong() {
        val prefs = MemoryPrefs()
        assertEquals("Not yet refreshed", AllAccountsWidgetProvider.statusLine(prefs, "claude"))

        prefs.edit().putString("session_pct", "12% used").putString("last_update", "Updated 3:41 PM").apply()
        assertNull(AllAccountsWidgetProvider.statusLine(prefs, "claude"))

        prefs.edit().putString("last_update", "Offline · 3:41 PM").apply()
        assertEquals("Offline · 3:41 PM", AllAccountsWidgetProvider.statusLine(prefs, "claude"))

        prefs.edit().putString("chatgpt_session_pct", "Error").putString("chatgpt_last_update", "Updated 3:41 PM")
            .putString("chatgpt_error_message", "Session expired — tap to log in").apply()
        assertEquals("Session expired — tap to log in", AllAccountsWidgetProvider.statusLine(prefs, "chatgpt"))
    }

    @Test fun cardTitlesNameTheAccountOnceAServiceHasSeveral() {
        val prefs = MemoryPrefs()
        assertEquals("Claude", AllAccountsWidgetProvider.cardTitle(prefs, "claude"))
        val second = Accounts.add(prefs, Accounts.CLAUDE)!!
        Accounts.setLabel(prefs, second, "Work")
        assertEquals("Account 1", AllAccountsWidgetProvider.cardTitle(prefs, "claude"))
        assertEquals("Work", AllAccountsWidgetProvider.cardTitle(prefs, second))
        assertEquals("ChatGPT", AllAccountsWidgetProvider.cardTitle(prefs, "chatgpt"))
    }
}
