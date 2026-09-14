package com.jo.selfcontrol.ultimate

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Closes the install window at its deadline when LimitService is not alive to do it from its own
 * tick — a killed service, a Doze-suspended handler.
 *
 * Mirrors [DailyResetReceiver]'s exact-alarm handling, including the downgrade to an inexact alarm
 * when `SCHEDULE_EXACT_ALARM` is unavailable. An inexact alarm can overshoot by minutes under Doze,
 * which is tolerable only because this is the *backup* path: while the foreground service lives, it
 * is the one that closes the window on time.
 *
 * The alarm calls [InstallWindowManager.enforce] rather than closing outright, so a late-firing
 * alarm cannot cut short a newer window that has legitimately been opened since.
 */
class InstallWindowReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SelfControl.InstallWin"
        private const val REQUEST_CODE = 7778
        private const val ACTION = "com.jo.selfcontrol.ultimate.INSTALL_WINDOW_EXPIRED"

        fun schedule(context: Context, triggerAt: Long) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = pendingIntent(context)

            val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
            try {
                if (canExact) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                    Log.i(TAG, "Close alarm set (exact) for ${java.util.Date(triggerAt)}")
                } else {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                    Log.i(TAG, "Close alarm set (inexact — SCHEDULE_EXACT_ALARM missing) for ${java.util.Date(triggerAt)}")
                }
            } catch (e: SecurityException) {
                // Some OEMs reject exact alarms at runtime even when the check passed.
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                Log.w(TAG, "Exact alarm rejected, falling back to inexact: ${e.message}")
            }
        }

        fun cancel(context: Context) {
            try {
                val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                am.cancel(pendingIntent(context))
            } catch (e: Exception) {
                Log.w(TAG, "Cannot cancel close alarm: ${e.message}")
            }
        }

        private fun pendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, InstallWindowReceiver::class.java).apply { action = ACTION }
            return PendingIntent.getBroadcast(
                context, REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        Log.w(TAG, "Install window deadline alarm fired")
        InstallWindowManager.enforce(context)
        // The service is what keeps the window honest; if the alarm had to fire, it may be dead.
        LimitService.start(context)
    }
}
