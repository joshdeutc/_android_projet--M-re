package com.jo.selfcontrol.ultimate

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Gère la configuration des limites d'usage, stockée dans le filesDir de l'app (limits.json).
 * Les limites sont lues/écrites via l'UI de MainActivity — aucun déploiement externe nécessaire.
 *
 * Format de limits.json :
 * {
 *   "limits": [
 *     {
 *       "package": "com.instagram.android",
 *       "max_minutes_per_day": 30,
 *       "allowed_days": [0,1,2,3,4,5,6],
 *       "allowed_hours": "*"
 *     },
 *     {
 *       "package": "com.tiktok.android",
 *       "max_minutes_per_day": 15,
 *       "allowed_days": [1,3,5],
 *       "allowed_hours": "18:00-20:00"
 *     }
 *   ]
 * }
 *
 * allowed_days : 0=dimanche, 1=lundi, ..., 6=samedi. "*" = tous les jours.
 * allowed_hours : "HH:mm-HH:mm" ou "*" pour toute la journée.
 *
 * period_blocks (optionnel) — plages où les apps listées sont bloquées en priorité sur le quota journalier :
 * [
 *   { "packages": ["com.tinder.android"], "blocked_hours": "22:00-07:00" }
 * ]
 * Si l'heure de fin est avant l'heure de début, la plage passe minuit (ex. 22h → 7h).
 */
class ConfigManager {

    data class PeriodBlockRule(
        val packages: List<String>,
        val blockedStartMinutes: Int,
        val blockedEndMinutes: Int,
        val allowedDays: List<Int> = (0..6).toList(),
        val muteNotifications: Boolean = false
    )

    data class AppLimit(
        val packageName: String,
        val maxMinutesPerDay: Int,
        val maxSecondsPerDay: Int,      // limite en secondes (priorité sur minutes si défini)
        val allowedDays: List<Int>,     // 0=dim, 1=lun, ..., 6=sam
        val allowedHoursStart: Int,     // minutes depuis minuit (ex: 1080 = 18:00)
        val allowedHoursEnd: Int,       // minutes depuis minuit (ex: 1200 = 20:00)
        val allDay: Boolean,            // true si allowed_hours = "*"
        val session: SessionConfig? = null   // null = no per-unlock session limit
    )

    /**
     * Discord-style per-unlock session limit. Lower priority than curfew & daily quota.
     * Opening the app starts a session (sessionsUsed++); leaving the app or reaching
     * sessionDurationSec ends it; cooldownSec must elapse before a new session can start.
     */
    data class SessionConfig(
        val sessionDurationSec: Int,
        val cooldownSec: Int,
        val maxSessionsPerDay: Int
    )

    data class Config(
        val limits: List<AppLimit>,
        val periodBlocks: List<PeriodBlockRule> = emptyList()
    )

    companion object {
        private const val TAG = "SelfControl.Config"
        private const val LOCAL_CONFIG_NAME = "limits.json"

        /**
         * Load configuration from internal storage.
         * Missing or invalid file yields an empty config (fresh install / clean slate).
         */
        fun loadConfig(context: Context): Config {
            val localFile = File(context.filesDir, LOCAL_CONFIG_NAME)

            if (!localFile.exists()) {
                Log.i(TAG, "No config file found — using empty config")
                return Config(emptyList(), emptyList())
            }

            return try {
                val json = JSONObject(localFile.readText())
                parseConfig(json)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error parsing config: ${e.message}")
                Config(emptyList(), emptyList())
            }
        }

        /**
         * Returns the last modified timestamp of the internal config file.
         */
        fun getConfigLastModified(context: Context): Long {
            val localFile = File(context.filesDir, LOCAL_CONFIG_NAME)
            return if (localFile.exists()) localFile.lastModified() else 0L
        }

        /**
         * Full JSON (same as written to limits.json).
         */
        fun configToJsonString(config: Config): String = buildConfigJson(config).toString(2)

        /**
         * Save configuration directly to the internal storage.
         */
        fun saveConfig(context: Context, config: Config) {
            val localFile = File(context.filesDir, LOCAL_CONFIG_NAME)
            try {
                localFile.writeText(configToJsonString(config))
                Log.i(TAG, "✅ Config saved successfully")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error saving config: ${e.message}")
            }
        }

        fun fromJsonString(jsonString: String): Config? {
            return try {
                parseConfig(JSONObject(jsonString))
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error parsing config string: ${e.message}")
                null
            }
        }

        private fun buildConfigJson(config: Config): JSONObject {
            val json = JSONObject()
            val limitsArray = org.json.JSONArray()
            for (limit in config.limits) {
                val obj = JSONObject().apply {
                    put("package", limit.packageName)
                    put("max_minutes_per_day", limit.maxMinutesPerDay)
                    put("max_seconds_per_day", limit.maxSecondsPerDay)
                    put("allowed_days", if (limit.allowedDays.size == 7) "*" else org.json.JSONArray(limit.allowedDays))
                    val hours = if (limit.allDay) "*" else String.format(
                        "%02d:%02d-%02d:%02d",
                        limit.allowedHoursStart / 60, limit.allowedHoursStart % 60,
                        limit.allowedHoursEnd / 60, limit.allowedHoursEnd % 60
                    )
                    put("allowed_hours", hours)
                    limit.session?.let {
                        put("session", JSONObject().apply {
                            put("session_duration_sec", it.sessionDurationSec)
                            put("cooldown_sec", it.cooldownSec)
                            put("max_sessions_per_day", it.maxSessionsPerDay)
                        })
                    }
                }
                limitsArray.put(obj)
            }
            json.put("limits", limitsArray)
            val pbArray = org.json.JSONArray()
            for (rule in config.periodBlocks) {
                val pkgArr = org.json.JSONArray()
                for (p in rule.packages) pkgArr.put(p)
                val hours = String.format(
                    "%02d:%02d-%02d:%02d",
                    rule.blockedStartMinutes / 60, rule.blockedStartMinutes % 60,
                    rule.blockedEndMinutes / 60, rule.blockedEndMinutes % 60
                )
                pbArray.put(JSONObject().apply {
                    put("packages", pkgArr)
                    put("blocked_hours", hours)
                    put("blocked_days", if (rule.allowedDays.size == 7) "*" else org.json.JSONArray(rule.allowedDays))
                    put("mute_notifications", rule.muteNotifications)
                })
            }
            json.put("period_blocks", pbArray)
            return json
        }

        /** True si maintenant (minutes depuis minuit) est dans la plage bloquée [start, end) avec passage minuit si start > end. */
        fun isInBlockedWindow(nowMinutes: Int, start: Int, end: Int): Boolean {
            if (start == end) return false
            if (start < end) return nowMinutes >= start && nowMinutes < end
            return nowMinutes >= start || nowMinutes < end
        }

        /**
         * Relaxation of a limit (more allowed time or limit deleted) → must go through delay.
         */
        fun shouldDeferLimitsChange(old: Config?, newLimits: List<AppLimit>): Boolean {
            val oldMap = old?.limits?.associateBy { it.packageName } ?: emptyMap()
            val newMap = newLimits.associateBy { it.packageName }
            // Limit deleted → relaxation → defer
            for (pkg in oldMap.keys) {
                if (pkg !in newMap) return true
            }
            // Limit increased (more allowed time) → relaxation → defer
            for ((pkg, newLimit) in newMap) {
                val oldLimit = oldMap[pkg] ?: continue  // new app added → stricter, no defer
                if (newLimit.maxSecondsPerDay > oldLimit.maxSecondsPerDay) return true
                if (isSessionRelaxation(oldLimit.session, newLimit.session)) return true
            }
            return false
        }

        /**
         * Session limit relaxation = removed entirely, OR more session time per unlock,
         * OR less cooldown, OR more sessions per day. Any of these gives the user more
         * access than before, so must go through the delay gate (no instant escape).
         */
        private fun isSessionRelaxation(old: SessionConfig?, new: SessionConfig?): Boolean {
            if (old == null) return false                     // no prior limit → can only become stricter
            if (new == null) return true                      // removed → relaxation
            if (new.sessionDurationSec > old.sessionDurationSec) return true
            if (new.cooldownSec < old.cooldownSec) return true
            if (new.maxSessionsPerDay > old.maxSessionsPerDay) return true
            return false
        }

        /**
         * Relaxation of period blocks (fewer blocked minutes or curfew deleted) → must go through delay.
         */
        fun shouldDeferPeriodBlocksChange(old: List<PeriodBlockRule>, new: List<PeriodBlockRule>): Boolean {
            val pkgs = old.flatMap { it.packages }.toSet() union new.flatMap { it.packages }.toSet()
            for (pkg in pkgs) {
                val o = blockedMinutesInDayForPackage(old, pkg)
                val n = blockedMinutesInDayForPackage(new, pkg)
                for (m in o) {
                    if (m !in n) return true  // blocked minute removed → relaxation → defer
                }
            }
            return false
        }

        private fun blockedMinutesInDayForPackage(rules: List<PeriodBlockRule>, pkg: String): Set<Int> {
            val set = mutableSetOf<Int>()
            for (rule in rules) {
                if (pkg !in rule.packages) continue
                for (day in rule.allowedDays) {
                    addHalfOpenMinuteRange(set, day, rule.blockedStartMinutes, rule.blockedEndMinutes)
                }
            }
            return set
        }

        private fun addHalfOpenMinuteRange(set: MutableSet<Int>, day: Int, start: Int, end: Int) {
            if (start == end) return
            if (start < end) {
                for (m in start until end) set.add(day * 1440 + m)
            } else {
                for (m in start until 1440) set.add(day * 1440 + m)
                val nextDay = (day + 1) % 7
                for (m in 0 until end) set.add(nextDay * 1440 + m)
            }
        }

        private fun parseConfig(json: JSONObject): Config {
            val limitsArray = json.optJSONArray("limits") ?: org.json.JSONArray()
            val limits = mutableListOf<AppLimit>()

            for (i in 0 until limitsArray.length()) {
                val obj = limitsArray.getJSONObject(i)
                val pkg = obj.getString("package")
                val maxMin = obj.optInt("max_minutes_per_day", 0)
                val maxSec = if (obj.has("max_seconds_per_day")) {
                    obj.getInt("max_seconds_per_day")
                } else {
                    maxMin * 60
                }

                // Parse allowed_days
                val allowedDays = when (val days = obj.get("allowed_days")) {
                    is String -> if (days == "*") (0..6).toList() else listOf()
                    else -> {
                        val arr = obj.getJSONArray("allowed_days")
                        (0 until arr.length()).map { arr.getInt(it) }
                    }
                }

                // Parse allowed_hours
                val hoursStr = obj.getString("allowed_hours")
                val allDay = hoursStr == "*"
                var startMinutes = 0
                var endMinutes = 1440 // 24h

                if (!allDay && hoursStr.contains("-")) {
                    val parts = hoursStr.split("-")
                    startMinutes = parseTimeToMinutes(parts[0])
                    endMinutes = parseTimeToMinutes(parts[1])
                }

                val sessionObj = obj.optJSONObject("session")
                val sessionConfig = if (sessionObj != null) {
                    SessionConfig(
                        sessionDurationSec = sessionObj.getInt("session_duration_sec"),
                        cooldownSec = sessionObj.getInt("cooldown_sec"),
                        maxSessionsPerDay = sessionObj.getInt("max_sessions_per_day")
                    )
                } else null

                limits.add(AppLimit(
                    packageName = pkg,
                    maxMinutesPerDay = maxMin,
                    maxSecondsPerDay = maxSec,
                    allowedDays = allowedDays,
                    allowedHoursStart = startMinutes,
                    allowedHoursEnd = endMinutes,
                    allDay = allDay,
                    session = sessionConfig
                ))
            }

            val periodBlocks = mutableListOf<PeriodBlockRule>()
            val pbArr = json.optJSONArray("period_blocks")
            if (pbArr != null) {
                for (i in 0 until pbArr.length()) {
                    val obj = pbArr.getJSONObject(i)
                    val pkgsArr = obj.getJSONArray("packages")
                    val pkgs = (0 until pkgsArr.length()).map { pkgsArr.getString(it) }
                    val blockedDays = when (val days = obj.opt("blocked_days")) {
                        is String -> if (days == "*") (0..6).toList() else emptyList()
                        else -> {
                            val arr = obj.optJSONArray("blocked_days")
                            if (arr != null) {
                                (0 until arr.length()).map { arr.getInt(it) }
                            } else {
                                (0..6).toList()
                            }
                        }
                    }
                    val hoursStr = obj.getString("blocked_hours")
                    if (hoursStr != "*" && hoursStr.contains("-")) {
                        val parts = hoursStr.split("-")
                        val start = parseTimeToMinutes(parts[0])
                        val end = parseTimeToMinutes(parts[1])
                        val muteNotif = obj.optBoolean("mute_notifications", false)
                        periodBlocks.add(PeriodBlockRule(pkgs, start, end, blockedDays, muteNotif))
                    }
                }
            }

            return Config(limits, periodBlocks)
        }

        /**
         * Parse "18:30" → 1110 (minutes depuis minuit)
         */
        private fun parseTimeToMinutes(time: String): Int {
            val parts = time.trim().split(":")
            return parts[0].toInt() * 60 + parts[1].toInt()
        }
    }
}
