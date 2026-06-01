package com.jo.selfcontrol.ultimate

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log

/**
 * Exact foreground-time computation from UsageEvents.
 *
 * Replaces UsageStatsManager.queryAndAggregateUsageStats() which has bucket-aggregation
 * quirks (today's daily bucket can return data spanning the whole bucket including
 * 00:00–02:00, and on some OEMs the aggregation returns yesterday's data after a fresh
 * boot). queryEvents() gives us raw foreground/background transitions which we sum
 * ourselves over the exact [beginMs, endMs] window — no rounding, no bucket nonsense.
 *
 * If a session straddles beginMs (started before, still active at begin), we clip from
 * beginMs. If still in foreground at endMs, we count up to endMs.
 */
object UsageQuery {

    private const val TAG = "SelfControl.UsageQuery"

    /**
     * Returns map of package → foreground seconds in [beginMs, endMs).
     * Only packages that had ≥1 second of foreground time are present.
     */
    fun foregroundSecondsByPackage(ctx: Context, beginMs: Long, endMs: Long): Map<String, Int> {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return emptyMap()
        val totals = mutableMapOf<String, Long>()
        // Per-package timestamp of the last MOVE_TO_FOREGROUND not yet closed by a background.
        val openSince = mutableMapOf<String, Long>()
        try {
            val events = usm.queryEvents(beginMs, endMs)
            val ev = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(ev)
                val pkg = ev.packageName ?: continue
                when (ev.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> {
                        // Clamp in case the event is before beginMs (defensive — query
                        // should already clip but some OEMs return slightly earlier events).
                        openSince[pkg] = maxOf(ev.timeStamp, beginMs)
                    }
                    UsageEvents.Event.ACTIVITY_PAUSED -> {
                        // Only count when we have a matching RESUMED inside the window.
                        // We do NOT fall back to beginMs for orphan PAUSED: on Samsung
                        // A53/A15 queryEvents can drop the matching RESUMED, and we
                        // cannot distinguish a dropped RESUMED from a legitimate
                        // session straddling beginMs. Using beginMs as fallback caused
                        // single PAUSED events late in the day to inflate the count by
                        // many hours (observed: 58320s on a 58029s window from a single
                        // late PAUSED). Under-counting straddling sessions is acceptable
                        // — the per-second tick in LimitService.enforceLimit covers the
                        // in-progress case via max(local, system).
                        val start = openSince.remove(pkg) ?: continue
                        val end = minOf(ev.timeStamp, endMs)
                        if (end > start) {
                            totals[pkg] = (totals[pkg] ?: 0L) + (end - start)
                        }
                    }
                }
            }
            // Sessions still open at endMs are intentionally NOT counted here.
            // On Samsung A53/A15, queryEvents can drop the matching PAUSED on long
            // windows. If we counted (endMs - openResume), an orphan RESUMED from
            // early in the day would inflate the total by many hours (observed:
            // 58319s on a 58029s window from a single RESUMED at 02:05). The
            // in-progress session is already tracked by LimitService's per-second
            // tick on the current foreground app, so dropping it here is safe —
            // worst case we under-count by a few seconds before the next sync
            // reconciles via max(local, system).
        } catch (e: Exception) {
            Log.e(TAG, "queryEvents failed: ${e.message}")
        }
        return totals.mapValues { (it.value / 1000L).toInt() }
            .filterValues { it > 0 }
    }

    /** Convenience: foreground seconds for a single package. */
    fun foregroundSeconds(ctx: Context, pkg: String, beginMs: Long, endMs: Long): Int {
        return foregroundSecondsByPackage(ctx, beginMs, endMs)[pkg] ?: 0
    }
}
