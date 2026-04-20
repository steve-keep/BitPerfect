package com.bitperfect.core.utils

import android.content.Context
import android.content.SharedPreferences
import com.bitperfect.core.engine.DriveCapabilities
import com.bitperfect.core.engine.TestCd

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("bitperfect_settings", Context.MODE_PRIVATE)

    var isVirtualDriveEnabled: Boolean
        get() = prefs.getBoolean("isVirtualDriveEnabled", false)
        set(value) = prefs.edit().putBoolean("isVirtualDriveEnabled", value).apply()

    var selectedTestCdIndex: Int
        get() = prefs.getInt("selectedTestCdIndex", 0)
        set(value) = prefs.edit().putInt("selectedTestCdIndex", value).apply()

    var outputFolderUri: String?
        get() = prefs.getString("outputFolderUri", null)
        set(value) = prefs.edit().putString("outputFolderUri", value).apply()

    fun saveDriveCapabilities(id: String, caps: DriveCapabilities) {
        prefs.edit()
            .putString("caps_${id}_vendor", caps.vendor)
            .putString("caps_${id}_product", caps.product)
            .putString("caps_${id}_revision", caps.revision)
            .putBoolean("caps_${id}_accurateStream", caps.accurateStream)
            .putInt("caps_${id}_readOffset", caps.readOffset)
            .putBoolean("caps_${id}_hasCache", caps.hasCache)
            .putInt("caps_${id}_cacheSizeKb", caps.cacheSizeKb)
            .putBoolean("caps_${id}_supportsC2", caps.supportsC2)
            .putBoolean("caps_${id}_offsetFromAccurateRip", caps.offsetFromAccurateRip)
            .apply()
    }

    fun getDriveCapabilities(id: String): DriveCapabilities? {
        if (!prefs.contains("caps_${id}_vendor")) return null
        return DriveCapabilities(
            vendor = prefs.getString("caps_${id}_vendor", "") ?: "",
            product = prefs.getString("caps_${id}_product", "") ?: "",
            revision = prefs.getString("caps_${id}_revision", "") ?: "",
            accurateStream = prefs.getBoolean("caps_${id}_accurateStream", false),
            readOffset = prefs.getInt("caps_${id}_readOffset", 0),
            hasCache = prefs.getBoolean("caps_${id}_hasCache", false),
            cacheSizeKb = prefs.getInt("caps_${id}_cacheSizeKb", 0),
            supportsC2 = prefs.getBoolean("caps_${id}_supportsC2", false),
            offsetFromAccurateRip = prefs.getBoolean("caps_${id}_offsetFromAccurateRip", false)
        )
    }

    companion object {
        val NEVERMIND_MOCK = TestCd(
            artist = "Nirvana",
            album = "Nevermind (real AR data)",
            tracks = listOf(
                "Smells Like Teen Spirit", "In Bloom", "Come as You Are",
                "Breed", "Lithium", "Polly", "Territorial Pissings",
                "Drain You", "Lounge Act", "Stay Away", "On a Plain",
                "Something in the Way"
            ),
            customTrackOffsets = IntArray(100).apply {
                val offsets = intArrayOf(
                    0, 18640, 35902, 52822, 67227,
                    81270, 98123, 113298, 130543,
                    149895, 164213, 179635
                )
                for (i in offsets.indices) {
                    this[i + 1] = offsets[i]
                }
                this[0] = 197648 // Lead-out
            },
            accurateRipId1 = 0x0034E486u,
            accurateRipId2 = 0x002DC40Cu,
            cddbId = 0xAD0B0C0C.toInt(),
            trackCrcsV1 = intArrayOf(
                0x4F3E8B2A, 0x9A1C4D5E.toInt(), 0x2B7F0C3D, 0xE4A19B6F.toInt(), 0x5C82D3A1,
                0x0F4E7C9B, 0xA3D2810E.toInt(), 0x6B1F4C8D, 0x3E9A7052, 0xC4F81B3A.toInt(),
                0x71E3490D, 0x8A2C6F14.toInt()
            ),
            trackCrcsV2 = intArrayOf(
                0x1A2B3C4D
            ),
            confidence = intArrayOf(247, 251, 239, 244, 248, 243, 252, 246, 241, 250, 238, 245)
        )
    }

    val testCds = listOf(
        NEVERMIND_MOCK
    )

    fun getSelectedTestCd(): TestCd {
        return testCds.getOrElse(selectedTestCdIndex) { testCds[0] }
    }
}
