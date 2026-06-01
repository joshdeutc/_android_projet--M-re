package com.jo.selfcontrol.ultimate

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.util.Calendar

/**
 * Fires at the logical-day boundary (02:00 wall-clock) to guarantee the rollover happens
 * even when the device is in deep Doze and the LimitService Handler is suspended.
 *
 * The receiver itself doesn't perform the reset — it just ensures the service is running.
 * The next enforceLimit() tick (within 1s) will detect logicalToday != trackingDay and
 * clear usageToday + suspendedApps as before.
 *
 * setExactAndAllowWhileIdle is one-shot, so we re-schedule on every fire.
 */
class DailyResetReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SelfControl.DailyReset"
        private const val REQUEST_CODE = 7777
        private const val ACTION = "com.jo.selfcontrol.ultimate.DAILY_RESET"

        fun schedule(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = pendingIntent(context)
            val triggerAt = nextResetMillis()

            val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
            try {
                if (canExact) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                    Log.i(TAG, "Daily reset alarm scheduled (exact) at ${java.util.Date(triggerAt)}")
                } else {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                    Log.i(TAG, "Daily reset alarm scheduled (inexact — SCHEDULE_EXACT_ALARM missing) at ${java.util.Date(triggerAt)}")
                }
            } catch (e: SecurityException) {
                // Fall back to inexact if exact was rejected at runtime (some OEMs).
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                Log.w(TAG, "Exact alarm rejected, falling back to inexact: ${e.message}")
            }
        }

        private fun pendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, DailyResetReceiver::class.java).apply { action = ACTION }
            return PendingIntent.getBroadcast(
                context, REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        /** Next 02:00:30 wall-clock. The 30s offset gives the service a moment to wake. */
        private fun nextResetMillis(): Long {
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 2)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 30)
                set(Calendar.MILLISECOND, 0)
            }
            if (cal.timeInMillis <= System.currentTimeMillis()) {
                cal.add(Calendar.DATE, 1)
            }
            return cal.timeInMillis
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        Log.i(TAG, "Daily reset alarm fired — ensuring service is running")
        // Service onCreate/enforceLimit handles the actual rollover. We just need to be
        // awake at this moment so the day-change check runs while the device is on.
        LimitService.start(context)
        // Re-schedule for the next day. setExactAndAllowWhileIdle is one-shot.
        schedule(context)
    }
}
