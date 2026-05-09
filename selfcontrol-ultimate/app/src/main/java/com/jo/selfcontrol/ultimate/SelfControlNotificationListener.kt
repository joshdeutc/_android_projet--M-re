package com.jo.selfcontrol.ultimate

import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Intercepts notifications from apps that the user chose to mute while blocked
 * (curfew rules, daily quota, per-app mute preference).
 *
 * Strategy: SNOOZE notifications for a long duration, then UNSNOOZE when unmuted.
 * - snoozeNotification() works on API 26+ (our minSdk)
 * - unsnoozeNotification() works on API 30+ (Android 11)
 * - On API 26-29: snoozed notifications stay hidden until they auto-expire (30 days)
 *   but the user gets fresh notifications once the app is unblocked
 *
 * Nuclear Mode uses Android's native DND (Do Not Disturb) instead of this listener,
 * which properly suppresses notifications at the OS level with no popup flash.
 *
 * NO notification content is stored — only notification keys for unsnoozing.
 * This avoids any privacy risk from storing message text.
 *
 * Requires "Notification access" permission (Settings > Notification access).
 * No root needed.
 */
class SelfControlNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "SelfControl.NotifListen"

        /** Snooze for 30 days — effectively "forever" until we unsnooze */
        private const val SNOOZE_DURATION_MS = 30L * 24 * 60 * 60 * 1000

        @Volatile
        var instance: SelfControlNotificationListener? = null
            private set

        /** Packages whose notifications should be snoozed right now (curfew/quota/user-pref). */
        val mutedPackages = java.util.concurrent.ConcurrentSkipListSet<String>()

        /**
         * Track snoozed notification keys per package (for unsnoozing later).
         * Only keys are stored — NO notification content, NO privacy risk.
         */
        private val snoozedKeys = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()

        /**
         * Snooze all existing visible notifications from a package.
         * Called when muting is first applied.
         */
        fun cancelExistingNotifications(packageName: String) {
            val inst = instance ?: return
            try {
                val active = inst.activeNotifications ?: return
                for (sbn in active) {
                    if (sbn.packageName == packageName) {
                        try {
                            inst.snoozeNotification(sbn.key, SNOOZE_DURATION_MS)
                            snoozedKeys.getOrPut(packageName) { java.util.concurrent.ConcurrentHashMap.newKeySet() }.add(sbn.key)
                        } catch (e: Exception) {
                            try { inst.cancelNotification(sbn.key) } catch (_: Exception) {}
                        }
                    }
                }
                Log.i(TAG, "Snoozed existing notifications for $packageName")
            } catch (e: Exception) {
                Log.w(TAG, "Error snoozing existing notifications: ${e.message}")
            }
        }

        /**
         * Unsnooze all previously snoozed notifications for a package.
         * On API 30+: notifications reappear exactly as they were (like DND turning off).
         * On API 26-29: keys are cleared but notifications stay snoozed until they expire.
         */
        fun unsnoozeMutedPackage(packageName: String) {
            val keys = snoozedKeys.remove(packageName)
            if (keys.isNullOrEmpty()) {
                Log.d(TAG, "No snoozed keys tracked for $packageName")
                return
            }

            if (Build.VERSION.SDK_INT < 30) {
                Log.w(TAG, "API < 30: cannot unsnooze, notifications for $packageName will reappear on next post")
                return
            }

            val inst = instance ?: run {
                Log.w(TAG, "NotificationListener not connected, cannot unsnooze for $packageName")
                snoozedKeys[packageName] = keys
                return
            }

            var count = 0
            for (key in keys) {
                try {
                    val unsnoozed = try {
                        val m = NotificationListenerService::class.java.getDeclaredMethod("unsnoozeNotification", String::class.java)
                        m.isAccessible = true
                        m.invoke(inst, key)
                        true
                    } catch (e: ReflectiveOperationException) {
                        Log.w(TAG, "unsnoozeNotification unavailable, using 0ms snooze expiry fallback: ${e.message}")
                        false
                    }
                    if (!unsnoozed) {
                        try { inst.snoozeNotification(key, 0L) } catch (_: Exception) {}
                        try { inst.snoozeNotification(key, 1L) } catch (_: Exception) {}
                    }
                    count++
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to unsnooze $key: ${e.message}")
                }
            }
            Log.i(TAG, "Unsnoozed $count/${keys.size} notifications for $packageName")
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.i(TAG, "NotificationListener connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
        Log.w(TAG, "NotificationListener disconnected")
    }

    /**
     * Called by Android every time a notification is posted.
     * CRITICAL: This must be as fast as possible to minimize the popup flash.
     * The notification briefly appears before we can snooze it — the faster
     * we call snoozeNotification(), the shorter the flash.
     *
     * Nuclear Mode does NOT use this path — DND handles it at the OS level.
     */
    override fun onNotificationPosted(sbn: StatusBarNotification?, rankingMap: RankingMap?) {
        // Fast path: null check + package check with minimal branching
        val pkg = sbn?.packageName ?: return
        if (pkg !in mutedPackages) return
        if (pkg == packageName) return // never block our own notifications

        // Snooze immediately — minimize any work before this call
        try {
            snoozeNotification(sbn.key, SNOOZE_DURATION_MS)
            snoozedKeys.getOrPut(pkg) { java.util.concurrent.ConcurrentHashMap.newKeySet() }.add(sbn.key)
        } catch (e: Exception) {
            try {
                cancelNotification(sbn.key)
            } catch (_: Exception) {}
        }
    }
}
