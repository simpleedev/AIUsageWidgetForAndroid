package com.example.claudewidget

/** Defense in depth; call sites must also avoid supplying response bodies, URLs and credentials. */
object SafeDiagnostics {
    private val bearer = Regex("(?i)Bearer\\s+[^\\s,;]+")
    private val jwt = Regex("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")
    private val credential = Regex("(?i)(sessionKey|accessToken|access_token|chatgpt_access_token|(?:__Secure-)?next-auth\\.session-token(?:\\.\\d+)?|cf_clearance|authorization|cookie)\\s*[:=]\\s*[^\\r\\n]+")

    fun redact(message: String): String = credential.replace(jwt.replace(bearer.replace(message, "[redacted]"), "[redacted]"), "$1=[redacted]")
        .replace('\r', ' ').replace('\n', ' ').take(1024)
}
