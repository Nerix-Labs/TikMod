package com.mts.tikmod.utils

import java.util.concurrent.ConcurrentHashMap

/**
 * Notes every time TikTok asks the user to prove they're human ("Verify you're not a robot"), with what TikMod
 * was doing at that moment, so the cause can be traced from logcat (`XLog`):
 *  - TikTok's captcha client fetching a challenge (requests to the verification host / captcha paths);
 *  - the LIVE room's own access-control captcha, pushed to the viewer as an `AccessControlMessage`.
 * It only observes; it never answers, hides or skips a check.
 *
 * `adb shell setprop debug.tikmod.netstat 1` also logs, every 30 s, how many requests TikTok made per path.
 */
object VerifyWatch {
    @Volatile private var last = 0L
    private val counts = ConcurrentHashMap<String, Int>()
    @Volatile private var countsSince = System.currentTimeMillis()

    val netstat: Boolean by lazy {
        runCatching {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, "debug.tikmod.netstat") == "1"
        }.getOrDefault(false)
    }

    fun isVerifyRequest(host: String, path: String): Boolean =
        host.contains("verification") || host.startsWith("rc-verification") || path.contains("captcha/") ||
            path.startsWith("captcha") || path.contains("/verify/get") || path.contains("verifycenter")

    fun onVerifyRequest(host: String, path: String) {
        val now = System.currentTimeMillis()
        if (now - last < 5_000) return
        last = now
        XLog.log("VERIFY: TikTok asked for a human check ($host/$path) — ${snapshot(now)}")
    }

    fun onLiveCaptcha(roomId: Long, durationSec: Long) {
        XLog.log("VERIFY: LIVE access-control captcha for room $roomId (${durationSec}s) — ${snapshot(System.currentTimeMillis())}")
    }

    private fun snapshot(now: Long): String =
        "live screen=${MediaContext.liveScreenVisible} room=${MediaContext.enteredRoom()} " +
            "rooms entered: ${MediaContext.entersSince(now - 60_000)}/1min ${MediaContext.entersSince(now - 600_000)}/10min, " +
            "captions=${CaptionGate.activeRoom ?: "off"} caption pulls ${CaptionGate.startsSince(now - 600_000)}/10min"

    /** Per-path request counter (netstat debug mode only). */
    fun count(path: String) {
        if (!netstat) return
        val key = path.trimEnd('/').split('/').take(4).joinToString("/")
        counts.merge(key, 1, Int::plus)
        val now = System.currentTimeMillis()
        if (now - countsSince >= 30_000) {
            val snap = counts.entries.sortedByDescending { it.value }.take(25).joinToString(" ") { "${it.key}=${it.value}" }
            counts.clear()
            XLog.log("NETSTAT ${(now - countsSince) / 1000}s: $snap")
            countsSince = now
        }
    }
}

/**
 * Keeps TikMod's LIVE captions from looking like a stream-scraping bot. Captions need the room's audio, which
 * TikMod reads with its own connection to the stream; done for every room the viewer swipes past, that is a burst
 * of extra stream pulls tied to the account. So a room only gets captions after the viewer settled on it, and new
 * pulls are spaced out.
 */
object CaptionGate {
    /** The viewer has to stay this long on a stream before TikMod opens its own connection to it. */
    const val DWELL_MS = 8_000L
    /** At most one new stream connection per this interval… */
    const val MIN_GAP_MS = 20_000L
    /** …and at most this many per 10 minutes. */
    const val MAX_PER_10_MIN = 6

    @Volatile var activeRoom: String? = null
    private val starts = ArrayDeque<Long>()

    fun startsSince(since: Long): Int = synchronized(starts) { starts.count { it >= since } }

    /** True if a new caption connection may be opened now (and records it). */
    fun tryStart(room: String, now: Long = System.currentTimeMillis()): Boolean = synchronized(starts) {
        while (starts.isNotEmpty() && starts.first() < now - 600_000) starts.removeFirst()
        if (starts.isNotEmpty() && now - starts.last() < MIN_GAP_MS) return false
        if (starts.size >= MAX_PER_10_MIN) return false
        starts.addLast(now)
        activeRoom = room
        true
    }
}
