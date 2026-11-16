package com.example.claudewidget

import android.content.SharedPreferences

/**
 * Accounts within a service. An account id is the service name for the first account
 * ("claude", "chatgpt") and "<service>-<n>" for the others ("claude-2"). Using the service
 * name for the first account means a pre-multi-account install keeps its saved session,
 * readings and settings exactly where they were, with nothing to migrate.
 *
 * The account list and labels live in the ordinary prefs; credentials stay in [SessionStore].
 */
object Accounts {
    const val CLAUDE = "claude"
    const val CHATGPT = "chatgpt"
    val SERVICES = listOf(CLAUDE, CHATGPT)

    /** Keeps PendingIntent request codes and notification ids in their own ranges (see [slot]). */
    const val MAX_PER_SERVICE = 10

    private val ID = Regex("^(claude|chatgpt)(?:-([2-9]|10))?$")
    private const val LABEL_MAX = 32

    fun isValid(id: String?): Boolean = id != null && ID.matches(id)

    fun requireValid(id: String) = require(isValid(id)) { "Invalid account" }

    fun serviceOf(id: String): String = id.substringBefore('-')

    /** 1 for a service's first account, 2..[MAX_PER_SERVICE] for the others. */
    fun number(id: String): Int = id.substringAfter('-', "1").toInt()

    fun idFor(service: String, number: Int): String = if (number == 1) service else "$service-$number"

    /** A small number unique to each possible account (0 until 2 * [MAX_PER_SERVICE]), for request codes and notification ids. */
    fun slot(id: String): Int = (number(id) - 1) * 2 + if (serviceOf(id) == CHATGPT) 1 else 0

    fun serviceName(service: String) = if (service == CHATGPT) "ChatGPT" else "Claude"

    /**
     * Prefix of an account's readings in the prefs. The first accounts keep the keys they've
     * always used: unprefixed for Claude ("session_pct"), "chatgpt_" for ChatGPT.
     */
    fun prefix(id: String): String = when (id) {
        CLAUDE -> ""
        CHATGPT -> "chatgpt_"
        else -> "${id}_"
    }

    fun errorKey(id: String): String = if (id == CLAUDE) "claude_error_message" else "${prefix(id)}error_message"

    /** Every reading key the worker writes for an account, so logout/removal can clear them all. */
    fun readingKeys(id: String): List<String> {
        val p = prefix(id)
        return listOf("session_pct", "session_reset", "session_prog", "session_reset_epoch_ms",
            "weekly_pct", "weekly_reset", "weekly_prog", "weekly_reset_epoch_ms",
            "fable_prog", "fable_reset", "last_update", "updated_at").map { p + it } + errorKey(id)
    }

    private fun listKey(service: String) = "accounts_$service"
    private fun labelKey(id: String) = "account_label_$id"
    private fun widgetKey(appWidgetId: Int) = "widget_account_$appWidgetId"

    /** The service's accounts in the order they were added. Never empty: a new install has the first account. */
    fun list(prefs: SharedPreferences, service: String): List<String> {
        val saved = prefs.getString(listKey(service), null)
            ?.split(',')
            ?.filter { isValid(it) && serviceOf(it) == service }
            ?.distinct()
        return if (saved.isNullOrEmpty()) listOf(service) else saved
    }

    fun all(prefs: SharedPreferences): List<String> = SERVICES.flatMap { list(prefs, it) }

    fun exists(prefs: SharedPreferences, id: String): Boolean =
        isValid(id) && id in list(prefs, serviceOf(id))

    /** Adds a not-yet-signed-in account and returns its id, or null when the service already has the maximum. */
    fun add(prefs: SharedPreferences, service: String): String? {
        val current = list(prefs, service)
        val number = (1..MAX_PER_SERVICE).firstOrNull { idFor(service, it) !in current } ?: return null
        val id = idFor(service, number)
        prefs.edit()
            .putString(listKey(service), (current + id).joinToString(","))
            // A reused id must not inherit an old label
            .remove(labelKey(id))
            .apply()
        return id
    }

    /**
     * Forgets the account's label, list entry and widget bindings. Its session and readings are cleared
     * separately, by the caller. The last account of a service can't be removed.
     */
    fun remove(prefs: SharedPreferences, id: String): Boolean {
        val service = serviceOf(id)
        val current = list(prefs, service)
        if (id !in current || current.size <= 1) return false
        val editor = prefs.edit()
            .putString(listKey(service), (current - id).joinToString(","))
            .remove(labelKey(id))
        prefs.all.forEach { (key, value) ->
            if (key.startsWith("widget_account_") && value == id) editor.remove(key)
        }
        editor.apply()
        return true
    }

    /** The user's name for the account, or "Account <n>". */
    fun label(prefs: SharedPreferences, id: String): String =
        prefs.getString(labelKey(id), null)?.takeIf { it.isNotBlank() } ?: "Account ${number(id)}"

    fun setLabel(prefs: SharedPreferences, id: String, label: String) {
        val clean = cleanLabel(label)
        if (clean.isEmpty()) prefs.edit().remove(labelKey(id)).apply()
        else prefs.edit().putString(labelKey(id), clean).apply()
    }

    /** One line, no control characters, and short enough for a widget title. */
    fun cleanLabel(label: String): String =
        label.filterNot { it.isISOControl() }.trim().take(LABEL_MAX).trim()

    fun hasSeveral(prefs: SharedPreferences, service: String) = list(prefs, service).size > 1

    /** "Claude" while the service has one account, "Claude · Work" once it has more. */
    fun displayName(prefs: SharedPreferences, id: String): String {
        val service = serviceOf(id)
        return if (hasSeveral(prefs, service)) "${serviceName(service)} · ${label(prefs, id)}" else serviceName(service)
    }

    /** For log lines: "Claude", "Claude #2". Never the user's label, which could contain anything. */
    fun logName(id: String): String {
        val n = number(id)
        return if (n == 1) serviceName(serviceOf(id)) else "${serviceName(serviceOf(id))} #$n"
    }

    /** The account a widget shows: the one picked for it, or the service's first account. */
    fun widgetAccount(prefs: SharedPreferences, appWidgetId: Int, service: String): String {
        val bound = prefs.getString(widgetKey(appWidgetId), null)
        return if (bound != null && serviceOf(bound) == service && exists(prefs, bound)) bound
        else list(prefs, service).first()
    }

    fun bindWidget(prefs: SharedPreferences, appWidgetId: Int, id: String) {
        prefs.edit().putString(widgetKey(appWidgetId), id).apply()
    }

    fun unbindWidgets(prefs: SharedPreferences, appWidgetIds: IntArray) {
        val editor = prefs.edit()
        appWidgetIds.forEach { editor.remove(widgetKey(it)) }
        editor.apply()
    }
}
