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
        val url = buildUrl("get_live_categories")
        val json = request(url) ?: return emptyList()
        return gson.fromJson(json, Array<Category>::class.java)?.toList() ?: emptyList()
    }

    suspend fun fetchStreams(): List<Stream> {
        val url = buildUrl("get_live_streams")
        val json = request(url) ?: return emptyList()
        return gson.fromJson(json, Array<Stream>::class.java)?.toList() ?: emptyList()
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
