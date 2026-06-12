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
import com.bitperfect.app.output.OutputRepository
import com.bitperfect.app.output.SpeakerTypeProvider
import com.bitperfect.app.player.PlayerRepository
import com.bitperfect.core.output.OutputPluginRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class BitPerfectApplication : Application(), ImageLoaderFactory {
    lateinit var playerRepository: PlayerRepository
        private set

    lateinit var outputRepository: OutputRepository
        private set

    lateinit var speakerTypeProvider: SpeakerTypeProvider
        private set

    val outputPluginRegistry = OutputPluginRegistry()

    override fun onCreate() {
        super.onCreate()
        // TODO Phase 2: register(UsbDacOutputPlugin(this))
        // TODO Phase 3: register(WiimOutputPlugin(this))
        val crashHandler = CrashHandler(this)
        Thread.setDefaultUncaughtExceptionHandler(crashHandler)

        TagOptionSingleton.getInstance().isAndroid = true

        DeviceStateManager.initialize(this)
        com.bitperfect.app.usb.RipRepository.getInstance()

        playerRepository = PlayerRepository(this)

        outputRepository = OutputRepository(
            this,
            playerRepository,
            CoroutineScope(SupervisorJob() + Dispatchers.IO)
        )

        speakerTypeProvider = SpeakerTypeProvider(
            this,
            CoroutineScope(SupervisorJob() + Dispatchers.Main)
        )
        speakerTypeProvider.setOutputRepository(outputRepository)
        playerRepository.setSpeakerTypeProvider(speakerTypeProvider)
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
