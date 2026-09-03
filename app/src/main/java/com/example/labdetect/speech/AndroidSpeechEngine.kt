package com.example.labdetect.speech

import android.content.Context
import android.media.MediaPlayer
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.example.labdetect.BuildConfig
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

/** Voz Marin de OpenAI, con la mejor voz española instalada en Android como respaldo offline. */
class AndroidSpeechEngine(context: Context) : TextToSpeech.OnInitListener {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val fallbackTts = TextToSpeech(appContext, this)
    private var fallbackReady = false
    private var player: MediaPlayer? = null
    private var closed = false
    private var generation = 0
    private var onFinished: (() -> Unit)? = null

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
        fallbackTts.setSpeechRate(0.97f)
        fallbackTts.setPitch(1.0f)
        fallbackTts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = finishIfCurrent(utteranceId)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finishIfCurrent(utteranceId)
        })
        fallbackReady = true
    }

    fun speak(text: String, finished: (() -> Unit)? = null) {
        val cleanText = text.trim()
        if (cleanText.isBlank() || closed) return
        runOnMain {
            stopPlayback(completePrevious = true)
            onFinished = finished
            val requestGeneration = ++generation
            if (BuildConfig.OPENAI_API_KEY.isBlank() || !hasInternet()) {
                speakWithFallback(cleanText, requestGeneration)
            } else {
                executor.execute {
                    val audioFile = runCatching { requestOpenAiAudio(cleanText) }.getOrNull()
                    mainHandler.post {
                        if (closed || requestGeneration != generation) {
                            audioFile?.delete()
                        } else if (audioFile != null) {
                            playAudio(audioFile, cleanText, requestGeneration)
                        } else {
                            speakWithFallback(cleanText, requestGeneration)
                        }
                    }
                }
            }
        }
    }

    fun stop() {
        runOnMain {
            generation++
            stopPlayback(completePrevious = true)
        }
    }

    fun close() {
        closed = true
        generation++
        executor.shutdownNow()
        stopPlayback(completePrevious = true)
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
                .put("instructions", NATURAL_VOICE_INSTRUCTIONS)
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

    private fun playAudio(file: File, fallbackText: String, requestGeneration: Int) {
        player = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            setOnPreparedListener { mediaPlayer ->
                if (requestGeneration == generation) mediaPlayer.start()
            }
            setOnCompletionListener { mediaPlayer ->
                mediaPlayer.release()
                if (player === mediaPlayer) player = null
                file.delete()
                if (requestGeneration == generation) completeSpeech()
            }
            setOnErrorListener { mediaPlayer, _, _ ->
                mediaPlayer.release()
                if (player === mediaPlayer) player = null
                file.delete()
                if (requestGeneration == generation) speakWithFallback(fallbackText, requestGeneration)
                true
            }
            prepareAsync()
        }
    }

    private fun speakWithFallback(text: String, requestGeneration: Int) {
        if (fallbackReady && !closed && requestGeneration == generation) {
            fallbackTts.speak(
                text,
                TextToSpeech.QUEUE_FLUSH,
                null,
                "$UTTERANCE_PREFIX$requestGeneration"
            )
        } else {
            completeSpeech()
        }
    }

    private fun finishIfCurrent(utteranceId: String?) {
        val utteranceGeneration = utteranceId?.removePrefix(UTTERANCE_PREFIX)?.toIntOrNull()
        if (utteranceGeneration == generation) mainHandler.post { completeSpeech() }
    }

    private fun stopPlayback(completePrevious: Boolean) {
        runCatching { player?.stop() }
        player?.release()
        player = null
        if (fallbackReady) fallbackTts.stop()
        if (completePrevious) completeSpeech()
    }

    private fun completeSpeech() {
        val callback = onFinished
        onFinished = null
        callback?.invoke()
    }

    private fun hasInternet(): Boolean {
        val manager = appContext.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
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
        private const val OPENAI_VOICE = "marin"
        private const val UTTERANCE_PREFIX = "labdetect-answer-"
        private const val NATURAL_VOICE_INSTRUCTIONS =
            "Habla en español latino con un acento ecuatoriano suave y auténtico. Usa una voz humana, cálida " +
                "y cercana, como una laboratorista explicándole algo a un compañero frente al equipo. Mantén " +
                "un ritmo conversacional, con pausas breves y entonación natural. Pronuncia claramente marcas, " +
                "unidades y términos técnicos. Evita sonar robótica, monótona, exagerada o como un anuncio."
    }
}
