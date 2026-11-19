package com.mts.tikmod.hooks

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.mts.tikmod.Prefs
import com.mts.tikmod.utils.MediaContext
import com.mts.tikmod.utils.TikModDownloader
import com.mts.tikmod.utils.XLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records the chat of a LIVE stream you're watching.
 *
 * Chat lines are `com.bytedance.android.livesdk.model.message.ChatMessage` (not obfuscated). TikTok calls
 * `supportDisplayText()` / `canText()` on a fully-built message when it shows the comment, so hooking those
 * sees exactly the chat on screen without touching TikTok's message pipeline. Each message carries its own
 * room (`getBaseMessage().roomId`) and id (`messageId`), so lines are tied to the right stream even on
 * multi-guest lives.
 *
 * Every line goes two places:
 *  - a text transcript per room, saved to Download/TikMod when you leave the live;
 *  - TikMod's chat log (through the provider), so a LIVE recording of that room gets the chat burned in.
 * Only while TikTok is open on the stream — the background auto-recorder can't see chat.
 */
class LiveChatHook : BaseHook() {
    override val name: String = "LIVE Chat Recording"

    private var chatMessageClass: Class<*>? = null
    private val time = SimpleDateFormat("HH:mm:ss", Locale.US)

    /** One room's running transcript. */
    private class Buffer(val roomId: String, var owner: String, var title: String) {
        val seen = HashSet<Long>()
        val sb = StringBuilder()
        var count = 0
    }

    private val buffers = LinkedHashMap<String, Buffer>()
    private val pending = JSONArray()
    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())
    private val saveRunnable = Runnable { synchronized(lock) { buffers.values.forEach { saveLocked(it) }; buffers.clear() } }
    private val flushRunnable = Runnable { flush() }
    @Volatile private var sent = 0

    override fun apply(classLoader: ClassLoader, context: Context?) {
        tryHook {
            val cm = XposedHelpers.findClassIfExists("com.bytedance.android.livesdk.model.message.ChatMessage", classLoader)
            chatMessageClass = cm
            status = status.copy(foundClass = cm?.name ?: "ChatMessage not found")
            if (cm == null) { XLog.log("LiveChat: ChatMessage class not found"); return@tryHook }
            val onRender = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) { runCatching { onShown(param.thisObject) } }
            }
            var hooked = 0
            for (m in listOf("supportDisplayText", "canText")) hooked += hookAllMethodsSafely(cm, m, onRender)
            XLog.log("LiveChat: hooked $hooked ChatMessage render method(s)")
        }
    }

    /** A chat message TikTok is showing (called several times per message; deduplicated by id). */
    private fun onShown(msg: Any?) {
        msg ?: return
        if (!MediaContext.liveScreenVisible) return
        val ctx = Prefs.hostContext ?: return
        if (!Prefs.bool(ctx, Prefs.KEY_RECORD_CHAT)) return
        if (chatMessageClass?.isInstance(msg) != true) return
        val content = (runCatching { XposedHelpers.getObjectField(msg, "content") }.getOrNull() as? String)?.trim()
        if (content.isNullOrBlank()) return

        val base = runCatching { XposedHelpers.callMethod(msg, "getBaseMessage") }.getOrNull()
        val roomId = base?.let { runCatching { XposedHelpers.getLongField(it, "roomId") }.getOrNull() }
            ?.takeIf { it > 0 }?.toString() ?: MediaContext.watchingNow()?.roomId ?: return
        val id = base?.let { runCatching { XposedHelpers.getLongField(it, "messageId") }.getOrNull() }
            ?.takeIf { it != 0L } ?: (content.hashCode().toLong() * 31 + roomId.hashCode())
        val nick = nickOf(msg)
        val now = System.currentTimeMillis()

        synchronized(lock) {
            val b = buffers.getOrPut(roomId) {
                val live = MediaContext.room(roomId)
                Buffer(roomId, live?.owner.orEmpty(), live?.title.orEmpty())
            }
            if (!b.seen.add(id)) return
            if (b.owner.isBlank()) MediaContext.room(roomId)?.let { b.owner = it.owner; b.title = it.title }
            b.sb.append('[').append(time.format(Date(now))).append("] ")
            if (nick.isNotBlank()) b.sb.append(nick).append(": ")
            b.sb.append(content).append('\n')
            b.count++
            pending.put(JSONObject().put("r", roomId).put("t", now).put("n", nick).put("x", content).put("i", id))
        }
        main.removeCallbacks(flushRunnable)
        main.postDelayed(flushRunnable, 1500)
    }

    /** Sends the queued lines to TikMod's chat log, where the LIVE recorder reads them. */
    private fun flush() {
        val ctx = Prefs.hostContext ?: return
        val batch: String
        synchronized(lock) {
            if (pending.length() == 0) return
            batch = pending.toString()
            while (pending.length() > 0) pending.remove(pending.length() - 1)
        }
        if (sent < 3) { sent++; XLog.log("LiveChat: sending chat lines ${batch.take(120)}") }
        Thread({
            runCatching { ctx.contentResolver.call(Uri.parse(Prefs.PROVIDER_URI), "chat_lines", batch, null) }
                .onFailure { XLog.log("LiveChat: sending lines failed: ${it.message}") }
        }, "TikMod-ChatLines").start()
    }

    private fun nickOf(msg: Any): String = runCatching {
        val user = XposedHelpers.getObjectField(msg, "userInfo") ?: XposedHelpers.getObjectField(msg, "atUser") ?: return ""
        (XposedHelpers.callMethod(user, "getNickName") as? String)?.takeIf { it.isNotBlank() }
            ?: (XposedHelpers.callMethod(user, "getDisplayId") as? String).orEmpty()
    }.getOrDefault("")

    /**
     * The LIVE screen came to the front or left it. Starting a recording, the gift panel or the comment box
     * briefly leave the LIVE screen, so the transcript is only saved after it has been gone for a while.
     */
    fun onScreen(visible: Boolean) {
        main.removeCallbacks(saveRunnable)
        if (!visible) {
            flush()
            main.postDelayed(saveRunnable, 30_000)
        }
    }

    /** Must hold [lock]. Saves a buffer on a background thread if it has anything. */
    private fun saveLocked(b: Buffer) {
        if (b.count == 0) return
        val owner = b.owner.ifBlank { "live" }
        XLog.log("LiveChat: saving @$owner (${b.count} messages)")
        val ctx = Prefs.hostContext ?: return
        val header = "TikMod — LIVE chat @$owner\n${b.title}\n${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())}\n${b.count} messages\n\n"
        val text = header + b.sb.toString()
        Thread({ runCatching { TikModDownloader.saveChat(ctx, owner, b.title, text) } }, "TikMod-ChatSave").start()
    }
}
