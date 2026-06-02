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
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.bitperfect.app.MainActivity
import com.bitperfect.core.utils.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import com.bitperfect.app.R

import androidx.core.app.ServiceCompat
import android.content.pm.ServiceInfo
import kotlinx.coroutines.withContext

class RipService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val channelId = "RipServiceChannel"
    private val notificationId = 1

    private lateinit var wakeLock: PowerManager.WakeLock

    private var artistName = ""
    private var albumTitle = ""
    private var ripManager: RipManager? = null

    private val repository = RipRepository.getInstance()

    companion object {
        const val EXTRA_ARTIST = "extra_artist"
        const val EXTRA_ALBUM = "extra_album"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Acquire wake lock BEFORE startForeground so it's held during the entire rip
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "BitPerfect::RipWakeLock"
        )
        wakeLock.acquire(/* timeout = */ 4 * 60 * 60 * 1000L) // 4 hours max safety timeout
        AppLogger.d("RipService", "Acquired partial wake lock")

        artistName = intent?.getStringExtra(EXTRA_ARTIST) ?: ""
        albumTitle = intent?.getStringExtra(EXTRA_ALBUM) ?: ""

        val title = if (artistName.isNotBlank() && albumTitle.isNotBlank()) {
            "$artistName — $albumTitle"
        } else {
            "BitPerfect"
        }

        val startingText = getString(R.string.notif_starting)

        var foregroundServiceType = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // API 34
            foregroundServiceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            foregroundServiceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }

        ServiceCompat.startForeground(
            this,
            notificationId,
            buildNotification(title, startingText, "", 0, 0, indeterminate = true),
            foregroundServiceType
        )

        // Initialize RipManager using the parameters from repository
        val params = repository.pendingRipParameters
        if (params == null) {
            AppLogger.e("RipService", "No pending rip parameters found")
            stopSelf()
            return START_NOT_STICKY
        }

        repository.pendingRipParameters = null

        val info = DeviceStateManager.driveStatus.value.info
        val driveVendor = info?.vendor ?: ""
        val driveProduct = info?.model ?: ""

        val previousStates = if (repository.ripStates.value.isNotEmpty()) repository.ripStates.value else null

        val manager = RipManager(
            context = applicationContext,
            outputFolderUriString = params.outputFolderUriString,
            toc = params.toc,
            metadata = params.metadata,
            expectedChecksums = params.expectedChecksums,
            artworkBytes = params.artworkBytes,
            lyricsMap = params.lyricsMap,
            driveVendor = driveVendor,
            driveProduct = driveProduct,
            initialTracks = params.tracksToRip ?: params.toc.tracks.map { it.trackNumber },
            previousStates = previousStates
        )
        ripManager = manager

        repository.setIsRipping(true)
        repository.updateRipStates(manager.trackStates.value)

        // Launch the actual ripping process
        scope.launch {
            var unexpectedError = false
            try {
                withContext(Dispatchers.IO) {
                    UsbReadSession.open().use { session ->
                        manager.startRipping(session)
                    }
                }
            } catch (e: Exception) {
                unexpectedError = true
                AppLogger.e("RipService", "Failed to open USB session", e)
            } finally {
                repository.setIsRipping(false)

                if (unexpectedError && manager.isCancelled == false) {
                    AppLogger.w("RipService", "Rip ended with unexpected error — USB connection preserved for polling loop recovery")
                }
            }
        }

        // Listen for track states and update repository and notification
        scope.launch(Dispatchers.Main) {
            combine(manager.trackStates, repository.isRipping) { states, isRipping ->
                Pair(states, isRipping)
            }.collect { (states, isRipping) ->
                repository.updateRipStates(states)

                if (!isRipping) {
                    ServiceCompat.stopForeground(this@RipService, ServiceCompat.STOP_FOREGROUND_REMOVE)
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

        // Listen for queue track events
        scope.launch {
            repository.queueTrackEvent.collect { trackNumber ->
                if (trackNumber != null) {
                    ripManager?.queueTrack(trackNumber)
                    repository.clearQueueTrackEvent()
                }
            }
        }

        // Listen for cancel events
        scope.launch {
            repository.cancelEvent.collect { deleteFiles ->
                if (deleteFiles != null) {
                    ripManager?.cancel()
                    if (deleteFiles) {
                        ripManager?.deleteRipFiles()
                    }
                    repository.clearCancelEvent()
                }
            }
        }

        return START_NOT_STICKY
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
        if (::wakeLock.isInitialized && wakeLock.isHeld) {
            wakeLock.release()
            AppLogger.d("RipService", "Released partial wake lock")
        }
        repository.setIsRipping(false)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
