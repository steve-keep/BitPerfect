package com.bitperfect.app.ui.utils

import android.content.Context
import android.net.Uri
import androidx.collection.LruCache
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ColorExtractor {
    // Cache extracted colors by URI string to avoid recalculating the palette
    private val colorCache = LruCache<String, Color>(50)
    private const val PREFS_NAME = "color_extractor_cache"

    fun getCachedColor(context: Context, uriString: String?): Color? {
        if (uriString == null) return null
        colorCache.get(uriString)?.let { return it }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.contains(uriString)) {
            val colorValue = prefs.getInt(uriString, 0)
            val color = Color(colorValue)
            colorCache.put(uriString, color)
            return color
        }
        return null
    }

    suspend fun extractVividColor(context: Context, uriString: String): Color? = withContext(Dispatchers.IO) {
        getCachedColor(context, uriString)?.let { return@withContext it }

        try {
            val uri = Uri.parse(uriString)
            val request = ImageRequest.Builder(context)
                .data(uri)
                .allowHardware(false)
                // Load at a consistent fixed size for color extraction to guarantee same palette
                .size(400)
                .build()

            val result = context.imageLoader.execute(request)
            val drawable = result.drawable ?: return@withContext null
            val bitmap = drawable.toBitmap()

            val palette = Palette.from(bitmap)
                .generate()

            // Prioritize light vibrant swatch
            val swatch = palette.lightVibrantSwatch
                ?: palette.vibrantSwatch
                ?: palette.lightMutedSwatch
                ?: palette.mutedSwatch
                ?: palette.dominantSwatch

            swatch?.rgb?.let { colorValue ->
                val color = Color(colorValue)
                colorCache.put(uriString, color)
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().putInt(uriString, colorValue).apply()
                return@withContext color
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
        }
        null
    }
}
