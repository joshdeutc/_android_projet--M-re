package com.jo.selfcontrol.ultimate

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Per-package install blocklist.
 *
 * Android has **no** API — not even for a Device Owner — that takes a package name and
 * prevents its future installation. `DISALLOW_INSTALL_APPS` is all-or-nothing, and the real
 * "only these apps may be installed" allowlist lives in managed Google Play, which requires
 * Android Enterprise enrolment we don't have (see `docs/INSTALL_BLOCKLIST.md`).
 *
 * So this is a **post-install kill switch** instead: the instant a blacklisted package appears
 * (or at every service start, for packages that shipped with the ROM), it is hidden via
 * `setApplicationHidden()` — it vanishes from the launcher and cannot be started. Matching is on
 * the exact package name, so there are no false positives.
 *
 * Hiding is deliberately preferred over a silent uninstall: it is reversible, loses no user
 * data, and works on preinstalled system apps like the stock browser — which a plain uninstall
 * cannot touch, and which are the main reason this feature exists.
 *
 * Groups live in `limits.json` under `install_blocks`, which means removing one goes through the
 * same delay gate as every other relaxation, including its own `protection_delay_sec`.
 */
object InstallBlockManager {

    private const val TAG = "SelfControl.InstallBlock"
    private const val STATE_FILE = "install_block_state.json"

    /**
     * Packages we refuse to hide no matter what the config says — hiding any of these would
     * soft-brick the device or disable the app doing the enforcing. Silently skipped, logged once.
     */
    private val HARD_GUARDS = setOf(
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.google.android.gms",
        "com.android.permissioncontroller",
    )

    // ──────────────────────────────────────
    //  Target resolution
    // ──────────────────────────────────────

    /** Every package the config wants gone, minus the ones we refuse to touch. */
    fun targetPackages(ctx: Context, config: ConfigManager.Config): Set<String> {
        if (config.installBlocks.isEmpty()) return emptySet()
        val guards = HARD_GUARDS + ctx.packageName + launcherPackages(ctx)
        val requested = config.installBlocks.flatMap { it.packages }.toSet()
        val refused = requested intersect guards
        if (refused.isNotEmpty()) {
            Log.w(TAG, "Refusing to install-block critical package(s): $refused")
        }
        return requested - guards
    }

    /** Subset of [targetPackages] actually present on the device right now. */
    fun blockedInstalledPackages(ctx: Context, config: ConfigManager.Config): Set<String> {
        val targets = targetPackages(ctx, config)
        if (targets.isEmpty()) return emptySet()
        return targets.filter { isInstalled(ctx, it) }.toSet()
    }

    /**
     * `MATCH_UNINSTALLED_PACKAGES` is required here, not a nicety: `setApplicationHidden()` makes
     * a package invisible to a plain `getApplicationInfo()` — including to us, the admin that hid
     * it. Without the flag, a package we just hid looks uninstalled on the very next sweep, and
     * [enforce] would release it again: the blocklist would quietly undo itself at the first
     * config reload or reboot.
     */
    private fun isInstalled(ctx: Context, pkg: String): Boolean = try {
        ctx.packageManager.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES)
        true
    } catch (e: Exception) {
        false
    }

    private fun launcherPackages(ctx: Context): Set<String> = try {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        ctx.packageManager.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName }
            .toSet()
    } catch (e: Exception) {
        Log.w(TAG, "Launcher resolve failed: ${e.message}")
        emptySet()
    }

    // ──────────────────────────────────────
    //  Enforcement
    // ──────────────────────────────────────

    /**
     * Bring the device in line with the config: hide everything targeted and installed, and
     * release anything we hid earlier that is no longer targeted (i.e. whose group survived
     * its protection timer and was removed).
     *
     * Idempotent — safe to call on every service start and on every config reload.
     */
    fun enforce(ctx: Context, config: ConfigManager.Config) {
        val targets = targetPackages(ctx, config)
        val present = targets.filter { isInstalled(ctx, it) }.toSet()
        val previouslyHidden = loadHiddenState(ctx)

        for (pkg in present - previouslyHidden) {
            block(ctx, pkg)
        }
        // Re-assert on packages we already own: an OS update or a manual unhide can drift.
        for (pkg in present intersect previouslyHidden) {
            DeviceOwnerHelper.hideApp(ctx, pkg, true)
            AppWatcherService.blockedApps.add(pkg)
        }

        // Releasing is driven by config intent — `previouslyHidden - targets` — and never by a
        // package merely looking absent. A hidden package is invisible to PackageManager, so
        // "absent" must never be read as "the user is allowed to have it back"; only the config
        // dropping the package (its group deleted, after its protection timer) may release it.
        val toRelease = previouslyHidden - targets
        for (pkg in toRelease) {
            release(ctx, pkg)
        }

        val newState = (previouslyHidden - toRelease) + present
        if (newState != previouslyHidden) {
            saveHiddenState(ctx, newState)
            Log.i(TAG, "Install blocklist enforced: ${newState.size} package(s) hidden")
            EventLog.log(
                ctx, "INSTALL_BLOCK",
                "enforced: +${(present - previouslyHidden).size} " +
                    "-${toRelease.size} (total ${newState.size})"
            )
        }
    }

    /**
     * Fast path for the `ACTION_PACKAGE_ADDED` receiver — a freshly installed package is hidden
     * within milliseconds instead of waiting for the next enforce sweep.
     */
    fun onPackageAdded(ctx: Context, pkg: String) {
        val config = ConfigManager.loadConfig(ctx)
        if (pkg !in targetPackages(ctx, config)) return
        Log.w(TAG, "Blacklisted package installed: $pkg → hiding immediately")
        EventLog.log(ctx, "INSTALL_BLOCK", "$pkg installed → blocked on sight")
        block(ctx, pkg)
        saveHiddenState(ctx, loadHiddenState(ctx) + pkg)
    }

    /**
     * Hide the package. Falls back to OS suspension when hiding is refused (some system
     * packages), and always registers it with the A11Y layer so a package that somehow
     * launches anyway is bounced back to Home.
     */
    private fun block(ctx: Context, pkg: String) {
        AppWatcherService.blockedApps.add(pkg)
        val hidden = DeviceOwnerHelper.hideApp(ctx, pkg, true)
        if (!hidden) {
            Log.w(TAG, "hideApp($pkg) refused — falling back to suspension")
            DeviceOwnerHelper.suspendApp(ctx, pkg)
        }
    }

    private fun release(ctx: Context, pkg: String) {
        Log.i(TAG, "Releasing $pkg from the install blocklist")
        DeviceOwnerHelper.hideApp(ctx, pkg, false)
        DeviceOwnerHelper.unsuspendApp(ctx, pkg)
        AppWatcherService.blockedApps.remove(pkg)
    }

    // ──────────────────────────────────────
    //  Persisted state
    // ──────────────────────────────────────

    /**
     * Which packages *we* hid. Needed because `setApplicationHidden` state lives in the OS with
     * no attribution, so without this we could not tell our packages apart from anyone else's
     * on release, nor know what to un-hide once a group's timer finally expires.
     */
    fun loadHiddenState(ctx: Context): Set<String> {
        val file = File(ctx.filesDir, STATE_FILE)
        if (!file.exists()) return emptySet()
        return try {
            val arr = JSONObject(file.readText()).optJSONArray("hidden") ?: return emptySet()
            (0 until arr.length()).map { arr.getString(it) }.toSet()
        } catch (e: Exception) {
            Log.e(TAG, "Error reading $STATE_FILE: ${e.message}")
            emptySet()
        }
    }

    private fun saveHiddenState(ctx: Context, packages: Set<String>) {
        val file = File(ctx.filesDir, STATE_FILE)
        try {
            val json = JSONObject().apply {
                put("hidden", org.json.JSONArray(packages.sorted()))
            }
            file.writeText(json.toString(2))
        } catch (e: Exception) {
            Log.e(TAG, "Error writing $STATE_FILE: ${e.message}")
        }
    }

    // ──────────────────────────────────────
    //  CSV import
    // ──────────────────────────────────────

    data class CsvImportResult(
        val entries: List<Pair<String, String>>,   // group name → package
        val rejected: List<String>
    ) {
        val groupCount: Int get() = entries.map { it.first }.distinct().size
        val packageCount: Int get() = entries.map { it.second }.distinct().size
    }

    private val PACKAGE_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$")

    /**
     * Parse a `group,package` CSV. A header row is detected and skipped, and its column order
     * is honoured so `package,group` works too. Blank lines and `#` comments are ignored.
     * Anything that isn't a plausible package name lands in [CsvImportResult.rejected] rather
     * than aborting the whole import.
     */
    fun parseCsv(text: String): CsvImportResult {
        val entries = mutableListOf<Pair<String, String>>()
        val rejected = mutableListOf<String>()

        var groupIdx = 0
        var packageIdx = 1
        var headerHandled = false

        // Strip a UTF-8 BOM: Excel and Notepad both add one, and it would otherwise become part
        // of the first cell and turn a valid header into an unrecognised one.
        for (raw in text.removePrefix("﻿").lineSequence()) {
            val line = raw.trim().removeSuffix("\r")
            if (line.isEmpty() || line.startsWith("#")) continue

            val cells = splitCsvLine(line)

            if (!headerHandled) {
                headerHandled = true
                val lower = cells.map { it.lowercase() }
                val g = lower.indexOfFirst { it == "group" || it == "groupe" }
                val p = lower.indexOfFirst { it == "package" || it == "packagename" }
                if (g >= 0 && p >= 0) {
                    groupIdx = g
                    packageIdx = p
                    continue   // real header — consumed
                }
                // No header: fall through and treat this line as data.
            }

            if (cells.size <= maxOf(groupIdx, packageIdx)) {
                rejected.add(line)
                continue
            }
            val group = cells[groupIdx].trim()
            val pkg = cells[packageIdx].trim()
            if (group.isEmpty() || !PACKAGE_REGEX.matches(pkg)) {
                rejected.add(line)
                continue
            }
            entries.add(group to pkg)
        }

        return CsvImportResult(entries.distinct(), rejected)
    }

    /** Minimal RFC-4180-ish split: handles double-quoted cells containing commas. */
    private fun splitCsvLine(line: String): List<String> {
        val cells = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> {
                    sb.append('"'); i++
                }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> {
                    cells.add(sb.toString()); sb.clear()
                }
                else -> sb.append(c)
            }
            i++
        }
        cells.add(sb.toString())
        return cells.map { it.trim() }
    }

    /**
     * Merge imported entries into [config], **add-only**: existing groups gain packages, new
     * groups are created, nothing is ever removed and no timer is touched. That makes an import
     * a pure hardening, so it can be applied instantly without going through the delay gate.
     */
    fun mergeIntoConfig(
        config: ConfigManager.Config,
        result: CsvImportResult
    ): ConfigManager.Config {
        if (result.entries.isEmpty()) return config
        val byGroup = result.entries.groupBy({ it.first }, { it.second })
        val existing = config.installBlocks.associateBy { it.name }.toMutableMap()

        for ((name, packages) in byGroup) {
            val current = existing[name]
            existing[name] = if (current == null) {
                ConfigManager.InstallBlockGroup(name, packages.distinct())
            } else {
                current.copy(packages = (current.packages + packages).distinct())
            }
        }
        // Preserve original ordering, append genuinely new groups at the end.
        val ordered = config.installBlocks.map { existing.getValue(it.name) } +
            existing.keys.filter { name -> config.installBlocks.none { it.name == name } }
                .map { existing.getValue(it) }
        return config.copy(installBlocks = ordered)
    }

    /** Read a CSV pushed to an absolute path (ADB workflow). Null when unreadable. */
    fun readCsvFile(path: String): String? = try {
        val file = File(path)
        if (file.isFile) file.readText() else null
    } catch (e: Exception) {
        Log.e(TAG, "Cannot read CSV at $path: ${e.message}")
        null
    }
}
