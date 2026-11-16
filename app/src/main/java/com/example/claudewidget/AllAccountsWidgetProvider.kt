package com.example.claudewidget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import androidx.core.os.BundleCompat
import com.example.claudewidget.AllAccountsLayout.Density
import com.example.claudewidget.AllAccountsLayout.Style
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One large widget (4x4 to start) showing every signed-in Claude and ChatGPT account, the same
 * accounts the app's Usage tab shows: each on its own card here, or all in one compact list in
 * [AllAccountsListWidgetProvider]. [AllAccountsLayout] decides how they're arranged at each size.
 * There's no account to pick, so unlike the other widgets these have no configure activity.
 */
open class AllAccountsWidgetProvider : AppWidgetProvider() {

    /** How this provider's widgets show the accounts. */
    internal open val style: Style get() = Style.CARDS

    companion object {
        /** RemoteViews accepts at most 16 sized layouts. */
        private const val MAX_SIZED_VIEWS = 16

        /** The size of a new 4x4 widget (70n - 30 dp) until the launcher reports one. */
        private const val DEFAULT_SIZE_DP = 250

        private fun providerFor(style: Style) =
            if (style == Style.LIST) AllAccountsListWidgetProvider::class.java else AllAccountsWidgetProvider::class.java

        /**
         * Redraws every All Accounts widget, in both styles. [status] (like "Refreshing...") replaces
         * the time of the last refresh in the header; the readings stay, so the widget never goes blank.
         */
        fun updateAllWidgets(context: Context, status: String? = null) {
            val mgr = AppWidgetManager.getInstance(context)
            for (style in Style.values()) {
                val ids = mgr.getAppWidgetIds(ComponentName(context, providerFor(style)))
                // Nothing to draw, so don't read every account's session
                if (ids.isNotEmpty()) draw(context, mgr, ids, style, status)
            }
        }

        fun showRefreshingState(context: Context, status: String = "Refreshing...") =
            updateAllWidgets(context, status)

        private fun draw(context: Context, mgr: AppWidgetManager, ids: IntArray, style: Style, status: String? = null) {
            val prefs = context.getSharedPreferences("ClaudeWidgetPrefs", Context.MODE_PRIVATE)
            val sessions = SessionStore.get(context)
            val accounts = Accounts.all(prefs).filter { sessions.isLoggedIn(it) }
            for (id in ids) {
                mgr.updateAppWidget(id, createViews(context, prefs, accounts, mgr.getAppWidgetOptions(id), status, style))
            }
        }

        internal fun createViews(
            context: Context,
            prefs: SharedPreferences,
            accounts: List<String>,
            options: Bundle,
            status: String? = null,
            style: Style = Style.CARDS
        ): RemoteViews {
            // Android 12+ launchers list every size the widget is shown at (typically portrait and
            // landscape), and each gets its own arrangement.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val sizes = BundleCompat.getParcelableArrayList(
                    options, AppWidgetManager.OPTION_APPWIDGET_SIZES, SizeF::class.java
                )?.distinct()
                if (!sizes.isNullOrEmpty() && sizes.size <= MAX_SIZED_VIEWS) {
                    return RemoteViews(sizes.associateWith {
                        createViews(context, prefs, accounts, it.width.toInt(), it.height.toInt(), status, style)
                    })
                }
            }
            // Older launchers give the portrait width and landscape height as the minimums, and the
            // landscape width and portrait height as the maximums. Height decides how many accounts
            // fit, so lay out each orientation rather than using the smallest of both.
            fun option(key: String) = options.getInt(key, 0).takeIf { it > 0 }
            val minWidth = option(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) ?: DEFAULT_SIZE_DP
            val minHeight = option(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT) ?: DEFAULT_SIZE_DP
            val maxWidth = option(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)
            val maxHeight = option(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
            if (maxWidth == null || maxHeight == null) {
                return createViews(context, prefs, accounts, minWidth, minHeight, status, style)
            }
            return RemoteViews(
                createViews(context, prefs, accounts, maxWidth, minHeight, status, style),
                createViews(context, prefs, accounts, minWidth, maxHeight, status, style)
            )
        }

        /**
         * The widget for [accounts] at [widthDp] x [heightDp].
         *
         * A launcher can reapply a new update onto the views it already shows when the layout is the
         * same, which keeps anything the update doesn't set. So every visibility is set either way,
         * and both containers are emptied before rows are added.
         */
        internal fun createViews(
            context: Context,
            prefs: SharedPreferences,
            accounts: List<String>,
            widthDp: Int,
            heightDp: Int,
            status: String? = null,
            style: Style = Style.CARDS
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.all_accounts_widget)
            views.setTextViewText(R.id.tv_all_status, status ?: lastRefreshed(prefs))
            views.removeAllViews(R.id.all_accounts_cards)
            views.removeAllViews(R.id.all_accounts_lines)

            val empty = accounts.isEmpty()
            val list = if (style == Style.LIST) R.id.all_accounts_lines else R.id.all_accounts_cards
            views.setViewVisibility(R.id.all_accounts_cards,
                if (!empty && list == R.id.all_accounts_cards) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.all_accounts_lines,
                if (!empty && list == R.id.all_accounts_lines) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.tv_all_empty, if (empty) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.btn_all_refresh, if (empty) View.GONE else View.VISIBLE)
            if (empty) {
                views.setViewVisibility(R.id.tv_all_more, View.GONE)
                views.setOnClickPendingIntent(R.id.all_root, WidgetClicks.openUsage(context))
                return views
            }

            // Any account's refresh refreshes them all
            val refresh = WidgetClicks.refresh(context, accounts.first())
            views.setOnClickPendingIntent(R.id.btn_all_refresh, refresh)
            views.setOnClickPendingIntent(R.id.all_root,
                if (WidgetClicks.opensApp(prefs)) WidgetClicks.openUsage(context) else refresh)

            val fontScale = context.resources.configuration.fontScale
            val plan = AllAccountsLayout.plan(
                accounts.map { AllAccountsLayout.Card(fable = fableUsed(prefs, it) != null, status = statusLine(prefs, it) != null) },
                widthDp, heightDp, fontScale, style
            )
            val listResets = AllAccountsLayout.listShowsResets(widthDp, plan.columns, fontScale)
            val rowLayout = if (style == Style.LIST) R.layout.all_accounts_line_row else R.layout.all_accounts_row
            val rowId = if (style == Style.LIST) R.id.all_accounts_line_row else R.id.all_accounts_row
            // A short last row's entries share its whole width, so no row has a hole in it
            for (rowAccounts in accounts.take(plan.shown).chunked(plan.columns)) {
                val row = RemoteViews(context.packageName, rowLayout)
                for (account in rowAccounts) {
                    val entry = if (style == Style.LIST) createLine(context, prefs, account, listResets, plan.fable)
                        else createCard(context, prefs, account, plan.density, plan.fable)
                    row.addView(rowId, entry)
                }
                views.addView(list, row)
            }

            val hidden = accounts.size - plan.shown
            val moreLine = hidden > 0 && !plan.moreInHeader
            views.setViewVisibility(R.id.tv_all_more, if (moreLine) View.VISIBLE else View.GONE)
            if (moreLine) {
                views.setTextViewText(R.id.tv_all_more, "+$hidden more · tap to see all")
                views.setOnClickPendingIntent(R.id.tv_all_more, WidgetClicks.openUsage(context))
            } else if (hidden > 0) {
                views.setTextViewText(R.id.tv_all_status, "+$hidden more · ${status ?: lastRefreshed(prefs)}")
            }
            return views
        }

        private fun createCard(
            context: Context,
            prefs: SharedPreferences,
            account: String,
            density: Density,
            showFable: Boolean
        ): RemoteViews {
            val layout = if (density == Density.LARGE) R.layout.all_accounts_card_large else R.layout.all_accounts_card
            val card = RemoteViews(context.packageName, layout)
            val fable = bindAccount(context, card, prefs, account, resets = density != Density.COMPACT,
                shortResets = false, showFable = showFable)
            card.setViewVisibility(R.id.fable_gap, if (fable) View.VISIBLE else View.GONE)
            return card
        }

        private fun createLine(
            context: Context,
            prefs: SharedPreferences,
            account: String,
            resets: Boolean,
            showFable: Boolean
        ): RemoteViews {
            val line = RemoteViews(context.packageName, R.layout.all_accounts_line)
            bindAccount(context, line, prefs, account, resets, shortResets = true, showFable = showFable)
            return line
        }

        /**
         * Fills in one account's card or list entry, which share their ids. [resets] shows the time
         * to each limit's reset; [shortResets] drops its "Resets in" for the list's narrow column.
         * [showFable] false leaves out the Fable row to make room. Returns whether it shows one.
         */
        private fun bindAccount(
            context: Context,
            views: RemoteViews,
            prefs: SharedPreferences,
            account: String,
            resets: Boolean,
            shortResets: Boolean,
            showFable: Boolean
        ): Boolean {
            val service = Accounts.serviceOf(account)
            val p = Accounts.prefix(account)

            views.setImageViewResource(R.id.iv_card_icon,
                if (service == Accounts.CHATGPT) R.drawable.ic_chatgpt else R.drawable.ic_claude)
            views.setTextViewText(R.id.tv_title, cardTitle(prefs, account))
            views.setContentDescription(R.id.card_root, Accounts.displayName(prefs, account))
            views.setOnClickPendingIntent(R.id.card_root, WidgetClicks.tap(context, prefs, account))

            val status = statusLine(prefs, account)
            views.setViewVisibility(R.id.tv_last_update, if (status != null) View.VISIBLE else View.GONE)
            views.setTextViewText(R.id.tv_last_update, status.orEmpty())

            val showLeft = UsageDisplay.mode(prefs, service) == "left"
            val resetVisibility = if (resets) View.VISIBLE else View.GONE
            fun reset(key: String, default: String): String {
                val text = prefs.getString(key, null)
                return if (shortResets) shortReset(text) else text ?: default
            }
            UsageDisplay.bind(views, R.id.tv_session_pct, R.id.pb_session,
                prefs.getString("${p}session_pct", "--"), prefs.getInt("${p}session_prog", 0), showLeft)
            UsageDisplay.bind(views, R.id.tv_weekly_pct, R.id.pb_weekly,
                prefs.getString("${p}weekly_pct", "--"), prefs.getInt("${p}weekly_prog", 0), showLeft)
            views.setTextViewText(R.id.tv_session_reset, reset("${p}session_reset", "Tap refresh"))
            views.setTextViewText(R.id.tv_weekly_reset, reset("${p}weekly_reset", "Tap refresh"))
            views.setViewVisibility(R.id.tv_session_reset, resetVisibility)
            views.setViewVisibility(R.id.tv_weekly_reset, resetVisibility)

            val fable = fableUsed(prefs, account)?.takeIf { showFable }
            views.setViewVisibility(R.id.fable_row, if (fable != null) View.VISIBLE else View.GONE)
            if (fable != null) {
                UsageDisplay.bind(views, R.id.tv_fable_pct, R.id.pb_fable, "$fable% used", fable, showLeft)
                views.setTextViewText(R.id.tv_fable_reset, reset("${p}fable_reset", "Unknown"))
                views.setViewVisibility(R.id.tv_fable_reset, resetVisibility)
            }
            return fable != null
        }

        /**
         * The service's name, or the account's name once the service has several ("Work"). The
         * icon already says which service it is, and in a narrow card "Claude · Work" would be cut
         * off before the part that tells the accounts apart.
         */
        internal fun cardTitle(prefs: SharedPreferences, account: String): String {
            val service = Accounts.serviceOf(account)
            return if (Accounts.hasSeveral(prefs, service)) Accounts.label(prefs, account)
                else Accounts.serviceName(service)
        }

        /** A saved reset text without its "Resets in", for the list's narrow column: "2h 13m", "Now", "Ready". */
        internal fun shortReset(text: String?): String = when {
            text == null -> "--"
            text.startsWith("Resets in ") -> text.removePrefix("Resets in ")
            text == "Resets now" -> "Now"
            else -> text
        }

        /** Weekly Fable usage, when Claude reports it for [account]. */
        private fun fableUsed(prefs: SharedPreferences, account: String): Int? =
            if (Accounts.serviceOf(account) != Accounts.CLAUDE) null
            else prefs.getInt("${Accounts.prefix(account)}fable_prog", -1).takeIf { it >= 0 }

        /**
         * What's wrong with [account]'s readings, or null when they're from the last refresh (whose
         * time is in the header): the error from its last refresh, "Offline · 3:12 PM", or that it
         * hasn't been refreshed yet.
         */
        internal fun statusLine(prefs: SharedPreferences, account: String): String? {
            val p = Accounts.prefix(account)
            if (prefs.getString("${p}session_pct", null) == "Error") {
                return prefs.getString(Accounts.errorKey(account), null) ?: "Refresh failed"
            }
            val lastUpdate = prefs.getString("${p}last_update", null) ?: return "Not yet refreshed"
            return lastUpdate.takeUnless { it.startsWith("Updated ") }
        }

        /** "Updated 3:41 PM", with the date when it wasn't today. */
        private fun lastRefreshed(prefs: SharedPreferences): String {
            val last = prefs.getLong("last_successful_refresh_epoch_ms", 0L)
            if (last <= 0L) return "Not yet refreshed"
            val pattern = if (DateUtils.isToday(last)) "h:mm a" else "MMM d, h:mm a"
            return "Updated " + SimpleDateFormat(pattern, Locale.getDefault()).format(Date(last))
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        draw(context, appWidgetManager, appWidgetIds, style)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        // Resizing changes how many accounts fit and how they're arranged
        draw(context, appWidgetManager, intArrayOf(appWidgetId), style)
    }
}

/** The All Accounts widget as one compact list: no cards, gaps or padding between accounts. */
class AllAccountsListWidgetProvider : AllAccountsWidgetProvider() {
    override val style: Style get() = Style.LIST
}
