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
 *
 * install_blocks (optionnel) — groupes nommés de packages interdits à l'installation.
 * Voir `docs/INSTALL_BLOCKLIST.md`.
 * [
 *   { "name": "Navigateurs", "packages": ["com.android.chrome"], "protection_delay_sec": 2592000 }
 * ]
 *
 * protection_delay_sec (optionnel, sur n'importe quelle règle) — délai propre à cette règle,
 * PRIORITAIRE sur le délai global de DelayManager. Voir `docs/PER_RULE_PROTECTION_DELAY.md`.
 */
class ConfigManager {

    data class PeriodBlockRule(
        val packages: List<String>,
        val blockedStartMinutes: Int,
        val blockedEndMinutes: Int,
        val allowedDays: List<Int> = (0..6).toList(),
        val muteNotifications: Boolean = false,
        val protectionDelaySec: Int? = null
    ) {
        /** Signature stable au contenu — sert à retrouver une règle après édition (cf. requiredDefer). */
        fun scheduleSignature(): String =
            packages.sorted().joinToString(",") + "|$blockedStartMinutes-$blockedEndMinutes|" +
                allowedDays.sorted().joinToString(",")
    }

    data class AppLimit(
        val packageName: String,
        val maxMinutesPerDay: Int,
        val maxSecondsPerDay: Int,      // limite en secondes (priorité sur minutes si défini)
        val allowedDays: List<Int>,     // 0=dim, 1=lun, ..., 6=sam
        val allowedHoursStart: Int,     // minutes depuis minuit (ex: 1080 = 18:00)
        val allowedHoursEnd: Int,       // minutes depuis minuit (ex: 1200 = 20:00)
        val allDay: Boolean,            // true si allowed_hours = "*"
        val session: SessionConfig? = null,  // null = no per-unlock session limit
        val protectionDelaySec: Int? = null
    )

    /**
     * A named batch of packages that must never be usable on this device. Enforced by
     * [InstallBlockManager] (hide + suspend as soon as the package appears), not by a
     * per-package OS install restriction — Android has no such API. Keyed by [name].
     */
    data class InstallBlockGroup(
        val name: String,
        val packages: List<String>,
        val protectionDelaySec: Int? = null
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
        val periodBlocks: List<PeriodBlockRule> = emptyList(),
        val installBlocks: List<InstallBlockGroup> = emptyList()
    )

    /**
     * Outcome of comparing an old config to a candidate one.
     *
     * [seconds] is how long the change must wait — the MAX over every rule being relaxed,
     * each rule contributing its own `protection_delay_sec` when it has one, else the global
     * effective delay. [fromExplicitTimer] is true when the winning candidate came from a
     * per-rule timer; such a timer outranks the global delay AND the settings unlock, which
     * is the whole point of the feature (a 30-day curfew must not fall to a 1h unlock).
     */
    data class DeferRequirement(
        val seconds: Int,
        val fromExplicitTimer: Boolean,
        val reason: String
    ) {
        val mustDefer: Boolean get() = seconds > 0

        companion object {
            val NONE = DeferRequirement(0, false, "")
        }
    }

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
                    limit.protectionDelaySec?.let { put("protection_delay_sec", it) }
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
                    rule.protectionDelaySec?.let { put("protection_delay_sec", it) }
                })
            }
            json.put("period_blocks", pbArray)

            val ibArray = org.json.JSONArray()
            for (group in config.installBlocks) {
                val pkgArr = org.json.JSONArray()
                for (p in group.packages) pkgArr.put(p)
                ibArray.put(JSONObject().apply {
                    put("name", group.name)
                    put("packages", pkgArr)
                    group.protectionDelaySec?.let { put("protection_delay_sec", it) }
                })
            }
            json.put("install_blocks", ibArray)
            return json
        }

        /** True si maintenant (minutes depuis minuit) est dans la plage bloquée [start, end) avec passage minuit si start > end. */
        fun isInBlockedWindow(nowMinutes: Int, start: Int, end: Int): Boolean {
            if (start == end) return false
            if (start < end) return nowMinutes >= start && nowMinutes < end
            return nowMinutes >= start || nowMinutes < end
        }

        /**
         * How long a candidate config must wait before it can be written.
         *
         * Walks every rule that exists in [old] and asks "is the user getting more freedom
         * here?". Each relaxed rule contributes a delay: its own `protection_delay_sec` if it
         * has one, else [globalDelaySec]. The strictest candidate wins, so touching a heavily
         * protected curfew in the same save as a trivial change makes the whole save wait for
         * the curfew's timer. Save the two changes separately to avoid that.
         *
         * Returns [DeferRequirement.NONE] when the change is purely a hardening.
         */
        fun requiredDefer(old: Config?, new: Config, globalDelaySec: Int): DeferRequirement {
            if (old == null) return DeferRequirement.NONE
            val candidates = mutableListOf<DeferRequirement>()

            fun add(rulePolicy: Int?, reason: String) {
                if (rulePolicy != null) {
                    candidates.add(DeferRequirement(rulePolicy, true, reason))
                } else if (globalDelaySec > 0) {
                    candidates.add(DeferRequirement(globalDelaySec, false, reason))
                }
            }

            // ── App limits (keyed by package) ───────────────────────────────
            val oldLimits = old.limits.associateBy { it.packageName }
            val newLimits = new.limits.associateBy { it.packageName }
            for ((pkg, oldLimit) in oldLimits) {
                val newLimit = newLimits[pkg]
                if (newLimit == null) {
                    add(oldLimit.protectionDelaySec, "Limit deleted: $pkg")
                    continue
                }
                if (newLimit.maxSecondsPerDay > oldLimit.maxSecondsPerDay) {
                    add(oldLimit.protectionDelaySec, "Quota raised: $pkg")
                }
                if (isSessionRelaxation(oldLimit.session, newLimit.session)) {
                    add(oldLimit.protectionDelaySec, "Session relaxed: $pkg")
                }
                if (isTimerLowered(oldLimit.protectionDelaySec, newLimit.protectionDelaySec)) {
                    add(oldLimit.protectionDelaySec, "Protection timer lowered: $pkg")
                }
            }

            // ── Curfews (schedule compared by covered minutes, timer by signature) ──
            for (oldRule in old.periodBlocks) {
                if (losesBlockedMinutes(oldRule, new.periodBlocks)) {
                    add(oldRule.protectionDelaySec, "Curfew shortened or deleted")
                    continue
                }
                val twin = new.periodBlocks.firstOrNull {
                    it.scheduleSignature() == oldRule.scheduleSignature()
                }
                if (twin != null && isTimerLowered(oldRule.protectionDelaySec, twin.protectionDelaySec)) {
                    add(oldRule.protectionDelaySec, "Protection timer lowered: curfew")
                }
            }

            // ── Install blocklist groups (keyed by name) ────────────────────
            val newGroups = new.installBlocks.associateBy { it.name }
            for (oldGroup in old.installBlocks) {
                val newGroup = newGroups[oldGroup.name]
                if (newGroup == null) {
                    add(oldGroup.protectionDelaySec, "Install group deleted: ${oldGroup.name}")
                    continue
                }
                val dropped = oldGroup.packages.toSet() - newGroup.packages.toSet()
                if (dropped.isNotEmpty()) {
                    add(
                        oldGroup.protectionDelaySec,
                        "${dropped.size} package(s) removed from ${oldGroup.name}"
                    )
                }
                if (isTimerLowered(oldGroup.protectionDelaySec, newGroup.protectionDelaySec)) {
                    add(oldGroup.protectionDelaySec, "Protection timer lowered: ${oldGroup.name}")
                }
            }

            // Strictest wins; on a tie an explicit per-rule timer outranks the global one.
            return candidates.maxWithOrNull(
                compareBy<DeferRequirement> { it.seconds }.thenBy { it.fromExplicitTimer }
            ) ?: DeferRequirement.NONE
        }

        /**
         * Dropping or shrinking a rule's own timer is itself a relaxation — otherwise a 30-day
         * curfew could be defused in one tap by setting its timer back to zero.
         */
        private fun isTimerLowered(old: Int?, new: Int?): Boolean = (new ?: 0) < (old ?: 0)

        /**
         * True when any minute [oldRule] used to block for one of its packages is no longer
         * blocked by [newRules]. Compares against the whole new rule set, so splitting a rule
         * in two without losing coverage is not a relaxation.
         */
        private fun losesBlockedMinutes(
            oldRule: PeriodBlockRule,
            newRules: List<PeriodBlockRule>
        ): Boolean {
            for (pkg in oldRule.packages) {
                val before = blockedMinutesInDayForPackage(listOf(oldRule), pkg)
                val after = blockedMinutesInDayForPackage(newRules, pkg)
                if (before.any { it !in after }) return true
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
                    session = sessionConfig,
                    protectionDelaySec = parseProtectionDelay(obj)
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
                        periodBlocks.add(
                            PeriodBlockRule(
                                pkgs, start, end, blockedDays, muteNotif,
                                parseProtectionDelay(obj)
                            )
                        )
                    }
                }
            }

            val installBlocks = mutableListOf<InstallBlockGroup>()
            val ibArr = json.optJSONArray("install_blocks")
            if (ibArr != null) {
                for (i in 0 until ibArr.length()) {
                    val obj = ibArr.getJSONObject(i)
                    val name = obj.optString("name").trim()
                    if (name.isEmpty()) continue
                    val pkgsArr = obj.optJSONArray("packages") ?: org.json.JSONArray()
                    val pkgs = (0 until pkgsArr.length())
                        .map { pkgsArr.getString(it).trim() }
                        .filter { it.isNotEmpty() }
                        .distinct()
                    installBlocks.add(InstallBlockGroup(name, pkgs, parseProtectionDelay(obj)))
                }
            }

            return Config(limits, periodBlocks, installBlocks)
        }

        /** `protection_delay_sec` absent or <= 0 → null, meaning "fall back to the global delay". */
        private fun parseProtectionDelay(obj: JSONObject): Int? {
            if (!obj.has("protection_delay_sec")) return null
            val v = obj.optInt("protection_delay_sec", 0)
            return if (v > 0) v else null
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
