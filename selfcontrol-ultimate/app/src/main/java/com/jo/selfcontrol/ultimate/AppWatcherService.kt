package com.jo.selfcontrol.ultimate

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.net.Uri
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
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
        // Visible app-label variants used to detect our own App-info / Settings page.
        // MUST include the current launcher label ("Custos") or the anti-uninstall /
        // force-stop protection silently stops triggering after a rename.
        private val TARGET_APP_NAMES = mutableSetOf("Custos", "custos", "SelfControl", "selfcontrol", "Self control", "Self Control")

        // Cancel buttons to click
        private val CANCEL_KEYWORDS = setOf("Cancel", "No", "Cancel", "Non")

        // Danger keywords that trigger protection
        private val DANGER_KEYWORDS = setOf(
            "Disable", "Désactiver",
            "Force stop", "Forcer l'arrêt",
            "Uninstall", "Désinstaller",
            "Remove", "Delete", "Supprimer"
        )

        // Cooldown to avoid HOME spamming loop
        private const val HOME_COOLDOWN_MS = 500L

        private const val INSTAGRAM_PACKAGE = "com.instagram.android"

        /**
         * View ids marking a screen the user gets sent away from: the home feed, Explore, people
         * search, Reels.
         *
         * Matched on ids and never on visible text: the labels are localised — this device reports
         * "Rechercher et explorer" — so text matching would break on a language change, while ids are
         * identical in every locale. All of these were read off the live node tree of this device.
         *
         * Still Instagram's names to change at any release, so this fails open — see
         * [enforceInstagramRestrictions].
         */
        private val INSTAGRAM_REDIRECT_IDS = listOf(
            "com.instagram.android:id/main_feed_action_bar",          // home feed
            "com.instagram.android:id/explore_action_bar",            // Explore grid
            "com.instagram.android:id/action_bar_search_edit_text",   // people search
            // Reels builds a different container depending on how it was opened, so all the observed
            // variants are listed. Matching only `clips_viewer_container` fired on Reels reached one
            // way and stayed blind on the other.
            "com.instagram.android:id/clips_viewer_container",
            "com.instagram.android:id/clips_viewer_view_pager",
            "com.instagram.android:id/clips_expanded_touch_view",
            "com.instagram.android:id/root_clips_layout",
            "com.instagram.android:id/clips_swipe_refresh_container",
            "com.instagram.android:id/clips_video_container"
        )

        /**
         * Screens left by deep link rather than by tapping the messages tab.
         *
         * On Explore and in search, Instagram reads a tab tap as "dismiss search" and returns to the
         * previously selected tab — the feed. The feed rule then fires and does reach the messages, but
         * only after the anti-loop delay, so the user visibly stopped on the feed along the way. The
         * deep link goes straight to the inbox and skips the detour.
         */
        private val INSTAGRAM_PREFER_DEEPLINK_IDS = setOf(
            "com.instagram.android:id/explore_action_bar",
            "com.instagram.android:id/action_bar_search_edit_text"
        )

        /**
         * Screens that veto a redirect — the inbox and an open conversation. Being here means the user
         * is already where we would send them, so no stray match can become a redirect loop.
         *
         * Exact ids, not a `direct_`/`message_` prefix: `direct_tab` is the bottom-bar button present
         * on every screen we mean to leave, and a Reel's comment composer is close enough in naming
         * that a loose prefix vetoed the redirect on any commented Reel.
         */
        private val INSTAGRAM_INBOX_IDS = listOf(
            "com.instagram.android:id/direct_inbox_action_bar",
            "com.instagram.android:id/direct_thread_header"
        )

        /**
         * Floor between two screen checks.
         *
         * Instagram emits content-change events continuously — video frames, comment lists — and the
         * first version answered every one of them with a recursive walk of thousands of nodes on the
         * accessibility thread. That produced an "app not responding" on Custos. Everything below runs
         * behind this gate, and the check itself is now a handful of native id lookups rather than a
         * tree walk.
         */
        private const val INSTAGRAM_SCAN_INTERVAL_MS = 80L
        private const val SCREEN_RULE_SCAN_INTERVAL_MS = 80L

        /** The bottom-bar messages tab — the thing we tap to perform the redirect. */
        private const val INSTAGRAM_DIRECT_TAB_VIEW_ID = "com.instagram.android:id/direct_tab"

        /**
         * The bottom bar, left to right on this device: feed, Reels, messages, search, profile.
         *
         * Reading which one is selected is the only screen test that survives scrolling — every
         * content marker tried so far collapses out of view when the feed is scrolled down.
         */
        private val INSTAGRAM_TAB_VIEW_IDS = listOf(
            "com.instagram.android:id/feed_tab",
            "com.instagram.android:id/clips_tab",
            INSTAGRAM_DIRECT_TAB_VIEW_ID,
            "com.instagram.android:id/search_tab",
            "com.instagram.android:id/profile_tab"
        )

        /**
         * Tabs the user is sent away from. The profile is deliberately absent — it was never part of
         * what had to be closed, and it is how you reach your own posts and settings.
         */
        private val INSTAGRAM_REDIRECTED_TABS = setOf(
            "com.instagram.android:id/feed_tab",
            "com.instagram.android:id/clips_tab",
            "com.instagram.android:id/search_tab"
        )

        /**
         * Deep link to the message inbox, used when the bottom bar is not on screen to be tapped.
         * The package is pinned on the intent so this can never escape to a browser.
         */
        private const val INSTAGRAM_INBOX_URI = "https://www.instagram.com/direct/inbox/"

        /** Long enough that one redirect settles before the next content event triggers another. */
        private const val INSTAGRAM_REDIRECT_COOLDOWN_MS = 500L
        private const val SCREEN_RULE_REDIRECT_COOLDOWN_MS = 500L

        @Volatile
        var currentForegroundApp: String = "unknown"

        /** Currently blocked apps — LimitService adds here, Watcher performs HOME spam */
        val blockedApps = java.util.concurrent.ConcurrentSkipListSet<String>()

        @Volatile
        private var instance: AppWatcherService? = null

        /** The live service, for callers that need to read the screen (screen learning). */
        val serviceInstance: AppWatcherService? get() = instance

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

        /**
         * Re-check the Instagram screen from outside the accessibility event stream.
         *
         * Called every tick by LimitService while Instagram is in the foreground, because events
         * alone are not enough: a feed sitting on a still photo emits nothing, so the redirect only
         * ever fired while the app happened to be animating. The check is rate-gated internally, so
         * calling it once a second costs almost nothing.
         */
        fun recheckInstagram() {
            instance?.enforceInstagramRestrictions()
        }

        /**
         * Re-check learned rules for [pkg] outside the event stream, for the same reason
         * [recheckInstagram] exists: a screen sitting still emits no accessibility events, so
         * event-driven checks go blind exactly when the user has stopped moving.
         */
        fun recheckScreenRules(pkg: String) {
            if (ScreenLearnSession.isActive) return
            instance?.enforceScreenRules(pkg)
        }
    }

    private var appLabel: String = "Custos"

    /**
     * The keyboard's package, resolved once at connect.
     *
     * The IME raises its own window, which arrives as a window-state change and used to overwrite
     * [currentForegroundApp] — so while typing in Instagram, Custos believed the foreground app was
     * `com.google.android.inputmethod.latin`. That silently disabled the Instagram heartbeat, and it
     * also meant time spent typing in a limited app was attributed to the keyboard, which has no
     * limit. Treated as an overlay, exactly like `systemui` already is.
     */
    private var imePackage: String? = null
    private var lastHomeActionTime = 0L
    private var lastInstagramBackTime = 0L
    private var lastInstagramScanTime = 0L
    private var lastInstagramDiagTime = 0L

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
        imePackage = try {
            Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                ?.substringBefore('/')
                ?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
        try {
            val info = serviceInfo ?: android.accessibilityservice.AccessibilityServiceInfo()
            info.flags = info.flags or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            serviceInfo = info
            Log.i(TAG, "Configured serviceInfo flags with FLAG_REPORT_VIEW_IDS")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update serviceInfo flags: ${e.message}")
        }
        Log.i(TAG, "IME package treated as overlay: $imePackage")
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
     * Sends HOME with a cooldown to avoid event loops.
     */
    private fun goHome(reason: String): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastHomeActionTime < HOME_COOLDOWN_MS) return false
        lastHomeActionTime = now
        Log.w(TAG, "🛡️ Protection activated: $reason — returning home")
        EventLog.log(this, "HOME", "forced home: $reason (fg=$currentForegroundApp)")
        performGlobalAction(GLOBAL_ACTION_HOME)
        return true
    }

    /**
     * Keep the official Instagram app on the messages: leaving for the feed, Explore, search or
     * Reels sends the user straight back to the inbox.
     *
     * Redirecting rather than [goHome], because the app has to stay usable for conversations —
     * ejecting to the launcher would make Instagram unusable instead of narrow. Instagram is a
     * single-activity app whose screens are fragments, which is why there is no window class to test
     * as there is for Settings; the node tree is the only thing that says which screen is up, and
     * a tab switch fires no window-state event at all.
     *
     * Two limits worth being honest about. This is **reactive**: the feed renders, then we leave it,
     * so a glimpse is unavoidable — a WebView blocks by URL and never paints the page. And it **fails
     * open**: if Instagram renames these ids, nothing throws and nothing logs, the redirect simply
     * stops happening. The `IG_REDIRECT` lines in the event log are the only way to notice, by their
     * absence.
     */
    private fun enforceInstagramRestrictions(): Boolean {
        // Rate gate first, before touching a single node: this runs on the accessibility thread and
        // Instagram fires content changes continuously.
        val now = System.currentTimeMillis()
        if (now - lastInstagramScanTime < INSTAGRAM_SCAN_INTERVAL_MS) return false
        lastInstagramScanTime = now

        // Both questions below are about what is *on screen*, so they look at the active window only.
        // Searching every window instead made the feature fire exactly once per session: after the
        // first redirect the conversation's window stays in the window list even once the user has
        // gone back to the feed, so the inbox veto matched forever and silenced everything after it.
        // All-window search is needed for one thing only — finding the bottom bar to tap.
        val root = rootInActiveWindow
        if (root == null) {
            diagInstagram("root=null")
            return false
        }

        // The bottom bar is asked first, because it is the only signal that survives scrolling.
        // Content markers like `main_feed_action_bar` collapse out of sight as soon as the feed is
        // scrolled down, which let the user out simply by swiping before the redirect landed — and let
        // them back in when they scrolled up again. Which tab is selected does not move.
        val selectedTab = instagramSelectedTab(root)
        if (selectedTab == INSTAGRAM_DIRECT_TAB_VIEW_ID) return false

        val hit = when (selectedTab) {
            in INSTAGRAM_REDIRECTED_TABS -> selectedTab
            // No tab reported as selected: either the bar is hidden (full-screen Reels, an open
            // conversation) or this build of Instagram does not expose the state. Fall back to the
            // visible-content markers, which is the previous behaviour.
            else -> {
                val veto = INSTAGRAM_INBOX_IDS.firstOrNull { hasViewId(root, it) }
                if (veto != null) null else INSTAGRAM_REDIRECT_IDS.firstOrNull { hasViewId(root, it) }
            }
        }

        if (hit == null) {
            diagInstagram("no redirect — tab=${selectedTab?.removePrefix("com.instagram.android:id/") ?: "none"} tabs=[${tabStates(root)}]")
            return false
        }

        if (now - lastInstagramBackTime < INSTAGRAM_REDIRECT_COOLDOWN_MS) return false
        lastInstagramBackTime = now

        // Tapping the tab is preferred: it is what a finger would do and it keeps Instagram's own
        // navigation state intact. The deep link is the fallback for screens where the bar cannot be
        // reached. BACK is deliberately not used: in the Reels player it merely steps to the previous
        // reel, which is how this first shipped and why nothing appeared to happen.
        val how = if (hit in INSTAGRAM_PREFER_DEEPLINK_IDS) {
            when {
                openInstagramInbox() -> "deeplink"
                tapInstagramDirectTab() -> "tab"
                else -> "failed"
            }
        } else {
            when {
                tapInstagramDirectTab() -> "tab"
                openInstagramInbox() -> "deeplink"
                else -> "failed"
            }
        }
        Log.w(TAG, "🛡️ Instagram: $hit → messages ($how)")
        EventLog.log(this, "IG_REDIRECT", "$hit → DM ($how)")
        return how != "failed"
    }

    /**
     * Jump to the message inbox by deep link — the way out of screens that hide the bottom bar.
     *
     * The package is pinned so the URL can never fall through to a browser, which would hand the
     * user the very web feed we are keeping them away from.
     */
    private fun openInstagramInbox(): Boolean = try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(INSTAGRAM_INBOX_URI)).apply {
            setPackage(INSTAGRAM_PACKAGE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
        true
    } catch (e: Exception) {
        Log.w(TAG, "Instagram inbox deep link failed: ${e.message}")
        false
    }

    /**
     * Diagnostic for the redirect decision, throttled to one line every two seconds.
     *
     * Temporary: it exists because reasoning from the outside kept producing wrong theories about why
     * the redirect did or did not fire. It reports which window was inspected and which ids were
     * actually there, which is the only thing that settles it.
     */
    private fun diagInstagram(detail: String) {
        val now = System.currentTimeMillis()
        if (now - lastInstagramDiagTime < 2_000L) return
        lastInstagramDiagTime = now
        Log.i(TAG, "IG diag: $detail")
    }

    /**
     * Which bottom-bar tab is currently selected, or null when the bar is absent or reports nothing.
     *
     * Both `isSelected` and `isChecked` are consulted because widgets differ in which one they set and
     * Instagram's choice is not documented. A build that populates neither simply yields null and the
     * caller falls back to content markers — so this degrades to the previous behaviour rather than
     * breaking. [tabStates] exists so the log says which of the two is actually used.
     */
    private fun instagramSelectedTab(root: AccessibilityNodeInfo): String? {
        for (id in INSTAGRAM_TAB_VIEW_IDS) {
            val selected = try {
                root.findAccessibilityNodeInfosByViewId(id)
                    .any { it.isVisibleToUser && (it.isSelected || it.isChecked) }
            } catch (e: Exception) {
                false
            }
            if (selected) return id
        }
        return null
    }

    /** Raw selected/checked/visible state of every tab, for the log. */
    private fun tabStates(root: AccessibilityNodeInfo): String =
        INSTAGRAM_TAB_VIEW_IDS.joinToString(" ") { id ->
            val short = id.removePrefix("com.instagram.android:id/")
            val nodes = try {
                root.findAccessibilityNodeInfosByViewId(id)
            } catch (e: Exception) {
                emptyList()
            }
            if (nodes.isEmpty()) "$short=absent"
            else nodes.joinToString(",") { "$short=s${it.isSelected}/c${it.isChecked}/v${it.isVisibleToUser}" }
        }

    /**
     * Whether [viewId] is present in [root]'s tree **and actually on screen**.
     *
     * The visibility test is the whole point. Instagram's main activity is one `ViewPager`
     * (`swipeable_tab_view_pager`) that keeps every tab's fragment alive at once, so the inbox, the
     * feed and Reels all exist in the tree permanently — only one of them is visible. Testing mere
     * presence made the inbox veto match while the user was on the feed, which silenced the redirect
     * from the first visit to the messages onward: before that first visit the fragment did not exist
     * yet, which is exactly why it appeared to work once per session and then stop.
     */
    private fun hasViewId(root: AccessibilityNodeInfo, viewId: String): Boolean = try {
        root.findAccessibilityNodeInfosByViewId(viewId).any { it.isVisibleToUser }
    } catch (e: Exception) {
        false
    }

    /**
     * First node carrying [viewId] in any window, or null.
     *
     * Searches every window and not just `rootInActiveWindow`, because Instagram puts the bottom bar
     * and the Reels player in separate windows — the active-window tree showed the player without the
     * bar, which is why the tab tap kept failing. `flagRetrieveInteractiveWindows` in the service
     * config is what makes [windows] usable here.
     *
     * The lookup is the framework's own native search, so it stays cheap enough to run on the
     * accessibility thread — unlike the recursive walk this replaced, which caused an ANR.
     */
    private fun findNodeInAnyWindow(viewId: String): AccessibilityNodeInfo? {
        try {
            for (window in windows) {
                val root = window.root ?: continue
                // Visible only: the bottom bar exists off-screen in the pager too, and tapping the
                // coordinates of an invisible copy would land somewhere arbitrary.
                root.findAccessibilityNodeInfosByViewId(viewId)
                    .firstOrNull { it.isVisibleToUser }
                    ?.let { return it }
            }
        } catch (e: Exception) {
            Log.w(TAG, "findNodeInAnyWindow($viewId) failed: ${e.message}")
        }
        return try {
            rootInActiveWindow?.findAccessibilityNodeInfosByViewId(viewId)
                ?.firstOrNull { it.isVisibleToUser }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Tap the centre of the messages tab.
     *
     * A gesture rather than `ACTION_CLICK`: the id sits on the icon, which is not itself clickable,
     * and walking up to the first clickable ancestor lands on the whole bottom bar — clicking that
     * selects nothing in particular and leaves Instagram on an indeterminate screen. Dispatching a
     * real tap at the icon's own coordinates is what the user's finger does, so the tab reacts the
     * way it always does. Requires `canPerformGestures` in the service config.
     */
    private fun tapInstagramDirectTab(): Boolean = try {
        val icon = findNodeInAnyWindow(INSTAGRAM_DIRECT_TAB_VIEW_ID)
        if (icon == null) {
            false
        } else {
            val bounds = Rect()
            icon.getBoundsInScreen(bounds)
            if (bounds.isEmpty) {
                false
            } else {
                Log.i(TAG, "Instagram DM tab bounds=$bounds → tap ${bounds.exactCenterX()},${bounds.exactCenterY()}")
                val path = Path().apply { moveTo(bounds.exactCenterX(), bounds.exactCenterY()) }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0L, 10L))
                    .build()
                dispatchGesture(gesture, null, null)
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Instagram DM tab tap failed: ${e.message}")
        false
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
        // The keyboard is an overlay over whatever app is really in front, not a foreground app.
        if (packageName == imePackage) return

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
            if (packageName in InstallWindowManager.INSTALLER_PACKAGES && InstallWindowManager.isOpen(this)) {
                blockedApps.remove(packageName)
            } else {
                goHome("App blocked: $packageName (limit reached)")
                return
            }
        }

        if (packageName == INSTAGRAM_PACKAGE && enforceInstagramRestrictions()) {
            return
        }

        // Learned rules never fire while a session is teaching one: the user is being asked to visit
        // the very screen we would send them away from.
        if (!ScreenLearnSession.isActive && enforceScreenRules(packageName)) {
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
        // Instagram swaps fragments inside one activity, so switching to Explore fires only a
        // content change — never a window state change. Without this hook the block would catch a
        // cold start on Explore and nothing else, which is the case that almost never happens.
        if (packageName == INSTAGRAM_PACKAGE) {
            enforceInstagramRestrictions()
            return
        }

        // Same reasoning for learned rules: a tab switch inside a single-activity app is a content
        // change and nothing else.
        if (!ScreenLearnSession.isActive && enforceScreenRules(packageName)) {
            return
        }

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
    //  Learned screen rules — enforcement
    // ──────────────────────────────────────

    private var screenRules: List<ScreenRuleManager.ScreenRule> = emptyList()
    private var screenRulesStamp = 0L
    private var screenRulesCheckedAt = 0L
    private var lastScreenRuleActionAt = 0L
    private var lastScreenRuleScanAt = 0L

    /**
     * Apply the rules taught by [ScreenLearnSession] to [pkg], returning true if the user was moved.
     *
     * Deliberately mirrors the hand-written Instagram path rather than replacing it yet: that one is
     * tuned by a long debugging session and works, so it keeps priority until this has proved itself on
     * a second app.
     *
     * Every lookup is the framework's native id search over the target package's windows — no tree
     * walking. The walk is what caused an ANR when it ran on every accessibility event, and this runs
     * just as often.
     */
    internal fun enforceScreenRules(pkg: String): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastScreenRuleActionAt < SCREEN_RULE_REDIRECT_COOLDOWN_MS) return false
        if (now - lastScreenRuleScanAt < SCREEN_RULE_SCAN_INTERVAL_MS) return false
        lastScreenRuleScanAt = now

        reloadScreenRulesIfStale(now)
        val rules = screenRules.filter { it.packageName == pkg }
        if (rules.isEmpty()) return false

        for (rule in rules) {
            // Check active hours schedule if defined
            if (!isRuleActiveNow(rule)) continue
            // Already where the rule wants the user — nothing to do, and never a loop.
            if (rule.allowedIds.any { visibleIdPresent(pkg, it) }) continue
            val hit = rule.blockedIds.firstOrNull { visibleIdPresent(pkg, it) } ?: continue

            lastScreenRuleActionAt = now
            performScreenEscape(rule, hit)
            return true
        }
        return false
    }

    private fun isRuleActiveNow(rule: ScreenRuleManager.ScreenRule): Boolean {
        val hours = rule.blockedHours ?: return true
        if (hours == "*" || hours.isBlank()) return true
        val parts = hours.split("-")
        if (parts.size != 2) return true
        return try {
            val start = ConfigManager.parseTimeToMinutes(parts[0])
            val end = ConfigManager.parseTimeToMinutes(parts[1])
            val cal = java.util.Calendar.getInstance()
            val nowMin = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
            ConfigManager.isInBlockedWindow(nowMin, start, end)
        } catch (e: Exception) {
            true
        }
    }

    /** Reload rules only when the file actually changed, and at most every few seconds. */
    private fun reloadScreenRulesIfStale(now: Long) {
        if (now - screenRulesCheckedAt < 3_000L) return
        screenRulesCheckedAt = now
        val stamp = try {
            java.io.File(filesDir, "screen_rules.json").lastModified()
        } catch (e: Exception) {
            0L
        }
        if (stamp == screenRulesStamp) return
        screenRulesStamp = stamp
        screenRules = ScreenRuleManager.load(this)
        Log.i(TAG, "Screen rules reloaded: ${screenRules.size}")
    }

    /** Whether [viewId] (or text marker `text:...`, or desc marker `desc:...`) is on screen right now, in one of [pkg]'s windows. */
    private fun visibleIdPresent(pkg: String, viewId: String): Boolean {
        try {
            for (window in windows) {
                val root = window.root ?: continue
                if (root.packageName != pkg) continue
                val match = when {
                    viewId.startsWith("text:") -> {
                        val txt = viewId.removePrefix("text:")
                        root.findAccessibilityNodeInfosByText(txt).any {
                            it.isVisibleToUser && it.text?.toString().equals(txt, ignoreCase = true)
                        }
                    }
                    viewId.startsWith("desc:") -> {
                        val desc = viewId.removePrefix("desc:")
                        root.findAccessibilityNodeInfosByText(desc).any {
                            it.isVisibleToUser && it.contentDescription?.toString().equals(desc, ignoreCase = true)
                        }
                    }
                    else -> {
                        root.findAccessibilityNodeInfosByViewId(viewId).any { it.isVisibleToUser }
                    }
                }
                if (match) return true
            }
        } catch (e: Exception) {
            Log.w(TAG, "visibleIdPresent($viewId) failed: ${e.message}")
        }
        return false
    }

    /**
     * Leave the blocked screen, then **check 900 ms later whether it worked**.
     *
     * The check is the point. Until now a redirect only reported that an action had been *emitted*, and
     * that hid a real failure for days: tapping the messages tab from Instagram's search dismissed the
     * search and landed on the feed, which looked like success in the log. Verifying the destination
     * turns each real use into a test, so a rule that stops working shows up as failures instead of
     * silence — and the counters say which action to prefer next time.
     */
    private fun performScreenEscape(rule: ScreenRuleManager.ScreenRule, hit: String) {
        val pkg = rule.packageName
        val escapeTapId = rule.escapeTapId ?: if (pkg == "com.snapchat.android") {
            "com.snapchat.android:id/ngs_chat_icon_container"
        } else null

        val how = when {
            escapeTapId != null &&
                tapNavItem(pkg, escapeTapId, rule.escapeTapIndex) -> "tap"
            rule.escapeDeeplink != null && openDeeplink(pkg, rule.escapeDeeplink) -> "deeplink"
            pkg == "com.snapchat.android" && tapSnapchatChat() -> "snapchat_chat_coords"
            performGlobalAction(GLOBAL_ACTION_BACK) -> "back"
            else -> "failed"
        }
        val shortHit = hit.substringAfter(":id/")
        Log.w(TAG, "🛡️ ${rule.name}: $shortHit → escape ($how)")

        overlayHandler.postDelayed({
            val reached = rule.allowedIds.any { visibleIdPresent(pkg, it) }
            ScreenRuleManager.recordOutcome(this, rule.name, reached)
            Log.w(TAG, "   ${rule.name}: destination ${if (reached) "atteinte" else "NON atteinte"}")
            EventLog.log(
                this, "SCREEN_RULE",
                "${rule.name} $shortHit → $how, reached=$reached"
            )
        }, 900L)
    }

    /** Fallback coordinate tap on the Snapchat bottom nav Chat icon (approx 31.6% width, 90.9% height). */
    private fun tapSnapchatChat(): Boolean = try {
        val dm = resources.displayMetrics
        val x = dm.widthPixels * 0.316f
        val y = dm.heightPixels * 0.909f
        val path = Path().apply { moveTo(x, y) }
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 10L))
                .build(),
            null, null
        )
    } catch (e: Exception) {
        Log.w(TAG, "tapSnapchatChat failed: ${e.message}")
        false
    }

    /** Open [uri] inside [pkg]. Pinned to the package so a URL can never fall through to a browser. */
    private fun openDeeplink(pkg: String, uri: String): Boolean = try {
        startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
                setPackage(pkg)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        true
    } catch (e: Exception) {
        Log.w(TAG, "deeplink $uri failed: ${e.message}")
        false
    }

    // ──────────────────────────────────────
    //  Screen learning — guidance overlay
    // ──────────────────────────────────────

    private var learnOverlay: View? = null

    /**
     * A floating panel with an instruction and two buttons, shown over the app being learned.
     *
     * Same `TYPE_ACCESSIBILITY_OVERLAY` as the session label — no `SYSTEM_ALERT_WINDOW` needed — but
     * without `FLAG_NOT_TOUCHABLE` so the buttons work, and *with* `FLAG_NOT_FOCUSABLE` so the app
     * underneath keeps input focus. Taking focus would change the very screen we are about to read.
     *
     * Anchored to the top on purpose: the flow asks the user to switch tabs, and those live at the
     * bottom of every app this is meant for.
     */
    internal fun showLearnOverlay(
        text: String,
        primaryLabel: String,
        onPrimary: () -> Unit,
        onCancel: () -> Unit
    ) {
        overlayHandler.post {
            try {
                hideLearnOverlayInternal()
                val density = resources.displayMetrics.density
                fun dp(v: Int) = (v * density).toInt()

                val panel = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#F21B1B2E"))
                        cornerRadius = dp(16).toFloat()
                    }
                }
                panel.addView(
                    TextView(this).apply {
                        this.text = text
                        setTextColor(Color.WHITE)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                        typeface = Typeface.DEFAULT_BOLD
                    }
                )
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(8), 0, 0)
                }
                row.addView(
                    Button(this).apply {
                        this.text = primaryLabel
                        setOnClickListener { onPrimary() }
                    }
                )
                row.addView(
                    Button(this).apply {
                        this.text = "Cancel"
                        setOnClickListener { onCancel() }
                    }
                )
                panel.addView(row)

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    y = dp(48)
                }
                (getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(panel, params)
                learnOverlay = panel
            } catch (e: Exception) {
                Log.e(TAG, "learn overlay failed: ${e.message}")
            }
        }
    }

    internal fun hideLearnOverlay() {
        overlayHandler.post { hideLearnOverlayInternal() }
    }

    private fun hideLearnOverlayInternal() {
        val v = learnOverlay ?: return
        learnOverlay = null
        try {
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (_: Exception) {}
    }

    // ──────────────────────────────────────
    //  Screen learning — capture primitives
    // ──────────────────────────────────────

    /**
     * Every **visible** view id belonging to [pkg], across all its windows.
     *
     * This walks the tree, which is exactly what caused an ANR when the redirect check did it on every
     * accessibility event. It is safe here because it runs only when the user taps "capture" during a
     * learning session — a handful of times, never in a loop. The node budget is a guard against a
     * pathological tree, not a performance tuning.
     *
     * Windows are filtered by package so the reading is unaffected by our own overlay, the keyboard, or
     * a pulled-down notification shade being the active window.
     */
    internal fun visibleIdsFor(pkg: String): Set<String> {
        val ids = LinkedHashSet<String>()
        val prefix = "$pkg:id/"
        var budget = 4_000

        val dm = resources.displayMetrics
        val screenBounds = Rect()
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null || budget <= 0) return
            budget--
            val id = node.viewIdResourceName
            node.getBoundsInScreen(screenBounds)
            val onScreen = node.isVisibleToUser || (screenBounds.width() > 0 && screenBounds.height() > 0 &&
                    screenBounds.intersects(0, 0, dm.widthPixels, dm.heightPixels))

            if (onScreen) {
                if (id != null && id.startsWith(prefix)) {
                    ids.add(id)
                }
                val txt = node.text?.toString()?.trim()
                if (!txt.isNullOrEmpty() && txt.length in 3..25 && !txt.all { it.isDigit() || it == ':' || it == ' ' }) {
                    ids.add("text:$txt")
                }
            }
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }

        try {
            var foundInWindows = false
            for (window in windows) {
                val root = window.root ?: continue
                if (root.packageName?.toString() == pkg) {
                    foundInWindows = true
                    walk(root)
                }
            }
            if (!foundInWindows) {
                rootInActiveWindow?.let { root ->
                    if (root.packageName?.toString() == pkg) {
                        walk(root)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "visibleIdsFor($pkg) failed: ${e.message}")
        }
        Log.i(TAG, "visibleIdsFor($pkg) returning ${ids.size} ids")
        return ids
    }

    /**
     * The view id of the currently selected navigation item in [pkg], plus its index among the nodes
     * sharing that id — or null when nothing reports itself selected.
     *
     * The index carries the meaning for apps like WhatsApp, whose bottom bar is a Material
     * `BottomNavigationView`: all five tabs carry the same view id, so the position is the only thing
     * that names the tab. Instagram gives each tab a distinct id and the index is 0.
     */
    internal fun selectedNavItem(pkg: String): Pair<String, Int>? {
        if (pkg == "com.snapchat.android") {
            // Snapchat's custom bottom bar items do not set isSelected or isChecked.
            // Discriminate active tab by visible screen markers:
            if (visibleIdPresent(pkg, "com.snapchat.android:id/ff_item") || visibleIdPresent(pkg, "text:Chat")) {
                return "com.snapchat.android:id/ngs_chat_icon_container" to 0
            }
            if (visibleIdPresent(pkg, "com.snapchat.android:id/camera_page") || visibleIdPresent(pkg, "com.snapchat.android:id/camera_capture_button")) {
                return "com.snapchat.android:id/ngs_camera_icon_container" to 0
            }
            if (visibleIdPresent(pkg, "com.snapchat.android:id/df_large_story") || visibleIdPresent(pkg, "com.snapchat.android:id/friend_card_frame") || visibleIdPresent(pkg, "text:Stories")) {
                return "com.snapchat.android:id/ngs_community_icon_container" to 0
            }
            if (visibleIdPresent(pkg, "com.snapchat.android:id/spotlight_container") || visibleIdPresent(pkg, "text:Spotlight")) {
                return "com.snapchat.android:id/ngs_spotlight_icon_container" to 0
            }
            return "com.snapchat.android:id/ngs_chat_icon_container" to 0
        }

        val prefix = "$pkg:id/"
        val byId = LinkedHashMap<String, MutableList<Boolean>>()
        var budget = 3_000

        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null || budget <= 0) return
            budget--
            val id = node.viewIdResourceName
            if (id != null && id.startsWith(prefix) && node.isVisibleToUser) {
                byId.getOrPut(id) { mutableListOf() }.add(node.isSelected || node.isChecked)
            }
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }

        try {
            var found = false
            for (window in windows) {
                val root = window.root ?: continue
                if (root.packageName?.toString() == pkg) {
                    found = true
                    walk(root)
                }
            }
            if (!found) {
                rootInActiveWindow?.let { root ->
                    if (root.packageName?.toString() == pkg) walk(root)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "selectedNavItem($pkg) failed: ${e.message}")
            return null
        }

        // Prefer an id with several instances where exactly one is selected — that is the shape of a
        // tab bar, and it rules out unrelated one-off selected widgets such as a checked list row.
        val tabLike = byId.entries
            .filter { it.value.size in 2..8 && it.value.count { sel -> sel } == 1 }
            .minByOrNull { it.value.size }
        if (tabLike != null) {
            return tabLike.key to tabLike.value.indexOfFirst { it }
        }
        val single = byId.entries.firstOrNull { it.value.size == 1 && it.value[0] }
        return single?.let { it.key to 0 }
    }

    /** Tap the centre of the [index]-th visible node carrying [viewId] in [pkg]. */
    internal fun tapNavItem(pkg: String, viewId: String, index: Int): Boolean {
        val matches = mutableListOf<AccessibilityNodeInfo>()

        try {
            var found = false
            for (window in windows) {
                val root = window.root ?: continue
                if (root.packageName?.toString() == pkg) {
                    found = true
                    matches.addAll(root.findAccessibilityNodeInfosByViewId(viewId) ?: emptyList())
                }
            }
            if (!found) {
                rootInActiveWindow?.let { root ->
                    if (root.packageName?.toString() == pkg) {
                        matches.addAll(root.findAccessibilityNodeInfosByViewId(viewId) ?: emptyList())
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "tapNavItem lookup failed: ${e.message}")
            return false
        }

        val node = matches.getOrNull(index) ?: run {
            Log.w(TAG, "tapNavItem: no match at index $index (found ${matches.size} total for $viewId)")
            return false
        }
        Log.i(TAG, "tapNavItem: found target node at index $index for $viewId")

        // 1. Try direct accessibility action click on node or its ancestors
        var curr: AccessibilityNodeInfo? = node
        var clicked = false
        while (curr != null) {
            if (curr.isClickable) {
                clicked = curr.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.i(TAG, "tapNavItem: ACTION_CLICK returned $clicked on ${curr.viewIdResourceName ?: curr.className}")
                if (clicked) break
            }
            curr = curr.parent
        }

        // 2. Also dispatch physical tap gesture on center coordinates
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        var gestured = false
        if (!bounds.isEmpty) {
            try {
                val path = Path().apply { moveTo(bounds.exactCenterX(), bounds.exactCenterY()) }
                gestured = dispatchGesture(
                    GestureDescription.Builder()
                        .addStroke(GestureDescription.StrokeDescription(path, 0L, 10L))
                        .build(),
                    null, null
                )
                Log.i(TAG, "tapNavItem: dispatchGesture returned $gestured at (${bounds.centerX()}, ${bounds.centerY()})")
            } catch (e: Exception) {
                Log.w(TAG, "tapNavItem gesture failed: ${e.message}")
            }
        }
        return clicked || gestured
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
