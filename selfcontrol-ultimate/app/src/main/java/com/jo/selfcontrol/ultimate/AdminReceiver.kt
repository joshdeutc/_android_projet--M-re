package com.jo.selfcontrol.ultimate

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Device Admin Receiver. When the app is provisioned as Device Owner via
 * `dpm set-device-owner`, this is also the DO entry point.
 */
class AdminReceiver : DeviceAdminReceiver() {

    companion object {
        private const val TAG = "SelfControl.Admin"
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "Device Admin enabled (DO=${DeviceOwnerHelper.isDeviceOwner(context)})")

        if (DeviceOwnerHelper.isDeviceOwner(context)) {
            DeviceOwnerHelper.applyInitialPolicies(context)
        }

        LimitService.start(context)
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.i(TAG, "Device Admin disabled")
    }

    /**
     * Triggered after `dpm set-device-owner` completes the provisioning flow.
     * Apply the lockdown policies right away so uninstall is blocked immediately.
     */
    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        Log.i(TAG, "Provisioning complete — applying Device Owner policies")
        DeviceOwnerHelper.applyInitialPolicies(context)
        LimitService.start(context)
    }
}
