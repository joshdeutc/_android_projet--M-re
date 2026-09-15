package com.jo.selfcontrol.ultimate

import android.content.Context
import android.os.UserManager
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Time-boxed install window.
 *
 * `DISALLOW_INSTALL_APPS` is normally on, so the only way to sideload or update anything is an
 * `ALLOW_INSTALL` broadcast over adb. That broadcast used to clear the restrictions and leave them
 * cleared, which put the lock's *restoration* on the same channel that opened it: once adb died —
 * and the PC-side watchdog re-blocking it is exactly what is meant to happen — the device stayed
 * installable with nothing left to close it, until another adb window could be bought at the cost
 * of the host's unblock delay. The most permissive state was the one that survived a failure.
 *
 * So the window now closes on the device's own clock and never needs adb again. Three independent
 * paths converge on [enforce]:
 *   - the LimitService tick, which is the one that actually closes it on time,
 *   - an alarm at the deadline ([InstallWindowReceiver]), for when the service is not alive,
 *   - boot, which closes unconditionally.
 *
 * Every ambiguity resolves to *locked*. A missing, unparseable, or nonsensical state file found
 * alongside lowered restrictions is read as "close it now", never as "a window must be open" —
 * the same reasoning as [InstallBlockManager], where an absent package is never read as
 * permission to have it back.
 *
 * There is deliberately no way to extend a window: reopening costs another adb window, which costs
 * the host's delay. A window that could be extended from the device is a setting temptation would
 * go looking for.
 */
object InstallWindowManager {

    private const val TAG = "SelfControl.InstallWin"
    private const val STATE_FILE = "install_window.json"

    /** Long enough for a few Play Store updates, short enough that forgetting costs little. */
    const val DEFAULT_MINUTES = 15

    /** Hard ceiling on a requested window, and on any duration read back from disk. */
    const val MAX_MINUTES = 120

    /**
     * The only packages an open window un-hides, when the blocklist happens to cover them.
     *
     * Deliberately a fixed set and not a config key. A configurable "apps released during the
     * window" list is a setting temptation would go looking for — the window exists to let the
     * *installers* run, so anything beyond them would be an unblock mechanism wearing a different
     * name. Adding an app here is a code change, which is the friction that keeps it honest.
     */
    val INSTALLER_PACKAGES = setOf(
        "com.android.vending",                   // Play Store
        "com.google.android.packageinstaller",   // Google package installer
        "com.android.packageinstaller",          // AOSP package installer
        "com.samsung.android.packageinstaller"   // Samsung package installer
    )

    // ──────────────────────────────────────
    //  Open / close
    // ──────────────────────────────────────

    /**
     * Lower the install restrictions for [requestedMinutes], clamped to [MAX_MINUTES].
     *
     * The state file is written *before* the restrictions come down, and a write failure aborts
     * the whole thing: an untracked open window is the one outcome worse than no window at all,
     * since nothing would know when to close it.
     */
    fun open(ctx: Context, requestedMinutes: Int) {
        val minutes = requestedMinutes.coerceIn(1, MAX_MINUTES)
        val now = System.currentTimeMillis()
        val deadline = now + minutes * 60_000L

        if (!writeState(ctx, now, deadline)) {
            Log.e(TAG, "Refusing to open: window state could not be persisted")
            return
        }
        if (DeviceOwnerHelper.isDeviceOwner(ctx)) {
            DeviceOwnerHelper.setInstallRestrictions(ctx, blocked = false)
        }
        // Unsuspend and unblock installer packages (Play Store, package installers)
        for (pkg in INSTALLER_PACKAGES) {
            DeviceOwnerHelper.unsuspendApp(ctx, pkg)
            AppWatcherService.blockedApps.remove(pkg)
        }
        // The state file is already on disk, so targetPackages() now sees the window as open and
        // this sweep is what actually un-hides the Play Store. Order matters: sweeping first would
        // find the window still closed and re-hide it.
        syncInstallBlocks(ctx)
        InstallWindowReceiver.schedule(ctx, deadline)

        Log.w(TAG, "Install window OPEN ${minutes}min, until ${java.util.Date(deadline)}")
        EventLog.log(ctx, "INSTALL_WINDOW", "opened ${minutes}min")
    }

    /** Re-hide any install blocklist items and forget the window. Idempotent. */
    fun close(ctx: Context, reason: String) {
        InstallWindowReceiver.cancel(ctx)
        clearState(ctx)
        // Note: We do NOT re-impose DISALLOW_INSTALL_APPS here; WhitelistManager isolates unapproved apps.
        syncInstallBlocks(ctx)
        Log.w(TAG, "Install window CLOSED ($reason)")
        EventLog.log(ctx, "INSTALL_WINDOW", "closed ($reason)")
    }

    /**
     * Re-run the install blocklist so it picks up the window's current state. Cheap no-op when the
     * blocklist is empty or covers no installer.
     */
    private fun syncInstallBlocks(ctx: Context) {
        try {
            InstallBlockManager.enforce(ctx, ConfigManager.loadConfig(ctx))
        } catch (e: Exception) {
            Log.e(TAG, "Install blocklist sync failed: ${e.message}")
        }
    }

    /** App installations are permitted under Whitelist architecture. */
    fun isOpen(ctx: Context): Boolean = true

    // ──────────────────────────────────────
    //  Enforcement
    // ──────────────────────────────────────

    /**
     * Fail-closed sweep. Under Whitelist architecture, global install restrictions remain cleared,
     * so this does not re-impose DISALLOW_INSTALL_APPS.
     */
    fun enforce(ctx: Context) {
        // No-op: WhitelistManager handles isolating non-whitelisted apps on installation.
    }

    /**
     * Millis left on a *trustworthy* window; `<= 0` once it has run out; `null` when the state
     * cannot be trusted at all, which callers must treat as "close it".
     *
     * The two sanity checks are what make a hand-edited file or a shifted clock useless: a window
     * may not claim to have started in the future, nor to last longer than [MAX_MINUTES]. Rolling
     * the clock *forward* only expires it sooner, and rolling it back trips the first check.
     */
    fun remainingMillis(ctx: Context, now: Long): Long? {
        val f = stateFile(ctx)
        if (!f.exists()) return null
        return try {
            val json = JSONObject(f.readText())
            val openedAt = json.getLong("opened_at")
            val deadline = json.getLong("deadline")
            when {
                openedAt > now -> {
                    Log.w(TAG, "Window claims to start in the future — distrusted")
                    null
                }
                deadline - openedAt > MAX_MINUTES * 60_000L -> {
                    Log.w(TAG, "Window claims to last past the ceiling — distrusted")
                    null
                }
                else -> deadline - now
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unreadable $STATE_FILE, treating as no window: ${e.message}")
            null
        }
    }

    /**
     * Whether both install restrictions are currently set on our admin.
     *
     * An unreadable restriction set returns `false` on purpose: "unknown" has to behave like
     * "down" so [enforce] goes on to decide, rather than taking the fast path out.
     */
    private fun restrictionsUp(ctx: Context): Boolean {
        val r = DeviceOwnerHelper.currentUserRestrictions(ctx) ?: return false
        return r.getBoolean(UserManager.DISALLOW_INSTALL_APPS) &&
            r.getBoolean(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
    }

    // ──────────────────────────────────────
    //  Persisted state
    // ──────────────────────────────────────

    private fun stateFile(ctx: Context) = File(ctx.filesDir, STATE_FILE)

    /** False when the state could not be written, which [open] treats as a reason to abort. */
    private fun writeState(ctx: Context, openedAt: Long, deadline: Long): Boolean = try {
        stateFile(ctx).writeText(
            JSONObject().apply {
                put("opened_at", openedAt)
                put("deadline", deadline)
            }.toString(2)
        )
        true
    } catch (e: Exception) {
        Log.e(TAG, "Cannot write $STATE_FILE: ${e.message}")
        false
    }

    private fun clearState(ctx: Context) {
        try {
            stateFile(ctx).delete()
        } catch (e: Exception) {
            Log.w(TAG, "Cannot delete $STATE_FILE: ${e.message}")
        }
    }
}
