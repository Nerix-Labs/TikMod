package com.mts.tikmod

import com.mts.tikmod.utils.L

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.mts.tikmod.live.LiveWatchService
import com.mts.tikmod.ui.*
import com.mts.tikmod.ui.theme.Neon
import com.mts.tikmod.ui.theme.ThemeMode
import com.mts.tikmod.ui.theme.TikModTheme
import com.mts.tikmod.utils.TikModDownloader
import com.mts.tikmod.updates.Changelog
import com.mts.tikmod.updates.Updater
import kotlinx.coroutines.delay
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private var startTab by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.initDefaultsIfNeeded(this)
        enableEdgeToEdge()
        startTab = intent?.getStringExtra("tab")
        LiveWatchService.sync(this)
        setContent {
            var themeKey by remember { mutableStateOf(Prefs.string(this, Prefs.KEY_UI_THEME)) }
            val mode = when (themeKey) { "dynamic" -> ThemeMode.DYNAMIC; "system" -> ThemeMode.SYSTEM; else -> ThemeMode.NEON }
            TikModTheme(mode) {
                // Re-read every string and flip the layout direction when the language changes.
                key(L.revision) {
                    CompositionLocalProvider(LocalLayoutDirection provides if (L.isRtl()) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                        TikModApp(startTab, onThemeChange = { themeKey = it })
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        startTab = intent.getStringExtra("tab")
    }
}

private enum class Screen(val icon: ImageVector, private val labelRes: Int, private val tint: () -> Color) {
    HOME(Icons.Rounded.SpaceDashboard, R.string.nav_home, { Neon.Cyan }),
    DOWNLOADS(Icons.Rounded.Download, R.string.nav_downloads, { Neon.Cyan }),
    LIVE(Icons.Rounded.Sensors, R.string.nav_live, { Neon.Pink }),
    CHAT(Icons.Rounded.Forum, R.string.nav_chat, { Neon.Violet }),
    FEED(Icons.Rounded.AutoAwesome, R.string.nav_feed, { Neon.Amber });

    // Resolved when used, so they follow the current language and theme.
    val label: String get() = L.t(labelRes)
    val accent: Color get() = tint()
}

@Composable
private fun TikModApp(startTab: String?, onThemeChange: (String) -> Unit) {
    val ctx = LocalContext.current
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    LaunchedEffect(startTab) { if (startTab == "live") screen = Screen.LIVE }
    val active = remember { Prefs.isModuleActive(ctx) }
    // "What's new" opens by itself once after every update; the history sheet from Home → Updates.
    var showWhatsNew by remember { mutableStateOf(Updater.shouldShowWhatsNew(ctx) && Changelog.forVersion(BuildConfig.VERSION_NAME) != null) }
    var showHistory by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { Updater.checkIfDue(ctx, Prefs.bool(ctx, Prefs.KEY_AUTO_UPDATE_CHECK)) }

    // No Scaffold/Surface here, so set the default text color explicitly (otherwise it's black in dark mode).
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
    Box(Modifier.fillMaxSize()) {
        AuroraBackground()
        AnimatedContent(
            targetState = screen,
            transitionSpec = {
                val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                (slideInHorizontally(tween(380, easing = FastOutSlowInEasing)) { -it / 5 * dir } + fadeIn(tween(300))) togetherWith
                    (slideOutHorizontally(tween(300)) { it / 5 * dir } + fadeOut(tween(200)))
            },
            label = "screens"
        ) { s ->
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .statusBarsPadding().padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                when (s) {
                    Screen.HOME -> HomeScreen(active, onNavigate = { screen = it }, onThemeChange = onThemeChange, onShowHistory = { showHistory = true })
                    Screen.DOWNLOADS -> DownloadsScreen()
                    Screen.LIVE -> LiveScreen()
                    Screen.CHAT -> ChatScreen()
                    Screen.FEED -> FeedScreen()
                }
            }
        }
        Box(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars)
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.94f))
        )
        FloatingNav(screen, Modifier.align(Alignment.BottomCenter)) { screen = it }
    }
    if (showWhatsNew) Changelog.forVersion(BuildConfig.VERSION_NAME)?.let { v ->
        WhatsNewSheet(v) { showWhatsNew = false; Updater.markWhatsNewSeen(ctx) }
    }
    if (showHistory) HistorySheet { showHistory = false }
    }
}

// ================================================================== navigation

@Composable
private fun FloatingNav(current: Screen, modifier: Modifier = Modifier, onSelect: (Screen) -> Unit) {
    Row(
        modifier.navigationBarsPadding().padding(horizontal = 14.dp, vertical = 12.dp).fillMaxWidth()
            .clip(RoundedCornerShape(30.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f))
            .border(1.dp, Brush.horizontalGradient(listOf(Neon.Cyan.copy(alpha = 0.5f), Neon.Violet.copy(alpha = 0.3f), Neon.Pink.copy(alpha = 0.5f))), RoundedCornerShape(30.dp))
            .padding(6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Screen.entries.forEach { s ->
            val on = s == current
            val w by animateDpAsState(if (on) 108.dp else 52.dp, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMediumLow), label = "w")
            val bg by animateColorAsState(if (on) s.accent else Color.Transparent, tween(250), label = "bg")
            val fg by animateColorAsState(if (on) Neon.OnAccent else Subtle, tween(250), label = "fg")
            Row(
                Modifier.width(w).height(48.dp).clip(RoundedCornerShape(24.dp)).background(bg)
                    .clickable(remember { MutableInteractionSource() }, null) { onSelect(s) },
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(s.icon, s.label, tint = fg, modifier = Modifier.size(22.dp))
                AnimatedVisibility(on, enter = fadeIn(tween(200, 80)) + expandHorizontally(), exit = fadeOut(tween(100)) + shrinkHorizontally()) {
                    Text(s.label, color = fg, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun ScreenTitle(title: String, subtitle: String, accent: Color) {
    Column(Modifier.padding(horizontal = 6.dp, vertical = 6.dp)) {
        Text(title, fontSize = 30.sp, fontWeight = FontWeight.Black)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(26.dp).height(3.dp).clip(CircleShape).background(Brush.horizontalGradient(listOf(accent, accent.copy(alpha = 0f)))))
            Spacer(Modifier.width(8.dp))
            Text(subtitle, fontSize = 13.sp, color = Subtle)
        }
    }
}

// ================================================================== HOME

@Composable
private fun HomeScreen(active: Boolean, onNavigate: (Screen) -> Unit, onThemeChange: (String) -> Unit, onShowHistory: () -> Unit) {
    val ctx = LocalContext.current
    val history = remember { Prefs.readHistory(ctx) }
    val ads = remember { Prefs.readStat(ctx, Prefs.STAT_ADS_BLOCKED) }
    val channels = remember { Prefs.autoLiveChannels(ctx).size }

    Appear(0) {
        GlassCard(glow = true, padding = 20.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("TikMod", fontSize = 34.sp, fontWeight = FontWeight.Black,
                        style = LocalTextStyle.current.copy(brush = Brush.linearGradient(listOf(Neon.Cyan, Neon.Violet, Neon.Pink))))
                    Text("by Nerix Labs", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp, color = Subtle)
                    Text(L.t(R.string.home_version, BuildConfig.VERSION_NAME), fontSize = 12.sp, color = Subtle)
                    Spacer(Modifier.height(12.dp))
                    Row(
                        Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)).padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PulsingDot(if (active) Neon.Lime else Neon.Pink, 8.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(if (active) L.t(R.string.status_connected) else L.t(R.string.status_inactive), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                CoreOrb(active)
            }
        }
    }
    (Updater.status as? Updater.Status.Available)?.let { st ->
        Appear(1) { UpdateBanner(st.release) { onShowHistory() } }
    }
    if (!active) Appear(1) {
        Section(Icons.Rounded.WarningAmber, L.t(R.string.setup_title), L.t(R.string.setup_subtitle), accent = Neon.Amber) {
            Text(L.t(R.string.setup_steps), fontSize = 13.sp, lineHeight = 21.sp, modifier = Modifier.padding(horizontal = 10.dp))
        }
    }
    Appear(2) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(Icons.Rounded.Download, history.size.toLong(), L.t(R.string.nav_downloads), Neon.Cyan, Modifier.weight(1f))
            StatTile(Icons.Rounded.Block, ads, L.t(R.string.stat_ads_blocked), Neon.Amber, Modifier.weight(1f))
            StatTile(Icons.Rounded.Sensors, channels.toLong(), L.t(R.string.stat_channels), Neon.Pink, Modifier.weight(1f))
        }
    }
    Appear(3) { SectionLabel(L.t(R.string.home_shortcuts)) }
    Appear(4) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickTile(Icons.Rounded.Download, L.t(R.string.quick_download), Prefs.KEY_REMOVE_WATERMARK, Neon.Cyan, Modifier.weight(1f)) { Prefs.setForceDownloadEnabled(ctx, it) }
                QuickTile(Icons.Rounded.DeleteSweep, L.t(R.string.quick_deleted), Prefs.KEY_ANTI_RECALL, Neon.Violet, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickTile(Icons.Rounded.Block, L.t(R.string.quick_ads), Prefs.KEY_REMOVE_ADS, Neon.Amber, Modifier.weight(1f))
                QuickTile(Icons.Rounded.KeyboardHide, L.t(R.string.quick_typing), Prefs.KEY_DISABLE_TYPING_INDICATOR, Neon.Violet, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickTile(Icons.Rounded.FiberManualRecord, L.t(R.string.quick_autolive), Prefs.KEY_AUTO_LIVE, Neon.Pink, Modifier.weight(1f)) { LiveWatchService.sync(ctx) }
                QuickTile(Icons.Rounded.Screenshot, L.t(R.string.quick_screenshot), Prefs.KEY_UNRESTRICT_ALL, Neon.Lime, Modifier.weight(1f))
            }
        }
    }
    if (history.isNotEmpty()) {
        Appear(5) { SectionLabel(L.t(R.string.home_recent)) }
        Appear(6) {
            GlassCard(padding = 10.dp) {
                history.take(3).forEach { HistoryRow(it) }
                TextButton(onClick = { onNavigate(Screen.DOWNLOADS) }, modifier = Modifier.align(Alignment.End)) { Text(L.t(R.string.home_all_downloads)) }
            }
        }
    }
    Appear(7) { SectionLabel(L.t(R.string.home_system)) }
    Appear(8) { SystemSection(onThemeChange, onShowHistory) }
}

@Composable
private fun SystemSection(onThemeChange: (String) -> Unit, onShowHistory: () -> Unit) {
    val ctx = LocalContext.current
    val createFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let {
            runCatching {
                ctx.contentResolver.openOutputStream(it)?.use { s -> s.write(Prefs.exportToJson(ctx).toByteArray()) }
                Toast.makeText(ctx, L.t(R.string.toast_settings_exported), Toast.LENGTH_SHORT).show()
            }
        }
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            runCatching {
                val json = ctx.contentResolver.openInputStream(it)?.bufferedReader()?.use { r -> r.readText() }
                if (json != null && Prefs.importFromJson(ctx, json)) Toast.makeText(ctx, L.t(R.string.toast_settings_imported), Toast.LENGTH_SHORT).show()
            }
        }
    }
    LanguageSection()
    Section(Icons.Rounded.Palette, L.t(R.string.sys_look), accent = Neon.Violet) {
        SegmentedPref(L.t(R.string.sys_theme), Prefs.KEY_UI_THEME, listOf(
            Opt("neon", L.t(R.string.theme_neon), Icons.Rounded.Bolt), Opt("dynamic", "Material You", Icons.Rounded.ColorLens), Opt("system", L.t(R.string.theme_system), Icons.Rounded.Contrast)
        ), accent = Neon.Violet, onChange = onThemeChange)
    }
    Section(Icons.Rounded.Tune, L.t(R.string.sys_manage), L.t(R.string.sys_manage_sub), accent = Neon.Cyan, collapsible = true, initiallyExpanded = false) {
        ToggleRow(L.t(R.string.sys_unrestrict), L.t(R.string.sys_unrestrict_desc), Prefs.KEY_UNRESTRICT_ALL, Icons.Rounded.Screenshot, Neon.Lime)
        Hairline()
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(Icons.Rounded.Upload, L.t(R.string.sys_export), Modifier.weight(1f)) { createFile.launch("tikmod_config.json") }
            ActionButton(Icons.Rounded.FileOpen, L.t(R.string.sys_import), Modifier.weight(1f)) { openFile.launch("application/json") }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(Icons.Rounded.CleaningServices, L.t(R.string.sys_reset_cache), Modifier.weight(1f)) {
                Prefs.resetDiscoveryCache(ctx); Toast.makeText(ctx, L.t(R.string.toast_cache_reset), Toast.LENGTH_SHORT).show()
            }
            ActionButton(Icons.Rounded.RestartAlt, L.t(R.string.sys_restart_tiktok), Modifier.weight(1f), accent = Neon.Pink) {
                Prefs.forceRestartTikTok(ctx); Toast.makeText(ctx, L.t(R.string.toast_tiktok_restart), Toast.LENGTH_SHORT).show()
            }
        }
        Hint(L.t(R.string.sys_hint))
    }
    Section(Icons.Rounded.Tune, L.t(R.string.tweaks_title), L.t(R.string.tweaks_sub), accent = Neon.Lime, collapsible = true, initiallyExpanded = false) {
        ToggleRow(L.t(R.string.tweak_screen_on), L.t(R.string.tweak_screen_on_desc), Prefs.KEY_KEEP_SCREEN_ON, Icons.Rounded.ScreenLockPortrait, Neon.Lime)
        ToggleRow(L.t(R.string.tweak_haptics), L.t(R.string.tweak_haptics_desc), Prefs.KEY_HAPTICS_OFF, Icons.Rounded.Vibration, Neon.Lime)
        ToggleRow(L.t(R.string.tweak_links), L.t(R.string.tweak_links_desc), Prefs.KEY_EXTERNAL_LINKS, Icons.Rounded.OpenInBrowser, Neon.Lime)
    }
    UpdatesSection(onShowHistory)
    AboutCard()
}

/** Manual language choice: follow the phone, or one of the bundled translations. */
@Composable
private fun LanguageSection() {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val current = L.language()
    val currentName = L.LANGUAGES.firstOrNull { it.first == current }?.second ?: L.t(R.string.language_system)
    GlassCard(padding = 0.dp) {
        Row(
            Modifier.fillMaxWidth().clickable { open = true }.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconTile(Icons.Rounded.Translate, Neon.Cyan, 44.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(L.t(R.string.language_title), fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text(currentName, fontSize = 13.sp, color = Subtle)
            }
            Icon(Icons.Rounded.UnfoldMore, null, tint = Subtle)
        }
    }
    if (open) Panel(onDismiss = { open = false }) {
        Text(L.t(R.string.language_title), fontSize = 24.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(12.dp))
        (listOf(L.SYSTEM to L.t(R.string.language_system)) + L.LANGUAGES).forEach { (tag, name) ->
            val on = tag == current
            Row(
                Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(16.dp))
                    .background(if (on) Neon.Cyan.copy(alpha = 0.14f) else Color.Transparent)
                    .clickable { open = false; if (!on) L.setLanguage(ctx, tag) }
                    .padding(horizontal = 14.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (tag == L.SYSTEM) { Icon(Icons.Rounded.PhoneAndroid, null, tint = if (on) Neon.Cyan else Subtle, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(10.dp)) }
                Text(name, Modifier.weight(1f), fontSize = 15.5.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium)
                if (on) Icon(Icons.Rounded.Check, null, tint = Neon.Cyan, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun AboutCard() {
    val ctx = LocalContext.current
    GlassCard(padding = 18.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(15.dp))
                    .background(Brush.linearGradient(listOf(Neon.Cyan, Neon.Violet, Neon.Pink))),
                contentAlignment = Alignment.Center
            ) { Text("N", fontSize = 24.sp, fontWeight = FontWeight.Black, color = Neon.OnAccent) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Nerix Labs", fontSize = 17.sp, fontWeight = FontWeight.Black)
                Text(L.t(R.string.about_line, BuildConfig.VERSION_NAME), fontSize = 12.5.sp, color = Subtle)
            }
            IconButton(onClick = {
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Nerix-Labs/TikMod")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }) { Icon(Icons.Rounded.Code, L.t(R.string.about_source), tint = Neon.Cyan) }
        }
    }
}

@Composable
private fun ActionButton(icon: ImageVector, text: String, modifier: Modifier = Modifier, accent: Color = Neon.Cyan, onClick: () -> Unit) {
    Row(
        modifier.height(48.dp).clip(RoundedCornerShape(16.dp))
            .background(accent.copy(alpha = 0.12f)).border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick).padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ================================================================== DOWNLOADS

@Composable
private fun DownloadsScreen() {
    val ctx = LocalContext.current
    Appear(0) { ScreenTitle(L.t(R.string.nav_downloads), L.t(R.string.dl_subtitle), Neon.Cyan) }
    Appear(1) {
        Section(Icons.Rounded.Download, L.t(R.string.dl_engine), L.t(R.string.dl_engine_sub)) {
            ToggleRow(L.t(R.string.dl_engine_toggle), L.t(R.string.dl_engine_toggle_desc), Prefs.KEY_REMOVE_WATERMARK, Icons.Rounded.PowerSettingsNew) { Prefs.setForceDownloadEnabled(ctx, it) }
            Hairline()
            SegmentedPref(L.t(R.string.dl_quality), Prefs.KEY_DOWNLOAD_QUALITY, listOf(
                Opt(Prefs.DOWNLOAD_QUALITY_MAX, L.t(R.string.dl_quality_max)), Opt(Prefs.DOWNLOAD_QUALITY_HD, "HD"), Opt(Prefs.DOWNLOAD_QUALITY_SD, L.t(R.string.dl_quality_sd))
            ))
            SegmentedPref(L.t(R.string.dl_codec), Prefs.KEY_VIDEO_CODEC, listOf(
                Opt(Prefs.VIDEO_CODEC_BEST, L.t(R.string.dl_codec_best)), Opt(Prefs.VIDEO_CODEC_H264, L.t(R.string.dl_codec_h264))
            ), description = L.t(R.string.dl_codec_desc))
        }
    }
    Appear(2) {
        Section(Icons.Rounded.Share, L.t(R.string.share_title), L.t(R.string.share_sub), badge = L.t(R.string.tag_new)) {
            SegmentedPref(L.t(R.string.share_default), Prefs.KEY_SHARE_MODE, listOf(
                Opt(Prefs.SHARE_MODE_MEDIA, L.t(R.string.share_mode_file), Icons.Rounded.Movie),
                Opt(Prefs.SHARE_MODE_MEDIA_LINK, L.t(R.string.share_mode_file_link), Icons.Rounded.AddLink),
                Opt(Prefs.SHARE_MODE_LINK, L.t(R.string.share_mode_link), Icons.Rounded.Link)
            ))
            Hint(L.t(R.string.share_hint))
        }
    }
    Appear(3) {
        Section(Icons.Rounded.Widgets, L.t(R.string.card_buttons), L.t(R.string.card_buttons_sub), collapsible = true, initiallyExpanded = false) {
            ToggleRow(L.t(R.string.card_photos), L.t(R.string.card_photos_desc), Prefs.KEY_DOWNLOAD_PHOTOS, Icons.Rounded.Collections)
            ToggleRow(L.t(R.string.card_jpeg), L.t(R.string.card_jpeg_desc), Prefs.KEY_IMAGES_AS_JPEG, Icons.Rounded.Image)
            ToggleRow(L.t(R.string.card_audio), L.t(R.string.card_audio_desc), Prefs.KEY_DOWNLOAD_AUDIO, Icons.Rounded.MusicNote)
            SegmentedPref(L.t(R.string.card_audio_source), Prefs.KEY_AUDIO_SOURCE, listOf(Opt(Prefs.AUDIO_SOURCE_ORIGINAL, L.t(R.string.card_audio_original)), Opt(Prefs.AUDIO_SOURCE_VIDEO, L.t(R.string.card_audio_video))))
            ToggleRow(L.t(R.string.card_cover), L.t(R.string.card_cover_desc), Prefs.KEY_DOWNLOAD_COVER, Icons.Rounded.Wallpaper)
            ToggleRow(L.t(R.string.card_avatar), L.t(R.string.card_avatar_desc), Prefs.KEY_DOWNLOAD_AVATAR, Icons.Rounded.AccountCircle)
            ToggleRow(L.t(R.string.card_link), L.t(R.string.card_link_desc), Prefs.KEY_COPY_LINK_BUTTON, Icons.Rounded.Link)
            Hairline()
            SegmentedPref(L.t(R.string.card_save_button), Prefs.KEY_DOWNLOAD_UI_MODE, listOf(
                Opt(Prefs.DOWNLOAD_UI_MODE_ADD, L.t(R.string.card_save_keep)), Opt(Prefs.DOWNLOAD_UI_MODE_REPLACE, L.t(R.string.card_save_tikmod))
            ))
        }
    }
    Appear(4) {
        Section(Icons.Rounded.FolderOpen, L.t(R.string.files_title), collapsible = true, initiallyExpanded = false) {
            SegmentedPref(L.t(R.string.files_folder), Prefs.KEY_SAVE_LOCATION, listOf(
                Opt(Prefs.SAVE_LOCATION_MOVIES, "Movies"), Opt(Prefs.SAVE_LOCATION_DCIM, "DCIM"), Opt(Prefs.SAVE_LOCATION_DOWNLOAD, "Download")
            ))
            ToggleRow(L.t(R.string.files_author_folders), "TikMod/@username/…", Prefs.KEY_AUTHOR_FOLDERS, Icons.Rounded.CreateNewFolder)
            ToggleRow(L.t(R.string.files_skip_dupes), L.t(R.string.files_skip_dupes_desc), Prefs.KEY_SKIP_DUPLICATES, Icons.Rounded.FilterNone)
            ToggleRow(L.t(R.string.files_copy_caption), L.t(R.string.files_copy_caption_desc), Prefs.KEY_COPY_CAPTION, Icons.Rounded.ContentCopy)
            ToggleRow(L.t(R.string.files_progress), L.t(R.string.files_progress_desc), Prefs.KEY_NOTIFICATION_PROGRESS, Icons.Rounded.NotificationsActive)
            ToggleRow(L.t(R.string.files_history), L.t(R.string.files_history_desc), Prefs.KEY_DOWNLOAD_HISTORY, Icons.Rounded.History)
            Hairline()
            FileNamePatternEditor()
        }
    }
    Appear(5) { HistorySection() }
}

@Composable
private fun FileNamePatternEditor() {
    val ctx = LocalContext.current
    var pattern by remember { mutableStateOf(Prefs.getCustomFilenamePattern(ctx)) }
    val presets = listOf("{author}_{id}", "{nickname} - {desc}", "{date}_{author}", "TikTok_{id}_{quality}")
    val preview = pattern.replace("{id}", "7301234567890123456").replace("{author}", "tiktok_user")
        .replace("{nickname}", L.t(R.string.pattern_sample_creator)).replace("{desc}", L.t(R.string.pattern_sample_desc)).replace("{date}", "20261117_204500")
        .replace("{quality}", "1080p").replace("{type}", "video")
    val ltr = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr)
    Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(L.t(R.string.pattern_title), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        OutlinedTextField(
            value = pattern, onValueChange = { pattern = it }, singleLine = true, textStyle = ltr,
            shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
            supportingText = { Text("$preview.mp4", maxLines = 1, overflow = TextOverflow.Ellipsis, style = ltr) },
            trailingIcon = {
                IconButton(onClick = {
                    Prefs.setString(ctx, Prefs.KEY_CUSTOM_FILENAME_PATTERN, pattern.ifBlank { Prefs.DEFAULT_FILENAME_PATTERN })
                    Toast.makeText(ctx, L.t(R.string.toast_pattern_saved), Toast.LENGTH_SHORT).show()
                }) { Icon(Icons.Rounded.Check, L.t(R.string.action_save), tint = Neon.Cyan) }
            }
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            presets.forEach { p ->
                AssistChip(onClick = { pattern = p; Prefs.setString(ctx, Prefs.KEY_CUSTOM_FILENAME_PATTERN, p) }, label = { Text(p, fontSize = 11.sp, style = ltr) })
            }
        }
        Text(L.t(R.string.pattern_tags), fontSize = 11.sp, color = Subtle)
    }
}

@Composable
private fun HistorySection() {
    val ctx = LocalContext.current
    var history by remember { mutableStateOf(Prefs.readHistory(ctx)) }
    var showAll by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            icon = { Icon(Icons.Rounded.DeleteSweep, null) },
            title = { Text(L.t(R.string.history_clear_title)) },
            text = { Text(L.t(R.string.history_clear_text)) },
            confirmButton = { TextButton(onClick = { Prefs.clearHistory(ctx); history = emptyList(); confirmClear = false }) { Text(L.t(R.string.action_clear)) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(L.t(R.string.action_cancel)) } }
        )
    }
    Section(Icons.Rounded.History, L.t(R.string.home_recent), L.p(R.plurals.history_items, history.size)) {
        Row(Modifier.padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(Icons.Rounded.Refresh, L.t(R.string.action_refresh), Modifier.weight(1f)) { history = Prefs.readHistory(ctx) }
            if (history.isNotEmpty()) ActionButton(Icons.Rounded.DeleteOutline, L.t(R.string.history_clear_button), Modifier.weight(1f), accent = Neon.Pink) { confirmClear = true }
        }
        if (history.isEmpty()) {
            Hint(L.t(R.string.history_empty))
        } else {
            (if (showAll) history else history.take(8)).forEach { HistoryRow(it) }
            if (history.size > 8) TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) L.t(R.string.history_show_less) else L.p(R.plurals.history_show_all, history.size)) }
        }
    }
}

@Composable
private fun HistoryRow(entry: JSONObject) {
    val ctx = LocalContext.current
    val (icon, accent) = when (entry.optString("kind")) {
        "VIDEO" -> Icons.Rounded.Movie to Neon.Cyan
        "PHOTO" -> Icons.Rounded.Image to Neon.Violet
        "AUDIO" -> Icons.Rounded.MusicNote to Neon.Lime
        "COVER" -> Icons.Rounded.Wallpaper to Neon.Amber
        "AVATAR" -> Icons.Rounded.AccountCircle to Neon.Violet
        "LIVE" -> Icons.Rounded.Sensors to Neon.Pink
        else -> Icons.Rounded.InsertDriveFile to Neon.Cyan
    }
    val time = DateUtils.getRelativeTimeSpanString(entry.optLong("time"), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable {
            runCatching {
                ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(entry.optString("uri")), entry.optString("mime"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }.onFailure { Toast.makeText(ctx, L.t(R.string.toast_file_missing), Toast.LENGTH_SHORT).show() }
        }.padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconTile(icon, accent, 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.optString("title").ifBlank { entry.optString("name") }, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            val author = entry.optString("author").takeIf { it.isNotBlank() }?.let { "@$it • " } ?: ""
            Text("$author${entry.optString("detail")} • $time", fontSize = 11.sp, color = Subtle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val link = entry.optString("link")
        if (link.isNotBlank()) IconButton(onClick = {
            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }) { Icon(Icons.Rounded.OpenInNew, L.t(R.string.history_open_post), tint = Subtle, modifier = Modifier.size(20.dp)) }
    }
}

// ================================================================== LIVE

@SuppressLint("BatteryLife")
@Composable
private fun LiveScreen() {
    val ctx = LocalContext.current
    var enabled by remember { mutableStateOf(Prefs.bool(ctx, Prefs.KEY_AUTO_LIVE)) }
    var channels by remember { mutableStateOf(Prefs.autoLiveChannels(ctx)) }
    var state by remember { mutableStateOf(Prefs.readAutoLiveState(ctx)) }
    var input by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { while (true) { state = Prefs.readAutoLiveState(ctx); delay(1500) } }

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun setEnabled(v: Boolean) {
        enabled = v
        Prefs.setBool(ctx, Prefs.KEY_AUTO_LIVE, v)
        if (v && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        LiveWatchService.sync(ctx)
    }
    fun addChannel() {
        val h = input.trim().substringAfterLast("tiktok.com/").removePrefix("@").substringBefore('/').substringBefore('?').lowercase()
        if (h.isBlank()) return
        channels = (channels + h).distinct()
        Prefs.setAutoLiveChannels(ctx, channels)
        input = ""
        if (enabled) LiveWatchService.checkNow(ctx)
    }

    Appear(0) { ScreenTitle(L.t(R.string.nav_live), L.t(R.string.live_subtitle), Neon.Pink) }
    Appear(1) { LiveHero(enabled, channels.size, state) { setEnabled(it) } }

    val active = state.keys().asSequence().filter { k ->
        state.optJSONObject(k)?.optString("status").let { it == "recording" || it == "saving" }
    }.toList()
    AnimatedVisibility(active.isNotEmpty(), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        ActiveRecordings(active, state)
    }
    Appear(2) { RecordNowCard() }

    Appear(2) {
        Section(Icons.Rounded.PersonSearch, L.t(R.string.live_followed), if (channels.isEmpty()) L.t(R.string.live_followed_empty) else L.p(R.plurals.live_channels_count, channels.size), accent = Neon.Pink) {
            OutlinedTextField(
                value = input, onValueChange = { input = it }, singleLine = true,
                placeholder = { Text(L.t(R.string.live_add_hint)) },
                leadingIcon = { Icon(Icons.Rounded.AlternateEmail, null) },
                trailingIcon = {
                    FilledIconButton(onClick = { addChannel() }, enabled = input.isNotBlank(),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Neon.Pink, contentColor = Neon.OnAccent)) {
                        Icon(Icons.Rounded.Add, L.t(R.string.action_add))
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { addChannel() }),
                textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
                shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)
            )
            channels.forEach { h ->
                key(h) {
                    ChannelRow(h, state.optJSONObject(h), enabled,
                        onStop = { LiveWatchService.stop(ctx, h) },
                        onRecord = { LiveWatchService.recordNow(ctx, h) },
                        onRemove = {
                            channels = channels - h
                            Prefs.setAutoLiveChannels(ctx, channels)
                            LiveWatchService.sync(ctx)
                        })
                }
            }
            if (channels.isNotEmpty() && enabled) {
                TextButton(onClick = { LiveWatchService.checkNow(ctx) }, modifier = Modifier.align(Alignment.End)) {
                    Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(L.t(R.string.live_check_now))
                }
            }
        }
    }

    Appear(3) {
        Section(Icons.Rounded.Tune, L.t(R.string.live_settings), accent = Neon.Pink) {
            SegmentedPref(L.t(R.string.live_trigger), Prefs.KEY_AUTO_LIVE_TRIGGER, listOf(
                Opt(Prefs.AUTO_LIVE_TRIGGER_ANY, L.t(R.string.live_trigger_any), Icons.Rounded.PlayCircle),
                Opt(Prefs.AUTO_LIVE_TRIGGER_START, L.t(R.string.live_trigger_start), Icons.Rounded.NewReleases)
            ), description = L.t(R.string.live_trigger_desc), accent = Neon.Pink) { LiveWatchService.checkNow(ctx) }
            SegmentedPref(L.t(R.string.live_notify), Prefs.KEY_AUTO_LIVE_NOTIFY, listOf(
                Opt(Prefs.AUTO_LIVE_NOTIFY_PROGRESS, L.t(R.string.live_notify_progress), Icons.Rounded.Speed),
                Opt(Prefs.AUTO_LIVE_NOTIFY_MINIMAL, L.t(R.string.live_notify_minimal), Icons.Rounded.NotificationsNone)
            ), description = L.t(R.string.live_notify_desc), accent = Neon.Pink)
            SegmentedPref(L.t(R.string.live_quality), Prefs.KEY_AUTO_LIVE_QUALITY, listOf(
                Opt("origin", L.t(R.string.live_quality_source)), Opt("uhd", "1080p"), Opt("hd", "720p"), Opt("sd", "480p")
            ), accent = Neon.Pink)
            SegmentedPref(L.t(R.string.live_interval), Prefs.KEY_AUTO_LIVE_INTERVAL, listOf(
                Opt("30", L.t(R.string.live_interval_30s)), Opt("60", L.t(R.string.live_interval_1m)), Opt("120", L.t(R.string.live_interval_2m)), Opt("300", L.t(R.string.live_interval_5m))
            ), description = L.t(R.string.live_interval_desc), accent = Neon.Pink) { LiveWatchService.checkNow(ctx) }
            SegmentedPref(L.t(R.string.live_parallel), Prefs.KEY_LIVE_MAX_PARALLEL, listOf(
                Opt("0", L.t(R.string.live_parallel_unlimited)), Opt("2", "2"), Opt("3", "3"), Opt("5", "5")
            ), description = L.t(R.string.live_parallel_desc), accent = Neon.Pink)
        }
    }

    Appear(4) { com.mts.tikmod.ui.CaptionsSection() }

    Appear(4) { com.mts.tikmod.ui.ChatRecordingSection() }

    Appear(4) { LiveHistorySection(channels, onFollow = { h ->
        channels = (channels + h).distinct()
        Prefs.setAutoLiveChannels(ctx, channels)
        LiveWatchService.sync(ctx)
    }) }

    Appear(5) {
        val pm = ctx.getSystemService(PowerManager::class.java)
        var exempt by remember { mutableStateOf(pm.isIgnoringBatteryOptimizations(ctx.packageName)) }
        Section(Icons.Rounded.BatteryChargingFull, L.t(R.string.live_reliability), accent = Neon.Lime) {
            ToggleRowRaw(
                L.t(R.string.live_battery), if (exempt) L.t(R.string.live_battery_on) else L.t(R.string.live_battery_off),
                exempt, Icons.Rounded.BatterySaver, Neon.Lime
            ) {
                runCatching {
                    ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
                }
                exempt = pm.isIgnoringBatteryOptimizations(ctx.packageName)
            }
            Hint(L.t(R.string.live_saved_hint))
        }
    }
}

/** LIVE streams I entered in TikTok (recorded by the module, kept on this phone only). */
@Composable
private fun LiveHistorySection(followed: List<String>, onFollow: (String) -> Unit) {
    val ctx = LocalContext.current
    var list by remember { mutableStateOf(Prefs.readLiveHistory(ctx)) }
    var showAll by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { while (true) { delay(4000); list = Prefs.readLiveHistory(ctx) } }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        icon = { Icon(Icons.Rounded.DeleteSweep, null) },
        title = { Text(L.t(R.string.live_history_clear_q)) },
        confirmButton = { TextButton(onClick = { Prefs.clearLiveHistory(ctx); list = emptyList(); confirmClear = false }) { Text(L.t(R.string.action_clear)) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(L.t(R.string.action_cancel)) } }
    )
    Section(Icons.Rounded.History, L.t(R.string.live_history_title), L.t(R.string.live_history_sub), accent = Neon.Pink, collapsible = true, initiallyExpanded = true) {
        ToggleRow(L.t(R.string.live_history_toggle), L.t(R.string.live_history_toggle_desc), Prefs.KEY_LIVE_HISTORY, Icons.Rounded.ManageHistory, Neon.Pink)
        if (list.isEmpty()) Hint(L.t(R.string.live_history_empty))
        (if (showAll) list else list.take(6)).forEach { e ->
            val handle = e.optString("handle")
            Row(
                Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                    .clickable {
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.tiktok.com/@$handle/live")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }.padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(38.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Neon.Pink, Neon.Violet))),
                    contentAlignment = Alignment.Center
                ) { Text(handle.take(1).uppercase(), fontWeight = FontWeight.Black, color = Neon.OnAccent) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("@$handle", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val time = DateUtils.getRelativeTimeSpanString(e.optLong("time"), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
                    Text(listOf(e.optString("title"), time).filter { it.isNotBlank() }.joinToString(" • "), fontSize = 11.5.sp, color = Subtle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (handle.isNotBlank() && handle.lowercase() !in followed) IconButton(onClick = { onFollow(handle.lowercase()) }) {
                    Icon(Icons.Rounded.PersonAddAlt1, L.t(R.string.live_history_follow), tint = Neon.Pink)
                }
            }
        }
        if (list.size > 6) TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) L.t(R.string.history_show_less) else L.p(R.plurals.history_show_all, list.size)) }
        if (list.isNotEmpty()) TextButton(onClick = { confirmClear = true }, modifier = Modifier.align(Alignment.End)) { Text(L.t(R.string.history_clear_button)) }
    }
}

/** Every recording that's running right now (auto + manual), with live time/size and stop buttons. */
@Composable
private fun ActiveRecordings(handles: List<String>, state: JSONObject) {
    val ctx = LocalContext.current
    Section(Icons.Rounded.FiberManualRecord, L.t(R.string.live_recording_now), L.p(R.plurals.live_parallel_count, handles.size), accent = Neon.Pink) {
        handles.forEach { h ->
            val st = state.optJSONObject(h) ?: return@forEach
            val saving = st.optString("status") == "saving"
            Row(
                Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(16.dp))
                    .background(Neon.Pink.copy(alpha = 0.10f)).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PulsingDot(if (saving) Neon.Amber else Neon.Pink, 10.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(st.optString("nick").ifBlank { h }, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (saving) L.t(R.string.live_saving) else "${TikModDownloader.formatDuration(st.optLong("dur"))}  •  ${TikModDownloader.formatSize(st.optLong("bytes"))}  •  " +
                            if (st.optBoolean("manual")) L.t(R.string.live_manual) else L.t(R.string.live_auto),
                        fontSize = 12.sp, color = Subtle
                    )
                }
                if (!saving) IconButton(onClick = { LiveWatchService.stop(ctx, h) }) { Icon(Icons.Rounded.StopCircle, L.t(R.string.action_stop_save), tint = Neon.Pink) }
            }
        }
        if (handles.size > 1) TextButton(onClick = { LiveWatchService.stopAll(ctx) }, modifier = Modifier.align(Alignment.End)) {
            Icon(Icons.Rounded.Stop, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(L.t(R.string.action_stop_save_all))
        }
    }
}

/** Start recording any channel right now, followed or not. */
@Composable
private fun RecordNowCard() {
    val ctx = LocalContext.current
    var input by remember { mutableStateOf("") }
    fun go() {
        val h = input.trim().substringAfterLast("tiktok.com/").removePrefix("@").substringBefore('/').substringBefore('?').lowercase()
        if (h.isBlank()) return
        LiveWatchService.recordNow(ctx, h)
        input = ""
    }
    Section(Icons.Rounded.Videocam, L.t(R.string.live_instant), L.t(R.string.live_instant_sub), accent = Neon.Pink) {
        OutlinedTextField(
            value = input, onValueChange = { input = it }, singleLine = true,
            placeholder = { Text(L.t(R.string.live_instant_hint)) },
            leadingIcon = { Icon(Icons.Rounded.AlternateEmail, null) },
            trailingIcon = {
                FilledIconButton(onClick = { go() }, enabled = input.isNotBlank(),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Neon.Pink, contentColor = Neon.OnAccent)) {
                    Icon(Icons.Rounded.FiberManualRecord, L.t(R.string.action_record_now))
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { go() }),
            textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
            shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)
        )
        Hint(L.t(R.string.live_instant_footer))
    }
}

@Composable
private fun LiveHero(enabled: Boolean, count: Int, state: JSONObject, onToggle: (Boolean) -> Unit) {
    val recording = state.keys().asSequence().count { k -> state.optJSONObject(k)?.optString("status") == "recording" }
    val t = rememberInfiniteTransition(label = "radar")
    val sweep by t.animateFloat(0f, 360f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "sweep")
    val ring by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing)), label = "ring")
    GlassCard(glow = enabled, padding = 20.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val c = if (enabled) Neon.Pink else Color.Gray
                    drawCircle(c.copy(alpha = 0.12f))
                    drawCircle(c.copy(alpha = 0.35f), style = Stroke(1.dp.toPx()), radius = size.minDimension / 2 * 0.66f)
                    drawCircle(c.copy(alpha = 0.25f), style = Stroke(1.dp.toPx()), radius = size.minDimension / 2 * 0.33f)
                    if (enabled) drawCircle(c.copy(alpha = (1f - ring) * 0.6f), style = Stroke(2.dp.toPx()), radius = size.minDimension / 2 * ring)
                }
                if (enabled) Canvas(Modifier.fillMaxSize().rotate(sweep)) {
                    drawArc(Brush.sweepGradient(listOf(Color.Transparent, Neon.Pink.copy(alpha = 0.55f))), 270f, 90f, true)
                }
                Icon(if (recording > 0) Icons.Rounded.FiberManualRecord else Icons.Rounded.Sensors, null,
                    tint = if (enabled) Neon.Pink else Subtle, modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(L.t(R.string.live_auto_title), fontWeight = FontWeight.Black, fontSize = 20.sp)
                Text(
                    when {
                        recording > 0 -> L.p(R.plurals.live_recording_count, recording)
                        !enabled -> L.t(R.string.state_off)
                        count == 0 -> L.t(R.string.live_add_channel_first)
                        else -> L.p(R.plurals.live_scanning, count)
                    },
                    fontSize = 13.sp, color = if (recording > 0) Neon.Pink else Subtle
                )
            }
            Switch(enabled, onToggle, colors = SwitchDefaults.colors(checkedTrackColor = Neon.Pink, checkedThumbColor = Neon.OnAccent))
        }
    }
}

@Composable
private fun ChannelRow(handle: String, st: JSONObject?, enabled: Boolean, onStop: () -> Unit, onRecord: () -> Unit, onRemove: () -> Unit) {
    val status = st?.optString("status").orEmpty()
    val nick = st?.optString("nick").orEmpty()
    val (label, color) = when {
        !enabled -> L.t(R.string.ch_watching_off) to Subtle
        st?.has("error") == true && status.isBlank() -> L.t(R.string.ch_network_error) to Neon.Amber
        status == "recording" -> L.t(R.string.ch_recording, TikModDownloader.formatDuration(st!!.optLong("dur")), TikModDownloader.formatSize(st.optLong("bytes"))) to Neon.Pink
        status == "saving" -> L.t(R.string.ch_saving) to Neon.Amber
        status == "live" -> L.t(R.string.ch_live_waiting) to Neon.Lime
        status == "skipped" -> L.t(R.string.ch_live_stopped) to Neon.Amber
        status == "missing" -> L.t(R.string.ch_not_found) to Neon.Amber
        status == "queued" -> L.t(R.string.ch_queued) to Neon.Amber
        status == "nospace" -> L.t(R.string.ch_no_space) to Neon.Amber
        status == "offline" -> L.t(R.string.ch_offline) + (st?.optLong("checked")?.takeIf { it > 0 }?.let { L.t(R.string.ch_checked) + DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.SECOND_IN_MILLIS) } ?: "") to Subtle
        else -> L.t(R.string.ch_checking) to Subtle
    }
    val rec = status == "recording"
    val canRecord = status == "live" || status == "skipped" || status == "queued"
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp).clip(RoundedCornerShape(18.dp))
            .background(if (rec) Neon.Pink.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(44.dp).clip(CircleShape)
                    .background(Brush.linearGradient(listOf(Neon.Cyan, Neon.Violet, Neon.Pink)))
                    .padding(2.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainer),
                contentAlignment = Alignment.Center
            ) { Text(handle.take(1).uppercase(), fontWeight = FontWeight.Black, fontSize = 18.sp) }
            if (rec || status == "live") Box(Modifier.align(Alignment.BottomEnd)) { PulsingDot(if (rec) Neon.Pink else Neon.Lime, 10.dp) }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(nick.ifBlank { handle }, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("@$handle", fontSize = 11.5.sp, color = Subtle, style = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr))
            Text(label, fontSize = 11.5.sp, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (rec) IconButton(onClick = onStop) { Icon(Icons.Rounded.StopCircle, L.t(R.string.action_stop_save), tint = Neon.Pink) }
        if (canRecord) IconButton(onClick = onRecord) { Icon(Icons.Rounded.FiberManualRecord, L.t(R.string.action_record_now), tint = Neon.Pink) }
        IconButton(onClick = onRemove) { Icon(Icons.Rounded.Close, L.t(R.string.action_remove), tint = Subtle) }
    }
}

// ================================================================== CHAT

@Composable
private fun ChatScreen() {
    Appear(0) { ScreenTitle(L.t(R.string.nav_chat), L.t(R.string.chat_subtitle), Neon.Violet) }
    Appear(1) {
        Section(Icons.Rounded.DoneAll, L.t(R.string.chat_receipts), L.t(R.string.chat_receipts_sub), accent = Neon.Violet) {
            SegmentedPref(L.t(R.string.chat_mode), Prefs.KEY_READ_RECEIPTS_MODE, listOf(
                Opt(Prefs.READ_RECEIPTS_MODE_ON_REPLY, L.t(R.string.chat_mode_reply), Icons.Rounded.Reply),
                Opt(Prefs.READ_RECEIPTS_MODE_ALWAYS_BLOCK, L.t(R.string.chat_mode_never), Icons.Rounded.VisibilityOff),
                Opt(Prefs.READ_RECEIPTS_MODE_NORMAL, L.t(R.string.chat_mode_normal), Icons.Rounded.Visibility)
            ), accent = Neon.Violet)
            Hint(L.t(R.string.chat_receipts_hint))
        }
    }
    Appear(2) {
        Section(Icons.Rounded.VisibilityOff, L.t(R.string.chat_presence), accent = Neon.Violet) {
            ToggleRow(L.t(R.string.chat_hide_typing), L.t(R.string.chat_hide_typing_desc), Prefs.KEY_DISABLE_TYPING_INDICATOR, Icons.Rounded.KeyboardHide, Neon.Violet)
            ToggleRow(L.t(R.string.chat_hide_online), L.t(R.string.chat_hide_online_desc), Prefs.KEY_HIDE_ONLINE_STATUS, Icons.Rounded.WifiOff, Neon.Violet)
            ToggleRow(L.t(R.string.chat_hide_views), L.t(R.string.chat_hide_views_desc), Prefs.KEY_HIDE_VIEWS, Icons.Rounded.PlayDisabled, Neon.Violet, badge = L.t(R.string.tag_experimental))
        }
    }
    Appear(3) {
        Section(Icons.Rounded.DeleteSweep, L.t(R.string.chat_deleted), L.t(R.string.chat_deleted_sub), accent = Neon.Violet) {
            ToggleRow(L.t(R.string.chat_keep_deleted), L.t(R.string.chat_keep_deleted_desc), Prefs.KEY_ANTI_RECALL, Icons.Rounded.Restore, Neon.Violet)
            ToggleRow(L.t(R.string.chat_save_deleted_media), L.t(R.string.chat_save_deleted_media_desc), Prefs.KEY_SAVE_DELETED_MEDIA, Icons.Rounded.SaveAlt, Neon.Violet)
        }
    }
}

// ================================================================== FEED

@Composable
private fun FeedScreen() {
    val ctx = LocalContext.current
    val blocked = remember { Prefs.readStat(ctx, Prefs.STAT_ADS_BLOCKED) }
    Appear(0) { ScreenTitle(L.t(R.string.nav_feed), L.t(R.string.feed_subtitle), Neon.Amber) }
    Appear(1) {
        GlassCard(padding = 18.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(Icons.Rounded.Shield, Neon.Amber, 52.dp)
                Spacer(Modifier.width(16.dp))
                Column {
                    CountUp(blocked, 32.sp, Neon.Amber)
                    Text(L.t(R.string.feed_ads_counter), fontSize = 12.5.sp, color = Subtle)
                }
            }
        }
    }
    Appear(2) {
        Section(Icons.Rounded.Block, L.t(R.string.quick_ads), accent = Neon.Amber) {
            ToggleRow(L.t(R.string.feed_ads), L.t(R.string.feed_ads_desc), Prefs.KEY_REMOVE_ADS, Icons.Rounded.Campaign, Neon.Amber)
            ToggleRow(L.t(R.string.feed_splash), L.t(R.string.feed_splash_desc), Prefs.KEY_BLOCK_SPLASH_ADS, Icons.Rounded.FastForward, Neon.Amber)
            ToggleRow(L.t(R.string.feed_comment_ads), L.t(R.string.feed_comment_ads_desc), Prefs.KEY_HIDE_COMMENT_ADS, Icons.Rounded.CommentsDisabled, Neon.Amber)
            ToggleRow(L.t(R.string.feed_paid), L.t(R.string.feed_paid_desc), Prefs.KEY_HIDE_PAID_PARTNERSHIP, Icons.Rounded.Handshake, Neon.Amber)
        }
    }
    Appear(3) {
        Section(Icons.Rounded.FilterAlt, L.t(R.string.feed_clean), L.t(R.string.feed_clean_sub), accent = Neon.Amber) {
            ToggleRow(L.t(R.string.feed_live), L.t(R.string.feed_live_desc), Prefs.KEY_HIDE_FEED_LIVE, Icons.Rounded.Sensors, Neon.Amber)
            ToggleRow(L.t(R.string.feed_shop), L.t(R.string.feed_shop_desc), Prefs.KEY_HIDE_SHOP, Icons.Rounded.ShoppingBag, Neon.Amber)
            ToggleRow(L.t(R.string.card_photos), L.t(R.string.feed_photos_desc), Prefs.KEY_HIDE_PHOTO_POSTS, Icons.Rounded.Collections, Neon.Amber)
            Hint(L.t(R.string.feed_hint))
        }
    }
    Appear(4) {
        Section(Icons.Rounded.Speed, L.t(R.string.feed_playback), accent = Neon.Cyan) {
            SegmentedPref(L.t(R.string.feed_speed), Prefs.KEY_PLAYBACK_SPEED, listOf(
                Opt("0.25", "0.25×"), Opt("0.5", "0.5×"), Opt("1.0", "1×"),
                Opt("1.5", "1.5×"), Opt("2.0", "2×"), Opt("3.0", "3×")
            ), accent = Neon.Cyan)
            ToggleRow(L.t(R.string.feed_seekbar), L.t(R.string.feed_seekbar_desc), Prefs.KEY_FORCE_SEEKBAR, Icons.Rounded.Timeline, Neon.Cyan)
            ToggleRow(L.t(R.string.feed_mute), L.t(R.string.feed_mute_desc), Prefs.KEY_MUTE_DEFAULT, Icons.Rounded.VolumeOff, Neon.Cyan)
        }
    }
    Appear(5) {
        Section(Icons.Rounded.FilterList, L.t(R.string.feed_filters), L.t(R.string.feed_filters_sub), accent = Neon.Amber, collapsible = true, initiallyExpanded = false) {
            ToggleRow(L.t(R.string.feed_reposts), L.t(R.string.feed_reposts_desc), Prefs.KEY_HIDE_REPOSTS, Icons.Rounded.Repeat, Neon.Amber)
            ToggleRow(L.t(R.string.feed_cards), L.t(R.string.feed_cards_desc), Prefs.KEY_CLEAN_FEED, Icons.Rounded.Dashboard, Neon.Amber)
            ListEditor(L.t(R.string.feed_keywords), L.t(R.string.feed_keywords_hint), Prefs.KEY_FEED_KEYWORDS, Icons.Rounded.Tag)
            ListEditor(L.t(R.string.feed_creators), L.t(R.string.feed_creators_hint), Prefs.KEY_MUTED_CREATORS, Icons.Rounded.PersonOff)
            DurationFilter()
            Hint(L.t(R.string.feed_filters_hint))
        }
    }
}

/** A multi-line text box bound to a pref (comma or newline separated list). */
@Composable
private fun ListEditor(title: String, hint: String, key: String, icon: ImageVector) {
    val ctx = LocalContext.current
    var value by remember { mutableStateOf(Prefs.string(ctx, key)) }
    Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Neon.Amber, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(10.dp))
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        }
        OutlinedTextField(
            value = value, onValueChange = { value = it },
            placeholder = { Text(hint, fontSize = 13.sp, color = Subtle) },
            shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(), minLines = 1, maxLines = 4,
            trailingIcon = {
                IconButton(onClick = {
                    Prefs.setString(ctx, key, value.trim()); Toast.makeText(ctx, L.t(R.string.toast_saved), Toast.LENGTH_SHORT).show()
                }) { Icon(Icons.Rounded.Check, L.t(R.string.action_save), tint = Neon.Amber) }
            }
        )
    }
}

/** Two number boxes: hide videos shorter than / longer than N seconds (blank = off). */
@Composable
private fun DurationFilter() {
    val ctx = LocalContext.current
    var minv by remember { mutableStateOf(Prefs.string(ctx, Prefs.KEY_HIDE_SHORT).let { if (it == "0") "" else it }) }
    var maxv by remember { mutableStateOf(Prefs.string(ctx, Prefs.KEY_HIDE_LONG).let { if (it == "0") "" else it }) }
    val ltr = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr)
    Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Timer, null, tint = Neon.Amber, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(10.dp))
            Text(L.t(R.string.feed_duration), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(minv, { v -> minv = v.filter { it.isDigit() } }, Modifier.weight(1f), singleLine = true, textStyle = ltr,
                label = { Text(L.t(R.string.feed_duration_min)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(16.dp))
            OutlinedTextField(maxv, { v -> maxv = v.filter { it.isDigit() } }, Modifier.weight(1f), singleLine = true, textStyle = ltr,
                label = { Text(L.t(R.string.feed_duration_max)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(16.dp))
        }
        TextButton(onClick = {
            Prefs.setString(ctx, Prefs.KEY_HIDE_SHORT, minv.ifBlank { "0" }); Prefs.setString(ctx, Prefs.KEY_HIDE_LONG, maxv.ifBlank { "0" })
            Toast.makeText(ctx, L.t(R.string.toast_saved), Toast.LENGTH_SHORT).show()
        }) { Text(L.t(R.string.action_save)) }
    }
}
