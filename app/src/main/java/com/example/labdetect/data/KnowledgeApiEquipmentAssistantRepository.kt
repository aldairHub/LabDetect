package com.example.labdetect.data

import com.example.labdetect.BuildConfig
import com.example.labdetect.domain.EquipmentAssistantRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Consulta al servicio RAG protegido. La clave de OpenAI nunca se incluye en el APK.
 */
class KnowledgeApiEquipmentAssistantRepository : EquipmentAssistantRepository {
    override suspend fun ask(question: String, variantId: String): String = withContext(Dispatchers.IO) {
        val baseUrl = BuildConfig.KNOWLEDGE_API_URL.trim().trimEnd('/')
        if (baseUrl.isBlank()) {
            return@withContext "La base documental todavía no está conectada en este dispositivo."
        }

        runCatching {
            val connection = (URL("$baseUrl/v1/equipment/ask").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 45_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
            }
            try {
                val payload = JSONObject()
                    .put("variant_id", variantId)
                    .put("question", question)
                    .toString()
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(payload) }

                val stream = if (connection.responseCode in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }
                val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                val json = runCatching { JSONObject(body) }.getOrNull()
                if (connection.responseCode !in 200..299) {
                    error(json?.optString("detail")?.takeIf { it.isNotBlank() } ?: "HTTP ${connection.responseCode}")
                }
                json?.optString("answer")?.takeIf { it.isNotBlank() }
                    ?: error("Respuesta documental vacía")
            } finally {
                connection.disconnect()
            }
        }.getOrElse {
            "No pude consultar los manuales en este momento. Revisa la conexión e inténtalo otra vez."
        }
    }
}
