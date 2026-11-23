package com.mts.tikmod.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.mts.tikmod.Prefs
import com.mts.tikmod.R
import com.mts.tikmod.captions.CaptionLanguages
import com.mts.tikmod.captions.CaptionService
import com.mts.tikmod.ui.theme.Neon
import com.mts.tikmod.utils.L

/** Settings for translated live captions: on/off, the spoken language (with speech packs) and the caption language. */
@Composable
fun CaptionsSection() {
    val ctx = LocalContext.current
    val accent = Neon.Cyan
    var enabled by remember { mutableStateOf(Prefs.bool(ctx, Prefs.KEY_LIVE_CAPTIONS)) }
    var source by remember { mutableStateOf(Prefs.string(ctx, Prefs.KEY_CAPTION_SOURCE).ifBlank { CaptionLanguages.AUTO }) }
    var target by remember { mutableStateOf(Prefs.string(ctx, Prefs.KEY_CAPTION_TARGET)) }
    var installed by remember { mutableStateOf(emptySet<String>()) }
    var available by remember { mutableStateOf(emptySet<String>()) }
    val progress = remember { mutableStateMapOf<String, Int>() } // tag -> 0..99, or -2 while Android handles it
    var pickSource by remember { mutableStateOf(false) }
    var pickTarget by remember { mutableStateOf(false) }
    var denied by remember { mutableStateOf(false) }

    fun refresh() = CaptionService.querySupport(ctx) { inst, avail -> installed = inst; available = avail }
    LaunchedEffect(Unit) { refresh() }

    fun setEnabled(v: Boolean) { enabled = v; Prefs.setBool(ctx, Prefs.KEY_LIVE_CAPTIONS, v) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        denied = !granted
        setEnabled(granted)
    }

    fun download(tag: String) {
        if (progress.containsKey(tag)) return
        progress[tag] = 0
        CaptionService.download(ctx, tag) { state ->
            when (state) {
                100 -> { progress.remove(tag); refresh() }
                -1 -> progress.remove(tag)
                else -> progress[tag] = state
            }
        }
    }

    Section(Icons.Rounded.ClosedCaption, L.t(R.string.captions_title), L.t(R.string.captions_sub), accent = accent, collapsible = true, initiallyExpanded = false) {
        if (!CaptionService.supported()) { Hint(L.t(R.string.caption_unsupported)); return@Section }
        ToggleRowRaw(L.t(R.string.captions_toggle), L.t(R.string.captions_toggle_desc), enabled, Icons.Rounded.Subtitles, accent) { on ->
            // Android's speech recognizer asks its caller for this permission even though no microphone is used.
            if (on && ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                permission.launch(Manifest.permission.RECORD_AUDIO)
            } else setEnabled(on)
        }
        if (denied) Hint(L.t(R.string.captions_need_permission))

        PickerRow(Icons.Rounded.GraphicEq, L.t(R.string.captions_source),
            if (source == CaptionLanguages.AUTO) L.t(R.string.caption_auto) else CaptionLanguages.name(source), accent) { pickSource = true }
        PickerRow(Icons.Rounded.Translate, L.t(R.string.captions_target),
            if (target.isBlank()) L.t(R.string.captions_target_app) else CaptionLanguages.name(target), accent) { pickTarget = true }
        ToggleRow(L.t(R.string.captions_original), L.t(R.string.captions_original_desc), Prefs.KEY_CAPTION_ORIGINAL, accent = accent)
        SegmentedPref(L.t(R.string.captions_size), Prefs.KEY_CAPTION_SIZE, listOf(
            Opt("s", L.t(R.string.caption_size_s)), Opt("m", L.t(R.string.caption_size_m)), Opt("l", L.t(R.string.caption_size_l))
        ), accent = accent)
        SegmentedPref(L.t(R.string.captions_lines), Prefs.KEY_CAPTION_LINES, listOf(
            Opt("1", "1"), Opt("2", "2"), Opt("3", "3"), Opt("4", "4")
        ), accent = accent)
        SegmentedPref(L.t(R.string.captions_bg), Prefs.KEY_CAPTION_BG, listOf(
            Opt("solid", L.t(R.string.caption_bg_solid)), Opt("dim", L.t(R.string.caption_bg_dim)), Opt("none", L.t(R.string.caption_bg_none))
        ), accent = accent)
        ToggleRow(L.t(R.string.captions_outline), L.t(R.string.captions_outline_desc), Prefs.KEY_CAPTION_OUTLINE, accent = accent)
        ToggleRow(L.t(R.string.captions_always), L.t(R.string.captions_always_desc), Prefs.KEY_CAPTION_ALWAYS, accent = accent)
        Hint(L.t(R.string.captions_hint_use))
        Hint(L.t(R.string.captions_hint_privacy))
    }

    if (pickSource) Panel(onDismiss = { pickSource = false }) {
        Text(L.t(R.string.captions_source), fontSize = 24.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(12.dp))
        ChoiceRow(L.t(R.string.caption_auto), L.t(R.string.captions_auto_desc), source == CaptionLanguages.AUTO, accent, Icons.Rounded.AutoAwesome) {
            source = CaptionLanguages.AUTO; Prefs.setString(ctx, Prefs.KEY_CAPTION_SOURCE, source); pickSource = false
        }
        // Languages this phone can recognize: the installed ones first.
        val known = CaptionLanguages.SPEECH.filter { it in installed || it in available }
        known.sortedBy { it !in installed }.forEach { tag ->
            val has = tag in installed
            val p = progress[tag]
            val note = when {
                has -> L.t(R.string.captions_installed)
                p == null -> L.t(R.string.captions_download)
                p == -2 -> L.t(R.string.captions_download_system)
                else -> L.t(R.string.captions_downloading, p)
            }
            ChoiceRow(CaptionLanguages.name(tag), note, source == tag, accent, if (has) null else Icons.Rounded.Download) {
                if (has) { source = tag; Prefs.setString(ctx, Prefs.KEY_CAPTION_SOURCE, tag); pickSource = false }
                else download(tag)
            }
        }
    }

    if (pickTarget) Panel(onDismiss = { pickTarget = false }) {
        Text(L.t(R.string.captions_target), fontSize = 24.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(12.dp))
        (listOf("") + CaptionLanguages.TARGETS).forEach { tag ->
            ChoiceRow(if (tag.isBlank()) L.t(R.string.captions_target_app) else CaptionLanguages.name(tag), null, target == tag, accent, null) {
                target = tag; Prefs.setString(ctx, Prefs.KEY_CAPTION_TARGET, tag); pickTarget = false
            }
        }
    }
}

/** Save the chat of streams you watch to a text file. Works only while TikTok is open on the room. */
@Composable
fun ChatRecordingSection() {
    Section(Icons.Rounded.Forum, L.t(R.string.chat_rec_title), L.t(R.string.chat_rec_sub), accent = Neon.Pink, collapsible = true, initiallyExpanded = false) {
        ToggleRow(L.t(R.string.chat_rec_toggle), L.t(R.string.chat_rec_toggle_desc), Prefs.KEY_RECORD_CHAT, Icons.Rounded.Forum, Neon.Pink)
        ToggleRow(L.t(R.string.chat_in_video), L.t(R.string.chat_in_video_desc), Prefs.KEY_CHAT_IN_VIDEO, Icons.Rounded.Subtitles, Neon.Pink)
        SegmentedPref(L.t(R.string.gifts_in_video), Prefs.KEY_GIFTS_IN_VIDEO, listOf(
            Opt(Prefs.GIFTS_ALL, L.t(R.string.gifts_all)), Opt(Prefs.GIFTS_ANIMATED, L.t(R.string.gifts_animated)),
            Opt(Prefs.GIFTS_NONE, L.t(R.string.gifts_none))
        ), description = L.t(R.string.gifts_in_video_desc), accent = Neon.Pink)
        Hint(L.t(R.string.chat_rec_hint))
    }
}

@Composable
private fun PickerRow(icon: ImageVector, title: String, value: String, accent: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(value, fontSize = 13.sp, color = Subtle)
        }
        Icon(Icons.Rounded.UnfoldMore, null, tint = Subtle)
    }
}

@Composable
private fun ChoiceRow(name: String, note: String?, selected: Boolean, accent: Color, icon: ImageVector?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(16.dp))
            .background(if (selected) accent.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 15.5.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
            if (note != null) Text(note, fontSize = 12.sp, color = Subtle, lineHeight = 15.sp)
        }
        if (selected) Icon(Icons.Rounded.Check, null, tint = accent, modifier = Modifier.size(20.dp))
        else if (icon != null) Icon(icon, null, tint = Subtle, modifier = Modifier.size(20.dp))
    }
}
