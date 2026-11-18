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

        /** Record a channel right now (from the TikMod app). The service looks up the stream itself. */
        fun recordNow(context: Context, handle: String) {
            val ctx = context.applicationContext
            runCatching {
                ContextCompat.startForegroundService(ctx, Intent(ctx, LiveWatchService::class.java)
                    .setAction(ACTION_RECORD).putExtra(EXTRA_HANDLE, handle))
            }
        }

        fun stop(context: Context, handle: String) {
            runCatching { context.startService(Intent(context, LiveWatchService::class.java).setAction(ACTION_STOP_REC).putExtra(EXTRA_HANDLE, handle)) }
        }

        fun stopAll(context: Context) {
            runCatching { context.startService(Intent(context, LiveWatchService::class.java).setAction(ACTION_STOP_ALL)) }
        }
        const val ACTION_RECORD = "com.mts.tikmod.action.RECORD_LIVE" // manual recording handed over from TikTok
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
    private val lock = Object()
    @Volatile private var alive = false
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
            }
        }
        // Save whatever an interrupted recording left behind, then start watching.
        thread(name = "TikMod-LiveSalvage") { runCatching { TikModDownloader.salvageInterruptedRecordings(this) } }
        thread(name = "TikMod-LiveWatch") { watchLoop() }
        XLog.log("AutoLive: service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_REC -> intent.getStringExtra(EXTRA_HANDLE)?.let { stopRecording(it) }
            ACTION_STOP_ALL -> sessions.keys.toList().forEach { stopRecording(it) }
            ACTION_RECORD -> {
                val handle = intent.getStringExtra(EXTRA_HANDLE)?.trim()?.removePrefix("@")?.lowercase().orEmpty()
                val urls = intent.getStringArrayListExtra(EXTRA_URLS).orEmpty()
                val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
                if (handle.isBlank()) return START_STICKY
                if (sessions.containsKey(handle)) {
                    TikModDownloader.toast(this, L.t(R.string.toast_already_recording, handle))
                } else if (urls.isNotEmpty()) {
                    manualStart(handle, handle, title, urls)
                } else {
                    // Started from the TikMod app: look the stream up ourselves.
                    pendingStarts.incrementAndGet()
                    thread(name = "TikMod-RecordNow") {
                        val room = runCatching { LiveApi.fetch(handle, Prefs.string(this, Prefs.KEY_AUTO_LIVE_QUALITY)) }.getOrNull()
                        when {
                            room == null -> TikModDownloader.toast(this, L.t(R.string.toast_user_not_found, handle))
                            !room.live || room.flvUrls.isEmpty() -> TikModDownloader.toast(this, L.t(R.string.toast_not_live, handle))
                            else -> manualStart(handle, room.nickname, room.title, room.flvUrls)
                        }
                        pendingStarts.decrementAndGet()
                        wake()
                    }
                }
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

    private fun watchLoop() {
        while (alive) {
            val autoOn = Prefs.bool(this, Prefs.KEY_AUTO_LIVE)
            val channels = if (autoOn) Prefs.autoLiveChannels(this) else emptyList()
            if (channels.isEmpty() && sessions.isEmpty() && pendingStarts.get() == 0) { stopSelf(); return }
            state.keys.retainAll(channels.toSet() + sessions.keys)
            for (h in channels) {
                if (!alive) return
                runCatching { check(h) }.onFailure { e ->
                    state.getOrPut(h) { JSONObject() }.put("error", e.message ?: "network").put("checked", System.currentTimeMillis())
                }
            }
            writeState()
            updateWatchNotification(channels.size)
            val interval = (Prefs.string(this, Prefs.KEY_AUTO_LIVE_INTERVAL).toLongOrNull() ?: 60L).coerceIn(15L, 900L)
            synchronized(lock) { if (alive) lock.wait(interval * 1000) }
        }
    }

    private fun check(handle: String) {
        val room = LiveApi.fetch(handle, Prefs.string(this, Prefs.KEY_AUTO_LIVE_QUALITY))
        val st = state.getOrPut(handle) { JSONObject() }
        st.put("checked", System.currentTimeMillis()).remove("error")
        if (room == null) { st.put("status", "missing"); return }
        st.put("nick", room.nickname).put("viewers", room.viewers).put("title", room.title)
        if (!room.live) {
            seenOffline.add(handle); skipUntilOffline.remove(handle); resumeAllowed.remove(handle)
            if (!sessions.containsKey(handle)) st.put("status", "offline")
            return
        }
        if (sessions.containsKey(handle)) return
        val trigger = Prefs.string(this, Prefs.KEY_AUTO_LIVE_TRIGGER)
        val allowed = trigger == Prefs.AUTO_LIVE_TRIGGER_ANY || handle in seenOffline || handle in resumeAllowed
        if (!allowed || handle in skipUntilOffline || room.flvUrls.isEmpty()) {
            st.put("status", if (handle in skipUntilOffline) "skipped" else "live")
            return
        }
        if (!hasSlot()) { st.put("status", "queued"); return }
        if (freeSpace() < MIN_FREE_TO_START) { st.put("status", "nospace"); return }
        startRecording(handle, room.nickname, room.title, room.flvUrls, manual = false)
    }

    // ------------------------------------------------------------------ recording

    private fun freeSpace() = runCatching { cacheDir.usableSpace }.getOrDefault(Long.MAX_VALUE)

    /** Parallel-recording limit from settings (0 = unlimited). */
    private fun hasSlot(): Boolean {
        val max = Prefs.string(this, Prefs.KEY_LIVE_MAX_PARALLEL).toIntOrNull() ?: 0
        return max <= 0 || sessions.size < max
    }

    private fun manualStart(handle: String, nickname: String, title: String, urls: List<String>) {
        if (!hasSlot()) { TikModDownloader.toast(this, L.t(R.string.toast_parallel_limit)); return }
        if (freeSpace() < MIN_FREE_TO_START) { TikModDownloader.toast(this, L.t(R.string.toast_no_space_start)); return }
        skipUntilOffline.remove(handle)
        startRecording(handle, nickname, title, urls, manual = true)
        TikModDownloader.toast(this, L.t(R.string.toast_recording_started, handle))
    }

    private fun startRecording(handle: String, nickname: String, title: String, initialUrls: List<String>, manual: Boolean) {
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
            this, s.notifId, Intent(this, LiveWatchService::class.java).setAction(ACTION_STOP_REC).putExtra(EXTRA_HANDLE, handle),
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
            try {
                TikModDownloader.captureLive(
                    this, owner = handle, title = title.ifBlank { "LIVE @$handle" },
                    urls = {
                        if (firstUrls) { firstUrls = false; initialUrls }
                        else runCatching { LiveApi.fetch(handle, Prefs.string(this, Prefs.KEY_AUTO_LIVE_QUALITY))?.flvUrls }.getOrNull()
                            ?.takeIf { it.isNotEmpty() } ?: initialUrls
                    },
                    isRunning = { alive && !s.stopped },
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
                if (progressMode) runCatching { nm.cancel(s.notifId) }
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
        val rec = sessions.keys.toList()
        val stopPi = PendingIntent.getService(
            this, 1, Intent(this, LiveWatchService::class.java).setAction(ACTION_STOP_SERVICE),
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
