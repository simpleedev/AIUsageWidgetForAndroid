package com.example.claudewidget

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.work.WorkInfo
import androidx.work.WorkManager
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.ByteArrayInputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_TARGET_TAB = "target_tab"
        private const val EXTRA_TARGET_ACCOUNT = "target_account"
        private const val STATE_TAB = "current_tab"
        private const val NOTIFICATION_PERMISSION_REQUEST = 1001

        /** Opening the app (or the Usage tab) skips its refresh when quota was refreshed this recently. */
        private const val OPEN_REFRESH_COOLDOWN_MS = 20_000L

        /** How often the Usage tab refreshes while it's open, in seconds. 0 refreshes only on open. */
        private const val IN_APP_REFRESH_KEY = "in_app_refresh_seconds"
        private const val DEFAULT_IN_APP_REFRESH_SECONDS = 60L

        /**
         * Opens the app on [account]'s tab with that account selected. If the app is already open,
         * CLEAR_TOP + SINGLE_TOP deliver this to onNewIntent so the tab still switches, instead of the
         * app just coming to the front.
         */
        fun openAccountIntent(context: Context, account: String): Intent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_TARGET_TAB, Accounts.serviceOf(account))
                putExtra(EXTRA_TARGET_ACCOUNT, account)
            }
    }

    private lateinit var sharedPrefs: SharedPreferences
    private val sessions by lazy { SessionStore.get(this) }
    private val loginClient by lazy { SessionHttp.client() }
    private lateinit var claudeBrowser: LoginBrowser
    private lateinit var chatGptBrowser: LoginBrowser

    private var currentTab: String = "usage" // "usage", "claude", "chatgpt", or "settings"
    private var pendingNotificationAccount: String? = null
    private var resumed = false

    private val loginCheckHandler = Handler(Looper.getMainLooper())
    private val usageHandler = Handler(Looper.getMainLooper())

    /** Interval options: display label → minutes value */
    private val intervalOptions = listOf(
        "Every 15 minutes" to 15L,
        "Every 30 minutes" to 30L,
        "Every 1 hour" to 60L,
        "Every 2 hours" to 120L,
        "Every 4 hours" to 240L,
        "Never (manual only)" to 0L
    )

    /** Usage tab refresh while the app is open: display label → seconds value */
    private val inAppIntervalOptions = listOf(
        "Every 30 seconds" to 30L,
        "Every minute" to 60L,
        "Every 2 minutes" to 120L,
        "Every 5 minutes" to 300L,
        "Only when the app opens" to 0L
    )

    // ---- Usage tab state ----
    private var usageRefreshing = false
    private var usageRetrying = false
    private var lastInAppRefreshRequestMs = 0L
    private val renderUsageRunnable = Runnable { renderUsage() }
    private val usageTicker = object : Runnable {
        override fun run() {
            if (!resumed || currentTab != "usage") return
            refreshUsageIfStale()
            scheduleUsageTick()
        }
    }

    /** Redraws the Usage tab when the worker saves new readings. Held here: prefs keep listeners weakly. */
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        if (currentTab == "usage") {
            usageHandler.removeCallbacks(renderUsageRunnable)
            usageHandler.postDelayed(renderUsageRunnable, 150L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sharedPrefs = getSharedPreferences("ClaudeWidgetPrefs", Context.MODE_PRIVATE)
        if (savedInstanceState == null) {
            // The worker uses the encrypted copy. Remove leftover service sessions from the browser.
            // Not on recreation (rotation), which would throw away a login that's in progress.
            Accounts.SERVICES.forEach { service ->
                if (Accounts.list(sharedPrefs, service).any { sessions.isLoggedIn(it) }) clearSiteData(service)
            }
        }
        QuotaNotifications.restoreEnabled(this)

        // Keep the periodic refresh on the current request (constraints, backoff) after an update.
        // Logging in schedules it the first time.
        if (anyAccountLoggedIn()) {
            UpdateWidgetWorker.schedulePeriodic(this)
        }

        WebView.setWebContentsDebuggingEnabled(applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        CookieManager.getInstance().setAcceptCookie(true)

        claudeBrowser = LoginBrowser("claude", findViewById(R.id.browser_claude), findViewById(R.id.webView_claude))
        chatGptBrowser = LoginBrowser("chatgpt", findViewById(R.id.browser_chatgpt), findViewById(R.id.webView_chatgpt))

        setupTabButtons()
        findViewById<View>(R.id.btn_usage_refresh).setOnClickListener { refreshUsageNow() }

        // "Refreshing..." on the Usage tab follows the app's own refresh runs and tap-to-refresh
        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData(UpdateWidgetWorker.REFRESH_NOW_WORK)
            .observe(this) { infos ->
                usageRefreshing = infos.any { it.state == WorkInfo.State.RUNNING }
                usageRetrying = !usageRefreshing &&
                    infos.any { it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount > 0 }
                if (currentTab == "usage") updateUsageStatus()
            }

        val restoredTab = savedInstanceState?.getString(STATE_TAB)
        when {
            restoredTab != null -> switchTab(restoredTab)
            applyTarget(intent) -> Unit
            // Show usage straight away once something is connected; otherwise start with a login
            anyAccountLoggedIn() -> switchTab("usage")
            else -> switchTab("claude")
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_TAB, currentTab)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyTarget(intent)
    }

    override fun onStart() {
        super.onStart()
        sharedPrefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    override fun onStop() {
        super.onStop()
        sharedPrefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        when {
            currentTab == "usage" -> {
                // Readings may have changed while the app was in the background
                renderUsage()
                refreshUsageIfStale()
                scheduleUsageTick()
            }
            // A refresh may have failed while the app was in the background
            findViewById<View>(R.id.successLayout).visibility == View.VISIBLE -> showConnectionStatus()
        }
    }

    /**
     * Opens the tab (and account) a widget or notification asked for. Returns false when the app
     * was opened some other way. Intents from before multiple accounts name only the service.
     */
    private fun applyTarget(intent: Intent?): Boolean {
        val tab = intent?.getStringExtra(EXTRA_TARGET_TAB) ?: return false
        val service = if (tab == "chatgpt") "chatgpt" else "claude"
        val account = intent.getStringExtra(EXTRA_TARGET_ACCOUNT)
            ?.takeIf { Accounts.serviceOf(it) == service && Accounts.exists(sharedPrefs, it) }
        if (account != null) selectAccount(account)
        AppLog.i(this, "App", "Opened ${Accounts.logName(account ?: selectedAccount(service))} tab")
        switchTab(service)
        return true
    }

    private fun serviceName(service: String) = Accounts.serviceName(service)

    private fun accentColor(service: String) = if (service == "chatgpt") 0xFF10A37F.toInt() else 0xFFD4511E.toInt()

    private fun browserFor(service: String) = if (service == "chatgpt") chatGptBrowser else claudeBrowser

    private fun anyAccountLoggedIn() = Accounts.all(sharedPrefs).any { sessions.isLoggedIn(it) }

    /** The service shown on the Claude or ChatGPT tab. */
    private val currentService: String get() = if (currentTab == "chatgpt") "chatgpt" else "claude"

    /** The account selected on [currentService]'s tab. */
    private val currentAccount: String get() = selectedAccount(currentService)

    private fun selectedAccount(service: String): String {
        val saved = sharedPrefs.getString("selected_account_$service", null)
        return if (saved != null && Accounts.serviceOf(saved) == service && Accounts.exists(sharedPrefs, saved)) saved
        else Accounts.list(sharedPrefs, service).first()
    }

    private fun selectAccount(account: String) {
        sharedPrefs.edit().putString("selected_account_${Accounts.serviceOf(account)}", account).apply()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun setupTabButtons() {
        findViewById<TextView>(R.id.tab_usage).setOnClickListener { switchTab("usage") }
        findViewById<TextView>(R.id.tab_claude).setOnClickListener { switchTab("claude") }
        findViewById<TextView>(R.id.tab_chatgpt).setOnClickListener { switchTab("chatgpt") }
        findViewById<TextView>(R.id.tab_settings).setOnClickListener { switchTab("settings") }
    }

    private fun switchTab(tab: String) {
        currentTab = if (tab in listOf("usage", "claude", "chatgpt", "settings")) tab else "usage"

        val tabs = mapOf(
            "usage" to R.id.tab_usage,
            "claude" to R.id.tab_claude,
            "chatgpt" to R.id.tab_chatgpt,
            "settings" to R.id.tab_settings
        )
        tabs.forEach { (name, viewId) ->
            findViewById<TextView>(viewId).apply {
                val selected = name == currentTab
                setBackgroundColor(
                    if (selected) {
                        when (name) {
                            "claude" -> accentColor("claude")
                            "chatgpt" -> accentColor("chatgpt")
                            else -> 0xFF4A4A4A.toInt()
                        }
                    } else {
                        0xFF222222.toInt()
                    }
                )
                setTextColor(if (selected) 0xFFFFFFFF.toInt() else 0xFF888888.toInt())
            }
        }

        val serviceTab = currentTab == "claude" || currentTab == "chatgpt"
        findViewById<View>(R.id.account_bar).visibility = if (serviceTab) View.VISIBLE else View.GONE
        findViewById<View>(R.id.usageLayout).visibility = if (currentTab == "usage") View.VISIBLE else View.GONE
        findViewById<View>(R.id.settingsLayout).visibility = if (currentTab == "settings") View.VISIBLE else View.GONE
        if (!serviceTab) {
            findViewById<View>(R.id.loadingLayout).visibility = View.GONE
            findViewById<View>(R.id.successLayout).visibility = View.GONE
            claudeBrowser.frame.visibility = View.GONE
            chatGptBrowser.frame.visibility = View.GONE
        }

        usageHandler.removeCallbacks(usageTicker)
        when (currentTab) {
            "settings" -> setupSharedSettings()
            "usage" -> {
                renderUsage()
                if (resumed) {
                    refreshUsageIfStale()
                    scheduleUsageTick()
                }
            }
            else -> {
                renderAccountBar()
                showAccountContent()
            }
        }
    }

    /** The selected account's settings when it's signed in, otherwise its login page. */
    private fun showAccountContent() {
        if (sessions.isLoggedIn(currentAccount)) showSuccessScreen() else showLoginScreen()
    }

    // ---------------------------------------------------------------------------------------------
    // Usage tab
    // ---------------------------------------------------------------------------------------------

    /**
     * One card per signed-in account. Each card is the account's real widget (the same RemoteViews
     * the home screen gets), so the two always look alike. Its clicks are replaced with in-app ones.
     */
    private fun renderUsage() {
        usageHandler.removeCallbacks(renderUsageRunnable)
        val container = findViewById<LinearLayout>(R.id.usage_cards)
        container.removeAllViews()

        val accounts = Accounts.all(sharedPrefs).filter { sessions.isLoggedIn(it) }
        findViewById<View>(R.id.tv_usage_empty).visibility = if (accounts.isEmpty()) View.VISIBLE else View.GONE
        findViewById<View>(R.id.tv_usage_hint).visibility = if (accounts.isEmpty()) View.GONE else View.VISIBLE
        findViewById<View>(R.id.btn_usage_refresh).visibility = if (accounts.isEmpty()) View.GONE else View.VISIBLE

        // The size a 4x2 widget gets, so cards pick the same layout and rows the widget shows
        val widthDp = (resources.configuration.screenWidthDp - 32).coerceAtLeast(250)
        for (account in accounts) {
            val claude = Accounts.serviceOf(account) == Accounts.CLAUDE
            val hasFable = claude && sharedPrefs.getInt("${Accounts.prefix(account)}fable_prog", -1) >= 0
            val heightDp = if (hasFable) 230 else 170
            val views = if (claude) {
                ClaudeWidgetProvider.createViews(this, sharedPrefs, account, widthDp, heightDp)
            } else {
                ChatGptWidgetProvider.createViews(this, sharedPrefs, account, widthDp, heightDp)
            }
            val card = try {
                views.apply(this, container)
            } catch (e: Exception) {
                AppLog.w(this, "App", "Couldn't show the ${Accounts.logName(account)} card", e)
                continue
            }
            // A minimum rather than a fixed height: the widget's spacers fill it, and large text still fits
            card.minimumHeight = dp(heightDp)
            card.contentDescription = Accounts.displayName(sharedPrefs, account)
            card.findViewById<View>(R.id.btn_refresh)?.setOnClickListener { refreshUsageNow() }
            card.setOnClickListener {
                selectAccount(account)
                switchTab(Accounts.serviceOf(account))
            }
            container.addView(card, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) })
        }
        updateUsageStatus()
    }

    private fun updateUsageStatus() {
        val status = findViewById<TextView>(R.id.tv_usage_status)
        val last = sharedPrefs.getLong("last_successful_refresh_epoch_ms", 0L)
        status.text = when {
            usageRefreshing -> "Refreshing…"
            usageRetrying -> "Can't reach the server, trying again soon"
            last > 0L -> "Last refreshed ${SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(last))}"
            else -> "Not refreshed yet"
        }

        val seconds = inAppRefreshSeconds()
        val every = inAppIntervalOptions.firstOrNull { it.second == seconds }?.first?.lowercase(Locale.getDefault())
        findViewById<TextView>(R.id.tv_usage_hint).text = if (seconds > 0L && every != null) {
            "Tap a card to open that account. Refreshes $every while this tab is open."
        } else {
            "Tap a card to open that account. Refreshes when you open the app."
        }
    }

    private fun inAppRefreshSeconds() = sharedPrefs.getLong(IN_APP_REFRESH_KEY, DEFAULT_IN_APP_REFRESH_SECONDS)

    private fun scheduleUsageTick() {
        usageHandler.removeCallbacks(usageTicker)
        val seconds = inAppRefreshSeconds()
        if (seconds > 0L) usageHandler.postDelayed(usageTicker, seconds * 1000L)
    }

    /**
     * Refreshes unless quota was refreshed (or a refresh was asked for) in the last
     * [OPEN_REFRESH_COOLDOWN_MS], so rotating the screen or switching tabs doesn't refetch.
     * A refresh that's already running or waiting to retry is left alone.
     */
    private fun refreshUsageIfStale() {
        if (!anyAccountLoggedIn()) return
        val now = System.currentTimeMillis()
        val last = maxOf(sharedPrefs.getLong("last_successful_refresh_epoch_ms", 0L), lastInAppRefreshRequestMs)
        if (now - last < OPEN_REFRESH_COOLDOWN_MS) return
        lastInAppRefreshRequestMs = now
        UpdateWidgetWorker.refreshIfIdle(this)
    }

    /** The Refresh button: like tapping refresh on a widget, it starts over now, even while retrying. */
    private fun refreshUsageNow() {
        lastInAppRefreshRequestMs = System.currentTimeMillis()
        UpdateWidgetWorker.runNow(this)
        scheduleUsageTick()
    }

    // ---------------------------------------------------------------------------------------------
    // Accounts on the Claude and ChatGPT tabs
    // ---------------------------------------------------------------------------------------------

    private fun renderAccountBar() {
        val chips = findViewById<LinearLayout>(R.id.account_chips)
        chips.removeAllViews()
        val service = currentService
        val accounts = Accounts.list(sharedPrefs, service)
        val selected = currentAccount

        for (account in accounts) {
            val label = Accounts.label(sharedPrefs, account)
            val text = if (sessions.isLoggedIn(account)) label else "$label (signed out)"
            chips.addView(accountChip(text, account == selected, service) {
                if (account != currentAccount) {
                    selectAccount(account)
                    renderAccountBar()
                    showAccountContent()
                }
            })
        }
        if (accounts.size < Accounts.MAX_PER_SERVICE) {
            chips.addView(accountChip("+ Add account", false, service) { addAccount(service) })
        }
    }

    private fun accountChip(text: String, selected: Boolean, service: String, onClick: () -> Unit) =
        TextView(this).apply {
            this.text = text
            gravity = Gravity.CENTER
            textSize = 13f
            maxLines = 1
            setTypeface(typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
            setTextColor(if (selected) 0xFFFFFFFF.toInt() else 0xFFAAAAAA.toInt())
            setBackgroundColor(if (selected) accentColor(service) else 0xFF222222.toInt())
            setPadding(dp(14), 0, dp(14), 0)
            isSelected = selected
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(36)
            ).apply { marginEnd = dp(4) }
        }

    private fun addAccount(service: String) {
        val account = Accounts.add(sharedPrefs, service)
        if (account == null) {
            Toast.makeText(this, "You can add up to ${Accounts.MAX_PER_SERVICE} ${serviceName(service)} accounts",
                Toast.LENGTH_LONG).show()
            return
        }
        AppLog.i(this, Accounts.logName(account), "Account added")
        selectAccount(account)
        renderAccountBar()
        // A widget's title gains its account name once a service has two accounts
        updateWidgetsFor(service)
        showLoginScreen(restart = true)
    }

    /** Forgets [account] entirely: sign-in, readings, notification, name and widget choice. */
    private fun removeAccount(account: String) {
        val service = Accounts.serviceOf(account)
        if (!clearSavedSession(account)) return
        val browser = browserFor(service)
        if (browser.loginInProgress && browser.account == account) {
            browser.finish()
            clearSiteData(service)
        }
        QuotaNotifications.forget(this, account)
        Accounts.remove(sharedPrefs, account)
        AppLog.i(this, Accounts.logName(account), "Account removed")
        // Widgets that showed it fall back to the first remaining account
        updateWidgetsFor(service)
        if (currentTab == service) {
            renderAccountBar()
            showAccountContent()
        }
    }

    private fun confirmRemoveAccount(account: String) {
        val name = Accounts.displayName(sharedPrefs, account)
        AlertDialog.Builder(this)
            .setTitle("Remove $name?")
            .setMessage("Its saved sign-in and usage are deleted from this phone. Widgets showing it switch to " +
                "another ${serviceName(Accounts.serviceOf(account))} account.")
            .setPositiveButton("Remove") { _, _ -> removeAccount(account) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRenameDialog(account: String) {
        val input = EditText(this).apply {
            setText(Accounts.label(sharedPrefs, account))
            hint = "Account ${Accounts.number(account)}"
            isSingleLine = true
            filters = arrayOf(InputFilter.LengthFilter(32))
            selectAll()
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("Rename account")
            .setMessage("For example Work or Personal.")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                Accounts.setLabel(sharedPrefs, account, input.text.toString())
                updateWidgetsFor(Accounts.serviceOf(account))
                if (currentTab == Accounts.serviceOf(account)) {
                    renderAccountBar()
                    showAccountContent()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Shows the current tab's own browser, logging in to the selected account. Its page is kept when
     * switching tabs, so a half-finished login is still there when you come back. [restart] reloads
     * the login page.
     */
    private fun showLoginScreen(restart: Boolean = false) {
        findViewById<View>(R.id.loadingLayout).visibility = View.VISIBLE
        findViewById<View>(R.id.successLayout).visibility = View.GONE

        val service = currentService
        val account = currentAccount
        val browser = browserFor(service)
        claudeBrowser.frame.visibility = if (browser === claudeBrowser) View.VISIBLE else View.GONE
        chatGptBrowser.frame.visibility = if (browser === chatGptBrowser) View.VISIBLE else View.GONE

        val several = Accounts.hasSeveral(sharedPrefs, service)
        findViewById<TextView>(R.id.tv_login_instructions).text = if (several) {
            "Log in below with the ${serviceName(service)} account to save as \"${Accounts.label(sharedPrefs, account)}\". " +
                "The app will automatically save your session for the widget."
        } else {
            "Please log in to ${serviceName(service)} below. The app will automatically save your session for the widget."
        }
        findViewById<TextView>(R.id.btn_remove_pending_account).apply {
            visibility = if (several) View.VISIBLE else View.GONE
            text = "Remove \"${Accounts.label(sharedPrefs, account)}\""
            setOnClickListener { removeAccount(account) }
        }

        // The browser may be partway through another account's login. Its cookies must not be
        // saved as this account, so drop them before starting over.
        val otherAccount = browser.loginInProgress && browser.account != account
        if (otherAccount) clearSiteData(service)
        if (restart || otherAccount || !browser.loginInProgress) {
            browser.startLogin(account)
        }
    }

    private fun showSuccessScreen() {
        findViewById<View>(R.id.loadingLayout).visibility = View.GONE
        findViewById<View>(R.id.successLayout).visibility = View.VISIBLE

        val reloginBtn = findViewById<TextView>(R.id.btn_relogin)
        reloginBtn.text = "Re-login to ${Accounts.displayName(sharedPrefs, currentAccount)}"
        reloginBtn.setBackgroundColor(accentColor(currentService))

        showConnectionStatus()
        setupSettings()
    }

    /**
     * Shows "Connected!", or the last refresh error if there is one. An error is usually why a widget opened
     * the app, so the screen shouldn't claim everything is fine.
     */
    private fun showConnectionStatus() {
        val account = currentAccount
        val name = Accounts.displayName(sharedPrefs, account)
        val prefix = Accounts.prefix(account)
        val failed = sharedPrefs.getString("${prefix}session_pct", "--") == "Error"
        // Reset rows show the concise state ("Error"). Keep the actionable error reason separately
        // so this screen can still explain whether the session expired or the server failed.
        val reason = (sharedPrefs.getString(Accounts.errorKey(account), null) ?: "Error").substringBefore(" — ")

        val icon = findViewById<TextView>(R.id.tv_success_icon)
        val title = findViewById<TextView>(R.id.tv_success_title)
        val subtitle = findViewById<TextView>(R.id.tv_success_subtitle)

        if (!failed) {
            icon.text = "✓"
            icon.setTextColor(0xFF4CAF50.toInt())
            title.text = "$name Connected!"
            subtitle.text = if (Accounts.hasSeveral(sharedPrefs, currentService)) {
                "See it on the Usage tab, or add a ${serviceName(currentService)} Widget to your home screen and pick this account."
            } else {
                "See it on the Usage tab, or add the ${serviceName(currentService)} Widget to your home screen."
            }
            return
        }

        icon.text = "!"
        icon.setTextColor(0xFFFFB300.toInt())
        if (reason.contains("expired", ignoreCase = true) || reason.contains("log in", ignoreCase = true)) {
            title.text = "$name Session Expired"
            subtitle.text = "Tap Re-login below to sign in again. Open Settings > View Log for details."
        } else {
            title.text = "$name Refresh Failed"
            subtitle.text = "$reason. Tap refresh on the widget to try again, or open Settings > View Log for details."
        }
    }

    private fun showLogDialog() {
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            "?"
        }
        val header = "AI Usage Widget $version, Android ${Build.VERSION.RELEASE}. Newest first."
        val log = AppLog.read(this)
        val logText = if (log.isEmpty()) "$header\n\nNothing logged yet." else "$header\n\n$log"

        val pad = (16 * resources.displayMetrics.density).toInt()
        val textView = TextView(this).apply {
            text = logText
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(pad, pad, pad, pad)
        }

        AlertDialog.Builder(this)
            .setTitle("Log")
            .setView(ScrollView(this).apply { addView(textView) })
            .setPositiveButton("Copy") { _, _ ->
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("AI Usage Widget log", logText))
                // Android 13+ shows its own "Copied" confirmation
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Close", null)
            .setNeutralButton("Clear") { _, _ ->
                AppLog.clear(this)
                Toast.makeText(this, "Log cleared", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    /** Forgets the session and readings of [account]. Other accounts are untouched. */
    private fun clearSavedSession(account: String): Boolean {
        try {
            sessions.clear(account)
        } catch (e: Exception) {
            AppLog.e(this, "Security", "Unable to remove saved sign-in", e)
            Toast.makeText(this, "Unable to remove saved sign-in. Please try again.", Toast.LENGTH_LONG).show()
            return false
        }
        sharedPrefs.edit().apply {
            Accounts.readingKeys(account).forEach { remove(it) }
        }.apply()
        QuotaNotifications.cancel(this, account)
        updateWidgetsFor(Accounts.serviceOf(account))
        return true
    }

    /** Deletes [service]'s cookies and web storage, leaving the other service and the Google sign-in alone. */
    private fun clearSiteData(service: String) {
        if (service == "claude") {
            clearSiteData(
                urls = listOf("https://claude.ai", "https://claude.ai/", "https://api.claude.ai", "https://anthropic.com",
                    "https://auth.claude.ai", "https://console.anthropic.com", "https://platform.claude.com"),
                domains = listOf("claude.ai", ".claude.ai", "api.claude.ai", "auth.claude.ai", "anthropic.com", ".anthropic.com",
                    "console.anthropic.com", "platform.claude.com", ".claude.com"),
                knownNames = setOf(
                    "sessionKey",
                    "cf_clearance",
                    "__cf_bm",
                    "anthropic-session",
                    "claude-session",
                    "ajs_user_id",
                    "ajs_anonymous_id",
                    "intercom-id",
                    "intercom-session"
                ),
                origins = listOf("https://claude.ai", "https://api.claude.ai", "https://anthropic.com", "https://auth.claude.ai",
                    "https://console.anthropic.com", "https://platform.claude.com")
            )
        } else {
            clearSiteData(
                urls = listOf("https://chatgpt.com", "https://chatgpt.com/", "https://oaistatic.com", "https://openai.com",
                    "https://auth.openai.com", "https://auth0.openai.com"),
                domains = listOf("chatgpt.com", ".chatgpt.com", "oaistatic.com", ".oaistatic.com", "openai.com", ".openai.com",
                    "auth.openai.com", "auth0.openai.com"),
                knownNames = setOf(
                    "__Secure-next-auth.session-token",
                    "next-auth.session-token",
                    "__Host-next-auth.csrf-token",
                    "cf_clearance",
                    "__cf_bm",
                    "oai-did",
                    "oai-nav-state"
                ),
                origins = listOf("https://chatgpt.com", "https://openai.com", "https://auth.openai.com", "https://auth0.openai.com")
            )
        }
    }

    private fun clearSiteData(urls: List<String>, domains: List<String>, knownNames: Set<String>, origins: List<String>) {
        val cookieManager = CookieManager.getInstance()

        val cookieNamesToClear = mutableSetOf<String>()
        cookieNamesToClear.addAll(knownNames)

        for (url in urls) {
            val cookieStr = cookieManager.getCookie(url) ?: continue
            for (cookie in cookieStr.split(";")) {
                val name = cookie.substringBefore("=").trim()
                if (name.isNotEmpty()) {
                    cookieNamesToClear.add(name)
                }
            }
        }

        // Secure is required to delete __Secure- and __Host- cookies (like ChatGPT's session token);
        // without it the deletion is silently ignored. Every URL here is https, so it's fine for all of them.
        val expired = "Path=/; Secure; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=0"
        for (url in urls) {
            for (name in cookieNamesToClear) {
                cookieManager.setCookie(url, "$name=; $expired")
                for (domain in domains) {
                    cookieManager.setCookie(url, "$name=; Domain=$domain; $expired")
                }
            }
        }

        try {
            val webStorage = WebStorage.getInstance()
            origins.forEach { webStorage.deleteOrigin(it) }
        } catch (e: Exception) {
            AppLog.w(this, "Browser", "Failed to clear web storage", e)
        }

        cookieManager.flush()
    }

    /** Redraws [service]'s widgets and its accounts' notifications. */
    private fun updateWidgetsFor(service: String) {
        if (service == "claude") {
            ClaudeWidgetProvider.updateAllWidgets(this)
        } else {
            ChatGptWidgetProvider.updateAllWidgets(this)
        }
        Accounts.list(sharedPrefs, service).forEach { QuotaNotifications.updateAccount(this, it) }
    }

    private fun setupSharedSettings() {
        // ---- Shared auto-refresh interval ----
        val spinner = findViewById<Spinner>(R.id.spinner_interval)
        val labels = intervalOptions.map { it.first }
        val adapter = ArrayAdapter(this, R.layout.spinner_item, labels)
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        spinner.adapter = adapter

        val currentInterval = sharedPrefs.getLong("refresh_interval_minutes", 15L)
        val selectedIndex = intervalOptions.indexOfFirst { it.second == currentInterval }.coerceAtLeast(0)
        spinner.setSelection(selectedIndex)

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val newInterval = intervalOptions[position].second
                if (newInterval != sharedPrefs.getLong("refresh_interval_minutes", 15L)) {
                    UpdateWidgetWorker.rescheduleWork(this@MainActivity, newInterval)
                    AppLog.i(this@MainActivity, "Settings", "Auto refresh interval changed to ${newInterval}m")
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // ---- Usage tab refresh while the app is open ----
        val inAppSpinner = findViewById<Spinner>(R.id.spinner_in_app_interval)
        val inAppAdapter = ArrayAdapter(this, R.layout.spinner_item, inAppIntervalOptions.map { it.first })
        inAppAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        inAppSpinner.adapter = inAppAdapter
        inAppSpinner.setSelection(inAppIntervalOptions.indexOfFirst { it.second == inAppRefreshSeconds() }.coerceAtLeast(0))
        inAppSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val seconds = inAppIntervalOptions[position].second
                if (seconds != inAppRefreshSeconds()) {
                    sharedPrefs.edit().putLong(IN_APP_REFRESH_KEY, seconds).apply()
                    AppLog.i(this@MainActivity, "Settings", "Usage tab refresh interval changed to ${seconds}s")
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // ---- Shared widget tap action ----
        val tapOptions = listOf("Refresh Status" to "refresh", "Open App" to "open_app")
        val tapSpinner = findViewById<Spinner>(R.id.spinner_tap_action)
        val tapLabels = tapOptions.map { it.first }
        val tapAdapter = ArrayAdapter(this, R.layout.spinner_item, tapLabels)
        tapAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        tapSpinner.adapter = tapAdapter

        val currentTapAction = sharedPrefs.getString("tap_action", "refresh")
        val tapSelectedIndex = tapOptions.indexOfFirst { it.second == currentTapAction }.coerceAtLeast(0)
        tapSpinner.setSelection(tapSelectedIndex)

        tapSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val newAction = tapOptions[position].second
                if (newAction != sharedPrefs.getString("tap_action", "refresh")) {
                    sharedPrefs.edit().putString("tap_action", newAction).apply()
                    ClaudeWidgetProvider.updateAllWidgets(this@MainActivity)
                    ChatGptWidgetProvider.updateAllWidgets(this@MainActivity)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // ---- Best-effort refresh when the screen turns on ----
        val screenOnBox = findViewById<CheckBox>(R.id.cb_refresh_on_screen)
        screenOnBox.buttonTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(0xFFB0B0B0.toInt(), 0xFF808080.toInt())
        )
        screenOnBox.setOnCheckedChangeListener(null)
        screenOnBox.isChecked = sharedPrefs.getBoolean("refresh_on_screen_on", false)
        screenOnBox.setOnCheckedChangeListener { _, checked ->
            sharedPrefs.edit().putBoolean("refresh_on_screen_on", checked).apply()
            AppLog.i(
                this,
                "Settings",
                "Refresh when screen turns on ${if (checked) "enabled" else "disabled"}"
            )
        }

        findViewById<View>(R.id.btn_view_log).setOnClickListener {
            showLogDialog()
        }
    }

    private fun setupSettings() {
        // ---- Service-specific settings ----
        val service = currentService
        val account = currentAccount
        findViewById<TextView>(R.id.tv_service_settings_title).text = "${serviceName(service)} Settings"

        // ---- Account name ----
        val several = Accounts.hasSeveral(sharedPrefs, service)
        findViewById<TextView>(R.id.tv_account_label).text = Accounts.label(sharedPrefs, account)
        findViewById<TextView>(R.id.tv_account_name_hint).text = if (several) {
            "Shown on this account's widgets, notification and Usage card."
        } else {
            "Shown on widgets and notifications once you add another ${serviceName(service)} account."
        }
        findViewById<View>(R.id.btn_rename_account).setOnClickListener { showRenameDialog(account) }

        // ---- Usage Display Spinner (per service: applies to this tab's widget only) ----
        findViewById<TextView>(R.id.tv_usage_display_title).text = "Show ${serviceName(service)} Usage As"

        val displayOptions = listOf("Percent used" to "used", "Percent left" to "left")
        val displaySpinner = findViewById<Spinner>(R.id.spinner_usage_display)
        val displayLabels = displayOptions.map { it.first }
        val displayAdapter = ArrayAdapter(this, R.layout.spinner_item, displayLabels)
        displayAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        displaySpinner.adapter = displayAdapter

        val currentDisplay = UsageDisplay.mode(sharedPrefs, service)
        val displaySelectedIndex = displayOptions.indexOfFirst { it.second == currentDisplay }.coerceAtLeast(0)
        displaySpinner.setSelection(displaySelectedIndex)

        displaySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val newDisplay = displayOptions[position].second
                // Compare against the saved value (not currentDisplay) so switching back and forth works
                if (newDisplay != UsageDisplay.mode(sharedPrefs, service)) {
                    val linked = sharedPrefs.getBoolean(UsageDisplay.LINKED_KEY, false)
                    val services = if (linked) listOf(service, UsageDisplay.otherService(service)) else listOf(service)
                    val editor = sharedPrefs.edit()
                    services.forEach { editor.putString(UsageDisplay.prefKey(it), newDisplay) }
                    editor.apply()
                    // Widgets format from saved data, so no re-fetch is needed
                    services.forEach { updateWidgetsFor(it) }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // ---- "Use the same option for <other service>" checkbox ----
        // Both tabs read and write the same pref, so checking/unchecking on one tab is reflected on the other.
        val linkBox = findViewById<CheckBox>(R.id.cb_usage_display_linked)
        linkBox.text = "Use the same option for ${serviceName(UsageDisplay.otherService(service))}"
        linkBox.buttonTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(accentColor(service), 0xFF808080.toInt())
        )
        // setupSettings runs on every tab switch, so detach the old listener before restoring the state
        linkBox.setOnCheckedChangeListener(null)
        linkBox.isChecked = sharedPrefs.getBoolean(UsageDisplay.LINKED_KEY, false)
        linkBox.setOnCheckedChangeListener { _, checked ->
            val editor = sharedPrefs.edit().putBoolean(UsageDisplay.LINKED_KEY, checked)
            if (checked) {
                // Apply this tab's choice to the other service right away
                val other = UsageDisplay.otherService(service)
                editor.putString(UsageDisplay.prefKey(other), UsageDisplay.mode(sharedPrefs, service))
                editor.apply()
                updateWidgetsFor(other)
            } else {
                // Unlinking keeps each service's current value; they just stop syncing
                editor.apply()
            }
        }

        // ---- Ongoing quota notification (only the selected account appears on this tab) ----
        setupNotificationCheckbox(account, R.id.cb_notification_service)

        // ---- Re-login button (clears this account's session only, preserves Google account) ----
        findViewById<View>(R.id.btn_relogin).setOnClickListener {
            if (!clearSavedSession(account)) return@setOnClickListener
            clearSiteData(service)
            AppLog.i(this, Accounts.logName(account), "Re-login: cleared the saved session")
            renderAccountBar()
            showLoginScreen(restart = true)
        }

        // ---- Full Logout button (this account plus the Google sign-in) ----
        val fullLogoutBtn = findViewById<TextView>(R.id.btn_full_logout)
        fullLogoutBtn.text = "Log out of ${Accounts.displayName(sharedPrefs, account)} completely (clear Google session)"
        fullLogoutBtn.setOnClickListener {
            if (!clearSavedSession(account)) return@setOnClickListener
            clearSiteData(service)
            AppLog.i(this, Accounts.logName(account), "Full logout: cleared the saved session and all cookies")
            renderAccountBar()
            // Clearing the Google sign-in reliably takes removing every cookie. Other accounts stay
            // logged in: their widgets and tabs use the sessions saved by the app, not these cookies.
            CookieManager.getInstance().removeAllCookies {
                CookieManager.getInstance().flush()
                runOnUiThread {
                    WebStorage.getInstance().deleteAllData()
                    claudeBrowser.webView.clearCache(true)
                    chatGptBrowser.webView.clearCache(true)
                    if (currentTab == service && currentAccount == account) showLoginScreen(restart = true)
                }
            }
        }

        // ---- Remove account (only while the service has another one to fall back to) ----
        findViewById<TextView>(R.id.btn_remove_account).apply {
            visibility = if (several) View.VISIBLE else View.GONE
            text = "Remove \"${Accounts.label(sharedPrefs, account)}\" from this app"
            setOnClickListener { confirmRemoveAccount(account) }
        }
    }

    /**
     * The login browser for one service. Each service has its own WebView, popup and login check, tied to
     * that service rather than to whichever tab is showing, so the two logins can't affect each other.
     * (Cookies are shared app-wide per domain, which is what lets the Google sign-in carry over.)
     * One login runs at a time per service; [account] is the account it will be saved as.
     */
    private inner class LoginBrowser(val service: String, val frame: FrameLayout, val webView: WebView) {
        private val name = serviceName(service)

        /** The account a detected login is saved to. Set when the login page is opened. */
        var account: String = service
            private set
        private val loginUrl = if (service == "claude") "https://claude.ai/login" else "https://chatgpt.com/auth/login"
        private val pollIntervalMs = if (service == "claude") 1000L else 1500L

        /** True from opening the login page until the login is detected. Read from WebView background threads. */
        @Volatile var loginInProgress = false
            private set

        private var popup: WebView? = null
        var sessionCall: Call? = null
        private var clearHistoryOnNextPage = false

        private val pollRunnable = object : Runnable {
            override fun run() {
                if (!loginInProgress) return
                checkForLogin()
                loginCheckHandler.postDelayed(this, pollIntervalMs)
            }
        }

        init {
            applyBrowserSettings(webView)
            webView.settings.apply {
                useWideViewPort = true
                loadWithOverviewMode = true
            }

            webView.webChromeClient = object : WebChromeClient() {
                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: Message?
                ): Boolean {
                    if (!isUserGesture || !loginInProgress || resultMsg == null) return false
                    openPopup(resultMsg)
                    return true
                }

                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    // Webpage-controlled console output may contain credentials; never copy it to logs.
                    return true
                }
            }

            webView.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean =
                    request == null || LoginUrlPolicy.blocksNavigation(service, request.url.toString(), request.isForMainFrame)

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    if (request != null && LoginUrlPolicy.blocksNavigation(service, request.url.toString(), request.isForMainFrame)) return blockedResource()
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (clearHistoryOnNextPage) {
                        // So Back from a restarted login page doesn't return to the previous session's pages
                        clearHistoryOnNextPage = false
                        view?.clearHistory()
                    }
                    if (loginInProgress) checkForLogin()
                }

                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                    super.doUpdateVisitedHistory(view, url, isReload)
                    if (loginInProgress) checkForLogin()
                }

                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true && loginInProgress) {
                        AppLog.w(this@MainActivity, name,
                            "Login page failed to load (code ${error?.errorCode})")
                    }
                }

                override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, response: WebResourceResponse?) {
                    super.onReceivedHttpError(view, request, response)
                    if (request?.isForMainFrame == true && loginInProgress) {
                        AppLog.w(this@MainActivity, name,
                            "Login page returned HTTP ${response?.statusCode}")
                    }
                }
            }
        }

        fun startLogin(account: String) {
            this.account = account
            AppLog.i(this@MainActivity, Accounts.logName(account), "Opening the login page")
            loginInProgress = true
            sessionCall?.cancel()
            sessionCall = null
            clearHistoryOnNextPage = true
            closePopup()
            webView.loadUrl(loginUrl)
            loginCheckHandler.removeCallbacks(pollRunnable)
            loginCheckHandler.post(pollRunnable)
        }

        /** Stops the login check and unloads the site, so its scripts don't keep running hidden. */
        fun finish() {
            loginInProgress = false
            sessionCall?.cancel()
            sessionCall = null
            loginCheckHandler.removeCallbacks(pollRunnable)
            closePopup()
            webView.stopLoading()
            clearHistoryOnNextPage = true
            webView.loadUrl("about:blank")
        }

        private fun checkForLogin() {
            if (service == "claude") checkClaudeLoginCookies(this) else attemptChatGptSessionExtraction(this)
        }

        /** Returns true if there was a popup to close. */
        fun closePopup(): Boolean {
            val view = popup ?: return false
            popup = null
            try {
                frame.removeView(view)
                view.destroy()
            } catch (e: Exception) {
                AppLog.w(this@MainActivity, name, "Error removing popup WebView", e)
            }
            return true
        }

        /** Google sign-in opens in a popup window, shown on top of this service's page only. */
        private fun openPopup(resultMsg: Message?) {
            closePopup()
            val newWebView = WebView(this@MainActivity)
            popup = newWebView
            applyBrowserSettings(newWebView)

            newWebView.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean = true
                override fun onCloseWindow(window: WebView?) {
                    if (window === popup) closePopup()
                }
            }
            newWebView.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean =
                    request == null || LoginUrlPolicy.blocksNavigation(service, request.url.toString(), request.isForMainFrame)

                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    if (request != null && LoginUrlPolicy.blocksNavigation(service, request.url.toString(), request.isForMainFrame)) return blockedResource()
                    return null
                }
            }

            frame.addView(newWebView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))

            val transport = resultMsg?.obj as? WebView.WebViewTransport
            transport?.webView = newWebView
            resultMsg?.sendToTarget()
        }
    }

    /** Settings shared by the login pages and their Google sign-in popups. */
    private fun applyBrowserSettings(view: WebView) {
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            safeBrowsingEnabled = true

            val defaultAgent = userAgentString
            userAgentString = defaultAgent.replace("; wv", "")
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
    }

    private fun blockedResource() = WebResourceResponse(
        "text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private fun hasValidClaudeSessionKey(cookies: String?): Boolean {
        if (cookies.isNullOrEmpty()) return false
        val regex = Regex("""(?:^|;\s*)sessionKey=([^;]+)""")
        val match = regex.find(cookies) ?: return false
        val value = match.groupValues[1].trim()
        return value.isNotEmpty() && value != "deleted" && value != "\"\"" && value != "null"
    }

    private fun checkClaudeLoginCookies(browser: LoginBrowser) {
        if (!browser.loginInProgress) return
        val cookies = CookieManager.getInstance().getCookie("https://claude.ai")
        if (hasValidClaudeSessionKey(cookies)) {
            val userAgent = browser.webView.settings.userAgentString
            completeLogin(browser, cookies, null, userAgent)
        }
    }

    private fun attemptChatGptSessionExtraction(browser: LoginBrowser) {
        if (!browser.loginInProgress || browser.sessionCall != null ||
            !LoginUrlPolicy.isServiceOrigin("chatgpt", browser.webView.url)) return
        val cookies = CookieManager.getInstance().getCookie("https://chatgpt.com") ?: return
        val userAgent = browser.webView.settings.userAgentString
        val call = SessionHttp.chatGptSessionCall(loginClient, cookies, userAgent)
        browser.sessionCall = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = completed(call, null)
            override fun onResponse(call: Call, response: Response) {
                val token = response.use {
                    try { if (it.isSuccessful) SessionHttp.accessToken(SessionHttp.readBody(it)) else null }
                    catch (_: Exception) { null }
                }
                completed(call, token)
            }
            private fun completed(call: Call, token: String?) {
                runOnUiThread {
                    if (browser.sessionCall !== call) return@runOnUiThread
                    browser.sessionCall = null
                    if (browser.loginInProgress && !call.isCanceled() && token != null) {
                        completeLogin(browser, cookies, token, userAgent)
                    }
                }
            }
        })
    }

    /**
     * Saves a detected login and starts a refresh. Also drops the service's last error, so the new login
     * doesn't show as failed (on the widget or the Connected screen) before that refresh finishes.
     */
    private fun completeLogin(browser: LoginBrowser, cookies: String?, token: String?, userAgent: String) {
        val account = browser.account
        try {
            sessions.save(account, cookies, token, userAgent)
        } catch (e: Exception) {
            AppLog.e(this, "Security", "Unable to securely save sign-in", e)
            browser.finish()
            Toast.makeText(this, "Unable to securely save sign-in. Please try again.", Toast.LENGTH_LONG).show()
            return
        }
        browser.finish()
        clearSiteData(browser.service)
        browser.webView.clearCache(true)

        val prefix = Accounts.prefix(account)
        val editor = sharedPrefs.edit()
        if (sharedPrefs.getString("${prefix}session_pct", null) == "Error") {
            editor.remove("${prefix}session_pct")
                .remove("${prefix}session_reset")
                .remove("${prefix}weekly_pct")
                .remove("${prefix}weekly_reset")
                .remove(Accounts.errorKey(account))
        }
        editor.apply()
        CookieManager.getInstance().flush()
        AppLog.i(this, Accounts.logName(account), "Login detected, session saved")

        if (currentTab == browser.service) {
            renderAccountBar()
            if (currentAccount == account) showSuccessScreen()
        }
        UpdateWidgetWorker.enqueueWork(this)
    }

    private fun setupNotificationCheckbox(account: String, viewId: Int) {
        val box = findViewById<CheckBox>(viewId)
        box.text = "${Accounts.displayName(sharedPrefs, account)} ongoing notification"
        box.buttonTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(accentColor(Accounts.serviceOf(account)), 0xFF808080.toInt())
        )
        box.setOnCheckedChangeListener(null)
        box.isChecked = QuotaNotifications.isEnabled(this, account)
        box.setOnCheckedChangeListener { _, checked ->
            if (!checked) {
                if (pendingNotificationAccount == account) pendingNotificationAccount = null
                QuotaNotifications.setEnabled(this, account, false)
                return@setOnCheckedChangeListener
            }

            if (QuotaNotifications.canPostNotifications(this)) {
                QuotaNotifications.setEnabled(this, account, true)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Do not persist the opt-in until Android actually grants notification permission.
                pendingNotificationAccount = account
                requestPermissions(
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_PERMISSION_REQUEST
                )
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != NOTIFICATION_PERMISSION_REQUEST) return

        val account = pendingNotificationAccount?.takeIf { Accounts.exists(sharedPrefs, it) }
        pendingNotificationAccount = null
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED

        if (account != null && granted) {
            QuotaNotifications.setEnabled(this, account, true)
        } else if (account != null) {
            QuotaNotifications.setEnabled(this, account, false)
            Toast.makeText(this, "Notification permission is required for ongoing quota status", Toast.LENGTH_LONG).show()
        }
        if (findViewById<View>(R.id.successLayout).visibility == View.VISIBLE) {
            // Re-bind from the persisted state so a denied permission immediately returns
            // the attempted checkbox to OFF.
            setupSettings()
        }
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        usageHandler.removeCallbacks(usageTicker)
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        super.onDestroy()
        loginCheckHandler.removeCallbacksAndMessages(null)
        usageHandler.removeCallbacksAndMessages(null)
        claudeBrowser.finish()
        chatGptBrowser.finish()
        claudeBrowser.webView.destroy()
        chatGptBrowser.webView.destroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val loginShowing = findViewById<View>(R.id.loadingLayout).visibility == View.VISIBLE
        val browser = browserFor(currentService)
        if (loginShowing && browser.closePopup()) {
            return
        }
        if (loginShowing && browser.webView.canGoBack()) {
            browser.webView.goBack()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }
}
