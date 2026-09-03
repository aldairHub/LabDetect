package com.example.labdetect.speech

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import com.example.labdetect.BuildConfig
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

/** Voz neuronal de OpenAI, con TTS del teléfono como respaldo offline. */
class AndroidSpeechEngine(context: Context) : TextToSpeech.OnInitListener {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val fallbackTts = TextToSpeech(appContext, this)
    private var fallbackReady = false
    private var player: MediaPlayer? = null
    private var closed = false

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        val bestVoice = fallbackTts.voices.orEmpty()
            .filter { it.locale.language == "es" }
            .minWithOrNull(
                compareBy<Voice> { it.isNetworkConnectionRequired }
                    .thenBy { localePriority(it.locale) }
                    .thenByDescending { it.quality }
            )
        bestVoice?.let { fallbackTts.voice = it } ?: run {
            fallbackTts.language = Locale("es", "EC")
        }
        fallbackTts.setSpeechRate(0.98f)
        fallbackTts.setPitch(1.0f)
        fallbackReady = true
    }

    fun speak(text: String) {
        val cleanText = text.trim()
        if (cleanText.isBlank() || closed) return
        stop()
        if (BuildConfig.OPENAI_API_KEY.isBlank()) {
            speakWithFallback(cleanText)
            return
        }
        executor.execute {
            val audioFile = runCatching {
                requestOpenAiAudio(cleanText)
            }.getOrNull()
            mainHandler.post {
                if (closed) {
                    audioFile?.delete()
                } else if (audioFile != null) {
                    playAudio(audioFile, cleanText)
                } else {
                    speakWithFallback(cleanText)
                }
            }
        }
    }

    fun stop() {
        mainHandler.post {
            runCatching { player?.stop() }
            player?.release()
            player = null
            if (fallbackReady) fallbackTts.stop()
        }
    }

    fun close() {
        closed = true
        executor.shutdownNow()
        player?.release()
        player = null
        fallbackTts.stop()
        fallbackTts.shutdown()
    }

    private fun requestOpenAiAudio(text: String): File {
        val connection = (URL(OPENAI_SPEECH_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 45_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer ${BuildConfig.OPENAI_API_KEY}")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "audio/mpeg")
        }
        try {
            val payload = JSONObject()
                .put("model", BuildConfig.OPENAI_TTS_MODEL)
                .put("voice", OPENAI_VOICE)
                .put("input", text)
                .put(
                    "instructions",
                    "Habla en español latino ecuatoriano, con tono cercano, técnico y tranquilo. " +
                        "Pronuncia con claridad, usa un ritmo conversacional y evita sonar como un anuncio."
                )
                .put("response_format", "mp3")
                .toString()
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(payload) }
            if (connection.responseCode !in 200..299) error("Voz neuronal no disponible")
            return File.createTempFile("labdetect-voice-", ".mp3", appContext.cacheDir).also { file ->
                connection.inputStream.use { input -> file.outputStream().use(input::copyTo) }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun playAudio(file: File, fallbackText: String) {
        player = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            setOnPreparedListener { it.start() }
            setOnCompletionListener {
                it.release()
                if (player === it) player = null
                file.delete()
            }
            setOnErrorListener { mediaPlayer, _, _ ->
                mediaPlayer.release()
                if (player === mediaPlayer) player = null
                file.delete()
                speakWithFallback(fallbackText)
                true
            }
            prepareAsync()
        }
    }

    private fun speakWithFallback(text: String) {
        if (fallbackReady && !closed) {
            fallbackTts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "labdetect-answer")
        }
    }

    private fun localePriority(locale: Locale): Int = when (locale.country) {
        "EC" -> 0
        "MX" -> 1
        "US", "419" -> 2
        "ES" -> 3
        else -> 4
    }

    companion object {
        private const val OPENAI_SPEECH_URL = "https://api.openai.com/v1/audio/speech"
        private const val OPENAI_VOICE = "coral"
    }
}
