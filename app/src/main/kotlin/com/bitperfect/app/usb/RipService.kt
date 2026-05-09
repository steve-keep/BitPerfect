package com.bitperfect.app.usb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.bitperfect.app.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import com.bitperfect.app.R // Assume R is accessible

class RipService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val channelId = "RipServiceChannel"
    private val notificationId = 1

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val session = RipSession.getInstance(applicationContext)

        startForeground(notificationId, createNotification("Starting rip...", 0, 0, 0))

        scope.launch {
            combine(session.ripStates, session.isRipping) { states, isRipping ->
                Pair(states, isRipping)
            }.collect { (states, isRipping) ->
                if (!isRipping) {
                    stopSelf()
                    return@collect
                }

                if (states.isEmpty()) return@collect

                val totalTracks = states.size
                var currentlyRippingTrack: TrackRipState? = null

                for (state in states.values) {
                    if (state.status == RipStatus.RIPPING || state.status == RipStatus.IDLE || state.status == RipStatus.VERIFYING) {
                        currentlyRippingTrack = state
                        break // Finding the earliest track that is not success/error/cancelled
                    }
                }

                if (currentlyRippingTrack == null) {
                    // It means everything is either success, error or unverified. Still wait for isRipping to be false
                    updateNotification("Finishing up...", 100, 100, 0)
                } else {
                    val completedTracks = states.values.count { it.status == RipStatus.SUCCESS || it.status == RipStatus.UNVERIFIED || it.status == RipStatus.WARNING }

                    val trackNumber = currentlyRippingTrack.trackNumber
                    val progressInt = (currentlyRippingTrack.progress * 100).toInt()

                    val text = "Ripping Track $trackNumber of $totalTracks - $progressInt%"

                    updateNotification(text, totalTracks, completedTracks, progressInt)
                }
            }
        }

        return START_STICKY
    }

    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(
            channelId,
            "CD Ripping Service",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(serviceChannel)
    }

    private fun createNotification(text: String, maxProgress: Int, currentProgress: Int, trackProgress: Int): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, channelId)
            .setContentTitle("BitPerfect Ripping")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher) // R.drawable.ic_notification is better if available, but we use launcher for now
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        if (maxProgress > 0) {
            // We can show overall progress or track progress
            builder.setProgress(100, trackProgress, false)
        }

        return builder.build()
    }

    private fun updateNotification(text: String, maxProgress: Int, currentProgress: Int, trackProgress: Int) {
        val notification = createNotification(text, maxProgress, currentProgress, trackProgress)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
