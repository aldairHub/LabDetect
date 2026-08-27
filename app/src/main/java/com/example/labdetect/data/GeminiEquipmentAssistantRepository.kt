package com.example.labdetect.data

import com.example.labdetect.BuildConfig
import com.example.labdetect.domain.EquipmentAssistantRepository
import com.google.ai.client.generativeai.GenerativeModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GeminiEquipmentAssistantRepository : EquipmentAssistantRepository {
    
    private val generativeModel = GenerativeModel(
        modelName = "gemini-3.6-flash",
        apiKey = BuildConfig.GEMINI_API_KEY
    )

    override suspend fun ask(question: String, equipmentContext: String): String = withContext(Dispatchers.IO) {
        try {
            val prompt = """
                Eres un asistente que da información breve y clara sobre objetos.
                El objeto detectado es: $equipmentContext. 
                Responde a esta pregunta del usuario sobre ese objeto de forma concisa: $question
            """.trimIndent()

            // TODO: Aquí se puede agregar file_search o subida de documentos más adelante 
            // si se necesita contexto específico de equipos de laboratorio.

            val response = generativeModel.generateContent(prompt)
            response.text ?: "Lo siento, no pude generar una respuesta."
        } catch (e: Exception) {
            "Error al consultar al asistente: ${e.localizedMessage ?: "Fallo de conexión"}"
        }
    }
}
