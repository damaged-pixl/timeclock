package com.timeclock

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.util.Calendar

/**
 * Work-limit settings + enforcement scheduling. The mark maths is pure code in
 * Calc.kt; everything here turns those marks into alarms, auto clock-outs and
 * catch-ups.
 */
object Limit {

    const val ACTION_CHECK = "com.timeclock.limit.CHECK"

    private const val REQ_ALARM = 4101 // + slot index, max 3 crossings a day
    private const val FALLBACK_NOTIF_ID = 4203
    private const val FALLBACK_CHANNEL = "limit_fallback"

    fun enabled(ctx: Context): Boolean = prefs(ctx).getBoolean("limit_enabled", false)
    fun skipThursdays(ctx: Context): Boolean = prefs(ctx).getBoolean("limit_skip_thursday", false)

    fun save(ctx: Context, enabled: Boolean, skipThursday: Boolean) {
        prefs(ctx).edit()
            .putBoolean("limit_enabled", enabled)
            .putBoolean("limit_skip_thursday", skipThursday)
            .apply()
    }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun isThursday(ts: Long): Boolean = Calendar.getInstance().run {
        timeInMillis = ts
        get(Calendar.DAY_OF_WEEK) == Calendar.THURSDAY
    }

    /** Is the limit feature armed for the day containing [ts]? */
    fun armedToday(ctx: Context, ts: Long): Boolean =
        enabled(ctx) && !(skipThursdays(ctx) && isThursday(ts))

    /** Toasts must come from a Looper thread; receiver workers don't have one. */
    fun toast(ctx: Context, msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(ctx.applicationContext, msg, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Recompute the whole enforcement state: cancel alarms, backdate an auto
     * clock-out if a mark was crossed while we couldn't act (missed alarm,
     * phone off, reboot), and schedule exact alarms for the remaining
     * crossings. Safe to call as often as wanted — every punch change, app
     * open, settings change, boot. Returns true when it stopped the counter.
     */
    fun checkAndSchedule(ctx: Context): Boolean {
        cancelAlarms(ctx)
        if (!enabled(ctx)) return false
        val db = TimeDb(ctx)
        val punches = db.allPunches()
        if (!isClockedIn(punches)) return false
        val openIn = punches.last().ts
        if (!armedToday(ctx, openIn)) return false

        val crossings = limitCrossings(punches, openIn)
        val due = crossings.firstOrNull() ?: return false // nothing left to enforce today

        if (due.at <= System.currentTimeMillis()) {
            // Mark crossed while we couldn't act — stop the counter now,
            // backdated to the exact crossing instant.
            if (punches.last().isIn) {
                db.addPunch("out", due.at)
                toast(
                    ctx,
                    "Auto clocked out at ${fmtClock(due.at)} — " +
                        if (due.kind == LimitKind.DAY) "1 hour limit" else "half hour limit"
                )
                return true
            }
            return false
        }

        // One alarm per distinct crossing instant (marks can share one).
        val instants = crossings.map { it.at }.distinct()
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val now = System.currentTimeMillis()
        instants.forEachIndexed { i, at ->
            am.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + (at - now),
                alarmPi(ctx, i)
            )
        }
        return false
    }

    private fun alarmPi(ctx: Context, slot: Int): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, REQ_ALARM + slot,
            Intent(ctx, LimitReceiver::class.java).setAction(ACTION_CHECK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun cancelAlarms(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        repeat(3) { am.cancel(alarmPi(ctx, it)) }
    }

    /**
     * Last-resort alert when the foreground service can't run (background FGS
     * start blocked): a max-priority notification on a channel that carries
     * the alarm sound itself. The counter has already been stopped by then.
     */
    fun postFallbackAlarm(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(FALLBACK_CHANNEL, "Work limit (missed alarm)", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 700, 500)
            }
        )
        val notif = NotificationCompat.Builder(ctx, FALLBACK_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("WORK LIMIT REACHED")
            .setContentText("Auto clocked out — tap to open")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(
                PendingIntent.getActivity(
                    ctx, 2, Intent(ctx, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setAutoCancel(true)
            .build()
        nm.notify(FALLBACK_NOTIF_ID, notif)
    }
}
