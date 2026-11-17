package com.mts.tikmod.utils

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Minimal client for TikTok's public web endpoint that tells whether a user is live and, if so,
 * hands out the pull URLs. No login needed:
 *   GET https://www.tiktok.com/api-live/user/room/?aid=1988&uniqueId=<handle>&sourceType=54
 * data.user.status / data.liveRoom.status == 2 means live.
 * data.liveRoom.streamData.pull_data.stream_data is a JSON string: data.<quality>.main.flv
 */
object LiveApi {

    data class Room(
        val handle: String,
        val nickname: String,
        val live: Boolean,
        val roomId: String,
        val title: String,
        val viewers: Int,
        val avatar: String,
        /** FLV pull URLs, best first (according to the requested quality). */
        val flvUrls: List<String>
    )

    private val QUALITY_ORDER = listOf("origin", "uhd", "hd", "sd", "ld")

    /** Throws on network/HTTP errors; returns null if the user doesn't exist. */
    fun fetch(handle: String, preferredQuality: String = "origin"): Room? {
        val clean = handle.trim().removePrefix("@")
        val url = "https://www.tiktok.com/api-live/user/room/?aid=1988&uniqueId=${URLEncoder.encode(clean, "UTF-8")}&sourceType=54"
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36")
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("Referer", "https://www.tiktok.com/@$clean/live")
        try {
            if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val data = JSONObject(body).optJSONObject("data") ?: return null
            val user = data.optJSONObject("user") ?: return null
            val room = data.optJSONObject("liveRoom")
            val live = user.optInt("status") == 2 || room?.optInt("status") == 2
            val urls = if (live) streamUrls(room, preferredQuality) else emptyList()
            return Room(
                handle = user.optString("uniqueId", clean),
                nickname = user.optString("nickname", clean),
                live = live,
                roomId = user.optString("roomId"),
                title = room?.optString("title").orEmpty(),
                viewers = room?.optJSONObject("liveRoomStats")?.optInt("userCount") ?: 0,
                avatar = user.optString("avatarThumb"),
                flvUrls = urls
            )
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private fun streamUrls(room: JSONObject?, preferred: String): List<String> {
        val raw = room?.optJSONObject("streamData")?.optJSONObject("pull_data")?.optString("stream_data").orEmpty()
        if (raw.isBlank()) return emptyList()
        val qualities = runCatching { JSONObject(raw).optJSONObject("data") }.getOrNull() ?: return emptyList()
        val start = QUALITY_ORDER.indexOf(preferred).coerceAtLeast(0)
        // Preferred quality first, then lower ones, then anything higher as a last resort.
        val order = QUALITY_ORDER.drop(start) + QUALITY_ORDER.take(start).reversed()
        return order.mapNotNull { q -> qualities.optJSONObject(q)?.optJSONObject("main")?.optString("flv")?.takeIf { it.startsWith("http") } }
            .distinct()
    }
}
