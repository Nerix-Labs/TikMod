package com.mts.tikmod.utils

import com.mts.tikmod.Prefs

/**
 * Turns a live TikTok `Aweme` object into a clean, fully-resolved [AwemeMedia].
 *
 * This is what makes downloads exact: every URL comes from the very post the user acted on,
 * not from a global "last URL seen" guess (which preloading of the next videos corrupts).
 */
object MediaResolver {

    /** One downloadable rendition of the video. [urls] are CDN mirrors of the same file. */
    data class VideoOption(
        val urls: List<String>,
        val width: Int,
        val height: Int,
        val bitrate: Int,
        val isH265: Boolean,
        val label: String,
        val sizeBytes: Long
    ) {
        /** TikTok's ladder tier from the gear name ("adapt_lowest_1080_1" -> 1080). Width/height fields are often the source size. */
        val tier: Int get() = Regex("(?:^|_)(\\d{3,4})(?:_|$)").find(label)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val shortSide: Int get() {
            val dims = minOf(width, height).takeIf { it > 0 } ?: maxOf(width, height)
            return if (tier > 0 && dims > 0) minOf(tier, dims) else if (tier > 0) tier else dims
        }
        /** Best proxy for real quality: bytes on disk, then bitrate. */
        val weight: Long get() = if (sizeBytes > 0) sizeBytes else bitrate.toLong() * 60 / 8
        val qualityTag: String
            get() = when {
                shortSide >= 2160 -> "4K"
                shortSide >= 1440 -> "2K"
                shortSide >= 1080 -> "1080p"
                shortSide >= 720 -> "720p"
                shortSide > 0 -> "${shortSide}p"
                else -> "HD"
            }
    }

    data class AwemeMedia(
        val awemeId: String,
        val desc: String,
        val authorUniqueId: String,
        val authorNickname: String,
        val shareUrl: String,
        val createTime: Long,
        val videoOptions: List<VideoOption>,
        val musicUrls: List<String>,
        val musicTitle: String,
        val coverUrls: List<String>,
        val avatarUrls: List<String>,
        val images: List<List<String>>,
        val durationMs: Int,
        /** Timestamp (ms) of the frame the creator picked as the cover, -1 if unknown. */
        val coverTimeMs: Long = -1,
        /** The real @handle (empty when unknown — [authorUniqueId] may fall back to the nickname). */
        val authorHandle: String = ""
    ) {
        val isPhotoPost: Boolean get() = images.isNotEmpty()
        val hasVideo: Boolean get() = videoOptions.isNotEmpty()
        val webUrl: String
            get() = shareUrl.ifBlank {
                if (authorUniqueId.isNotBlank()) "https://www.tiktok.com/@$authorUniqueId/video/$awemeId"
                else "https://www.tiktok.com/video/$awemeId"
            }
    }

    fun resolve(aweme: Any?): AwemeMedia? {
        if (aweme == null) return null
        return runCatching {
            val id = Reflect.string(aweme, "getAid", "aid") ?: return null
            val author = Reflect.get(aweme, "getAuthor", "author")
            val video = Reflect.get(aweme, "getVideo", "video")
            val music = Reflect.get(aweme, "getMusic", "music")
            XLog.log("MediaResolver: aweme=$id author=${author?.javaClass?.simpleName} " +
                "uniqueId=${Reflect.field(author, "uniqueId")} nick=${Reflect.field(author, "nickname")} uid=${Reflect.field(author, "uid")}")

            AwemeMedia(
                awemeId = id,
                desc = Reflect.string(aweme, "getDesc", "desc") ?: "",
                authorUniqueId = Reflect.string(author, "getUniqueId", "uniqueId")
                    ?: Reflect.string(author, "getShortId", "shortId")?.takeIf { it != "0" }
                    ?: Reflect.string(author, "getNickname", "nickname") ?: "",
                authorNickname = Reflect.string(author, "getNickname", "nickname") ?: "",
                shareUrl = Reflect.string(aweme, "getShareUrl", "shareUrl")?.substringBefore('?') ?: "",
                createTime = Reflect.long(aweme, "getCreateTime", "createTime"),
                videoOptions = collectVideoOptions(video),
                musicUrls = Reflect.urls(Reflect.get(music, "getPlayUrl", "playUrl")),
                musicTitle = Reflect.string(music, "getMusicName", "musicName") ?: "",
                coverUrls = collectCover(video),
                // Largest first, but keep every size as a fallback: the large one is often an expired signed URL.
                avatarUrls = listOf(
                    "getAvatarLarger" to "avatarLarger", "getAvatar300" to "avatar300",
                    "getAvatar168" to "avatar168", "getAvatarMedium" to "avatarMedium", "getAvatarThumb" to "avatarThumb"
                ).flatMap { (g, f) -> Reflect.urls(Reflect.get(author, g, f)) }.distinct(),
                images = collectImages(aweme),
                durationMs = Reflect.int(video, "getDuration", "duration"),
                coverTimeMs = (Reflect.get(video, "getCoverTsp", "coverTsp") as? Number)?.toDouble()
                    ?.takeIf { it >= 0 }?.let { (it * 1000).toLong() } ?: -1,
                authorHandle = Reflect.string(author, "getUniqueId", "uniqueId") ?: ""
            )
        }.onFailure { XLog.log("MediaResolver: ${it.message}") }.getOrNull()
    }

    private fun collectVideoOptions(video: Any?): List<VideoOption> {
        if (video == null) return emptyList()
        val out = ArrayList<VideoOption>()
        val vw = Reflect.int(video, "getWidth", "width")
        val vh = Reflect.int(video, "getHeight", "height")

        // 1. Every bitrate rendition (this is where 1080p / 2K / H.264 vs H.265 live)
        val bitRates = (Reflect.get(video, "getBitRate", "bitRateList") as? List<*>).orEmpty()
        for (br in bitRates) {
            val urls = Reflect.urls(Reflect.get(br, "getPlayAddr", "playAddr"))
            if (urls.isEmpty()) continue
            val gear = Reflect.string(br, "getGearName", "gearName") ?: ""
            val h265 = Reflect.int(br, "getIsBytevc1", "isBytevc1") == 1 ||
                gear.contains("bytevc1", true) || gear.contains("h265", true)
            out += VideoOption(
                urls = urls,
                width = Reflect.int(br, "getVideoWidth", "videoWidth").takeIf { it > 0 } ?: vw,
                height = Reflect.int(br, "getVideoHeight", "videoHeight").takeIf { it > 0 } ?: vh,
                bitrate = Reflect.int(br, "getBitRate", "bitRate"),
                isH265 = h265,
                label = gear,
                sizeBytes = Reflect.long(br, "getSize", "size")
            )
        }

        // 2. Explicit no-watermark download address (usually the original H.264 upload)
        fun addAddr(model: Any?, label: String, h265: Boolean = false) {
            val urls = Reflect.urls(model)
            if (urls.isEmpty() || out.any { it.urls.first() == urls.first() }) return
            out += VideoOption(
                urls = urls,
                width = Reflect.int(model, "getWidth", "width").takeIf { it > 0 } ?: vw,
                height = Reflect.int(model, "getHeight", "height").takeIf { it > 0 } ?: vh,
                bitrate = 0, isH265 = h265, label = label,
                sizeBytes = Reflect.long(model, "getSize", "size")
            )
        }
        addAddr(Reflect.get(video, "getDownloadNoWatermarkAddr", "downloadNoWatermarkAddr"), "no_watermark")
        addAddr(Reflect.get(video, "getPlayAddrH264", "h264PlayAddrValue"), "play_h264")
        addAddr(Reflect.get(video, "getPlayAddr", "playAddrValue"), "play")
        addAddr(Reflect.get(video, "getPlayAddrBytevc1", "playAddrBytevc1Value"), "play_h265", h265 = true)
        return out
    }

    /**
     * Highest-resolution still cover. TikTok URLs are signed (the size inside them can't be edited),
     * so we compare every cover the post carries and take the largest, preferring JPEG mirrors.
     */
    private fun collectCover(video: Any?): List<String> {
        if (video == null) return emptyList()
        val candidates = listOf(
            "getOriginCover" to "originCover", "getCover" to "cover", "getAiCover" to "aiCover",
            "getCachedOuterCoverUrl" to "cachedOuterCoverUrl", "getDynamicCover" to "dynamicCover"
        ).flatMap { (g, f) ->
            Reflect.urls(Reflect.get(video, g, f)).filterNot { it.contains(".gif") || it.contains("animated") }
        }.distinct()
        // width/height on these models report the *video* size, so rank by the CDN template instead:
        // "...~tplv-xxx-origin.image" is full size; "...shrink:640:640..." is capped at 640.
        fun score(url: String): Int {
            val path = url.substringBefore('?')
            if (path.contains("-origin")) return 100_000
            return Regex(":(\\d{2,4}):(\\d{2,4})").find(path)?.let { maxOf(it.groupValues[1].toInt(), it.groupValues[2].toInt()) } ?: 1
        }
        fun formatRank(url: String): Int {
            val path = url.substringBefore('?').lowercase()
            return when {
                path.endsWith(".jpeg") || path.endsWith(".jpg") -> 0
                path.endsWith(".image") || path.endsWith(".webp") -> 1
                else -> 2 // heic
            }
        }
        return candidates.sortedWith(compareByDescending<String> { score(it) }.thenBy { formatRank(it) })
    }

    /** Photo-mode / slideshow posts: the highest quality, watermark-free URL list of every image. */
    private fun collectImages(aweme: Any): List<List<String>> {
        val info = Reflect.get(aweme, "getPhotoModeImageInfo", "photoModeImageInfo")
        val candidates = listOfNotNull(
            Reflect.get(info, "getPhotoModeImageList", "photoModeImageList") as? List<*>,
            Reflect.get(info, "getImageList", "imageList") as? List<*>,
            Reflect.get(aweme, "getImageInfos", "imageInfos") as? List<*>
        )
        for (list in candidates) {
            val images = list.mapNotNull { img ->
                val urls = sequenceOf(
                    Reflect.field(img, "displayImageNoWatermark"),
                    Reflect.get(img, "getLabelLarge", "labelLarge"),
                    Reflect.field(img, "targetMultiRateImageUrl"),
                    Reflect.field(img, "urlModel"),
                    Reflect.field(img, "displayImage"),
                    Reflect.field(img, "thumbnail")
                ).map { Reflect.urls(it) }.firstOrNull { it.isNotEmpty() }
                urls
            }
            if (images.isNotEmpty()) return images
        }
        return emptyList()
    }

    /**
     * Orders the renditions for download according to the user's quality + codec settings.
     * The downloader walks this list (and every mirror URL inside) until one succeeds.
     */
    fun rankVideoOptions(options: List<VideoOption>, quality: String, codec: String): List<VideoOption> {
        if (options.isEmpty()) return options
        val pool = if (codec == Prefs.VIDEO_CODEC_H264) {
            options.filter { !it.isH265 }.ifEmpty { options }
        } else options

        // Resolution fields lie (TikTok reports the source size for every rendition), so "best" means
        // the most data: the original no-watermark upload or the highest-bitrate rendition.
        val byBest = compareByDescending<VideoOption> { it.weight }.thenByDescending { it.shortSide }
        val ranked = when (quality) {
            Prefs.DOWNLOAD_QUALITY_SD -> pool.sortedWith(compareBy<VideoOption> { it.weight.takeIf { w -> w > 0 } ?: Long.MAX_VALUE })
            Prefs.DOWNLOAD_QUALITY_HD -> pool.sortedWith(
                compareBy<VideoOption> { if (it.shortSide in 700..1080) 0 else 1 }.then(byBest)
            )
            else -> pool.sortedWith(byBest)
        }
        // Anything filtered out by codec preference stays as a last-resort fallback.
        return ranked + (options - ranked.toSet())
    }
}
