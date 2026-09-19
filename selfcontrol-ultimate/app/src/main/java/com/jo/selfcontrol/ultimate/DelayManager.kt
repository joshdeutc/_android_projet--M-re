package com.jo.selfcontrol.ultimate

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.UUID

object DelayManager {
    private const val TAG = "SelfControl.Delay"
    private const val FILE_NAME = "delay_config.json"

    data class DelayScheduleRule(
        val id: String,
        val startMinutes: Int,
        val endMinutes: Int,
        val delaySeconds: Int,
        val allowedDays: List<Int>
    )

    data class DelayState(
        val globalDelaySeconds: Int,
        val unlockSettingsUnlockTime: Long, // 0 = never requested, >0 = timestamp when settings will be unblocked
        val requestedConfigUpdates: List<PendingConfigUpdate>,
        val delaySchedule: List<DelayScheduleRule> = emptyList(),
        val pendingDelaySeconds: Int? = null,
        val pendingDelayExecuteAt: Long = 0L
    )

    data class PendingConfigUpdate(
        val id: String,
        val description: String,
        val newLimitsJson: String,
        val executeAt: Long,
        val targetType: String = "FULL_CONFIG",
        val targetKey: String = "",
        val isDelete: Boolean = false,
        val payloadJson: String = ""
    )

    fun loadState(context: Context): DelayState {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) {
            return DelayState(
                globalDelaySeconds = 0,
                unlockSettingsUnlockTime = 0,
                requestedConfigUpdates = emptyList()
            )
        }
        return try {
            val json = JSONObject(file.readText())
            val delay = json.optInt("global_delay_seconds", 0)
            val unlockTime = json.optLong("unlock_time", 0L)

            val execAt = json.optLong("pending_delay_execute_at", 0L)
            val pendingSec = if (execAt > 0L && json.has("pending_delay_seconds")) {
                json.getInt("pending_delay_seconds")
            } else null
            
            val pendingArray = json.optJSONArray("pending_configs")
            val pendingList = mutableListOf<PendingConfigUpdate>()
            if (pendingArray != null) {
                for (i in 0 until pendingArray.length()) {
                    val obj = pendingArray.getJSONObject(i)
                    pendingList.add(PendingConfigUpdate(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        description = obj.optString("description", "Config change"),
                        newLimitsJson = obj.optString("config", ""),
                        executeAt = obj.getLong("execute_at"),
                        targetType = obj.optString("target_type", "FULL_CONFIG"),
                        targetKey = obj.optString("target_key", ""),
                        isDelete = obj.optBoolean("is_delete", false),
                        payloadJson = obj.optString("payload", obj.optString("config", ""))
                    ))
                }
            }

            val scheduleArray = json.optJSONArray("delay_schedule")
            val schedule = mutableListOf<DelayScheduleRule>()
            if (scheduleArray != null) {
                for (i in 0 until scheduleArray.length()) {
                    val obj = scheduleArray.getJSONObject(i)
                    val days = mutableListOf<Int>()
                    val daysRaw = obj.opt("allowed_days")
                    if (daysRaw is String && daysRaw == "*") {
                        days.addAll(0..6)
                    } else {
                        val arr = obj.optJSONArray("allowed_days")
                        if (arr != null) {
                            for (d in 0 until arr.length()) {
                                days.add(arr.getInt(d))
                            }
                        }
                    }
                    if (days.isEmpty()) {
                        days.addAll(0..6)
                    }

                    schedule.add(
                        DelayScheduleRule(
                            id = obj.optString("id", UUID.randomUUID().toString()),
                            startMinutes = obj.optInt("start_minutes", 0).coerceIn(0, 1439),
                            endMinutes = obj.optInt("end_minutes", 1440).coerceIn(0, 1440),
                            delaySeconds = obj.optInt("delay_seconds", 0).coerceAtLeast(0),
                            allowedDays = days.distinct().sorted()
                        )
                    )
                }
            }

            DelayState(delay, unlockTime, pendingList, schedule, pendingSec, execAt)
        } catch (e: Exception) {
            Log.e(TAG, "Error reading delay_config.json: ${e.message}")
            DelayState(globalDelaySeconds = 0, unlockSettingsUnlockTime = 0, requestedConfigUpdates = emptyList())
        }
    }

    private fun saveState(context: Context, state: DelayState) {
        val file = File(context.filesDir, FILE_NAME)
        try {
            val json = JSONObject().apply {
                put("global_delay_seconds", state.globalDelaySeconds)
                put("unlock_time", state.unlockSettingsUnlockTime)
                val array = org.json.JSONArray()
                for (update in state.requestedConfigUpdates) {
                    val obj = JSONObject().apply {
                        put("id", update.id)
                        put("description", update.description)
                        put("config", update.newLimitsJson)
                        put("execute_at", update.executeAt)
                        put("target_type", update.targetType)
                        put("target_key", update.targetKey)
                        put("is_delete", update.isDelete)
                        put("payload", update.payloadJson)
                    }
                    array.put(obj)
                }
                put("pending_configs", array)
                val scheduleArray = org.json.JSONArray()
                for (rule in state.delaySchedule) {
                    val dayArr = if (rule.allowedDays.size == 7) {
                        "*"
                    } else {
                        org.json.JSONArray(rule.allowedDays)
                    }
                    val obj = JSONObject().apply {
                        put("id", rule.id)
                        put("start_minutes", rule.startMinutes)
                        put("end_minutes", rule.endMinutes)
                        put("delay_seconds", rule.delaySeconds)
                        put("allowed_days", dayArr)
                    }
                    scheduleArray.put(obj)
                }
                put("delay_schedule", scheduleArray)
                put("pending_delay_execute_at", state.pendingDelayExecuteAt)
                if (state.pendingDelaySeconds != null && state.pendingDelayExecuteAt > 0L) {
                    put("pending_delay_seconds", state.pendingDelaySeconds)
                } else {
                    remove("pending_delay_seconds")
                }
            }
            file.writeText(json.toString(2))
        } catch (e: Exception) {
            Log.e(TAG, "Error writing delay_config.json: ${e.message}")
        }
    }

    fun setGlobalDelay(context: Context, seconds: Int) {
        val state = loadState(context)
        saveState(
            context,
            state.copy(
                globalDelaySeconds = seconds,
                pendingDelaySeconds = null,
                pendingDelayExecuteAt = 0L
            )
        )
    }

    fun addScheduleRule(
        context: Context,
        startMinutes: Int,
        endMinutes: Int,
        delaySeconds: Int,
        allowedDays: List<Int>
    ) {
        val state = loadState(context)
        val newRule = DelayScheduleRule(
            id = UUID.randomUUID().toString(),
            startMinutes = startMinutes.coerceIn(0, 1439),
            endMinutes = endMinutes.coerceIn(0, 1440),
            delaySeconds = delaySeconds.coerceAtLeast(0),
            allowedDays = if (allowedDays.isEmpty()) (0..6).toList() else allowedDays.distinct().sorted()
        )
        saveState(context, state.copy(delaySchedule = state.delaySchedule + newRule))
    }

    fun removeScheduleRule(context: Context, ruleId: String) {
        val state = loadState(context)
        saveState(context, state.copy(delaySchedule = state.delaySchedule.filter { it.id != ruleId }))
    }

    private fun isInTimeWindow(nowMinutes: Int, start: Int, end: Int): Boolean {
        if (start == end) return false
        if (start < end) return nowMinutes >= start && nowMinutes < end
        return nowMinutes >= start || nowMinutes < end
    }

    private fun getEffectiveDelaySeconds(state: DelayState, nowMs: Long = System.currentTimeMillis()): Int {
        if (state.delaySchedule.isEmpty()) return state.globalDelaySeconds
        val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
        val day = cal.get(Calendar.DAY_OF_WEEK) - 1
        val nowMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        var best = state.globalDelaySeconds
        for (rule in state.delaySchedule) {
            if (day !in rule.allowedDays) continue
            if (!isInTimeWindow(nowMinutes, rule.startMinutes, rule.endMinutes)) continue
            if (rule.delaySeconds > best) {
                best = rule.delaySeconds
            }
        }
        return best
    }

    fun getCurrentEffectiveDelaySeconds(context: Context): Int {
        val state = loadState(context)
        return getEffectiveDelaySeconds(state)
    }

    /**
     * Delay change: applied after current [globalDelaySeconds] (unless settings already unlocked / delay 0 handled by UI).
     */
    fun requestDelayChange(context: Context, newSeconds: Int) {
        val state = loadState(context)
        val effectiveDelaySeconds = getEffectiveDelaySeconds(state)
        val executeAt = System.currentTimeMillis() + effectiveDelaySeconds * 1000L
        saveState(
            context,
            state.copy(pendingDelaySeconds = newSeconds, pendingDelayExecuteAt = executeAt)
        )
        Log.i(TAG, "Delay change scheduled at $executeAt -> ${newSeconds}s")
    }

    fun cancelPendingDelayChange(context: Context) {
        val state = loadState(context)
        saveState(context, state.copy(pendingDelaySeconds = null, pendingDelayExecuteAt = 0L))
        Log.i(TAG, "Pending delay change cancelled")
    }

    fun applyPendingDelayIfReady(context: Context) {
        val state = loadState(context)
        val target = state.pendingDelaySeconds ?: return
        if (state.pendingDelayExecuteAt <= 0L) return
        if (System.currentTimeMillis() < state.pendingDelayExecuteAt) return
        saveState(
            context,
            state.copy(
                globalDelaySeconds = target,
                pendingDelaySeconds = null,
                pendingDelayExecuteAt = 0L
            )
        )
        Log.i(TAG, "Pending delay applied: ${target}s")
    }

    data class ConfiguredDelayItem(
        val title: String,
        val delaySeconds: Long,
        val isGlobal: Boolean = false,
        val description: String = ""
    )

    fun formatDuration(seconds: Long): String {
        if (seconds <= 0L) return "0s"
        val d = seconds / 86400L
        val h = (seconds % 86400L) / 3600L
        val m = (seconds % 3600L) / 60L
        val s = seconds % 60L
        return when {
            d > 0L -> if (h > 0L) "${d}j ${h}h" else "${d}j"
            h > 0L -> if (m > 0L) "${h}h ${m}m" else "${h}h"
            m > 0L -> if (s > 0L) "${m}m ${s}s" else "${m}m"
            else -> "${s}s"
        }
    }

    private fun getAppLabel(context: Context, pkg: String): String {
        return try {
            val pm = context.packageManager
            val ai = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(ai).toString()
        } catch (e: Exception) {
            pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
        }
    }

    /**
     * Returns the list of all configured delays in the system:
     * - The general delay (always included)
     * - Any module or rule with a dedicated protection delay (if NOT aligned on global delay)
     * Sorted in descending order of duration.
     */
    fun getConfiguredDelays(context: Context): List<ConfiguredDelayItem> {
        val list = mutableListOf<ConfiguredDelayItem>()

        // 1. Délai général
        val globalSec = getCurrentEffectiveDelaySeconds(context).toLong()
        list.add(
            ConfiguredDelayItem(
                title = "🌐 Délai Général",
                delaySeconds = globalSec,
                isGlobal = true,
                description = if (globalSec == 0L) "Aucun délai (0s)" else formatDuration(globalSec)
            )
        )

        // 2. Quarantaine Whitelist (uniquement si non alignée sur le général)
        try {
            val wlState = WhitelistManager.loadState(context)
            if (!wlState.useGlobalDelay) {
                val wlSec = wlState.quarantineDelayHours * 3600L
                list.add(
                    ConfiguredDelayItem(
                        title = "🛡️ Quarantaine Whitelist",
                        delaySeconds = wlSec,
                        isGlobal = false,
                        description = "${wlState.quarantineDelayHours}h (Dédié)"
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading Whitelist delay: ${e.message}")
        }

        // 3. Règles ConfigManager (Limites d'apps, Couvre-feux, Bloqueurs d'installation)
        try {
            val config = ConfigManager.loadConfig(context)

            val delayState = loadState(context)
            val pendingUpdates = delayState.requestedConfigUpdates

            // Limites d'applications
            for (limit in config.limits) {
                val sec = limit.protectionDelaySec
                val pendingInfo = getPendingAppLimit(context, limit.packageName)
                val pendingLimit = if (pendingInfo != null && !pendingInfo.second) pendingInfo.first else null
                val pendingSec = pendingLimit?.protectionDelaySec

                if (sec != null && sec > 0) {
                    val appName = getAppLabel(context, limit.packageName)
                    val desc = if (pendingSec != null && pendingSec != sec) {
                        "${formatDuration(sec.toLong())} (⏳ → ${formatDuration(pendingSec.toLong())})"
                    } else {
                        formatDuration(sec.toLong())
                    }
                    list.add(
                        ConfiguredDelayItem(
                            title = "📱 Limite : $appName",
                            delaySeconds = sec.toLong(),
                            isGlobal = false,
                            description = desc
                        )
                    )
                } else if (pendingSec != null && pendingSec > 0) {
                    val appName = getAppLabel(context, limit.packageName)
                    list.add(
                        ConfiguredDelayItem(
                            title = "📱 Limite : $appName",
                            delaySeconds = pendingSec.toLong(),
                            isGlobal = false,
                            description = "Global (⏳ → ${formatDuration(pendingSec.toLong())})"
                        )
                    )
                }
            }

            // Couvre-feux (Curfews)
            for ((idx, rule) in config.periodBlocks.withIndex()) {
                val sec = rule.protectionDelaySec
                val targetApps = rule.packages.take(2).joinToString(", ") { getAppLabel(context, it) }
                val more = if (rule.packages.size > 2) " (+${rule.packages.size - 2})" else ""
                val label = if (targetApps.isNotBlank()) "🌙 Couvre-feu ($targetApps$more)" else "🌙 Couvre-feu #${idx + 1}"

                val pendingCurfew = pendingUpdates.find {
                    it.targetType == "CURFEW" && it.targetKey == rule.scheduleSignature() && !it.isDelete
                }?.let { runCatching { ConfigManager.parsePeriodBlockRule(JSONObject(it.payloadJson)) }.getOrNull() }
                val pendingSec = pendingCurfew?.protectionDelaySec

                if (sec != null && sec > 0) {
                    val desc = if (pendingSec != null && pendingSec != sec) {
                        "${formatDuration(sec.toLong())} (⏳ → ${formatDuration(pendingSec.toLong())})"
                    } else {
                        formatDuration(sec.toLong())
                    }
                    list.add(
                        ConfiguredDelayItem(
                            title = label,
                            delaySeconds = sec.toLong(),
                            isGlobal = false,
                            description = desc
                        )
                    )
                } else if (pendingSec != null && pendingSec > 0) {
                    list.add(
                        ConfiguredDelayItem(
                            title = label,
                            delaySeconds = pendingSec.toLong(),
                            isGlobal = false,
                            description = "Global (⏳ → ${formatDuration(pendingSec.toLong())})"
                        )
                    )
                }
            }

            // Groupes d'installation
            for (group in config.installBlocks) {
                val sec = group.protectionDelaySec
                if (sec != null && sec > 0) {
                    list.add(
                        ConfiguredDelayItem(
                            title = "🚫 Bloqueur : ${group.name}",
                            delaySeconds = sec.toLong(),
                            isGlobal = false,
                            description = formatDuration(sec.toLong())
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading ConfigManager delays: ${e.message}")
        }

        // 4. Règles d'écran (ScreenRuleManager)
        try {
            val screenRules = ScreenRuleManager.load(context)
            for (r in screenRules) {
                val sec = r.protectionDelaySec
                if (sec != null && sec > 0) {
                    list.add(
                        ConfiguredDelayItem(
                            title = "🔒 Écran : ${r.name}",
                            delaySeconds = sec.toLong(),
                            isGlobal = false,
                            description = formatDuration(sec.toLong())
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading ScreenRuleManager delays: ${e.message}")
        }

        return list.sortedByDescending { it.delaySeconds }
    }

    /**
     * Checks whether the user is eligible to request an uninstallation / settings unlock.
     * Rules:
     * 1. No module or rule must have a custom / dedicated delay (all rules aligned to global).
     * 2. No delay reduction or pending delay execution must be in flight.
     * 3. The general delay must be 0.
     */
    fun checkUninstallEligibility(context: Context): Pair<Boolean, String?> {
        val now = System.currentTimeMillis()

        // 1. Vérifier la Whitelist
        try {
            val wlState = WhitelistManager.loadState(context)
            if (!wlState.useGlobalDelay) {
                return false to "La Whitelist utilise un délai dédié (${wlState.quarantineDelayHours}h). Alignez-la sur le délai général."
            }
            if (wlState.pendingDelayExecuteAt > now) {
                val rem = ((wlState.pendingDelayExecuteAt - now) / 1000)
                return false to "Une modification du délai Whitelist est encore en attente (${formatDuration(rem)} restantes)."
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking Whitelist eligibility: ${e.message}")
        }

        // 2. Vérifier les règles ConfigManager (Limites, Couvre-feux, Bloqueurs)
        try {
            val config = ConfigManager.loadConfig(context)
            val customLimits = config.limits.filter { it.protectionDelaySec != null && it.protectionDelaySec > 0 }
            if (customLimits.isNotEmpty()) {
                val names = customLimits.joinToString(", ") { getAppLabel(context, it.packageName) }
                return false to "Des limites d'applications ont un délai de protection dédié ($names). Remettez-les sur le délai général."
            }

            val customCurfews = config.periodBlocks.filter { it.protectionDelaySec != null && it.protectionDelaySec > 0 }
            if (customCurfews.isNotEmpty()) {
                return false to "Un ou plusieurs couvre-feux ont un délai de protection dédié. Remettez-les sur le délai général."
            }

            val customGroups = config.installBlocks.filter { it.protectionDelaySec != null && it.protectionDelaySec > 0 }
            if (customGroups.isNotEmpty()) {
                val names = customGroups.joinToString(", ") { it.name }
                return false to "Des groupes de blocage ont un délai dédié ($names). Remettez-les sur le délai général."
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking ConfigManager eligibility: ${e.message}")
        }

        // 3. Vérifier ScreenRuleManager
        try {
            val customScreenRules = ScreenRuleManager.load(context).filter { (it.protectionDelaySec ?: 0) > 0 }
            if (customScreenRules.isNotEmpty()) {
                val names = customScreenRules.joinToString(", ") { it.name }
                return false to "Des règles d'écran ont un délai dédié ($names). Remettez-les sur le délai général."
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking ScreenRuleManager eligibility: ${e.message}")
        }

        // 4. Vérifier les modifications de configuration ou de délai en attente
        val delayState = loadState(context)
        if (delayState.requestedConfigUpdates.any { it.executeAt > now }) {
            return false to "Des modifications de configuration sont encore en cours d'attente."
        }
        if (delayState.pendingDelayExecuteAt > now) {
            val rem = ((delayState.pendingDelayExecuteAt - now) / 1000)
            return false to "Une modification du délai général est encore en attente (${formatDuration(rem)} restantes)."
        }

        // 5. Vérifier que le délai général est à 0
        val effectiveGlobalSec = getCurrentEffectiveDelaySeconds(context).toLong()
        if (effectiveGlobalSec > 0L) {
            return false to "Le délai général doit être réglé sur 0 minute (actuellement ${formatDuration(effectiveGlobalSec)})."
        }

        return true to null
    }

    // Unlocks settings temporarily to allow device admin removal / uninstallation
    fun requestSettingsUnlock(context: Context): Pair<Boolean, String?> {
        val eligibility = checkUninstallEligibility(context)
        if (!eligibility.first) {
            Log.w(TAG, "Settings unlock rejected: ${eligibility.second}")
            return eligibility
        }

        val state = loadState(context)
        val effectiveDelaySeconds = getEffectiveDelaySeconds(state)
        val unlockTime = System.currentTimeMillis() + (effectiveDelaySeconds * 1000L)
        saveState(context, state.copy(unlockSettingsUnlockTime = unlockTime))
        Log.i(TAG, "Settings unlock requested. Will unlock at: $unlockTime")
        return true to null
    }

    fun cancelSettingsUnlock(context: Context) {
        val state = loadState(context)
        saveState(context, state.copy(unlockSettingsUnlockTime = 0L))
        Log.i(TAG, "Settings unlock cancelled.")
    }

    fun isSettingsUnlocked(context: Context): Boolean {
        val state = loadState(context)
        if (state.unlockSettingsUnlockTime == 0L) return false
        return System.currentTimeMillis() >= state.unlockSettingsUnlockTime
    }
    
    fun getSettingsUnlockTime(context: Context): Long {
        return loadState(context).unlockSettingsUnlockTime
    }

    /**
     * Queue a config change behind the delay gate.
     *
     * [overrideDelaySeconds] lets the caller impose a per-rule `protection_delay_sec` instead of
     * the global effective delay — see `ConfigManager.requiredDefer`. Passing null keeps the
     * historical behaviour (global delay + active schedule rules).
     */
    fun requestConfigUpdate(
        context: Context,
        newConfigJson: String,
        description: String,
        overrideDelaySeconds: Int? = null
    ) {
        val state = loadState(context)
        val effectiveDelaySeconds = overrideDelaySeconds ?: getEffectiveDelaySeconds(state)
        val executeAt = System.currentTimeMillis() + (effectiveDelaySeconds * 1000L)

        val updatedList = state.requestedConfigUpdates.toMutableList()
        updatedList.add(
            PendingConfigUpdate(
                id = UUID.randomUUID().toString(),
                description = description,
                newLimitsJson = newConfigJson,
                executeAt = executeAt
            )
        )

        saveState(context, state.copy(requestedConfigUpdates = updatedList))
        Log.i(TAG, "Config update requested. Will apply at: $executeAt")
    }

    fun requestAppLimitUpdate(
        context: Context,
        pkg: String,
        newLimit: ConfigManager.AppLimit,
        description: String,
        overrideDelaySeconds: Int? = null
    ) {
        val state = loadState(context)
        val effectiveDelaySeconds = overrideDelaySeconds ?: getEffectiveDelaySeconds(state)
        val executeAt = System.currentTimeMillis() + (effectiveDelaySeconds * 1000L)

        // Remove any existing pending update for this same package
        val filtered = state.requestedConfigUpdates.filterNot {
            it.targetType == "APP_LIMIT" && it.targetKey == pkg
        }.toMutableList()

        val payload = ConfigManager.appLimitToJson(newLimit).toString()
        filtered.add(
            PendingConfigUpdate(
                id = UUID.randomUUID().toString(),
                description = description,
                newLimitsJson = payload,
                executeAt = executeAt,
                targetType = "APP_LIMIT",
                targetKey = pkg,
                isDelete = false,
                payloadJson = payload
            )
        )
        saveState(context, state.copy(requestedConfigUpdates = filtered))
        Log.i(TAG, "App limit update requested for $pkg. Will apply at: $executeAt")
    }

    fun requestAppLimitDelete(
        context: Context,
        pkg: String,
        description: String,
        overrideDelaySeconds: Int? = null
    ) {
        val state = loadState(context)
        val effectiveDelaySeconds = overrideDelaySeconds ?: getEffectiveDelaySeconds(state)
        val executeAt = System.currentTimeMillis() + (effectiveDelaySeconds * 1000L)

        val filtered = state.requestedConfigUpdates.filterNot {
            it.targetType == "APP_LIMIT" && it.targetKey == pkg
        }.toMutableList()

        filtered.add(
            PendingConfigUpdate(
                id = UUID.randomUUID().toString(),
                description = description,
                newLimitsJson = "",
                executeAt = executeAt,
                targetType = "APP_LIMIT",
                targetKey = pkg,
                isDelete = true,
                payloadJson = ""
            )
        )
        saveState(context, state.copy(requestedConfigUpdates = filtered))
        Log.i(TAG, "App limit delete requested for $pkg. Will apply at: $executeAt")
    }

    fun cancelAppLimitUpdate(context: Context, pkg: String) {
        val state = loadState(context)
        val filtered = state.requestedConfigUpdates.filterNot {
            it.targetType == "APP_LIMIT" && it.targetKey == pkg
        }
        saveState(context, state.copy(requestedConfigUpdates = filtered))
    }

    fun getPendingAppLimit(context: Context, pkg: String): Pair<ConfigManager.AppLimit?, Boolean>? {
        val state = loadState(context)
        val now = System.currentTimeMillis()
        val update = state.requestedConfigUpdates
            .filter { it.targetType == "APP_LIMIT" && it.targetKey == pkg && it.executeAt > now }
            .maxByOrNull { it.executeAt } ?: return null
        if (update.isDelete) return Pair(null, true)
        return try {
            val limit = ConfigManager.parseAppLimit(JSONObject(update.payloadJson))
            Pair(limit, false)
        } catch (e: Exception) {
            null
        }
    }

    fun cancelConfigUpdates(context: Context) {
        val state = loadState(context)
        saveState(context, state.copy(requestedConfigUpdates = emptyList()))
    }

    fun cancelConfigUpdate(context: Context, updateId: String) {
        val state = loadState(context)
        saveState(
            context,
            state.copy(requestedConfigUpdates = state.requestedConfigUpdates.filter { it.id != updateId })
        )
    }

    fun getLatestPendingConfigJson(context: Context): String? {
        return loadState(context).requestedConfigUpdates.maxByOrNull { it.executeAt }?.newLimitsJson
    }

    // Called periodically by LimitService to check pending configs
    fun applyPendingConfigsIfReady(context: Context) {
        val state = loadState(context)
        if (state.requestedConfigUpdates.isEmpty()) return

        val now = System.currentTimeMillis()
        val readyUpdates = state.requestedConfigUpdates.filter { it.executeAt <= now }
        if (readyUpdates.isEmpty()) return

        var activeConfig = ConfigManager.loadConfig(context)

        for (update in readyUpdates.sortedBy { it.executeAt }) {
            activeConfig = when (update.targetType) {
                "APP_LIMIT" -> {
                    val pkg = update.targetKey
                    if (update.isDelete) {
                        activeConfig.copy(limits = activeConfig.limits.filter { it.packageName != pkg })
                    } else {
                        val newLimit = runCatching { ConfigManager.parseAppLimit(JSONObject(update.payloadJson)) }.getOrNull()
                        if (newLimit != null) {
                            val newLimits = activeConfig.limits.filter { it.packageName != pkg }.toMutableList()
                            newLimits.add(newLimit)
                            activeConfig.copy(limits = newLimits)
                        } else activeConfig
                    }
                }
                "CURFEW" -> {
                    val sig = update.targetKey
                    if (update.isDelete) {
                        activeConfig.copy(periodBlocks = activeConfig.periodBlocks.filter { it.scheduleSignature() != sig })
                    } else {
                        val newRule = runCatching { ConfigManager.parsePeriodBlockRule(JSONObject(update.payloadJson)) }.getOrNull()
                        if (newRule != null) {
                            val newRules = activeConfig.periodBlocks.filter { it.scheduleSignature() != sig }.toMutableList()
                            newRules.add(newRule)
                            activeConfig.copy(periodBlocks = newRules)
                        } else activeConfig
                    }
                }
                "INSTALL_BLOCK" -> {
                    val name = update.targetKey
                    if (update.isDelete) {
                        activeConfig.copy(installBlocks = activeConfig.installBlocks.filter { it.name != name })
                    } else {
                        val newGroup = runCatching { ConfigManager.parseInstallBlockGroup(JSONObject(update.payloadJson)) }.getOrNull()
                        if (newGroup != null) {
                            val newGroups = activeConfig.installBlocks.filter { it.name != name }.toMutableList()
                            newGroups.add(newGroup)
                            activeConfig.copy(installBlocks = newGroups)
                        } else activeConfig
                    }
                }
                else -> {
                    ConfigManager.fromJsonString(update.newLimitsJson) ?: activeConfig
                }
            }
        }

        ConfigManager.saveConfig(context, activeConfig)

        val remaining = state.requestedConfigUpdates.filter { it.executeAt > now }
        saveState(context, state.copy(requestedConfigUpdates = remaining))
        Log.i(TAG, "Applied ${readyUpdates.size} pending config updates.")
    }
}
