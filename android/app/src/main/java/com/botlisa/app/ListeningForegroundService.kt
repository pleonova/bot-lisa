package com.botlisa.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Companion to Lisa Assistant's hands-free mode -- carries no logic of its
 * own. SpeechAssistant, the TranslationSpeaker instances, and the /assist
 * networking all stay owned by LisaScreen's Composable in MainActivity.kt;
 * this service exists purely so that composable keeps running once the
 * screen locks or the app is backgrounded.
 *
 * Two things break hands-free mode the moment the app leaves the foreground:
 *  1. Since Android 9, a background process cannot touch the microphone at
 *     all unless it's running an active foreground service (this is an
 *     AppOpsManager policy, independent of which component opened the mic).
 *  2. Without something holding the process at foreground priority, Android
 *     can reclaim it (and MainActivity + its Composition, and the
 *     SpeechAssistant living inside it) under memory pressure once
 *     backgrounded.
 *
 * A bare foreground service with foregroundServiceType="microphone" resolves
 * both -- MainActivity starts/stops it alongside assistant.start()/.stop()
 * (see startHandsFree()/stopHandsFree() in MainActivity.kt). The partial
 * wake lock keeps CPU available for recognition + the /assist HTTP call
 * while the screen is off; Doze/App Standby would otherwise throttle both.
 */
class ListeningForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "botlisa:listening")
            .apply { setReferenceCounted(false); acquire(WAKE_LOCK_TIMEOUT_MS) }
    }

    // onCreate/onStartCommand/onDestroy/onBind are Service lifecycle
    // callbacks the Android system invokes -- this class doesn't call them
    // itself, it just implements what should happen at each stage.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground must be called quickly after the service starts
        // (a system requirement) -- it's what actually promotes this to a
        // foreground service, showing the notification below and unlocking
        // mic access while backgrounded.
        startForeground(NOTIFICATION_ID, buildNotification())
        // START_STICKY tells Android to recreate this service (with a null
        // Intent) if the system kills it under memory pressure, rather than
        // leaving it dead -- appropriate here since MainActivity expects it
        // to keep running for as long as hands-free mode is on.
        return START_STICKY
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    // Returning null means this service can't be bound to (no two-way
    // interface for other components to call into it) -- it only supports
    // start()/stop() via Intents, which is all MainActivity needs.
    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        // A PendingIntent hands another party (here, the notification
        // system) permission to fire an Intent as if it were this app,
        // later -- used so tapping the notification reopens MainActivity.
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Listening in the background")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID, "Hands-free listening", NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Shown while Lisa Assistant is listening in the background." }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "hands_free_listening"
        private const val NOTIFICATION_ID = 1
        // Safety net only -- start()/stop() are expected to bound the real
        // lifetime; this just guarantees the lock can't outlive a crashed
        // service indefinitely.
        private const val WAKE_LOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ListeningForegroundService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ListeningForegroundService::class.java))
        }
    }
}
