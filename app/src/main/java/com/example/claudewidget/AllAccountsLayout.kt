package com.example.claudewidget

import kotlin.math.max

/**
 * How the All Accounts widgets arrange their accounts at a given size: how many columns, how much
 * detail each account shows, and how many accounts fit. It works from dp estimates of the layouts
 * rather than measuring them, because a widget's views are only laid out in the launcher. The
 * estimates are deliberately generous; AllAccountsWidgetTest checks them against real layouts.
 *
 * Densities are tried roomiest first. For each, one column is tried first, then more if the widget
 * is wide enough, and the first arrangement where every account gets the height it needs wins.
 * Cards share the height equally; list entries each get what they need, plus an equal share of
 * what's left. When even the densest doesn't fit them all, Fable rows are dropped (as the Claude
 * widget does when it's short), and then as many accounts as fit are shown, followed by "+N more".
 */
internal object AllAccountsLayout {

    enum class Style {
        /** Each account on its own card (all_accounts_card*.xml), 8dp apart. */
        CARDS,
        /** Every account in one list (all_accounts_line.xml), a line between each. */
        LIST
    }

    enum class Density {
        /** Cards styled like the 4x2 Claude and ChatGPT widgets, for one or two accounts. */
        LARGE,
        /** Cards with smaller text and thinner bars, with reset times. */
        NORMAL,
        /** NORMAL cards without the reset times. */
        COMPACT,
        /** List entries with 12dp bars and larger text, for a few accounts. */
        LIST_LARGE,
        /** List entries with 8dp bars. */
        LIST_MEDIUM,
        /** The densest list entries, with 4dp bars: a title line and one line per limit. */
        LIST
    }

    /** What one account must fit: a Fable row (Claude, when reported) and a status line (offline or an error). */
    data class Card(val fable: Boolean = false, val status: Boolean = false)

    /**
     * [shown] is less than the number of accounts when the rest are summarized as "+N more", which
     * goes in the header instead of a line of its own when [moreInHeader] (a very short widget).
     * [fable] is false when the Fable rows are left out to make room.
     */
    data class Plan(
        val columns: Int,
        val density: Density,
        val shown: Int,
        val fable: Boolean = true,
        val moreInHeader: Boolean = false
    )

    // Sizes from all_accounts_widget.xml, in dp
    const val PADDING = 12
    private const val HEADER = 20
    private const val MORE_GAP = 6
    private const val MORE_TEXT_SP = 10f

    /** Space between cards, and the line between list entries. */
    fun gap(style: Style) = if (style == Style.LIST) 1 else 8

    /** Space between the header and the accounts. */
    private fun headerGap(style: Style) = if (style == Style.LIST) 6 else 8

    private fun densities(style: Style) =
        if (style == Style.LIST) listOf(Density.LIST_LARGE, Density.LIST_MEDIUM, Density.LIST)
        else listOf(Density.LARGE, Density.NORMAL, Density.COMPACT)

    /** Text line height per sp, with font padding off. Roboto needs about 1.17; the rest is margin for other fonts. */
    private const val LINE = 1.25f

    /** Room for padding plus a row's label and percentage ("Session", "100% used") side by side, in dp. */
    private fun minCellWidth(density: Density, fontScale: Float) = when (density) {
        Density.LARGE -> 28 + 172 * fontScale
        Density.NORMAL, Density.COMPACT -> 20 + 105 * fontScale
        // Padding and a short bar, plus the label and percentage
        Density.LIST_LARGE -> 20 + 189 * fontScale
        Density.LIST_MEDIUM -> 20 + 174 * fontScale
        Density.LIST -> 20 + 160 * fontScale
    }

    /** The height [card] needs at [density], in dp, with [fontScale] applied to its text. */
    fun cardHeight(card: Card, density: Density, fontScale: Float = 1f): Float {
        val metrics = if (card.fable) 3 else 2
        fun text(sp: Float) = sp * LINE * fontScale
        return when (density) {
            // all_accounts_card_large.xml
            Density.LARGE -> 2 * 14f +                                   // padding
                max(22f, text(16f)) +                                    // icon and title
                (if (card.status) 2f + text(10f) else 0f) +
                metrics * (text(13f) + 4f + 8f + 2f + text(10f)) +       // label, bar, reset
                metrics * 8f                                             // minimum gap above each row
            // all_accounts_card.xml
            Density.NORMAL, Density.COMPACT -> 2 * 8f +
                max(14f, text(12f)) +
                (if (card.status) 1f + text(9f) else 0f) +
                metrics * (text(11f) + 3f + 4f +
                    (if (density == Density.NORMAL) 1f + text(9f) else 0f)) +
                metrics * 4f
            // all_accounts_line*.xml: the status shares the title's line, and the bar its row's.
            // Their spacers only take what's left over, so they need nothing here. The padding is
            // about half the gap above each row, so two entries' padding make one such gap.
            Density.LIST_LARGE -> 2 * 3f + max(18f, text(15f)) + metrics * (6f + max(12f, text(13f)))
            Density.LIST_MEDIUM -> 2 * 2f + max(16f, text(13f)) + metrics * (4f + max(8f, text(12f)))
            Density.LIST -> 2 * 1f + max(14f, text(12f)) + metrics * (2f + max(4f, text(11f)))
        }
    }

    /**
     * Whether list entries at [density], [columns] across a [widthDp]-wide widget, have room for
     * the reset time at the end of each row (all_accounts_line*.xml: label, bar, percentage, reset).
     */
    fun listShowsResets(widthDp: Int, columns: Int, density: Density, fontScale: Float = 1f): Boolean {
        val cell = (widthDp - 2 * PADDING - gap(Style.LIST) * (columns - 1)) / columns.toFloat()
        // The label, percentage and reset columns' minimum widths
        val text = when (density) {
            Density.LIST_LARGE -> 56 + 68 + 54
            Density.LIST_MEDIUM -> 52 + 62 + 50
            else -> 48 + 58 + 46
        }
        // Padding, the shortest useful bar, and the margins between the columns
        return cell >= 8 + 70 + 18 + text * fontScale
    }

    fun plan(cards: List<Card>, widthDp: Int, heightDp: Int, fontScale: Float = 1f, style: Style = Style.CARDS): Plan {
        val (plan, fits) = arrange(cards, widthDp, heightDp, fontScale, style)
        if (cards.none { it.fable } || (fits && plan.shown == cards.size)) return plan
        // Fable rows are the first thing to go, before an account is cut off or left for "+N more"
        val (withoutFable, fitsWithout) = arrange(cards.map { it.copy(fable = false) }, widthDp, heightDp, fontScale, style)
        val better = withoutFable.shown > plan.shown || (withoutFable.shown == plan.shown && fitsWithout && !fits)
        return if (better) withoutFable.copy(fable = false) else plan
    }

    /** The plan for [cards] as they are, and whether its accounts really fit (the first always shows). */
    private fun arrange(cards: List<Card>, widthDp: Int, heightDp: Int, fontScale: Float, style: Style): Pair<Plan, Boolean> {
        val count = cards.size
        val all = densities(style)
        if (count == 0) return Plan(1, all.first(), 0) to true

        val gap = gap(style)
        val width = widthDp - 2 * PADDING
        val height = heightDp - 2 * PADDING - max(HEADER.toFloat(), 15f * LINE * fontScale) - headerGap(style)

        fun maxColumns(density: Density) =
            ((width + gap) / (minCellWidth(density, fontScale) + gap)).toInt().coerceIn(1, count)

        /** Whether the first [shown] accounts, [columns] across, fit in [available] dp. */
        fun fits(density: Density, columns: Int, shown: Int, available: Float): Boolean {
            val rows = cards.take(shown).chunked(columns)
            val heights = rows.map { row -> row.maxOf { cardHeight(it, density, fontScale) } }
            val gaps = gap * (rows.size - 1)
            // Cards share the height equally, so every row must fit the tallest card
            return if (style == Style.LIST) heights.sum() + gaps <= available
                else (available - gaps) / rows.size >= heights.max()
        }

        for (density in all) {
            // Too narrow for even one column of these; the densest is used regardless
            if (density != all.last() && minCellWidth(density, fontScale) > width) continue
            for (columns in 1..maxColumns(density)) {
                if (fits(density, columns, count, height)) return Plan(columns, density, count) to true
            }
        }

        // Too many to show: fill as many of the densest rows as fit above "+N more"
        val density = all.last()
        val columns = maxColumns(density)
        val available = height - MORE_GAP - MORE_TEXT_SP * LINE * fontScale
        var shown = columns
        while (shown < count && fits(density, columns, minOf(count, shown + columns), available)) {
            shown = minOf(count, shown + columns)
        }
        if (fits(density, columns, shown, available)) return Plan(columns, density, shown) to true
        // Room for one row but not the "+N more" line too: say it in the header
        val fitsAlone = fits(density, columns, shown, height)
        return Plan(columns, density, shown, moreInHeader = fitsAlone) to fitsAlone
    }
}
