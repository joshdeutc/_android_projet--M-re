package com.jo.selfcontrol.ultimate

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import java.io.File

/**
 * Accessibility Service that:
 * 1. Detects in real-time which app is in the foreground
 * 2. Blocks access to the SelfControl page in Settings (anti force-stop/uninstall)
 */
class AppWatcherService : AccessibilityService() {

    companion object {
        private const val TAG = "SelfControl.Watcher"

        // Android Settings Packages
        private val SETTINGS_PACKAGES = setOf(
            "com.android.settings",
            "com.samsung.android.settings",
            "com.samsung.android.app.routines",
            "com.samsung.android.sm",
            "com.samsung.accessibility"
        )

        // Packages that could trigger an uninstallation
        private val UNINSTALL_PACKAGES = setOf(
            "com.android.vending",                   // Play Store
            "com.google.android.packageinstaller",   // Google package installer
            "com.android.packageinstaller",          // AOSP package installer
            "com.samsung.android.packageinstaller"   // Samsung package installer
        )

        // App names to protect
        private val TARGET_APP_NAMES = mutableSetOf("SelfControl", "selfcontrol", "Self control", "Self Control")

        // Cancel buttons to click
        private val CANCEL_KEYWORDS = setOf("Cancel", "No", "Annuler", "Non")

        // Danger keywords that trigger protection
        private val DANGER_KEYWORDS = setOf(
            "Disable", "Désactiver",
            "Force stop", "Forcer l'arrêt",
            "Uninstall", "Désinstaller",
            "Remove", "Delete", "Supprimer"
        )

        // Cooldown to avoid HOME spamming loop
        private const val HOME_COOLDOWN_MS = 500L

        @Volatile
        var currentForegroundApp: String = "unknown"

        /** Currently blocked apps — LimitService adds here, Watcher performs HOME spam */
        val blockedApps = java.util.concurrent.ConcurrentSkipListSet<String>()

        @Volatile
        private var instance: AppWatcherService? = null

        @Volatile
        var isBound: Boolean = false
            private set

        @Volatile
        var lastBoundTimestamp: Long = 0L
            private set

        /**
         * Force HOME action if the current foreground app is blocked.
         * Called by LimitService when it detects a limit has been reached,
         * so the user is kicked out immediately without waiting for a window state change.
         */
        fun forceHomeIfBlocked() {
            val inst = instance ?: return
            val pkg = currentForegroundApp
            if (pkg in blockedApps) {
                inst.goHome("Forced: $pkg blocked")
            }
        }

        /**
         * Show/hide the floating session countdown over the current foreground app.
         * Called by LimitService.enforceLimit every second with the up-to-date text,
         * or null to hide. Uses TYPE_ACCESSIBILITY_OVERLAY so no SYSTEM_ALERT_WINDOW
         * permission is needed.
         */
        fun updateSessionOverlay(text: String?) {
            instance?.applySessionOverlay(text)
        }
    }

    private var appLabel: String = "SelfControl"
    private var lastHomeActionTime = 0L

    private var overlayView: TextView? = null
    private val overlayHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isBound = true
        lastBoundTimestamp = System.currentTimeMillis()
        try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            appLabel = packageManager.getApplicationLabel(appInfo).toString()
            TARGET_APP_NAMES.add(appLabel)
        } catch (_: Exception) {}
        Log.i(TAG, "✅ AccessibilityService connected — monitoring active (label: $appLabel)")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        isBound = false
        Log.w(TAG, "⚠️ AccessibilityService UNBIND — protection inactive")
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                handleWindowChanged(packageName, event)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                handleContentChanged(packageName)
            }
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                // Dismiss heads-up popup from muted apps.
                // GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE only collapses the shade,
                // it does NOT dismiss heads-up popups. We need both actions:
                // 1. Dismiss shade (in case it's open)
                // 2. Collapse the heads-up popup by sending it away
                if (packageName in SelfControlNotificationListener.mutedPackages
                    && packageName != "com.jo.selfcontrol.ultimate") {
                    // This collapses any heads-up popup that's currently showing
                    performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
                    // Also try to find and dismiss the heads-up notification node
                    dismissHeadsUpNotification(packageName)
                    Log.d(TAG, "Dismissed heads-up notification from muted app: $packageName")
                }
            }
        }
    }

    /**
     * Sends BACK + HOME with a cooldown to avoid event loops.
     */
    private fun goHome(reason: String): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastHomeActionTime < HOME_COOLDOWN_MS) return false
        lastHomeActionTime = now
        Log.w(TAG, "🛡️ Protection activated: $reason — returning home")
        EventLog.log(this, "HOME", "forced home: $reason (fg=$currentForegroundApp)")
        performGlobalAction(GLOBAL_ACTION_BACK)
        performGlobalAction(GLOBAL_ACTION_HOME)
        return true
    }

    private fun handleWindowChanged(packageName: String, event: AccessibilityEvent) {
        if (packageName == "com.jo.selfcontrol.ultimate") return

        val className = event.className?.toString() ?: ""

        val isDialog = className.contains("Dialog", ignoreCase = true)
            || className.contains("Popup", ignoreCase = true)
        
        if (isDialog) {
            checkSettingsForSelfControl(requireDangerKeyword = true)
        }

        if (packageName.startsWith("com.android.systemui")) return

        if (packageName != currentForegroundApp) {
            val previousApp = currentForegroundApp
            currentForegroundApp = packageName
            Log.d(TAG, "🔄 $previousApp → $packageName")
            EventLog.log(this, "FG", "$previousApp → $packageName (class=$className)")
            // Hide the session overlay on app switch; LimitService re-shows within 1s
            // if the new foreground app has an active session.
            applySessionOverlay(null)
        }

        if (packageName in blockedApps) {
            goHome("App blocked: $packageName (limit reached)")
            return
        }

        val isAppInfoPage = className.contains("AppInfoBase")
            || className.contains("InstalledAppDetails")
            || className.contains("AppInfoDashboard")

        val isUninstallerPage = className.contains("UninstallerActivity")
            || className.contains("UninstallUninstalling")
            || className.contains("UninstallAppProgress")

        val isAccessibilityPage = className.contains("AccessibilitySettings")
            || className.contains("AccessibilityDetailsSettings")
            || className.contains("ToggleAccessibilityService")
            || className.contains("InstalledAccessibilityService")
            || className.contains("AccessibilityHomepageActivity")
            || packageName == "com.samsung.accessibility"

        if (isAccessibilityPage && packageName in SETTINGS_PACKAGES) {
            checkSettingsForSelfControl(requireDangerKeyword = false)
        }

        if (isAppInfoPage || isUninstallerPage) {
            checkSettingsForSelfControl(requireDangerKeyword = false)
            return
        }

        if (packageName in UNINSTALL_PACKAGES) {
            checkSettingsForSelfControl(requireDangerKeyword = true)
            return
        }

        if (packageName in SETTINGS_PACKAGES) {
            // Generic Settings screens are no longer blocked by default.
            // We only trigger when dangerous actions are visible.
            checkSettingsForSelfControl(requireDangerKeyword = true)
        }
    }

    private fun handleContentChanged(packageName: String) {
        if (packageName in UNINSTALL_PACKAGES) {
            checkSettingsForSelfControl(requireDangerKeyword = true)
            return
        }

        if (packageName in SETTINGS_PACKAGES || packageName == "android") {
            checkSettingsForSelfControl(requireDangerKeyword = true)
        }
    }

    private fun checkSettingsForSelfControl(requireDangerKeyword: Boolean = false) {
        // If DelayManager says we requested settings unlock, bypass protection entirely
        if (DelayManager.isSettingsUnlocked(this)) {
            Log.d(TAG, "🔓 Settings bypassed: Delay unlock active.")
            return
        }

        val rootNode = rootInActiveWindow ?: return

        try {
            var isOurAppOnScreen = false
            for (appName in TARGET_APP_NAMES) {
                val appNodes = rootNode.findAccessibilityNodeInfosByText(appName)
                if (appNodes != null && appNodes.isNotEmpty()) {
                    isOurAppOnScreen = true
                    appNodes.forEach { it.recycle() }
                    break
                }
            }
            if (!isOurAppOnScreen) return

            if (requireDangerKeyword) {
                var dangerFound = false
                for (danger in DANGER_KEYWORDS) {
                    val dangerNodes = rootNode.findAccessibilityNodeInfosByText(danger)
                    if (dangerNodes != null && dangerNodes.isNotEmpty()) {
                        dangerFound = true
                        dangerNodes.forEach { it.recycle() }
                        break
                    }
                }
                if (!dangerFound) return
            }

            Log.w(TAG, "🛡️ SelfControl + danger detected! (strict=$requireDangerKeyword)")

            var clickedCancel = false
            for (cancel in CANCEL_KEYWORDS) {
                val cancelNodes = rootNode.findAccessibilityNodeInfosByText(cancel)
                if (cancelNodes != null && cancelNodes.isNotEmpty()) {
                    for (node in cancelNodes) {
                        if (node.isClickable) {
                            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            Log.w(TAG, "🛡️ Clicked on '$cancel'")
                            clickedCancel = true
                            break
                        }
                    }
                    cancelNodes.forEach { it.recycle() }
                    if (clickedCancel) break
                }
            }

            goHome("SelfControl detected on sensitive screen")
        } catch (e: Exception) {
            Log.e(TAG, "Error during auto-click: ${e.message}")
        } finally {
            rootNode.recycle()
        }
    }

    /**
     * Try to dismiss a heads-up notification popup from a muted app.
     * Heads-up popups are rendered by SystemUI as a floating window.
     * We look for dismissible notification nodes and swipe them away.
     */
    private fun dismissHeadsUpNotification(fromPackage: String) {
        try {
            val rootNode = rootInActiveWindow ?: return
            try {
                // Find nodes that are dismissible (heads-up notifications are usually dismissible)
                findAndDismissNotificationNodes(rootNode, fromPackage)
            } finally {
                rootNode.recycle()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error dismissing heads-up: ${e.message}")
        }
    }

    private fun findAndDismissNotificationNodes(node: AccessibilityNodeInfo, targetPackage: String) {
        // Check if this node is dismissible (heads-up notification)
        if (node.isDismissable) {
            try {
                node.performAction(AccessibilityNodeInfo.ACTION_DISMISS)
                Log.d(TAG, "Dismissed heads-up notification node for $targetPackage")
                return
            } catch (_: Exception) {}
        }

        // Recurse into children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                findAndDismissNotificationNodes(child, targetPackage)
            } finally {
                child.recycle()
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "⚠️ AccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        applySessionOverlay(null)
        Log.w(TAG, "💀 AccessibilityService destroyed")
        currentForegroundApp = "unknown"
    }

    // ──────────────────────────────────────
    //  Session overlay
    // ──────────────────────────────────────

    private fun applySessionOverlay(text: String?) {
        overlayHandler.post {
            try {
                if (text == null) {
                    removeOverlayView()
                } else {
                    ensureOverlayView()
                    overlayView?.text = text
                }
            } catch (e: Exception) {
                Log.w(TAG, "session overlay update failed: ${e.message}")
            }
        }
    }

    private fun ensureOverlayView() {
        if (overlayView != null) return
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val bg = GradientDrawable().apply {
            setColor(Color.parseColor("#CC000000"))
            cornerRadius = dp(18).toFloat()
        }
        val tv = TextView(this).apply {
            background = bg
            setTextColor(Color.parseColor("#80CBC4"))
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            text = ""
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(40)  // below the status bar
        }
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        try {
            wm.addView(tv, params)
            overlayView = tv
        } catch (e: Exception) {
            Log.e(TAG, "session overlay addView failed: ${e.message}")
        }
    }

    private fun removeOverlayView() {
        val v = overlayView ?: return
        overlayView = null
        try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.removeView(v)
        } catch (_: Exception) {}
    }
}
