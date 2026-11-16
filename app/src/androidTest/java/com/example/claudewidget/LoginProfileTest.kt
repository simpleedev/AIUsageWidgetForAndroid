package com.example.claudewidget

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.LinearLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.Profile
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class LoginProfileTest {
    @Test fun anotherAccountsLoginCantSeeTheFirstAccountsGoogleSession() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        var supported = false
        instrumentation.runOnMainSync { supported = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) }
        assumeTrue("This WebView has no profiles; the shared-jar fallback applies instead", supported)

        val prefs = context.getSharedPreferences("ClaudeWidgetPrefs", Context.MODE_PRIVATE)
        val sessions = SessionStore.get(context)
        sessions.clear("claude")
        sessions.clear("chatgpt")
        prefs.edit().clear().putLong("refresh_interval_minutes", 0L).commit()
        val second = Accounts.add(prefs, "claude")!!

        // A Google session in the default profile, as the first account's login leaves it
        val googleUrl = "https://accounts.google.com"
        val planted = CountDownLatch(1)
        instrumentation.runOnMainSync {
            CookieManager.getInstance().setCookie(googleUrl, "SID=synthetic-first-account; Secure; Path=/") { planted.countDown() }
        }
        assertTrue(planted.await(10, TimeUnit.SECONDS))

        val activity = instrumentation.startActivitySync(MainActivity.openAccountIntent(context, second)) as MainActivity
        try {
            instrumentation.runOnMainSync {
                assertTrue(CookieManager.getInstance().getCookie(googleUrl).orEmpty().contains("synthetic-first-account"))

                val view = activity.findViewById<WebView>(R.id.webView_claude)
                val profile = WebViewCompat.getProfile(view)
                assertEquals(BrowserProfiles.nameFor(second), profile.name)
                assertFalse(profile.cookieManager.getCookie(googleUrl).orEmpty().contains("synthetic-first-account"))

                // Switching back to the first account goes back to the shared default profile
                activity.findViewById<LinearLayout>(R.id.account_chips).getChildAt(0).performClick()
                val first = activity.findViewById<WebView>(R.id.webView_claude)
                assertNotSame(view, first)
                assertEquals(Profile.DEFAULT_PROFILE_NAME, WebViewCompat.getProfile(first).name)
            }
        } finally {
            instrumentation.runOnMainSync {
                activity.finish()
                CookieManager.getInstance().removeAllCookies(null)
            }
            prefs.edit().clear().commit()
            instrumentation.runOnMainSync {
                // If its WebView is still shutting down, the app deletes it as an orphan on its next start
                try { ProfileStore.getInstance().deleteProfile(BrowserProfiles.nameFor(second)!!) } catch (_: IllegalStateException) {}
            }
        }
    }
}
