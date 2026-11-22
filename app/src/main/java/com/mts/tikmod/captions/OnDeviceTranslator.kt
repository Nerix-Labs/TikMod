package com.mts.tikmod.captions

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.mts.tikmod.utils.XLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Last resort for live captions when both Google web endpoints fail or rate-limit: ML Kit's on-device translator.
 *
 * Each language pair needs a model (about 30 MB, downloaded once by ML Kit and kept). The first time a pair is
 * needed the download starts in the background and the caption shows the original text; once the model is there,
 * translation keeps working with no network at all.
 */
object OnDeviceTranslator {
    private val clients = ConcurrentHashMap<String, com.google.mlkit.nl.translate.Translator>()
    private val ready: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val downloading: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Google web codes ([Translator.code]) to ML Kit's. */
    private fun mlCode(google: String): String? = when (google) {
        "iw" -> TranslateLanguage.HEBREW
        "zh-CN", "zh-TW" -> TranslateLanguage.CHINESE
        else -> TranslateLanguage.fromLanguageTag(google)
    }

    /** The translation, or null when the pair isn't supported or its model isn't downloaded yet. Never blocks long. */
    fun translate(text: String, sl: String, tl: String): String? {
        if (sl == "auto") return null // ML Kit needs a known source language
        val s = mlCode(sl) ?: return null
        val t = mlCode(tl) ?: return null
        if (s == t) return text
        val key = "$s>$t"
        val client = clients.getOrPut(key) {
            Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(s).setTargetLanguage(t).build())
        }
        if (key !in ready) {
            if (downloading.add(key)) {
                XLog.log("Captions: on-device model $key — downloading")
                client.downloadModelIfNeeded(DownloadConditions.Builder().build())
                    .addOnSuccessListener { ready.add(key); downloading.remove(key); XLog.log("Captions: on-device model $key ready") }
                    .addOnFailureListener { downloading.remove(key); XLog.log("Captions: on-device model $key failed: ${it.message}") }
            }
            return null
        }
        return runCatching { Tasks.await(client.translate(text), 3, TimeUnit.SECONDS) }
            .onFailure { XLog.log("Captions: on-device translation failed: ${it.message}") }
            .getOrNull()?.trim()?.ifEmpty { null }
    }
}
