package com.jo.selfcontrol.ultimate

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persistent on-device audit trail.
 *
 * Every meaningful decision the app makes — foreground app changes, session start/end (with the
 * reason), blocks/unblocks, forced HOME — is appended here with a millisecond timestamp. When the
 * user reports "I got kicked out and don't know why", this file is the ground truth: it shows
 * exactly what happened and what triggered it.
 *
 * Storage:
 *  - <filesDir>/events.log               — primary, reliable, survives reboot (root to read).
 *  - exportToExternal() copies it to getExternalFilesDir so it can be pulled with plain `adb pull`
 *    (no root): /sdcard/Android/data/com.jo.selfcontrol.ultimate/files/events.log
 *
 * The file is a ring: once it passes MAX_BYTES we drop the oldest half. Each line is also mirrored
 * to Logcat (tag "SelfControl.Event") for live `adb logcat` debugging.
 *
 * PRIVACY: this whole logger is gated on [BuildConfig.EVENT_LOG_ENABLED], which is only `true` for
 * the `me` developer flavor. In every other flavor (basic / admin / deviceAdmin) all methods here
 * are no-ops — no foreground-app history is ever recorded or persisted. The blocking engine still
 * *observes* the foreground app to enforce limits, but it is not written down.
 */
object EventLog {

    /** True only in the `me` developer flavor. Other flavors never record anything. */
    private val enabled: Boolean get() = BuildConfig.EVENT_LOG_ENABLED

    private const val TAG = "SelfControl.Event"
    private const val FILE_NAME = "events.log"
    private const val MAX_BYTES = 512 * 1024   // 512 KB ring buffer

    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val lock = Any()

    /**
     * Append one event. [cat] is a short category (FG, SESSION, BLOCK, UNBLOCK, HOME, CONFIG, …),
     * [msg] is the human-readable detail. Never throws — logging must not crash the app.
     */
    fun log(ctx: Context, cat: String, msg: String) {
        if (!enabled) return
        val line = "${fmt.format(Date())} [$cat] $msg"
        Log.i(TAG, line)
        synchronized(lock) {
            try {
                val f = File(ctx.filesDir, FILE_NAME)
                if (f.exists() && f.length() > MAX_BYTES) {
                    val text = f.readText()
                    f.writeText(text.substring(text.length / 2))
                }
                f.appendText("$line\n")
            } catch (_: Exception) {
                // best-effort; swallow
            }
        }
    }

    /**
     * Copy the log to the app's external files dir so it can be pulled without root:
     *   adb pull /sdcard/Android/data/com.jo.selfcontrol.ultimate/files/events.log
     * Returns the destination path, or null on failure.
     */
    fun exportToExternal(ctx: Context): String? {
        if (!enabled) return null
        return synchronized(lock) {
            try {
                val src = File(ctx.filesDir, FILE_NAME)
                if (!src.exists()) return@synchronized null
                val dir = ctx.getExternalFilesDir(null) ?: return@synchronized null
                val dst = File(dir, FILE_NAME)
                src.copyTo(dst, overwrite = true)
                Log.i(TAG, "Exported event log → ${dst.absolutePath}")
                dst.absolutePath
            } catch (e: Exception) {
                Log.e(TAG, "Export failed: ${e.message}")
                null
            }
        }
    }

    /** Wipe the log (e.g. for a clean repro). */
    fun clear(ctx: Context) {
        if (!enabled) return
        synchronized(lock) {
            try { File(ctx.filesDir, FILE_NAME).delete() } catch (_: Exception) {}
        }
    }
}
