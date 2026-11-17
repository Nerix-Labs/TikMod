package com.mts.tikmod.hooks

import android.content.Context
import com.mts.tikmod.Prefs
import com.mts.tikmod.utils.Reflect
import com.mts.tikmod.utils.XLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

/**
 * DM privacy for TikTok 47.7.x, at the single choke point every IM request goes through.
 *
 * Every IM call is a `com.bytedance.im.core.proto.Request` with a numeric `cmd`:
 *   mark read: 604 / 2002 / 613 / 1006 / 1007 / 1010   typing: 411   online/offline: 400 / 401
 *   send message: 100
 * Two senders exist (new coroutine RemoteFetcher + legacy WS/HTTP sender); both are hooked.
 *
 * Read-receipt modes:
 *  - always_block: mark-read requests never leave the phone
 *  - on_reply:     they are held per conversation and released right after I send a message there
 *  - normal:       untouched
 */
class ChatPrivacyHook : BaseHook() {
    override val name: String = "Chat Privacy (Seen / Typing / Online)"

    private companion object {
        val MARK_READ = setOf(604, 2002, 613, 1006, 1007, 1010)
        const val TYPING = 411
        val PRESENCE = setOf(400, 401)
        const val SEND_MESSAGE = 100

        // TikTok 47.7.3 senders (obfuscated, verified against the dex). Located by name + signature.
        const val NEW_SENDER = "X.0CfZ"; const val NEW_METHOD = "LLJJJJ"
        const val OLD_SENDER = "X.0CfD"; const val OLD_METHOD = "LJIIZILJ"
        const val TYPING_TIMER = "com.ss.android.ugc.aweme.im.typingindicator.timer.TypingStatusSenderTimer"
    }

    /** A blocked mark-read call, kept so it can be sent later ("seen after I reply"). */
    private class Held(val method: Member, val target: Any?, val args: Array<Any?>, val newPath: Boolean)
    private val held = ConcurrentHashMap<String, Held>()

    override fun apply(classLoader: ClassLoader, context: Context?) {
        tryHook {
            var found = 0
            XposedHelpers.findClassIfExists(NEW_SENDER, classLoader)?.declaredMethods
                ?.firstOrNull { it.name == NEW_METHOD && it.parameterTypes.size == 3 }
                ?.let { XposedBridge.hookMethod(it, senderHook(newPath = true)); found++ }
            XposedHelpers.findClassIfExists(OLD_SENDER, classLoader)?.declaredMethods
                ?.firstOrNull { it.name == OLD_METHOD && it.parameterTypes.size == 1 }
                ?.let { XposedBridge.hookMethod(it, senderHook(newPath = false)); found++ }

            // Typing: stop the timer that emits "typing…" events (belt and braces with cmd 411).
            XposedHelpers.findClassIfExists(TYPING_TIMER, classLoader)?.let { cls ->
                cls.declaredMethods.filter { it.parameterTypes.contentEquals(arrayOf(String::class.java)) && it.returnType == Void.TYPE }
                    .forEach { m ->
                        XposedBridge.hookMethod(m, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (Prefs.isDisableTypingIndicator(null)) param.result = null
                            }
                        })
                    }
                found++
            }
            status = status.copy(foundClass = "IM senders found: $found/3")
            XLog.log("ChatPrivacy: hooked $found/3 choke points")
        }
    }

    private fun senderHook(newPath: Boolean) = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val request = requestOf(param.args.firstOrNull()) ?: return
            val cmd = (Reflect.field(request, "cmd") as? Number)?.toInt() ?: return
            val block = when {
                cmd in MARK_READ -> when (Prefs.getReadReceiptsMode(null)) {
                    Prefs.READ_RECEIPTS_MODE_NORMAL -> false
                    Prefs.READ_RECEIPTS_MODE_ON_REPLY -> {
                        conversationOf(request)?.let { held[it] = Held(param.method, param.thisObject, param.args.clone(), newPath) }
                        true
                    }
                    else -> true
                }
                cmd == TYPING -> Prefs.isDisableTypingIndicator(null)
                cmd in PRESENCE -> Prefs.bool(null, Prefs.KEY_HIDE_ONLINE_STATUS)
                else -> false
            }
            if (!block) return
            XLog.log("ChatPrivacy: blocked cmd=$cmd (${if (newPath) "new" else "legacy"} sender)")
            if (newPath) param.throwable = java.io.IOException("TikMod: blocked IM cmd $cmd")
            else param.result = null
        }

        override fun afterHookedMethod(param: MethodHookParam) {
            // After I send a message, release the held "seen" for that conversation.
            val request = requestOf(param.args.firstOrNull()) ?: return
            val cmd = (Reflect.field(request, "cmd") as? Number)?.toInt() ?: return
            if (cmd != SEND_MESSAGE || Prefs.getReadReceiptsMode(null) != Prefs.READ_RECEIPTS_MODE_ON_REPLY) return
            val conv = conversationOf(request) ?: return
            val h = held.remove(conv) ?: return
            Thread({ replay(h, conv) }, "TikMod-SeenOnReply").start()
        }
    }

    private fun replay(h: Held, conv: String) {
        runCatching {
            val args = h.args.clone()
            if (h.newPath) {
                // Suspend function: give it a do-nothing continuation that shares the original context.
                val original = args[2]
                val type = (h.method as Method).parameterTypes[2]
                args[2] = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, m, a ->
                    when (m.name) {
                        "getContext" -> m.invoke(original)
                        "resumeWith" -> null
                        "toString" -> "TikModContinuation"
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> a?.getOrNull(0) === proxy
                        else -> null
                    }
                }
            }
            XposedBridge.invokeOriginalMethod(h.method, h.target, args)
            XLog.log("ChatPrivacy: released 'seen' for conversation $conv after reply")
        }.onFailure { XLog.log("ChatPrivacy: replay failed: ${it.message}") }
    }

    /** The proto Request inside the sender's argument (found by type, names are obfuscated). */
    private fun requestOf(arg: Any?): Any? {
        if (arg == null) return null
        if (arg.javaClass.name == "com.bytedance.im.core.proto.Request") return arg
        var c: Class<*>? = arg.javaClass
        while (c != null && c != Any::class.java) {
            c.declaredFields.firstOrNull { it.type.name == "com.bytedance.im.core.proto.Request" }?.let { f ->
                f.isAccessible = true
                return f.get(arg)
            }
            c = c.superclass
        }
        return null
    }

    /** conversation_id from whichever request body is set (mark-read, send-message, …). */
    private fun conversationOf(request: Any): String? {
        val body = Reflect.field(request, "body") ?: return null
        for (f in body.javaClass.declaredFields) {
            if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
            f.isAccessible = true
            val part = runCatching { f.get(body) }.getOrNull() ?: continue
            (Reflect.field(part, "conversation_id") as? String)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }
}
