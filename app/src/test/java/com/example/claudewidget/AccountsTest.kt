package com.example.claudewidget

import org.junit.Assert.*
import org.junit.Test
import javax.crypto.KeyGenerator

class AccountsTest {

    @Test fun firstAccountsKeepTheirPreMultiAccountKeys() {
        assertEquals("", Accounts.prefix("claude"))
        assertEquals("chatgpt_", Accounts.prefix("chatgpt"))
        assertEquals("claude_error_message", Accounts.errorKey("claude"))
        assertEquals("chatgpt_error_message", Accounts.errorKey("chatgpt"))
        assertEquals("claude_notification_enabled", QuotaNotifications.prefKey("claude"))
        assertEquals("chatgpt_notification_enabled", QuotaNotifications.prefKey("chatgpt"))
        // Request codes and notification ids of the first accounts are unchanged (4101/4102 etc.)
        assertEquals(0, Accounts.slot("claude"))
        assertEquals(1, Accounts.slot("chatgpt"))
    }

    @Test fun otherAccountsGetTheirOwnKeys() {
        assertEquals("claude-2_", Accounts.prefix("claude-2"))
        assertEquals("chatgpt-3_error_message", Accounts.errorKey("chatgpt-3"))
        assertEquals("claude-2_notification_enabled", QuotaNotifications.prefKey("claude-2"))
        assertTrue(Accounts.readingKeys("claude-2").all { it.startsWith("claude-2_") })
        // No account's keys may overlap another's
        val all = (1..Accounts.MAX_PER_SERVICE).flatMap { n ->
            Accounts.SERVICES.flatMap { Accounts.readingKeys(Accounts.idFor(it, n)) }
        }
        assertEquals(all.size, all.toSet().size)
    }

    @Test fun slotsAreUniqueAndStayInRange() {
        val ids = Accounts.SERVICES.flatMap { s -> (1..Accounts.MAX_PER_SERVICE).map { Accounts.idFor(s, it) } }
        val slots = ids.map { Accounts.slot(it) }
        assertEquals(ids.size, slots.toSet().size)
        // Notification ids (4101 + slot) and request codes (4201 + slot, ...) must not run into the next range
        assertTrue(slots.all { it in 0 until 100 })
    }

    @Test fun onlyKnownAccountIdsAreValid() {
        listOf("claude", "chatgpt", "claude-2", "chatgpt-10").forEach { assertTrue(it, Accounts.isValid(it)) }
        listOf(null, "", "claude-1", "claude-11", "claude-0", "gemini", "claude-2x", "claude--2", "../claude",
            "Claude", "claude-02").forEach { assertFalse("$it", Accounts.isValid(it)) }
    }

    @Test fun newInstallHasOneAccountPerService() {
        val prefs = MemoryPrefs()
        assertEquals(listOf("claude"), Accounts.list(prefs, "claude"))
        assertEquals(listOf("claude", "chatgpt"), Accounts.all(prefs))
        assertFalse(Accounts.hasSeveral(prefs, "claude"))
        assertEquals("Claude", Accounts.displayName(prefs, "claude"))
    }

    @Test fun addingUsesTheLowestFreeNumberUpToTheLimit() {
        val prefs = MemoryPrefs()
        assertEquals("claude-2", Accounts.add(prefs, "claude"))
        assertEquals("claude-3", Accounts.add(prefs, "claude"))
        assertTrue(Accounts.remove(prefs, "claude-2"))
        assertEquals("claude-2", Accounts.add(prefs, "claude"))
        assertEquals(listOf("claude", "claude-3", "claude-2"), Accounts.list(prefs, "claude"))
        // ChatGPT's list is separate
        assertEquals(listOf("chatgpt"), Accounts.list(prefs, "chatgpt"))

        while (Accounts.list(prefs, "claude").size < Accounts.MAX_PER_SERVICE) assertNotNull(Accounts.add(prefs, "claude"))
        assertNull(Accounts.add(prefs, "claude"))
    }

    @Test fun theLastAccountCantBeRemovedButTheFirstCan() {
        val prefs = MemoryPrefs()
        assertFalse(Accounts.remove(prefs, "chatgpt"))
        Accounts.add(prefs, "chatgpt")
        assertTrue(Accounts.remove(prefs, "chatgpt"))
        assertEquals(listOf("chatgpt-2"), Accounts.list(prefs, "chatgpt"))
        // With the first account gone, the next one added takes its id (and its unprefixed-style keys)
        assertEquals("chatgpt", Accounts.add(prefs, "chatgpt"))
    }

    @Test fun labelsAreCleanedAndForgottenOnRemoval() {
        val prefs = MemoryPrefs()
        val id = Accounts.add(prefs, "claude")!!
        assertEquals("Account 2", Accounts.label(prefs, id))
        Accounts.setLabel(prefs, id, "  Work\n\u0000 ")
        assertEquals("Work", Accounts.label(prefs, id))
        assertEquals("Claude · Work", Accounts.displayName(prefs, id))
        assertEquals("Claude · Account 1", Accounts.displayName(prefs, "claude"))
        Accounts.setLabel(prefs, id, "x".repeat(100))
        assertEquals(32, Accounts.label(prefs, id).length)
        Accounts.setLabel(prefs, id, "   ")
        assertEquals("Account 2", Accounts.label(prefs, id))

        Accounts.setLabel(prefs, id, "Work")
        Accounts.remove(prefs, id)
        assertEquals("claude-2", Accounts.add(prefs, "claude"))
        assertEquals("Account 2", Accounts.label(prefs, "claude-2"))
    }

    @Test fun logNamesNeverIncludeUserLabels() {
        val prefs = MemoryPrefs()
        Accounts.setLabel(prefs, "claude", "sessionKey=secret")
        assertEquals("Claude", Accounts.logName("claude"))
        assertEquals("ChatGPT #4", Accounts.logName("chatgpt-4"))
    }

    @Test fun widgetsFallBackToTheFirstAccount() {
        val prefs = MemoryPrefs()
        val second = Accounts.add(prefs, "claude")!!
        assertEquals("claude", Accounts.widgetAccount(prefs, 7, "claude"))
        Accounts.bindWidget(prefs, 7, second)
        assertEquals(second, Accounts.widgetAccount(prefs, 7, "claude"))
        // A binding for the other service, or to a removed account, is ignored
        Accounts.bindWidget(prefs, 8, "chatgpt")
        assertEquals("claude", Accounts.widgetAccount(prefs, 8, "claude"))
        Accounts.remove(prefs, second)
        assertEquals("claude", Accounts.widgetAccount(prefs, 7, "claude"))
        assertFalse(prefs.contains("widget_account_7"))
        Accounts.bindWidget(prefs, 9, "claude")
        Accounts.unbindWidgets(prefs, intArrayOf(9))
        assertFalse(prefs.contains("widget_account_9"))
    }

    @Test fun corruptAccountListsAreIgnored() {
        val prefs = MemoryPrefs()
        prefs.edit().putString("accounts_claude", "chatgpt-2,../x,claude-3,claude-3,").apply()
        assertEquals(listOf("claude-3"), Accounts.list(prefs, "claude"))
        prefs.edit().putString("accounts_claude", ",,").apply()
        assertEquals(listOf("claude"), Accounts.list(prefs, "claude"))
    }

    // ---- SessionStore with several accounts ----

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private fun store(legacy: MemoryPrefs = MemoryPrefs(), secured: MemoryPrefs = MemoryPrefs()) =
        SessionStore(legacy, secured, SessionCipher { key })

    @Test fun accountsOfOneServiceAreIsolated() {
        val secured = MemoryPrefs()
        val store = store(secured = secured)
        store.save("claude", "first", null, "UA")
        val second = store.save("claude-2", "second", null, "UA")
        assertEquals("first", store.snapshot("claude").cookies)
        assertEquals("second", store.snapshot("claude-2").cookies)
        assertEquals("claude", second.service)

        store.clear("claude")
        assertFalse(store.isLoggedIn("claude"))
        assertEquals("second", store.snapshot("claude-2").cookies)
        assertTrue(store.ifCurrent(second) {})
        assertFalse(secured.values.values.any { it is String && (it.contains("first") || it.contains("second")) })
    }

    @Test fun oneAccountsRecordCantBeReadAsAnother() {
        val secured = MemoryPrefs()
        val store = store(secured = secured)
        store.save("chatgpt-2", "cookies", "token", "UA")
        // Copy the encrypted record (and revision) to another account's slot
        secured.edit().putString("chatgpt-3", secured.getString("chatgpt-2", null))
            .putLong("chatgpt-3_revision", secured.getLong("chatgpt-2_revision", 0L)).commit()
        assertFalse(store.isLoggedIn("chatgpt-3"))
        assertEquals("token", store.snapshot("chatgpt-2").accessToken)
    }

    @Test fun reusedAccountIdRejectsTheOldAccountsWrites() {
        val store = store()
        val old = store.save("claude-2", "removed-account", null, "UA")
        store.clear("claude-2")
        store.save("claude-2", "new-account", null, "UA")
        var wrote = false
        assertFalse(store.ifCurrent(old) { wrote = true })
        assertFalse(wrote)
        assertEquals("new-account", store.snapshot("claude-2").cookies)
    }

    @Test fun onlyFirstAccountsMigrateLegacyPlaintext() {
        val legacy = MemoryPrefs()
        legacy.edit().putString("saved_cookies", "legacy-claude").commit()
        val store = store(legacy = legacy)
        assertFalse(store.isLoggedIn("claude-2"))
        assertEquals("legacy-claude", legacy.getString("saved_cookies", null))
        assertEquals("legacy-claude", store.snapshot("claude").cookies)
        assertFalse(legacy.contains("saved_cookies"))
    }

    @Test fun invalidAccountIdsAreRejected() {
        val store = store()
        assertThrows(IllegalArgumentException::class.java) { store.snapshot("claude-11") }
        assertThrows(IllegalArgumentException::class.java) { store.save("other", "c", null, null) }
    }
}
