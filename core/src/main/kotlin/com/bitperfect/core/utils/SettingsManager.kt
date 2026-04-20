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

    val testCds = listOf(
        TestCd(
            artist = "Nirvana",
            album = "Smells Like Teen Spirit (Promo)",
            tracks = listOf("Smells Like Teen Spirit"),
            customTrackOffsets = IntArray(100).apply {
                this[1] = 0
                this[0] = 22500 // ~5 minutes
                this[2] = 22500 // End of track 1 if lastTrack > 1
            }
        ),
        TestCd(
            artist = "Pink Floyd",
            album = "The Dark Side of the Moon",
            tracks = listOf(
                "Speak to Me", "Breathe (In the Air)", "On the Run", "Time",
                "The Great Gig in the Sky", "Money", "Us and Them",
                "Any Colour You Like", "Brain Damage", "Eclipse"
            )
        ),
        TestCd(
            artist = "Michael Jackson",
            album = "Thriller",
            tracks = listOf(
                "Wanna Be Startin' Somethin'", "Baby Be Mine", "The Girl Is Mine",
                "Thriller", "Beat It", "Billie Jean", "Human Nature",
                "P.Y.T. (Pretty Young Thing)", "The Lady in My Life"
            )
        ),
        TestCd(
            artist = "Fleetwood Mac",
            album = "Rumours",
            tracks = listOf(
                "Second Hand News", "Dreams", "Never Going Back Again",
                "Don't Stop", "Go Your Own Way", "Songbird", "The Chain",
                "You Make Loving Fun", "I Don't Want to Know", "Oh Daddy", "Gold Dust Woman"
            )
        ),
        TestCd(
            artist = "Nirvana",
            album = "Nevermind",
            tracks = listOf(
                "Smells Like Teen Spirit", "In Bloom", "Come as You Are",
                "Breed", "Lithium", "Polly", "Territorial Pissings",
                "Drain You", "Lounge Act", "Stay Away", "On a Plain",
                "Something in the Way"
            )
        ),
        TestCd(
            artist = "Daft Punk",
            album = "Random Access Memories",
            tracks = listOf(
                "Give Life Back to Music", "The Game of Love", "Giorgio by Moroder",
                "Within", "Instant Crush", "Lose Yourself to Dance", "Touch",
                "Get Lucky", "Beyond", "Motherboard", "Fragments of Time",
                "Doin' It Right", "Contact"
            )
        )
    )

    fun getSelectedTestCd(): TestCd {
        return testCds.getOrElse(selectedTestCdIndex) { testCds[0] }
    }
}
