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

/**
 * All credential access goes through this store; quota readings/settings remain in ordinary prefs.
 * Sessions are keyed by account id (see [Accounts]). A service's first account uses the service
 * name, so sessions saved before multiple accounts existed are read from the same record.
 */
class SessionStore internal constructor(
    private val legacy: SharedPreferences,
    private val secured: SharedPreferences,
    private val cipher: SessionCipher,
    private val onFailure: (Throwable) -> Unit = {}
) {
    data class Snapshot(
        val account: String,
        val revision: Long,
        val cookies: String? = null,
        val accessToken: String? = null,
        val userAgent: String? = null
    ) {
        val service: String get() = Accounts.serviceOf(account)

        val isLoggedIn: Boolean get() = !cookies.isNullOrEmpty() ||
            (service == "chatgpt" && !accessToken.isNullOrEmpty())

        // Never expose credentials through logs, assertions or exception messages.
        override fun toString() = "SessionSnapshot(account=$account, revision=$revision, signedIn=$isLoggedIn)"
    }

    private val lock = Any()

    fun snapshot(account: String): Snapshot = synchronized(lock) {
        Accounts.requireValid(account)
        try {
            readLocked(account)
        } catch (e: Exception) {
            onFailure(e)
            Snapshot(account, revision(account)) // Fail closed; never use plaintext as a fallback.
        }
    }

    fun isLoggedIn(account: String) = snapshot(account).isLoggedIn

    fun save(account: String, cookies: String?, token: String?, userAgent: String?): Snapshot = synchronized(lock) {
        Accounts.requireValid(account)
        val next = Snapshot(account, revision(account) + 1, cookies, token, userAgent)
        require(next.isLoggedIn) { "Empty session" }
        persistLocked(next)
        next
    }

    fun clear(account: String) = synchronized(lock) {
        Accounts.requireValid(account)
        clearLocked(account)
    }

    /** Prevent a request started before logout/re-login from restoring credentials or UI readings. */
    fun ifCurrent(expected: Snapshot, action: () -> Unit): Boolean = synchronized(lock) {
        val current = snapshot(expected.account)
        if (!current.isLoggedIn || current.revision != expected.revision) return@synchronized false
        action()
        true
    }

    fun updateToken(snapshot: Snapshot, token: String): Boolean = ifCurrent(snapshot) {
        persistLocked(snapshot.copy(accessToken = token))
    }

    private fun readLocked(account: String): Snapshot {
        val storedRevision = revision(account)
        val encoded = secured.getString(account, null)
        if (encoded != null) {
            // A completed migration may have crashed before the legacy cleanup.
            removeLegacy(account)
            return try {
                decode(account, storedRevision, cipher.decrypt(account, storedRevision, encoded))
            } catch (e: Exception) {
                // Corrupt records/missing keys must never resurrect an older plaintext session.
                clearLocked(account)
                onFailure(e)
                Snapshot(account, revision(account))
            }
        }
        // Only the first account of each service can have a pre-1.1.9 plaintext session.
        if (account != "claude" && account != "chatgpt") return Snapshot(account, storedRevision)
        val cookies = legacy.getString(if (account == "claude") "saved_cookies" else "chatgpt_saved_cookies", null)
        val token = if (account == "chatgpt") legacy.getString("chatgpt_access_token", null) else null
        val ua = legacy.getString(if (account == "claude") "user_agent" else "chatgpt_user_agent", null)
        val old = Snapshot(account, storedRevision + 1, cookies, token, ua)
        if (!old.isLoggedIn) return Snapshot(account, storedRevision)
        persistLocked(old) // Encrypt, durably save, verify, then erase plaintext.
        return old
    }

    private fun persistLocked(snapshot: Snapshot) {
        val json = JSONObject().apply {
            put("cookies", snapshot.cookies ?: JSONObject.NULL)
            put("token", snapshot.accessToken ?: JSONObject.NULL)
            put("userAgent", snapshot.userAgent ?: JSONObject.NULL)
        }.toString()
        // The account id is bound into the ciphertext, so one account's record can't be read as another's.
        val encrypted = cipher.encrypt(snapshot.account, snapshot.revision, json)
        // Check encryption before changing storage; an unavailable key preserves the old record.
        check(cipher.decrypt(snapshot.account, snapshot.revision, encrypted) == json)
        if (!secured.edit().putString(snapshot.account, encrypted)
                .putLong("${snapshot.account}_revision", snapshot.revision).commit()) {
            throw IOException("Secure session write failed")
        }
        val saved = secured.getString(snapshot.account, null) ?: throw IOException("Secure session missing")
        check(cipher.decrypt(snapshot.account, snapshot.revision, saved) == json)
        removeLegacy(snapshot.account)
    }

    private fun clearLocked(account: String) {
        // Delete legacy first, so even an interrupted logout cannot trigger migration again.
        removeLegacy(account)
        if (!secured.edit().remove(account).putLong("${account}_revision", revision(account) + 1).commit()) {
            throw IOException("Secure session removal failed")
        }
    }

    private fun removeLegacy(account: String) {
        val keys = when (account) {
            "claude" -> listOf("saved_cookies", "user_agent")
            "chatgpt" -> listOf("chatgpt_saved_cookies", "chatgpt_access_token", "chatgpt_user_agent")
            else -> return
        }
        if (keys.none { legacy.contains(it) }) return
        val editor = legacy.edit()
        keys.forEach { editor.remove(it) }
        if (!editor.commit()) throw IOException("Legacy session removal failed")
    }

    private fun decode(account: String, revision: Long, plaintext: String): Snapshot {
        val json = JSONObject(plaintext)
        fun value(key: String) = if (json.isNull(key)) null else json.getString(key)
        return Snapshot(account, revision, value("cookies"), value("token"), value("userAgent"))
    }

    private fun revision(account: String) = secured.getLong("${account}_revision", 0L)

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
