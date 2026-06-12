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
            val listeningStats = parseListeningStats(json.optJSONObject("listeningStats"))

            HomeListsCache(recentlyPlayed, rediscover, newReleases, listeningStats)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun saveCachedLists(
        recentlyPlayed: List<RecentlyPlayedItem>,
        rediscover: List<Pair<ArtistInfo, AlbumInfo>>,
        newReleases: List<Pair<ArtistInfo, AlbumInfo>>,
        listeningStats: ListeningStats? = null
    ) {
        try {
            val json = JSONObject()
            json.put("recentlyPlayed", serializeRecentlyPlayed(recentlyPlayed))
            json.put("rediscover", serializeAlbumsList(rediscover))
            json.put("newReleases", serializeAlbumsList(newReleases))
            if (listeningStats != null) {
                json.put("listeningStats", serializeListeningStats(listeningStats))
            }

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

    private fun parseListeningStats(json: JSONObject?): ListeningStats? {
        if (json == null) return null
        try {
            val mostListenedArtist = parseTopArtist(json.optJSONObject("mostListenedArtist"))
            val totalTimeListenedMs = json.optLong("totalTimeListenedMs", 0L)
            val topSongsAllTime = parseTopSongs(json.optJSONArray("topSongsAllTime"))
            val topSongsThisMonth = parseTopSongs(json.optJSONArray("topSongsThisMonth"))
            val topSongsThisYear = parseTopSongs(json.optJSONArray("topSongsThisYear"))
            return ListeningStats(mostListenedArtist, totalTimeListenedMs, topSongsAllTime, topSongsThisMonth, topSongsThisYear)
        } catch (e: Exception) {
            return null
        }
    }

    private fun parseTopArtist(json: JSONObject?): TopArtist? {
        if (json == null) return null
        val artistName = json.optString("artistName", "")
        if (artistName.isEmpty()) return null
        val playCount = json.optInt("playCount", 0)
        val artUriStr = json.optString("artUri", null)
        val artUri = if (!artUriStr.isNullOrEmpty() && artUriStr != "null") Uri.parse(artUriStr) else null
        return TopArtist(artistName, playCount, artUri)
    }

    private fun parseTopSongs(array: JSONArray?): List<TopSong> {
        val result = mutableListOf<TopSong>()
        if (array == null) return result
        for (i in 0 until array.length()) {
            val json = array.optJSONObject(i) ?: continue
            val trackTitle = json.optString("trackTitle", "")
            val artistName = json.optString("artistName", "")
            val playCount = json.optInt("playCount", 0)
            val albumId = json.optLong("albumId", -1L)
            val albumTitle = json.optString("albumTitle", "")
            if (trackTitle.isNotEmpty() && artistName.isNotEmpty()) {
                result.add(TopSong(trackTitle, artistName, playCount, albumId, albumTitle))
            }
        }
        return result
    }

    private fun serializeListeningStats(stats: ListeningStats): JSONObject {
        val obj = JSONObject()
        if (stats.mostListenedArtist != null) {
            val artistObj = JSONObject()
            artistObj.put("artistName", stats.mostListenedArtist.artistName)
            artistObj.put("playCount", stats.mostListenedArtist.playCount)
            artistObj.put("artUri", stats.mostListenedArtist.artUri?.toString())
            obj.put("mostListenedArtist", artistObj)
        }
        obj.put("totalTimeListenedMs", stats.totalTimeListenedMs)
        obj.put("topSongsAllTime", serializeTopSongs(stats.topSongsAllTime))
        obj.put("topSongsThisMonth", serializeTopSongs(stats.topSongsThisMonth))
        obj.put("topSongsThisYear", serializeTopSongs(stats.topSongsThisYear))
        return obj
    }

    private fun serializeTopSongs(list: List<TopSong>): JSONArray {
        val array = JSONArray()
        for (song in list) {
            val obj = JSONObject()
            obj.put("trackTitle", song.trackTitle)
            obj.put("artistName", song.artistName)
            obj.put("playCount", song.playCount)
            obj.put("albumId", song.albumId)
            obj.put("albumTitle", song.albumTitle)
            array.put(obj)
        }
        return array
    }
}

data class HomeListsCache(
    val recentlyPlayed: List<RecentlyPlayedItem>,
    val rediscover: List<Pair<ArtistInfo, AlbumInfo>>,
    val newReleases: List<Pair<ArtistInfo, AlbumInfo>>,
    val listeningStats: ListeningStats? = null
)
