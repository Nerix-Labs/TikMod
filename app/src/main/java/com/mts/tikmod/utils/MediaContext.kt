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

    data class LiveStream(val roomId: String, val title: String, val owner: String, val urls: List<String>)

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

    // ---- LIVE history: which stream is really being watched
    /** True while TikTok's LIVE room screen is in front (set from the activity lifecycle). */
    @Volatile var liveScreenVisible = false
    private class Watch(var first: Long, var last: Long)
    private val watching = ConcurrentHashMap<String, Watch>()
    private val recorded = ConcurrentHashMap<String, Boolean>()
    @Volatile private var lastPolledId: String? = null

    /**
     * TikTok also loads rooms it only previews or preloads. A room counts as "entered" when the player keeps
     * polling it for a few seconds in a row while the LIVE room screen is open.
     */
    private fun touch(id: String) {
        if (!liveScreenVisible) { watching.remove(id); return }
        val now = System.currentTimeMillis()
        val w = watching.getOrPut(id) { Watch(now, now) }
        if (now - w.last > 3000) w.first = now
        w.last = now
        if (now - w.first < 6000 || recorded.putIfAbsent(id, true) != null) return
        val live = rooms[id] ?: run { recorded.remove(id); return }
        if (watching.size > 32) watching.clear()
        val ctx = Prefs.hostContext ?: return
        if (!Prefs.bool(ctx, Prefs.KEY_LIVE_HISTORY)) return
        Thread({
            runCatching {
                val entry = org.json.JSONObject().put("room", id).put("handle", live.owner).put("title", live.title)
                    .put("time", System.currentTimeMillis())
                ctx.contentResolver.call(android.net.Uri.parse(Prefs.PROVIDER_URI), "add_live_history", entry.toString(), null)
                XLog.log("LiveHistory: entered @${live.owner}")
            }
        }, "TikMod-LiveHistory").start()
    }

    fun onLiveRoom(room: Any, streamUrl: Any?) {
        // Hot path: the player polls getStreamUrl() constantly on the same objects.
        if (lastRoomObj?.get() === room && lastStreamObj?.get() === streamUrl) { lastPolledId?.let { touch(it) }; return }
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
                urls = urls
            )
            lastRoomId = id
            lastPolledId = id
            touch(id)
            if (rooms.size > 64) rooms.keys.take(rooms.size - 64).forEach { rooms.remove(it) }
        }
    }

    /** The stream to record: the shared room if we know it, otherwise the most recently played one. */
    fun currentLive(): LiveStream? {
        liveShareRoomId?.let { rooms[it] }?.let { return it }
        return lastRoomId?.let { rooms[it] }
    }

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
