package com.tvapp

import android.util.Xml
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.Locale

class EpgRepository(
    private val client: OkHttpClient = OkHttpClient()
) {
    private val programsByChannel = mutableMapOf<String, MutableList<EpgProgram>>()
    private val channelsByName = mutableMapOf<String, MutableSet<String>>()

    suspend fun load(url: String) {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            val body = response.body ?: return
            InputStreamReader(body.byteStream(), StandardCharsets.UTF_8).use { reader ->
                parse(reader)
            }
        }
    }

    fun currentProgram(streamName: String, nowSeconds: Long = System.currentTimeMillis() / 1000): EpgEntry? {
        val normalized = normalizeName(streamName)
        val channelIds = channelsByName[normalized] ?: return null
        for (channelId in channelIds) {
            val items = programsByChannel[channelId] ?: continue
            val match = items.firstOrNull { nowSeconds in it.startTimestamp..it.endTimestamp }
            if (match != null) {
                return EpgEntry(match.title, match.description, match.startTimestamp, match.endTimestamp)
            }
        }
        return null
    }

    private fun parse(reader: InputStreamReader) {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(reader)

        var currentChannelId: String? = null
        var currentDisplayName: String? = null

        var currentProgChannel: String? = null
        var currentProgStart = 0L
        var currentProgStop = 0L
        var currentProgTitle: String? = null
        var currentProgDesc: String? = null

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "channel" -> {
                            currentChannelId = parser.getAttributeValue(null, "id")?.trim().orEmpty()
                            currentDisplayName = null
                        }
                        "display-name" -> {
                            val nameText = parser.nextText().trim()
                            if (nameText.isNotEmpty()) {
                                currentDisplayName = nameText
                            }
                        }
                        "programme" -> {
                            currentProgChannel = parser.getAttributeValue(null, "channel")?.trim()
                            currentProgStart = parser.getAttributeValue(null, "start_timestamp")?.toLongOrNull() ?: 0L
                            currentProgStop = parser.getAttributeValue(null, "stop_timestamp")?.toLongOrNull() ?: 0L
                            currentProgTitle = null
                            currentProgDesc = null
                        }
                        "title" -> {
                            currentProgTitle = parser.nextText().trim()
                        }
                        "desc" -> {
                            currentProgDesc = parser.nextText().trim()
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "channel" -> {
                            val id = currentChannelId?.takeIf { it.isNotBlank() }
                            val display = currentDisplayName?.takeIf { it.isNotBlank() }
                            if (id != null && display != null) {
                                val norm = normalizeName(display)
                                val set = channelsByName.getOrPut(norm) { mutableSetOf() }
                                set.add(id)
                                val normId = normalizeName(id)
                                val idSet = channelsByName.getOrPut(normId) { mutableSetOf() }
                                idSet.add(id)
                            }
                            currentChannelId = null
                            currentDisplayName = null
                        }
                        "programme" -> {
                            val channel = currentProgChannel
                            val title = currentProgTitle
                            if (channel != null && title != null && currentProgStart > 0 && currentProgStop > 0) {
                                val list = programsByChannel.getOrPut(channel) { mutableListOf() }
                                list.add(EpgProgram(channel, title, currentProgDesc, currentProgStart, currentProgStop))
                            }
                            currentProgChannel = null
                            currentProgTitle = null
                            currentProgDesc = null
                            currentProgStart = 0L
                            currentProgStop = 0L
                        }
                    }
                }
            }
            eventType = parser.next()
        }

        // Sort each channel's programs by start time for quick lookup
        programsByChannel.values.forEach { list ->
            list.sortBy { it.startTimestamp }
        }
    }

    private fun normalizeName(name: String): String {
        val lower = name.lowercase(Locale.getDefault())
        val noBrackets = lower.replace("\\[.*?\\]".toRegex(), " ")
        val decomposed = Normalizer.normalize(noBrackets, Normalizer.Form.NFD)
        val stripped = decomposed.replace("[^a-z0-9 ]".toRegex(), " ")
        return stripped.trim().replace("\\s+".toRegex(), " ")
    }
}
