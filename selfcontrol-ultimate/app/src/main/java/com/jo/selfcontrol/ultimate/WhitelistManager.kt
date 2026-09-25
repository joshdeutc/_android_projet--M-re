package com.jo.selfcontrol.ultimate

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Strict Whitelist (Zero-Trust) Engine for Device Owner / Admin flavors.
 *
 * Every 3rd-party application on the device MUST belong to the approved whitelist.
 * If an unauthorized application is present or installed (via Play Store, adb, sideload, etc.):
 *   1. Immediately hidden (`setApplicationHidden(true)`) — vanishes from the launcher
 *   2. Immediately suspended (`setPackagesSuspended(true)`) — frozen at the OS level
 *   3. Added to A11Y kill-switch (`AppWatcherService.blockedApps`) — instant HOME bounce
 *
 * Adding a new application to the whitelist requires an irrevocable quarantine delay (e.g. 24h / 48h).
 * Once the delay finishes, the application is automatically released (unhidden and unsuspended).
 * Removing an app from the whitelist locks it down immediately.
 */
object WhitelistManager {

    private const val TAG = "SelfControl.Whitelist"
    private const val STATE_FILE = "whitelist_state.json"
    private const val HIDDEN_STATE_FILE = "whitelist_hidden.json"
    const val DEFAULT_DELAY_HOURS = 24

    /**
     * Set to true if unauthorized apps should also be uninstalled silently.
     * Kept false for now to preserve application data while completely blocking access.
     */
    var SILENT_UNINSTALL_ENABLED = false

    private val HARD_GUARDS = setOf(
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.google.android.gms",
        "com.android.permissioncontroller",
        "com.android.vending",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.samsung.android.packageinstaller",
        "com.android.phone",
        "com.android.server.telecom",
        "com.android.providers.telephony",
        "com.google.android.dialer",
        "com.google.android.apps.messaging",
        "com.android.mms",
        "com.google.android.inputmethod.latin",
        "com.google.android.GoogleCamera",
        "com.google.android.apps.photos",
        "com.google.android.calendar",
        "com.google.android.contacts",
        "com.google.android.gm",
        "com.google.android.deskclock",
        "com.google.android.calculator"
    )

    /**
     * Pre-populated with the user's initial legitimate 3rd-party apps across tested devices.
     * Note: com.paris.velib is deliberately excluded for testing.
     */
    val INITIAL_ALLOWED_PACKAGES = setOf(
        "com.jo.lycee",
        "com.waze",
        "fr.gouv.franceidentite",
        "com.shazam.android",
        "com.app.tgtg",
        "com.azure.authenticator",
        "com.memrise.android.memrisecompanion",
        "ch.protonmail.android",
        "com.google.android.apps.docs.editors.docs",
        "com.enki.Enki750g",
        "com.tribab.tricount.android",
        "com.jo.notubeplayer",
        "air.com.unit9.bkFrApp",
        "com.jo.selfcontrol.ultimate",
        "com.guardian",
        "com.vsct.vsc.mobile.horaireetresa.android",
        "cc.dreamspark.intervaltimer",
        "com.lexilize.fc",
        "com.jo.discordpersonal",
        "com.microsoft.skydrive",
        "com.sportigoaccess",
        "com.ttxapps.autosync",
        "fr.epiconcept.pdfnoteskiosk",
        "com.jo.community",
        "com.google.android.apps.translate",
        "fr.iledefrance.labaz",
        "com.passbolt.mobile.android",
        "com.eurotalk.uTalk",
        "fr.bouyguestelecom.ecm.android",
        "com.voxsquare.voxpay.android",
        "coop.up.beneficiaire",
        "co.feeld",
        "tv.arte.plus7",
        "com.whatsapp",
        "com.intsig.camscanner",
        "fr.doctolib.www",
        "com.google.android.contactkeys",
        "com.bambuna.podcastaddict",
        "in.krosbits.musicolet",
        "mobi.societegenerale.mobile.lappli",
        "fr.icdc.sl6.app",
        "com.boursorama.android.clients",
        "com.shotguntheapp.android",
        "com.completude.professeur",
        "com.chyrpe.chyrpe",
        "com.fabernovel.ratp",
        "com.google.android.keep",
        "com.english.progress.learn",
        "com.google.android.safetycore",
        "com.ttxapps.drivesync",
        "com.jo.tapology",
        "com.google.android.apps.authenticator2",
        "com.chess",
        // Additional apps from user devices (Redmi, etc.)
        "com.discord",
        "com.snapchat.android",
        "deezer.android.app",
        "fr.leboncoin",
        "fr.vinted",
        "com.facebook.katana",
        "com.duolingo",
        "com.amazon.mShop.android.shopping",
        "com.booking",
        "com.linkedin.android",
        "com.audible.application",
        "com.google.android.apps.youtube.music",
        "com.google.android.apps.tachyon",
        "com.google.android.apps.docs",
        "com.google.android.apps.magazines",
        "com.google.android.apps.podcasts",
        "com.google.android.videos",
        "com.agoda.mobile.consumer",
        "cn.wps.xiaomi.abroad.lite",
        "com.xiaomi.midrop",
        "com.xiaomi.scanner",
        "com.xiaomi.smarthome",
        "com.miui.calculator",
        "com.miui.weather2",
        "com.miui.notes",
        "com.miui.screenrecorder",
        "com.miui.mediaeditor",
        "com.miui.compass",
        "com.miui.android.fashiongallery",
        "com.duokan.phone.remotecontroller",
        "com.amazon.appmanager",
        "com.amazon.avod.thirdpartyclient",
        "com.google.ar.core",
        "com.android.soundrecorder",
        "com.opera.preinstall",
        "com.mi.global.shop",
        "com.mi.global.bbs"
    )

    /**
     * Cleans an input which may either be a plain package name (e.g. "com.spotify.music")
     * or a full Play Store URL (e.g. "https://play.google.com/store/apps/details?id=com.spotify.music&hl=fr").
     */
    fun sanitizePackageName(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.contains("id=")) {
            val regex = Regex("[?&]id=([a-zA-Z0-9._]+)")
            val match = regex.find(trimmed)
            if (match != null) {
                return match.groupValues[1]
            }
        }
        return trimmed.substringBefore("&").substringBefore("?").trim()
    }

    /**
     * Collects all currently installed non-system packages on the device so that an initial
     * installation on a new phone does not lock out existing legitimate applications.
     */
    fun getInitialAllowedPackages(ctx: Context): Set<String> {
        val result = INITIAL_ALLOWED_PACKAGES.filterNot { isGuarded(ctx, it) }.toMutableSet()
        try {
            val pm = ctx.packageManager
            val installed = pm.getInstalledPackages(0)
            for (info in installed) {
                if (info.packageName == ctx.packageName) continue
                if (isGuarded(ctx, info)) continue
                result.add(info.packageName)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error discovering initial installed packages: ${e.message}")
        }
        return result.filterNot { isGuarded(ctx, it) }.toSet()
    }

    data class PendingRequest(
        val packageName: String,
        val requestedAt: Long,
        val availableAt: Long
    )

    data class WhitelistState(
        val enabled: Boolean = false,
        val allowedPackages: Set<String> = INITIAL_ALLOWED_PACKAGES,
        val pendingRequests: List<PendingRequest> = emptyList(),
        val quarantineDelayHours: Int = DEFAULT_DELAY_HOURS,
        val useGlobalDelay: Boolean = false,
        val pendingDelayHours: Int? = null,
        val pendingDelayUseGlobal: Boolean = false,
        val pendingDelayExecuteAt: Long = 0L
    )

    @Volatile private var cachedState: WhitelistState? = null
    @Volatile private var lastStateModified: Long = 0L

    @Synchronized
    fun loadState(ctx: Context): WhitelistState {
        return checkAndPromotePendingRequests(ctx)
    }

    @Synchronized
    fun loadStateInternal(ctx: Context): WhitelistState {
        val file = File(ctx.filesDir, STATE_FILE)
        if (!file.exists()) {
            val initialAllowed = getInitialAllowedPackages(ctx)
            val defaultState = WhitelistState(enabled = false, allowedPackages = initialAllowed)
            saveState(ctx, defaultState)
            return defaultState
        }
        val lastMod = file.lastModified()
        val mem = cachedState
        if (mem != null && lastMod == lastStateModified && lastMod > 0L) {
            return mem
        }
        return try {
            val json = JSONObject(file.readText())
            val enabled = json.optBoolean("enabled", false)
            val allowedArr = json.optJSONArray("allowed_packages") ?: JSONArray()
            val rawAllowed = (0 until allowedArr.length()).map { allowedArr.getString(it) }.toSet()
            // Clean up any guarded/system packages if present
            val hasGuarded = rawAllowed.any { isGuarded(ctx, it) }
            val allowed = if (hasGuarded) rawAllowed.filterNot { isGuarded(ctx, it) }.toSet() else rawAllowed

            val pendingArr = json.optJSONArray("pending_requests") ?: JSONArray()
            val pending = (0 until pendingArr.length()).mapNotNull { i ->
                val obj = pendingArr.optJSONObject(i) ?: return@mapNotNull null
                val pkg = obj.optString("package")
                val reqAt = obj.optLong("requested_at")
                val availAt = obj.optLong("available_at")
                if (pkg.isNotBlank()) PendingRequest(pkg, reqAt, availAt) else null
            }

            val quarantineHours = json.optInt("quarantine_delay_hours", DEFAULT_DELAY_HOURS)
            val useGlobal = json.optBoolean("use_global_delay", false)
            val pendingDelayHours = if (json.has("pending_delay_hours") && !json.isNull("pending_delay_hours")) json.getInt("pending_delay_hours") else null
            val pendingDelayUseGlobal = json.optBoolean("pending_delay_use_global", false)
            val pendingDelayExecuteAt = json.optLong("pending_delay_execute_at", 0L)

            val state = WhitelistState(
                enabled = enabled,
                allowedPackages = allowed,
                pendingRequests = pending,
                quarantineDelayHours = quarantineHours,
                useGlobalDelay = useGlobal,
                pendingDelayHours = pendingDelayHours,
                pendingDelayUseGlobal = pendingDelayUseGlobal,
                pendingDelayExecuteAt = pendingDelayExecuteAt
            )
            cachedState = state
            lastStateModified = lastMod

            if (hasGuarded) {
                Log.i(TAG, "Pruned ${rawAllowed.size - allowed.size} system packages from whitelist state (remaining: ${allowed.size})")
                saveState(ctx, state)
            }
            state
        } catch (e: Exception) {
            Log.e(TAG, "Error reading $STATE_FILE: ${e.message}", e)
            WhitelistState()
        }
    }

    /**
     * Checks if any pending requests or quarantine delay changes have expired.
     * Automatically promotes ready applications to the whitelist, releases them
     * from OS hide/suspend, and notifies the user.
     */
    @Synchronized
    fun checkAndPromotePendingRequests(ctx: Context): WhitelistState {
        var state = loadStateInternal(ctx)
        if (!state.enabled) return state

        val now = System.currentTimeMillis()
        var modified = false

        // 0. Check pending delay change
        if (state.pendingDelayExecuteAt in 1..now && state.pendingDelayHours != null) {
            val newHours = state.pendingDelayHours!!
            val useGlobal = state.pendingDelayUseGlobal
            state = state.copy(
                quarantineDelayHours = newHours,
                useGlobalDelay = useGlobal,
                pendingDelayHours = null,
                pendingDelayUseGlobal = false,
                pendingDelayExecuteAt = 0L
            )
            modified = true
            Log.w(TAG, "Applied pending whitelist delay change -> ${newHours}h (global=$useGlobal)")
            EventLog.log(ctx, "WHITELIST", "Applied pending delay change: ${newHours}h (global=$useGlobal)")
        }

        // 1. Check pending requests that reached their unlock time
        val ready = state.pendingRequests.filter { it.availableAt <= now }
        if (ready.isNotEmpty()) {
            val newlyAllowed = ready.map { it.packageName }.toSet()
            val updatedPending = state.pendingRequests.filter { it.availableAt > now }
            val updatedAllowed = state.allowedPackages + newlyAllowed
            state = state.copy(allowedPackages = updatedAllowed, pendingRequests = updatedPending)
            modified = true
            Log.w(TAG, "Pending whitelist delay completed for: $newlyAllowed -> Added to whitelist")
            EventLog.log(ctx, "WHITELIST", "Quarantine delay expired: added $newlyAllowed to whitelist")

            // Release the packages immediately: unhide + unsuspend + remove from blockedApps
            val hidden = loadHiddenState(ctx).toMutableSet()
            for (pkg in newlyAllowed) {
                release(ctx, pkg)
                hidden.remove(pkg)
                notifyAppUnlocked(ctx, pkg)
            }
            saveHiddenState(ctx, hidden)
        }

        if (modified) {
            saveState(ctx, state)
        }

        scheduleNextUnlockAlarm(ctx)
        return state
    }

    fun scheduleNextUnlockAlarm(ctx: Context) {
        val state = loadStateInternal(ctx)
        val now = System.currentTimeMillis()
        val nextPending = state.pendingRequests.map { it.availableAt }.filter { it > now }.minOrNull()
        val nextDelay = if (state.pendingDelayExecuteAt > now) state.pendingDelayExecuteAt else null

        val candidateTimes = listOfNotNull(nextPending, nextDelay)
        if (candidateTimes.isEmpty()) return
        val targetTime = candidateTimes.min()

        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(ctx, CommandReceiver::class.java).apply {
                action = "com.jo.selfcontrol.ultimate.CHECK_WHITELIST_EXPIRATION"
            }
            val pi = PendingIntent.getBroadcast(
                ctx,
                8888,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, targetTime, pi)
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, targetTime, pi)
            }
            Log.i(TAG, "Scheduled whitelist unlock alarm for ${java.util.Date(targetTime)}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule unlock alarm: ${e.message}")
        }
    }

    fun notifyAppUnlocked(ctx: Context, pkg: String) {
        try {
            ensureNotificationChannel(ctx)
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val appLabel = try {
                val ai = ctx.packageManager.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES)
                ctx.packageManager.getApplicationLabel(ai).toString()
            } catch (e: Exception) {
                pkg.substringAfterLast('.')
            }

            val launchIntent = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: Intent(ctx, MainActivity::class.java)
            val pi = PendingIntent.getActivity(
                ctx,
                pkg.hashCode() + 2,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notif = NotificationCompat.Builder(ctx, NOTIF_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("🎉 Application autorisée : $appLabel")
                .setContentText("Le délai de quarantaine est terminé. L'application est utilisable.")
                .setStyle(NotificationCompat.BigTextStyle().bigText(
                    "L'application \"$appLabel\" a terminé sa période de quarantaine.\n" +
                    "Elle a été intégrée à la Whitelist et est maintenant débloquée et visible !"
                ))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()

            nm.notify(300000 + pkg.hashCode(), notif)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post unlocked notification for $pkg: ${e.message}")
        }
    }

    @Synchronized
    fun saveState(ctx: Context, state: WhitelistState) {
        val file = File(ctx.filesDir, STATE_FILE)
        try {
            val json = JSONObject().apply {
                put("enabled", state.enabled)
                put("allowed_packages", JSONArray(state.allowedPackages.sorted()))
                val pendingArr = JSONArray()
                state.pendingRequests.forEach { req ->
                    pendingArr.put(JSONObject().apply {
                        put("package", req.packageName)
                        put("requested_at", req.requestedAt)
                        put("available_at", req.availableAt)
                    })
                }
                put("pending_requests", pendingArr)
                put("quarantine_delay_hours", state.quarantineDelayHours)
                put("use_global_delay", state.useGlobalDelay)
                if (state.pendingDelayHours != null) {
                    put("pending_delay_hours", state.pendingDelayHours)
                } else {
                    put("pending_delay_hours", JSONObject.NULL)
                }
                put("pending_delay_use_global", state.pendingDelayUseGlobal)
                put("pending_delay_execute_at", state.pendingDelayExecuteAt)
            }
            file.writeText(json.toString(2))
            cachedState = state
            lastStateModified = file.lastModified()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving $STATE_FILE: ${e.message}", e)
        }
    }

    @Synchronized
    fun loadHiddenState(ctx: Context): Set<String> {
        val file = File(ctx.filesDir, HIDDEN_STATE_FILE)
        if (!file.exists()) return emptySet()
        return try {
            val arr = JSONObject(file.readText()).optJSONArray("hidden") ?: return emptySet()
            (0 until arr.length()).map { arr.getString(it) }.toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    @Synchronized
    fun saveHiddenState(ctx: Context, packages: Set<String>) {
        val file = File(ctx.filesDir, HIDDEN_STATE_FILE)
        try {
            val json = JSONObject().apply {
                put("hidden", JSONArray(packages.sorted()))
            }
            file.writeText(json.toString(2))
        } catch (e: Exception) {
            Log.e(TAG, "Error saving $HIDDEN_STATE_FILE: ${e.message}")
        }
    }

    /**
     * Returns the effective quarantine delay in seconds.
     * If [useGlobalDelay] is enabled, queries DelayManager's active protection delay.
     */
    fun getEffectiveQuarantineDelaySeconds(ctx: Context): Long {
        val state = loadState(ctx)
        return if (state.useGlobalDelay) {
            DelayManager.getCurrentEffectiveDelaySeconds(ctx).toLong().coerceAtLeast(60L)
        } else {
            (state.quarantineDelayHours * 3600L).coerceAtLeast(60L)
        }
    }

    /**
     * Returns the effective quarantine delay formatted as an integer number of hours.
     */
    fun getEffectiveQuarantineDelayHours(ctx: Context): Int {
        val sec = getEffectiveQuarantineDelaySeconds(ctx)
        return (sec / 3600L).toInt().coerceAtLeast(1)
    }

    /**
     * Sets the quarantine delay.
     * Hardening rules:
     * - If increasing the delay (or if settings are unlocked via DelayManager): immediate.
     * - If reducing the delay: deferred by the current delay duration to prevent impulse relaxation.
     */
    fun setQuarantineDelay(ctx: Context, newHours: Int, useGlobal: Boolean = false): String {
        val state = loadState(ctx)
        val currentSec = getEffectiveQuarantineDelaySeconds(ctx)
        val newSec = if (useGlobal) {
            DelayManager.getCurrentEffectiveDelaySeconds(ctx).toLong().coerceAtLeast(60L)
        } else {
            (newHours * 3600L).coerceAtLeast(60L)
        }

        val now = System.currentTimeMillis()
        val desc = if (useGlobal) "aligné sur le délai général" else "$newHours heure(s)"

        if (newSec >= currentSec || DelayManager.isSettingsUnlocked(ctx)) {
            val updated = state.copy(
                quarantineDelayHours = if (newHours > 0) newHours else state.quarantineDelayHours,
                useGlobalDelay = useGlobal,
                pendingDelayHours = null,
                pendingDelayUseGlobal = false,
                pendingDelayExecuteAt = 0L
            )
            saveState(ctx, updated)
            Log.w(TAG, "Quarantine delay set immediately to $desc")
            EventLog.log(ctx, "WHITELIST", "Quarantine delay set immediately to $desc")
            return "Délai de quarantaine mis à jour : $desc"
        } else {
            val executeAt = now + currentSec * 1000L
            val updated = state.copy(
                pendingDelayHours = newHours,
                pendingDelayUseGlobal = useGlobal,
                pendingDelayExecuteAt = executeAt
            )
            saveState(ctx, updated)
            val waitDesc = if (currentSec >= 3600) "${currentSec / 3600}h" else "${currentSec / 60}m"
            Log.w(TAG, "Quarantine delay reduction to $desc scheduled for ${java.util.Date(executeAt)}")
            EventLog.log(ctx, "WHITELIST", "Quarantine delay reduction to $desc scheduled in $waitDesc")
            return "Réduction vers $desc programmée dans $waitDesc (anti-impulsion)"
        }
    }

    /**
     * Cancels any pending reduction of the quarantine delay. Hardening action, applies immediately.
     */
    fun cancelPendingDelayChange(ctx: Context): Boolean {
        val state = loadState(ctx)
        if (state.pendingDelayExecuteAt > 0L) {
            saveState(ctx, state.copy(
                pendingDelayHours = null,
                pendingDelayUseGlobal = false,
                pendingDelayExecuteAt = 0L
            ))
            Log.w(TAG, "Canceled pending whitelist delay change")
            EventLog.log(ctx, "WHITELIST", "Canceled pending whitelist delay change")
            return true
        }
        return false
    }

    /**
     * Enables or disables the whitelist.
     * When disabled, any quarantined / hidden packages are immediately released.
     * When enabled, enforcement runs across installed packages.
     */
    fun setWhitelistEnabled(ctx: Context, enabled: Boolean, fromAdb: Boolean = false): Boolean {
        if (BuildConfig.WHITELIST_ADB_ONLY && !fromAdb) {
            Log.w(TAG, "Refused UI whitelist toggle: flavor requires ADB")
            return false
        }
        synchronized(this) {
            val state = loadState(ctx)
            if (state.enabled == enabled) return true
            saveState(ctx, state.copy(enabled = enabled))
            if (enabled) {
                enforce(ctx)
            } else {
                val hidden = loadHiddenState(ctx)
                for (pkg in hidden) {
                    release(ctx, pkg)
                }
                saveHiddenState(ctx, emptySet())
            }
            Log.w(TAG, "Whitelist ${if (enabled) "ENABLED" else "DISABLED"}")
            EventLog.log(ctx, "WHITELIST", "Whitelist ${if (enabled) "ENABLED" else "DISABLED"}")
            return true
        }
    }

    fun isWhitelisted(ctx: Context, pkg: String): Boolean {
        val cleanPkg = sanitizePackageName(pkg)
        if (cleanPkg in HARD_GUARDS) return true
        if (cleanPkg == ctx.packageName) return true
        if (isGuarded(ctx, cleanPkg)) return true
        val state = loadState(ctx)
        return cleanPkg in state.allowedPackages
    }

    private val guardedCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    @Volatile private var cachedLauncherPackages: Set<String> = emptySet()
    @Volatile private var lastLauncherCheck: Long = 0L
    @Volatile private var cachedImePackages: Set<String> = emptySet()
    @Volatile private var lastImeCheck: Long = 0L

    fun isGuarded(ctx: Context, pkgInfo: PackageInfo): Boolean {
        val pkg = pkgInfo.packageName
        guardedCache[pkg]?.let { return it }
        if (pkg in HARD_GUARDS || pkg == ctx.packageName) {
            guardedCache[pkg] = true
            return true
        }
        val appInfo = pkgInfo.applicationInfo
        val isSys = if (appInfo != null) {
            ((appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0) || ((appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0)
        } else false
        if (isSys) {
            guardedCache[pkg] = true
            return true
        }
        if (pkg in launcherPackages(ctx) || pkg in inputMethodPackages(ctx)) {
            guardedCache[pkg] = true
            return true
        }
        guardedCache[pkg] = false
        return false
    }

    fun isGuarded(ctx: Context, pkg: String): Boolean {
        guardedCache[pkg]?.let { return it }
        if (pkg in HARD_GUARDS || pkg == ctx.packageName) {
            guardedCache[pkg] = true
            return true
        }
        val pm = ctx.packageManager
        val isSys = try {
            val appInfo = pm.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES)
            ((appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0) || ((appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0)
        } catch (e: Exception) {
            false
        }
        if (isSys) {
            guardedCache[pkg] = true
            return true
        }
        if (pkg in launcherPackages(ctx) || pkg in inputMethodPackages(ctx)) {
            guardedCache[pkg] = true
            return true
        }
        guardedCache[pkg] = false
        return false
    }

    private fun launcherPackages(ctx: Context): Set<String> {
        val now = System.currentTimeMillis()
        if (cachedLauncherPackages.isNotEmpty() && (now - lastLauncherCheck < 60_000L)) {
            return cachedLauncherPackages
        }
        return try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val set = ctx.packageManager.queryIntentActivities(intent, 0)
                .map { it.activityInfo.packageName }
                .toSet()
            cachedLauncherPackages = set
            lastLauncherCheck = now
            set
        } catch (e: Exception) {
            cachedLauncherPackages
        }
    }

    private fun inputMethodPackages(ctx: Context): Set<String> {
        val now = System.currentTimeMillis()
        if (cachedImePackages.isNotEmpty() && (now - lastImeCheck < 60_000L)) {
            return cachedImePackages
        }
        return try {
            val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            val set = imm?.enabledInputMethodList?.map { it.packageName }?.toSet() ?: emptySet()
            cachedImePackages = set
            lastImeCheck = now
            set
        } catch (e: Exception) {
            cachedImePackages
        }
    }

    /**
     * Enforces the whitelist across all installed packages.
     * Promotes expired pending requests, unhides newly allowed apps, and locks unauthorized apps.
     */
    fun enforce(ctx: Context) {
        val state = checkAndPromotePendingRequests(ctx)
        if (!state.enabled) return

        // 1. Un-hide / release any packages that are now allowed OR are system/guarded apps
        val hidden = loadHiddenState(ctx).toMutableSet()
        val toRelease = hidden.filter { pkg ->
            pkg in state.allowedPackages || isGuarded(ctx, pkg)
        }.toSet()
        for (pkg in toRelease) {
            release(ctx, pkg)
            hidden.remove(pkg)
            Log.i(TAG, "Released guarded/allowed app: $pkg")
        }

        // 2. Scan installed packages
        val pm = ctx.packageManager
        val installed = try {
            pm.getInstalledPackages(PackageManager.MATCH_UNINSTALLED_PACKAGES)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get installed packages: ${e.message}")
            return
        }

        for (pkgInfo in installed) {
            val pkg = pkgInfo.packageName
            if (isGuarded(ctx, pkgInfo)) continue

            if (pkg !in state.allowedPackages) {
                Log.w(TAG, "🚨 UNAUTHORIZED APP DETECTED on device: $pkg -> Blocking access!")
                EventLog.log(ctx, "WHITELIST", "Unauthorized app $pkg detected -> Blocked access")
                neutralize(ctx, pkg)
                hidden.add(pkg)
            }
        }

        saveHiddenState(ctx, hidden)
    }

    /**
     * Fast-path hook invoked from ACTION_PACKAGE_ADDED / ACTION_PACKAGE_REPLACED receiver.
     */
    fun onPackageAdded(ctx: Context, pkg: String) {
        val state = loadState(ctx)
        if (!state.enabled) return
        if (pkg == ctx.packageName) return
        if (pkg in HARD_GUARDS) return
        if (isGuarded(ctx, pkg)) {
            Log.i(TAG, "Installed package $pkg is system or guarded app. Allowed.")
            return
        }
        if (pkg in state.allowedPackages) {
            Log.i(TAG, "Installed package $pkg is in whitelist. Allowed.")
            return
        }

        Log.w(TAG, "🚨 UNAUTHORIZED APP INSTALLED: $pkg -> Neutralizing immediately!")
        EventLog.log(ctx, "WHITELIST", "Blocked on sight: $pkg installed without whitelist approval")
        neutralize(ctx, pkg)
        val hidden = loadHiddenState(ctx) + pkg
        saveHiddenState(ctx, hidden)
        notifyAppQuarantined(ctx, pkg)
    }

    private const val NOTIF_CHANNEL_ID = "whitelist_quarantine_alerts"

    private fun ensureNotificationChannel(ctx: Context) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID,
                "Alertes Whitelist & Nouvelles Applications",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications pour demander l'ajout d'une nouvelle application à la Whitelist"
                enableVibration(true)
            }
            nm.createNotificationChannel(channel)
        }
    }

    /**
     * Posts an interactive system notification when an unauthorized app is installed.
     * Tapping opens MainActivity directly to the quarantine confirmation dialog.
     */
    fun notifyAppQuarantined(ctx: Context, pkg: String) {
        try {
            ensureNotificationChannel(ctx)
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

            val appLabel = try {
                val ai = ctx.packageManager.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES)
                ctx.packageManager.getApplicationLabel(ai).toString()
            } catch (e: Exception) {
                pkg.substringAfterLast('.')
            }

            val delaySec = getEffectiveQuarantineDelaySeconds(ctx)
            val delayText = if (delaySec >= 3600) "${delaySec / 3600}h" else "${delaySec / 60}m"

            val intent = Intent(ctx, MainActivity::class.java).apply {
                action = "com.jo.selfcontrol.ultimate.ACTION_WHITELIST_PROMPT"
                putExtra("extra_package", pkg)
                putExtra("extra_label", appLabel)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

            val pi = PendingIntent.getActivity(
                ctx,
                pkg.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notif = NotificationCompat.Builder(ctx, NOTIF_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("📥 Nouvelle application : $appLabel")
                .setContentText("Toucher pour demander l'ajout à la Whitelist ($delayText)")
                .setStyle(NotificationCompat.BigTextStyle().bigText(
                    "L'application \"$appLabel\" ($pkg) a été installée et immédiatement isolée.\n\n" +
                    "Touchez ici pour demander son ajout à la Whitelist (délai de quarantaine : $delayText)."
                ))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()

            nm.notify(200000 + pkg.hashCode(), notif)
            Log.i(TAG, "Notification posted for quarantined app $pkg ($appLabel)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post quarantined notification for $pkg: ${e.message}", e)
        }
    }

    /**
     * Blocks access to an unauthorized package:
     * 1. OS Hide (removes launcher icon immediately)
     * 2. OS Suspend (greys out, blocks launch at OS level)
     * 3. A11Y kill switch (HOME spam if foregrounded)
     * 4. Optional: Device Owner silent uninstallation
     */
    private fun neutralize(ctx: Context, pkg: String) {
        AppWatcherService.blockedApps.add(pkg)
        DeviceOwnerHelper.hideApp(ctx, pkg, true)
        DeviceOwnerHelper.suspendApp(ctx, pkg)

        if (SILENT_UNINSTALL_ENABLED && DeviceOwnerHelper.isDeviceOwner(ctx)) {
            val uninstalled = DeviceOwnerHelper.uninstallAppSilently(ctx, pkg)
            Log.w(TAG, "Silent uninstall issued for $pkg: result=$uninstalled")
        } else {
            Log.w(TAG, "Access blocked for $pkg (hidden + suspended + A11Y)")
        }
    }

    /**
     * Releases an authorized package from lockdown.
     */
    fun release(ctx: Context, pkg: String) {
        Log.i(TAG, "Releasing $pkg from whitelist lockdown")
        DeviceOwnerHelper.hideApp(ctx, pkg, false)
        DeviceOwnerHelper.unsuspendApp(ctx, pkg)
        AppWatcherService.blockedApps.remove(pkg)
    }

    /**
     * Request adding an app to the whitelist. Must wait [delaySeconds] before it becomes active.
     */
    fun requestAppAddition(
        ctx: Context,
        pkg: String,
        delaySeconds: Long = getEffectiveQuarantineDelaySeconds(ctx),
        fromAdb: Boolean = false
    ): Boolean {
        if (BuildConfig.WHITELIST_ADB_ONLY && !fromAdb) {
            Log.w(TAG, "Refused UI whitelist request: flavor requires ADB ($pkg)")
            return false
        }
        val cleanPkg = sanitizePackageName(pkg)
        if (cleanPkg.isBlank()) return false
        val state = loadState(ctx)
        if (cleanPkg in state.allowedPackages) {
            Log.i(TAG, "App $cleanPkg already in whitelist.")
            return false
        }
        val now = System.currentTimeMillis()
        if (delaySeconds <= 0L) {
            val updated = state.allowedPackages + cleanPkg
            val filteredPending = state.pendingRequests.filterNot { it.packageName == cleanPkg }
            saveState(ctx, state.copy(allowedPackages = updated, pendingRequests = filteredPending))
            release(ctx, cleanPkg)
            val hidden = loadHiddenState(ctx).toMutableSet()
            hidden.remove(cleanPkg)
            saveHiddenState(ctx, hidden)
            Log.w(TAG, "Immediately added $cleanPkg to whitelist (delaySec=0)")
            EventLog.log(ctx, "WHITELIST", "Immediately added $cleanPkg to whitelist (fromAdb=$fromAdb)")
            return true
        }
        val existing = state.pendingRequests.find { it.packageName == cleanPkg }
        if (existing != null) {
            Log.i(TAG, "App $cleanPkg already pending until ${java.util.Date(existing.availableAt)}")
            return false
        }
        val availableAt = now + delaySeconds * 1000L
        val newPending = state.pendingRequests + PendingRequest(cleanPkg, now, availableAt)
        saveState(ctx, state.copy(pendingRequests = newPending))
        scheduleNextUnlockAlarm(ctx)
        val hours = delaySeconds / 3600L
        Log.w(TAG, "Added $cleanPkg to quarantine. Available in ${delaySeconds}s / ${hours}h (at ${java.util.Date(availableAt)})")
        EventLog.log(ctx, "WHITELIST", "Requested $cleanPkg addition with ${delaySeconds}s delay (fromAdb=$fromAdb)")
        return true
    }

    fun requestAppAddition(ctx: Context, pkg: String, delayHours: Int, fromAdb: Boolean = false): Boolean {
        return requestAppAddition(ctx, pkg, delayHours * 3600L, fromAdb)
    }

    /**
     * Removes an app from the whitelist.
     * This is a hardening action: it takes effect immediately, locking and hiding the app on-the-fly.
     */
    fun removePackageFromWhitelist(ctx: Context, pkg: String): Boolean {
        val cleanPkg = sanitizePackageName(pkg)
        val state = loadState(ctx)
        if (cleanPkg !in state.allowedPackages) return false
        val updated = state.allowedPackages - cleanPkg
        saveState(ctx, state.copy(allowedPackages = updated))
        neutralize(ctx, cleanPkg)
        val hidden = loadHiddenState(ctx) + cleanPkg
        saveHiddenState(ctx, hidden)
        Log.w(TAG, "Removed $cleanPkg from whitelist. Locked down immediately.")
        EventLog.log(ctx, "WHITELIST", "Removed $cleanPkg from whitelist -> Locked down")
        return true
    }

    /**
     * Cancels a pending addition request. Hardening action, applies immediately.
     */
    fun cancelPendingRequest(ctx: Context, pkg: String): Boolean {
        val cleanPkg = sanitizePackageName(pkg)
        val state = loadState(ctx)
        val filtered = state.pendingRequests.filterNot { it.packageName == cleanPkg }
        if (filtered.size != state.pendingRequests.size) {
            saveState(ctx, state.copy(pendingRequests = filtered))
            scheduleNextUnlockAlarm(ctx)
            Log.w(TAG, "Canceled pending whitelist request for $cleanPkg")
            EventLog.log(ctx, "WHITELIST", "Canceled pending request for $cleanPkg")
            return true
        }
        return false
    }
}
