package com.mts.tikmod.hooks

import android.content.Context
import com.mts.tikmod.Prefs
import com.mts.tikmod.utils.MediaContext
import com.mts.tikmod.utils.VerifyWatch
import com.mts.tikmod.utils.XLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.Collections

/**
 * Presence privacy at TikTok's HTTP layer (TTNet / retrofit2): every API call is built as a
 * `com.bytedance.retrofit2.client.Request`, whose constructor receives the full URL.
 *
 * A request that would report presence is pointed at an unroutable local address, so it fails on the phone
 * and never reaches TikTok. Rules are matched on the URL path only.
 *
 * Debug: `adb shell setprop debug.tikmod.netlog 1` logs every distinct request path (no query, no body).
 */
class PresenceHook : BaseHook() {
    override val name: String = "Presence Privacy (Online / Views)"

    private companion object {
        const val BLOCKED = "http://127.0.0.1:9/tikmod_blocked"
        val REQUEST_CLASSES = listOf("com.bytedance.retrofit2.client.Request")
    }

    private val seen: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private val netlog: Boolean by lazy {
        runCatching {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, "debug.tikmod.netlog") == "1"
        }.getOrDefault(false)
    }

    override fun apply(classLoader: ClassLoader, context: Context?) {
        tryHook {
            val cls = REQUEST_CLASSES.firstNotNullOfOrNull { XposedHelpers.findClassIfExists(it, classLoader) }
            status = status.copy(foundClass = cls?.name ?: "request class not found")
            if (cls == null) { XLog.log("Presence: request class not found"); return@tryHook }
            XposedBridge.hookAllConstructors(cls, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val i = param.args.indexOfFirst { it is String && (it.startsWith("http://") || it.startsWith("https://")) }
                    if (i < 0) return
                    val url = param.args[i] as String
                    val path = url.substringAfter("://").substringAfter('/', "").substringBefore('?')
                    val host = url.substringAfter("://").substringBefore('/')
                    if (netlog && seen.add(path)) XLog.log("NET $host /$path")
                    VerifyWatch.count(path)
                    if (VerifyWatch.isVerifyRequest(host, path)) VerifyWatch.onVerifyRequest(host, path)
                    if (netlog && path.startsWith("webcast/room/enter")) dump(param.args, url)
                    if (path.startsWith("webcast/room/enter")) roomId(param.args, url)?.let { MediaContext.onRoomEntered(it) }
                    else if (path.startsWith("webcast/room/leave")) roomId(param.args, url)?.let { MediaContext.onRoomLeft(it) }
                    val rule = matchRule(path) ?: return
                    param.args[i] = BLOCKED
                    if (seen.add("blocked:$path")) XLog.log("Presence: blocked /$path ($rule)")
                }
            })
            XLog.log("Presence: hooked ${cls.name} (${cls.declaredConstructors.size} constructors, netlog=$netlog)")
        }
    }

    /** room_id of an enter/leave request: in the form body, or in the query. */
    private fun roomId(args: Array<Any?>, url: String): String? = runCatching {
        val pattern = Regex("(?:^|[?&])room_id=(\\d+)")
        pattern.find(url)?.let { return@runCatching it.groupValues[1] }
        val body = args.filterNotNull().firstOrNull { a -> a.javaClass.methods.any { it.name == "writeTo" && it.parameterTypes.size == 1 } } ?: return@runCatching null
        val out = java.io.ByteArrayOutputStream()
        body.javaClass.methods.first { it.name == "writeTo" && it.parameterTypes.size == 1 }.invoke(body, out)
        pattern.find(out.toString("UTF-8"))?.groupValues?.get(1)
    }.getOrNull()

    private fun headerName(h: Any?): String? = runCatching {
        h?.javaClass?.methods?.firstOrNull { it.name == "getName" && it.parameterTypes.isEmpty() }?.invoke(h) as? String
    }.getOrNull()

    /** Research aid (netlog only): shape of a request — argument types, query keys, body keys. Long values are cut. */
    private fun dump(args: Array<Any?>, url: String) = runCatching {
        fun brief(kv: String): String {
            val k = kv.substringBefore('='); val v = kv.substringAfter('=', "")
            val secret = listOf("token", "sign", "ticket", "cookie", "x-", "device", "iid", "openudid", "cdid").any { k.lowercase().contains(it) }
            return k + "=" + if (secret) "…" else v.take(40)
        }
        XLog.log("ENTER args: " + args.joinToString { it?.javaClass?.name ?: "null" })
        XLog.log("ENTER headers: " + args.filterIsInstance<List<*>>().joinToString(" | ") { l -> l.joinToString(",") { h -> headerName(h) ?: "?" } })
        XLog.log("ENTER extra: " + args.filterIsInstance<Map<*, *>>().joinToString { m -> m.keys.joinToString(",") { it.toString() } }.take(300))
        XLog.log("ENTER strings: " + args.filterIsInstance<String>().filter { !it.startsWith("http") }.joinToString { it.take(30) })
        XLog.log("ENTER query: " + url.substringAfter('?', "").split('&').joinToString(" ") { brief(it) }.take(1500))
        args.filterNotNull().firstOrNull { a -> a.javaClass.methods.any { it.name == "writeTo" } }?.let { body ->
            val out = java.io.ByteArrayOutputStream()
            body.javaClass.methods.first { it.name == "writeTo" && it.parameterTypes.size == 1 }.invoke(body, out)
            val text = out.toString("UTF-8")
            XLog.log("ENTER body(${body.javaClass.name}): " + text.split('&').joinToString(" ") { brief(it) }.take(1500))
        }
    }.onFailure { XLog.log("ENTER dump failed: ${it.message}") }

    /** Name of the rule that blocks this path, or null. */
    private fun matchRule(path: String): String? {
        if (Prefs.bool(null, Prefs.KEY_HIDE_ONLINE_STATUS) && ONLINE_RULES.any { path.startsWith(it) }) return "online"
        if (Prefs.bool(null, Prefs.KEY_HIDE_VIEWS) && VIEW_RULES.any { path.startsWith(it) }) return "views"
        return null
    }

    // Paths observed on TikTok 47.7.3 (see the netlog switch above).
    //
    // Not possible from Java hooks: hiding that I entered a LIVE room (researched on 47.7.3).
    //  - The room only opens with a valid answer to POST webcast/room/enter/ (room_id in the form body),
    //    and that request is what registers the viewer.
    //  - Blocking it, or swapping it for webcast/room/info/?room_id=…, makes TikTok report an unstable
    //    connection and skip the stream.
    //  - Sending it without the account's identity isn't reachable here: no headers exist on the retrofit
    //    Request, and the session cookie is attached by Cronet's native cookie store (CookieHandler is null).

    /** My own "active now" report. activity_status/fetch (other people's status) is left alone. */
    private val ONLINE_RULES = listOf("tiktok/v1/activity_status/report")
    /** Per-video play reports (view counts, watch history). */
    private val VIEW_RULES = listOf("aweme/v1/aweme/stats/")
}
