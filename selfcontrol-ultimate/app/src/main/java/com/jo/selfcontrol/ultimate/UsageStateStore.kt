package com.jo.selfcontrol.ultimate

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists usageToday + suspendedApps + the logical-day boundary to disk so they survive
 * service kills (Doze, OEM optimizations).
 *
 * Without this, a service kill mid-day would lose all accumulated quota and the next restart
 * would call loadTodayUsageFromSystem() — which has a UsageStatsManager bucket quirk that
 * can re-pull yesterday's usage and incorrectly re-block apps that should be free.
 *
 * Same logical day on restart → restore in-memory state verbatim, skip system query.
 * Different logical day (stale) → discard, start fresh.
 */
object UsageStateStore {

    private const val TAG = "SelfControl.UsageState"
    private const val FILE_NAME = "usage_state.json"

    data class State(
        val logicalDayStartMs: Long,
        val usageToday: Map<String, Int>,
        val suspendedApps: Set<String>
    )

    @Synchronized
    fun load(ctx: Context): State? {
        val file = File(ctx.filesDir, FILE_NAME)
        if (!file.exists()) return null
        return try {
            val json = JSONObject(file.readText())
            val dayStart = json.getLong("logical_day_start_ms")
            val usage = mutableMapOf<String, Int>()
            val usageJson = json.optJSONObject("usage_today")
            if (usageJson != null) {
                val keys = usageJson.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    usage[k] = usageJson.getInt(k)
                }
            }
            val susp = mutableSetOf<String>()
            val suspArr = json.optJSONArray("suspended_apps")
            if (suspArr != null) {
                for (i in 0 until suspArr.length()) susp.add(suspArr.getString(i))
            }
            State(dayStart, usage, susp)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load usage state: ${e.message}")
            null
        }
    }

    @Synchronized
    fun persist(
        ctx: Context,
        logicalDayStartMs: Long,
        usageToday: Map<String, Int>,
        suspendedApps: Set<String>
    ) {
        try {
            val json = JSONObject()
            json.put("logical_day_start_ms", logicalDayStartMs)
            val usageJson = JSONObject()
            for ((pkg, sec) in usageToday) usageJson.put(pkg, sec)
            json.put("usage_today", usageJson)
            val suspArr = JSONArray()
            for (p in suspendedApps) suspArr.put(p)
            json.put("suspended_apps", suspArr)
            File(ctx.filesDir, FILE_NAME).writeText(json.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist usage state: ${e.message}")
        }
    }
}
