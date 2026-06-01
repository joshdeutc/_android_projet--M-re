package com.jo.selfcontrol.ultimate

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Discord-style per-unlock session limit (see ConfigManager.SessionConfig).
 *
 * Semantics:
 *  - Opening the app while no session is active and no cooldown is pending starts a new
 *    session: sessionStartMs = now, sessionsUsed += 1.
 *  - Leaving the app (foreground changes away from pkg) OR reaching session duration ends
 *    the session and starts a cooldown of cooldownSec.
 *  - While in cooldown OR sessionsUsed >= maxSessionsPerDay, the app is blocked.
 *
 * Day rollover (driven externally via resetDayIfNeeded) clears sessionsUsed and cooldownEnd.
 */
object SessionManager {

    private const val TAG = "SelfControl.Session"
    private const val FILE_NAME = "session_state.json"

    enum class Result { IN_SESSION, STARTED_SESSION, BLOCKED_COOLDOWN, BLOCKED_DAILY }

    data class PkgState(
        var sessionStartMs: Long = 0L,   // 0 = no active session
        var cooldownEndMs: Long = 0L,    // 0 = no cooldown
        var sessionsUsed: Int = 0
    )

    /** Read-only snapshot for the dashboard / notification. Computed from PkgState + cfg. */
    data class Status(
        val state: State,
        val sessionsUsed: Int,
        val maxSessionsPerDay: Int,
        val sessionRemainingSec: Int,    // > 0 only when state == ACTIVE
        val cooldownEndsAtMs: Long       // > 0 only when state == COOLDOWN
    ) {
        enum class State { ACTIVE, COOLDOWN, READY, DAILY_EXHAUSTED }
    }

    // In-memory mirror of session_state.json; lazily loaded on first access.
    private val states = mutableMapOf<String, PkgState>()
    private var trackingDay: Int = -1
    private var loaded = false

    @Synchronized
    private fun ensureLoaded(ctx: Context) {
        if (loaded) return
        loaded = true
        val file = File(ctx.filesDir, FILE_NAME)
        if (!file.exists()) return
        try {
            val json = JSONObject(file.readText())
            trackingDay = json.optInt("tracking_day", -1)
            val pkgs = json.optJSONObject("packages") ?: return
            val keys = pkgs.keys()
            while (keys.hasNext()) {
                val pkg = keys.next()
                val o = pkgs.getJSONObject(pkg)
                states[pkg] = PkgState(
                    sessionStartMs = o.optLong("session_start_ms", 0L),
                    cooldownEndMs = o.optLong("cooldown_end_ms", 0L),
                    sessionsUsed = o.optInt("sessions_used", 0)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load session state: ${e.message}")
        }
    }

    @Synchronized
    private fun persist(ctx: Context) {
        try {
            val json = JSONObject()
            json.put("tracking_day", trackingDay)
            val pkgs = JSONObject()
            for ((pkg, st) in states) {
                pkgs.put(pkg, JSONObject().apply {
                    put("session_start_ms", st.sessionStartMs)
                    put("cooldown_end_ms", st.cooldownEndMs)
                    put("sessions_used", st.sessionsUsed)
                })
            }
            json.put("packages", pkgs)
            File(ctx.filesDir, FILE_NAME).writeText(json.toString(2))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist session state: ${e.message}")
        }
    }

    /**
     * Call when the user is currently viewing [pkg] and it has a SessionConfig.
     * Returns whether the user can keep using it (IN_SESSION / STARTED_SESSION)
     * or must be blocked (BLOCKED_COOLDOWN / BLOCKED_DAILY).
     */
    @Synchronized
    fun evaluate(ctx: Context, pkg: String, cfg: ConfigManager.SessionConfig, nowMs: Long): Result {
        ensureLoaded(ctx)
        val st = states.getOrPut(pkg) { PkgState() }

        // Active session: timer expired?
        if (st.sessionStartMs > 0L) {
            val endsAt = st.sessionStartMs + cfg.sessionDurationSec * 1000L
            if (nowMs >= endsAt) {
                // Session expired by timer
                st.sessionStartMs = 0L
                st.cooldownEndMs = endsAt + cfg.cooldownSec * 1000L
                persist(ctx)
                EventLog.log(ctx, "SESSION", "$pkg ended reason=duration_timer (${cfg.sessionDurationSec}s) → cooldown ${cfg.cooldownSec}s")
                return Result.BLOCKED_COOLDOWN
            }
            return Result.IN_SESSION
        }

        // No active session: cooldown or daily limit?
        if (nowMs < st.cooldownEndMs) return Result.BLOCKED_COOLDOWN
        if (st.sessionsUsed >= cfg.maxSessionsPerDay) return Result.BLOCKED_DAILY

        // Start a new session
        st.sessionStartMs = nowMs
        st.sessionsUsed += 1
        persist(ctx)
        Log.i(TAG, "▶️ Session started: $pkg (#${st.sessionsUsed}/${cfg.maxSessionsPerDay})")
        EventLog.log(ctx, "SESSION", "$pkg started #${st.sessionsUsed}/${cfg.maxSessionsPerDay} (dur ${cfg.sessionDurationSec}s)")
        return Result.STARTED_SESSION
    }

    /**
     * Call when the user leaves [pkg] (foreground change). If a session was active,
     * end it and start the cooldown using [cfg].
     */
    @Synchronized
    fun endSessionIfActive(ctx: Context, pkg: String, cfg: ConfigManager.SessionConfig, nowMs: Long) {
        ensureLoaded(ctx)
        val st = states[pkg] ?: return
        if (st.sessionStartMs <= 0L) return
        st.sessionStartMs = 0L
        st.cooldownEndMs = nowMs + cfg.cooldownSec * 1000L
        persist(ctx)
        Log.i(TAG, "⏸️ Session ended: $pkg → cooldown ${cfg.cooldownSec}s")
        EventLog.log(ctx, "SESSION", "$pkg ended reason=left_to_home → cooldown ${cfg.cooldownSec}s")
    }

    /** Returns packages that currently have an active session, for "did the user leave?" detection. */
    @Synchronized
    fun activePackages(ctx: Context): Set<String> {
        ensureLoaded(ctx)
        return states.filterValues { it.sessionStartMs > 0L }.keys.toSet()
    }

    /**
     * Daily reset. Call from LimitService when a new logical day starts.
     * Clears sessionsUsed and cooldownEnd; preserves any active sessionStart so a session
     * that began before midnight isn't silently extended into a fresh quota.
     */
    @Synchronized
    fun resetDayIfNeeded(ctx: Context, today: Int) {
        ensureLoaded(ctx)
        if (today == trackingDay) return
        trackingDay = today
        for (st in states.values) {
            st.cooldownEndMs = 0L
            st.sessionsUsed = if (st.sessionStartMs > 0L) 1 else 0
        }
        persist(ctx)
        Log.i(TAG, "📅 Session day reset (day=$today)")
    }

    /**
     * Read-only check: "should we keep [pkg] suspended because of session limits?"
     * Used by LimitService.checkAndUnblockApps so we don't bounce-unblock then re-block.
     * Returns true iff there is no active session AND (cooldown active OR daily limit hit).
     */
    @Synchronized
    fun shouldStayBlocked(ctx: Context, pkg: String, cfg: ConfigManager.SessionConfig, nowMs: Long): Boolean {
        ensureLoaded(ctx)
        val st = states[pkg] ?: return false
        if (st.sessionStartMs > 0L) return false
        if (nowMs < st.cooldownEndMs) return true
        if (st.sessionsUsed >= cfg.maxSessionsPerDay) return true
        return false
    }

    /** Debug snapshot for diagnostics / tests. */
    @Synchronized
    fun snapshot(ctx: Context): Map<String, PkgState> {
        ensureLoaded(ctx)
        return states.mapValues { it.value.copy() }
    }

    /**
     * Live status of [pkg]'s session limit, for UI display (notification + dashboard).
     * Read-only — does not start/end sessions.
     */
    @Synchronized
    fun statusOf(ctx: Context, pkg: String, cfg: ConfigManager.SessionConfig, nowMs: Long): Status {
        ensureLoaded(ctx)
        val st = states[pkg] ?: PkgState()
        return when {
            st.sessionStartMs > 0L -> {
                val endsAt = st.sessionStartMs + cfg.sessionDurationSec * 1000L
                val leftSec = ((endsAt - nowMs) / 1000L).coerceAtLeast(0L).toInt()
                Status(Status.State.ACTIVE, st.sessionsUsed, cfg.maxSessionsPerDay, leftSec, 0L)
            }
            nowMs < st.cooldownEndMs ->
                Status(Status.State.COOLDOWN, st.sessionsUsed, cfg.maxSessionsPerDay, 0, st.cooldownEndMs)
            st.sessionsUsed >= cfg.maxSessionsPerDay ->
                Status(Status.State.DAILY_EXHAUSTED, st.sessionsUsed, cfg.maxSessionsPerDay, 0, 0L)
            else ->
                Status(Status.State.READY, st.sessionsUsed, cfg.maxSessionsPerDay, 0, 0L)
        }
    }
}
