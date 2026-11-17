package com.mts.tikmod.hooks

import android.content.Context
import android.telephony.TelephonyManager
import com.mts.tikmod.Prefs
import com.mts.tikmod.utils.XLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import org.json.JSONObject
import java.net.URI
import java.net.URL

class FrameworkJsonHook : BaseHook() {
    override val name: String = "Framework-Level System Interception"

    companion object {
        private val isInsideHook = ThreadLocal<Boolean>()
    }

    override fun apply(classLoader: ClassLoader, context: Context?) {
        tryHook {
            status = status.copy(foundClass = "Android SDK Framework & Network Interceptor")

            // Initialize/warm up PrefsCache in memory
            Prefs.refreshCache(context, force = true)

            // 1. Hook JSON Deserialization (JSONObject, GSON, OkHttp)
            hookJsonDeserialization(classLoader, context)

            // 2. Hook JSONObject Getter / Opt Methods
            hookJSONObjectGetters(context)

            // 3. Hook java.net.URL and java.net.URI for URL Redirection & Tracking
            hookUrlAndUri(context)

            // 4. Hook TelephonyManager for Region Lock Spoofing
            hookTelephonyManager(context)
        }
    }

    private fun transformJsonPayload(json: String, context: Context?): String {
        if (json.length < 10) return json
        if (!json.contains("download") &&
            !json.contains("play") &&
            !json.contains("aweme") &&
            !json.contains("is_ad") &&
            !json.contains("prevent_download") &&
            !json.contains("item_duet") &&
            !json.contains("copyright") &&
            !json.contains("enable_hd") &&
            !json.contains("read")
        ) return json

        var modified = json

        modified = modified.replace(Regex(""""prevent_download"\s*:\s*(true|[1-9]\d*)"""), """"prevent_download":0""")
        modified = modified.replace(Regex(""""is_download"\s*:\s*false"""), """"is_download":true""")
        modified = modified.replace(Regex(""""download_status"\s*:\s*[1-9]\d*"""), """"download_status":0""")
        modified = modified.replace(Regex(""""download_setting"\s*:\s*[1-9]\d*"""), """"download_setting":0""")
        modified = modified.replace(Regex(""""download_control"\s*:\s*[1-9]\d*"""), """"download_control":0""")
        modified = modified.replace(Regex(""""allow_download"\s*:\s*false"""), """"allow_download":true""")
        modified = modified.replace(Regex(""""is_ad"\s*:\s*true"""), """"is_ad":false""")
        modified = modified.replace(Regex(""""item_duet"\s*:\s*[1-9]\d*"""), """"item_duet":0""")
        modified = modified.replace(Regex(""""item_stitch"\s*:\s*[1-9]\d*"""), """"item_stitch":0""")
        modified = modified.replace(Regex(""""item_comment"\s*:\s*[1-9]\d*"""), """"item_comment":0""")

        // Unrestrict Regional Audio
        if (Prefs.isUnrestrictAudio(context)) {
            modified = modified.replace(Regex(""""copyright_restricted"\s*:\s*1"""), """"copyright_restricted":0""")
            modified = modified.replace(Regex(""""mute"\s*:\s*true"""), """"mute":false""")
        }

        // Unlock HD Uploads
        if (Prefs.isUnlockHDUploads(context)) {
            modified = modified.replace(Regex(""""enable_hd"\s*:\s*0"""), """"enable_hd":1""")
        }

        // Block Read Receipts in JSON
        if (Prefs.isDisableReadReceipts(context)) {
            modified = modified.replace(Regex(""""read_status"\s*:\s*[1-9]\d*"""), """"read_status":0""")
            modified = modified.replace(Regex(""""has_read"\s*:\s*true"""), """"has_read":false""")
            modified = modified.replace(Regex(""""is_read"\s*:\s*true"""), """"is_read":false""")
        }

        if (modified.contains("/playwm/")) {
            modified = modified.replace("/playwm/", "/play/")
        }

        return modified
    }

    private fun hookJsonDeserialization(classLoader: ClassLoader, context: Context?) {
        // 1. JSONObject Constructor
        runCatching {
            XposedHelpers.findAndHookConstructor(
                JSONObject::class.java,
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (isInsideHook.get() == true) return
                        isInsideHook.set(true)
                        try {
                            val json = param.args[0] as? String ?: return
                            val modified = transformJsonPayload(json, context)
                            if (modified != json) {
                                param.args[0] = modified
                                XLog.log("FrameworkJsonHook: Transformed JSONObject string payload!")
                            }
                        } catch (t: Throwable) {
                            // Silence
                        } finally {
                            isInsideHook.set(false)
                        }
                    }
                }
            )
        }

        // 2. Gson.fromJson(String, ...)
        runCatching {
            val gsonClass = XposedHelpers.findClassIfExists("com.google.gson.Gson", classLoader)
            if (gsonClass != null) {
                hookAllMethodsSafely(gsonClass, "fromJson", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (isInsideHook.get() == true) return
                        isInsideHook.set(true)
                        try {
                            if (param.args.isNotEmpty() && param.args[0] is String) {
                                val json = param.args[0] as String
                                val modified = transformJsonPayload(json, context)
                                if (modified != json) {
                                    param.args[0] = modified
                                    XLog.log("FrameworkJsonHook: Transformed Gson.fromJson payload!")
                                }
                            }
                        } catch (t: Throwable) {
                            // Silence
                        } finally {
                            isInsideHook.set(false)
                        }
                    }
                })
            }
        }

        // 3. JsonParser.parseString(String)
        runCatching {
            val jsonParserClass = XposedHelpers.findClassIfExists("com.google.gson.JsonParser", classLoader)
            if (jsonParserClass != null) {
                hookAllMethodsSafely(jsonParserClass, "parseString", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (isInsideHook.get() == true) return
                        isInsideHook.set(true)
                        try {
                            if (param.args.isNotEmpty() && param.args[0] is String) {
                                val json = param.args[0] as String
                                val modified = transformJsonPayload(json, context)
                                if (modified != json) {
                                    param.args[0] = modified
                                    XLog.log("FrameworkJsonHook: Transformed JsonParser.parseString payload!")
                                }
                            }
                        } catch (t: Throwable) {
                            // Silence
                        } finally {
                            isInsideHook.set(false)
                        }
                    }
                })
            }
        }

        // 4. OkHttp ResponseBody.string()
        runCatching {
            val responseBodyClass = XposedHelpers.findClassIfExists("okhttp3.ResponseBody", classLoader)
            if (responseBodyClass != null) {
                hookMethodSafely(responseBodyClass, "string", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (isInsideHook.get() == true) return
                        isInsideHook.set(true)
                        try {
                            val result = param.result as? String ?: return
                            val modified = transformJsonPayload(result, context)
                            if (modified != result) {
                                param.result = modified
                                XLog.log("FrameworkJsonHook: Transformed OkHttp ResponseBody payload!")
                            }
                        } catch (t: Throwable) {
                            // Silence
                        } finally {
                            isInsideHook.set(false)
                        }
                    }
                })
            }
        }
    }

    private fun hookJSONObjectGetters(context: Context?) {
        val booleanHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (isInsideHook.get() == true) return
                isInsideHook.set(true)
                try {
                    val key = param.args[0] as? String ?: return
                    when (key) {
                        "is_download", "can_download", "allow_download", "can_share",
                        "can_forward", "can_comment", "can_duet", "can_stitch", "enable_hd" -> {
                            param.result = true
                        }
                        "is_ad", "is_ads", "prevent_download", "copyright_restricted", "mute" -> {
                            param.result = false
                        }
                        "has_read", "is_read" -> {
                            if (Prefs.isDisableReadReceipts(context)) {
                                param.result = false
                            }
                        }
                    }
                } catch (t: Throwable) {
                    // Silence
                } finally {
                    isInsideHook.set(false)
                }
            }
        }

        hookAllMethodsSafely(JSONObject::class.java, "optBoolean", booleanHook)
        hookAllMethodsSafely(JSONObject::class.java, "getBoolean", booleanHook)

        val intHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (isInsideHook.get() == true) return
                isInsideHook.set(true)
                try {
                    val key = param.args[0] as? String ?: return
                    when (key) {
                        "prevent_download", "download_setting", "download_status",
                        "download_control", "get_download_setting", "copyright_restricted" -> {
                            param.result = 0
                        }
                        "read_status", "read_index" -> {
                            if (Prefs.isDisableReadReceipts(context)) {
                                param.result = 0
                            }
                        }
                    }
                } catch (t: Throwable) {
                    // Silence
                } finally {
                    isInsideHook.set(false)
                }
            }
        }

        hookAllMethodsSafely(JSONObject::class.java, "optInt", intHook)
        hookAllMethodsSafely(JSONObject::class.java, "getInt", intHook)

        val stringHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (isInsideHook.get() == true) return
                isInsideHook.set(true)
                try {
                    val key = param.args[0] as? String ?: ""
                    val result = param.result as? String ?: return

                    // Media targeting now comes from the exact Aweme (MediaContext); here we only clean URLs.
                    if (result.contains("/playwm/")) {
                        param.result = result.replace("/playwm/", "/play/")
                    }
                } catch (t: Throwable) {
                    // Silence
                } finally {
                    isInsideHook.set(false)
                }
            }
        }

        hookAllMethodsSafely(JSONObject::class.java, "optString", stringHook)
        hookAllMethodsSafely(JSONObject::class.java, "getString", stringHook)

        val jsonObjHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (isInsideHook.get() == true) return
                isInsideHook.set(true)
                try {
                    val key = param.args[0] as? String ?: return
                    val jsonObj = param.thisObject as? JSONObject ?: return

                    if (key == "download_addr") {
                        val downloadAddrObj = param.result as? JSONObject
                        val hasUrls = downloadAddrObj?.optJSONArray("url_list")?.let { it.length() > 0 } ?: false

                        if (!hasUrls) {
                            val playAddr = jsonObj.optJSONObject("play_addr") ?: jsonObj.optJSONObject("play_addr_h264")
                            if (playAddr != null) {
                                param.result = playAddr
                                XLog.log("FrameworkJsonHook: Replaced missing download_addr with clean play_addr!")
                            }
                        }
                    }
                } catch (t: Throwable) {
                    // Silence
                } finally {
                    isInsideHook.set(false)
                }
            }
        }

        hookAllMethodsSafely(JSONObject::class.java, "optJSONObject", jsonObjHook)
        hookAllMethodsSafely(JSONObject::class.java, "getJSONObject", jsonObjHook)
    }

    private fun hookUrlAndUri(context: Context?) {
        runCatching {
            XposedHelpers.findAndHookConstructor(
                URL::class.java,
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (isInsideHook.get() == true) return
                        isInsideHook.set(true)
                        try {
                            val spec = param.args[0] as? String ?: return
                            if (Prefs.isDisableReadReceipts(context) &&
                                (spec.contains("/im/v1/read") || spec.contains("/read_receipt") || spec.contains("mark_read") || spec.contains("/v1/read/"))) {
                                param.args[0] = "http://127.0.0.1/tikmod_blocked_read_receipt"
                                XLog.log("FrameworkJsonHook: Blocked network URL for read receipt!")
                                return
                            }
                            if (spec.contains("/playwm/")) {
                                val clean = spec.replace("/playwm/", "/play/")
                                param.args[0] = clean
                            }
                        } catch (t: Throwable) {
                            // Silence
                        } finally {
                            isInsideHook.set(false)
                        }
                    }
                }
            )
        }

        runCatching {
            XposedHelpers.findAndHookMethod(
                URI::class.java,
                "create",
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (isInsideHook.get() == true) return
                        isInsideHook.set(true)
                        try {
                            val str = param.args[0] as? String ?: return
                            if (Prefs.isDisableReadReceipts(context) &&
                                (str.contains("/im/v1/read") || str.contains("/read_receipt") || str.contains("mark_read") || str.contains("/v1/read/"))) {
                                param.args[0] = "http://127.0.0.1/tikmod_blocked_read_receipt"
                                XLog.log("FrameworkJsonHook: Blocked network URI for read receipt!")
                                return
                            }
                            if (str.contains("/playwm/")) {
                                val clean = str.replace("/playwm/", "/play/")
                                param.args[0] = clean
                            }
                        } catch (t: Throwable) {
                            // Silence
                        } finally {
                            isInsideHook.set(false)
                        }
                    }
                }
            )
        }
    }

    private fun hookTelephonyManager(context: Context?) {
        runCatching {
            val targetRegion = Prefs.getRegion(context)
            if (targetRegion.isNotBlank()) {
                val regionLower = targetRegion.lowercase()
                val regionHook = object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (isInsideHook.get() == true) return
                        isInsideHook.set(true)
                        try {
                            param.result = regionLower
                        } finally {
                            isInsideHook.set(false)
                        }
                    }
                }

                hookAllMethodsSafely(TelephonyManager::class.java, "getSimCountryIso", regionHook)
                hookAllMethodsSafely(TelephonyManager::class.java, "getNetworkCountryIso", regionHook)
                XLog.log("FrameworkJsonHook: TelephonyManager region spoofed to $regionLower")
            }
        }
    }
}
