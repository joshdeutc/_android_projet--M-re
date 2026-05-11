package com.jo.selfcontrol.ultimate

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists user-defined Nuclear Mode presets so a configuration can be reapplied later
 * without re-selecting apps and duration every time.
 *
 * Storage: filesDir/nuclear_presets.json
 * Format:
 * { "presets": [ { "name": "...", "packages": [...], "durationMs": 3600000 } ] }
 */
object NuclearPresetsManager {

    private const val TAG = "SelfControl.Presets"
    private const val FILE_NAME = "nuclear_presets.json"

    data class Preset(
        val name: String,
        val packages: List<String>,
        val durationMs: Long
    )

    fun loadPresets(context: Context): List<Preset> {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return emptyList()
        return try {
            val json = JSONObject(file.readText())
            val arr = json.optJSONArray("presets") ?: return emptyList()
            val out = mutableListOf<Preset>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val name = obj.getString("name")
                val pkgsArr = obj.getJSONArray("packages")
                val pkgs = (0 until pkgsArr.length()).map { pkgsArr.getString(it) }
                val durationMs = obj.getLong("durationMs")
                out.add(Preset(name, pkgs, durationMs))
            }
            out
        } catch (e: Exception) {
            Log.e(TAG, "Error loading presets: ${e.message}")
            emptyList()
        }
    }

    fun savePresets(context: Context, presets: List<Preset>) {
        try {
            val arr = JSONArray()
            for (p in presets) {
                arr.put(JSONObject().apply {
                    put("name", p.name)
                    put("packages", JSONArray(p.packages))
                    put("durationMs", p.durationMs)
                })
            }
            val json = JSONObject().put("presets", arr)
            File(context.filesDir, FILE_NAME).writeText(json.toString(2))
        } catch (e: Exception) {
            Log.e(TAG, "Error saving presets: ${e.message}")
        }
    }

    /** Adds or replaces a preset by name. Returns the updated list. */
    fun upsertPreset(context: Context, preset: Preset): List<Preset> {
        val current = loadPresets(context).toMutableList()
        val idx = current.indexOfFirst { it.name.equals(preset.name, ignoreCase = true) }
        if (idx >= 0) current[idx] = preset else current.add(preset)
        savePresets(context, current)
        return current
    }

    fun deletePreset(context: Context, name: String): List<Preset> {
        val current = loadPresets(context).filterNot { it.name.equals(name, ignoreCase = true) }
        savePresets(context, current)
        return current
    }

    fun renamePreset(context: Context, oldName: String, newName: String): List<Preset> {
        val current = loadPresets(context).map {
            if (it.name.equals(oldName, ignoreCase = true)) it.copy(name = newName) else it
        }
        savePresets(context, current)
        return current
    }
}
