package com.jo.selfcontrol.ultimate

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log

/**
 * Watchdog: fires every 5 minutes to ensure LimitService is still running.
 * Handles cases where Android kills the service (battery optimization, OEM behavior).
 */
class WatchdogReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SelfControl.Watchdog"
        private const val INTERVAL_MS = 5 * 60 * 1000L // 5 minutes
        private const val REQUEST_CODE = 9999

        fun schedule(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, WatchdogReceiver::class.java)
            val pi = PendingIntent.getBroadcast(
                context, REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.setRepeating(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + INTERVAL_MS,
                INTERVAL_MS,
                pi
            )
            Log.i(TAG, "Watchdog alarm scheduled (every ${INTERVAL_MS / 1000}s)")
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (!LimitService.isRunning) {
            Log.w(TAG, "LimitService not running — restarting!")
            LimitService.start(context)
        }
    }
}
