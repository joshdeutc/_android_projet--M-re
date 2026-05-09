package com.jo.selfcontrol.ultimate

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Manages the Nuclear Mode persistence so it survives reboots.
 * Nuclear Mode uses Android's native DND for notification suppression.
 */
object NuclearManager {

    private const val TAG = "SelfControl.Nuclear"
    private const val FILE_NAME = "nuclear_state.json"

    data class NuclearState(
        val active: Boolean,
        val endTimestamp: Long,
        val blockedPackages: List<String>,
        val cancelRequestAt: Long = 0L,
        val cancelExecuteAt: Long = 0L
    )

    fun saveState(context: Context, state: NuclearState) {
        try {
            val json = JSONObject().apply {
                put("active", state.active)
                put("endTimestamp", state.endTimestamp)
                val appsArray = org.json.JSONArray(state.blockedPackages)
                put("blockedPackages", appsArray)
                put("cancelRequestAt", state.cancelRequestAt)
                put("cancelExecuteAt", state.cancelExecuteAt)
            }
            File(context.filesDir, FILE_NAME).writeText(json.toString())
            Log.i(TAG, "Nuclear state saved")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving nuclear state: ${e.message}")
        }
    }

    fun loadState(context: Context): NuclearState? {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return null

        return try {
            val json = JSONObject(file.readText())
            val active = json.getBoolean("active")
            val endTimestamp = json.getLong("endTimestamp")
            val appsArray = json.getJSONArray("blockedPackages")
            val packages = mutableListOf<String>()
            for (i in 0 until appsArray.length()) {
                packages.add(appsArray.getString(i))
            }
            NuclearState(
                active = active,
                endTimestamp = endTimestamp,
                blockedPackages = packages,
                cancelRequestAt = json.optLong("cancelRequestAt", 0L),
                cancelExecuteAt = json.optLong("cancelExecuteAt", 0L)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error loading nuclear state: ${e.message}")
            null
        }
    }

    fun clearState(context: Context) {
        try {
            File(context.filesDir, FILE_NAME).delete()
            Log.i(TAG, "Nuclear state cleared")
        } catch (e: Exception) {
            // Ignore
        }
    }

    fun isExpired(state: NuclearState): Boolean {
        return System.currentTimeMillis() >= state.endTimestamp
    }

    fun withCancelRequested(state: NuclearState, now: Long, executeAt: Long): NuclearState {
        return state.copy(cancelRequestAt = now, cancelExecuteAt = executeAt)
    }

    fun withCancelCleared(state: NuclearState): NuclearState {
        return state.copy(cancelRequestAt = 0L, cancelExecuteAt = 0L)
    }

    fun isCancelReady(state: NuclearState): Boolean {
        return state.cancelExecuteAt > 0L && System.currentTimeMillis() >= state.cancelExecuteAt
    }
}
