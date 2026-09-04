package com.example.labdetect.speech

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Cliente de la voz Read Aloud de Microsoft Edge. Es un servicio no oficial;
 * cualquier error se propaga para que el motor active el respaldo local.
 */
internal class EdgeTtsClient(context: Context) {
    private val cacheDirectory = context.cacheDir
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    fun synthesize(text: String): File {
        val spokenText = textForRequest(text)
        require(spokenText.isNotBlank()) { "No hay texto para reproducir" }

        val requestId = UUID.randomUUID().toString().replace("-", "")
        val request = Request.Builder()
            .url("$EDGE_URL&ConnectionId=$requestId&Sec-MS-GEC=${generateGecToken()}&Sec-MS-GEC-Version=$GEC_VERSION")
            .header("Pragma", "no-cache")
            .header("Cache-Control", "no-cache")
            .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "es-EC,es-419;q=0.9,es;q=0.8")
            .header("Cookie", "muid=${generateMuid()};")
            .build()
        val output = File.createTempFile("labdetect-edge-", ".mp3", cacheDirectory)
        val writer = FileOutputStream(output)
        val completed = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        val socket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(speechConfig())
                webSocket.send(ssmlRequest(requestId, spokenText))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("Path:turn.end")) {
                    completed.countDown()
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                runCatching {
                    val payload = bytes.toByteArray()
                    if (payload.size < 4) return@runCatching
                    val headerLength = ((payload[0].toInt() and 0xff) shl 8) or
                        (payload[1].toInt() and 0xff)
                    if (headerLength + HEADER_SEPARATOR_BYTES >= payload.size) return@runCatching
                    val header = String(payload, 0, headerLength, Charsets.UTF_8)
                    if (header.contains("Path:audio") && header.contains("Content-Type:audio/mpeg")) {
                        writer.write(payload, headerLength + HEADER_SEPARATOR_BYTES, payload.size - headerLength - HEADER_SEPARATOR_BYTES)
                    }
                }.onFailure {
                    failure.compareAndSet(null, it)
                    completed.countDown()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                failure.compareAndSet(null, t)
                completed.countDown()
            }
        })

        try {
            check(completed.await(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "Edge tardó demasiado en responder" }
            failure.get()?.let { throw it }
            check(output.length() > 0) { "Edge no devolvió audio" }
            return output
        } catch (error: Throwable) {
            output.delete()
            throw error
        } finally {
            writer.close()
            socket.cancel()
        }
    }

    private fun speechConfig(): String = buildString {
        append("X-Timestamp:").append(edgeTimestamp()).append("\r\n")
        append("Content-Type:application/json; charset=utf-8\r\n")
        append("Path:speech.config\r\n\r\n")
        append("{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":")
        append("{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"},")
        append("\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n")
    }

    private fun ssmlRequest(requestId: String, text: String): String = buildString {
        append("X-RequestId:").append(requestId).append("\r\n")
        append("Content-Type:application/ssml+xml\r\n")
        append("X-Timestamp:").append(edgeTimestamp()).append("\r\n")
        append("Path:ssml\r\n\r\n")
        append("<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='es-MX'>")
        append("<voice name='$LATIN_SPANISH_VOICE'><prosody rate='-4%' pitch='+0Hz'>")
        append(escapeXml(text))
        append("</prosody></voice></speak>")
    }

    private fun textForRequest(value: String): String {
        val cleaned = value.filter { it.code == 9 || it.code >= 32 }.trim()
        val builder = StringBuilder()
        var bytes = 0
        for (char in cleaned) {
            val charBytes = char.toString().toByteArray(Charsets.UTF_8).size
            if (bytes + charBytes > MAX_TEXT_BYTES) break
            builder.append(char)
            bytes += charBytes
        }
        return builder.toString()
    }

    private fun edgeTimestamp(): String = SimpleDateFormat(
        "EEE MMM dd yyyy:HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'",
        Locale.US
    ).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

    private fun generateGecToken(): String {
        val windowsSeconds = (System.currentTimeMillis() / 1_000L) + WINDOWS_EPOCH_SECONDS
        val roundedSeconds = windowsSeconds - (windowsSeconds % 300L)
        val ticks = roundedSeconds * 10_000_000L
        return MessageDigest.getInstance("SHA-256")
            .digest("$ticks$TRUSTED_CLIENT_TOKEN".toByteArray(Charsets.US_ASCII))
            .joinToString("") { "%02X".format(Locale.US, it.toInt() and 0xff) }
    }

    private fun generateMuid(): String = ByteArray(16).also(SecureRandom()::nextBytes)
        .joinToString("") { "%02X".format(Locale.US, it.toInt() and 0xff) }

    private fun escapeXml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private companion object {
        const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        const val GEC_VERSION = "1-143.0.3650.75"
        const val EDGE_URL = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1?TrustedClientToken=$TRUSTED_CLIENT_TOKEN"
        const val LATIN_SPANISH_VOICE = "es-MX-DaliaNeural"
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0"
        const val WINDOWS_EPOCH_SECONDS = 11_644_473_600L
        const val MAX_TEXT_BYTES = 3_900
        const val HEADER_SEPARATOR_BYTES = 2
        const val CONNECT_TIMEOUT_SECONDS = 12L
        const val READ_TIMEOUT_SECONDS = 50L
    }
}
