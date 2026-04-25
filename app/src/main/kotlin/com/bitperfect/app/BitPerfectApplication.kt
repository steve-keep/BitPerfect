package com.bitperfect.app

import android.app.Application
import com.bitperfect.app.di.appModule
import org.jaudiotagger.tag.TagOptionSingleton
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level

class BitPerfectApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        TagOptionSingleton.getInstance().isAndroid = true
        OpenTelemetryProvider.initialize(this)

        org.koin.core.context.GlobalContext.getOrNull() ?: org.koin.core.context.startKoin {
            androidLogger(Level.ERROR)
            androidContext(this@BitPerfectApplication)
            modules(appModule)
        }
    }
}
