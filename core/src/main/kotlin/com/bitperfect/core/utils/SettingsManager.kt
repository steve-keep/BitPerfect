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
        val ADELE_21_MOCK = TestCd(
            artist = "Adele",
            album = "21",
            tracks = listOf(
                "Rolling in the Deep", "Rumour Has It", "Turning Tables",
                "Don't You Remember", "Set Fire to the Rain", "He Won't Go",
                "Take It All", "I'll Be Waiting", "One and Only",
                "Lovesong", "Someone Like You"
            ),
            customTrackOffsets = IntArray(100).apply {
                val offsets = intArrayOf(
                    0, 17122, 33867, 52627, 70867,
                    88994, 109840, 126956, 145080,
                    171192, 194925
                )
                for (i in offsets.indices) {
                    this[i + 1] = offsets[i]
                }
                this[0] = 216301 // Lead-out
            },
            accurateRipId1 = 0x0012BBFBu,
            accurateRipId2 = 0x00A47020u,
            cddbId = 0x930B440B.toInt(),
            trackCrcsV1 = intArrayOf(
                0xD152B2F5.toInt(), 0x58B6EE79.toInt(), 0x53B73AD7.toInt(), 0x09FA80A5.toInt(),
                0x9694C5CD.toInt(), 0x87852E00.toInt(), 0xF83CC2F8.toInt(), 0x7D75FC72.toInt(),
                0x0C6D5982.toInt(), 0xCF474EB4.toInt(), 0x9AB9365E.toInt()
            ),
            trackCrcsV2 = intArrayOf(
                0x2B03CE1A.toInt(), 0xFA1E8750.toInt(), 0xA8E44103.toInt(), 0xF2329CD7.toInt(),
                0xDBD15AB6.toInt(), 0x67926F74.toInt(), 0xFA9DB4E3.toInt(), 0x7FE71747.toInt(),
                0xCC2EB4D5.toInt(), 0x6B952B9E.toInt(), 0xA99B1EF0.toInt()
            ),
            confidence = intArrayOf(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1)
        )
    }

    val testCds = listOf(
        ADELE_21_MOCK
    )

    fun getSelectedTestCd(): TestCd {
        return testCds.getOrElse(selectedTestCdIndex) { testCds[0] }
    }
}
