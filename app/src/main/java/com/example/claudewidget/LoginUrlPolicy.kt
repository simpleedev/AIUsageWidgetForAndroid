package com.example.claudewidget

import java.net.URI

/** Applies to main-frame navigation and popups; HTTPS CDN resources may still load. */
object LoginUrlPolicy {
    private val identityHosts = setOf("accounts.google.com", "appleid.apple.com", "login.microsoftonline.com", "login.live.com")
    private val claudeHosts = setOf("claude.ai", "www.claude.ai", "auth.claude.ai", "console.anthropic.com", "platform.claude.com")
    private val chatGptHosts = setOf("chatgpt.com", "www.chatgpt.com", "auth.openai.com", "auth0.openai.com")

    fun allows(service: String, url: String): Boolean {
        if (url == "about:blank") return true
        val uri = parseHttps(url) ?: return false
        val hosts = when (service) { "claude" -> claudeHosts; "chatgpt" -> chatGptHosts; else -> return false }
        return uri.host.lowercase() in hosts || uri.host.lowercase() in identityHosts
    }

    fun isServiceOrigin(service: String, url: String?): Boolean {
        val uri = url?.let(::parseHttps) ?: return false
        return uri.host.equals(if (service == "claude") "claude.ai" else "chatgpt.com", ignoreCase = true)
    }

    fun isHttpsResource(url: String): Boolean = parseHttps(url) != null

    fun blocksNavigation(service: String, url: String, isMainFrame: Boolean): Boolean {
        if (url == "about:blank") return false
        // HTTPS verification/CDN frames retain browser origin isolation; only top-level pages
        // require the authentication-host list. There is no native bridge in any frame.
        return !isHttpsResource(url) || (isMainFrame && !allows(service, url))
    }

    private fun parseHttps(url: String): URI? = try {
        URI(url).takeIf { it.scheme.equals("https", true) && !it.host.isNullOrEmpty() &&
            it.rawUserInfo == null && (it.port == -1 || it.port == 443) }
    } catch (_: Exception) { null }
}
