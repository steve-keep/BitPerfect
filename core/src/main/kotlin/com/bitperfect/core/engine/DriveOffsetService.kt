package com.bitperfect.core.engine

import android.content.Context
import android.util.Log
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import java.io.File

@Serializable
data class DriveOffset(
    val driveName: String,
    val offset: Int
)

class DriveOffsetService(private val context: Context, private val client: HttpClient = HttpClient(OkHttp)) {

    private val cacheFile: File by lazy {
        File(context.filesDir, "accuraterip_offsets.json")
    }

    private var inMemoryCache: List<DriveOffset>? = null
    private val mutex = Mutex()

    private val manufacturerMappings = mapOf(
        "JLMS" to "Lite-ON",
        "HL-DT-ST" to "LG Electronics",
        "Matshita" to "Panasonic"
    )

    suspend fun findOffsetForDrive(vendor: String, product: String): Int? {
        val offsets = getOffsets()

        var normalizedVendor = vendor.trim()
        val normalizedProduct = product.trim()

        // Apply manufacturer mappings
        manufacturerMappings.forEach { (key, value) ->
            if (normalizedVendor.equals(key, ignoreCase = true)) {
                normalizedVendor = value
            }
        }

        val searchString1 = "$normalizedVendor - $normalizedProduct"
        val searchString2 = "$normalizedVendor $normalizedProduct"

        val match = offsets.find {
            it.driveName.equals(searchString1, ignoreCase = true) ||
            it.driveName.equals(searchString2, ignoreCase = true) ||
            it.driveName.replace(" ", "").equals(searchString1.replace(" ", ""), ignoreCase = true)
        }

        return match?.offset
    }

    private suspend fun getOffsets(): List<DriveOffset> = mutex.withLock {
        if (inMemoryCache != null) {
            return inMemoryCache!!
        }

        val diskCache = loadFromDisk()
        if (diskCache != null) {
            inMemoryCache = diskCache
            return diskCache
        }

        val fetched = fetchAndParseOffsets()
        if (fetched.isNotEmpty()) {
            saveToDisk(fetched)
            inMemoryCache = fetched
        }
        return fetched
    }

    private suspend fun fetchAndParseOffsets(): List<DriveOffset> = withContext(Dispatchers.IO) {
        try {
            val response = client.get("http://www.accuraterip.com/driveoffsets.htm")
            if (response.status.value == 200) {
                val html = response.bodyAsText()
                val doc = Jsoup.parse(html)
                val rows = doc.select("table tr")

                val parsedOffsets = mutableListOf<DriveOffset>()

                for (row in rows) {
                    val cols = row.select("td")
                    if (cols.size >= 2) {
                        val driveName = cols[0].text().trim()
                        val offsetString = cols[1].text().trim()

                        // Ignore header rows
                        if (driveName.equals("CD Drive", ignoreCase = true) || offsetString.equals("Correction Offset", ignoreCase = true)) {
                            continue
                        }

                        val offsetValue = offsetString.replace("+", "").toIntOrNull()
                        if (offsetValue != null) {
                            parsedOffsets.add(DriveOffset(driveName, offsetValue))
                        } else if (offsetString.equals("[Purged]", ignoreCase = true)) {
                            // Assign an offset of 0 for purged drives so they are still identified
                            parsedOffsets.add(DriveOffset(driveName, 0))
                        }
                    }
                }
                return@withContext parsedOffsets
            }
        } catch (e: Exception) {
            Log.e("DriveOffsetService", "Error fetching offsets: ${e.message}")
        }
        return@withContext emptyList()
    }

    private suspend fun loadFromDisk(): List<DriveOffset>? = withContext(Dispatchers.IO) {
        if (cacheFile.exists()) {
            val lastModified = cacheFile.lastModified()
            val ageMillis = System.currentTimeMillis() - lastModified
            val sevenDaysMillis = 7L * 24 * 60 * 60 * 1000

            if (ageMillis < sevenDaysMillis) {
                try {
                    val json = cacheFile.readText()
                    return@withContext Json.decodeFromString<List<DriveOffset>>(json)
                } catch (e: Exception) {
                    Log.e("DriveOffsetService", "Error reading disk cache: ${e.message}")
                }
            }
        }
        return@withContext null
    }

    private suspend fun saveToDisk(offsets: List<DriveOffset>) = withContext(Dispatchers.IO) {
        try {
            val json = Json.encodeToString(offsets)
            cacheFile.writeText(json)
        } catch (e: Exception) {
            Log.e("DriveOffsetService", "Error writing disk cache: ${e.message}")
        }
    }
}
