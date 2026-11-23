package com.tx24.spicyplayer.network.service

import com.tx24.spicyplayer.network.model.SongLyricsNetwork
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tx24.spicyplayer.network.data.awaitResponse
import com.tx24.spicyplayer.network.model.LrclibSearchEntry
import java.io.IOException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request


interface LyricsService {

    companion object {
        const val BASE_URL = "https://lrclib.net/"
    }

    suspend fun getSongLyrics(
        artistName: String,
        trackName: String,
        albumName: String?,
        durationSeconds: Int?,
    ): SongLyricsNetwork

    suspend fun searchSongLyrics(
        artistName: String,
        trackName: String,
    ): List<LrclibSearchEntry>

}

class LyricsServiceException(val status: Int, val retryAfter: String?) : IOException("LRCLIB HTTP $status")

class HttpLyricsService(private val client: OkHttpClient, private val gson: Gson) : LyricsService {
    override suspend fun getSongLyrics(artistName: String, trackName: String, albumName: String?, durationSeconds: Int?) =
        gson.fromJson(get("get", artistName, trackName, albumName, durationSeconds), SongLyricsNetwork::class.java)
            ?: throw IOException("Empty LRCLIB response")

    override suspend fun searchSongLyrics(artistName: String, trackName: String): List<LrclibSearchEntry> =
        gson.fromJson(get("search", artistName, trackName, null, null), object : TypeToken<List<LrclibSearchEntry>>() {})
            ?: emptyList()

    private suspend fun get(path: String, artist: String, title: String, album: String?, duration: Int?): String {
        val url = LyricsService.BASE_URL.toHttpUrl().newBuilder()
            .addPathSegment("api").addPathSegment(path)
            .addQueryParameter("artist_name", artist).addQueryParameter("track_name", title)
            .apply {
                if (album != null) addQueryParameter("album_name", album)
                if (duration != null) addQueryParameter("duration", duration.toString())
            }.build()
        val request = Request.Builder().url(url)
            .header("User-Agent", "SpicyLyricsWindows/0.1 (+https://github.com/spicylyrics/mobile)").build()
        return client.newCall(request).awaitResponse().use { response ->
            if (!response.isSuccessful) throw LyricsServiceException(response.code, response.header("Retry-After"))
            response.body?.string() ?: throw IOException("Empty LRCLIB response")
        }
    }
}
