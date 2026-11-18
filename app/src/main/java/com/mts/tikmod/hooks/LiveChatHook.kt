package com.mts.tikmod.hooks

import android.content.Context
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

    override fun apply(classLoader: ClassLoader, context: Context?) {
        tryHook {
            val factory = XposedHelpers.findClassIfExists("com.ss.ugc.live.sdk.message.MessageManagerFactory", classLoader)
            chatMessageClass = XposedHelpers.findClassIfExists("com.bytedance.android.livesdk.model.message.ChatMessage", classLoader)
            status = status.copy(foundClass = factory?.name ?: "message factory not found")
            if (factory == null || chatMessageClass == null) { XLog.log("LiveChat: message classes not found"); return@tryHook }
            // The chat message type number, from a throwaway instance (the type is intrinsic to the class).
            chatTypeInt = runCatching { (XposedHelpers.callMethod(chatMessageClass!!.newInstance(), "getIntType") as? Int) ?: -1 }.getOrDefault(-1)
            XLog.log("LiveChat: chat type = $chatTypeInt")

            XposedBridge.hookAllMethods(factory, "get", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val manager = param.result ?: return
                    runCatching { registerOn(manager, classLoader) }.onFailure { XLog.log("LiveChat: register failed: ${it.message}") }
                }
            })
            XLog.log("LiveChat: watching ${factory.name}.get()")
        }
    }

    private fun registerOn(manager: Any, classLoader: ClassLoader) {
        synchronized(handledManagers) { if (!handledManagers.add(manager)) return }
        val onMessage = XposedHelpers.findClassIfExists("com.ss.ugc.live.sdk.message.interfaces.OnMessageListener", classLoader)
        if (onMessage != null && chatTypeInt > 0) {
            val listener = Proxy.newProxyInstance(classLoader, arrayOf(onMessage)) { _, method, args ->
                if (method.name == "onMessage") runCatching { onIncoming(args?.getOrNull(0)) }
                null
            }
            runCatching { XposedHelpers.callMethod(manager, "addMessageListener", chatTypeInt, listener) }
                .onFailure { XLog.log("LiveChat: addMessageListener failed: ${it.message}") }
                .onSuccess { XLog.log("LiveChat: listening on a room's chat") }
            return
        }
        // Fallback only: a pass-through interceptor (returns false = don't consume) when the type is unknown.
        val interceptor = XposedHelpers.findClassIfExists("com.ss.ugc.live.sdk.message.interfaces.IInterceptor", classLoader) ?: return
        val proxy = Proxy.newProxyInstance(classLoader, arrayOf(interceptor)) { _, method, args ->
            if (method.name == "onMessage") { runCatching { onIncoming(args?.getOrNull(0)) }; return@newProxyInstance false }
            if (method.returnType == java.lang.Boolean.TYPE) false else null
        }
        runCatching { XposedHelpers.callMethod(manager, "addInterceptor", proxy) }
            .onSuccess { XLog.log("LiveChat: intercepting a room's chat (fallback)") }
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
            var b = buffer
            if (b == null || b.roomId != live.roomId) {
                saveLocked(b)
                b = Buffer(live.roomId, live.owner, live.title).also { buffer = it }
            }
            if (!b.seen.add(id)) return
            if (b.seen.size > 8000) { saveLocked(b); b = Buffer(live.roomId, live.owner, live.title).also { buffer = it } }
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

    /** The LIVE screen left the foreground: save what was collected. */
    fun onScreen(visible: Boolean) {
        if (visible) return
        synchronized(lock) { val b = buffer; buffer = null; saveLocked(b) }
    }

    /** Must hold [lock]. Saves a buffer on a background thread if it has anything. */
    private fun saveLocked(b: Buffer?) {
        if (b == null || b.count == 0) return
        val ctx = Prefs.hostContext ?: return
        val header = "TikMod — LIVE chat @${b.owner}\n${b.title}\n${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())}\n${b.count} messages\n\n"
        val text = header + b.sb.toString()
        Thread({ runCatching { TikModDownloader.saveChat(ctx, b.owner, b.title, text) } }, "TikMod-ChatSave").start()
    }
}
