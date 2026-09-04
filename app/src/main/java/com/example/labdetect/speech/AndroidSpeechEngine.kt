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
import java.util.Locale
import java.util.concurrent.Executors

/** Voz Edge online, Piper neural offline y Android como último respaldo. */
class AndroidSpeechEngine(context: Context) : TextToSpeech.OnInitListener {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val fallbackTts = TextToSpeech(appContext, this)
    private val edgeTts = EdgeTtsClient(appContext)
    private val piperTts = PiperSpeechEngine(appContext)
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
            if (!hasInternet()) {
                speakWithOfflineVoice(cleanText, requestGeneration)
            } else {
                executor.execute {
                    val audioFile = runCatching { edgeTts.synthesize(cleanText) }.getOrNull()
                    mainHandler.post {
                        if (closed || requestGeneration != generation) {
                            audioFile?.delete()
                        } else if (audioFile != null) {
                            piperTts.prepareForOfflineUse()
                            playAudio(audioFile, cleanText, requestGeneration)
                        } else {
                            speakWithOfflineVoice(cleanText, requestGeneration)
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
        piperTts.close()
        stopPlayback(completePrevious = true)
        fallbackTts.shutdown()
    }

    private fun playAudio(file: java.io.File, fallbackText: String, requestGeneration: Int) {
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

    private fun speakWithOfflineVoice(text: String, requestGeneration: Int) {
        executor.execute {
            val offlineAudio = piperTts.synthesizeIfInstalled(text)
            mainHandler.post {
                if (closed || requestGeneration != generation) {
                    offlineAudio?.delete()
                } else if (offlineAudio != null) {
                    playAudio(offlineAudio, text, requestGeneration)
                } else {
                    piperTts.prepareForOfflineUse()
                    speakWithFallback(text, requestGeneration)
                }
            }
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
        private const val UTTERANCE_PREFIX = "labdetect-answer-"
    }
}
