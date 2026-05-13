package com.bitperfect.core.utils

import android.content.Context
import android.content.SharedPreferences

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("bitperfect_settings", Context.MODE_PRIVATE)

    var outputFolderUri: String?
        get() = prefs.getString("outputFolderUri", null)
        set(value) = prefs.edit().putString("outputFolderUri", value).apply()

    var embedLyrics: Boolean
        get() = prefs.getBoolean("embedLyrics", true)
        set(value) = prefs.edit().putBoolean("embedLyrics", value).apply()
}
