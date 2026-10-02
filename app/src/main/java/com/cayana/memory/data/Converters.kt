package com.cayana.memory.data

import com.cayana.memory.model.EventCandidate
import com.cayana.memory.model.EventConfidence
import org.json.JSONArray
import org.json.JSONObject

object Converters {

    fun serializeMetadata(metadata: Map<String, String>): String {
        val json = JSONObject()
        metadata.forEach { (key, value) -> json.put(key, value) }
        return json.toString()
    }

    fun parseMetadata(jsonStr: String): Map<String, String> {
        if (jsonStr.isBlank()) return emptyMap()
        return runCatching {
            val json = JSONObject(jsonStr)
            val map = mutableMapOf<String, String>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                map[key] = json.optString(key, "")
            }
            map
        }.getOrDefault(emptyMap())
    }

    fun serializeEntities(entities: List<String>): String {
        val array = JSONArray()
        entities.forEach { array.put(it) }
        return array.toString()
    }

    fun parseEntities(jsonStr: String): List<String> {
        if (jsonStr.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(jsonStr)
            val list = mutableListOf<String>()
            for (i in 0 until array.length()) {
                list.add(array.getString(i))
            }
            list
        }.getOrDefault(emptyList())
    }

    fun serializeCandidates(candidates: List<EventCandidate>): String {
        val array = JSONArray()
        candidates.forEach { candidate ->
            val obj = JSONObject()
            obj.put("title", candidate.title)
            obj.put("start", candidate.startTimestamp)
            candidate.endTimestamp?.let { obj.put("end", it) }
            candidate.location?.let { obj.put("loc", it) }
            obj.put("conf", candidate.confidence.name)
            candidate.rawMatchedSnippet?.let { obj.put("snippet", it) }
            array.put(obj)
        }
        return array.toString()
    }

    fun parseCandidates(jsonStr: String): List<EventCandidate> {
        if (jsonStr.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(jsonStr)
            val list = mutableListOf<EventCandidate>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val title = obj.getString("title")
                val start = obj.getLong("start")
                val end = if (obj.has("end")) obj.getLong("end") else null
                val loc = if (obj.has("loc")) obj.getString("loc") else null
                val conf = runCatching {
                    EventConfidence.valueOf(obj.getString("conf"))
                }.getOrDefault(EventConfidence.LOW)
                val snippet = if (obj.has("snippet")) obj.getString("snippet") else null

                list.add(
                    EventCandidate(
                        title = title,
                        startTimestamp = start,
                        endTimestamp = end,
                        location = loc,
                        confidence = conf,
                        rawMatchedSnippet = snippet
                    )
                )
            }
            list
        }.getOrDefault(emptyList())
    }
}
