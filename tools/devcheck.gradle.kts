// Dev helper: the same check as tools/check_resolver.py, as a Gradle task (no Python needed; runs from the Gradle
// panel in Android Studio: tikmod > tikmodCheckResolver). Applied from the root build.gradle.kts.
// Result: printed and saved to build/tikmod-check.txt. Add -Pforce to test the DexKit searches themselves.
import java.util.Properties

val sdkDir: String = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}.getProperty("sdk.dir") ?: System.getenv("ANDROID_HOME") ?: ""
val adbPath: String = File(sdkDir, "platform-tools/" + if (System.getProperty("os.name").startsWith("Windows")) "adb.exe" else "adb").path
val reportFile: File = rootProject.layout.buildDirectory.file("tikmod-check.txt").get().asFile
val forceSearch: Boolean = providers.gradleProperty("force").isPresent

tasks.register("tikmodCheckResolver") {
    group = "tikmod"
    description = "Restart TikTok on the connected phone and report which hooks / obfuscated targets were found"
    notCompatibleWithConfigurationCache("runs adb against a device")
    doLast {
        val pkg = "com.zhiliaoapp.musically"
        val diag = "/data/user/0/com.mts.tikmod/files/diag_report.txt"
        fun run(vararg c: String): String {
            val p = ProcessBuilder(*c).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            return out
        }
        fun adb(vararg a: String) = run(adbPath, *a)
        fun su(cmd: String) = adb("shell", "su -c '$cmd'")
        val sb = StringBuilder()
        fun say(s: String) { println(s); sb.appendLine(s) }

        val state = adb("get-state").trim()
        if (state != "device") throw GradleException("No phone over adb ($adbPath): $state")
        val ver = Regex("versionName=(\\S+)").find(adb("shell", "dumpsys package $pkg"))?.groupValues?.get(1) ?: "?"
        say("TikTok $ver   (force search: $forceSearch)")
        su("rm -f $diag")
        su("setprop debug.tikmod.forcesearch ${if (forceSearch) 1 else 0}")
        adb("logcat", "-c")
        adb("shell", "am force-stop $pkg")
        adb("shell", "monkey -p $pkg -c android.intent.category.LAUNCHER 1")
        var report = ""
        for (i in 1..60) {
            Thread.sleep(1000)
            report = su("cat $diag 2>/dev/null").trim()
            if (report.isNotEmpty()) break
        }
        if (forceSearch) su("setprop debug.tikmod.forcesearch 0")
        val log = adb("logcat", "-d", "-s", "TikMod:*", "LSPosed-Bridge:*")
        val missing = Regex("Resolver: \\[([^\\]]+)\\] NOT FOUND").findAll(log).map { it.groupValues[1] }.toSortedSet()
        val slow = Regex("Resolver: \\[([^\\]]+)\\] structural search took (\\d+) ms").findAll(log).map { "${it.groupValues[1]} ${it.groupValues[2]} ms" }.toList()
        if (report.isEmpty()) say("No diagnostics after 60 s: is TikMod 4.8.0 installed and enabled in LSPosed with TikTok in scope?")
        else say(report)
        if (slow.isNotEmpty()) say("\nStructural searches: " + slow.joinToString())
        if (missing.isNotEmpty()) say("\nNOT FOUND in the log: " + missing.joinToString())
        val failed = report.lines().count { it.trim().startsWith("✗") }
        say("\nRESULT: " + if (report.isNotEmpty() && failed == 0 && missing.isEmpty()) "all good" else "$failed hook/target problem(s), ${missing.size} not found")
        sb.appendLine().appendLine("---- log ----").append(log.takeLast(200_000))
        reportFile.parentFile.mkdirs()
        reportFile.writeText(sb.toString())
    }
}
