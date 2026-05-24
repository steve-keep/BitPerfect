package com.bitperfect.app.usb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
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

import androidx.core.app.ServiceCompat

class RipService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val channelId = "RipServiceChannel"
    private val notificationId = 1

    private var artistName = ""
    private var albumTitle = ""

    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        const val EXTRA_ARTIST = "extra_artist"
        const val EXTRA_ALBUM = "extra_album"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BitPerfect:RipWakeLock").apply {
            acquire()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val session = RipSession.getInstance(applicationContext)

        artistName = intent?.getStringExtra(EXTRA_ARTIST) ?: ""
        albumTitle = intent?.getStringExtra(EXTRA_ALBUM) ?: ""

        val title = if (artistName.isNotBlank() && albumTitle.isNotBlank()) {
            "$artistName — $albumTitle"
        } else {
            "BitPerfect"
        }

        val startingText = getString(R.string.notif_starting)
        startForeground(
            notificationId,
            buildNotification(title, startingText, "", 0, 0, indeterminate = true)
        )

        scope.launch {
            combine(session.ripStates, session.isRipping) { states, isRipping ->
                Pair(states, isRipping)
            }.collect { (states, isRipping) ->
                if (!isRipping) {
                    ServiceCompat.stopForeground(this@RipService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    if (wakeLock?.isHeld == true) {
                        wakeLock?.release()
                    }
                    stopSelf()
                    return@collect
                }

                if (states.isEmpty()) return@collect

                val totalTracks = states.size

                var currentlyRippingTrack: TrackRipState? = null

                // Priority 1: RIPPING
                currentlyRippingTrack = states.values.filter { it.status == RipStatus.RIPPING }.minByOrNull { it.trackNumber }
                // Priority 2: VERIFYING
                if (currentlyRippingTrack == null) {
                    currentlyRippingTrack = states.values.filter { it.status == RipStatus.VERIFYING }.minByOrNull { it.trackNumber }
                }
                // Priority 3: IDLE
                if (currentlyRippingTrack == null) {
                    currentlyRippingTrack = states.values.filter { it.status == RipStatus.IDLE }.minByOrNull { it.trackNumber }
                }

                if (currentlyRippingTrack == null) {
                    // It means everything is either success, error or unverified. Still wait for isRipping to be false
                    updateNotification(title, getString(R.string.notif_finishing), "", 100, 100, indeterminate = true)
                } else {
                    val completedTracks = states.values.count {
                        it.status in setOf(RipStatus.SUCCESS, RipStatus.UNVERIFIED, RipStatus.WARNING, RipStatus.ERROR)
                    }

                    val trackNumber = currentlyRippingTrack.trackNumber
                    val progressInt = (currentlyRippingTrack.progress * 100).toInt()

                    val text = if (currentlyRippingTrack.status == RipStatus.VERIFYING) {
                        getString(R.string.notif_verifying_text, trackNumber)
                    } else {
                        getString(R.string.notif_ripping_text, trackNumber, totalTracks, progressInt)
                    }

                    val subText = getString(R.string.notif_subtext, completedTracks, totalTracks)

                    val overallMax = totalTracks * 100
                    val overallProgress = completedTracks * 100 + progressInt

                    updateNotification(title, text, subText, overallMax, overallProgress)
                }
            }
        }

        return START_STICKY
    }

    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(
            channelId,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(serviceChannel)
    }

    private fun buildNotification(
        title: String,
        text: String,
        subText: String,
        overallMax: Int,
        overallProgress: Int,
        indeterminate: Boolean = false
    ): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val cancelIntent = PendingIntent.getBroadcast(
            this, 0,
            Intent(this, RipCancelReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(subText)
            // TODO: Replace with a monochrome R.drawable.ic_notification once created
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .addAction(0, getString(R.string.notif_cancel), cancelIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        if (overallMax > 0 || indeterminate) {
            builder.setProgress(overallMax, overallProgress, indeterminate)
        }

        return builder.build()
    }

    private fun updateNotification(
        title: String,
        text: String,
        subText: String,
        overallMax: Int,
        overallProgress: Int,
        indeterminate: Boolean = false
    ) {
        val notification = buildNotification(title, text, subText, overallMax, overallProgress, indeterminate)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
