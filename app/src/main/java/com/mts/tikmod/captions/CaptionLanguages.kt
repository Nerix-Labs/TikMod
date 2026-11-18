package com.mts.tikmod.captions

import com.mts.tikmod.utils.L
import java.util.Locale

/** The languages live captions can listen to, and how they're named on screen. */
object CaptionLanguages {
    const val AUTO = "auto"

    /**
     * Speech languages of Android's on-device recognizer (one tag per language; the phone reports which of
     * them it really has, see [CaptionService.querySupport]).
     */
    val SPEECH = listOf(
        "en-US", "es-ES", "fr-FR", "de-DE", "it-IT", "pt-BR", "ru-RU", "tr-TR", "pl-PL", "nl-NL", "sv-SE",
        "da-DK", "nb-NO", "hi-IN", "id-ID", "th-TH", "vi-VN", "ja-JP", "ko-KR", "cmn-Hans-CN", "cmn-Hant-TW"
    )

    /** Caption (translation) languages offered in settings: the app's own languages. */
    val TARGETS get() = L.LANGUAGES.map { it.first }

    private fun locale(tag: String): Locale = when {
        tag.startsWith("cmn-Hant") || tag.startsWith("zh-Hant") || tag.endsWith("-TW") -> Locale.forLanguageTag("zh-Hant")
        tag.startsWith("cmn") || tag.startsWith("zh") -> Locale.forLanguageTag("zh-Hans")
        else -> Locale.forLanguageTag(tag)
    }

    /** "Spanish", "Chinese (Simplified)"… in the app's language. */
    fun name(tag: String): String {
        val l = locale(tag)
        val shown = if (l.script.isNotEmpty()) l.getDisplayName(L.locale()) else l.getDisplayLanguage(L.locale())
        return shown.replaceFirstChar { it.titlecase(L.locale()) }.ifBlank { tag }
    }

    /** Same language, whatever the region ("en-GB" and "en-US"). */
    fun sameLanguage(a: String, b: String) = Translator.code(a) == Translator.code(b)

    /** The entry of [SPEECH] for a tag the recognizer reported ("es-US" -> "es-ES"), or null. */
    fun speechTag(tag: String): String? = SPEECH.firstOrNull { it.equals(tag, true) } ?: SPEECH.firstOrNull { sameLanguage(it, tag) }
}
