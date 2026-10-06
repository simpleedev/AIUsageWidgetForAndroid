package com.example.claudewidget

import android.content.Context
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import javax.crypto.KeyGenerator

@RunWith(AndroidJUnit4::class)
class SessionStoreTest {
    private lateinit var legacy: SharedPreferences
    private lateinit var secured: SharedPreferences
    private lateinit var store: SessionStore
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Before fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        legacy = context.getSharedPreferences("test_legacy", Context.MODE_PRIVATE)
        secured = context.getSharedPreferences("test_secure", Context.MODE_PRIVATE)
        legacy.edit().clear().commit(); secured.edit().clear().commit()
        store = SessionStore(legacy, secured, SessionCipher { key })
    }

    @Test fun migrationPreservesSessionsAndRemovesPlaintext() {
        legacy.edit().putString("saved_cookies", "sessionKey=synthetic-claude")
            .putString("user_agent", "synthetic-UA").putString("chatgpt_saved_cookies", "synthetic-chatgpt")
            .putString("chatgpt_access_token", "synthetic-token").putBoolean("ordinary_setting", true).commit()
        assertEquals("sessionKey=synthetic-claude", store.snapshot("claude").cookies)
        assertEquals("synthetic-token", store.snapshot("chatgpt").accessToken)
        assertFalse(legacy.contains("saved_cookies")); assertFalse(legacy.contains("chatgpt_access_token"))
        assertFalse(legacy.contains("chatgpt_saved_cookies")); assertFalse(legacy.contains("user_agent"))
        assertTrue(legacy.getBoolean("ordinary_setting", false))
        assertFalse(secured.all.toString().contains("synthetic-claude"))
        val restarted = SessionStore(legacy, secured, SessionCipher { key })
        assertEquals("synthetic-token", restarted.snapshot("chatgpt").accessToken)
    }

    @Test fun interruptedMigrationCleansUpRemainingLegacyCopies() {
        store.save("claude", "new-session", null, "UA")
        legacy.edit().putString("saved_cookies", "old-session").commit()
        assertEquals("new-session", store.snapshot("claude").cookies)
        assertFalse(legacy.contains("saved_cookies"))
    }

    @Test fun migrationEncryptionFailureDoesNotDestroyOldDataOrUseIt() {
        legacy.edit().putString("saved_cookies", "old-session").commit()
        val unavailable = SessionStore(legacy, secured, SessionCipher { throw IllegalStateException("key unavailable") })
        assertFalse(unavailable.snapshot("claude").isLoggedIn)
        assertEquals("old-session", legacy.getString("saved_cookies", null))
        assertFalse(secured.contains("claude"))
    }

    @Test fun migrationWriteFailureKeepsLegacyCredentials() {
        legacy.edit().putString("saved_cookies", "old-session").commit()
        val failedPrefs = object : SharedPreferences by secured {
            override fun edit(): SharedPreferences.Editor {
                val real = secured.edit()
                return object : SharedPreferences.Editor by real {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor { real.putString(key, value); return this }
                    override fun putLong(key: String?, value: Long): SharedPreferences.Editor { real.putLong(key, value); return this }
                    override fun commit() = false
                }
            }
        }
        val unavailable = SessionStore(legacy, failedPrefs, SessionCipher { key })
        assertFalse(unavailable.snapshot("claude").isLoggedIn)
        assertEquals("old-session", legacy.getString("saved_cookies", null))
    }

    @Test fun corruptionDoesNotRestoreAnOlderPlaintextSession() {
        store.save("claude", "new-session", null, "UA")
        legacy.edit().putString("saved_cookies", "old-session").commit()
        secured.edit().putString("claude", "broken!").commit()
        assertFalse(store.snapshot("claude").isLoggedIn)
        assertFalse(legacy.contains("saved_cookies")); assertFalse(secured.contains("claude"))
    }

    @Test fun missingKeyFailsClosed() {
        store.save("chatgpt", "session", "token", "UA")
        val unavailable = SessionStore(legacy, secured, SessionCipher { throw IllegalStateException("missing key") })
        assertFalse(unavailable.snapshot("chatgpt").isLoggedIn)
        assertFalse(secured.contains("chatgpt"))
    }

    @Test fun logoutRejectsStaleTokenAndQuotaWrites() {
        val old = store.save("chatgpt", "cookies", "original-token", "UA")
        store.clear("chatgpt")
        assertFalse(store.updateToken(old, "resurrected-token"))
        var wroteQuota = false
        assertFalse(store.ifCurrent(old) { wroteQuota = true })
        assertFalse(wroteQuota); assertFalse(store.isLoggedIn("chatgpt"))
        store.save("chatgpt", "new-cookies", "new-token", "UA")
        assertFalse(store.updateToken(old, "old-account-token"))
        assertEquals("new-token", store.snapshot("chatgpt").accessToken)
    }

    @Test fun loggingOutOneServicePreservesTheOther() {
        store.save("claude", "claude-session", null, "UA")
        val chat = store.save("chatgpt", "chat-session", "token", "UA")
        assertTrue(store.updateToken(chat, "refreshed-token"))
        store.clear("claude")
        assertFalse(store.isLoggedIn("claude"))
        assertEquals("refreshed-token", store.snapshot("chatgpt").accessToken)
        assertFalse(chat.toString().contains("token"))
    }

    @Test fun realAndroidKeystoreRoundTrip() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val real = SessionStore.get(context)
        val saved = real.save("chatgpt", "synthetic-cookies", "synthetic-token", "UA")
        assertEquals("synthetic-token", real.snapshot("chatgpt").accessToken)
        assertTrue(real.updateToken(saved, "synthetic-refreshed-token"))
        real.clear("chatgpt")
        assertFalse(real.isLoggedIn("chatgpt"))
    }
}
