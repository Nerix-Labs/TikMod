package com.mts.tikmod

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
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    TikModApp(startTab, onThemeChange = { themeKey = it })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        startTab = intent.getStringExtra("tab")
    }
}

private enum class Screen(val icon: ImageVector, val label: String, val accent: Color) {
    HOME(Icons.Rounded.SpaceDashboard, "ראשי", Neon.Cyan),
    DOWNLOADS(Icons.Rounded.Download, "הורדות", Neon.Cyan),
    LIVE(Icons.Rounded.Sensors, "לייב", Neon.Pink),
    CHAT(Icons.Rounded.Forum, "צ'אט", Neon.Violet),
    FEED(Icons.Rounded.AutoAwesome, "פיד", Neon.Amber)
}

@Composable
private fun TikModApp(startTab: String?, onThemeChange: (String) -> Unit) {
    val ctx = LocalContext.current
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    LaunchedEffect(startTab) { if (startTab == "live") screen = Screen.LIVE }
    val active = remember { Prefs.isModuleActive(ctx) }

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
                    Screen.HOME -> HomeScreen(active, onNavigate = { screen = it }, onThemeChange = onThemeChange)
                    Screen.DOWNLOADS -> DownloadsScreen()
                    Screen.LIVE -> LiveScreen()
                    Screen.CHAT -> ChatScreen()
                    Screen.FEED -> FeedScreen()
                }
            }
        }
        FloatingNav(screen, Modifier.align(Alignment.BottomCenter)) { screen = it }
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
private fun HomeScreen(active: Boolean, onNavigate: (Screen) -> Unit, onThemeChange: (String) -> Unit) {
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
                    Text("גרסה ${BuildConfig.VERSION_NAME} • TikTok 47.7.3", fontSize = 12.sp, color = Subtle)
                    Spacer(Modifier.height(12.dp))
                    Row(
                        Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)).padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PulsingDot(if (active) Neon.Lime else Neon.Pink, 8.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(if (active) "מחובר לטיקטוק" else "המודול לא פעיל", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                CoreOrb(active)
            }
        }
    }
    if (!active) Appear(1) {
        Section(Icons.Rounded.WarningAmber, "צריך להפעיל ב-LSPosed", "4 צעדים קצרים", accent = Neon.Amber) {
            Text("1. פתח את LSPosed Manager\n2. הפעל את TikMod ברשימת המודולים\n3. סמן את TikTok בטווח (Scope)\n4. לחץ למטה על \"הפעל מחדש את טיקטוק\"", fontSize = 13.sp, lineHeight = 21.sp, modifier = Modifier.padding(horizontal = 10.dp))
        }
    }
    Appear(2) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(Icons.Rounded.Download, history.size.toLong(), "הורדות", Neon.Cyan, Modifier.weight(1f))
            StatTile(Icons.Rounded.Block, ads, "פרסומות נחסמו", Neon.Amber, Modifier.weight(1f))
            StatTile(Icons.Rounded.Sensors, channels.toLong(), "ערוצים במעקב", Neon.Pink, Modifier.weight(1f))
        }
    }
    Appear(3) { SectionLabel("קיצורים") }
    Appear(4) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickTile(Icons.Rounded.Download, "הורדה בלי סימן מים", Prefs.KEY_REMOVE_WATERMARK, Neon.Cyan, Modifier.weight(1f)) { Prefs.setForceDownloadEnabled(ctx, it) }
                QuickTile(Icons.Rounded.DeleteSweep, "הודעות שנמחקו", Prefs.KEY_ANTI_RECALL, Neon.Violet, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickTile(Icons.Rounded.Block, "חסימת פרסומות", Prefs.KEY_REMOVE_ADS, Neon.Amber, Modifier.weight(1f))
                QuickTile(Icons.Rounded.KeyboardHide, "הסתרת \"מקליד/ה\"", Prefs.KEY_DISABLE_TYPING_INDICATOR, Neon.Violet, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickTile(Icons.Rounded.FiberManualRecord, "הקלטת לייב אוטומטית", Prefs.KEY_AUTO_LIVE, Neon.Pink, Modifier.weight(1f)) { LiveWatchService.sync(ctx) }
                QuickTile(Icons.Rounded.Screenshot, "צילום מסך בכל מקום", Prefs.KEY_UNRESTRICT_ALL, Neon.Lime, Modifier.weight(1f))
            }
        }
    }
    if (history.isNotEmpty()) {
        Appear(5) { SectionLabel("הורדות אחרונות") }
        Appear(6) {
            GlassCard(padding = 10.dp) {
                history.take(3).forEach { HistoryRow(it) }
                TextButton(onClick = { onNavigate(Screen.DOWNLOADS) }, modifier = Modifier.align(Alignment.End)) { Text("לכל ההורדות") }
            }
        }
    }
    Appear(7) { SectionLabel("מערכת") }
    Appear(8) { SystemSection(onThemeChange) }
}

@Composable
private fun SystemSection(onThemeChange: (String) -> Unit) {
    val ctx = LocalContext.current
    val createFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let {
            runCatching {
                ctx.contentResolver.openOutputStream(it)?.use { s -> s.write(Prefs.exportToJson(ctx).toByteArray()) }
                Toast.makeText(ctx, "ההגדרות נשמרו לקובץ", Toast.LENGTH_SHORT).show()
            }
        }
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            runCatching {
                val json = ctx.contentResolver.openInputStream(it)?.bufferedReader()?.use { r -> r.readText() }
                if (json != null && Prefs.importFromJson(ctx, json)) Toast.makeText(ctx, "ההגדרות יובאו", Toast.LENGTH_SHORT).show()
            }
        }
    }
    Section(Icons.Rounded.Palette, "מראה", accent = Neon.Violet) {
        SegmentedPref("ערכת צבעים", Prefs.KEY_UI_THEME, listOf(
            Opt("neon", "ניאון", Icons.Rounded.Bolt), Opt("dynamic", "Material You", Icons.Rounded.ColorLens), Opt("system", "מערכת", Icons.Rounded.Contrast)
        ), accent = Neon.Violet, onChange = onThemeChange)
    }
    Section(Icons.Rounded.Tune, "ניהול", "גיבוי, איפוס והפעלה מחדש", accent = Neon.Cyan, collapsible = true, initiallyExpanded = false) {
        ToggleRow("צילום והקלטת מסך בכל מקום", "מבטל את החסימה במסכים מוגנים בטיקטוק", Prefs.KEY_UNRESTRICT_ALL, Icons.Rounded.Screenshot, Neon.Lime)
        Hairline()
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(Icons.Rounded.Upload, "ייצוא", Modifier.weight(1f)) { createFile.launch("tikmod_config.json") }
            ActionButton(Icons.Rounded.FileOpen, "ייבוא", Modifier.weight(1f)) { openFile.launch("application/json") }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(Icons.Rounded.CleaningServices, "איפוס זיכרון", Modifier.weight(1f)) {
                Prefs.resetDiscoveryCache(ctx); Toast.makeText(ctx, "הזיכרון אופס", Toast.LENGTH_SHORT).show()
            }
            ActionButton(Icons.Rounded.RestartAlt, "הפעל מחדש את טיקטוק", Modifier.weight(1f), accent = Neon.Pink) {
                Prefs.forceRestartTikTok(ctx); Toast.makeText(ctx, "טיקטוק ייטען מחדש בפתיחה הבאה", Toast.LENGTH_SHORT).show()
            }
        }
        Hint("כל שינוי מגיע לטיקטוק מיד. רק הפעלה או כיבוי של רכיב שלם דורשים הפעלה מחדש של טיקטוק.")
    }
    AboutCard()
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
                Text("TikMod ${BuildConfig.VERSION_NAME} • קוד פתוח, רישיון GPL-3.0", fontSize = 12.5.sp, color = Subtle)
            }
            IconButton(onClick = {
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/neriyaomesi/TikMod")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }) { Icon(Icons.Rounded.Code, "קוד המקור", tint = Neon.Cyan) }
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
    Appear(0) { ScreenTitle("הורדות", "מהפוסט שפתחת, באיכות הכי גבוהה", Neon.Cyan) }
    Appear(1) {
        Section(Icons.Rounded.Download, "מנוע ההורדות", "כרטיס TikMod בחלונית השיתוף") {
            ToggleRow("הפעלת מנוע ההורדות", "מציג את הכרטיס החכם בכל שיתוף", Prefs.KEY_REMOVE_WATERMARK, Icons.Rounded.PowerSettingsNew) { Prefs.setForceDownloadEnabled(ctx, it) }
            Hairline()
            SegmentedPref("איכות וידאו", Prefs.KEY_DOWNLOAD_QUALITY, listOf(
                Opt(Prefs.DOWNLOAD_QUALITY_MAX, "מקסימלית"), Opt(Prefs.DOWNLOAD_QUALITY_HD, "HD"), Opt(Prefs.DOWNLOAD_QUALITY_SD, "חסכונית")
            ))
            SegmentedPref("קידוד", Prefs.KEY_VIDEO_CODEC, listOf(
                Opt(Prefs.VIDEO_CODEC_BEST, "הכי טוב · H.265"), Opt(Prefs.VIDEO_CODEC_H264, "תואם הכל · H.264")
            ), description = "H.264 נפתח בכל מכשיר ונגן, H.265 קטן יותר ובאיכות גבוהה יותר")
        }
    }
    Appear(2) {
        Section(Icons.Rounded.Share, "שיתוף חכם", "מה נשלח כשבוחרים אפליקציה בחלונית השיתוף", badge = "חדש") {
            SegmentedPref("ברירת מחדל", Prefs.KEY_SHARE_MODE, listOf(
                Opt(Prefs.SHARE_MODE_MEDIA, "הקובץ", Icons.Rounded.Movie),
                Opt(Prefs.SHARE_MODE_MEDIA_LINK, "קובץ+קישור", Icons.Rounded.AddLink),
                Opt(Prefs.SHARE_MODE_LINK, "קישור", Icons.Rounded.Link)
            ))
            Hint("סרטון נשלח כ-MP4 נקי, קרוסלה נשלחת כל התמונות יחד, ושידור חי נשאר קישור. בכרטיס יש כפתור להחלפת המצב לשיתוף אחד בלבד.")
        }
    }
    Appear(3) {
        Section(Icons.Rounded.Widgets, "כפתורים בכרטיס", "מה מופיע בכרטיס TikMod", collapsible = true, initiallyExpanded = false) {
            ToggleRow("פוסטי תמונות", "כל התמונות בקרוסלה, באיכות מקורית", Prefs.KEY_DOWNLOAD_PHOTOS, Icons.Rounded.Collections)
            ToggleRow("תמונות כ-JPEG", "ממיר HEIC/WebP לפורמט שנפתח בכל מקום", Prefs.KEY_IMAGES_AS_JPEG, Icons.Rounded.Image)
            ToggleRow("שמע", "הסאונד המקורי או פס הקול של הסרטון", Prefs.KEY_DOWNLOAD_AUDIO, Icons.Rounded.MusicNote)
            SegmentedPref("מקור השמע", Prefs.KEY_AUDIO_SOURCE, listOf(Opt(Prefs.AUDIO_SOURCE_ORIGINAL, "סאונד מקורי"), Opt(Prefs.AUDIO_SOURCE_VIDEO, "מתוך הסרטון")))
            ToggleRow("קאבר HD", "הפריים שהיוצר בחר, ברזולוציה מלאה", Prefs.KEY_DOWNLOAD_COVER, Icons.Rounded.Wallpaper)
            ToggleRow("תמונת פרופיל", "תמונת היוצר ב-1080p", Prefs.KEY_DOWNLOAD_AVATAR, Icons.Rounded.AccountCircle)
            ToggleRow("קישור ישיר", "קישור לקובץ הנקי, להורדה במחשב", Prefs.KEY_COPY_LINK_BUTTON, Icons.Rounded.Link)
            Hairline()
            SegmentedPref("כפתור \"שמירה\" של טיקטוק", Prefs.KEY_DOWNLOAD_UI_MODE, listOf(
                Opt(Prefs.DOWNLOAD_UI_MODE_ADD, "להשאיר מקורי"), Opt(Prefs.DOWNLOAD_UI_MODE_REPLACE, "דרך TikMod")
            ))
        }
    }
    Appear(4) {
        Section(Icons.Rounded.FolderOpen, "קבצים ושמירה", collapsible = true, initiallyExpanded = false) {
            SegmentedPref("תיקייה", Prefs.KEY_SAVE_LOCATION, listOf(
                Opt(Prefs.SAVE_LOCATION_MOVIES, "Movies"), Opt(Prefs.SAVE_LOCATION_DCIM, "DCIM"), Opt(Prefs.SAVE_LOCATION_DOWNLOAD, "Download")
            ))
            ToggleRow("תיקייה לכל יוצר", "TikMod/@username/…", Prefs.KEY_AUTHOR_FOLDERS, Icons.Rounded.CreateNewFolder)
            ToggleRow("דילוג על כפילויות", "לא מוריד שוב פוסט שכבר שמור", Prefs.KEY_SKIP_DUPLICATES, Icons.Rounded.FilterNone)
            ToggleRow("העתקת תיאור", "התיאור וההאשטגים ללוח בכל הורדה", Prefs.KEY_COPY_CAPTION, Icons.Rounded.ContentCopy)
            ToggleRow("התראת התקדמות", "אחוזים, גודל, ופתיחה או שיתוף בסיום", Prefs.KEY_NOTIFICATION_PROGRESS, Icons.Rounded.NotificationsActive)
            ToggleRow("היסטוריית הורדות", "רשימת ההורדות במסך הזה", Prefs.KEY_DOWNLOAD_HISTORY, Icons.Rounded.History)
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
        .replace("{nickname}", "יוצר").replace("{desc}", "הסרטון הכי טוב").replace("{date}", "20261117_204500")
        .replace("{quality}", "1080p").replace("{type}", "video")
    val ltr = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr)
    Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("תבנית שם קובץ", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        OutlinedTextField(
            value = pattern, onValueChange = { pattern = it }, singleLine = true, textStyle = ltr,
            shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
            supportingText = { Text("$preview.mp4", maxLines = 1, overflow = TextOverflow.Ellipsis, style = ltr) },
            trailingIcon = {
                IconButton(onClick = {
                    Prefs.setString(ctx, Prefs.KEY_CUSTOM_FILENAME_PATTERN, pattern.ifBlank { Prefs.DEFAULT_FILENAME_PATTERN })
                    Toast.makeText(ctx, "התבנית נשמרה", Toast.LENGTH_SHORT).show()
                }) { Icon(Icons.Rounded.Check, "שמור", tint = Neon.Cyan) }
            }
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            presets.forEach { p ->
                AssistChip(onClick = { pattern = p; Prefs.setString(ctx, Prefs.KEY_CUSTOM_FILENAME_PATTERN, p) }, label = { Text(p, fontSize = 11.sp, style = ltr) })
            }
        }
        Text("תגיות: {author} {nickname} {id} {desc} {date} {quality} {type}", fontSize = 11.sp, color = Subtle)
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
            title = { Text("לנקות את ההיסטוריה?") },
            text = { Text("רק הרשימה נמחקת. הקבצים עצמם נשארים בגלריה.") },
            confirmButton = { TextButton(onClick = { Prefs.clearHistory(ctx); history = emptyList(); confirmClear = false }) { Text("נקה") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("ביטול") } }
        )
    }
    Section(Icons.Rounded.History, "הורדות אחרונות", "${history.size} פריטים") {
        Row(Modifier.padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(Icons.Rounded.Refresh, "רענון", Modifier.weight(1f)) { history = Prefs.readHistory(ctx) }
            if (history.isNotEmpty()) ActionButton(Icons.Rounded.DeleteOutline, "ניקוי…", Modifier.weight(1f), accent = Neon.Pink) { confirmClear = true }
        }
        if (history.isEmpty()) {
            Hint("עדיין אין הורדות. בטיקטוק: שיתוף ← כרטיס TikMod.")
        } else {
            (if (showAll) history else history.take(8)).forEach { HistoryRow(it) }
            if (history.size > 8) TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "הצג פחות" else "הצג את כל ${history.size}") }
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
            }.onFailure { Toast.makeText(ctx, "הקובץ לא נמצא (אולי נמחק)", Toast.LENGTH_SHORT).show() }
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
        }) { Icon(Icons.Rounded.OpenInNew, "לפוסט", tint = Subtle, modifier = Modifier.size(20.dp)) }
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

    Appear(0) { ScreenTitle("לייב", "הקלטה אוטומטית של שידורים חיים", Neon.Pink) }
    Appear(1) { LiveHero(enabled, channels.size, state) { setEnabled(it) } }

    Appear(2) {
        Section(Icons.Rounded.PersonSearch, "ערוצים במעקב", if (channels.isEmpty()) "הוסף שם משתמש כדי להתחיל" else "${channels.size} ערוצים", accent = Neon.Pink) {
            OutlinedTextField(
                value = input, onValueChange = { input = it }, singleLine = true,
                placeholder = { Text("@username או קישור לפרופיל") },
                leadingIcon = { Icon(Icons.Rounded.AlternateEmail, null) },
                trailingIcon = {
                    FilledIconButton(onClick = { addChannel() }, enabled = input.isNotBlank(),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Neon.Pink, contentColor = Neon.OnAccent)) {
                        Icon(Icons.Rounded.Add, "הוסף")
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
                        onStop = { ctx.startService(Intent(ctx, LiveWatchService::class.java).setAction(LiveWatchService.ACTION_STOP_REC).putExtra(LiveWatchService.EXTRA_HANDLE, h)) },
                        onRemove = {
                            channels = channels - h
                            Prefs.setAutoLiveChannels(ctx, channels)
                            LiveWatchService.sync(ctx)
                        })
                }
            }
            if (channels.isNotEmpty() && enabled) {
                TextButton(onClick = { LiveWatchService.checkNow(ctx) }, modifier = Modifier.align(Alignment.End)) {
                    Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("בדוק עכשיו")
                }
            }
        }
    }

    Appear(3) {
        Section(Icons.Rounded.Tune, "מתי ואיך מקליטים", accent = Neon.Pink) {
            SegmentedPref("מתי להקליט", Prefs.KEY_AUTO_LIVE_TRIGGER, listOf(
                Opt(Prefs.AUTO_LIVE_TRIGGER_ANY, "כשמשדר (גם עכשיו)", Icons.Rounded.PlayCircle),
                Opt(Prefs.AUTO_LIVE_TRIGGER_START, "רק כשמתחיל", Icons.Rounded.NewReleases)
            ), description = "\"רק כשמתחיל\" לא יצטרף לשידור שכבר רץ כשהפעלת את המעקב", accent = Neon.Pink) { LiveWatchService.checkNow(ctx) }
            SegmentedPref("התראה בזמן הקלטה", Prefs.KEY_AUTO_LIVE_NOTIFY, listOf(
                Opt(Prefs.AUTO_LIVE_NOTIFY_PROGRESS, "זמן ומשקל", Icons.Rounded.Speed),
                Opt(Prefs.AUTO_LIVE_NOTIFY_MINIMAL, "רק התחלה וסיום", Icons.Rounded.NotificationsNone)
            ), description = "\"זמן ומשקל\" מציג התראה חיה עם כפתור עצירה. \"רק התחלה וסיום\" שולח התראה כשההקלטה מתחילה וכשהקובץ נשמר.", accent = Neon.Pink)
            SegmentedPref("איכות", Prefs.KEY_AUTO_LIVE_QUALITY, listOf(
                Opt("origin", "מקור"), Opt("uhd", "1080p"), Opt("hd", "720p"), Opt("sd", "480p")
            ), accent = Neon.Pink)
            SegmentedPref("תדירות בדיקה", Prefs.KEY_AUTO_LIVE_INTERVAL, listOf(
                Opt("30", "30 שנ׳"), Opt("60", "דקה"), Opt("120", "2 דק׳"), Opt("300", "5 דק׳")
            ), description = "בדיקה תכופה יותר תופסת את תחילת השידור מהר יותר, ובדיקה נדירה יותר חוסכת סוללה", accent = Neon.Pink) { LiveWatchService.checkNow(ctx) }
        }
    }

    Appear(4) {
        val pm = ctx.getSystemService(PowerManager::class.java)
        var exempt by remember { mutableStateOf(pm.isIgnoringBatteryOptimizations(ctx.packageName)) }
        Section(Icons.Rounded.BatteryChargingFull, "שיפור אמינות", accent = Neon.Lime) {
            ToggleRowRaw(
                "החרגה מחיסכון בסוללה", if (exempt) "מוחרג: המעקב ממשיך לרוץ גם כשהמסך כבוי" else "מומלץ: בלי זה אנדרואיד עלול להשהות את המעקב",
                exempt, Icons.Rounded.BatterySaver, Neon.Lime
            ) {
                runCatching {
                    ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
                }
                exempt = pm.isIgnoringBatteryOptimizations(ctx.packageName)
            }
            Hint("ההקלטות נשמרות כ-MP4 בתיקיית ההורדות שבחרת, ומופיעות גם ברשימת ההורדות. אפשר עדיין להקליט ידנית מכל שידור דרך כרטיס TikMod בחלונית השיתוף.")
        }
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
                Text("הקלטה אוטומטית", fontWeight = FontWeight.Black, fontSize = 20.sp)
                Text(
                    when {
                        !enabled -> "כבוי"
                        count == 0 -> "הוסף ערוץ כדי להתחיל"
                        recording > 0 -> "מקליט עכשיו $recording ${if (recording == 1) "שידור" else "שידורים"}"
                        else -> "סורק $count ערוצים"
                    },
                    fontSize = 13.sp, color = if (recording > 0) Neon.Pink else Subtle
                )
            }
            Switch(enabled, onToggle, colors = SwitchDefaults.colors(checkedTrackColor = Neon.Pink, checkedThumbColor = Neon.OnAccent))
        }
    }
}

@Composable
private fun ChannelRow(handle: String, st: JSONObject?, enabled: Boolean, onStop: () -> Unit, onRemove: () -> Unit) {
    val status = st?.optString("status").orEmpty()
    val nick = st?.optString("nick").orEmpty()
    val (label, color) = when {
        !enabled -> "המעקב כבוי" to Subtle
        st?.has("error") == true && status.isBlank() -> "שגיאת רשת, מנסה שוב" to Neon.Amber
        status == "recording" -> "מקליט • ${TikModDownloader.formatDuration(st!!.optLong("dur"))} • ${TikModDownloader.formatSize(st.optLong("bytes"))}" to Neon.Pink
        status == "saving" -> "שומר את ההקלטה…" to Neon.Amber
        status == "live" -> "בשידור חי (ממתין להתחלה חדשה)" to Neon.Lime
        status == "skipped" -> "בשידור • עצרת את ההקלטה" to Neon.Amber
        status == "missing" -> "משתמש לא נמצא" to Neon.Amber
        status == "offline" -> "לא משדר" + (st?.optLong("checked")?.takeIf { it > 0 }?.let { " • נבדק " + DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.SECOND_IN_MILLIS) } ?: "") to Subtle
        else -> "בודק…" to Subtle
    }
    val rec = status == "recording"
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
        if (rec) IconButton(onClick = onStop) { Icon(Icons.Rounded.StopCircle, "עצור ושמור", tint = Neon.Pink) }
        IconButton(onClick = onRemove) { Icon(Icons.Rounded.Close, "הסר", tint = Subtle) }
    }
}

// ================================================================== CHAT

@Composable
private fun ChatScreen() {
    Appear(0) { ScreenTitle("צ'אט", "פרטיות בהודעות הישירות", Neon.Violet) }
    Appear(1) {
        Section(Icons.Rounded.DoneAll, "אישורי קריאה", "מתי הצד השני רואה \"נצפה\"", accent = Neon.Violet) {
            SegmentedPref("מצב", Prefs.KEY_READ_RECEIPTS_MODE, listOf(
                Opt(Prefs.READ_RECEIPTS_MODE_ON_REPLY, "אחרי שאני עונה", Icons.Rounded.Reply),
                Opt(Prefs.READ_RECEIPTS_MODE_ALWAYS_BLOCK, "אף פעם", Icons.Rounded.VisibilityOff),
                Opt(Prefs.READ_RECEIPTS_MODE_NORMAL, "רגיל", Icons.Rounded.Visibility)
            ), accent = Neon.Violet)
            Hint("\"אחרי שאני עונה\": קוראים בשקט, וה\"נצפה\" נשלח לצ'אט רק כששולחים בו הודעה. המונה של הודעות שלא נקראו אצלך נשאר מדויק.")
        }
    }
    Appear(2) {
        Section(Icons.Rounded.VisibilityOff, "נוכחות", accent = Neon.Violet) {
            ToggleRow("הסתרת \"מקליד/ה\"", "הצד השני לא רואה שאתה כותב", Prefs.KEY_DISABLE_TYPING_INDICATOR, Icons.Rounded.KeyboardHide, Neon.Violet)
            ToggleRow("הסתרת \"פעיל/ה עכשיו\"", "לא מדווח לשרת על התחברות והתנתקות", Prefs.KEY_HIDE_ONLINE_STATUS, Icons.Rounded.WifiOff, Neon.Violet, badge = "ניסיוני")
        }
    }
    Appear(3) {
        Section(Icons.Rounded.DeleteSweep, "הודעות שנמחקו", "נשארות אצלך עם סימן פח קטן", accent = Neon.Violet) {
            ToggleRow("להשאיר הודעות שנמחקו", "הודעה שהצד השני מחק נשארת גלויה", Prefs.KEY_ANTI_RECALL, Icons.Rounded.Restore, Neon.Violet)
            ToggleRow("לשמור מדיה שנמחקה", "תמונה או סרטון שנמחקו נשמרים לגלריה", Prefs.KEY_SAVE_DELETED_MEDIA, Icons.Rounded.SaveAlt, Neon.Violet)
        }
    }
}

// ================================================================== FEED

@Composable
private fun FeedScreen() {
    val ctx = LocalContext.current
    val blocked = remember { Prefs.readStat(ctx, Prefs.STAT_ADS_BLOCKED) }
    Appear(0) { ScreenTitle("פיד", "פחות רעש, יותר תוכן", Neon.Amber) }
    Appear(1) {
        GlassCard(padding = 18.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(Icons.Rounded.Shield, Neon.Amber, 52.dp)
                Spacer(Modifier.width(16.dp))
                Column {
                    CountUp(blocked, 32.sp, Neon.Amber)
                    Text("פרסומות נחסמו עד עכשיו", fontSize = 12.5.sp, color = Subtle)
                }
            }
        }
    }
    Appear(2) {
        Section(Icons.Rounded.Block, "חסימת פרסומות", accent = Neon.Amber) {
            ToggleRow("פרסומות בפיד", "סרטונים ממומנים מוסרים לפני שהם מוצגים", Prefs.KEY_REMOVE_ADS, Icons.Rounded.Campaign, Neon.Amber)
            ToggleRow("פרסומת פתיחה", "דילוג על מסך הפרסומת בהפעלה", Prefs.KEY_BLOCK_SPLASH_ADS, Icons.Rounded.FastForward, Neon.Amber)
            ToggleRow("פרסומות בתגובות", "תגובות ממומנות מוסתרות", Prefs.KEY_HIDE_COMMENT_ADS, Icons.Rounded.CommentsDisabled, Neon.Amber)
            ToggleRow("שיתופי פעולה בתשלום", "סרטוני \"Paid partnership\" של מותגים", Prefs.KEY_HIDE_PAID_PARTNERSHIP, Icons.Rounded.Handshake, Neon.Amber)
        }
    }
    Appear(3) {
        Section(Icons.Rounded.FilterAlt, "פיד נקי", "מה לא להציג ב\"בשבילך\"", accent = Neon.Amber) {
            ToggleRow("שידורים חיים", "מסתיר שידורים שמשתחלים בין הסרטונים", Prefs.KEY_HIDE_FEED_LIVE, Icons.Rounded.Sensors, Neon.Amber)
            ToggleRow("סרטוני קניות", "סרטונים עם מוצרים וקישורי קנייה", Prefs.KEY_HIDE_SHOP, Icons.Rounded.ShoppingBag, Neon.Amber)
            ToggleRow("פוסטי תמונות", "רק סרטונים בפיד", Prefs.KEY_HIDE_PHOTO_POSTS, Icons.Rounded.Collections, Neon.Amber)
            Hint("הסינון קורה ברשימת הפיד עצמה, כך שאין חורים או סרטונים ריקים. שינוי חל מהעמוד הבא שנטען.")
        }
    }
}
