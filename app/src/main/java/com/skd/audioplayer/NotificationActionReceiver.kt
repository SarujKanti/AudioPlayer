package com.skd.audioplayer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives PendingIntent broadcasts fired by the media notification buttons
 * (previous, play/pause, next) and delegates to the live MainActivity instance.
 *
 * Works on all Android versions (API 24+):
 *   - The PendingIntents are created with FLAG_IMMUTABLE (required on API 31+).
 *   - The receiver is declared in the manifest so it is invoked even when the
 *     app is in the background.
 *   - If MainActivity has been destroyed (app killed), the WeakReference is null
 *     and the action is silently ignored.
 */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        val activity = MainActivity.instance?.get() ?: return
        when (intent?.action) {
            MainActivity.ACTION_PREVIOUS -> activity.playPreviousSong()
            MainActivity.ACTION_TOGGLE   -> activity.togglePlayback()
            MainActivity.ACTION_NEXT     -> activity.playNextSong()
        }
    }
}
