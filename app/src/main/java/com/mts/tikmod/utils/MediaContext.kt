package com.mts.tikmod.utils

import com.mts.tikmod.Prefs

import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

/**
 * Knows exactly which post / live room the user is acting on.
 *
 * - [onAwemeSharePackage]: TikTok builds an `AwemeSharePackage` holding the real `Aweme`
 *   the instant the share sheet (or long-press menu) opens for that post.
 * - [onLiveRoom]: every live `Room` that resolves a stream is indexed by id, and the one
 *   being played most recently is remembered as the active room.
 */
object MediaContext {

    private const val SHARE_TTL_MS = 2 * 60 * 1000L

    @Volatile private var sharePackageRef: WeakReference<Any>? = null
    @Volatile private var shareTime = 0L
    @Volatile var shareIsLive = false
        private set
    @Volatile private var liveShareRoomId: String? = null

    /** @param light the same stream, lightest first (audio-only when the room has it): for live captions. */
    data class LiveStream(val roomId: String, val title: String, val owner: String, val urls: List<String>, val light: List<String> = emptyList())

    private val rooms = ConcurrentHashMap<String, LiveStream>()
    @Volatile private var lastRoomId: String? = null

    /** Per-sheet override of the smart-share mode (chip in the card); null = use the setting. */
    @Volatile var shareModeOverride: String? = null

    fun onAwemeSharePackage(pkg: Any) {
        if (sharePackageRef?.get() !== pkg) shareModeOverride = null
        sharePackageRef = WeakReference(pkg)
        shareTime = System.currentTimeMillis()
        shareIsLive = false
        XLog.log("MediaContext: share target -> ${runCatching { Reflect.string(currentAweme(), "getAid", "aid") }.getOrNull()}")
    }

    fun onLiveSharePackage(pkg: Any) {
        sharePackageRef = WeakReference(pkg)
        shareTime = System.currentTimeMillis()
        shareIsLive = true
        val extras = Reflect.field(pkg, "extras") as? android.os.Bundle
        @Suppress("DEPRECATION")
        val fromExtras = extras?.let { b ->
            listOf("room_id", "roomId", "live_room_id").firstNotNullOfOrNull { k -> b.get(k)?.toString() }
        }
        liveShareRoomId = fromExtras ?: (Reflect.field(pkg, "identifier") as? String)
        XLog.log("MediaContext: live share -> room=$liveShareRoomId")
    }

    /** True when a share/long-press sheet was opened recently (guards UI injection). */
    fun hasRecentShare(windowMs: Long = 8000L): Boolean =
        sharePackageRef?.get() != null && System.currentTimeMillis() - shareTime < windowMs

    /** The `Aweme` object the user opened the share sheet for. */
    fun currentAweme(): Any? {
        if (System.currentTimeMillis() - shareTime > SHARE_TTL_MS || shareIsLive) return null
        val pkg = sharePackageRef?.get() ?: return null
        return Reflect.field(pkg, "aweme") ?: Reflect.call(pkg, "LJJI")
    }

    fun currentMedia(): MediaResolver.AwemeMedia? = MediaResolver.resolve(currentAweme())

    @Volatile private var lastRoomObj: WeakReference<Any>? = null
    @Volatile private var lastStreamObj: WeakReference<Any>? = null

    // ---- which LIVE stream is really being watched
    /** True while TikTok's LIVE room screen is in front (set from the activity lifecycle). */
    @Volatile var liveScreenVisible = false
    /** The room TikTok last sent an "enter room" request for (and hasn't left since). */
    @Volatile private var enteredId: String? = null
    private val recorded = ConcurrentHashMap<String, Boolean>()
    private val mainHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    /**
     * TikTok asked its server to enter a LIVE room. Rooms it only previews or preloads never get this request,
     * and swiping to the next stream sends a new one, so this is the stream on screen.
     */
    fun onRoomEntered(id: String) {
        enteredId = id
        val now = System.currentTimeMillis()
        synchronized(enters) { enters.addLast(now); while (enters.size > 64) enters.removeFirst() }
        XLog.log("MediaContext: entered LIVE room $id (${entersSince(now - 60_000)} in the last minute)")
        // LIVE history: counts once the viewer stayed a few seconds.
        mainHandler.postDelayed({ if (enteredId == id && liveScreenVisible) addToHistory(id) }, 6000)
    }

    fun onRoomLeft(id: String) {
        if (enteredId == id) enteredId = null
    }

    private val enters = ArrayDeque<Long>()

    /** How many LIVE rooms TikTok entered since [since] (diagnostics for verification checks). */
    fun entersSince(since: Long): Int = synchronized(enters) { enters.count { it >= since } }

    /** The room on screen, for logs. */
    fun enteredRoom(): String? = enteredId

    private fun addToHistory(id: String) {
        val live = rooms[id] ?: return
        val ctx = Prefs.hostContext ?: return
        if (!Prefs.bool(ctx, Prefs.KEY_LIVE_HISTORY) || recorded.putIfAbsent(id, true) != null) return
        if (recorded.size > 200) recorded.clear()
        Thread({
            runCatching {
                val entry = org.json.JSONObject().put("room", id).put("handle", live.owner).put("title", live.title)
                    .put("time", System.currentTimeMillis())
                ctx.contentResolver.call(android.net.Uri.parse(Prefs.PROVIDER_URI), "add_live_history", entry.toString(), null)
                XLog.log("LiveHistory: entered @${live.owner}")
            }
        }, "TikMod-LiveHistory").start()
    }

    /** A LIVE room TikTok has loaded (its owner, title and stream addresses), by room id. */
    fun room(id: String): LiveStream? = rooms[id]

    /** The LIVE room that is on screen right now, or null (also until its stream addresses are known). */
    fun watchingNow(): LiveStream? = if (liveScreenVisible) enteredId?.let { rooms[it] } else null

    fun onLiveRoom(room: Any, streamUrl: Any?) {
        // Hot path: the player polls getStreamUrl() constantly on the same objects.
        if (lastRoomObj?.get() === room && lastStreamObj?.get() === streamUrl) return
        lastRoomObj = WeakReference(room)
        lastStreamObj = streamUrl?.let { WeakReference(it) }
        runCatching {
            val id = (Reflect.call(room, "getIdStr") as? String)
                ?: Reflect.long(room, "getId", "id").takeIf { it > 0 }?.toString()
                ?: return
            val urls = bestLiveUrls(streamUrl)
            if (urls.isEmpty()) return
            val owner = Reflect.get(room, "getOwner", "owner")
            rooms[id] = LiveStream(
                roomId = id,
                title = (Reflect.call(room, "getTitle") as? String).orEmpty(),
                owner = Reflect.string(owner, "getDisplayId", "displayId")
                    ?: Reflect.string(owner, "getNickName", "nickName") ?: "",
                urls = urls,
                light = (listOfNotNull(audioOnlyUrl(streamUrl)) + urls.reversed()).distinct()
            )
            lastRoomId = id
            if (rooms.size > 64) rooms.keys.take(rooms.size - 64).forEach { rooms.remove(it) }
        }
    }

    /** The stream to record: the shared room if we know it, otherwise the most recently played one. */
    fun currentLive(): LiveStream? {
        liveShareRoomId?.let { rooms[it] }?.let { return it }
        return lastRoomId?.let { rooms[it] }
    }

    /**
     * The audio-only variant ("ao") some rooms have. It's listed in the stream description JSON that the
     * stream object carries in one of its (obfuscated) fields, so the fields are searched for that JSON.
     */
    private fun audioOnlyUrl(streamUrl: Any?): String? = runCatching {
        val queue = ArrayDeque<Pair<Any, Int>>()
        queue.add((streamUrl ?: return null) to 0)
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 120) {
            val (obj, depth) = queue.removeFirst()
            var c: Class<*>? = obj.javaClass
            while (c != null && c != Any::class.java) {
                for (f in c.declaredFields) {
                    if (java.lang.reflect.Modifier.isStatic(f.modifiers) || f.type.isPrimitive) continue
                    val v = runCatching { f.isAccessible = true; f.get(obj) }.getOrNull() ?: continue
                    if (v is String) {
                        if (v.length > 200 && v.contains("\"ao\"") && v.contains("flv")) {
                            val url = org.json.JSONObject(v).optJSONObject("data")?.optJSONObject("ao")?.optJSONObject("main")?.optString("flv")
                            if (!url.isNullOrBlank() && url.startsWith("http")) return url
                        }
                    } else if (depth < 2 && !v.javaClass.name.startsWith("java.") && !v.javaClass.name.startsWith("android.") && !v.javaClass.isArray) {
                        queue.add(v to depth + 1)
                    }
                }
                c = c.superclass
            }
        }
        null
    }.getOrNull()

    /** FLV first (a raw FLV byte stream is a playable file), best resolution first. */
    private fun bestLiveUrls(streamUrl: Any?): List<String> {
        if (streamUrl == null) return emptyList()
        val out = ArrayList<String>()
        val order = listOf("ORIGION", "ORIGIN", "UHD", "FULL_HD1", "HD1", "SD1", "SD2", "LD")
        (Reflect.field(streamUrl, "flvPullUrl") as? Map<*, *>)?.let { map ->
            val keys = map.keys.map { it.toString() }
            val sorted = keys.sortedBy { k -> order.indexOfFirst { k.equals(it, true) }.let { if (it < 0) 99 else it } }
            sorted.forEach { k -> (map[k] as? String)?.takeIf { it.startsWith("http") }?.let(out::add) }
        }
        (Reflect.field(streamUrl, "rtmpPullUrl") as? String)?.takeIf { it.startsWith("http") }?.let(out::add)
        return out.distinct()
    }
}
