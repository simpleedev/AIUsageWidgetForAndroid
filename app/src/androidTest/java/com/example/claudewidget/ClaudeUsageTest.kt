package com.example.claudewidget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class ClaudeUsageTest {
    @Test fun scopedWeeklyRowsDoNotReplaceTheAllModelsWindow() {
        val usage = ClaudeUsage.parse(JSONObject("""
            {
              "limits": [
                {"kind":"session", "group":"session", "percent":17},
                {"kind":"weekly_all", "group":"weekly", "percent":31,
                 "resets_at":"2026-11-16T12:00:00Z", "scope":null},
                {"kind":"weekly_scoped", "group":"weekly", "percent":0, "is_active":false,
                 "resets_at":null, "scope":{"model":{"id":null,"display_name":"Fable"}}},
                {"kind":"weekly_scoped", "group":"weekly", "percent":100,
                 "scope":{"model":{"display_name":"Opus"}}}
              ],
              "seven_day":{"utilization":57}
            }
        """.trimIndent()))

        assertEquals(17, usage.session?.percent)
        assertEquals(ClaudeUsage.Limit(31, "2026-11-16T12:00:00Z"), usage.weekly)
        assertEquals(0, usage.fable?.percent)
    }

    @Test fun readsGroupOnlyLimitsAndLegacyUtilization() {
        val usage = ClaudeUsage.parse(JSONObject("""
            {
              "limits": [{"group":"weekly", "percent":35}],
              "five_hour": {"utilization":25.0},
              "seven_day": {"utilization":45.0}
            }
        """.trimIndent()))

        assertEquals(25, usage.session?.percent)
        assertEquals(35, usage.weekly?.percent)
        assertNull(usage.fable)
        assertEquals(45, ClaudeUsage.parse(JSONObject("""{"seven_day":{"utilization":45.0}}""")).weekly?.percent)
    }

    @Test fun aScopedOnlyResponseHasNoAllModelsReading() {
        val usage = ClaudeUsage.parse(JSONObject("""
            {"limits":[{"kind":"weekly_scoped","group":"weekly","percent":100,
                        "scope":{"model":{"display_name":"Fable 5.2"}},
                        "resets_at":"2026-11-15T09:30:00Z"}]}
        """.trimIndent()))

        assertNull(usage.weekly)
        assertEquals(ClaudeUsage.Limit(100, "2026-11-15T09:30:00Z"), usage.fable)
    }

    @Test fun fableBarFitsCompactAndWideWidgets() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val prefs = context.getSharedPreferences("test_claude_widget", Context.MODE_PRIVATE)
            prefs.edit()
                .putInt("fable_prog", 24)
                .putString("fable_reset", "Resets in 6d 14h")
                .putString("usage_display", "left")
                .apply()

            for ((width, height) in listOf(110 to 110, 180 to 124, 150 to 180, 250 to 200, 250 to 210, 250 to 250, 373 to 210)) {
                val options = Bundle().apply {
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, width)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, height)
                }
                val root = ClaudeWidgetProvider.createViews(context, prefs, options).apply(context, null)
                val density = context.resources.displayMetrics.density
                val widthSpec = View.MeasureSpec.makeMeasureSpec((width * density).roundToInt(), View.MeasureSpec.EXACTLY)
                val heightSpec = View.MeasureSpec.makeMeasureSpec((height * density).roundToInt(), View.MeasureSpec.EXACTLY)
                root.measure(widthSpec, heightSpec)
                root.layout(0, 0, root.measuredWidth, root.measuredHeight)

                val fableRow = root.findViewById<View>(R.id.fable_row)
                assertEquals(View.VISIBLE, fableRow.visibility)
                assertEquals("76% left", root.findViewById<TextView>(R.id.tv_fable_pct).text.toString())
                val bar = root.findViewById<ProgressBar>(R.id.pb_fable)
                assertEquals(76, bar.progress)
                val contentBottom = root.height - root.paddingBottom
                val barBottom = fableRow.top + bar.bottom
                assertTrue("Fable bar ends at $barBottom of $contentBottom px for ${width}x$height dp",
                    bar.height > 0 && bar.bottom <= fableRow.height && barBottom <= contentBottom)

                val reset = root.findViewById<TextView>(R.id.tv_fable_reset)
                if (height >= 124) {
                    assertEquals("Resets in 6d 14h", reset.text.toString())
                    val resetBottom = fableRow.top + reset.bottom
                    assertTrue("Fable reset ends at $resetBottom of $contentBottom px for ${width}x$height dp",
                        reset.height > 0 && reset.bottom <= fableRow.height && resetBottom <= contentBottom)
                } else {
                    assertEquals(View.GONE, reset.visibility)
                }

                // Session -> Weekly and Weekly -> Fable gaps match (spacers may differ by a pixel).
                val sessionReset = root.findViewById<View>(R.id.tv_session_reset)
                val weeklyLabels = root.findViewById<View>(R.id.tv_weekly_pct).parent as View
                val weeklyReset = root.findViewById<View>(R.id.tv_weekly_reset)
                val sessionToWeekly = weeklyLabels.top - sessionReset.bottom
                val weeklyToFable = fableRow.top - weeklyReset.bottom
                assertTrue("Gaps $sessionToWeekly and $weeklyToFable px differ for ${width}x$height dp",
                    abs(sessionToWeekly - weeklyToFable) <= 1)
            }

            prefs.edit().remove("fable_prog").remove("fable_reset").apply()
            val root = ClaudeWidgetProvider.createViews(context, prefs, Bundle()).apply(context, null)
            assertEquals(View.GONE, root.findViewById<View>(R.id.fable_row).visibility)
            assertEquals(View.GONE, root.findViewById<View>(R.id.fable_gap).visibility)
        }
    }
}
