package com.example.labdetect.speech

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Voz neural Piper real para contingencias offline. La voz se descarga una sola
 * vez por Wi-Fi y queda dentro del almacenamiento privado de la aplicación.
 */
internal class PiperSpeechEngine(context: Context) {
    private val appContext = context.applicationContext
    private val voiceDirectory = File(appContext.filesDir, "voices/piper-daniela-int8")
    private val installing = AtomicBoolean(false)
    private val installer = Executors.newSingleThreadExecutor()

    fun synthesizeIfInstalled(text: String): File? {
        if (!isInstalled() || text.isBlank()) return null
        return runCatching {
            val model = File(voiceDirectory, MODEL_FILE)
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = model.absolutePath,
                        tokens = File(voiceDirectory, TOKENS_FILE).absolutePath,
                        dataDir = File(voiceDirectory, ESPEAK_DIRECTORY).absolutePath,
                        noiseScale = 0.667f,
                        noiseScaleW = 0.8f,
                        lengthScale = 1.0f
                    ),
                    numThreads = 2,
                    debug = false,
                    provider = "cpu"
                )
            )
            val tts = OfflineTts(config = config)
            try {
                val audio = tts.generate(text.trim(), speed = 1.0f)
                File.createTempFile("labdetect-piper-", ".wav", appContext.cacheDir).also {
                    check(audio.save(it.absolutePath)) { "No se pudo guardar el audio Piper" }
                }
            } finally {
                tts.release()
            }
        }.getOrNull()
    }

    /** Descarga diferida y silenciosa solo por Wi-Fi/no medido; nunca frena una respuesta. */
    fun prepareForOfflineUse() {
        if (isInstalled() || !isOnUnmeteredNetwork() || !installing.compareAndSet(false, true)) return
        installer.execute {
            runCatching { installVoicePack() }
            installing.set(false)
        }
    }

    fun close() {
        installer.shutdownNow()
    }

    private fun installVoicePack() {
        val parent = voiceDirectory.parentFile ?: return
        parent.mkdirs()
        val archive = File.createTempFile("piper-daniela-", ".tar.bz2", appContext.cacheDir)
        val staging = File(parent, ".piper-daniela-installing")
        runCatching { staging.deleteRecursively() }
        staging.mkdirs()
        try {
            downloadArchive(archive)
            check(sha256(archive).equals(ARCHIVE_SHA256, ignoreCase = true)) { "La voz Piper no pasó la verificación" }
            extractArchive(archive, staging)
            val extractedVoice = File(staging, BUNDLE_DIRECTORY)
            check(File(extractedVoice, MODEL_FILE).isFile) { "El paquete Piper está incompleto" }
            check(File(extractedVoice, TOKENS_FILE).isFile) { "Faltan los tokens Piper" }
            check(File(extractedVoice, ESPEAK_DIRECTORY).isDirectory) { "Falta el diccionario Piper" }
            if (voiceDirectory.exists()) voiceDirectory.deleteRecursively()
            check(extractedVoice.renameTo(voiceDirectory)) { "No se pudo activar la voz offline" }
        } finally {
            archive.delete()
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    private fun downloadArchive(destination: File) {
        val connection = (URL(ARCHIVE_URL).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 90_000
            setRequestProperty("User-Agent", "LabDetect/1.0 offline voice downloader")
        }
        try {
            check(connection.responseCode in 200..299) { "No se pudo descargar la voz Piper" }
            connection.inputStream.use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun extractArchive(archive: File, destination: File) {
        val destinationPath = destination.canonicalPath + File.separator
        BZip2CompressorInputStream(BufferedInputStream(FileInputStream(archive))).use { bzip ->
            TarArchiveInputStream(bzip).use { tar ->
                var entry = tar.nextTarEntry
                while (entry != null) {
                    val target = File(destination, entry.name)
                    check(target.canonicalPath.startsWith(destinationPath)) { "Entrada de voz no permitida" }
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { output -> tar.copyTo(output) }
                    }
                    entry = tar.nextTarEntry
                }
            }
        }
    }

    private fun isInstalled(): Boolean =
        File(voiceDirectory, MODEL_FILE).isFile &&
            File(voiceDirectory, TOKENS_FILE).isFile &&
            File(voiceDirectory, ESPEAK_DIRECTORY).isDirectory

    private fun isOnUnmeteredNetwork(): Boolean {
        val manager = appContext.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun sha256(file: File): String = FileInputStream(file).use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
        digest.digest().joinToString("") { byte ->
            "%02x".format(Locale.US, byte.toInt() and 0xff)
        }
    }

    private companion object {
        const val ARCHIVE_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-es_AR-daniela-high-int8.tar.bz2"
        const val ARCHIVE_SHA256 = "7218f0a119e4c16533ac187f71ab3019f2092f1594e43fef8392ae1f5b64abab"
        const val BUNDLE_DIRECTORY = "vits-piper-es_AR-daniela-high-int8"
        const val MODEL_FILE = "es_AR-daniela-high.onnx"
        const val TOKENS_FILE = "tokens.txt"
        const val ESPEAK_DIRECTORY = "espeak-ng-data"
    }
}
