package com.jo.selfcontrol.ultimate

import android.app.Activity
import android.app.AppOpsManager
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.text.TextUtils
import androidx.core.app.NotificationManagerCompat

object PermissionHelper {

    fun hasUsageStatsPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun requestUsageStatsPermission(activity: Activity) {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        activity.startActivity(intent)
    }

    fun hasAccessibilityPermission(context: Context): Boolean {
        var accessibilityEnabled = 0
        val service = "${context.packageName}/${AppWatcherService::class.java.canonicalName}"
        try {
            accessibilityEnabled = Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED
            )
        } catch (e: Settings.SettingNotFoundException) {
            // Ignore
        }
        val textString = TextUtils.SimpleStringSplitter(':')
        if (accessibilityEnabled == 1) {
            val settingValue = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            if (settingValue != null) {
                textString.setString(settingValue)
                while (textString.hasNext()) {
                    val accessibilityService = textString.next()
                    if (accessibilityService.equals(service, ignoreCase = true)) {
                        return true
                    }
                }
            }
        }
        return false
    }

    fun requestAccessibilityPermission(activity: Activity) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        activity.startActivity(intent)
    }

    fun isDeviceAdminActive(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(context, AdminReceiver::class.java)
        return dpm.isAdminActive(adminComponent)
    }

    fun requestDeviceAdmin(activity: Activity) {
        val adminComponent = ComponentName(activity, AdminReceiver::class.java)
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Required to prevent uninstallation")
        }
        activity.startActivity(intent)
    }

    fun hasNotificationPolicyPermission(context: Context): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return false
        return nm.isNotificationPolicyAccessGranted
    }

    fun requestNotificationPolicyPermission(activity: Activity) {
        val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        activity.startActivity(intent)
    }

    fun hasNotificationListenerPermission(context: Context): Boolean {
        val enabledListeners = NotificationManagerCompat.getEnabledListenerPackages(context)
        return context.packageName in enabledListeners
    }

    fun requestNotificationListenerPermission(activity: Activity) {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        activity.startActivity(intent)
    }

    fun hasPostNotificationsPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun requestPostNotificationsPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager ?: return true
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun requestIgnoreBatteryOptimizations(activity: Activity) {
        runCatching {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = android.net.Uri.parse("package:${activity.packageName}")
            }
            activity.startActivity(intent)
        }.onFailure {
            activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    /**
     * Opens the system App Info page for Custos.
     * This is where users on Android 13+ must unlock "Restricted Settings" (Paramètres Restreints).
     */
    fun openAppDetailsSettings(activity: Activity) {
        runCatching {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.fromParts("package", activity.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        }
    }

    enum class DeviceBrand(val displayName: String) {
        PIXEL("Google Pixel / Stock"),
        SAMSUNG("Samsung (One UI)"),
        XIAOMI("Xiaomi / Redmi (MIUI / HyperOS)"),
        OTHER("Autre Marque")
    }

    fun getDeviceBrand(): DeviceBrand {
        val m = Build.MANUFACTURER.lowercase()
        return when {
            m.contains("samsung") -> DeviceBrand.SAMSUNG
            m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") -> DeviceBrand.XIAOMI
            m.contains("google") -> DeviceBrand.PIXEL
            else -> DeviceBrand.OTHER
        }
    }

    /**
     * Checks if the 3 non-negotiable core permissions are granted:
     * 1. Usage Stats (tracking app limits)
     * 2. Accessibility Service (blocking foreground apps & whitelist)
     * 3. Post Notifications (maintaining foreground service)
     */
    fun isMandatorySetupComplete(context: Context): Boolean {
        val hasUsage = hasUsageStatsPermission(context)
        val hasA11y = hasAccessibilityPermission(context)
        val hasPostNotif = hasPostNotificationsPermission(context)
        return hasUsage && hasA11y && hasPostNotif
    }

    /**
     * Checks if all permissions (core + notification listener + DND) are granted.
     */
    fun isAllSetupComplete(context: Context): Boolean {
        return isMandatorySetupComplete(context) &&
                hasNotificationListenerPermission(context) &&
                hasNotificationPolicyPermission(context)
    }
}
