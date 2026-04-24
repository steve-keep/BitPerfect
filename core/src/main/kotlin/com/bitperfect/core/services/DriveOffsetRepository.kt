package com.bitperfect.core.services

import android.content.Context
import android.util.Log
import com.bitperfect.core.models.DriveOffset
import com.bitperfect.core.models.DriveOffsetsResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.io.File

class DriveOffsetRepository(private val context: Context) {
    private val TAG = "DriveOffsetRepository"
    private val CACHE_FILE_NAME = "drive_offsets_cache.json"
    private val OFFSETS_URL = "https://raw.githubusercontent.com/steve-keep/BitPerfect/refs/heads/main/data/drive-offsets.json"

    private val json = Json { ignoreUnknownKeys = true }

    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(json)
        }
    }

    private val _offsets = MutableStateFlow<List<DriveOffset>?>(null)
    val offsets: StateFlow<List<DriveOffset>?> = _offsets.asStateFlow()

    private val _generatedAt = MutableStateFlow<String?>(null)
    val generatedAt: StateFlow<String?> = _generatedAt.asStateFlow()

    suspend fun initialize() {
        withContext(Dispatchers.IO) {
            loadFromCache()
            fetchAndCache()
        }
    }

    private fun loadFromCache() {
        try {
            val cacheFile = File(context.cacheDir, CACHE_FILE_NAME)
            if (cacheFile.exists()) {
                val jsonString = cacheFile.readText()
                val response: DriveOffsetsResponse = json.decodeFromString(jsonString)
                _offsets.value = response.drives
                _generatedAt.value = response.generated_at
                Log.d(TAG, "Loaded ${_offsets.value?.size} offsets from cache")
            } else {
                Log.d(TAG, "Cache file not found")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading from cache: ${Log.getStackTraceString(e)}")
        }
    }

    private suspend fun fetchAndCache() {
        try {
            Log.d(TAG, "Fetching offsets from network...")
            val response: DriveOffsetsResponse = client.get(OFFSETS_URL).body()
            _offsets.value = response.drives
            _generatedAt.value = response.generated_at

            // Save to cache
            val cacheFile = File(context.cacheDir, CACHE_FILE_NAME)
            val jsonString = json.encodeToString(response)
            cacheFile.writeText(jsonString)
            Log.d(TAG, "Successfully fetched and cached ${_offsets.value?.size} offsets")
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching from network: ${Log.getStackTraceString(e)}")
        }
    }

    fun findOffset(vendor: String, product: String): DriveOffset? {
        val currentOffsets = _offsets.value ?: return null
        val normalizedVendor = vendor.trim().lowercase()
        val normalizedProduct = product.trim().lowercase()

        return currentOffsets.find {
            it.vendor.trim().lowercase() == normalizedVendor &&
            it.product.trim().lowercase() == normalizedProduct
        }
    }
}
