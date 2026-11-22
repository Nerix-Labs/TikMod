package com.mts.tikmod.updates

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mts.tikmod.BuildConfig
import com.mts.tikmod.R
import com.mts.tikmod.utils.L
import com.mts.tikmod.utils.TikModDownloader
import com.mts.tikmod.utils.XLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app updates from the project's GitHub releases.
 *
 * Releases are read from the public GitHub API (no account, no token). The APK attached to a release is downloaded
 * into the app's cache and handed to Android's PackageInstaller — the system always asks the user to confirm.
 */
object Updater {
    const val REPO = "Nerix-Labs/TikMod"
    /** Release index kept in the repository. Served as a plain file, so it has no API rate limit. */
    private const val INDEX = "https://raw.githubusercontent.com/$REPO/main/updates.json"
    /** Fallback only: the GitHub API allows 60 anonymous requests per hour per IP (often shared). */
    private const val API = "https://api.github.com/repos/$REPO/releases?per_page=20"
    private const val PREFS = "tikmod_updates"

    data class Release(
        val version: String,      // "4.1.0"
        val name: String,
        val notes: String,        // markdown body of the release
        val publishedAt: String,  // ISO date
        val apkUrl: String?,
        val apkSize: Long,
        val pageUrl: String,
        /** SHA-256 of the APK from updates.json (lower-case hex); the download is refused when it doesn't match. */
        val sha256: String? = null
    )

    sealed interface Status {
        data object Idle : Status
        data object Checking : Status
        data object UpToDate : Status
        data class Available(val release: Release) : Status
        data class Downloading(val release: Release, val progress: Float) : Status
        data object Installing : Status
        data class Error(val message: String) : Status
    }

    /** Observable by Compose; shared by the home banner and the updates section. */
    var status by mutableStateOf<Status>(Status.Idle)
    var releases by mutableStateOf<List<Release>>(emptyList())

    // ------------------------------------------------------------------ versions

    fun parse(v: String): List<Int> = v.trim().removePrefix("v").split('.', '-').mapNotNull { it.toIntOrNull() }

    fun isNewer(candidate: String, current: String = BuildConfig.VERSION_NAME): Boolean {
        val a = parse(candidate); val b = parse(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    // ------------------------------------------------------------------ check

    suspend fun check(context: Context, silent: Boolean = false) {
        if (status is Status.Downloading || status is Status.Installing) return
        if (!silent) status = Status.Checking
        val result = withContext(Dispatchers.IO) { runCatching { fetch() } }
        result.onSuccess { list ->
            releases = list
            prefs(context).edit().putLong("last_check", System.currentTimeMillis()).apply()
            val newest = list.firstOrNull { it.apkUrl != null }
            status = if (newest != null && isNewer(newest.version)) Status.Available(newest) else if (silent) Status.Idle else Status.UpToDate
        }.onFailure {
            XLog.log("Updater: check failed: ${it.message}")
            if (!silent) status = Status.Error(L.t(R.string.update_error_network))
        }
    }

    /** Daily background check when enabled (called when the app opens). */
    suspend fun checkIfDue(context: Context, enabled: Boolean) {
        if (!enabled) return
        val last = prefs(context).getLong("last_check", 0L)
        val age = System.currentTimeMillis() - last
        // Once a day; when the list isn't loaded yet (fresh process), at most once an hour.
        if (age < 60 * 60 * 1000L || (age < 24 * 60 * 60 * 1000L && releases.isNotEmpty())) return
        prefs(context).edit().putLong("last_check", System.currentTimeMillis()).apply()
        check(context, silent = true)
    }

    private fun newest(list: List<Release>) =
        list.sortedWith { a, b -> if (isNewer(a.version, b.version)) -1 else if (isNewer(b.version, a.version)) 1 else 0 }

    private fun fetch(): List<Release> =
        runCatching { fetchIndex() }.onFailure { XLog.log("Updater: index failed (${it.message}), trying the API") }
            .getOrNull()?.takeIf { it.isNotEmpty() } ?: fetchApi()

    private fun fetchIndex(): List<Release> {
        val conn = URL(INDEX).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.useCaches = false
        conn.setRequestProperty("User-Agent", "TikMod/${BuildConfig.VERSION_NAME}")
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            val arr = org.json.JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).getJSONArray("releases")
            return newest((0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val v = o.optString("version").ifBlank { return@mapNotNull null }
                Release(
                    version = v, name = "TikMod $v", notes = o.optString("notes"), publishedAt = o.optString("date"),
                    apkUrl = o.optString("apk").takeIf { it.startsWith("https://") }, apkSize = o.optLong("size"),
                    pageUrl = "https://github.com/$REPO/releases/tag/v$v",
                    sha256 = o.optString("sha256").lowercase().takeIf { it.matches(Regex("[0-9a-f]{64}")) }
                )
            })
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private fun fetchApi(): List<Release> {
        val conn = URL(API).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "TikMod/${BuildConfig.VERSION_NAME}")
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            val arr = JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                if (o.optBoolean("draft") || o.optBoolean("prerelease")) return@mapNotNull null
                val assets = o.optJSONArray("assets") ?: JSONArray()
                val apk = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
                    .firstOrNull { it.optString("name").endsWith(".apk") }
                Release(
                    version = o.optString("tag_name").removePrefix("v"),
                    name = o.optString("name"),
                    notes = o.optString("body"),
                    publishedAt = o.optString("published_at"),
                    apkUrl = apk?.optString("browser_download_url"),
                    apkSize = apk?.optLong("size") ?: 0L,
                    pageUrl = o.optString("html_url")
                )
            }.let { newest(it) }
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    // ------------------------------------------------------------------ install

    /** True when Android lets TikMod install packages; otherwise opens the system screen to allow it. */
    fun ensureInstallPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()) return true
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        TikModDownloader.toast(context, L.t(R.string.update_permission))
        return false
    }

    suspend fun downloadAndInstall(context: Context, release: Release) {
        val url = release.apkUrl ?: return
        if (!ensureInstallPermission(context)) return
        val ctx = context.applicationContext
        status = Status.Downloading(release, 0f)
        val file = withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(ctx.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                val out = File(dir, "TikMod-${release.version}.apk")
                var conn = URL(url).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 20_000
                conn.readTimeout = 30_000
                conn.setRequestProperty("User-Agent", "TikMod/${BuildConfig.VERSION_NAME}")
                if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.apkSize
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                conn.inputStream.use { input ->
                    out.outputStream().use { o ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastUi = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            o.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            done += n
                            val now = System.currentTimeMillis()
                            if (total > 0 && now - lastUi > 150) {
                                lastUi = now
                                status = Status.Downloading(release, (done.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
                conn.disconnect()
                // A corrupted or swapped file never reaches the installer.
                release.sha256?.let { want ->
                    val got = digest.digest().joinToString("") { "%02x".format(it) }
                    if (got != want) { out.delete(); error(L.t(R.string.update_bad_checksum)) }
                }
                out
            }
        }.getOrElse {
            status = Status.Error(L.t(R.string.update_failed, it.message ?: ""))
            return
        }
        status = Status.Installing
        withContext(Dispatchers.IO) { runCatching { install(ctx, file) } }.onFailure {
            status = Status.Error(L.t(R.string.update_failed, it.message ?: ""))
        }
    }

    private fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("TikMod.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(context, id, Intent(context, InstallResultReceiver::class.java), flags)
            session.commit(pending.intentSender)
        }
    }

    fun onInstallResult(context: Context, statusCode: Int, message: String?) {
        if (statusCode == PackageInstaller.STATUS_SUCCESS) return // the app restarts into the new version
        status = Status.Error(L.t(R.string.update_failed, message ?: statusCode.toString()))
    }

    // ------------------------------------------------------------------ "what's new" bookkeeping

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** True once after each update (and on first install), until the sheet is dismissed. */
    fun shouldShowWhatsNew(context: Context): Boolean =
        prefs(context).getInt("seen_version_code", 0) < BuildConfig.VERSION_CODE

    fun markWhatsNewSeen(context: Context) {
        prefs(context).edit().putInt("seen_version_code", BuildConfig.VERSION_CODE).apply()
    }
}

/** Receives PackageInstaller results: shows the system confirmation when needed, reports failures. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(confirm) }
            return
        }
        Updater.onInstallResult(context, status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }
}
