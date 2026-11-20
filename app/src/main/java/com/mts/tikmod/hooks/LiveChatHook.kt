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
import de.robv.android.xposed.XposedBridge
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
    private var giftMessageClass: Class<*>? = null
    private val time = SimpleDateFormat("HH:mm:ss", Locale.US)

    /** One room's running transcript. */
    private class Buffer(val roomId: String, var owner: String, var title: String) {
        val seen = HashSet<Long>()
        val giftGroups = HashSet<String>()
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
    private var assetsModelClass: Class<*>? = null
    private val resourceTypes = HashMap<Int, String>()
    private val assetsSeen = HashSet<Long>()
    private val pendingAssets = JSONArray()

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
            // Gifts: GiftMessage shares the same base and render calls.
            XposedHelpers.findClassIfExists("com.bytedance.android.livesdk.model.message.GiftMessage", classLoader)?.let { gm ->
                giftMessageClass = gm
                val onGift = object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) { runCatching { onGiftShown(param.thisObject) } }
                }
                for (m in listOf("supportDisplayText", "canText")) hooked += hookAllMethodsSafely(gm, m, onGift)
            }
            XLog.log("LiveChat: hooked $hooked chat/gift render method(s)")
            hookGiftAssets(classLoader)
            hookLiveCaptcha(classLoader)
        }
    }

    /** Diagnostics only: notes when the LIVE server pushes its "verify you're human" check (see VerifyWatch). */
    private fun hookLiveCaptcha(cl: ClassLoader) {
        val msg = XposedHelpers.findClassIfExists("com.bytedance.android.livesdk.model.message.AccessControlMessage", cl) ?: return
        val dec = XposedHelpers.findClassIfExists("com.bytedance.android.livesdk.model.message._AccessControlMessage_ProtoDecoder", cl) ?: return
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val m = param.result?.takeIf { msg.isInstance(it) } ?: return
                runCatching {
                    val c = XposedHelpers.getObjectField(m, "captcha") ?: return
                    com.mts.tikmod.utils.VerifyWatch.onLiveCaptcha(
                        runCatching { XposedHelpers.getLongField(c, "roomId") }.getOrDefault(0L),
                        runCatching { XposedHelpers.getLongField(c, "verifyDurationInSec") }.getOrDefault(0L)
                    )
                }
            }
        }
        var n = 0
        dec.declaredMethods.filter { it.returnType == msg || it.returnType == Any::class.java }.forEach { XposedBridge.hookMethod(it, hook); n++ }
        XLog.log("LiveChat: watching LIVE access-control checks ($n)")
    }

    /**
     * Gift animations are "assets" (id = the gift's primaryEffectId) that TikTok downloads and plays over the
     * stream. We note each asset's type and download URLs as TikTok decodes them, so the recorder can fetch
     * the same animation and put it into the recording.
     */
    private fun hookGiftAssets(cl: ClassLoader) {
        val am = XposedHelpers.findClassIfExists("com.bytedance.android.livesdk.gift.assets.AssetsModel", cl) ?: run {
            XLog.log("LiveChat: AssetsModel not found"); return
        }
        assetsModelClass = am
        runCatching {
            listOf("MP4", "WEBP", "LOTTIE", "ANIMAX", "EFFECT_LOKI", "LYNX", "STICKER", "PREFAB_TEMPLATE", "EFFECT_STREAM_EXTRA")
                .forEach { n -> resourceTypes[XposedHelpers.getStaticIntField(am, "RESOURCE_TYPE_$n")] = n.lowercase() }
        }
        val onAsset = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                runCatching { onAsset(param.result?.takeIf { am.isInstance(it) } ?: param.thisObject) }
            }
        }
        var n = 0
        // The proto decoder's method that returns an AssetsModel (its name is obfuscated; its type isn't).
        XposedHelpers.findClassIfExists("com.bytedance.android.livesdk.gift.assets._AssetsModel_ProtoDecoder", cl)
            ?.declaredMethods?.filter { it.returnType == am }?.forEach { XposedBridge.hookMethod(it, onAsset); n++ }
        // And the moment TikTok picks how to play one.
        for (m in listOf("getResourceFormat", "getDowngradeResType")) n += hookAllMethodsSafely(am, m, onAsset)
        XLog.log("LiveChat: watching gift assets ($n hooks, types $resourceTypes)")
    }

    private fun onAsset(a: Any?) {
        a ?: return
        if (assetsModelClass?.isInstance(a) != true) return
        val id = runCatching { XposedHelpers.getLongField(a, "id") }.getOrDefault(0L)
        if (id == 0L) return
        val type = runCatching { XposedHelpers.getIntField(a, "resourceType") }.getOrDefault(-1)
        fun urls(field: String) = runCatching {
            val rm = XposedHelpers.getObjectField(a, field) ?: return@runCatching emptyList<String>()
            (XposedHelpers.getObjectField(rm, "urlList") as? List<*>).orEmpty().mapNotNull { it as? String }
        }.getOrDefault(emptyList())
        val list = urls("resourceModel")
        if (list.isEmpty()) return
        synchronized(lock) {
            if (!assetsSeen.add(id)) return
            if (assetsSeen.size <= 6) XLog.log("LiveChat: asset $id ${resourceTypes[type] ?: type} ${list.first().take(110)}")
            pendingAssets.put(JSONObject().put("id", id).put("type", resourceTypes[type] ?: "t$type")
                .put("urls", JSONArray(list)).put("hevc", JSONArray(urls("resourceByteVC1Model")))
                .put("md5", runCatching { XposedHelpers.getObjectField(a, "md5") as? String }.getOrNull().orEmpty()))
        }
        main.removeCallbacks(flushAssets)
        main.postDelayed(flushAssets, 3000)
    }

    private val flushAssets = Runnable {
        val ctx = Prefs.hostContext ?: return@Runnable
        val batch: String
        synchronized(lock) {
            if (pendingAssets.length() == 0) return@Runnable
            batch = pendingAssets.toString()
            while (pendingAssets.length() > 0) pendingAssets.remove(pendingAssets.length() - 1)
        }
        Thread({ runCatching { ctx.contentResolver.call(Uri.parse(Prefs.PROVIDER_URI), "gift_assets", batch, null) } }, "TikMod-GiftAssets").start()
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

    /** A gift TikTok is showing. Each step of a combo is its own message (repeatCount grows); grouped later. */
    private fun onGiftShown(msg: Any?) {
        msg ?: return
        if (!MediaContext.liveScreenVisible) return
        val ctx = Prefs.hostContext ?: return
        if (!Prefs.bool(ctx, Prefs.KEY_RECORD_CHAT)) return
        if (giftMessageClass?.isInstance(msg) != true) return
        val gift = runCatching { XposedHelpers.getObjectField(msg, "mGift") }.getOrNull() ?: return
        val name = (runCatching { XposedHelpers.getObjectField(gift, "name") }.getOrNull() as? String)?.trim()
        if (name.isNullOrBlank()) return
        val base = runCatching { XposedHelpers.callMethod(msg, "getBaseMessage") }.getOrNull()
        val roomId = base?.let { runCatching { XposedHelpers.getLongField(it, "roomId") }.getOrNull() }
            ?.takeIf { it > 0 }?.toString() ?: MediaContext.watchingNow()?.roomId ?: return
        val id = base?.let { runCatching { XposedHelpers.getLongField(it, "messageId") }.getOrNull() }?.takeIf { it != 0L } ?: return
        val count = runCatching { XposedHelpers.getIntField(msg, "repeatCount") }.getOrDefault(1).coerceAtLeast(1)
        val diamonds = runCatching { XposedHelpers.getIntField(gift, "diamondCount") }.getOrDefault(0)
        val effectId = runCatching { XposedHelpers.getLongField(gift, "primaryEffectId") }.getOrDefault(0L)
        val effect = effectId > 0
        val giftId = runCatching { XposedHelpers.getLongField(gift, "id") }.getOrDefault(0L)
        val groupId = runCatching { XposedHelpers.getLongField(msg, "groupId") }.getOrDefault(0L)
        val from = runCatching { XposedHelpers.getObjectField(msg, "fromUser") }.getOrNull()
        val nick = from?.let { u ->
            (runCatching { XposedHelpers.callMethod(u, "getNickName") }.getOrNull() as? String)?.takeIf { it.isNotBlank() }
                ?: (runCatching { XposedHelpers.callMethod(u, "getDisplayId") }.getOrNull() as? String)
        }.orEmpty()
        val uid = from?.let { runCatching { XposedHelpers.callMethod(it, "getId") }.getOrNull() }?.toString().orEmpty()
        val url = listOf("image", "icon").firstNotNullOfOrNull { f ->
            val img = runCatching { XposedHelpers.getObjectField(gift, f) }.getOrNull() ?: return@firstNotNullOfOrNull null
            (runCatching { XposedHelpers.callMethod(img, "getUrls") }.getOrNull() as? List<*>)
                ?.firstNotNullOfOrNull { (it as? String)?.takeIf { s -> s.startsWith("http") } }
        }.orEmpty()
        // A combo keeps its group id; a gift sent on its own still needs a unique group.
        val group = if (groupId != 0L) "$uid|$giftId|$groupId" else "m$id"
        val now = System.currentTimeMillis()

        synchronized(lock) {
            val b = buffers.getOrPut(roomId) {
                val live = MediaContext.room(roomId)
                Buffer(roomId, live?.owner.orEmpty(), live?.title.orEmpty())
            }
            if (!b.seen.add(id)) return
            if (b.giftGroups.add(group)) {
                b.sb.append('[').append(time.format(Date(now))).append("] 🎁 ")
                if (nick.isNotBlank()) b.sb.append(nick).append(": ")
                b.sb.append(name).append(if (count > 1) " ×$count" else "").append('\n')
                b.count++
            }
            pending.put(JSONObject().put("r", roomId).put("t", now).put("k", "g").put("n", nick).put("x", name)
                .put("c", count).put("d", diamonds).put("e", effect).put("u", url).put("g", group).put("i", id).put("p", effectId))
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
