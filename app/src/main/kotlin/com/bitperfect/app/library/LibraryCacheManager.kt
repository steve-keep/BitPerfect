package com.bitperfect.app.library

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class LibraryCacheManager(private val context: Context) {

    private val cacheFile = File(context.cacheDir, "home_lists_cache.json")

    fun loadCachedLists(): HomeListsCache? {
        if (!cacheFile.exists()) return null

        return try {
            val jsonString = cacheFile.readText(Charsets.UTF_8)
            val json = JSONObject(jsonString)

            val recentlyPlayed = parseRecentlyPlayed(json.optJSONArray("recentlyPlayed"))
            val rediscover = parseAlbumsList(json.optJSONArray("rediscover"))
            val newReleases = parseAlbumsList(json.optJSONArray("newReleases"))

            HomeListsCache(recentlyPlayed, rediscover, newReleases)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun saveCachedLists(
        recentlyPlayed: List<RecentlyPlayedItem>,
        rediscover: List<Pair<ArtistInfo, AlbumInfo>>,
        newReleases: List<Pair<ArtistInfo, AlbumInfo>>
    ) {
        try {
            val json = JSONObject()
            json.put("recentlyPlayed", serializeRecentlyPlayed(recentlyPlayed))
            json.put("rediscover", serializeAlbumsList(rediscover))
            json.put("newReleases", serializeAlbumsList(newReleases))

            cacheFile.writeText(json.toString(), Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun parseRecentlyPlayed(array: JSONArray?): List<RecentlyPlayedItem> {
        val result = mutableListOf<RecentlyPlayedItem>()
        if (array == null) return result

        for (i in 0 until array.length()) {
            val itemJson = array.optJSONObject(i) ?: continue
            val type = itemJson.optString("type")
            if (type == "ArtistGroupItem") {
                val name = itemJson.optString("artistName")
                val thumb = itemJson.optString("thumbnailUrl")
                if (name.isNotEmpty() && thumb.isNotEmpty()) {
                    result.add(RecentlyPlayedItem.ArtistGroupItem(name, thumb))
                }
            } else if (type == "AlbumItem") {
                val albumJson = itemJson.optJSONObject("album")
                if (albumJson != null) {
                    val album = parseAlbumInfo(albumJson)
                    if (album != null) result.add(RecentlyPlayedItem.AlbumItem(album))
                }
            }
        }
        return result
    }

    private fun serializeRecentlyPlayed(list: List<RecentlyPlayedItem>): JSONArray {
        val array = JSONArray()
        for (item in list) {
            val obj = JSONObject()
            when (item) {
                is RecentlyPlayedItem.ArtistGroupItem -> {
                    obj.put("type", "ArtistGroupItem")
                    obj.put("artistName", item.artistName)
                    obj.put("thumbnailUrl", item.thumbnailUrl)
                }
                is RecentlyPlayedItem.AlbumItem -> {
                    obj.put("type", "AlbumItem")
                    obj.put("album", serializeAlbumInfo(item.album))
                }
            }
            array.put(obj)
        }
        return array
    }

    private fun parseAlbumsList(array: JSONArray?): List<Pair<ArtistInfo, AlbumInfo>> {
        val result = mutableListOf<Pair<ArtistInfo, AlbumInfo>>()
        if (array == null) return result

        for (i in 0 until array.length()) {
            val itemJson = array.optJSONObject(i) ?: continue
            val artistJson = itemJson.optJSONObject("artist")
            val albumJson = itemJson.optJSONObject("album")

            if (artistJson != null && albumJson != null) {
                val artist = parseArtistInfo(artistJson)
                val album = parseAlbumInfo(albumJson)
                if (artist != null && album != null) {
                    result.add(Pair(artist, album))
                }
            }
        }
        return result
    }

    private fun serializeAlbumsList(list: List<Pair<ArtistInfo, AlbumInfo>>): JSONArray {
        val array = JSONArray()
        for (pair in list) {
            val obj = JSONObject()
            obj.put("artist", serializeArtistInfo(pair.first))
            obj.put("album", serializeAlbumInfo(pair.second))
            array.put(obj)
        }
        return array
    }

    private fun parseAlbumInfo(json: JSONObject): AlbumInfo? {
        try {
            val id = json.getLong("id")
            val title = json.getString("title")
            val artUriStr = json.optString("artUri", null)
            val artUri = if (!artUriStr.isNullOrEmpty() && artUriStr != "null") Uri.parse(artUriStr) else null
            return AlbumInfo(id, title, artUri)
        } catch (e: Exception) {
            return null
        }
    }

    private fun serializeAlbumInfo(album: AlbumInfo): JSONObject {
        val obj = JSONObject()
        obj.put("id", album.id)
        obj.put("title", album.title)
        obj.put("artUri", album.artUri?.toString())
        return obj
    }

    private fun parseArtistInfo(json: JSONObject): ArtistInfo? {
        try {
            val id = json.getLong("id")
            val name = json.getString("name")
            return ArtistInfo(id, name, emptyList()) // we don't serialize full albums list for the pair to save space
        } catch (e: Exception) {
            return null
        }
    }

    private fun serializeArtistInfo(artist: ArtistInfo): JSONObject {
        val obj = JSONObject()
        obj.put("id", artist.id)
        obj.put("name", artist.name)
        return obj
    }
}

data class HomeListsCache(
    val recentlyPlayed: List<RecentlyPlayedItem>,
    val rediscover: List<Pair<ArtistInfo, AlbumInfo>>,
    val newReleases: List<Pair<ArtistInfo, AlbumInfo>>
)
