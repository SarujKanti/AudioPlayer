package com.skd.pocketWaves

import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat

/**
 * Minimal service whose only job is to cancel the playback notification
 * the moment the user swipes the app away from the recents screen.
 *
 * android:stopWithTask="false" (set in the manifest) keeps this service
 * alive after the task is removed so that onTaskRemoved() is delivered.
 * Without it, the service is destroyed together with the task and the
 * callback is never received.
 */
class AppLifecycleService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_NOT_STICKY   // do not restart if the system kills the service

    override fun onTaskRemoved(rootIntent: Intent?) {
        // User swiped the app from recents — cancel the notification immediately
        NotificationManagerCompat.from(this).cancelAll()
        stopSelf()
    }
}
