package com.vrplayer.app

import android.content.Intent

data class StreamRequest(
    val url: String,
    val referer: String = "",
    val userAgent: String = "",
    val cookie: String = ""
) {
    fun put(intent: Intent) {
        intent.putExtra(EXTRA_STREAM_URL, url)
        intent.putExtra(EXTRA_STREAM_REFERER, referer)
        intent.putExtra(EXTRA_STREAM_UA, userAgent)
        intent.putExtra(EXTRA_STREAM_COOKIE, cookie)
    }

    companion object {
        const val EXTRA_STREAM_URL = "extra_stream_url"
        const val EXTRA_STREAM_REFERER = "extra_stream_referer"
        const val EXTRA_STREAM_UA = "extra_stream_ua"
        const val EXTRA_STREAM_COOKIE = "extra_stream_cookie"

        fun from(intent: Intent): StreamRequest? {
            val url = intent.getStringExtra(EXTRA_STREAM_URL).orEmpty().trim()
            if (url.isEmpty()) return null
            return StreamRequest(
                url = url,
                referer = intent.getStringExtra(EXTRA_STREAM_REFERER).orEmpty(),
                userAgent = intent.getStringExtra(EXTRA_STREAM_UA).orEmpty(),
                cookie = intent.getStringExtra(EXTRA_STREAM_COOKIE).orEmpty()
            )
        }
    }
}

object StreamParse {
    private val urlRegex = Regex(
        """https?://[^\s"'<>\\]+""",
        RegexOption.IGNORE_CASE
    )
    private val headerRegex = Regex(
        """(?im)^\s*(referer|origin|user-agent|cookie)\s*[:=]\s*(.+?)\s*$"""
    )

    fun parse(raw: String): StreamRequest? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val headers = linkedMapOf<String, String>()
        for (m in headerRegex.findAll(text)) {
            headers[m.groupValues[1].lowercase()] = m.groupValues[2].trim().trim('"', '\'')
        }
        jsonField(text, "referer")?.let { headers.putIfAbsent("referer", it) }
        jsonField(text, "origin")?.let { headers.putIfAbsent("origin", it) }
        jsonField(text, "user-agent")?.let { headers.putIfAbsent("user-agent", it) }
        jsonField(text, "userAgent")?.let { headers.putIfAbsent("user-agent", it) }
        jsonField(text, "cookie")?.let { headers.putIfAbsent("cookie", it) }
        val urls = ArrayList<String>()
        jsonField(text, "url")?.let { urls += it }
        jsonField(text, "uri")?.let { urls += it }
        for (m in urlRegex.findAll(text)) {
            val u = cleanUrl(m.value)
            if (u.isNotEmpty()) urls += u
        }
        if (urls.isEmpty()) return null
        val video = urls.firstOrNull { isVideoLike(it) } ?: urls.first()
        val page = urls.firstOrNull { !isVideoLike(it) }.orEmpty()
        val referer = headers["referer"] ?: headers["origin"] ?: page
        return StreamRequest(
            url = video,
            referer = referer,
            userAgent = headers["user-agent"].orEmpty(),
            cookie = headers["cookie"].orEmpty()
        )
    }

    private fun isVideoLike(url: String): Boolean {
        val u = url.lowercase()
        return u.contains(".m3u8") ||
            u.contains(".mp4") ||
            u.contains(".mkv") ||
            u.contains(".webm") ||
            u.contains(".ts") ||
            u.contains(".m4s") ||
            u.contains(".mpd") ||
            u.contains(".flv") ||
            u.contains("mime=video")
    }

    private fun cleanUrl(value: String): String {
        return value.trim().trimEnd('.', ',', ';', ')', ']', '"', '\'')
    }

    private fun jsonField(text: String, key: String): String? {
        val m = Regex("\"$key\"\\s*:\\s*\"([^\"]+)\"").find(text) ?: return null
        return m.groupValues[1].trim()
    }
}
