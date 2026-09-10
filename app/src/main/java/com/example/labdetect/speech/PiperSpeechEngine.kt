package com.example.labdetect.speech

import android.content.Context
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
    private val engineLock = Any()
    private var engine: OfflineTts? = null
    @Volatile private var closed = false

    fun synthesizeIfInstalled(text: String): File? {
        if (closed || !isInstalled() || text.isBlank()) return null
        return runCatching {
            synchronized(engineLock) {
                if (closed) return null
                val audio = (engine ?: createEngine().also { engine = it })
                    .generate(text.trim(), speed = 1.0f)
                File.createTempFile("labdetect-piper-", ".wav", appContext.cacheDir).also {
                    check(audio.save(it.absolutePath)) { "No se pudo guardar el audio Piper" }
                }
            }
        }.getOrNull()
    }

    /** Extrae silenciosamente la voz incluida en la APK; nunca frena una respuesta. */
    fun prepareForOfflineUse() {
        if (closed || isInstalled() || !installing.compareAndSet(false, true)) return
        installer.execute {
            runCatching {
                synchronized(installationLock) {
                    if (!closed && !isInstalled()) installVoicePack()
                }
            }
            installing.set(false)
        }
    }

    fun close() {
        closed = true
        installer.shutdownNow()
        synchronized(engineLock) {
            engine?.release()
            engine = null
        }
    }

    private fun createEngine(): OfflineTts = OfflineTts(
        config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = File(voiceDirectory, MODEL_FILE).absolutePath,
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
    )

    private fun installVoicePack() {
        val parent = voiceDirectory.parentFile ?: return
        parent.mkdirs()
        val archive = File.createTempFile("piper-daniela-", ".tar.bz2", appContext.cacheDir)
        val staging = File(parent, ".piper-daniela-installing")
        runCatching { staging.deleteRecursively() }
        staging.mkdirs()
        try {
            appContext.assets.open(ARCHIVE_ASSET).use { input ->
                FileOutputStream(archive).use(input::copyTo)
            }
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
        val installationLock = Any()
        const val ARCHIVE_ASSET = "piper_es_ar_daniela_high_int8.tar.bz2"
        const val ARCHIVE_SHA256 = "7218f0a119e4c16533ac187f71ab3019f2092f1594e43fef8392ae1f5b64abab"
        const val BUNDLE_DIRECTORY = "vits-piper-es_AR-daniela-high-int8"
        const val MODEL_FILE = "es_AR-daniela-high.onnx"
        const val TOKENS_FILE = "tokens.txt"
        const val ESPEAK_DIRECTORY = "espeak-ng-data"
    }
}
