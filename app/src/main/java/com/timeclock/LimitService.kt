package com.timeclock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Work-limit enforcer. Runs as a foreground service while clocked in ("armed"
 * watch mode), and on a mark turns into a blaring alarm (auto clock-out
 * written at the exact mark instant first). Exact alarms via LimitReceiver are
 * the backup that fires it when doze freezes this service's timer.
 */
class LimitService : Service() {

    companion object {
        const val ACTION_START = "com.timeclock.limit.START"
        const val ACTION_ALARM = "com.timeclock.limit.ALARM"
        const val ACTION_STOP_ALARM = "com.timeclock.limit.STOP_ALARM"

        /** Broadcast (package-scoped) right after an auto clock-out punch is written. */
        const val ACTION_TRIGGERED = "com.timeclock.limit.STOPPED"

        private const val CHANNEL_WATCH = "limit_watch"
        private const val CHANNEL_ALARM = "limit_alarm"
        private const val NOTIF_WATCH = 4201
        private const val NOTIF_ALARM = 4202
        private const val BLARE_MAX_MS = 5L * 60_000

        @Volatile
        var running = false
            private set

        /** True while the alarm loop is playing — the service must not be
         *  stopped from the outside until it's dismissed (its tick guard
         *  already ignores watch-mode logic in this state). */
        @Volatile
        var blaring = false
            private set

        /** Start the armed watch; no-op when already up. Safe to call repeatedly. */
        fun ensureStarted(ctx: Context) {
            if (running) return
            ContextCompat.startForegroundService(
                ctx, Intent(ctx, LimitService::class.java).setAction(ACTION_START)
            )
        }

        /** Alarm mode — used by the backup alarm receiver. */
        fun startAlarm(ctx: Context) {
            ContextCompat.startForegroundService(
                ctx, Intent(ctx, LimitService::class.java).setAction(ACTION_ALARM)
            )
        }

        fun stopIfNeeded(ctx: Context) {
            // Never kill a service that is mid-alarm; it stops itself when dismissed.
            if (running && !blaring) ctx.stopService(Intent(ctx, LimitService::class.java))
        }
    }

    private enum class Mode { WATCH, ALARM }

    private var mode = Mode.WATCH
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var oldAlarmVolume = -1

    private val tickRun = Runnable { tick() }

    override fun onCreate() {
        super.onCreate()
        running = true
        makeChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ALARM -> {
                // Counter was already stopped by whoever fired this; just blare.
                if (mode == Mode.WATCH) startBlare()
            }
            ACTION_STOP_ALARM -> {
                stopBlare()
                stopSelf()
            }
            else -> { // ACTION_START, or a sticky restart (null intent) — (re)arm the watch.
                if (mode == Mode.WATCH) {
                    goForeground(NOTIF_WATCH, watchNotification(null))
                    handler.removeCallbacks(tickRun)
                    handler.post(tickRun)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopBlare()
        running = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    /** Watch tick: keep the notification fresh, enforce on time, die if disarmed. */
    private fun tick() {
        if (mode != Mode.WATCH) return
        val punches = TimeDb(this).allPunches()
        val armed = punches.isNotEmpty() && punches.last().isIn && Limit.armedToday(this, punches.last().ts)
        if (!armed) {
            stopSelf()
            return
        }
        val due = limitCrossings(punches, punches.last().ts).firstOrNull()
        if (due == null) {
            stopSelf() // every mark already consumed — nothing left to enforce
            return
        }
        if (due.at <= System.currentTimeMillis()) {
            stopAtMark(due)
            return
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIF_WATCH, watchNotification(due))
        handler.postDelayed(tickRun, 15_000)
    }

    /** Write the auto clock-out at the exact mark instant, then blare. */
    private fun stopAtMark(due: LimitMark) {
        val punches = TimeDb(this).allPunches()
        if (punches.lastOrNull()?.isIn == true) {
            TimeDb(this).addPunch("out", due.at)
            sendBroadcast(Intent(ACTION_TRIGGERED).setPackage(packageName))
        }
        startBlare()
    }

    private fun startBlare() {
        mode = Mode.ALARM
        blaring = true
        handler.removeCallbacks(tickRun)
        goForeground(NOTIF_ALARM, alarmNotification())

        // "Blaring" at half alarm volume (Bradley's preference) — raised only
        // if the stream is quieter than that, restored after.
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        val cur = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        val target = max / 2
        if (max > 0 && cur < target) {
            oldAlarmVolume = cur
            audio.setStreamVolume(AudioManager.STREAM_ALARM, target, 0)
        }

        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        if (uri != null) {
            player = MediaPlayer().apply {
                try {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    setDataSource(this@LimitService, uri)
                    isLooping = true
                    prepare()
                    start()
                } catch (e: Exception) {
                    release()
                    player = null
                }
            }
        }
        vibrator = systemVibrator().apply {
            vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 500), 0))
        }
        // Safety cap: stop after 5 minutes so it can't blare forever unattended.
        handler.postDelayed({ stopBlare(); stopSelf() }, BLARE_MAX_MS)
    }

    private fun stopBlare() {
        handler.removeCallbacksAndMessages(null)
        blaring = false
        player?.let {
            try { it.stop() } catch (e: Exception) {}
            it.release()
        }
        player = null
        vibrator?.cancel()
        vibrator = null
        if (oldAlarmVolume >= 0) {
            val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audio.setStreamVolume(AudioManager.STREAM_ALARM, oldAlarmVolume, 0)
            oldAlarmVolume = -1
        }
    }

    private fun systemVibrator(): Vibrator =
        if (Build.VERSION.SDK_INT >= 31) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

    /** FGS start can be blocked from the background; fail quietly if so. */
    private fun goForeground(id: Int, notif: Notification) {
        try {
            ServiceCompat.startForeground(
                this, id, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } catch (e: Exception) {
            stopSelf()
        }
    }

    private fun watchNotification(due: LimitMark?): Notification {
        val next = due?.let { " · next stop ${fmtClock(it.at)}" } ?: ""
        return builder(CHANNEL_WATCH)
            .setContentTitle("Clocked in")
            .setContentText("Work limit armed$next")
            .build()
    }

    private fun alarmNotification(): Notification {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, LimitService::class.java).setAction(ACTION_STOP_ALARM),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return builder(CHANNEL_ALARM)
            .setContentTitle("WORK LIMIT REACHED")
            .setContentText("Auto clocked out — tap to stop the alarm")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .addAction(0, "Stop alarm", stop)
            .build()
    }

    private fun builder(channel: String): NotificationCompat.Builder =
        NotificationCompat.Builder(this, channel)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )

    private fun makeChannels() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_WATCH, "Work limit (armed)", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALARM, "Work limit (alarm)", NotificationManager.IMPORTANCE_HIGH).apply {
                setShowBadge(false)
                setSound(null, null) // we play the loop ourselves via MediaPlayer
                enableVibration(false) // we vibrate on our own pattern
            }
        )
    }
}
