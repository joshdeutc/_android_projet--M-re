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
        val executeAt: Long
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
                        obj.optString("id", UUID.randomUUID().toString()),
                        obj.optString("description", "Config change"),
                        obj.getString("config"),
                        obj.getLong("execute_at")
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

    // Unlocks settings temporarily to allow device admin removal / uninstallation
    fun requestSettingsUnlock(context: Context) {
        val state = loadState(context)
        val effectiveDelaySeconds = getEffectiveDelaySeconds(state)
        val unlockTime = System.currentTimeMillis() + (effectiveDelaySeconds * 1000L)
        saveState(context, state.copy(unlockSettingsUnlockTime = unlockTime))
        Log.i(TAG, "Settings unlock requested. Will unlock at: $unlockTime")
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

    fun requestConfigUpdate(context: Context, newConfigJson: String, description: String) {
        val state = loadState(context)
        val effectiveDelaySeconds = getEffectiveDelaySeconds(state)
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
        if (readyUpdates.isNotEmpty()) {
            val latestReady = readyUpdates.maxByOrNull { it.executeAt } ?: return
            val latestConfig = latestReady.newLimitsJson
            // Write to config.json
            val file = File(context.filesDir, "limits.json")
            file.writeText(latestConfig)
            // Remove the applied updates
            val remaining = state.requestedConfigUpdates.filter { it.executeAt > now }
            saveState(context, state.copy(requestedConfigUpdates = remaining))
            Log.i(TAG, "Pending config applied automatically.")
        }
    }
}
