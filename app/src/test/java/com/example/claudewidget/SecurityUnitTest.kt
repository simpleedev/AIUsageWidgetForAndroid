package com.example.claudewidget

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import javax.crypto.KeyGenerator

class SecurityUnitTest {
    @Test fun hostileUrlsAreRejected() {
        val hostile = listOf("http://chatgpt.com/", "https://chatgpt.com.evil.test/", "https://evil.test/?chatgpt.com",
            "https://chatgpt.com@evil.test/", "https://evil.test@chatgpt.com/", "https://chatgpt.com:8443/",
            "javascript:alert(1)", "file:///sdcard/login.html", "content://example/login", "intent://example",
            "https://chatgpt.com\\@evil.test/", "https://chatgpt.com./")
        hostile.forEach { assertFalse(it, LoginUrlPolicy.allows("chatgpt", it)) }
    }

    @Test fun serviceAndIdentityOriginsAreScoped() {
        assertTrue(LoginUrlPolicy.allows("chatgpt", "https://auth.openai.com/u/login?code=example"))
        assertTrue(LoginUrlPolicy.allows("claude", "https://accounts.google.com/o/oauth2/auth"))
        assertTrue(LoginUrlPolicy.allows("claude", "https://claude.ai/login"))
        assertTrue(LoginUrlPolicy.allows("chatgpt", "about:blank"))
        assertFalse(LoginUrlPolicy.allows("claude", "https://chatgpt.com/"))
        assertFalse(LoginUrlPolicy.isServiceOrigin("chatgpt", "https://accounts.google.com/"))
        assertFalse(LoginUrlPolicy.isServiceOrigin("chatgpt", "https://chatgpt.com.evil.test/"))
        assertTrue(LoginUrlPolicy.isServiceOrigin("chatgpt", "https://CHATGPT.COM/"))
    }

    @Test fun verificationFramesRetainOriginIsolationWithoutExpandingMainNavigation() {
        val verification = "https://challenges.cloudflare.com/verification"
        assertFalse(LoginUrlPolicy.blocksNavigation("chatgpt", verification, false))
        assertTrue(LoginUrlPolicy.blocksNavigation("chatgpt", verification, true))
        assertTrue(LoginUrlPolicy.blocksNavigation("chatgpt", "http://challenges.cloudflare.com/", false))
        assertTrue(LoginUrlPolicy.blocksNavigation("claude", "file:///sdcard/example", false))
        assertFalse(LoginUrlPolicy.blocksNavigation("claude", "about:blank", true))
    }

    @Test fun encryptionRoundTripUsesFreshNonces() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val cipher = SessionCipher { key }
        val secret = "synthetic-cookie-and-token"
        val one = cipher.encrypt("claude", 1, secret)
        val two = cipher.encrypt("claude", 1, secret)
        assertNotEquals(one, two)
        assertEquals(secret, cipher.decrypt("claude", 1, one))
        assertFalse(String(Base64.getDecoder().decode(one)).contains(secret))
    }

    @Test fun tamperingWrongServiceRevisionAndKeyAreRejected() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val cipher = SessionCipher { key }
        val encrypted = cipher.encrypt("claude", 7, "synthetic-secret")
        val bytes = Base64.getDecoder().decode(encrypted)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { cipher.decrypt("claude", 7, Base64.getEncoder().encodeToString(bytes)) }
        assertThrows(Exception::class.java) { cipher.decrypt("chatgpt", 7, encrypted) }
        assertThrows(Exception::class.java) { cipher.decrypt("claude", 8, encrypted) }
        val other = SessionCipher { KeyGenerator.getInstance("AES").generateKey() }
        assertThrows(Exception::class.java) { other.decrypt("claude", 7, encrypted) }
        assertThrows(Exception::class.java) { cipher.decrypt("claude", 7, "invalid!") }
    }

    @Test fun redirectsCannotCarryCredentialsToAnotherServer() {
        val source = MockWebServer()
        val destination = MockWebServer()
        source.start(); destination.start()
        try {
            source.enqueue(MockResponse().setResponseCode(302).setHeader("Location", destination.url("/stolen")))
            val request = okhttp3.Request.Builder().url(source.url("/session"))
                .header("Cookie", "synthetic-session").header("Authorization", "Bearer synthetic-token").build()
            SessionHttp.client().newCall(request).execute().use { assertEquals(302, it.code) }
            assertEquals(0, destination.requestCount)
        } finally { source.shutdown(); destination.shutdown() }
    }

    @Test fun credentialPatternsAreRedacted() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjMifQ.signature"
        for (message in listOf("Bearer $jwt", "sessionKey=synthetic-secret", "accessToken: synthetic-secret",
                "Cookie: a=synthetic-secret; b=other", "__Secure-next-auth.session-token.0=synthetic-secret")) {
            val result = SafeDiagnostics.redact(message)
            assertFalse(result, result.contains("synthetic-secret"))
            assertFalse(result, result.contains(jwt))
        }
        assertEquals("HTTP 403", SafeDiagnostics.redact("HTTP 403"))
    }

    @Test fun oversizedChunkedResponsesAreRejectedBeforeParsing() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setChunkedBody("x".repeat(1024 * 1024 + 1), 4096))
            val request = okhttp3.Request.Builder().url(server.url("/oversized")).build()
            SessionHttp.client().newCall(request).execute().use { response ->
                assertThrows(IllegalArgumentException::class.java) { SessionHttp.readBody(response) }
            }
        } finally { server.shutdown() }
    }
}
