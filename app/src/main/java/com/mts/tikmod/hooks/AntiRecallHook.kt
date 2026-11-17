package com.mts.tikmod.hooks

import android.content.Context
import com.mts.tikmod.Prefs
import com.mts.tikmod.utils.Reflect
import com.mts.tikmod.utils.TikModDownloader
import com.mts.tikmod.utils.XLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import org.json.JSONObject
import java.io.File
import java.lang.reflect.Method
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Deleted (recalled) DMs stay readable.
 *
 * What TikTok 47.7.3 does on recall: the server pushes a new version of the message whose type/content is a
 * "recalled" placeholder, and the client marks it with ext "s:is_recalled"=1. Forcing isRecalled()=false
 * alone therefore shows "This message type isn't supported" — the original is already gone from the object.
 *
 * So we keep a small cache (memory + file in TikTok's files dir) of every message's original content and
 * type, keyed by server msgId. When a message turns up recalled, getContent()/getMsgType() hand back the
 * original, with a "נמחקה" tag for text messages. (Message model: obfuscated X.0CmB, readable getters.)
 */
class AntiRecallHook : BaseHook() {
    override val name: String = "Anti-Recall (Deleted Messages & Media)"

    /** [recalledAt] = minute-of-day when we first saw it deleted (-1 = not deleted yet). */
    private data class Original(val content: String, val type: Int, val recalledAt: Int = -1)

    private val cache = ConcurrentHashMap<String, Original>()
    private val savedMedia: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private val logged: MutableSet<String> = Collections.synchronizedSet(HashSet())
    @Volatile private var dirty = false
    @Volatile private var loaded = false
    private var isRecalledMethod: Method? = null
    private val inside = ThreadLocal<Boolean>()

    override fun apply(classLoader: ClassLoader, context: Context?) {
        tryHook {
            val msgClass = XposedHelpers.findClassIfExists("X.0CmB", classLoader)
                ?.takeIf { c -> c.declaredMethods.any { it.name == "isRecalled" } && c.declaredMethods.any { it.name == "getMsgId" } }
            status = status.copy(foundClass = msgClass?.name ?: "message model not found")
            if (msgClass == null) return@tryHook
            fun m(name: String) = msgClass.declaredMethods.firstOrNull { it.name == name && it.parameterTypes.isEmpty() }
            isRecalledMethod = m("isRecalled")
            DeletedBubble.install()
            DeletedBubble.installCustom(classLoader)

            // 1. Never hide a recalled bubble.
            m("isRecalled")?.let {
                XposedBridge.hookMethod(it, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.result != true || !Prefs.isAntiRecall(null)) return
                        // Only un-hide when we can show the original. Otherwise the server's placeholder
                        // (type 1805, empty content) would render as "message type not supported".
                        ensureLoaded()
                        val key = param.thisObject?.let { keyOf(it) } ?: return
                        if (cache.containsKey(key)) param.result = false
                    }
                })
            }
            // 2. Content: remember originals; give them back once the message is recalled.
            m("getContent")?.let {
                XposedBridge.hookMethod(it, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (inside.get() == true) return
                        inside.set(true)
                        try { onContent(param) } catch (_: Throwable) {} finally { inside.set(false) }
                    }
                })
            }
            // 3. Type: a recalled message gets a placeholder type; restore the original renderer.
            m("getMsgType")?.let {
                XposedBridge.hookMethod(it, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (inside.get() == true || !Prefs.isAntiRecall(null)) return
                        inside.set(true)
                        try {
                            val msg = param.thisObject ?: return
                            if (!wasRecalled(msg)) return
                            cache[keyOf(msg) ?: return]?.let { param.result = it.type }
                        } catch (_: Throwable) {} finally { inside.set(false) }
                    }
                })
            }
            XLog.log("AntiRecall: hooked ${msgClass.name} (isRecalled/getContent/getMsgType)")
        }
    }

    private fun onContent(param: XC_MethodHook.MethodHookParam) {
        val msg = param.thisObject ?: return
        val key = keyOf(msg) ?: return
        ensureLoaded()
        val content = param.result as? String
        if (!wasRecalled(msg)) {
            // Remember the original (only real content, and only once per message).
            if (!content.isNullOrBlank() && !cache.containsKey(key)) {
                val type = (Reflect.call(msg, "getMsgType") as? Number)?.toInt() ?: return
                cache[key] = Original(content, type)
                dirty = true
                if (cache.size > 3000) cache.keys.take(500).forEach { cache.remove(it) }
                scheduleSave()
            }
            return
        }
        if (!Prefs.isAntiRecall(null)) return
        var original = cache[key]
        if (original != null && original.recalledAt < 0) {
            // First time we see it deleted: remember when (shown in the bubble's tag, survives restarts).
            val now = java.util.Calendar.getInstance()
            original = original.copy(recalledAt = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE))
            cache[key] = original
            dirty = true
            scheduleSave()
        }
        if (logged.add(key)) {
            val nowType = runCatching { XposedBridge.invokeOriginalMethod(param.method.declaringClass.getDeclaredMethod("getMsgType"), msg, null) }.getOrNull()
            XLog.log("AntiRecall: recalled msg $key -> ${if (original != null) "restored (type=${original.type})" else "no cached original"} " +
                "[now type=$nowType content=${content?.take(80)}]")
        }
        if (original == null) return
        param.result = tagged(original.content, original.recalledAt)
        if (Prefs.isSaveDeletedMedia(null) && savedMedia.add(key)) saveMedia(original.content)
    }

    /** Original isRecalled() (our hook makes it return false). */
    private fun wasRecalled(msg: Any): Boolean {
        val ext = Reflect.call(msg, "getExt") as? Map<*, *>
        if (ext != null && (ext["s:is_recalled"] == "1" || ext["s:recalled"] == "true")) return true
        return runCatching { XposedBridge.invokeOriginalMethod(isRecalledMethod, msg, null) == true }.getOrDefault(false)
    }

    private fun keyOf(msg: Any): String? {
        val id = (Reflect.call(msg, "getMsgId") as? Number)?.toLong() ?: 0L
        if (id > 0) return id.toString()
        return (Reflect.call(msg, "getUuid") as? String)?.takeIf { it.isNotBlank() }
    }

    /**
     * Text messages are JSON {"text": …}. We prepend an invisible marker carrying the deletion time
     * (see [DeletedBubble]); every TextView showing it is restyled with a red "נמחקה" badge (drawn trash
     * sign, no emoji) — inline in the chat list, on its own line above the text in a tinted chat bubble.
     */
    private fun tagged(content: String, recalledAt: Int): String = runCatching {
        val json = JSONObject(content)
        val text = json.optString("text", "")
        if (text.isNotEmpty() && !text.startsWith(DeletedBubble.MARK)) json.put("text", DeletedBubble.encode(recalledAt) + text)
        json.toString()
    }.getOrDefault(content)

    // ---- persistence (TikTok's own files dir; survives restarts so old deleted messages still restore)

    private fun file(): File? = Prefs.hostContext?.filesDir?.let { File(it, "tikmod_msg_cache.json") }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        runCatching {
            val f = file() ?: return
            if (!f.exists()) return
            val json = JSONObject(f.readText())
            json.keys().forEach { k ->
                val o = json.optJSONObject(k) ?: return@forEach
                cache.putIfAbsent(k, Original(o.optString("c"), o.optInt("t"), o.optInt("r", -1)))
            }
            XLog.log("AntiRecall: loaded ${cache.size} cached messages")
        }
    }

    @Volatile private var saveScheduled = false
    private fun scheduleSave() {
        if (saveScheduled) return
        saveScheduled = true
        Thread({
            Thread.sleep(4000)
            saveScheduled = false
            if (!dirty) return@Thread
            dirty = false
            runCatching {
                val json = JSONObject()
                cache.forEach { (k, v) -> json.put(k, JSONObject().put("c", v.content).put("t", v.type).put("r", v.recalledAt)) }
                val f = file() ?: return@runCatching
                val tmp = File(f.parentFile, f.name + ".tmp")
                tmp.writeText(json.toString())
                tmp.renameTo(f)
            }
        }, "TikMod-MsgCache").start()
    }

    private fun saveMedia(content: String) {
        val ctx = Prefs.hostContext ?: return
        val urls = Regex("""https?:\\?/\\?/[^\s"'<>]+""").findAll(content).map { it.value.replace("\\/", "/") }
            .filter { u -> listOf("tos-", "tiktokcdn", "byteimg", "ibyteimg", ".jpg", ".jpeg", ".png", ".webp", ".mp4", ".heic").any { u.contains(it) } }
            .distinct().take(4).toList()
        if (urls.isEmpty()) return
        val time = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        urls.forEachIndexed { i, u -> TikModDownloader.downloadCover(ctx, u, "Deleted_${time}_$i") }
    }
}
