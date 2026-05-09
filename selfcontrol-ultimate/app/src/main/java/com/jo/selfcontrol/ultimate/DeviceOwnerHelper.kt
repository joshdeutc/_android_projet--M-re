package com.jo.selfcontrol.ultimate

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.UserManager
import android.provider.Settings
import android.util.Log

/**
 * Encapsulates DevicePolicyManager APIs available when the app is Device Owner.
 *
 * All methods are no-op (and return false where applicable) when the app is not Device Owner,
 * so callers can use them unconditionally — Device Owner becomes a strict upgrade over the
 * AccessibilityService fallback.
 */
object DeviceOwnerHelper {

    private const val TAG = "SelfControl.DO"

    private fun dpm(ctx: Context): DevicePolicyManager =
        ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private fun admin(ctx: Context): ComponentName =
        ComponentName(ctx, AdminReceiver::class.java)

    fun isDeviceOwner(ctx: Context): Boolean = try {
        dpm(ctx).isDeviceOwnerApp(ctx.packageName)
    } catch (e: Exception) {
        Log.e(TAG, "isDeviceOwner failed: ${e.message}")
        false
    }

    /**
     * Apply the locked-down policies that make the app self-protecting.
     * Idempotent — safe to call on every boot / service start.
     */
    fun applyInitialPolicies(ctx: Context) {
        if (!isDeviceOwner(ctx)) return
        val d = dpm(ctx)
        val a = admin(ctx)
        val pkg = ctx.packageName

        // 1. Block uninstall via UI (only DO can lift this)
        runCatching {
            d.setUninstallBlocked(a, pkg, true)
            Log.i(TAG, "setUninstallBlocked(true) on $pkg")
        }.onFailure { Log.e(TAG, "setUninstallBlocked failed: ${it.message}") }

        // 2. Auto-grant runtime permissions critical to enforcement
        runCatching {
            d.setPermissionGrantState(a, pkg, Manifest.permission.PACKAGE_USAGE_STATS,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
        }.onFailure { /* PACKAGE_USAGE_STATS is special; AppOps fallback handles it */ }

        runCatching {
            d.setPermissionGrantState(a, pkg, Manifest.permission.POST_NOTIFICATIONS,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
        }

        // 3. Auto-grant USAGE_STATS via AppOps (DO privilege escalation route)
        runCatching {
            val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
            val setMode = appOps.javaClass.getMethod(
                "setMode", Int::class.java, Int::class.java, String::class.java, Int::class.java
            )
            // OP_GET_USAGE_STATS = 43, MODE_ALLOWED = 0
            setMode.invoke(appOps, 43, android.os.Process.myUid(), pkg, 0)
            Log.i(TAG, "AppOp GET_USAGE_STATS granted via reflection")
        }.onFailure { Log.w(TAG, "AppOps reflection failed: ${it.message}") }

        // 4. Force-enable our AccessibilityService (and ensure A11Y is enabled globally)
        enforceA11YReEnable(ctx)

        // 5. Prevent all app installation (Play Store + sideloading) — ADB-only via ALLOW_INSTALL broadcast
        runCatching {
            d.addUserRestriction(a, UserManager.DISALLOW_INSTALL_APPS)
            d.addUserRestriction(a, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
        }

        // 6. Prevent factory reset (blocks Settings option and sometimes recovery)
        runCatching {
            d.addUserRestriction(a, UserManager.DISALLOW_FACTORY_RESET)
            Log.i(TAG, "Restriction added: DISALLOW_FACTORY_RESET")
        }

        // 7. Prevent booting into Safe Mode (where A11Y services are disabled)
        runCatching {
            d.addUserRestriction(a, UserManager.DISALLOW_SAFE_BOOT)
            Log.i(TAG, "Restriction added: DISALLOW_SAFE_BOOT")
        }
    }

    /**
     * Toggle the install-related user restrictions. Used by dev-only ALLOW_INSTALL / BLOCK_INSTALL
     * broadcasts so we can push updated APKs without losing Device Owner status.
     */
    fun setInstallRestrictions(ctx: Context, blocked: Boolean) {
        if (!isDeviceOwner(ctx)) return
        val d = dpm(ctx)
        val a = admin(ctx)
        runCatching {
            if (blocked) {
                d.addUserRestriction(a, UserManager.DISALLOW_INSTALL_APPS)
                d.addUserRestriction(a, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
                Log.i(TAG, "Install restrictions re-applied")
            } else {
                d.clearUserRestriction(a, UserManager.DISALLOW_INSTALL_APPS)
                d.clearUserRestriction(a, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
                Log.i(TAG, "Install restrictions cleared")
            }
        }.onFailure { Log.e(TAG, "setInstallRestrictions($blocked) failed: ${it.message}") }
    }

    /**
     * Re-write the secure setting that lists enabled accessibility services so ours stays bound.
     * Called periodically from LimitService to undo any manual disable.
     */
    fun enforceA11YReEnable(ctx: Context) {
        if (!isDeviceOwner(ctx)) return
        val d = dpm(ctx)
        val a = admin(ctx)
        val pkg = ctx.packageName
        val a11yComponent = "$pkg/$pkg.AppWatcherService"

        runCatching {
            // Read current list, append ours if missing
            val current = Settings.Secure.getString(
                ctx.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            val merged = if (current.split(':').any { it.equals(a11yComponent, ignoreCase = true) }) {
                current
            } else if (current.isBlank()) {
                a11yComponent
            } else {
                "$current:$a11yComponent"
            }
            d.setSecureSetting(a, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, merged)
            d.setSecureSetting(a, Settings.Secure.ACCESSIBILITY_ENABLED, "1")
        }.onFailure { Log.e(TAG, "enforceA11YReEnable failed: ${it.message}") }
    }

    /**
     * OS-level app suspension. Returns true if the call was issued (DO active),
     * false if caller must fall back to HOME spam via AccessibilityService.
     */
    fun suspendApp(ctx: Context, pkg: String): Boolean {
        if (!isDeviceOwner(ctx)) return false
        return runCatching {
            val failed = dpm(ctx).setPackagesSuspended(admin(ctx), arrayOf(pkg), true)
            // returns array of packages that could NOT be suspended
            failed.isEmpty() || !failed.contains(pkg)
        }.getOrElse {
            Log.e(TAG, "suspendApp($pkg) failed: ${it.message}")
            false
        }
    }

    fun unsuspendApp(ctx: Context, pkg: String): Boolean {
        if (!isDeviceOwner(ctx)) return false
        return runCatching {
            dpm(ctx).setPackagesSuspended(admin(ctx), arrayOf(pkg), false)
            true
        }.getOrElse {
            Log.e(TAG, "unsuspendApp($pkg) failed: ${it.message}")
            false
        }
    }

    /**
     * Sweep every installed package and lift any DO-applied suspension.
     * Used at service startup to recover from stuck states (e.g. daily reset before
     * the unsuspend-on-rollover bug was fixed, or service killed while apps were suspended
     * since `suspendedApps` is in-memory only).
     * Returns the list of packages that were actually unsuspended.
     */
    fun clearAllStuckSuspensions(ctx: Context): List<String> {
        if (!isDeviceOwner(ctx)) return emptyList()
        val d = dpm(ctx)
        val a = admin(ctx)
        val pm = ctx.packageManager
        val attempted = mutableListOf<String>()
        val failed = mutableListOf<String>()
        runCatching {
            val installed = pm.getInstalledApplications(0)
            for (app in installed) {
                val pkg = app.packageName
                if (pkg == ctx.packageName) continue
                runCatching {
                    if (pm.isPackageSuspended(pkg)) {
                        attempted.add(pkg)
                        // Returns the array of packages that could NOT be unsuspended.
                        val notModified = d.setPackagesSuspended(a, arrayOf(pkg), false)
                        if (notModified != null && notModified.contains(pkg)) {
                            failed.add(pkg)
                            Log.w(TAG, "  ↳ unsuspend FAILED for $pkg (returned in notModified)")
                        }
                    }
                }.onFailure { Log.w(TAG, "  ↳ unsuspend threw for $pkg: ${it.message}") }
            }
            if (attempted.isNotEmpty()) {
                val ok = attempted - failed.toSet()
                Log.w(TAG, "clearAllStuckSuspensions: ${ok.size} OK / ${failed.size} failed. OK=$ok failed=$failed")
            }
        }.onFailure { Log.e(TAG, "clearAllStuckSuspensions failed: ${it.message}") }
        return attempted - failed.toSet()
    }

    /** Hide an app entirely (used for Nuclear Mode OS-level enforcement). */
    fun hideApp(ctx: Context, pkg: String, hidden: Boolean): Boolean {
        if (!isDeviceOwner(ctx)) return false
        return runCatching {
            dpm(ctx).setApplicationHidden(admin(ctx), pkg, hidden)
        }.getOrElse {
            Log.e(TAG, "hideApp($pkg, $hidden) failed: ${it.message}")
            false
        }
    }

    /**
     * Emergency backdoor: lift uninstall protection then relinquish Device Owner status,
     * leaving the app installable/uninstallable as a normal app.
     */
    fun clearDeviceOwner(ctx: Context) {
        if (!isDeviceOwner(ctx)) return
        val d = dpm(ctx)
        val a = admin(ctx)
        val pkg = ctx.packageName

        runCatching {
            d.setUninstallBlocked(a, pkg, false)
            Log.w(TAG, "setUninstallBlocked(false) — uninstall now permitted")
        }
        runCatching {
            d.clearUserRestriction(a, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
            d.clearUserRestriction(a, UserManager.DISALLOW_FACTORY_RESET)
            d.clearUserRestriction(a, UserManager.DISALLOW_SAFE_BOOT)
        }
        runCatching {
            @Suppress("DEPRECATION")
            d.clearDeviceOwnerApp(pkg)
            Log.w(TAG, "Device Owner status cleared")
        }.onFailure { Log.e(TAG, "clearDeviceOwnerApp failed: ${it.message}") }
    }

    /**
     * One-shot status string for the dashboard UI.
     */
    fun statusLabel(ctx: Context): String = when {
        isDeviceOwner(ctx) -> "✅ Device Owner actif — uninstall bloqué"
        PermissionHelper.isDeviceAdminActive(ctx) -> "⚠️ Device Admin seul — utilise A11Y comme fallback"
        else -> "❌ Aucune protection système — A11Y uniquement"
    }
}
