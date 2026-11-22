package com.mts.tikmod.live

import com.mts.tikmod.R
import com.mts.tikmod.utils.L

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.mts.tikmod.MainActivity
import com.mts.tikmod.Prefs
import com.mts.tikmod.utils.LiveApi
import com.mts.tikmod.utils.TikModDownloader
import com.mts.tikmod.utils.XLog
import org.json.JSONObject
import java.net.HttpURLConnection
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Auto LIVE recorder.
 *
 * A foreground service in TikMod's own app (TikTok doesn't have to be open) that polls the chosen channels
 * and, when one goes live, records it with the same engine as the manual recorder (clean MP4 in the gallery).
 *
 *  - Trigger: "start" = only broadcasts that begin while we watch; "any" = also join one that's already running.
 *  - Notification: "progress" = ongoing notification with time + size and a Stop button;
 *    "minimal" = one "started" notification and the "saved" one at the end.
 *  - Runtime status for the settings screen is written to prefs ([Prefs.KEY_AUTO_LIVE_STATE]).
 */
class LiveWatchService : Service() {

    companion object {
        private const val WATCH_CHANNEL = "tikmod_live_watch"
        private const val EVENTS_CHANNEL = "tikmod_live_events"
        private const val REC_CHANNEL = "tikmod_live_recording"
        private const val WATCH_NOTIF_ID = 4300
        const val ACTION_STOP_REC = "com.mts.tikmod.action.AUTO_STOP_REC"
        const val ACTION_CHECK_NOW = "com.mts.tikmod.action.AUTO_CHECK_NOW"
        const val ACTION_STOP_SERVICE = "com.mts.tikmod.action.AUTO_STOP_SERVICE"
        const val ACTION_STOP_ALL = "com.mts.tikmod.action.STOP_ALL_REC"
        private const val MIN_FREE_TO_START = 1L shl 30        // 1 GB
        private const val MIN_FREE_WHILE_RECORDING = 300L shl 20 // 300 MB

        /** An Intent for this service, signed with the IPC token (the service is exported; see [Prefs.ipcToken]). */
        private fun cmd(ctx: Context, action: String): Intent =
            Intent(ctx, LiveWatchService::class.java).setAction(action).putExtra(Prefs.EXTRA_IPC_TOKEN, Prefs.ipcToken(ctx))

        /** Commands that start, stop or change something: only accepted with the token. */
        private val GUARDED = setOf("com.mts.tikmod.action.RECORD_LIVE", "com.mts.tikmod.action.LIVE_FOUND")

        /** Record a channel right now (from the TikMod app). The service looks up the stream itself. */
        fun recordNow(context: Context, handle: String) {
            val ctx = context.applicationContext
            runCatching {
                ContextCompat.startForegroundService(ctx, cmd(ctx, ACTION_RECORD).putExtra(EXTRA_HANDLE, handle))
            }
        }

        fun stop(context: Context, handle: String) {
            runCatching { context.startService(cmd(context, ACTION_STOP_REC).putExtra(EXTRA_HANDLE, handle)) }
        }

        fun stopAll(context: Context) {
            runCatching { context.startService(cmd(context, ACTION_STOP_ALL)) }
        }
        const val ACTION_RECORD = "com.mts.tikmod.action.RECORD_LIVE" // manual recording handed over from TikTok
        /** TikTok loaded the stream of a watched channel (private accounts you follow included): record it like an auto one. */
        const val ACTION_FOUND = "com.mts.tikmod.action.LIVE_FOUND"
        const val EXTRA_HANDLE = "handle"
        const val EXTRA_TITLE = "title"
        const val EXTRA_URLS = "urls"

        @Volatile var isRunning = false
            private set

        /** Start or stop the service so it matches the settings. */
        fun sync(context: Context) {
            val ctx = context.applicationContext
            val want = Prefs.bool(ctx, Prefs.KEY_AUTO_LIVE) && Prefs.autoLiveChannels(ctx).isNotEmpty()
            if (want) {
                runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, LiveWatchService::class.java).setAction(ACTION_CHECK_NOW)) }
                    .onFailure { XLog.log("AutoLive: can't start service: ${it.message}") }
            } else if (isRunning) {
                // Let the loop decide: it stops only when no recording (manual ones included) is running.
                runCatching { ctx.startService(Intent(ctx, LiveWatchService::class.java).setAction(ACTION_CHECK_NOW)) }
            }
        }

        fun checkNow(context: Context) {
            if (isRunning) runCatching { context.startService(Intent(context, LiveWatchService::class.java).setAction(ACTION_CHECK_NOW)) }
            else sync(context)
        }
    }

    private class Session(val handle: String, val notifId: Int) {
        @Volatile var stopped = false
        @Volatile var conn: HttpURLConnection? = null
        @Volatile var durationMs = 0L
        @Volatile var bytes = 0L
        @Volatile var title = ""
        val startedAt = System.currentTimeMillis()
    }

    private val sessions = ConcurrentHashMap<String, Session>()
    private val pendingStarts = java.util.concurrent.atomic.AtomicInteger(0)
    private val seenOffline: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private val skipUntilOffline: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private val resumeAllowed: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private val state = ConcurrentHashMap<String, JSONObject>()
    /** Stream addresses TikTok handed over lately, per channel (the web lookup has none for private accounts). */
    private val tiktokUrls = ConcurrentHashMap<String, List<String>>()
    private val privateNotified: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private val lock = Object()
    @Volatile private var alive = false
    private var backoff = 1L
    private var wakeLock: PowerManager.WakeLock? = null
    private var nextNotifId = 4400

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        startInForeground()
        isRunning = true
        alive = true
        // Channels that were being recorded when the process died (update, crash, low memory) resume
        // right away, even in "only when it starts" mode.
        runCatching {
            val prev = Prefs.readAutoLiveState(this)
            prev.keys().forEach { k ->
                val st = prev.optJSONObject(k)?.optString("status")
                if (st == "recording" || st == "saving") resumeAllowed.add(k)
                // Stopped by the user: stays stopped until that stream ends, also across restarts and updates.
                if (st == "skipped") skipUntilOffline.add(k)
                // Already told "private account is live" for this stream: no second notification after a restart.
                if (st == "private") privateNotified.add(k)
            }
        }
        // Save whatever an interrupted recording left behind, then start watching.
        thread(name = "TikMod-LiveWatch") {
            runCatching { resumeInterrupted() }.onFailure { XLog.log("AutoLive: resume failed: ${it.message}") }
            watchLoop()
        }
        XLog.log("AutoLive: service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action != null && (action in GUARDED || action == ACTION_STOP_REC || action == ACTION_STOP_ALL || action == ACTION_STOP_SERVICE) &&
            !Prefs.isValidIpcToken(this, intent?.getStringExtra(Prefs.EXTRA_IPC_TOKEN))) {
            XLog.log("AutoLive: ignored '$action' without a valid token")
            if (sessions.isEmpty() && !Prefs.bool(this, Prefs.KEY_AUTO_LIVE)) stopSelf()
            return START_STICKY
        }
        when (intent?.action) {
            ACTION_STOP_REC -> intent.getStringExtra(EXTRA_HANDLE)?.let { stopRecording(it) }
            ACTION_STOP_ALL -> sessions.keys.toList().forEach { stopRecording(it) }
            ACTION_RECORD -> {
                val handle = intent.getStringExtra(EXTRA_HANDLE)?.trim()?.removePrefix("@")?.lowercase().orEmpty()
                val urls = intent.getStringArrayListExtra(EXTRA_URLS).orEmpty()
                val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
                val roomId = intent.getStringExtra("room")
                if (handle.isBlank()) return START_STICKY
                if (sessions.containsKey(handle)) {
                    TikModDownloader.toast(this, L.t(R.string.toast_already_recording, handle))
                } else if (urls.isNotEmpty()) {
                    manualStart(handle, handle, title, urls, roomId)
                } else {
                    // Started from the TikMod app: look the stream up ourselves.
                    pendingStarts.incrementAndGet()
                    thread(name = "TikMod-RecordNow") {
                        val room = runCatching { LiveApi.fetch(handle, Prefs.liveQuality(this, handle)) }.getOrNull()
                        when {
                            room == null -> TikModDownloader.toast(this, L.t(R.string.toast_user_not_found, handle))
                            !room.live || room.flvUrls.isEmpty() -> TikModDownloader.toast(this, L.t(R.string.toast_not_live, handle))
                            else -> manualStart(handle, room.nickname, room.title, room.flvUrls, room.roomId)
                        }
                        pendingStarts.decrementAndGet()
                        wake()
                    }
                }
                wake()
            }
            ACTION_FOUND -> {
                val handle = intent.getStringExtra(EXTRA_HANDLE)?.trim()?.removePrefix("@")?.lowercase().orEmpty()
                val urls = intent.getStringArrayListExtra(EXTRA_URLS).orEmpty()
                if (handle.isNotBlank() && urls.isNotEmpty()) foundInTikTok(handle, intent.getStringExtra(EXTRA_TITLE).orEmpty(), urls, intent.getStringExtra("room"))
                wake()
            }
            ACTION_STOP_SERVICE -> {
                Prefs.setBool(this, Prefs.KEY_AUTO_LIVE, false)
                stopSelf()
            }
            else -> wake()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        alive = false
        isRunning = false
        sessions.values.forEach { it.stopped = true; runCatching { it.conn?.disconnect() } }
        wake()
        writeState()
        XLog.log("AutoLive: service stopped")
        super.onDestroy()
    }

    // ------------------------------------------------------------------ watching

    private fun wake() = synchronized(lock) { lock.notifyAll() }

    /**
     * After a restart (module update, crash, low memory): a recording that was cut off continues into the
     * same file when its channel is still live, so the result is one recording. Otherwise it's saved as it is.
     */
    private fun resumeInterrupted() {
        val auto = Prefs.autoLiveChannels(this).toSet()
        for (rec in TikModDownloader.interruptedRecordings(this)) {
            val handle = rec.owner.trim().removePrefix("@").lowercase()
            val room = if (handle == "live" || handle.isBlank()) null
            else runCatching { LiveApi.fetch(handle, Prefs.liveQuality(this, handle)) }.getOrNull()
            if (room != null && room.live && room.flvUrls.isNotEmpty() && !sessions.containsKey(handle)) {
                XLog.log("AutoLive: @$handle is still live — continuing the interrupted recording")
                TikModDownloader.toast(this, L.t(R.string.toast_rec_resuming, handle))
                startRecording(handle, room.nickname, rec.title.ifBlank { room.title }, room.flvUrls, manual = handle !in auto, resume = rec, roomId = room.roomId)
            } else {
                thread(name = "TikMod-LiveSalvage") { runCatching { TikModDownloader.saveInterrupted(this, rec) } }
            }
        }
    }

    private fun watchLoop() {
        while (alive) {
            val autoOn = Prefs.bool(this, Prefs.KEY_AUTO_LIVE)
            val channels = if (autoOn) Prefs.autoLiveChannels(this) else emptyList()
            if (channels.isEmpty() && sessions.isEmpty() && pendingStarts.get() == 0) { stopSelf(); return }
            state.keys.retainAll(channels.toSet() + sessions.keys)
            var failed = false
            for ((i, h) in channels.withIndex()) {
                if (!alive) return
                // Lookups are spaced out (a burst of identical requests every minute looks automated).
                if (i > 0) synchronized(lock) { if (alive) lock.wait(1500L + (Math.random() * 2500).toLong()) }
                runCatching { check(h) }.onFailure { e ->
                    failed = true
                    state.getOrPut(h) { JSONObject() }.put("error", e.message ?: "network").put("checked", System.currentTimeMillis())
                }
            }
            writeState()
            updateWatchNotification(channels.size)
            // Back off while TikTok's server refuses (rate limit / check page), up to 15 minutes; a little jitter always.
            backoff = if (failed) (backoff * 2).coerceAtMost(16) else 1
            val base = (Prefs.string(this, Prefs.KEY_AUTO_LIVE_INTERVAL).toLongOrNull() ?: 60L).coerceIn(30L, 900L)
            val interval = (base * backoff).coerceAtMost(900L) * 1000L
            val jitter = (interval * (0.85 + Math.random() * 0.3)).toLong()
            synchronized(lock) { if (alive) lock.wait(jitter) }
        }
    }

    private fun check(handle: String) {
        val room = LiveApi.fetch(handle, Prefs.liveQuality(this, handle))
        val st = state.getOrPut(handle) { JSONObject() }
        st.put("checked", System.currentTimeMillis()).remove("error")
        // Not visible to the web lookup (a private account): TikTok itself reports its LIVE (see foundInTikTok).
        if (room == null) { if (!sessions.containsKey(handle)) st.put("status", "missing"); return }
        st.put("nick", room.nickname).put("viewers", room.viewers).put("title", room.title)
        if (!room.live) {
            seenOffline.add(handle); skipUntilOffline.remove(handle); resumeAllowed.remove(handle)
            privateNotified.remove(handle); tiktokUrls.remove(handle)
            if (!sessions.containsKey(handle)) st.put("status", "offline")
            return
        }
        if (sessions.containsKey(handle)) return
        // Live, but its stream is only given to followers (private account): TikTok has to load it.
        if (room.flvUrls.isEmpty() && handle !in skipUntilOffline) {
            st.put("status", "private")
            if (privateNotified.add(handle)) notifyPrivateLive(handle, room.nickname)
            return
        }
        val trigger = Prefs.string(this, Prefs.KEY_AUTO_LIVE_TRIGGER)
        val allowed = trigger == Prefs.AUTO_LIVE_TRIGGER_ANY || handle in seenOffline || handle in resumeAllowed
        if (!allowed || handle in skipUntilOffline || room.flvUrls.isEmpty()) {
            st.put("status", if (handle in skipUntilOffline) "skipped" else "live")
            return
        }
        if (!hasSlot()) { st.put("status", "queued"); return }
        if (freeSpace() < MIN_FREE_TO_START) { st.put("status", "nospace"); return }
        startRecording(handle, room.nickname, room.title, room.flvUrls, manual = false, roomId = room.roomId)
    }

    /**
     * TikTok loaded the LIVE stream of a watched channel — while browsing, in the Following LIVE bar, or when you
     * opened it. That's the way private accounts you follow get recorded: their stream is only given to TikTok
     * (signed in as you), never to the web lookup. Same rules as the watcher: a recording you stopped stays
     * stopped, the parallel limit and free space apply.
     */
    private fun foundInTikTok(handle: String, title: String, urls: List<String>, roomId: String?) {
        tiktokUrls[handle] = urls
        if (!Prefs.bool(this, Prefs.KEY_AUTO_LIVE) || handle !in Prefs.autoLiveChannels(this)) return
        if (sessions.containsKey(handle) || handle in skipUntilOffline) return
        val st = state.getOrPut(handle) { JSONObject() }
        val prev = st.optString("status")
        val trigger = Prefs.string(this, Prefs.KEY_AUTO_LIVE_TRIGGER)
        // "Only when it starts": the web can't see a private account go live, so TikTok seeing it counts.
        val allowed = trigger == Prefs.AUTO_LIVE_TRIGGER_ANY || handle in seenOffline || handle in resumeAllowed ||
            prev == "private" || prev == "missing" || prev.isBlank()
        if (!allowed) return
        if (!hasSlot()) { st.put("status", "queued"); writeState(); return }
        if (freeSpace() < MIN_FREE_TO_START) { st.put("status", "nospace"); writeState(); return }
        XLog.log("AutoLive: TikTok shows @$handle live — recording")
        val nick = st.optString("nick").ifBlank { handle }
        runCatching { getSystemService(NotificationManager::class.java).cancel(privateNotifId(handle)) }
        startRecording(handle, nick, title, urls, manual = false, roomId = roomId)
    }

    private fun privateNotifId(handle: String) = 5200 + (handle.hashCode() and 0x3FF)

    /** A private account went live: one tap opens it in TikTok, and the recording starts by itself. */
    private fun notifyPrivateLive(handle: String, nickname: String) {
        val open = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.tiktok.com/@$handle/live"))
            .setPackage("com.zhiliaoapp.musically").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(this, privateNotifId(handle), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(this, EVENTS_CHANNEL)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle(L.t(R.string.notif_private_live, nickname.ifBlank { handle }))
            .setContentText(L.t(R.string.notif_private_live_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(L.t(R.string.notif_private_live_text)))
            .setContentIntent(pi).setAutoCancel(true).setCategory(NotificationCompat.CATEGORY_EVENT)
            .addAction(android.R.drawable.presence_video_online, L.t(R.string.notif_private_live_action), pi)
            .build()
        runCatching { getSystemService(NotificationManager::class.java).notify(privateNotifId(handle), n) }
        XLog.log("AutoLive: @$handle is live but private — asked to open it in TikTok")
    }

    // ------------------------------------------------------------------ recording

    private fun freeSpace() = runCatching { cacheDir.usableSpace }.getOrDefault(Long.MAX_VALUE)

    /** Parallel-recording limit from settings (0 = unlimited). */
    private fun hasSlot(): Boolean {
        val max = Prefs.string(this, Prefs.KEY_LIVE_MAX_PARALLEL).toIntOrNull() ?: 0
        return max <= 0 || sessions.size < max
    }

    private fun manualStart(handle: String, nickname: String, title: String, urls: List<String>, roomId: String? = null) {
        if (!hasSlot()) { TikModDownloader.toast(this, L.t(R.string.toast_parallel_limit)); return }
        if (freeSpace() < MIN_FREE_TO_START) { TikModDownloader.toast(this, L.t(R.string.toast_no_space_start)); return }
        skipUntilOffline.remove(handle)
        startRecording(handle, nickname, title, urls, manual = true, roomId = roomId)
        TikModDownloader.toast(this, L.t(R.string.toast_recording_started, handle))
    }

    private fun startRecording(
        handle: String, nickname: String, title: String, initialUrls: List<String>, manual: Boolean,
        resume: TikModDownloader.Interrupted? = null,
        roomId: String? = null
    ) {
        val s = Session(handle, nextNotifId++)
        s.title = title
        sessions[handle] = s
        resumeAllowed.add(handle) // a dropped connection while still live may resume
        val st = state.getOrPut(handle) { JSONObject() }
        st.put("status", "recording").put("since", s.startedAt).put("dur", 0).put("bytes", 0).put("manual", manual)
            .put("nick", nickname).put("title", title)
        writeState()
        acquireWakeLock()
        XLog.log("AutoLive: recording @$handle (${if (manual) "manual" else "auto"})")
        updateWatchNotification(Prefs.autoLiveChannels(this).size)

        val progressMode = manual || Prefs.string(this, Prefs.KEY_AUTO_LIVE_NOTIFY) != Prefs.AUTO_LIVE_NOTIFY_MINIMAL
        val nm = getSystemService(NotificationManager::class.java)
        val stopPi = PendingIntent.getService(
            this, s.notifId, cmd(this, ACTION_STOP_REC).putExtra(EXTRA_HANDLE, handle),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val who = nickname.ifBlank { handle }
        val builder = NotificationCompat.Builder(this, if (progressMode) REC_CHANNEL else EVENTS_CHANNEL)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle(L.t(R.string.notif_live_recording, who))
            .setContentIntent(openAppIntent())
            .addAction(android.R.drawable.ic_media_pause, L.t(R.string.notif_action_stop), stopPi)
        if (progressMode) {
            builder.setContentText(L.t(R.string.notif_connecting)).setSubText("@$handle")
                .setUsesChronometer(true).setWhen(s.startedAt)
                .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        } else {
            builder.setContentText(title.ifBlank { "@$handle" } + L.t(R.string.notif_saved_when_ends))
                .setAutoCancel(true).setCategory(NotificationCompat.CATEGORY_EVENT)
        }
        runCatching { nm.notify(s.notifId, builder.build()) }

        var firstUrls = true
        var lastStateWrite = 0L
        thread(name = "TikMod-AutoRec-$handle") {
            var saved = false
            try {
                saved = null != TikModDownloader.captureLive(
                    this, owner = handle, title = title.ifBlank { "LIVE @$handle" },
                    urls = {
                        if (firstUrls) { firstUrls = false; initialUrls }
                        else runCatching { LiveApi.fetch(handle, Prefs.liveQuality(this, handle))?.flvUrls }.getOrNull()
                            ?.takeIf { it.isNotEmpty() }
                            ?: tiktokUrls[handle]?.takeIf { it.isNotEmpty() } // private accounts: what TikTok last loaded
                            ?: initialUrls
                    },
                    isRunning = { alive && !s.stopped },
                    resume = resume,
                    roomId = roomId,
                    notifId = s.notifId,
                    onConn = { s.conn = it },
                    onProgress = { d, b ->
                        s.durationMs = d; s.bytes = b
                        st.put("dur", d).put("bytes", b)
                        val now = System.currentTimeMillis()
                        if (now - lastStateWrite > 2000) {
                            lastStateWrite = now; writeState()
                            if (freeSpace() < MIN_FREE_WHILE_RECORDING && !s.stopped) {
                                s.stopped = true
                                TikModDownloader.toast(this, L.t(R.string.toast_low_space_stop, handle))
                            }
                        }
                        if (progressMode) {
                            builder.setContentText("${TikModDownloader.formatDuration(d)}  •  ${TikModDownloader.formatSize(b)}")
                            runCatching { nm.notify(s.notifId, builder.build()) }
                        }
                    },
                    onSaving = {
                        if (progressMode) {
                            builder.setContentTitle(L.t(R.string.notif_saving_stream, who)).setContentText("${TikModDownloader.formatDuration(s.durationMs)} • ${TikModDownloader.formatSize(s.bytes)}")
                                .setProgress(0, 0, true).clearActions().setUsesChronometer(false)
                            runCatching { nm.notify(s.notifId, builder.build()) }
                        }
                        st.put("status", "saving"); writeState()
                    }
                )
            } catch (t: Throwable) {
                XLog.log("AutoLive: @$handle recording crashed: ${t.message}")
            } finally {
                // On success the same notification now reads "saved" (the save took it over): keep it.
                if (!saved) runCatching { nm.cancel(s.notifId) }
                sessions.remove(handle)
                if (s.stopped) { skipUntilOffline.add(handle); resumeAllowed.remove(handle) }
                st.put("status", if (s.stopped) "skipped" else "offline").put("last", System.currentTimeMillis())
                    .put("lastDur", s.durationMs)
                writeState()
                if (sessions.isEmpty()) releaseWakeLock()
                updateWatchNotification(Prefs.autoLiveChannels(this).size)
                wake() // re-check right away: if it's still live (connection drop), resume
            }
        }
    }

    private fun stopRecording(handle: String) {
        val s = sessions[handle] ?: return
        s.stopped = true
        runCatching { s.conn?.disconnect() }
        TikModDownloader.toast(this, L.t(R.string.toast_stopping, handle))
    }

    // ------------------------------------------------------------------ state & notifications

    private fun writeState() {
        val json = JSONObject()
        state.forEach { (k, v) -> json.put(k, v) }
        json.put("_service", JSONObject().put("running", alive).put("ts", System.currentTimeMillis()))
        Prefs.writeAutoLiveState(this, json)
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).putExtra("tab", "live").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun watchNotification(count: Int): Notification {
        // Each recording has its own notification (recording -> saving -> saved). This one only says what the
        // watcher does; it lists recordings only when nothing is being watched (manual recordings alone).
        val rec = if (count > 0) emptyList() else sessions.keys.toList()
        val stopPi = PendingIntent.getService(
            this, 1, cmd(this, ACTION_STOP_SERVICE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, WATCH_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle(if (rec.isEmpty()) L.p(R.plurals.notif_watching, count) else L.p(R.plurals.notif_recording_count, rec.size))
            .setContentText(if (rec.isEmpty()) L.t(R.string.notif_watching_text) else rec.joinToString(" • ") { "@$it" })
            .setOngoing(true).setSilent(true).setShowWhen(false)
            .setContentIntent(openAppIntent())
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, L.t(R.string.notif_action_stop_watch), stopPi)
            .build()
    }

    private fun startInForeground() {
        val n = watchNotification(Prefs.autoLiveChannels(this).size)
        if (Build.VERSION.SDK_INT >= 34) startForeground(WATCH_NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(WATCH_NOTIF_ID, n)
    }

    private fun updateWatchNotification(count: Int) {
        runCatching { getSystemService(NotificationManager::class.java).notify(WATCH_NOTIF_ID, watchNotification(count)) }
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT < 26) return
        nm.createNotificationChannel(NotificationChannel(WATCH_CHANNEL, L.t(R.string.channel_watch), NotificationManager.IMPORTANCE_MIN).apply {
            description = L.t(R.string.channel_watch_desc)
        })
        nm.createNotificationChannel(NotificationChannel(EVENTS_CHANNEL, L.t(R.string.channel_events), NotificationManager.IMPORTANCE_HIGH).apply {
            description = L.t(R.string.channel_events_desc)
        })
        if (nm.getNotificationChannel(REC_CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(REC_CHANNEL, "TikMod LIVE Recording", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = L.t(R.string.channel_recording_desc)
                setSound(null, null); enableVibration(false)
            })
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TikMod:AutoLive").apply { setReferenceCounted(false); acquire(12 * 60 * 60 * 1000L) }
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
    }
}

/** Restarts the watcher after reboot / app update when it's enabled. */
class LiveBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        LiveWatchService.sync(context)
    }
}
