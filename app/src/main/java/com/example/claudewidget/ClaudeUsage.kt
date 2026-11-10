package com.example.claudewidget

import org.json.JSONObject

internal data class ClaudeUsage(val session: Limit?, val weekly: Limit?, val fable: Limit?) {
    data class Limit(val percent: Int, val resetsAt: String)

    companion object {
        fun parse(json: JSONObject): ClaudeUsage {
            var session: Limit? = null
            var weekly: Limit? = null
            var fable: Limit? = null
            var legacySession: Limit? = null
            var legacyWeekly: Limit? = null

            val limits = json.optJSONArray("limits")
            for (index in 0 until (limits?.length() ?: 0)) {
                val row = limits?.optJSONObject(index) ?: continue
                val limit = readLimit(row, "percent") ?: continue
                val scope = row.optJSONObject("scope")

                // The group is shared by the all-models and model-specific weekly windows.
                when (row.optString("kind")) {
                    "session" -> session = limit
                    "weekly_all" -> weekly = limit
                    "weekly_scoped" -> if (scope != null && isFableScope(scope)) fable = limit
                    "", "null" -> if (row.isNull("scope")) {
                        when (row.optString("group")) {
                            "session" -> legacySession = limit
                            "weekly" -> legacyWeekly = limit
                        }
                    }
                }
            }

            return ClaudeUsage(
                session ?: legacySession ?: readLimit(json.optJSONObject("five_hour"), "utilization"),
                weekly ?: legacyWeekly ?: readLimit(json.optJSONObject("seven_day"), "utilization"),
                fable
            )
        }

        private fun readLimit(row: JSONObject?, percentKey: String): Limit? {
            val percent = row?.optInt(percentKey, -1) ?: return null
            if (percent < 0) return null
            return Limit(percent, row.optString("resets_at"))
        }

        private fun isFableScope(scope: JSONObject): Boolean {
            if (!scope.isNull("surface")) return false
            val model = scope.optJSONObject("model") ?: return false
            val name = model.optString("display_name").trim()
            return name.equals("Fable", ignoreCase = true) ||
                name.startsWith("Fable ", ignoreCase = true) ||
                model.optString("id").startsWith("claude-fable-", ignoreCase = true)
        }
    }
}
