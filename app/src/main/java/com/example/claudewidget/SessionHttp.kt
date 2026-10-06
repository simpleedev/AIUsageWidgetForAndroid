package com.example.claudewidget

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Native credential requests never follow redirects or trust page-supplied URLs. */
object SessionHttp {
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
}
