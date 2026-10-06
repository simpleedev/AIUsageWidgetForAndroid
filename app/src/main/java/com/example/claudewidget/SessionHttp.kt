package com.example.claudewidget

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Native credential requests never follow redirects or trust page-supplied URLs. */
object SessionHttp {
    private const val MAX_RESPONSE_BYTES = 1024 * 1024L
    fun client(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    fun chatGptSessionCall(client: OkHttpClient, cookies: String, userAgent: String): Call =
        client.newCall(Request.Builder()
            .url("https://chatgpt.com/api/auth/session")
            .header("Cookie", cookies)
            .header("User-Agent", userAgent)
            .header("Accept", "application/json")
            .header("Referer", "https://chatgpt.com/")
            .build())

    fun accessToken(body: String?): String? {
        if (body.isNullOrEmpty() || body.length > 1024 * 1024) return null
        val token = JSONObject(body).opt("accessToken") as? String ?: return null
        return token.takeIf { it.length in 20..32768 && it != "null" && it.none { char -> char.isWhitespace() || char.isISOControl() } }
    }

    /** Bound memory use before parsing a response, including chunked bodies without Content-Length. */
    fun readBody(response: Response): String? {
        val source = response.body?.source() ?: return null
        source.request(MAX_RESPONSE_BYTES + 1)
        require(source.buffer.size <= MAX_RESPONSE_BYTES) { "Response exceeds size limit" }
        return source.readUtf8()
    }
}
