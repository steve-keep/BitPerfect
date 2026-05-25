package com.bitperfect.app

import android.app.Application
import org.jaudiotagger.tag.TagOptionSingleton
import com.bitperfect.app.usb.DeviceStateManager
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import okhttp3.CacheControl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class BitPerfectApplication : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        val crashHandler = CrashHandler(this)
        Thread.setDefaultUncaughtExceptionHandler(crashHandler)

        TagOptionSingleton.getInstance().isAndroid = true

        DeviceStateManager.initialize(this)
        com.bitperfect.app.usb.RipSession.getInstance(this)
    }

    override fun newImageLoader(): ImageLoader {
        val cacheInterceptor = Interceptor { chain ->
            val request = chain.request()
            val response = chain.proceed(request)

            // Cache after 30 days
            val cacheControl = CacheControl.Builder()
                .maxAge(30, TimeUnit.DAYS)
                .build()

            response.newBuilder()
                .header("Cache-Control", cacheControl.toString())
                .build()
        }

        val okHttpClient = OkHttpClient.Builder()
            .addNetworkInterceptor(cacheInterceptor)
            .build()

        return ImageLoader.Builder(this)
            .okHttpClient(okHttpClient)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizePercent(0.02)
                    .build()
            }
            .build()
    }
}
