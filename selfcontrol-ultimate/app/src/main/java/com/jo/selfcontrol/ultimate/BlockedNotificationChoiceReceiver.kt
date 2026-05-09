package com.jo.selfcontrol.ultimate

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BlockedNotificationChoiceReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_BLOCKED_NOTIF_MUTE = "com.jo.selfcontrol.ultimate.BLOCKED_NOTIF_MUTE"
        const val ACTION_BLOCKED_NOTIF_KEEP = "com.jo.selfcontrol.ultimate.BLOCKED_NOTIF_KEEP"
        const val EXTRA_PACKAGE = "package"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: return
        when (action) {
            ACTION_BLOCKED_NOTIF_MUTE -> {
                LimitService.handleBlockedNotificationChoice(context, pkg, true)
                Log.i("SelfControl.BlockNotif", "User selected MUTE for $pkg")
            }
            ACTION_BLOCKED_NOTIF_KEEP -> {
                LimitService.handleBlockedNotificationChoice(context, pkg, false)
                Log.i("SelfControl.BlockNotif", "User selected KEEP for $pkg")
            }
        }
    }
}
