package com.tvapp

import com.google.gson.Gson
import com.google.gson.JsonObject
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
        
        return liveStreams + vodStreams
    }

    suspend fun fetchSeries(): List<SeriesInfo> {
        val seriesUrl = buildUrl("get_series")
        val seriesJson = request(seriesUrl) ?: return emptyList()
        
        val seriesArray = gson.fromJson(seriesJson, Array<JsonObject>::class.java) ?: return emptyList()
        return seriesArray.mapNotNull { seriesObj ->
            val seriesId = seriesObj.get("series_id")?.asInt ?: return@mapNotNull null
            val name = seriesObj.get("name")?.asString ?: ""
            val categoryId = seriesObj.get("category_id")?.asString ?: ""
            
            // Initially return series without seasons - we'll fetch them on demand
            SeriesInfo(seriesId, name, categoryId, emptyMap())
        }
    }

    suspend fun fetchSeriesDetails(seriesId: Int): SeriesInfo? {
        val seriesUrl = buildUrl("get_series_info") + "&series_id=${seriesId}"
        val seriesJson = request(seriesUrl) ?: return null
        
        try {
            val seriesObj = gson.fromJson(seriesJson, JsonObject::class.java)
            val info = seriesObj.getAsJsonObject("info")
            val name = info?.get("name")?.asString ?: ""
            val categoryId = info?.get("category_id")?.asString ?: ""
            
            val seasons = mutableMapOf<Int, List<Episode>>()
            val episodesObj = seriesObj.getAsJsonObject("episodes")
            
            episodesObj?.entrySet()?.forEach { (seasonKey, seasonValue) ->
                val seasonNum = seasonKey.toIntOrNull() ?: return@forEach
                val episodesArray = seasonValue.asJsonArray
                val episodes = mutableListOf<Episode>()
                
                episodesArray.forEach { episodeElement ->
                    val episodeObj = episodeElement.asJsonObject
                    // Try to get id as string first, then as int
                    val id = episodeObj.get("id")?.let { 
                        if (it.isJsonPrimitive && it.asJsonPrimitive.isNumber) {
                            it.asInt.toString()
                        } else {
                            it.asString
                        }
                    } ?: return@forEach
                    val episodeNum = episodeObj.get("episode_num")?.asInt ?: return@forEach
                    val episodeTitle = episodeObj.get("title")?.asString ?: "Episode $episodeNum"
                    val containerExtension = episodeObj.get("container_extension")?.asString
                    val infoObj = runCatching { episodeObj.getAsJsonObject("info") }.getOrNull()
                    val description = runCatching { infoObj?.get("plot")?.asString }.getOrNull()
                        ?: runCatching { infoObj?.get("description")?.asString }.getOrNull()
                        ?: runCatching { episodeObj.get("plot")?.asString }.getOrNull()
                        ?: runCatching { episodeObj.get("description")?.asString }.getOrNull()

                    episodes.add(Episode(id, episodeNum, episodeTitle, seasonNum, containerExtension, description))
                }
                
                if (episodes.isNotEmpty()) {
                    seasons[seasonNum] = episodes.sortedBy { it.episode_num }
                }
            }
            
            return SeriesInfo(seriesId, name, categoryId, seasons)
        } catch (e: Exception) {
            return null
        }
    }

    suspend fun fetchShortEpg(streamId: Int, limit: Int = 1): List<EpgEntry> {
        val epgUrl = buildUrl("get_short_epg") + "&stream_id=${streamId}&limit=${limit}"
        val epgJson = request(epgUrl) ?: return emptyList()

        return try {
            val epgObj = gson.fromJson(epgJson, JsonObject::class.java)
            val listings = epgObj?.getAsJsonArray("epg_listings") ?: return emptyList()

            listings.mapNotNull { element ->
                val item = element.asJsonObject
                val title = item.get("title")?.asString ?: return@mapNotNull null
                val description = item.get("description")?.asString
                val startTs = runCatching { item.get("start_timestamp")?.asLong }.getOrNull() ?: 0L
                val endTs = runCatching { item.get("stop_timestamp")?.asLong }.getOrNull() ?: 0L
                EpgEntry(title, description, startTs, endTs)
            }
        } catch (e: Exception) {
            emptyList()
        }
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
