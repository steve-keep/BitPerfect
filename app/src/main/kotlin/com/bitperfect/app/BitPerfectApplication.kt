package com.bitperfect.app

import android.app.Application
import org.jaudiotagger.tag.TagOptionSingleton

class BitPerfectApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        TagOptionSingleton.getInstance().isAndroid = true
        OpenTelemetryProvider.initialize(this)
    }
}
