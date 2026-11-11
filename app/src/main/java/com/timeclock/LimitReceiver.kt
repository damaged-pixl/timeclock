package com.timeclock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlin.concurrent.thread

/**
 * Fires work-limit enforcement from the exact alarms Limit.checkAndSchedule()
 * schedules at clock-in, and re-arms after boot. Always recomputes reality
 * from the db before acting, so a stale alarm can never stop a counter that
 * already stopped.
 */
class LimitReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        thread {
            try {
                when (intent.action) {
                    Intent.ACTION_BOOT_COMPLETED -> {
                        Limit.checkAndSchedule(context)
                        // FGS start from BOOT_COMPLETED is an explicit system
                        // exemption, so the watch can be re-armed here.
                        LimitService.ensureStarted(context)
                    }
                    else -> liveFire(context)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun liveFire(context: Context) {
        val punches = TimeDb(context).allPunches()
        if (punches.isEmpty() || !Limit.armedToday(context, punches.last().ts)) {
            Limit.checkAndSchedule(context)
            return
        }
        val due = limitCrossings(punches, punches.last().ts).firstOrNull()
        val now = System.currentTimeMillis()
        if (due == null || due.at > now + 2000) {
            // State changed since this alarm was set — just recompute.
            Limit.checkAndSchedule(context)
            return
        }
        // The mark we were waiting for is due: stop the counter at the exact
        // instant, then blare via the service (fallback notification if FGS
        // start is blocked).
        if (punches.last().isIn) {
            TimeDb(context).addPunch("out", due.at)
            context.sendBroadcast(
                Intent(LimitService.ACTION_TRIGGERED).setPackage(context.packageName)
            )
        }
        if (LimitService.running) {
            LimitService.startAlarm(context)
        } else {
            LimitService.startAlarm(context)
            Limit.postFallbackAlarm(context)
        }
        Limit.checkAndSchedule(context) // cancels the other pending alarms — clocked out now
    }
}
