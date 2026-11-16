package com.example.claudewidget

/**
 * Which WebView profile (cookies, web storage, cache) an account logs in with.
 *
 * Each service's first account uses the default profile, shared by Claude and ChatGPT, so one
 * Google sign-in serves both and re-login stays one tap, as before multiple accounts. Every other
 * account gets a profile of its own. Otherwise its "Continue with Google" would find the first
 * account's Google session and sign straight back into it. A separate profile also keeps that
 * account's own Google session for its re-login.
 */
internal object BrowserProfiles {
    const val PREFIX = "account_"

    /** The profile name for [account], or null for the default profile. */
    fun nameFor(account: String): String? =
        if (Accounts.number(account) == 1) null else PREFIX + account.replace('-', '_')

    /** Profiles left behind by accounts that no longer exist. Other apps' or WebView's own profiles are never listed. */
    fun orphans(existing: List<String>, accounts: List<String>): List<String> {
        val wanted = accounts.mapNotNull { nameFor(it) }.toSet()
        return existing.filter { it.startsWith(PREFIX) && it !in wanted }
    }
}
