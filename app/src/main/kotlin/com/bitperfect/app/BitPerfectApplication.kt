package com.bitperfect.app

import android.app.Application
import org.jaudiotagger.tag.TagOptionSingleton
import com.bitperfect.app.usb.DeviceStateManager

class BitPerfectApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val crashHandler = CrashHandler(this)
        Thread.setDefaultUncaughtExceptionHandler(crashHandler)

        TagOptionSingleton.getInstance().isAndroid = true
        OpenTelemetryProvider.initialize(this)
        DeviceStateManager.initialize(this)
        com.bitperfect.app.usb.RipSession.getInstance(this)
    }
}
