package com.tvapp

import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request

class XtreamApi(
    private val baseUrl: String,
    private val username: String,
    private val password: String
) {
    private val client = OkHttpClient()
    private val gson = Gson()

    suspend fun fetchCategories(): List<Category> {
        val liveUrl = buildUrl("get_live_categories")
        val liveJson = request(liveUrl) ?: ""
        val liveCategories = gson.fromJson(liveJson, Array<Category>::class.java)?.toList() ?: emptyList()
        
        val vodUrl = buildUrl("get_vod_categories")
        val vodJson = request(vodUrl) ?: ""
        val vodCategories = gson.fromJson(vodJson, Array<Category>::class.java)?.toList() ?: emptyList()
        
        val seriesUrl = buildUrl("get_series_categories")
        val seriesJson = request(seriesUrl) ?: ""
        val seriesCategories = gson.fromJson(seriesJson, Array<Category>::class.java)?.toList() ?: emptyList()
        
        return (liveCategories + vodCategories + seriesCategories).distinctBy { it.category_id }
    }

    suspend fun fetchStreams(): List<Stream> {
        val liveUrl = buildUrl("get_live_streams")
        val liveJson = request(liveUrl) ?: ""
        val liveStreams = gson.fromJson(liveJson, Array<Stream>::class.java)?.toList() ?: emptyList()
        
        val vodUrl = buildUrl("get_vod_streams")
        val vodJson = request(vodUrl) ?: ""
        val vodStreams = gson.fromJson(vodJson, Array<Stream>::class.java)?.toList() ?: emptyList()
        
        val seriesUrl = buildUrl("get_series")
        val seriesJson = request(seriesUrl) ?: ""
        val seriesStreams = gson.fromJson(seriesJson, Array<Stream>::class.java)?.toList() ?: emptyList()
        
        return liveStreams + vodStreams + seriesStreams
    }

    private fun buildUrl(action: String): String {
        val normalized = baseUrl.trimEnd('/')
        return "${normalized}/player_api.php?username=${username}&password=${password}&action=${action}"
    }

    private fun request(url: String): String? {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return null
            }
            return response.body?.string()
        }
    }
}
