package com.example.labdetect.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

data class SpanishVoice(val id: String, val description: String)

class AndroidSpeechEngine(
    context: Context,
    private val onVoicesReady: (List<SpanishVoice>, String?) -> Unit = { _, _ -> }
) : TextToSpeech.OnInitListener {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("speech_settings", Context.MODE_PRIVATE)
    private val tts = TextToSpeech(appContext, this)
    private var ready = false
    private var voicesById: Map<String, Voice> = emptyMap()

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        voicesById = tts.voices
            .orEmpty()
            .filter { it.locale.language == "es" }
            .associateBy { it.name }

        val savedId = preferences.getString(KEY_VOICE_ID, null)
        val selected = voicesById[savedId] ?: chooseBestVoice(voicesById.values)
        if (selected != null) {
            tts.voice = selected
            preferences.edit().putString(KEY_VOICE_ID, selected.name).apply()
        } else {
            tts.language = Locale("es", "EC")
        }
        tts.setSpeechRate(0.96f)
        tts.setPitch(1.0f)
        ready = true
        onVoicesReady(availableVoices(), selected?.name)
    }

    fun availableVoices(): List<SpanishVoice> = voicesById.values
        .sortedWith(compareBy<Voice> { it.isNetworkConnectionRequired }.thenBy { localePriority(it.locale) }.thenBy { it.name })
        .map { voice ->
            val mode = if (voice.isNetworkConnectionRequired) "en línea" else "sin conexión"
            SpanishVoice(voice.name, "${voice.locale.displayName} · $mode · ${voice.name}")
        }

    fun selectVoice(id: String): Boolean {
        val voice = voicesById[id] ?: return false
        val result = tts.setVoice(voice) == TextToSpeech.SUCCESS
        if (result) preferences.edit().putString(KEY_VOICE_ID, id).apply()
        return result
    }

    fun speak(text: String) {
        if (ready && text.isNotBlank()) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "labdetect-answer")
        }
    }

    fun close() {
        tts.stop()
        tts.shutdown()
    }

    private fun chooseBestVoice(voices: Collection<Voice>): Voice? = voices.minWithOrNull(
        compareBy<Voice> { it.isNetworkConnectionRequired }
            .thenBy { localePriority(it.locale) }
            .thenByDescending { it.quality }
    )

    private fun localePriority(locale: Locale): Int = when (locale.country) {
        "EC" -> 0
        "US" -> 1
        "MX" -> 2
        "419" -> 3
        "ES" -> 4
        else -> 5
    }

    companion object {
        private const val KEY_VOICE_ID = "voice_id"
    }
}
