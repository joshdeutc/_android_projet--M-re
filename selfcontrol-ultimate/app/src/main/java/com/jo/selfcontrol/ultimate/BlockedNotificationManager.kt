package com.jo.selfcontrol.ultimate

import android.content.Context
import android.util.Log

object BlockedNotificationManager {
    private const val TAG = "SelfControl.BlockNotif"
    private const val PREFS = "blocked_notification_prefs"
    private const val KEY_PREFIX_PREF = "pref_"
    private const val KEY_MUTED_SET = "muted_now"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getMuteWhenBlockedPreference(context: Context, packageName: String): Boolean? {
        val p = prefs(context)
        val raw = p.getString(KEY_PREFIX_PREF + packageName, null) ?: return null
        return when (raw) {
            "mute" -> true
            "keep" -> false
            else -> null
        }
    }

    fun setMuteWhenBlockedPreference(context: Context, packageName: String, muteWhenBlocked: Boolean) {
        prefs(context).edit()
            .putString(KEY_PREFIX_PREF + packageName, if (muteWhenBlocked) "mute" else "keep")
            .apply()
    }

    fun getCurrentlyMutedBySelfControl(context: Context): MutableSet<String> {
        return prefs(context).getStringSet(KEY_MUTED_SET, emptySet())?.toMutableSet() ?: mutableSetOf()
    }

    fun markCurrentlyMutedBySelfControl(context: Context, packageName: String, muted: Boolean) {
        val set = getCurrentlyMutedBySelfControl(context)
        if (muted) set.add(packageName) else set.remove(packageName)
        prefs(context).edit().putStringSet(KEY_MUTED_SET, set).apply()
    }

    /**
     * Mute/unmute notifications for a package using NotificationListenerService.
     * No root required — just needs "Notification access" permission.
     */
    fun applyNotificationMute(context: Context, packageName: String, mute: Boolean): Boolean {
        if (mute) {
            SelfControlNotificationListener.mutedPackages.add(packageName)
            // Also cancel any existing notifications from this app
            SelfControlNotificationListener.cancelExistingNotifications(packageName)
            Log.i(TAG, "Notification mute applied for $packageName (via NotificationListener)")
        } else {
            SelfControlNotificationListener.mutedPackages.remove(packageName)
            // Unsnooze: on API 30+ original notifications reappear (like DND off)
            SelfControlNotificationListener.unsnoozeMutedPackage(packageName)
            Log.i(TAG, "Notification unmute applied for $packageName (snoozed notifications released)")
        }
        return true
    }

    fun isNotificationListenerActive(): Boolean {
        return SelfControlNotificationListener.instance != null
    }
}
