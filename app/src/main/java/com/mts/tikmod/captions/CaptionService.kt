package com.mts.tikmod.captions

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.ModelDownloadListener
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.mts.tikmod.Prefs
import com.mts.tikmod.R
import com.mts.tikmod.utils.L
import com.mts.tikmod.utils.LiveApi
import com.mts.tikmod.utils.XLog
import kotlin.concurrent.thread

/**
 * Live captions for the LIVE stream that is on screen in TikTok.
 *
 * TikMod pulls the stream's audio itself ([AudioFeed]), runs it through the phone's on-device speech
 * recognizer, cuts the text into sentences ([Segmenter]) and translates them ([Translator]). Each caption is
 * sent back to TikTok as a broadcast, where [com.mts.tikmod.hooks.LiveCaptionHook] draws it over the stream.
 *
 * The hooked TikTok process starts the service when a LIVE room is entered, pings it while the room is on
 * screen and stops it on leaving. Without pings the service stops by itself.
 *
 * Spoken language: a fixed one from settings, or "auto": the language picked for this channel before,
 * otherwise the recognizer's own language detection, starting from the language used last.
 */
class CaptionService : Service(), RecognitionListener {

    companion object {
        const val ACTION_START = "com.mts.tikmod.action.CAPTIONS_START"
        const val ACTION_STOP = "com.mts.tikmod.action.CAPTIONS_STOP"
        const val ACTION_PING = "com.mts.tikmod.action.CAPTIONS_PING"
        const val ACTION_SOURCE = "com.mts.tikmod.action.CAPTIONS_SOURCE"     // extra "lang": picked on the stream
        const val ACTION_DOWNLOAD = "com.mts.tikmod.action.CAPTIONS_DOWNLOAD" // download the current speech pack
        const val ACTION_REFRESH = "com.mts.tikmod.action.CAPTIONS_REFRESH"   // a caption setting changed
        /** Broadcast to the TikTok process. Extras: room + (text, original, final) or (status, lang). */
        const val ACTION_CAPTION = "com.mts.tikmod.CAPTION"
        const val EXTRA_ROOM = "room"
        const val EXTRA_HANDLE = "handle"
        const val EXTRA_TITLE = "title"
        const val EXTRA_URLS = "urls"
        const val EXTRA_PACKAGE = "pkg"
        const val EXTRA_LANG = "lang"
        const val EXTRA_KEEP = "keep" // testing: don't require pings
        private const val CHANNEL = "tikmod_captions"
        private const val NOTIF_ID = 4290
        private const val PING_TIMEOUT_MS = 30_000L
        private const val STORE = "tikmod_captions"
        private const val PAUSE_MS = 1600L

        @Volatile var isRunning = false
            private set
        @Volatile private var current: CaptionService? = null
        /** Installed speech packs as last reported (asked once; forgotten when a pack is missing or downloaded). */
        @Volatile private var knownInstalled: Set<String> = emptySet()

        fun supported() = Build.VERSION.SDK_INT >= 33

        /** What was said in the stream being captioned, translated, one sentence per line. */
        fun transcript(): String = current?.let { s -> synchronized(s.lines) { s.lines.joinToString("\n") } }.orEmpty()

        fun command(context: Context, action: String, extras: Bundle?) {
            val ctx = context.applicationContext
            val intent = Intent(ctx, CaptionService::class.java).setAction(action).putExtras(extras ?: Bundle())
            runCatching {
                if (action == ACTION_START) ContextCompat.startForegroundService(ctx, intent)
                else if (isRunning) ctx.startService(intent)
            }.onFailure { XLog.log("Captions: can't reach the service: ${it.message}") }
        }

        private fun newRecognizer(context: Context): SpeechRecognizer =
            if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            else SpeechRecognizer.createSpeechRecognizer(context)

        /**
         * Which speech languages this phone can caption: [onResult] gets (installed, available to download),
         * as entries of [CaptionLanguages.SPEECH]. Call on the main thread.
         */
        fun querySupport(context: Context, onResult: (Set<String>, Set<String>) -> Unit) {
            if (!supported()) { onResult(emptySet(), emptySet()); return }
            runCatching {
                val rec = newRecognizer(context.applicationContext)
                rec.checkRecognitionSupport(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), context.mainExecutor, object : RecognitionSupportCallback {
                    override fun onSupportResult(s: RecognitionSupport) {
                        fun known(tags: List<String>) = tags.mapNotNull { CaptionLanguages.speechTag(it) }.toSet()
                        val installed = known(s.installedOnDeviceLanguages)
                        // Released first: the recognition service takes one client at a time.
                        runCatching { rec.destroy() }
                        onResult(installed, known(s.supportedOnDeviceLanguages + s.pendingOnDeviceLanguages) - installed)
                    }
                    override fun onError(error: Int) { runCatching { rec.destroy() }; onResult(emptySet(), emptySet()) }
                })
            }.onFailure { onResult(emptySet(), emptySet()) }
        }

        /** Downloads a speech pack. [onState]: 0..99 = progress, 100 = done, -1 = failed, -2 = Android took it over. */
        fun download(context: Context, tag: String, onState: (Int) -> Unit) {
            if (!supported()) { onState(-1); return }
            runCatching {
                val rec = newRecognizer(context.applicationContext)
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
                if (Build.VERSION.SDK_INT >= 34) {
                    rec.triggerModelDownload(intent, context.mainExecutor, object : ModelDownloadListener {
                        override fun onProgress(completedPercent: Int) = onState(completedPercent.coerceIn(0, 99))
                        override fun onSuccess() { onState(100); runCatching { rec.destroy() } }
                        override fun onScheduled() = onState(-2)
                        override fun onError(error: Int) { XLog.log("Captions: download of $tag failed ($error)"); onState(-1); runCatching { rec.destroy() } }
                    })
                } else {
                    rec.triggerModelDownload(intent)
                    onState(-2)
                }
            }.onFailure { onState(-1) }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val store by lazy { getSharedPreferences(STORE, Context.MODE_PRIVATE) }
    private var recognizer: SpeechRecognizer? = null
    private var feed: AudioFeed? = null
    private var readEnd: ParcelFileDescriptor? = null
    private var hostPackage = ""
    private var room = ""
    private var handle = ""
    private var hints = ArrayList<String>()
    /** True when the language may still change by detection (nothing was picked for this channel). */
    private var auto = true
    /** The language the recognizer is listening for right now. */
    @Volatile private var listening = "en-US"
    @Volatile private var target = "he"
    private var keep = false
    private var lastPing = 0L
    @Volatile private var generation = 0
    private var errorsInRow = 0
    private var sessionOpen = false
    private var downloading = false
    private var installed = emptySet<String>()
    private var heard: String? = null   // a different language the recognizer keeps reporting
    private var heardCount = 0
    private var heardSince = 0L
    private var hinted = false
    /** Translated sentences of this stream, oldest first. */
    private val lines = ArrayList<String>()

    private val segmenter = Segmenter { text, final -> caption(text, final) }
    private val pause = Runnable { if (segmenter.live.count { it == ' ' } >= 2) segmenter.flush() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, L.t(R.string.channel_captions), NotificationManager.IMPORTANCE_MIN)
            )
        }
        foreground()
        isRunning = true
        current = this
        translator.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent)
            ACTION_PING -> lastPing = System.currentTimeMillis()
            ACTION_SOURCE -> pickSource(intent.getStringExtra(EXTRA_LANG).orEmpty())
            ACTION_DOWNLOAD -> downloadPack()
            ACTION_REFRESH -> { target = targetLanguage(); foreground() }
            else -> shutdown()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        if (current === this) current = null
        generation++
        feed?.stop(); feed = null
        runCatching { recognizer?.destroy() }; recognizer = null
        runCatching { readEnd?.close() }; readEnd = null
        main.removeCallbacksAndMessages(null)
        translator.interrupt()
        super.onDestroy()
    }

    private fun shutdown() {
        XLog.log("Captions: stopped")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun targetLanguage() = Prefs.string(this, Prefs.KEY_CAPTION_TARGET).ifBlank { L.locale().toLanguageTag() }

    private fun foreground() {
        val stop = PendingIntent.getService(this, 3, Intent(this, CaptionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(L.t(R.string.notif_captions_title))
            .setContentText(if (handle.isBlank()) "" else "@$handle • ${CaptionLanguages.name(listening)} → ${CaptionLanguages.name(target)}")
            .setOngoing(true).setSilent(true).setShowWhen(false)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, L.t(R.string.notif_action_stop), stop)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIF_ID, n)
    }

    // ------------------------------------------------------------------ session

    private fun start(intent: Intent) {
        val newRoom = intent.getStringExtra(EXTRA_ROOM).orEmpty()
        val newHandle = intent.getStringExtra(EXTRA_HANDLE).orEmpty().trim().removePrefix("@").lowercase()
        lastPing = System.currentTimeMillis()
        keep = intent.getBooleanExtra(EXTRA_KEEP, false)
        if (feed != null && newRoom.isNotBlank() && newRoom == room) return // same stream: already on it
        generation++
        feed?.stop(); feed = null
        closeRecognizer()

        room = newRoom
        handle = newHandle
        hostPackage = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        target = targetLanguage()
        // Names the recognizer wouldn't guess: the channel and the words of the stream's title.
        hints = ArrayList((listOf(handle) + intent.getStringExtra(EXTRA_TITLE).orEmpty().split(Regex("[^\\p{L}\\p{N}']+")))
            .filter { it.length >= 4 }.distinct().take(20))
        val fixed = intent.getStringExtra(EXTRA_LANG)
            ?: store.getString("channel_$handle", null)?.takeIf { handle.isNotBlank() }
            ?: Prefs.string(this, Prefs.KEY_CAPTION_SOURCE).takeIf { it.isNotBlank() && it != CaptionLanguages.AUTO }
        auto = fixed == null
        listening = fixed ?: store.getString("last", null) ?: "en-US"
        errorsInRow = 0
        downloading = false
        heard = null
        hinted = false
        synchronized(lines) { lines.clear() }
        synchronized(jobLock) { finals.clear(); partial = null }
        foreground()

        if (!supported()) { status("unsupported"); shutdown(); return }
        val given = intent.getStringArrayListExtra(EXTRA_URLS).orEmpty()
        val gen = generation
        XLog.log("Captions: start @$handle room=$room listening=$listening auto=$auto target=$target urls=${given.size}")
        feed = AudioFeed(
            // TikTok's own addresses for this room when it handed them over; the public room lookup otherwise.
            urls = { given.ifEmpty { runCatching { LiveApi.fetch(handle, "ao")?.flvUrls }.getOrNull().orEmpty() } },
            onEnded = { main.post { if (gen == generation) { XLog.log("Captions: stream ended"); status("ended"); shutdown() } } }
        )
        status("starting")
        var begun = false
        fun begin(inst: Set<String>) {
            if (begun || gen != generation) return
            begun = true
            installed = inst
            // Automatic mode never starts on a language whose speech pack is missing.
            if (auto && inst.isNotEmpty() && listening !in inst) listening = if ("en-US" in inst) "en-US" else inst.first()
            main.postDelayed({ if (gen == generation) listen() }, 250)
        }
        if (knownInstalled.isNotEmpty()) begin(knownInstalled)
        else {
            querySupport(this) { inst, _ -> if (inst.isNotEmpty()) knownInstalled = inst; begin(inst) }
            // The recognition service doesn't always answer (e.g. while it closes the previous stream): go on without it.
            main.postDelayed({ begin(emptySet()) }, 1500)
        }
        watchdog(gen)
    }

    private fun watchdog(gen: Int) {
        main.postDelayed({
            if (gen != generation) return@postDelayed
            if (!keep && System.currentTimeMillis() - lastPing > PING_TIMEOUT_MS) { XLog.log("Captions: no viewer anymore"); shutdown(); return@postDelayed }
            watchdog(gen)
        }, 5000)
    }

    private fun closeRecognizer() {
        feed?.sink = null
        runCatching { recognizer?.destroy() }; recognizer = null
        sessionOpen = false
        main.removeCallbacks(pause)
        segmenter.finish("")
    }

    /** The viewer picked the spoken language on the stream ("auto" forgets the choice for this channel). */
    private fun pickSource(lang: String) {
        if (feed == null || lang.isBlank()) return
        val isAuto = lang == CaptionLanguages.AUTO
        if (handle.isNotBlank()) store.edit().apply { if (isAuto) remove("channel_$handle") else putString("channel_$handle", lang) }.apply()
        auto = isAuto
        if (!isAuto) switchTo(lang)
    }

    private fun switchTo(lang: String) {
        XLog.log("Captions: listening for $lang now")
        listening = lang
        store.edit().putString("last", lang).apply()
        errorsInRow = 0
        downloading = false
        closeRecognizer()
        foreground()
        status("starting")
        listen()
    }

    private fun downloadPack() {
        if (feed == null || downloading) return
        downloading = true
        val lang = listening
        val gen = generation
        status("download:0")
        download(this, lang) { state ->
            if (gen != generation || lang != listening) return@download
            when {
                state == 100 -> { downloading = false; knownInstalled = emptySet(); status("starting"); closeRecognizer(); listen() }
                state == -1 -> { downloading = false; status("download_failed") }
                // Android downloads it on its own schedule and doesn't report back: look again now and then.
                state == -2 -> { downloading = false; status("download_system"); again(20_000) }
                else -> status("download:$state")
            }
        }
    }

    /** Opens one recognition session reading from a fresh pipe. Sessions end by themselves; each end starts the next. */
    private fun listen() {
        if (Build.VERSION.SDK_INT < 33 || feed == null || sessionOpen) return
        val rec = recognizer ?: newRecognizer(this).also { it.setRecognitionListener(this); recognizer = it }
        runCatching { readEnd?.close() }
        val pipe = ParcelFileDescriptor.createPipe()
        readEnd = pipe[0] // startListening() hands it over asynchronously: closed when the next session opens
        feed?.sink = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, listening)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, AudioFeed.RATE)
            .putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
            // Punctuation and capitals: sentences can be told apart, which is what makes the translation readable.
            .putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY)
            // A transcript, not a keyboard: nothing is starred out.
            .putExtra(RecognizerIntent.EXTRA_MASK_OFFENSIVE_WORDS, false)
        if (hints.isNotEmpty()) intent.putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, hints)
        if (auto && Build.VERSION.SDK_INT >= 34) intent.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
        sessionOpen = true
        runCatching { rec.startListening(intent) }.onFailure { XLog.log("Captions: startListening failed: ${it.message}"); sessionOpen = false; again(2000) }
    }

    private fun again(delayMs: Long) {
        val gen = generation
        main.postDelayed({ if (gen == generation && !sessionOpen && !downloading) listen() }, delayMs)
    }

    // ------------------------------------------------------------------ recognizer callbacks (main thread)

    override fun onReadyForSpeech(params: Bundle?) { status("listening") }
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    private fun best(b: Bundle?) = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()

    override fun onPartialResults(partialResults: Bundle?) {
        errorsInRow = 0
        val text = best(partialResults)
        if (text.isEmpty()) return
        segmenter.partial(text)
        // No new words for a moment = the speaker paused: the line is finished.
        main.removeCallbacks(pause)
        main.postDelayed(pause, PAUSE_MS)
    }

    private fun finished(b: Bundle?) {
        errorsInRow = 0
        main.removeCallbacks(pause)
        segmenter.finish(best(b))
    }

    override fun onSegmentResults(segmentResults: Bundle) = finished(segmentResults)

    override fun onResults(results: Bundle?) {
        finished(results)
        sessionOpen = false
        again(100)
    }

    override fun onEndOfSegmentedSession() {
        sessionOpen = false
        again(100)
    }

    override fun onLanguageDetection(results: Bundle) {
        val detected = results.getString(SpeechRecognizer.DETECTED_LANGUAGE) ?: return
        val confidence = results.getInt(SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL)
        if (!auto || confidence < SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL_CONFIDENT) return
        val tag = CaptionLanguages.speechTag(detected) ?: return
        if (CaptionLanguages.sameLanguage(tag, listening)) { heard = null; return }
        // Single reports are often wrong (jingles, a few words): act only when the same language keeps coming.
        val now = System.currentTimeMillis()
        if (tag != heard) { heard = tag; heardCount = 0; heardSince = now }
        if (++heardCount < 4 || now - heardSince < 5000) return
        heard = null
        XLog.log("Captions: the stream sounds like $tag (installed: ${tag in installed})")
        if (tag in installed) {
            // Next time this channel starts right in its language.
            if (handle.isNotBlank()) store.edit().putString("channel_$handle", tag).apply()
            switchTo(tag)
        } else if (!hinted) {
            // Its speech pack isn't on the phone: keep going and let the viewer decide.
            hinted = true
            send(Bundle().apply { putString("status", "heard"); putString("lang", tag) })
        }
    }

    override fun onError(error: Int) {
        sessionOpen = false
        // 6 / 7 = nothing was said (music, silence): normal on a live stream.
        val quiet = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
        if (quiet) { again(100); return }
        errorsInRow++
        XLog.log("Captions: recognizer error $error (#$errorsInRow, language $listening)")
        when (error) {
            // The speech pack isn't on the phone: wait for the viewer (download, or another language).
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE, SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> {
                knownInstalled = emptySet()
                // Automatic mode: fall back to a language that works instead of stopping.
                if (auto && listening != "en-US") { XLog.log("Captions: no speech pack for $listening, back to en-US"); switchTo("en-US"); return }
                feed?.sink = null
                status(if (error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) "language_missing" else "language_unsupported")
                return
            }
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> { feed?.sink = null; status("permission"); return }
        }
        if (errorsInRow % 3 == 0) { runCatching { recognizer?.destroy() }; recognizer = null }
        again((500L * errorsInRow).coerceAtMost(5000))
    }

    // ------------------------------------------------------------------ translation + delivery

    private class Job(val text: String, val final: Boolean, val gen: Int, val from: String)
    private val jobLock = Object()
    /** Finished sentences wait in line and are all translated; of the growing one only the newest state matters. */
    private val finals = ArrayDeque<Job>()
    private var partial: Job? = null

    private fun caption(text: String, final: Boolean) {
        val job = Job(text, final, generation, listening)
        synchronized(jobLock) {
            if (final) { finals.addLast(job); if (finals.size > 5) finals.removeFirst(); partial = null }
            else partial = job
            jobLock.notifyAll()
        }
    }

    private val translator = thread(start = false, name = "TikMod-CaptionTranslate") {
        var last = 0L
        while (!Thread.currentThread().isInterrupted) {
            val j = try {
                synchronized(jobLock) {
                    while (finals.isEmpty() && partial == null) jobLock.wait()
                    finals.removeFirstOrNull() ?: partial!!.also { partial = null }
                }
            } catch (e: InterruptedException) { break }
            if (j.gen != generation) continue
            val shown = if (CaptionLanguages.sameLanguage(j.from, target)) j.text else try {
                // A sentence that is still growing is translated a couple of times a second at most.
                val wait = 450 - (System.currentTimeMillis() - last)
                if (!j.final && wait > 0) Thread.sleep(wait)
                last = System.currentTimeMillis()
                Translator.translate(j.text, j.from, target)
            } catch (e: InterruptedException) { break } catch (t: Throwable) {
                XLog.log("Captions: translation failed: ${t.message}")
                j.text
            }
            if (j.gen != generation) continue
            if (j.final) synchronized(lines) { lines.add(shown); if (lines.size > 400) lines.removeAt(0) }
            send(Bundle().apply { putString("text", shown); putString("original", j.text); putBoolean("final", j.final) })
        }
    }

    private fun status(s: String) {
        XLog.log("Captions: status $s")
        send(Bundle().apply { putString("status", s); putString("lang", listening) })
    }

    private fun send(extras: Bundle) {
        if (hostPackage.isBlank()) {
            // Started without a viewer (testing): the captions go to the log.
            extras.getString("text")?.let { XLog.log("CAPTION ${if (extras.getBoolean("final")) "■" else "…"} [${extras.getString("original")}] -> [$it]") }
            return
        }
        runCatching { sendBroadcast(Intent(ACTION_CAPTION).setPackage(hostPackage).putExtra("room", room).putExtras(extras)) }
    }
}
