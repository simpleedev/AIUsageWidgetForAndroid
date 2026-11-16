package com.example.claudewidget

import org.junit.Assert.*
import org.junit.Test

class BrowserProfilesTest {
    @Test fun firstAccountsShareTheDefaultProfile() {
        assertNull(BrowserProfiles.nameFor("claude"))
        assertNull(BrowserProfiles.nameFor("chatgpt"))
    }

    @Test fun everyOtherAccountHasItsOwnProfile() {
        val ids = Accounts.SERVICES.flatMap { s -> (2..Accounts.MAX_PER_SERVICE).map { Accounts.idFor(s, it) } }
        val names = ids.map { BrowserProfiles.nameFor(it) }
        assertTrue(names.all { it != null && it.matches(Regex("[a-z0-9_]+")) })
        assertEquals(ids.size, names.toSet().size)
        assertEquals("account_claude_2", BrowserProfiles.nameFor("claude-2"))
    }

    @Test fun onlyRemovedAccountsProfilesAreOrphans() {
        val existing = listOf("Default", "account_claude_2", "account_claude_3", "account_chatgpt_2", "other")
        val accounts = listOf("claude", "claude-3", "chatgpt", "chatgpt-2")
        assertEquals(listOf("account_claude_2"), BrowserProfiles.orphans(existing, accounts))
    }
}
