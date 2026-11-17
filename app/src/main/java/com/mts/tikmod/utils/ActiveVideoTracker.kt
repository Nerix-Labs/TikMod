package com.mts.tikmod.utils

object ActiveVideoTracker {
    var lastActiveVideoUrl: String? = null
    var lastActiveAwemeId: String? = null
    var lastActiveMusicUrl: String? = null
    var lastActiveCoverUrl: String? = null

    var isLiveStream: Boolean = false
    var liveStreamUrl: String? = null
    var liveRoomId: String? = null

    fun updateLiveStream(streamUrl: String?, roomId: String? = null) {
        if (!streamUrl.isNullOrBlank()) {
            liveStreamUrl = streamUrl
            isLiveStream = true
            if (!roomId.isNullOrBlank()) {
                liveRoomId = roomId
            }
            XLog.log("ActiveVideoTracker: Updated Live Stream URL -> $streamUrl")
        }
    }

    fun updateActiveVideo(url: String?, id: String? = null) {
        if (!url.isNullOrBlank()) {
            if (url.contains(".m3u8") || url.contains(".flv") || url.contains("pull-") || url.contains("live_url")) {
                updateLiveStream(url, id)
                return
            }
            val cleanUrl = if (url.contains("/playwm/")) url.replace("/playwm/", "/play/") else url
            lastActiveVideoUrl = cleanUrl
            isLiveStream = false
            if (!id.isNullOrBlank()) {
                lastActiveAwemeId = id
            }
            XLog.log("ActiveVideoTracker: Updated active video URL -> $cleanUrl")
        }
    }

    fun updateActiveMusic(url: String?) {
        if (!url.isNullOrBlank()) {
            lastActiveMusicUrl = url
            XLog.log("ActiveVideoTracker: Updated active music URL -> $url")
        }
    }

    fun updateActiveCover(url: String?) {
        if (!url.isNullOrBlank()) {
            lastActiveCoverUrl = url
            XLog.log("ActiveVideoTracker: Updated active cover URL -> $url")
        }
    }
}
