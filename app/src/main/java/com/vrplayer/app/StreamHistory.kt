package com.vrplayer.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class StreamHistory(context: Context) {
    private val prefs = context.getSharedPreferences("stream_history", Context.MODE_PRIVATE)

    fun list(): List<StreamRequest> {
        val raw = prefs.getString(KEY, "[]").orEmpty()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = ArrayList<StreamRequest>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = o.optString("url").trim()
            if (url.isEmpty()) continue
            out += StreamRequest(
                url = url,
                referer = o.optString("referer"),
                userAgent = o.optString("ua"),
                cookie = o.optString("cookie")
            )
        }
        return out
    }

    fun save(item: StreamRequest) {
        val items = list().filterNot { it.url == item.url }.toMutableList()
        items.add(0, item)
        val arr = JSONArray()
        for (it in items.take(8)) {
            arr.put(
                JSONObject()
                    .put("url", it.url)
                    .put("referer", it.referer)
                    .put("ua", it.userAgent)
                    .put("cookie", it.cookie)
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    companion object {
        private const val KEY = "items"
    }
}
