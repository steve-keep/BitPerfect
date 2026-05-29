package com.bitperfect.app.ripping.capability

import android.content.Context
import com.bitperfect.core.utils.AppLogger
import org.json.JSONObject
import java.io.File

interface DriveProfileRepository {
    fun getProfile(driveId: String): DriveProfile?
    fun saveProfile(profile: DriveProfile)
}

class DefaultDriveProfileRepository(private val context: Context) : DriveProfileRepository {

    private val directory: File by lazy {
        File(context.filesDir, "drive_profiles").apply {
            if (!exists()) {
                mkdirs()
            }
        }
    }

    override fun getProfile(driveId: String): DriveProfile? {
        val file = File(directory, "$driveId.json")
        if (!file.exists()) return null

        return try {
            val jsonText = file.readText()
            val json = JSONObject(jsonText)

            val profile = DriveProfile(
                vendor = json.getString("vendor"),
                model = json.getString("model"),
                firmware = if (json.has("firmware") && !json.isNull("firmware")) json.getString("firmware") else null,
                preferredReadSize = json.getInt("preferredReadSize"),
                maxReliableReadSize = json.getInt("maxReliableReadSize"),
                supportsStreaming = json.getBoolean("supportsStreaming"),
                likelyCachesAudio = json.getBoolean("likelyCachesAudio"),
                stableLargeReads = json.getBoolean("stableLargeReads"),
                unstableSeeking = json.getBoolean("unstableSeeking"),
                retrySuccessRate = json.getDouble("retrySuccessRate").toFloat(),
                overlapInstabilityRate = json.getDouble("overlapInstabilityRate").toFloat(),
                profileVersion = json.optInt("profileVersion", 1)
            )

            AppLogger.d(TAG, "Loaded existing drive profile\nprofileVersion=${profile.profileVersion}\ndriveId=$driveId")
            profile
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to load profile for $driveId", e)
            null
        }
    }

    override fun saveProfile(profile: DriveProfile) {
        val driveId = listOfNotNull(profile.vendor, profile.model, profile.firmware).joinToString("_").lowercase().replace(" ", "")

        val file = File(directory, "$driveId.json")
        try {
            val json = JSONObject().apply {
                put("vendor", profile.vendor)
                put("model", profile.model)
                put("firmware", profile.firmware)
                put("preferredReadSize", profile.preferredReadSize)
                put("maxReliableReadSize", profile.maxReliableReadSize)
                put("supportsStreaming", profile.supportsStreaming)
                put("likelyCachesAudio", profile.likelyCachesAudio)
                put("stableLargeReads", profile.stableLargeReads)
                put("unstableSeeking", profile.unstableSeeking)
                // Use Double for NaN support in JSON if needed, or simply standard float value
                put("retrySuccessRate", profile.retrySuccessRate.toDouble())
                put("overlapInstabilityRate", profile.overlapInstabilityRate.toDouble())
                put("profileVersion", profile.profileVersion)
            }

            file.writeText(json.toString(2))
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to save profile for $driveId", e)
        }
    }

    companion object {
        private const val TAG = "DriveProfileRepository"
    }
}
