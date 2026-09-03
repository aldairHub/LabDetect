package com.example.labdetect.data

import android.content.Context
import org.json.JSONObject
import java.text.Normalizer

data class LocalManual(
    val equipmentId: String,
    val displayName: String,
    val function: String,
    val specifications: String,
    val procedure: String,
    val safety: String,
    val maintenance: String,
    val fullText: String
) {
    fun characteristics(): String = listOf(function, specifications)
        .filter { it.isNotBlank() }
        .joinToString("\n\n")

    fun readableDocument(): String = buildString {
        append(displayName)
        append("\n\nFunción y descripción\n")
        append(function)
        append("\n\nEspecificaciones generales\n")
        append(specifications)
        append("\n\nProcedimiento general de uso\n")
        append(procedure)
        append("\n\nPrecauciones de seguridad\n")
        append(safety)
        append("\n\nMantenimiento básico\n")
        append(maintenance)
        append("\n\nDocumento técnico general disponible sin conexión. Los controles exactos pueden variar según el modelo.")
    }
}

/** Manual técnico incluido en la APK y respuestas locales sin modelos pesados. */
class LocalManualRepository(context: Context) {
    private val manuals: Map<String, LocalManual> = runCatching {
        val entries = JSONObject(
            context.applicationContext.assets.open("manual_text.json")
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
        ).getJSONObject("equipment")
        buildMap {
            val ids = entries.keys()
            while (ids.hasNext()) {
                val id = ids.next()
                val item = entries.getJSONObject(id)
                val text = item.optString("text")
                put(
                    id,
                    LocalManual(
                        equipmentId = id,
                        displayName = item.optString("display_name", id.replace('_', ' ')),
                        function = extract(text, "Funcion y descripcion", "Especificaciones tecnicas generales"),
                        specifications = extract(text, "Especificaciones tecnicas generales", "Procedimiento general de uso"),
                        procedure = extract(text, "Procedimiento general de uso", "Precauciones de seguridad"),
                        safety = extract(text, "Precauciones de seguridad", "Mantenimiento basico"),
                        maintenance = extract(text, "Mantenimiento basico", null),
                        fullText = text
                    )
                )
            }
        }
    }.getOrDefault(emptyMap())

    fun find(equipmentId: String): LocalManual? = manuals[equipmentId]

    fun answerOffline(equipmentId: String, question: String): String {
        val manual = find(equipmentId)
            ?: return "Todavía no tengo información guardada de este equipo."
        val normalizedQuestion = normalize(question)
        if (!isEquipmentQuestion(normalizedQuestion, manual)) {
            return "Puedo ayudarte únicamente con el equipo que estás enfocando."
        }
        val selected = when {
            containsAny(normalizedQuestion, "seguridad", "precaucion", "riesgo", "peligro", "cuidado") ->
                manual.safety
            containsAny(normalizedQuestion, "mantenimiento", "limpiar", "limpieza", "conservar") ->
                manual.maintenance
            containsAny(normalizedQuestion, "usar", "uso", "procedimiento", "encender", "operar", "manejar") ->
                manual.procedure
            containsAny(normalizedQuestion, "temperatura", "capacidad", "rango", "especificacion", "caracteristica") ->
                manual.specifications
            else -> manual.function
        }
        val answer = naturalSpeech(selected, 70)
        return answer.ifBlank {
            "${manual.displayName} está documentado en el manual guardado. Puedes abrirlo desde los detalles del equipo."
        }
    }

    private fun containsAny(text: String, vararg words: String): Boolean = words.any(text::contains)

    private fun isEquipmentQuestion(question: String, manual: LocalManual): Boolean {
        val equipmentWords = normalize(manual.displayName).split(' ').filter { it.length >= 4 }
        if (equipmentWords.any(question::contains)) return true
        return containsAny(
            question,
            "equipo", "aparato", "maquina", "esto", "este", "esta", "sirve", "funcion", "que hace",
            "usar", "uso", "operar", "encender", "apagar", "limpiar", "mantenimiento",
            "seguridad", "riesgo", "peligro", "precaucion", "temperatura", "capacidad",
            "rango", "muestra", "medir", "tiempo", "manual", "procedimiento", "calibrar",
            "caracteristica", "especificacion", "boton", "control", "voltaje", "presion", "velocidad"
        )
    }

    private fun naturalSpeech(text: String, maxWords: Int): String {
        val cleaned = text
            .replace('●', '.')
            .replace(Regex("""\s+\d{1,2}\s+(?=[A-ZÁÉÍÓÚ])"""), ". ")
            .replace(Regex("""\s+"""), " ")
            .replace(Regex("""\.{2,}"""), ".")
            .trim(' ', '.', ':')
        val words = cleaned.split(' ').filter { it.isNotBlank() }
        val shortened = if (words.size <= maxWords) cleaned else words.take(maxWords).joinToString(" ").trimEnd(',', ';') + "."
        return shortened.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    private fun normalize(value: String): String = Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("""\p{M}+"""), "")

    companion object {
        private fun extract(text: String, startMarker: String, endMarker: String?): String {
            val start = text.indexOf(startMarker, ignoreCase = true)
            if (start < 0) return ""
            val contentStart = start + startMarker.length
            val end = endMarker?.let { text.indexOf(it, contentStart, ignoreCase = true) }
                ?.takeIf { it >= 0 }
                ?: text.length
            return text.substring(contentStart, end)
                .replace('●', '•')
                .replace(Regex("""\s+"""), " ")
                .trim()
        }
    }
}
