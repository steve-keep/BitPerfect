package com.bitperfect.core.utils

import android.content.Context
import android.content.SharedPreferences

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("bitperfect_settings", Context.MODE_PRIVATE)

    var outputFolderUri: String?
        get() = prefs.getString("outputFolderUri", null)
        set(value) = prefs.edit().putString("outputFolderUri", value).apply()

    var recentlyPlayedAlbumIds: List<Long>
        get() = prefs.getString("recentlyPlayedAlbumIds", "")
            ?.split(",")
            ?.filter { it.isNotBlank() }
            ?.mapNotNull { it.toLongOrNull() }
            ?: emptyList()
        set(value) = prefs.edit().putString("recentlyPlayedAlbumIds", value.joinToString(",")).apply()

    fun addRecentlyPlayedAlbum(albumId: Long) {
        if (albumId <= 0) return
        val currentList = recentlyPlayedAlbumIds.toMutableList()
        currentList.remove(albumId)
        currentList.add(0, albumId)
        recentlyPlayedAlbumIds = currentList.take(10)
    }
}
