package com.example.claudewidget

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.io.IOException
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** All credential access goes through this store; quota readings/settings remain in ordinary prefs. */
class SessionStore internal constructor(
    private val legacy: SharedPreferences,
    private val secured: SharedPreferences,
    private val cipher: SessionCipher,
    private val onFailure: (Throwable) -> Unit = {}
) {
    data class Snapshot(
        val service: String,
        val revision: Long,
        val cookies: String? = null,
        val accessToken: String? = null,
        val userAgent: String? = null
    ) {
        val isLoggedIn: Boolean get() = !cookies.isNullOrEmpty() ||
            (service == "chatgpt" && !accessToken.isNullOrEmpty())

        // Never expose credentials through logs, assertions or exception messages.
        override fun toString() = "SessionSnapshot(service=$service, revision=$revision, signedIn=$isLoggedIn)"
    }

    private val lock = Any()

    fun snapshot(service: String): Snapshot = synchronized(lock) {
        requireService(service)
        try {
            readLocked(service)
        } catch (e: Exception) {
            onFailure(e)
            Snapshot(service, revision(service)) // Fail closed; never use plaintext as a fallback.
        }
    }

    fun isLoggedIn(service: String) = snapshot(service).isLoggedIn

    fun save(service: String, cookies: String?, token: String?, userAgent: String?): Snapshot = synchronized(lock) {
        requireService(service)
        val next = Snapshot(service, revision(service) + 1, cookies, token, userAgent)
        require(next.isLoggedIn) { "Empty session" }
        persistLocked(next)
        next
    }

    fun clear(service: String) = synchronized(lock) {
        requireService(service)
        clearLocked(service)
    }

    /** Prevent a request started before logout/re-login from restoring credentials or UI readings. */
    fun ifCurrent(expected: Snapshot, action: () -> Unit): Boolean = synchronized(lock) {
        val current = snapshot(expected.service)
        if (!current.isLoggedIn || current.revision != expected.revision) return@synchronized false
        action()
        true
    }

    fun updateToken(snapshot: Snapshot, token: String): Boolean = ifCurrent(snapshot) {
        persistLocked(snapshot.copy(accessToken = token))
    }

    private fun readLocked(service: String): Snapshot {
        val storedRevision = revision(service)
        val encoded = secured.getString(service, null)
        if (encoded != null) {
            // A completed migration may have crashed before the legacy cleanup.
            removeLegacy(service)
            return try {
                decode(service, storedRevision, cipher.decrypt(service, storedRevision, encoded))
            } catch (e: Exception) {
                // Corrupt records/missing keys must never resurrect an older plaintext session.
                clearLocked(service)
                onFailure(e)
                Snapshot(service, revision(service))
            }
        }
        val cookies = legacy.getString(if (service == "claude") "saved_cookies" else "chatgpt_saved_cookies", null)
        val token = if (service == "chatgpt") legacy.getString("chatgpt_access_token", null) else null
        val ua = legacy.getString(if (service == "claude") "user_agent" else "chatgpt_user_agent", null)
        val old = Snapshot(service, storedRevision + 1, cookies, token, ua)
        if (!old.isLoggedIn) return Snapshot(service, storedRevision)
        persistLocked(old) // Encrypt, durably save, verify, then erase plaintext.
        return old
    }

    private fun persistLocked(snapshot: Snapshot) {
        val json = JSONObject().apply {
            put("cookies", snapshot.cookies ?: JSONObject.NULL)
            put("token", snapshot.accessToken ?: JSONObject.NULL)
            put("userAgent", snapshot.userAgent ?: JSONObject.NULL)
        }.toString()
        val encrypted = cipher.encrypt(snapshot.service, snapshot.revision, json)
        // Check encryption before changing storage; an unavailable key preserves the old record.
        check(cipher.decrypt(snapshot.service, snapshot.revision, encrypted) == json)
        if (!secured.edit().putString(snapshot.service, encrypted)
                .putLong("${snapshot.service}_revision", snapshot.revision).commit()) {
            throw IOException("Secure session write failed")
        }
        val saved = secured.getString(snapshot.service, null) ?: throw IOException("Secure session missing")
        check(cipher.decrypt(snapshot.service, snapshot.revision, saved) == json)
        removeLegacy(snapshot.service)
    }

    private fun clearLocked(service: String) {
        // Delete legacy first, so even an interrupted logout cannot trigger migration again.
        removeLegacy(service)
        if (!secured.edit().remove(service).putLong("${service}_revision", revision(service) + 1).commit()) {
            throw IOException("Secure session removal failed")
        }
    }

    private fun removeLegacy(service: String) {
        val keys = if (service == "claude") listOf("saved_cookies", "user_agent")
            else listOf("chatgpt_saved_cookies", "chatgpt_access_token", "chatgpt_user_agent")
        if (keys.none { legacy.contains(it) }) return
        val editor = legacy.edit()
        keys.forEach { editor.remove(it) }
        if (!editor.commit()) throw IOException("Legacy session removal failed")
    }

    private fun decode(service: String, revision: Long, plaintext: String): Snapshot {
        val json = JSONObject(plaintext)
        fun value(key: String) = if (json.isNull(key)) null else json.getString(key)
        return Snapshot(service, revision, value("cookies"), value("token"), value("userAgent"))
    }

    private fun revision(service: String) = secured.getLong("${service}_revision", 0L)
    private fun requireService(service: String) = require(service == "claude" || service == "chatgpt")

    companion object {
        private const val KEY_ALIAS = "aiusagewidget.sessions.v1"
        @Volatile private var instance: SessionStore? = null

        fun get(context: Context): SessionStore = instance ?: synchronized(this) {
            instance ?: run {
                val app = context.applicationContext
                SessionStore(
                    app.getSharedPreferences("ClaudeWidgetPrefs", Context.MODE_PRIVATE),
                    app.getSharedPreferences("SecureSessionPrefs", Context.MODE_PRIVATE),
                    SessionCipher(::keystoreKey)
                ) { AppLog.w(app, "Security", "Session storage unavailable; sign-in may be required", it) }
                    .also { instance = it }
            }
        }

        private fun keystoreKey(create: Boolean): SecretKey {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
            check(create) { "Session key unavailable" }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build())
            }.generateKey()
        }
    }
}
