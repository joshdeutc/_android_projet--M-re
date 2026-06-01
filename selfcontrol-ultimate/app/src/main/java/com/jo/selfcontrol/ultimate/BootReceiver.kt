package com.jo.selfcontrol.ultimate

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Boot Receiver.
 * Starts the LimitService on boot, quick boot, and app update.
 * Also schedules the watchdog alarm to keep the service alive.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.i("SelfControl.Boot", "$action received -> starting LimitService + watchdog + daily reset")
        LimitService.start(context)
        WatchdogReceiver.schedule(context)
        DailyResetReceiver.schedule(context)
    }
}
