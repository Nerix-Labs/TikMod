package com.mts.tikmod.hooks

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.mts.tikmod.Prefs
import com.mts.tikmod.utils.MediaContext
import com.mts.tikmod.utils.TikModDownloader
import com.mts.tikmod.utils.XLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Proxy
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.WeakHashMap

/**
 * Records the chat of a LIVE stream you're watching.
 *
 * TikTok's live chat runs on a message pipeline whose public classes are not obfuscated:
 *   - `com.ss.ugc.live.sdk.message.MessageManagerFactory.get(...)` returns the room's `IMessageManager`;
 *   - `IMessageManager.addMessageListener(int, OnMessageListener)` delivers fully-built messages;
 *   - chat lines are `com.bytedance.android.livesdk.model.message.ChatMessage` (fields `content`, `userInfo`).
 *
 * We register a listener (never an interceptor by default, so the user's own chat can't be affected) and
 * collect the lines of the room that is on screen. When you leave the stream the transcript is saved as a
 * text file. This only works while TikTok is open on the room — the background auto-recorder can't see chat.
 */
class LiveChatHook : BaseHook() {
    override val name: String = "LIVE Chat Recording"

    private var chatMessageClass: Class<*>? = null
    private var chatTypeInt: Int = -1
    private val handledManagers: MutableSet<Any> = Collections.newSetFromMap(WeakHashMap())
    private val time = SimpleDateFormat("HH:mm:ss", Locale.US)

    /** One room's running transcript. */
    private class Buffer(val roomId: String, val owner: String, val title: String) {
        val seen = HashSet<Long>()
        val sb = StringBuilder()
        var count = 0
    }

    @Volatile private var buffer: Buffer? = null
    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())
    private val saveRunnable = Runnable { synchronized(lock) { val b = buffer; buffer = null; saveLocked(b) } }

    override fun apply(classLoader: ClassLoader, context: Context?) {
        tryHook {
            val cm = XposedHelpers.findClassIfExists("com.bytedance.android.livesdk.model.message.ChatMessage", classLoader)
            chatMessageClass = cm
            status = status.copy(foundClass = cm?.name ?: "ChatMessage not found")
            if (cm == null) { XLog.log("LiveChat: ChatMessage class not found"); return@tryHook }

            // Primary, manager-independent: TikTok calls these on a fully-built ChatMessage when it shows the
            // comment on the public screen. Robust across how the room's message pipeline was set up.
            val onRender = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) { runCatching { onIncoming(param.thisObject) } }
            }
            var hooked = 0
            for (name in listOf("supportDisplayText", "canText")) hooked += hookAllMethodsSafely(cm, name, onRender)
            XLog.log("LiveChat: hooked $hooked ChatMessage render method(s)")

            // Secondary: also tap the room's message pipeline when we can capture its manager.
            chatTypeInt = runCatching { (XposedHelpers.callMethod(cm.newInstance(), "getIntType") as? Int) ?: -1 }.getOrDefault(-1)
            XposedHelpers.findClassIfExists("com.ss.ugc.live.sdk.message.MessageManagerFactory", classLoader)?.let { factory ->
                XposedBridge.hookAllMethods(factory, "get", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val manager = param.result ?: return
                        runCatching { registerOn(manager, classLoader) }.onFailure { XLog.log("LiveChat: register failed: ${it.message}") }
                    }
                })
            }
        }
    }

    private fun registerOn(manager: Any, classLoader: ClassLoader) {
        synchronized(handledManagers) { if (!handledManagers.add(manager)) return }
        // An interceptor sees every message of the room and, returning false, never consumes any — so the
        // user's own chat is unaffected. (Subscribing by message type didn't deliver on this build.)
        val interceptor = XposedHelpers.findClassIfExists("com.ss.ugc.live.sdk.message.interfaces.IInterceptor", classLoader)
        if (interceptor != null) {
            val proxy = Proxy.newProxyInstance(classLoader, arrayOf(interceptor)) { _, method, args ->
                if (method.name == "onMessage") { runCatching { onIncoming(args?.getOrNull(0)) }; return@newProxyInstance false }
                if (method.returnType == java.lang.Boolean.TYPE) false else null
            }
            runCatching { XposedHelpers.callMethod(manager, "addInterceptor", proxy) }
                .onFailure { XLog.log("LiveChat: addInterceptor failed: ${it.message}") }
                .onSuccess { XLog.log("LiveChat: intercepting a room's chat") }
            return
        }
        // Fallback: subscribe to the chat message type with a listener.
        val onMessage = XposedHelpers.findClassIfExists("com.ss.ugc.live.sdk.message.interfaces.OnMessageListener", classLoader) ?: return
        if (chatTypeInt <= 0) return
        val listener = Proxy.newProxyInstance(classLoader, arrayOf(onMessage)) { _, method, args ->
            if (method.name == "onMessage") runCatching { onIncoming(args?.getOrNull(0)) }
            null
        }
        runCatching { XposedHelpers.callMethod(manager, "addMessageListener", chatTypeInt, listener) }
            .onSuccess { XLog.log("LiveChat: listening on a room's chat") }
    }


    /** Called on the message pipeline thread for every incoming message. */
    private fun onIncoming(msg: Any?) {
        msg ?: return
        val ctx = Prefs.hostContext ?: return
        if (!Prefs.bool(ctx, Prefs.KEY_RECORD_CHAT)) return
        if (chatMessageClass?.isInstance(msg) != true) return
        val live = MediaContext.watchingNow() ?: return   // only the room that is really on screen
        val content = (runCatching { XposedHelpers.getObjectField(msg, "content") }.getOrNull() as? String)?.trim()
        if (content.isNullOrBlank()) return
        val id = runCatching { XposedHelpers.callMethod(msg, "getMessageId") as? Long }.getOrNull() ?: content.hashCode().toLong()
        val nick = nickOf(msg)

        synchronized(lock) {
            // One buffer per viewing session. A multi-guest / music live reports many preloaded rooms, so the
            // "room on screen" flaps; keying by it would shatter the transcript into fragments. Instead we keep
            // all the chat shown on screen in one file, saved when the viewer leaves.
            var b = buffer
            if (b == null) { b = Buffer(live.roomId, live.owner, live.title); buffer = b }
            if (!b.seen.add(id)) return
            if (b.seen.size > 20000) { saveLocked(b); b = Buffer(live.roomId, live.owner, live.title); buffer = b }
            b.sb.append('[').append(time.format(Date())).append("] ")
            if (nick.isNotBlank()) b.sb.append(nick).append(": ")
            b.sb.append(content).append('\n')
            b.count++
        }
    }

    private fun nickOf(msg: Any): String = runCatching {
        val user = XposedHelpers.getObjectField(msg, "userInfo") ?: XposedHelpers.getObjectField(msg, "atUser") ?: return ""
        (XposedHelpers.callMethod(user, "getNickName") as? String)?.takeIf { it.isNotBlank() }
            ?: (XposedHelpers.callMethod(user, "getDisplayId") as? String).orEmpty()
    }.getOrDefault("")

    /**
     * The LIVE screen came to the front or left it. A live opens overlay activities (gift panel, comment box)
     * that briefly leave "LivePlay", so saving is debounced: only after the screen stays gone for a few
     * seconds — a real exit — is the transcript saved. A transient overlay cancels the pending save.
     */
    fun onScreen(visible: Boolean) {
        main.removeCallbacks(saveRunnable)
        if (!visible) main.postDelayed(saveRunnable, 6000)
    }

    /** Must hold [lock]. Saves a buffer on a background thread if it has anything. */
    private fun saveLocked(b: Buffer?) {
        if (b == null || b.count == 0) return
        XLog.log("LiveChat: saving @${b.owner} (${b.count} messages)")
        val ctx = Prefs.hostContext ?: return
        val header = "TikMod — LIVE chat @${b.owner}\n${b.title}\n${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())}\n${b.count} messages\n\n"
        val text = header + b.sb.toString()
        Thread({ runCatching { TikModDownloader.saveChat(ctx, b.owner, b.title, text) } }, "TikMod-ChatSave").start()
    }
}
