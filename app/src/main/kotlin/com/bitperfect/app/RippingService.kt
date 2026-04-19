package com.bitperfect.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.bitperfect.core.engine.*
import com.bitperfect.driver.ScsiDriver
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class RippingService : Service() {
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private var wakeLock: PowerManager.WakeLock? = null

    private val scsiDriver = ScsiDriver()
    lateinit var rippingEngine: RippingEngine

    private val NOTIFICATION_ID = 101
    private val CHANNEL_ID = "ripping_channel"

    inner class LocalBinder : Binder() {
        fun getService(): RippingService = this@RippingService
    }

    private val binder = LocalBinder()

    private var ripJob: Job? = null

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        rippingEngine = RippingEngine(this, scsiDriver)
        createNotificationChannel()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, createNotification(RipState()), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, createNotification(RipState()))
        }

        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager).run {
            newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BitPerfect:RippingWakeLock").apply {
                acquire()
            }
        }

        serviceScope.launch {
            rippingEngine.ripState.collectLatest { state ->
                updateNotification(state)
                if (!state.isRunning && state.status == "Full Rip Complete") {
                    // Optionally stop foreground or service here if needed,
                    // but usually better to let the user dismiss or have a "Done" action
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Ripping Service"
            val descriptionText = "Shows progress of the current CD rip"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(state: RipState): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val progress = (state.progress * 100).toInt()
        val contentText = if (state.isRunning) {
            "Track ${state.currentTrack}/${state.totalTracks}: ${state.status}"
        } else {
            state.status
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BitPerfect Ripping")
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setProgress(100, progress, !state.isRunning && state.progress == 0f)
            .setOngoing(state.isRunning)
            .build()
    }

    private fun updateNotification(state: RipState) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification(state))
    }
    suspend fun pollStatus(
        fd: Int,
        driverToUse: com.bitperfect.driver.IScsiDriver,
        endpointIn: Int,
        endpointOut: Int,
        forceRefresh: Boolean = false
    ) {
        rippingEngine.pollDriveStatus(fd, driverToUse, endpointIn, endpointOut, forceRefresh)
    }

    suspend fun ejectDisc(
        fd: Int,
        driverToUse: com.bitperfect.driver.IScsiDriver,
        endpointIn: Int,
        endpointOut: Int,
        forceRefresh: Boolean = false
    ) {
        rippingEngine.ejectDisc(fd, driverToUse, endpointIn, endpointOut)
    }

    suspend fun loadTray(
        fd: Int,
        driverToUse: com.bitperfect.driver.IScsiDriver,
        endpointIn: Int,
        endpointOut: Int,
        forceRefresh: Boolean = false
    ) {
        rippingEngine.loadTray(fd, driverToUse, endpointIn, endpointOut)
    }
    fun cancelRip() {
        ripJob?.cancel()
        rippingEngine.cancel()
    }

    fun startRip(
        fd: Int,
        basePath: String,
        driveModel: String,
        capabilities: DriveCapabilities,
        driverToUse: com.bitperfect.driver.IScsiDriver,
        endpointIn: Int,
        endpointOut: Int,
        forceRefresh: Boolean = false
    ) {
        ripJob = serviceScope.launch {
            rippingEngine.fullRip(this@RippingService, fd, basePath, driveModel, capabilities, driverToUse, endpointIn, endpointOut)
            stopSelfIfFinished()
        }
    }

    private fun stopSelfIfFinished() {
        if (!rippingEngine.ripState.value.isRunning) {
            // We might want to keep it running so user can see "Complete" in notification
            // stopForeground(STOP_FOREGROUND_DETACH)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
    }
}
