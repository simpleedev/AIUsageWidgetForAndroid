package com.example.claudewidget

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class WebViewSecurityTest {
    @Test fun loginViewsBlockMixedContentLocalFilesAndNativeBridges() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val finished = CountDownLatch(1)
        var bridge: String? = null
        try {
            instrumentation.runOnMainSync {
                for (id in listOf(R.id.webView_claude, R.id.webView_chatgpt)) {
                    val view = activity.findViewById<WebView>(id)
                    assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW, view.settings.mixedContentMode)
                    assertFalse(view.settings.allowFileAccess)
                    assertFalse(view.settings.allowContentAccess)
                    assertFalse(view.settings.javaScriptCanOpenWindowsAutomatically)
                    assertTrue(view.settings.safeBrowsingEnabled)
                }
                val chat = activity.findViewById<WebView>(R.id.webView_chatgpt)
                chat.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        view.evaluateJavascript("typeof window.AndroidBridge") { bridge = it; finished.countDown() }
                    }
                }
                chat.loadDataWithBaseURL("https://chatgpt.com", "<html><body>Security fixture</body></html>", "text/html", "UTF-8", null)
            }
            assertTrue(finished.await(10, TimeUnit.SECONDS))
            assertEquals("\"undefined\"", bridge)
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun refreshReceiverIsPrivateAndPendingIntentsAreImmutable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val info = context.packageManager.getReceiverInfo(ComponentName(context, QuotaNotificationReceiver::class.java), 0)
        assertFalse(info.exported)
        val intent = Intent(context, QuotaNotificationReceiver::class.java).apply {
            action = QuotaNotifications.ACTION_REFRESH
            putExtra(QuotaNotifications.EXTRA_SERVICE, "claude")
        }
        val pending = PendingIntent.getBroadcast(context, 9876, intent, PendingIntent.FLAG_IMMUTABLE)
        assertEquals(context.packageName, pending.creatorPackage)
        if (Build.VERSION.SDK_INT >= 31) assertTrue(pending.isImmutable)
        pending.cancel()
    }

    @Test fun backupsExcludeEveryAppDataDomainForBothTransferTypes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(0, context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
        val expected = setOf("root", "file", "database", "sharedpref", "external")
        val parser = context.resources.getXml(R.xml.data_extraction_rules)
        val found = mutableMapOf<String, MutableSet<String>>()
        var group = ""
        try {
            while (parser.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "cloud-backup", "device-transfer" -> group = parser.name
                        "exclude" -> {
                            assertEquals(".", parser.getAttributeValue(null, "path"))
                            found.getOrPut(group) { mutableSetOf() }.add(parser.getAttributeValue(null, "domain"))
                        }
                    }
                }
                parser.next()
            }
        } finally { parser.close() }
        assertEquals(expected, found["cloud-backup"])
        assertEquals(expected, found["device-transfer"])
    }

    @Test fun diagnosticsNeverPersistCredentialOrExceptionContent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AppLog.clear(context)
        AppLog.e(context, "SecurityTest", "sessionKey=synthetic-log-secret", IOException("exception-secret"))
        val logged = AppLog.read(context)
        assertFalse(logged.contains("synthetic-log-secret"))
        assertFalse(logged.contains("exception-secret"))
        assertTrue(logged.contains("IOException"))
        AppLog.clear(context)
    }

    @Test fun tokenParsingRejectsNullObjectsOversizeAndHeaderInjection() {
        assertNull(SessionHttp.accessToken("{\"accessToken\":null}"))
        assertNull(SessionHttp.accessToken("{\"accessToken\":{\"fake\":true}}"))
        assertNull(SessionHttp.accessToken("{\"accessToken\":\"short\"}"))
        assertNull(SessionHttp.accessToken("{\"accessToken\":\"long-synthetic-token\\r\\nInjected\"}"))
        val token = "valid-synthetic-session-token"
        assertEquals(token, SessionHttp.accessToken("{\"accessToken\":\"$token\"}"))
    }
}
