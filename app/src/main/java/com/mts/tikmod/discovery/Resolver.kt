package com.mts.tikmod.discovery

import com.mts.tikmod.utils.XLog
import de.robv.android.xposed.XposedHelpers
import org.json.JSONObject
import org.luckypray.dexkit.DexKitBridge
import java.io.File
import java.lang.reflect.Method

/**
 * Finds TikTok's obfuscated classes and methods so hooks survive app updates.
 *
 * TikTok renames its obfuscated classes (X.0CmB, X.19NP, …) on every release. For each target we try, in order:
 *  1. the result cached for this exact TikTok build (cache key = the installed APK path, which changes on update);
 *  2. the names known from the version we developed against;
 *  3. a structural search of TikTok's dex with DexKit (readable method names, parameter and field types),
 *     run once per TikTok build and then cached.
 * Every step fails quietly; a hook whose target can't be found is simply skipped.
 */
object Resolver {
    @Volatile private var apkPath: String? = null
    @Volatile private var cacheFile: File? = null
    private var cache: JSONObject? = null
    private var bridges: List<DexKitBridge>? = null
    private var bridgeFailed = false
    @Volatile private var apkPaths: List<String> = emptyList()

    /** [splitDirs]: TikTok ships as split APKs and part of its code lives outside base.apk. */
    fun init(sourceDir: String?, dataDir: String?, splitDirs: Array<String>? = null) {
        apkPath = sourceDir
        // Feature splits carry code; "config" splits (language, density, ABI) never do.
        apkPaths = listOfNotNull(sourceDir) + splitDirs.orEmpty().filter { !File(it).name.contains("config.") }
        cacheFile = dataDir?.let { File(it, "files/tikmod_resolver.json") }
    }

    /**
     * Debug switch: `adb shell setprop debug.tikmod.forcesearch 1` skips the cache and the known names, so the
     * structural search can be tested on a TikTok version whose names are already known.
     */
    private val forceSearch: Boolean by lazy {
        runCatching {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
                .invoke(null, "debug.tikmod.forcesearch") == "1"
        }.getOrDefault(false)
    }

    // ------------------------------------------------------------------ public API

    /** A class: cached → known names → DexKit [search] (returns a class name). */
    fun findClass(key: String, cl: ClassLoader, known: List<String>, search: (DexKitBridge) -> String?): Class<*>? {
        if (!forceSearch) {
            cached(key)?.let { name -> load(name, cl)?.let { return it } }
            for (name in known) load(name, cl)?.let { remember(key, name); return it }
        }
        val found = runSearch(key) { bs -> bs.firstNotNullOfOrNull { b -> runCatching { search(b) }.getOrNull() } } ?: return null
        return load(found, cl)?.also { remember(key, found); XLog.log("Resolver: [$key] found by structure -> $found") }
    }

    /** Like [findClass] for targets that can legitimately be several classes (all of them are returned). */
    fun findClasses(key: String, cl: ClassLoader, known: List<String>, search: (DexKitBridge) -> List<String>): List<Class<*>> {
        if (!forceSearch) {
            cached(key)?.split(',')?.mapNotNull { load(it, cl) }?.takeIf { it.isNotEmpty() }?.let { return it }
            known.mapNotNull { load(it, cl) }.takeIf { it.isNotEmpty() }?.let { found ->
                remember(key, found.joinToString(",") { it.name }); return found
            }
        }
        val names = runSearch(key) { bs -> bs.flatMap { b -> runCatching { search(b) }.getOrDefault(emptyList()) } }.orEmpty()
        val classes = names.distinct().mapNotNull { load(it, cl) }
        if (classes.isNotEmpty()) {
            remember(key, classes.joinToString(",") { it.name })
            XLog.log("Resolver: [$key] found by structure -> ${classes.joinToString { it.name }}")
        }
        return classes
    }

    /**
     * A method: cached → known class+method → DexKit [search] (returns "class#method#paramCount").
     * [accept] double-checks a candidate by reflection (signature) before it's used.
     */
    fun findMethod(
        key: String, cl: ClassLoader, known: List<Pair<String, String>>,
        accept: (Method) -> Boolean, search: (DexKitBridge) -> List<String>
    ): List<Method> {
        if (!forceSearch) {
            cached(key)?.let { spec -> methodsOf(spec, cl, accept).takeIf { it.isNotEmpty() }?.let { return it } }
            for ((cls, name) in known) {
                val ms = load(cls, cl)?.declaredMethods?.filter { it.name == name && accept(it) }.orEmpty()
                if (ms.isNotEmpty()) { remember(key, "$cls#$name"); return ms }
            }
        }
        val specs = runSearch(key) { bs -> bs.flatMap { b -> runCatching { search(b) }.getOrDefault(emptyList()) } }.orEmpty().distinct()
        val ms = specs.flatMap { methodsOf(it, cl, accept) }.distinct()
        if (ms.isNotEmpty()) {
            remember(key, specs.first())
            XLog.log("Resolver: [$key] found by structure -> ${specs.joinToString()}")
        }
        return ms
    }

    /** Release DexKit (its index is large) once all hooks are installed. */
    @Synchronized
    fun finish() {
        bridges?.forEach { runCatching { it.close() } }
        bridges = null
    }

    // ------------------------------------------------------------------ internals

    private fun methodsOf(spec: String, cl: ClassLoader, accept: (Method) -> Boolean): List<Method> {
        val (cls, name) = spec.split('#').let { it[0] to it.getOrNull(1).orEmpty() }
        return load(cls, cl)?.declaredMethods?.filter { it.name == name && accept(it) }.orEmpty()
    }

    private fun load(name: String, cl: ClassLoader): Class<*>? = runCatching { XposedHelpers.findClassIfExists(name, cl) }.getOrNull()

    @Synchronized
    private fun <T> runSearch(key: String, block: (List<DexKitBridge>) -> T?): T? {
        val b = openBridges() ?: return null
        val t0 = System.currentTimeMillis()
        return runCatching { block(b) }
            .onFailure { XLog.log("Resolver: [$key] search failed: ${it.message}") }
            .getOrNull()
            .also { XLog.log("Resolver: [$key] structural search took ${System.currentTimeMillis() - t0} ms") }
    }

    private fun openBridges(): List<DexKitBridge>? {
        bridges?.let { return it }
        if (bridgeFailed || apkPaths.isEmpty()) return null
        return runCatching {
            loadNative()
            // Only APKs that contain code (config splits for language/density have no dex).
            apkPaths.mapNotNull { path -> runCatching { DexKitBridge.create(path) }.getOrNull() }.takeIf { it.isNotEmpty() }
                ?: error("no dex found")
        }.onFailure {
            bridgeFailed = true
            XLog.log("Resolver: DexKit unavailable (${it.message}); only known names will be used")
        }.getOrNull()?.also { bridges = it; XLog.log("Resolver: indexing ${it.size} of ${apkPaths.size} APK files") }
    }

    private fun loadNative() {
        runCatching { System.loadLibrary("dexkit") }.onFailure {
            // Inside TikTok the module's libraries aren't on the search path: load the copy extracted
            // next to TikMod's APK (<install dir>/lib/<abi>/libdexkit.so).
            val lib = com.mts.tikmod.utils.L.moduleApkPath()?.let { File(it).parentFile }
                ?.let { File(it, "lib").listFiles().orEmpty().map { abi -> File(abi, "libdexkit.so") }.firstOrNull { f -> f.exists() } }
                ?: throw it
            System.load(lib.absolutePath)
        }
    }

    @Synchronized
    private fun cacheJson(): JSONObject {
        cache?.let { return it }
        val json = runCatching { JSONObject(cacheFile!!.readText()) }.getOrNull()
            ?.takeIf { it.optString("_apk") == apkPath } ?: JSONObject().put("_apk", apkPath)
        cache = json
        return json
    }

    private fun cached(key: String): String? = cacheJson().optString(key).takeIf { it.isNotBlank() }

    @Synchronized
    private fun remember(key: String, value: String) {
        val json = cacheJson()
        if (json.optString(key) == value) return
        json.put(key, value)
        runCatching { cacheFile?.let { f -> f.parentFile?.mkdirs(); f.writeText(json.toString()) } }
    }
}
