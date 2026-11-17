package com.mts.tikmod.utils

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.mts.tikmod.Prefs
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * TikMod download engine.
 *
 * - Exact media: callers pass a resolved [MediaResolver.AwemeMedia], never a guessed URL.
 * - Resilient: walks every rendition and every CDN mirror until one succeeds.
 * - Correct storage: MediaStore on Android 10+ (instantly visible in the gallery), files below.
 * - Honest type detection by magic bytes (MP4 / JPEG / PNG / WEBP / HEIC / MP3 / M4A).
 */
object TikModDownloader {

    enum class Kind(val label: String, val emoji: String) {
        VIDEO("סרטון", "🎬"), PHOTO("תמונה", "🖼️"), AUDIO("שמע", "🎵"),
        COVER("קאבר", "🖼️"), AVATAR("תמונת פרופיל", "👤"), LIVE("שידור חי", "🔴")
    }

    private const val CHANNEL_ID = "tikmod_downloads"
    private const val LIVE_CHANNEL_ID = "tikmod_live_recording"
    private const val ACTION_STOP_LIVE = "com.mts.tikmod.action.STOP_LIVE"
    private const val REGISTRY = "tikmod_download_registry"
    private const val LIVE_NOTIF_ID = 4242

    private val executor = Executors.newFixedThreadPool(2)
    private val notifIds = AtomicInteger(5000)
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    // ---------------------------------------------------------------- public API

    fun downloadVideo(context: Context, media: MediaResolver.AwemeMedia) {
        val ctx = context.applicationContext ?: context
        if (!media.hasVideo) {
            if (media.isPhotoPost) return downloadImages(ctx, media)
            return toast(ctx, "⚠️ לא נמצא וידאו בפוסט הזה")
        }
        if (isDuplicate(ctx, Kind.VIDEO, media.awemeId)) return
        copyCaptionIfEnabled(ctx, media)

        val ranked = MediaResolver.rankVideoOptions(media.videoOptions, Prefs.getDownloadQuality(ctx), Prefs.getVideoCodec(ctx))
        val best = ranked.first()
        ranked.forEachIndexed { i, o ->
            XLog.log("Downloader option #$i ${o.label} ${o.width}x${o.height} br=${o.bitrate} h265=${o.isH265} size=${o.sizeBytes} urls=${o.urls.size}")
        }
        toast(ctx, "⚡ TikMod: מוריד ${best.qualityTag}${if (best.isH265) " (H.265)" else ""} ללא סימן מים…")

        executor.execute {
            val job = Job(ctx, Kind.VIDEO, media.authorNickname.ifBlank { "TikTok" })
            var lastError = "no candidates"
            for (option in ranked) {
                val name = buildFileName(ctx, media, Kind.VIDEO, option.qualityTag)
                val result = runCatching { job.fetchAndSave(option.urls, name, media.authorUniqueId) }
                if (result.isSuccess) {
                    val saved = result.getOrThrow()
                    markDownloaded(ctx, Kind.VIDEO, media.awemeId, saved.uri)
                    job.finish(saved, "${option.qualityTag} • ${formatSize(saved.bytes)}")
                    addHistory(ctx, Kind.VIDEO, media, saved, option.qualityTag)
                    return@execute
                }
                lastError = result.exceptionOrNull()?.message ?: "unknown"
                XLog.log("Downloader: rendition ${option.label} failed: $lastError")
            }
            job.fail(lastError)
        }
    }

    fun downloadImages(context: Context, media: MediaResolver.AwemeMedia) {
        val ctx = context.applicationContext ?: context
        if (!media.isPhotoPost) return toast(ctx, "⚠️ אין תמונות בפוסט הזה")
        if (isDuplicate(ctx, Kind.PHOTO, media.awemeId)) return
        copyCaptionIfEnabled(ctx, media)
        val total = media.images.size
        toast(ctx, "🖼️ TikMod: מוריד $total תמונות באיכות מקורית…")

        executor.execute {
            val job = Job(ctx, Kind.PHOTO, media.authorNickname.ifBlank { "TikTok" })
            var ok = 0
            var last: Saved? = null
            media.images.forEachIndexed { i, urls ->
                job.setStep("תמונה ${i + 1} מתוך $total")
                val name = buildFileName(ctx, media, Kind.PHOTO, String.format(Locale.US, "%02d", i + 1))
                runCatching { job.fetchAndSave(urls, name, media.authorUniqueId) }
                    .onSuccess { ok++; last = it }
                    .onFailure { XLog.log("Downloader: image ${i + 1} failed: ${it.message}") }
            }
            val saved = last
            if (ok > 0 && saved != null) {
                markDownloaded(ctx, Kind.PHOTO, media.awemeId, saved.uri)
                job.finish(saved, "$ok/$total תמונות נשמרו")
                addHistory(ctx, Kind.PHOTO, media, saved, "$ok תמונות")
            } else job.fail("לא ניתן להוריד את התמונות")
        }
    }

    fun downloadAudio(context: Context, media: MediaResolver.AwemeMedia) {
        val ctx = context.applicationContext ?: context
        if (isDuplicate(ctx, Kind.AUDIO, media.awemeId)) return
        toast(ctx, "🎵 TikMod: מוריד שמע…")
        val preferOriginal = Prefs.getAudioSource(ctx) != Prefs.AUDIO_SOURCE_VIDEO

        executor.execute {
            val job = Job(ctx, Kind.AUDIO, media.musicTitle.ifBlank { "TikTok" })
            val name = buildFileName(ctx, media, Kind.AUDIO, media.musicTitle.take(30))
            var saved: Saved? = null

            if (preferOriginal && media.musicUrls.isNotEmpty()) {
                saved = runCatching { job.fetchAndSave(media.musicUrls, name, media.authorUniqueId) }
                    .onFailure { XLog.log("Downloader: original sound failed: ${it.message}") }.getOrNull()
            }
            if (saved == null && media.hasVideo) {
                saved = runCatching { job.extractAudioFromVideo(media, name) }
                    .onFailure { XLog.log("Downloader: audio extraction failed: ${it.message}") }.getOrNull()
            }
            if (saved == null && !preferOriginal && media.musicUrls.isNotEmpty()) {
                saved = runCatching { job.fetchAndSave(media.musicUrls, name, media.authorUniqueId) }.getOrNull()
            }

            if (saved != null) {
                markDownloaded(ctx, Kind.AUDIO, media.awemeId, saved.uri)
                job.finish(saved, formatSize(saved.bytes))
                addHistory(ctx, Kind.AUDIO, media, saved, saved.mime.substringAfter('/'))
            } else job.fail("לא נמצא מקור שמע זמין")
        }
    }

    /**
     * HD cover. TikTok stores still covers at 270–640 px only, so for videos we grab the exact frame the
     * creator chose (Video.coverTsp) from the best rendition, at full resolution, as a JPEG.
     * Photo posts / failures fall back to the best still cover URL.
     */
    fun downloadCover(context: Context, media: MediaResolver.AwemeMedia) {
        val ctx = context.applicationContext ?: context
        if (!media.hasVideo) return simpleDownload(ctx, Kind.COVER, media, media.coverUrls, "cover")
        toast(ctx, "🖼️ TikMod: מחלץ קאבר HD מהסרטון…")
        executor.execute {
            val job = Job(ctx, Kind.COVER, media.authorNickname.ifBlank { "TikTok" })
            val frame = runCatching { job.grabCoverFrame(media) }
                .onFailure { XLog.log("Downloader: HD frame failed (${it.message}), using still cover") }.getOrNull()
            if (frame != null) {
                runCatching {
                    val name = buildFileName(ctx, media, Kind.COVER, "cover") + ".jpg"
                    val saved = frame.inputStream().use { job.saveStream(it, name, "image/jpeg", frame.size.toLong(), media.authorUniqueId) }
                    job.finish(saved, "HD • ${formatSize(saved.bytes)}")
                    addHistory(ctx, Kind.COVER, media, saved, "HD")
                }.onFailure { job.fail(it.message ?: "save failed") }
                return@execute
            }
            runCatching { job.fetchAndSave(media.coverUrls, buildFileName(ctx, media, Kind.COVER, "cover"), media.authorUniqueId) }
                .onSuccess { job.finish(it, formatSize(it.bytes)); addHistory(ctx, Kind.COVER, media, it, "cover") }
                .onFailure { job.fail(it.message ?: "error") }
        }
    }


    /**
     * HD profile picture. Avatars inside feed items are 100x100 and often carry expired signatures (cached
     * profiles), so we first read a fresh 1080x1080 `avatarLarger` from the creator's public profile page.
     */
    fun downloadAvatar(context: Context, media: MediaResolver.AwemeMedia) {
        val ctx = context.applicationContext ?: context
        toast(ctx, "👤 TikMod: מוריד תמונת פרופיל HD…")
        executor.execute {
            val job = Job(ctx, Kind.AVATAR, media.authorNickname.ifBlank { media.authorHandle })
            val fresh = media.authorHandle.takeIf { it.isNotBlank() }?.let { handle ->
                runCatching { freshAvatarUrl(handle) }
                    .onFailure { XLog.log("Downloader: profile page lookup failed (${it.message})") }.getOrNull()
            }
            val urls = listOfNotNull(fresh) + media.avatarUrls
            if (urls.isEmpty()) return@execute job.fail("לא נמצאה תמונת פרופיל")
            runCatching { job.fetchAndSave(urls, buildFileName(ctx, media, Kind.AVATAR, "avatar"), media.authorUniqueId) }
                .onSuccess { job.finish(it, (if (fresh != null && it.bytes > 0) "1080p • " else "") + formatSize(it.bytes)); addHistory(ctx, Kind.AVATAR, media, it, "avatar") }
                .onFailure { job.fail(it.message ?: "error") }
        }
    }

    /** Reads the creator's current `avatarLarger` (1080x1080, freshly signed) from their public profile page. */
    private fun freshAvatarUrl(handle: String): String? {
        val conn = URL("https://www.tiktok.com/@" + java.net.URLEncoder.encode(handle, "UTF-8")).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000; conn.readTimeout = 15_000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}; ${Build.MODEL}) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36")
        conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        try {
            if (conn.responseCode !in 200..299) return null
            val html = conn.inputStream.bufferedReader().use { it.readText() }
            val raw = Regex("\"avatarLarger\":\"(https:[^\"]+)\"").find(html)?.groupValues?.get(1) ?: return null
            return raw.replace("\\u002F", "/").replace("\\u0026", "&").replace("\\/", "/")
                .also { XLog.log("Downloader: fresh avatar ${it.substringBefore('?').substringAfterLast('~')}") }
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /**
     * Smart share: makes sure the post's real media exists on the device (re-using an earlier download
     * when there is one) and hands back the content URIs + MIME type to attach to the share intent.
     * Videos -> one clean MP4; photo carousels -> every image.
     */
    fun prepareForShare(
        context: Context,
        media: MediaResolver.AwemeMedia,
        targetLabel: String,
        onReady: (uris: List<Uri>, mime: String) -> Unit,
        onFail: (String) -> Unit
    ) {
        val ctx = context.applicationContext ?: context
        val photos = media.isPhotoPost && (!media.hasVideo || Prefs.isDownloadPhotos(ctx))
        val kind = if (photos) Kind.PHOTO else Kind.VIDEO

        // Re-use the existing file (videos are stored as a single URI in the registry).
        if (kind == Kind.VIDEO) {
            val cached = registry(ctx).getString("${Kind.VIDEO.name}_${media.awemeId}", null)?.let { Uri.parse(it) }
            if (cached != null && runCatching { ctx.contentResolver.openFileDescriptor(cached, "r")?.use { true } }.getOrNull() == true) {
                return onReady(listOf(cached), "video/mp4")
            }
        }
        toast(ctx, "⏳ מכין את ה${if (photos) "תמונות" else "סרטון"} לשליחה ל-$targetLabel…")
        executor.execute {
            val job = Job(ctx, kind, "שליחה ל-$targetLabel")
            try {
                if (photos) {
                    val saved = media.images.mapIndexedNotNull { i, urls ->
                        job.setStep("תמונה ${i + 1} מתוך ${media.images.size}")
                        runCatching { job.fetchAndSave(urls, buildFileName(ctx, media, Kind.PHOTO, String.format(Locale.US, "%02d", i + 1)), media.authorUniqueId) }.getOrNull()
                    }
                    if (saved.isEmpty()) throw IllegalStateException("לא ניתן להוריד את התמונות")
                    job.finish(saved.last(), "${saved.size} תמונות • נשלח ל-$targetLabel")
                    addHistory(ctx, Kind.PHOTO, media, saved.last(), "${saved.size} תמונות")
                    mainHandler.post { onReady(saved.map { it.uri }, if (saved.all { it.mime == saved[0].mime }) saved[0].mime else "image/*") }
                } else {
                    val ranked = MediaResolver.rankVideoOptions(media.videoOptions, Prefs.getDownloadQuality(ctx), Prefs.getVideoCodec(ctx))
                    var saved: Saved? = null
                    for (o in ranked) {
                        saved = runCatching { job.fetchAndSave(o.urls, buildFileName(ctx, media, Kind.VIDEO, o.qualityTag), media.authorUniqueId) }.getOrNull()
                        if (saved != null) break
                    }
                    val s = saved ?: throw IllegalStateException("לא ניתן להוריד את הסרטון")
                    markDownloaded(ctx, Kind.VIDEO, media.awemeId, s.uri)
                    job.finish(s, "נשלח ל-$targetLabel")
                    addHistory(ctx, Kind.VIDEO, media, s, "שיתוף")
                    mainHandler.post { onReady(listOf(s.uri), s.mime) }
                }
            } catch (t: Throwable) {
                job.fail(t.message ?: "error")
                mainHandler.post { onFail(t.message ?: "error") }
            }
        }
    }

    /** Legacy URL entry points (used by Anti-Recall for deleted chat media). */
    fun downloadVideo(context: Context, videoUrl: String, title: String = "TikTok_Video") =
        rawDownload(context, Kind.VIDEO, videoUrl, title)

    fun downloadCover(context: Context, coverUrl: String, title: String = "TikTok_Cover") =
        rawDownload(context, Kind.COVER, coverUrl, title)

    fun copyLink(context: Context, media: MediaResolver.AwemeMedia) {
        val ctx = context.applicationContext ?: context
        val direct = MediaResolver.rankVideoOptions(media.videoOptions, Prefs.getDownloadQuality(ctx), Prefs.getVideoCodec(ctx))
            .firstOrNull()?.urls?.firstOrNull()
        val text = direct ?: media.images.firstOrNull()?.firstOrNull() ?: media.webUrl
        setClipboard(ctx, "TikMod link", text)
        toast(ctx, if (direct != null) "🔗 קישור ישיר ללא סימן מים הועתק" else "🔗 הקישור הועתק")
    }

    fun copyCaption(context: Context, media: MediaResolver.AwemeMedia) {
        val ctx = context.applicationContext ?: context
        setClipboard(ctx, "TikTok caption", media.desc)
        toast(ctx, "📝 התיאור הועתק (${media.desc.length} תווים)")
    }

    // ---------------------------------------------------------------- live recording

    /** In-process recording (fallback only: dies with TikTok). */
    @Volatile private var localRecording = false
    @Volatile private var liveConn: HttpURLConnection? = null
    @Volatile private var stopReceiverRegistered = false

    /** True while a LIVE recording runs — in TikMod's service (preferred) or, as a fallback, in-process. */
    val isLiveRecording: Boolean
        get() = localRecording || remoteRecordings(Prefs.hostContext).isNotEmpty()

    private fun remoteRecordings(ctx: Context?): List<String> = ctx?.let { c ->
        runCatching { c.contentResolver.call(Uri.parse(Prefs.PROVIDER_URI), "live_recording", null, null)?.getStringArrayList("handles") }.getOrNull()
    }.orEmpty()

    fun toggleLiveRecording(context: Context, live: MediaContext.LiveStream) {
        if (localRecording) { stopLiveRecording(context); return }
        val remote = remoteRecordings(context)
        if (remote.isNotEmpty()) {
            remote.forEach { h -> runCatching { context.contentResolver.call(Uri.parse(Prefs.PROVIDER_URI), "stop_live_record", h, null) } }
            return
        }
        startLiveRecording(context, live)
    }

    /**
     * Hands the recording to TikMod's own foreground service, so it keeps going when TikTok is closed or swiped
     * away from recents. Tries a direct start (TikTok is in the foreground, so it's allowed), then the provider.
     */
    private fun delegateToTikMod(ctx: Context, live: MediaContext.LiveStream): Boolean {
        val handle = live.owner.ifBlank { return false }
        val extras = android.os.Bundle().apply {
            putString("handle", handle)
            putString("title", live.title)
            putStringArrayList("urls", ArrayList(live.urls))
        }
        val intent = Intent("com.mts.tikmod.action.RECORD_LIVE")
            .setComponent(android.content.ComponentName("com.mts.tikmod", "com.mts.tikmod.live.LiveWatchService"))
            .putExtras(extras)
        val direct = runCatching {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(intent) else ctx.startService(intent)
        }.onFailure { XLog.log("LiveRecorder: direct hand-off failed (${it.message}), trying provider") }
            .getOrNull() != null
        if (direct) return true
        return runCatching {
            ctx.contentResolver.call(Uri.parse(Prefs.PROVIDER_URI), "start_live_record", null, extras)?.getBoolean("success") == true
        }.getOrDefault(false)
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun ensureStopReceiver(ctx: Context) {
        if (stopReceiverRegistered) return
        runCatching {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) { stopLiveRecording(c) }
            }
            val filter = IntentFilter(ACTION_STOP_LIVE)
            if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else ctx.registerReceiver(receiver, filter)
            stopReceiverRegistered = true
        }
    }

    fun startLiveRecording(context: Context, live: MediaContext.LiveStream) {
        val ctx = context.applicationContext ?: context
        if (localRecording) return
        if (delegateToTikMod(ctx, live)) {
            XLog.log("LiveRecorder: handed @${live.owner} to TikMod's service")
            return
        }
        localRecording = true
        ensureStopReceiver(ctx)
        createChannel(ctx)
        toast(ctx, "🔴 TikMod: ההקלטה החלה — אפשר לעצור מההתראה")

        thread(name = "TikMod-LiveRecorder") {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val stopIntent = PendingIntent.getBroadcast(
                ctx, 1, Intent(ACTION_STOP_LIVE).setPackage(ctx.packageName),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val builder = NotificationCompat.Builder(ctx, LIVE_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setContentTitle("🔴 מקליט LIVE • ${live.owner.ifBlank { live.roomId }}")
                .setContentText("מתחבר לשידור…")
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setUsesChronometer(true).setWhen(System.currentTimeMillis())
                .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
                .addAction(android.R.drawable.ic_media_pause, "⏹ עצור ושמור", stopIntent)
            runCatching { nm?.notify(LIVE_NOTIF_ID, builder.build()) }

            captureLive(
                ctx, owner = live.owner.ifBlank { live.roomId }, title = live.title,
                urls = { live.urls },
                isRunning = { localRecording },
                onConn = { liveConn = it },
                onProgress = { dur, bytes ->
                    builder.setContentText("${formatDuration(dur)} • ${formatSize(bytes)}")
                    runCatching { nm?.notify(LIVE_NOTIF_ID, builder.build()) }
                },
                onSaving = {
                    builder.setContentText("מעבד ושומר כ-MP4…").setProgress(0, 0, true)
                    runCatching { nm?.notify(LIVE_NOTIF_ID, builder.build()) }
                }
            )
            localRecording = false
            runCatching { nm?.cancel(LIVE_NOTIF_ID) }
        }
    }

    /** Result of [captureLive]: null when nothing usable was recorded. */
    data class LiveResult(val saved: Saved, val durationMs: Long)

    /**
     * Records a LIVE FLV stream tag-by-tag (clean clock from 0, continuous across reconnects), then remuxes
     * to a standard MP4 and saves it to the gallery (+ history, + "saved" notification with open/share).
     * Blocking — call from a background thread. Shared by the manual recorder and the auto-recorder.
     *
     * @param urls returns the current pull URLs; called again after a failed connection (lets the caller refresh).
     * @param isRunning polled continuously; return false to stop and save.
     * @param onProgress called about once a second with (duration ms, bytes written).
     * @param onSaving called once when the stream is over and the file is being processed.
     */
    fun captureLive(
        context: Context,
        owner: String,
        title: String,
        urls: () -> List<String>,
        isRunning: () -> Boolean,
        onConn: (HttpURLConnection?) -> Unit = {},
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        onSaving: () -> Unit = {},
        maxFailures: Int = 6,
        maxEmptySegments: Int = 5
    ): LiveResult? {
        val ctx = context.applicationContext ?: context
        val baseName = sanitize("Live_${owner}_${timestamp()}")
        val temp = File(ctx.cacheDir, "tikmod_live_${System.nanoTime()}.flv")
        activeLiveTemps.add(temp.absolutePath)
        // Sidecar so an interrupted recording (process killed, module updated) can be recovered with its name.
        runCatching {
            File(temp.path + ".json").writeText(JSONObject().put("owner", owner).put("title", title).put("base", baseName).toString())
        }
        try {
            return recordAndSave(ctx, temp, baseName, owner, title, urls, isRunning, onConn, onProgress, onSaving, maxFailures, maxEmptySegments)
        } finally {
            activeLiveTemps.remove(temp.absolutePath)
        }
    }

    private val activeLiveTemps: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

    private fun recordAndSave(
        ctx: Context, temp: File, baseName: String, owner: String, title: String,
        urls: () -> List<String>, isRunning: () -> Boolean, onConn: (HttpURLConnection?) -> Unit,
        onProgress: (Long, Long) -> Unit, onSaving: () -> Unit, maxFailures: Int, maxEmptySegments: Int
    ): LiveResult? {
        var writer: FlvRemuxer.Writer? = null
        var failures = 0
        var emptySegments = 0
        var urlIndex = 0
        var currentUrls = urls()

        temp.outputStream().buffered(256 * 1024).use { out ->
            while (isRunning() && failures < maxFailures && emptySegments < maxEmptySegments) {
                if (currentUrls.isEmpty()) { currentUrls = urls(); if (currentUrls.isEmpty()) { failures++; Thread.sleep(3000); continue } }
                val url = currentUrls[urlIndex % currentUrls.size]
                var tagsThisSegment = 0
                var conn: HttpURLConnection? = null
                try {
                    conn = open(url, readTimeoutMs = 20_000)
                    onConn(conn)
                    if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
                    conn.inputStream.use { input ->
                        val reader = FlvRemuxer.Reader(input)
                        reader.readHeader()
                        val w = writer ?: FlvRemuxer.Writer(out, reader.hasAudio, reader.hasVideo).also { writer = it }
                        w.startSegment()
                        var lastUi = 0L
                        while (isRunning()) {
                            val tag = reader.next() ?: break
                            w.write(tag)
                            tagsThisSegment++
                            failures = 0
                            val now = System.currentTimeMillis()
                            if (now - lastUi > 1000) { lastUi = now; onProgress(w.durationMs, w.bytes) }
                        }
                    }
                } catch (t: Throwable) {
                    if (!isRunning()) break
                    failures++
                    urlIndex++
                    if (failures % 2 == 0) currentUrls = urls() // signed URLs can expire: refresh
                    XLog.log("LiveRecorder[$owner]: reconnect #$failures (${t.message})")
                } finally {
                    runCatching { conn?.disconnect() }
                    onConn(null)
                }
                if (isRunning()) {
                    if (tagsThisSegment == 0) emptySegments++ else emptySegments = 0
                    Thread.sleep(1200)
                }
            }
        }
        val durationMs = writer?.durationMs ?: 0L
        onSaving()

        if (writer == null || durationMs < 500) {
            temp.delete(); File(temp.path + ".json").delete()
            toast(ctx, "❌ ההקלטה של $owner נכשלה — לא התקבל וידאו מהשידור")
            return null
        }
        return saveLiveFile(ctx, temp, baseName, owner, title, durationMs)
    }

    /**
     * Saves recordings that were cut off before they could be saved (TikMod's process was killed, e.g. by a
     * module update). Each leftover FLV in the cache is remuxed to MP4 and saved like a normal recording.
     */
    fun salvageInterruptedRecordings(context: Context) {
        val ctx = context.applicationContext ?: context
        synchronized(activeLiveTemps) {
            val orphans = ctx.cacheDir.listFiles { f -> f.name.startsWith("tikmod_live_") && f.name.endsWith(".flv") }
                .orEmpty().filter { it.absolutePath !in activeLiveTemps }
            for (f in orphans) {
                if (f.length() < 64 * 1024) { f.delete(); File(f.path + ".json").delete(); continue }
                val meta = runCatching { JSONObject(File(f.path + ".json").readText()) }.getOrNull()
                val owner = meta?.optString("owner").orEmpty().ifBlank { "LIVE" }
                val base = meta?.optString("base").orEmpty().ifBlank {
                    sanitize("Live_${owner}_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(f.lastModified())))
                }
                XLog.log("LiveRecorder: recovering interrupted recording ${f.name} (${formatSize(f.length())}) for $owner")
                toast(ctx, "♻️ משחזר הקלטה שנקטעה של $owner…")
                activeLiveTemps.add(f.absolutePath)
                try { saveLiveFile(ctx, f, base, owner, meta?.optString("title").orEmpty(), 0L) }
                finally { activeLiveTemps.remove(f.absolutePath) }
            }
        }
    }

    private fun saveLiveFile(ctx: Context, temp: File, baseName: String, owner: String, title: String, durationMs: Long): LiveResult? {
        val job = Job(ctx, Kind.LIVE, owner.ifBlank { "LIVE" })
        val label = title.ifBlank { "LIVE @$owner" }
        try {
            // Standard MP4 (no re-encode): plays in every player, correct duration, seekable.
            val mp4 = FlvRemuxer.toMp4(temp, ctx.cacheDir)
            runCatching {
                val ex = MediaExtractor()
                ex.setDataSource(mp4.file.absolutePath)
                val tracks = (0 until ex.trackCount).map { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME) }
                ex.release()
                XLog.log("LiveRecorder[$owner]: MP4 tracks=$tracks duration=${mp4.durationMs}ms")
                if (tracks.none { it?.startsWith("audio/") == true }) toast(ctx, "⚠️ בשידור הזה לא התקבל ערוץ שמע")
            }
            try {
                val ext = if (mp4.hasVideo) "mp4" else "m4a"
                val saved = mp4.file.inputStream().use { job.saveStream(it, "$baseName.$ext", mp4.mime, mp4.file.length(), owner) }
                job.finish(saved, "${formatDuration(mp4.durationMs)} • ${formatSize(saved.bytes)}")
                addHistoryRaw(ctx, Kind.LIVE, label, owner, saved, "MP4 • ${formatDuration(mp4.durationMs)}")
                return LiveResult(saved, mp4.durationMs)
            } finally {
                mp4.file.delete()
            }
        } catch (t: Throwable) {
            // Fallback: the cleaned FLV still has a correct duration.
            XLog.log("LiveRecorder[$owner]: MP4 remux failed (${t.message}), saving clean FLV")
            return runCatching {
                val saved = temp.inputStream().use { job.saveStream(it, "$baseName.flv", "video/x-flv", temp.length(), owner) }
                job.finish(saved, "${formatDuration(durationMs)} • FLV")
                addHistoryRaw(ctx, Kind.LIVE, label, owner, saved, "FLV")
                LiveResult(saved, durationMs)
            }.onFailure { job.fail(it.message ?: "save failed") }.getOrNull()
        } finally {
            temp.delete()
            File(temp.path + ".json").delete()
        }
    }

    fun stopLiveRecording(context: Context) {
        if (!localRecording) return
        localRecording = false
        runCatching { liveConn?.disconnect() }
        toast(context, "⏹️ TikMod: עוצר ושומר את ההקלטה…")
    }

    // ---------------------------------------------------------------- internals

    data class Saved(val uri: Uri, val mime: String, val bytes: Long, val displayName: String)

    /** One download job = one notification. */
    private class Job(val ctx: Context, val kind: Kind, val subtitle: String) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val id = notifIds.incrementAndGet()
        val showProgress = Prefs.isNotificationProgress(ctx)
        private var step = ""
        private var lastNotify = 0L
        val builder: NotificationCompat.Builder

        init {
            createChannel(ctx)
            builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("${kind.emoji} TikMod • מוריד ${kind.label}")
                .setContentText(subtitle)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setProgress(100, 0, true)
            notify(force = true)
        }

        fun setStep(text: String) { step = text; builder.setContentText("$text • $subtitle"); notify(force = true) }

        fun progress(done: Long, total: Long) {
            if (total > 0) builder.setProgress(100, ((done * 100) / total).toInt().coerceIn(0, 100), false)
            builder.setSubText(formatSize(done) + if (total > 0) " / ${formatSize(total)}" else "")
            notify()
        }

        private fun notify(force: Boolean = false) {
            if (!showProgress || nm == null) return
            val now = System.currentTimeMillis()
            if (!force && now - lastNotify < 400) return
            lastNotify = now
            runCatching { nm?.notify(id, builder.build()) }
        }

        fun fetchAndSave(urls: List<String>, baseName: String, author: String, strict: Boolean = true): Saved {
            var lastError: Throwable = IllegalStateException("no url")
            for (url in urls) {
                for (attempt in 0..1) {
                    try {
                        val conn = open(url)
                        try {
                            val code = conn.responseCode
                            if (code in 400..499) {
                                // Expired/forbidden mirror: retrying it is pointless, move to the next one.
                                lastError = IllegalStateException("HTTP $code")
                                XLog.log("Downloader: ${url.substringBefore('?').take(60)} -> HTTP $code, next mirror")
                                break
                            }
                            if (code !in 200..299) throw IllegalStateException("HTTP $code")
                            val length = conn.contentLengthLong
                            val input = conn.inputStream.buffered(64 * 1024)
                            input.mark(32)
                            val head = ByteArray(32)
                            val read = input.readAtLeast(head)
                            input.reset()
                            val (ext, mime) = sniff(head, read, conn.contentType, kind)
                            if (strict && kind == Kind.VIDEO && !mime.startsWith("video")) throw IllegalStateException("not a video ($mime)")
                            // A cover is a still image: skip animated WebP (dynamic cover) and try the next source.
                            if (kind == Kind.COVER && read >= 21 && String(head, 12, 4, Charsets.ISO_8859_1) == "VP8X" &&
                                (head[20].toInt() and 0x02) != 0) {
                                lastError = IllegalStateException("animated cover")
                                XLog.log("ℹ Downloader: skipping animated cover, next source")
                                break
                            }
                            // HEIC/WebP stills -> JPEG (opens everywhere: WhatsApp Web, Windows, older apps).
                            if (kind in setOf(Kind.PHOTO, Kind.COVER, Kind.AVATAR) && (mime == "image/heic" || mime == "image/webp") &&
                                Prefs.bool(ctx, Prefs.KEY_IMAGES_AS_JPEG)) {
                                val bytes = input.use { it.readBytes() }
                                val bmp = runCatching { android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
                                if (bmp != null) {
                                    val jpg = java.io.ByteArrayOutputStream()
                                    bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, jpg)
                                    bmp.recycle()
                                    val data = jpg.toByteArray()
                                    return saveStream(data.inputStream(), "$baseName.jpg", "image/jpeg", data.size.toLong(), author)
                                }
                                return saveStream(bytes.inputStream(), "$baseName.$ext", mime, bytes.size.toLong(), author)
                            }
                            return input.use { saveStream(it, "$baseName.$ext", mime, length, author) }
                        } finally {
                            runCatching { conn.disconnect() }
                        }
                    } catch (t: Throwable) {
                        lastError = t
                        XLog.log("Downloader: ${url.take(80)} attempt ${attempt + 1} -> ${t.message}")
                    }
                }
            }
            throw lastError
        }

        fun saveStream(input: InputStream, displayName: String, mime: String, length: Long, author: String): Saved {
            val (uri, out) = openOutput(ctx, kind, displayName, mime, author)
            var total = 0L
            try {
                out.use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        total += n
                        progress(total, length)
                    }
                }
                if (total == 0L) throw IllegalStateException("empty response")
                publish(ctx, uri, mime)
                return Saved(uri, mime, total, displayName)
            } catch (t: Throwable) {
                discard(ctx, uri)
                throw t
            }
        }

        /** Downloads the first rendition that works (all mirrors, with timeouts) into [target]. */
        fun downloadToTemp(options: List<MediaResolver.VideoOption>, target: File) {
            for (o in options) for (url in o.urls) {
                val ok = runCatching {
                    val conn = open(url)
                    try {
                        if (conn.responseCode !in 200..299) return@runCatching false
                        val len = conn.contentLengthLong
                        conn.inputStream.use { inp ->
                            target.outputStream().use { out ->
                                val buf = ByteArray(64 * 1024); var t = 0L
                                while (true) { val n = inp.read(buf); if (n < 0) break; out.write(buf, 0, n); t += n; progress(t, len) }
                            }
                        }
                        target.length() > 0
                    } finally { runCatching { conn.disconnect() } }
                }.getOrDefault(false)
                if (ok) return
            }
            throw IllegalStateException("video download failed")
        }

        fun extractAudioFromVideo(media: MediaResolver.AwemeMedia, baseName: String): Saved {
            // Smallest rendition: same audio track, fastest download.
            val options = media.videoOptions.sortedWith(compareBy<MediaResolver.VideoOption> { it.isH265 }.thenBy { it.weight })
            val tempVideo = File(ctx.cacheDir, "tikmod_a_${System.nanoTime()}.mp4")
            val tempAudio = File(ctx.cacheDir, "tikmod_a_${System.nanoTime()}.m4a")
            try {
                downloadToTemp(options, tempVideo)
                if (!extractAudioTrack(tempVideo, tempAudio)) throw IllegalStateException("no audio track")
                return tempAudio.inputStream().use { saveStream(it, "$baseName.m4a", "audio/mp4", tempAudio.length(), media.authorUniqueId) }
            } finally {
                tempVideo.delete(); tempAudio.delete()
            }
        }

        /**
         * JPEG of the cover frame at full resolution. Uses the already-downloaded video when there is one,
         * otherwise the best rendition is fetched to a temp file (MediaMetadataRetriever over HTTP can hang).
         */
        fun grabCoverFrame(media: MediaResolver.AwemeMedia): ByteArray {
            val timeUs = (media.coverTimeMs.takeIf { it >= 0 } ?: 0L) * 1000
            val r = android.media.MediaMetadataRetriever()
            val temp = File(ctx.cacheDir, "tikmod_f_${System.nanoTime()}.mp4")
            try {
                val local = registry(ctx).getString("${Kind.VIDEO.name}_${media.awemeId}", null)?.let { Uri.parse(it) }
                val usedLocal = local != null && runCatching { r.setDataSource(ctx, local) }.isSuccess
                if (!usedLocal) {
                    setStep("מוריד פריים באיכות מלאה")
                    val options = MediaResolver.rankVideoOptions(media.videoOptions, Prefs.DOWNLOAD_QUALITY_MAX, Prefs.VIDEO_CODEC_H264)
                    downloadToTemp(options, temp)
                    r.setDataSource(temp.absolutePath)
                }
                val bmp = r.getFrameAtTime(timeUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST)
                    ?: r.getFrameAtTime(timeUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: throw IllegalStateException("no frame")
                val out = java.io.ByteArrayOutputStream()
                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
                XLog.log("Downloader: HD cover ${bmp.width}x${bmp.height} @${timeUs / 1000}ms (local=$usedLocal)")
                bmp.recycle()
                return out.toByteArray()
            } finally {
                runCatching { r.release() }
                temp.delete()
            }
        }

        fun finish(saved: Saved, detail: String) {
            toast(ctx, "✅ ${kind.label} נשמר • $detail")
            if (nm == null) return
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(saved.uri, saved.mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            val share = Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType(saved.mime).putExtra(Intent.EXTRA_STREAM, saved.uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "שתף"
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            builder.setContentTitle("✅ ${kind.label} נשמר")
                .setContentText("${saved.displayName} • $detail")
                .setSubText(null)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setProgress(0, 0, false)
                .setOngoing(false)
                .setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(ctx, id, view, flags))
                .addAction(android.R.drawable.ic_menu_share, "שתף", PendingIntent.getActivity(ctx, id + 100000, share, flags))
            runCatching { nm?.notify(id, builder.build()) }
        }

        fun fail(reason: String) {
            toast(ctx, "❌ הורדת ה${kind.label} נכשלה: $reason")
            if (nm == null) return
            builder.setContentTitle("❌ ההורדה נכשלה").setContentText(reason).setProgress(0, 0, false)
                .setOngoing(false).setAutoCancel(true).setSmallIcon(android.R.drawable.stat_notify_error)
            runCatching { nm?.notify(id, builder.build()) }
        }
    }

    private fun simpleDownload(context: Context, kind: Kind, media: MediaResolver.AwemeMedia, urls: List<String>, tag: String) {
        val ctx = context.applicationContext ?: context
        if (urls.isEmpty()) return toast(ctx, "⚠️ לא נמצאה ${kind.label} בפוסט הזה")
        toast(ctx, "${kind.emoji} TikMod: מוריד ${kind.label} HD…")
        executor.execute {
            val job = Job(ctx, kind, media.authorNickname.ifBlank { "TikTok" })
            runCatching { job.fetchAndSave(urls, buildFileName(ctx, media, kind, tag), media.authorUniqueId) }
                .onSuccess { job.finish(it, formatSize(it.bytes)); addHistory(ctx, kind, media, it, tag) }
                .onFailure { job.fail(it.message ?: "error") }
        }
    }

    private fun rawDownload(context: Context, kind: Kind, url: String, title: String) {
        val ctx = context.applicationContext ?: context
        if (url.isBlank()) return
        executor.execute {
            val job = Job(ctx, kind, title)
            val clean = if (url.contains("/playwm/")) url.replace("/playwm/", "/play/") else url
            // Deleted chat media can be either a photo or a video: magic bytes decide (strict = false).
            runCatching { job.fetchAndSave(listOf(clean), sanitize("${title}_${timestamp()}"), "", strict = false) }
                .onSuccess { job.finish(it, formatSize(it.bytes)); addHistoryRaw(ctx, kind, title, "", it, "") }
                .onFailure { job.fail(it.message ?: "error") }
        }
    }

    // ---------------------------------------------------------------- storage

    private fun baseDir(ctx: Context, kind: Kind, mime: String): Pair<String, Uri?> {
        val location = Prefs.getSaveLocation(ctx)
        val isAudio = mime.startsWith("audio")
        val isImage = mime.startsWith("image")
        val api29 = Build.VERSION.SDK_INT >= 29
        if (location == Prefs.SAVE_LOCATION_DOWNLOAD) {
            return Environment.DIRECTORY_DOWNLOADS to (if (api29) MediaStore.Downloads.EXTERNAL_CONTENT_URI else null)
        }
        return when {
            isAudio -> Environment.DIRECTORY_MUSIC to (if (api29) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI else null)
            isImage -> (if (location == Prefs.SAVE_LOCATION_DCIM) Environment.DIRECTORY_DCIM else Environment.DIRECTORY_PICTURES) to
                (if (api29) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else null)
            mime.startsWith("video") -> (if (location == Prefs.SAVE_LOCATION_DCIM) Environment.DIRECTORY_DCIM else Environment.DIRECTORY_MOVIES) to
                (if (api29) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else null)
            else -> Environment.DIRECTORY_DOWNLOADS to (if (api29) MediaStore.Downloads.EXTERNAL_CONTENT_URI else null)
        }
    }

    private fun relativeDir(ctx: Context, root: String, author: String): String {
        val sub = if (Prefs.isAuthorFolders(ctx) && author.isNotBlank()) "/" + sanitize("@$author") else ""
        return "$root/TikMod$sub"
    }

    private fun openOutput(ctx: Context, kind: Kind, displayName: String, mime: String, author: String): Pair<Uri, OutputStream> {
        val (root, collection) = baseDir(ctx, kind, mime)
        val rel = relativeDir(ctx, root, author)
        if (collection != null && Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(collection, values)
                ?: throw IllegalStateException("MediaStore insert failed")
            val out = ctx.contentResolver.openOutputStream(uri, "w")
                ?: run { discard(ctx, uri); throw IllegalStateException("cannot open output") }
            return uri to out
        }
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStorageDirectory(), rel).apply { mkdirs() }
        val file = File(dir, displayName)
        return Uri.fromFile(file) to FileOutputStream(file)
    }

    private fun publish(ctx: Context, uri: Uri, mime: String) {
        if (uri.scheme == "content" && Build.VERSION.SDK_INT >= 29) {
            ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } else if (uri.scheme == "file") {
            MediaScannerConnection.scanFile(ctx, arrayOf(uri.path), arrayOf(mime), null)
        }
    }

    private fun discard(ctx: Context, uri: Uri) {
        runCatching {
            if (uri.scheme == "content") ctx.contentResolver.delete(uri, null, null) else File(uri.path ?: return).delete()
        }
    }

    // ---------------------------------------------------------------- duplicates & history

    private fun registry(ctx: Context) = ctx.getSharedPreferences(REGISTRY, Context.MODE_PRIVATE)

    private fun isDuplicate(ctx: Context, kind: Kind, id: String): Boolean {
        if (!Prefs.isSkipDuplicates(ctx)) return false
        val stored = registry(ctx).getString("${kind.name}_$id", null) ?: return false
        val stillThere = runCatching {
            val uri = Uri.parse(stored)
            if (uri.scheme == "file") File(uri.path!!).exists()
            else ctx.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
        }.getOrDefault(false)
        if (!stillThere) {
            registry(ctx).edit().remove("${kind.name}_$id").apply()
            return false
        }
        toast(ctx, "♻️ ה${kind.label} הזה כבר הורד — כבה \"דילוג על כפילויות\" כדי להוריד שוב")
        return true
    }

    private fun markDownloaded(ctx: Context, kind: Kind, id: String, uri: Uri) {
        runCatching { registry(ctx).edit().putString("${kind.name}_$id", uri.toString()).apply() }
    }

    private fun addHistory(ctx: Context, kind: Kind, media: MediaResolver.AwemeMedia, saved: Saved, detail: String) =
        addHistoryRaw(ctx, kind, media.desc.ifBlank { media.awemeId }, media.authorUniqueId, saved, detail, media.webUrl)

    private fun addHistoryRaw(ctx: Context, kind: Kind, title: String, author: String, saved: Saved, detail: String, link: String = "") {
        if (!Prefs.isDownloadHistory(ctx)) {
            XLog.log("History: disabled in settings, skipping")
            return
        }
        runCatching {
            val entry = JSONObject()
                .put("kind", kind.name).put("title", title.take(120)).put("author", author)
                .put("uri", saved.uri.toString()).put("mime", saved.mime).put("size", saved.bytes)
                .put("name", saved.displayName).put("detail", detail).put("link", link)
                .put("time", System.currentTimeMillis())
            val res = ctx.contentResolver.call(Uri.parse(Prefs.PROVIDER_URI), "add_history", entry.toString(), null)
            XLog.log("History: saved ${kind.name} -> ${res?.getBoolean("success")}")
        }.onFailure { XLog.log("History: provider call failed: ${it.javaClass.simpleName} ${it.message}") }
    }

    // ---------------------------------------------------------------- helpers

    private fun copyCaptionIfEnabled(ctx: Context, media: MediaResolver.AwemeMedia) {
        if (!Prefs.isCopyCaption(ctx) || media.desc.isBlank()) return
        setClipboard(ctx, "TikTok caption", media.desc)
    }

    private fun setClipboard(ctx: Context, label: String, text: String) {
        mainHandler.post {
            runCatching {
                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText(label, text))
            }
        }
    }

    fun buildFileName(ctx: Context, media: MediaResolver.AwemeMedia, kind: Kind, extra: String): String {
        val pattern = Prefs.getCustomFilenamePattern(ctx)
        var name = pattern
            .replace("{id}", media.awemeId)
            .replace("{author}", media.authorUniqueId.ifBlank { "TikTok" })
            .replace("{nickname}", media.authorNickname.ifBlank { media.authorUniqueId })
            .replace("{desc}", media.desc.replace(Regex("#\\S+"), "").trim().take(40))
            .replace("{date}", timestamp())
            .replace("{quality}", if (kind == Kind.VIDEO) extra else "")
            .replace("{type}", kind.name.lowercase())
        if (!pattern.contains("{id}") && !pattern.contains("{date}")) name += "_" + media.awemeId
        when (kind) {
            Kind.PHOTO -> name += "_$extra"
            Kind.AUDIO -> name += "_audio"
            Kind.COVER -> name += "_cover"
            Kind.AVATAR -> name = sanitize("${media.authorUniqueId.ifBlank { "user" }}_avatar_${timestamp()}")
            else -> {}
        }
        return sanitize(name)
    }

    /** Keeps Hebrew / any letters; strips only what file systems reject. */
    private fun sanitize(raw: String): String =
        raw.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .replace(Regex("\\s+"), " ").replace(Regex("_+"), "_")
            .trim(' ', '_', '.').take(120).ifBlank { "TikTok_${timestamp()}" }

    private fun timestamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun open(url: String, readTimeoutMs: Int = 30_000): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = readTimeoutMs
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "com.zhiliaoapp.musically/2024707030 (Linux; U; Android ${Build.VERSION.RELEASE}; ${Locale.getDefault()}; ${Build.MODEL}; Cronet/TTNetVersion)")
        conn.setRequestProperty("Accept", "*/*")
        conn.setRequestProperty("Referer", "https://www.tiktok.com/")
        conn.connect()
        return conn
    }

    private fun InputStream.readAtLeast(buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val n = read(buf, off, buf.size - off)
            if (n < 0) break
            off += n
        }
        return off
    }

    /** Detects the real container from magic bytes; falls back to Content-Type. */
    private fun sniff(h: ByteArray, n: Int, contentType: String?, kind: Kind): Pair<String, String> {
        fun at(i: Int, s: String) = n >= i + s.length && String(h, i, s.length, Charsets.ISO_8859_1) == s
        return when {
            n >= 3 && h[0] == 0xFF.toByte() && h[1] == 0xD8.toByte() -> "jpg" to "image/jpeg"
            at(1, "PNG") -> "png" to "image/png"
            at(0, "RIFF") && at(8, "WEBP") -> "webp" to "image/webp"
            at(0, "GIF8") -> "gif" to "image/gif"
            at(4, "ftyp") && (at(8, "heic") || at(8, "heix") || at(8, "mif1")) -> "heic" to "image/heic"
            at(4, "ftyp") && (at(8, "M4A ") || kind == Kind.AUDIO) -> "m4a" to "audio/mp4"
            at(4, "ftyp") || at(4, "moov") || at(4, "free") || at(4, "mdat") -> "mp4" to "video/mp4"
            at(0, "ID3") || (n >= 2 && h[0] == 0xFF.toByte() && (h[1].toInt() and 0xE0) == 0xE0) -> "mp3" to "audio/mpeg"
            at(0, "FLV") -> "flv" to "video/x-flv"
            contentType?.startsWith("image/") == true -> contentType.substringAfter('/').substringBefore(';') to contentType.substringBefore(';')
            contentType?.startsWith("audio/") == true -> "m4a" to "audio/mp4"
            kind == Kind.AUDIO -> "m4a" to "audio/mp4"
            kind == Kind.VIDEO -> "mp4" to "video/mp4"
            else -> "jpg" to "image/jpeg"
        }
    }

    private fun extractAudioTrack(input: File, output: File): Boolean {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        return try {
            extractor.setDataSource(input.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return false
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outTrack = muxer.addTrack(format)
            muxer.start()
            val size = runCatching { format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) }.getOrDefault(256 * 1024).coerceAtLeast(64 * 1024)
            val buffer = ByteBuffer.allocate(size)
            val info = MediaCodec.BufferInfo()
            while (true) {
                info.offset = 0
                info.size = extractor.readSampleData(buffer, 0)
                if (info.size < 0) break
                info.presentationTimeUs = extractor.sampleTime
                info.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                muxer.writeSampleData(outTrack, buffer, info)
                extractor.advance()
            }
            muxer.stop()
            output.length() > 0
        } catch (t: Throwable) {
            XLog.log("Downloader: extractAudioTrack ${t.message}")
            false
        } finally {
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun createChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "TikMod Downloads", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "התקדמות הורדות של TikMod"
                }
            )
        }
        // LIVE recording gets its own, more visible channel: a LOW channel lands in the collapsed
        // "silent" section where the Stop button is hidden. DEFAULT importance, but no sound/vibration.
        if (nm.getNotificationChannel(LIVE_CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(LIVE_CHANNEL_ID, "TikMod LIVE Recording", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "הקלטת שידור חי פעילה, עם כפתור עצירה"
                    setSound(null, null)
                    enableVibration(false)
                }
            )
        }
    }

    fun formatSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / (1L shl 20).toDouble())
        bytes >= 1L shl 10 -> String.format(Locale.US, "%d KB", bytes / (1L shl 10))
        else -> "$bytes B"
    }

    fun formatDuration(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
        else String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
    }

    fun toast(context: Context, message: String) {
        mainHandler.post { runCatching { Toast.makeText(context.applicationContext ?: context, message, Toast.LENGTH_SHORT).show() } }
    }
}
