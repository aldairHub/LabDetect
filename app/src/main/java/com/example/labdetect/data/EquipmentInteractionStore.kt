package com.example.labdetect.data

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/** Interacciones locales y exportables; nunca se envían fuera del teléfono automáticamente. */
class EquipmentInteractionStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun rememberSeen(equipmentId: String) {
        if (equipmentId.isBlank()) return
        val recent = readArray(RECENT_KEY)
        val result = JSONArray().put(JSONObject().put("equipmentId", equipmentId).put("at", System.currentTimeMillis()))
        for (index in 0 until recent.length()) {
            val item = recent.optJSONObject(index) ?: continue
            if (item.optString("equipmentId") != equipmentId) result.put(item)
        }
        val trimmed = JSONArray()
        for (index in 0 until minOf(result.length(), MAX_RECENT)) trimmed.put(result.opt(index))
        preferences.edit().putString(RECENT_KEY, trimmed.toString()).apply()
    }

    fun recentEquipmentIds(): List<String> = readArray(RECENT_KEY).toObjects()
        .map { it.optString("equipmentId") }
        .filter { it.isNotBlank() }

    fun saveDetectionFeedback(predictedId: String, correctedId: String?, frame: Bitmap?) {
        if (predictedId.isBlank()) return
        val imageName = frame?.let(::saveFrame)
        val entry = JSONObject()
            .put("predictedId", predictedId)
            .put("correctedId", correctedId ?: JSONObject.NULL)
            .put("at", System.currentTimeMillis())
            .put("image", imageName ?: JSONObject.NULL)
        val feedback = readArray(FEEDBACK_KEY).also { it.put(entry) }
        writeArray(FEEDBACK_KEY, feedback, MAX_FEEDBACK)
    }

    fun rememberQuestion(equipmentId: String, question: String, answer: String) {
        if (equipmentId.isBlank() || question.isBlank() || answer.isBlank()) return
        val entry = JSONObject()
            .put("equipmentId", equipmentId)
            .put("question", question.take(MAX_TEXT))
            .put("answer", answer.take(MAX_TEXT))
            .put("at", System.currentTimeMillis())
        val history = readArray(HISTORY_KEY).also { it.put(entry) }
        writeArray(HISTORY_KEY, history, MAX_HISTORY)
    }

    fun historyFor(equipmentId: String): List<QuestionRecord> = readArray(HISTORY_KEY).toObjects()
        .filter { it.optString("equipmentId") == equipmentId }
        .asReversed()
        .map { QuestionRecord(it.optString("question"), it.optString("answer")) }

    fun rememberMissingInformation(equipmentId: String, question: String) {
        if (equipmentId.isBlank() || question.isBlank()) return
        val entry = JSONObject()
            .put("equipmentId", equipmentId)
            .put("question", question.take(MAX_TEXT))
            .put("at", System.currentTimeMillis())
        val gaps = readArray(GAPS_KEY).also { it.put(entry) }
        writeArray(GAPS_KEY, gaps, MAX_GAPS)
    }

    private fun saveFrame(bitmap: Bitmap): String? = runCatching {
        val directory = File(appContext.filesDir, "training_feedback").apply { mkdirs() }
        val name = "feedback-${System.currentTimeMillis()}.jpg"
        FileOutputStream(File(directory, name)).use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
        }
        name
    }.getOrNull()

    private fun readArray(key: String): JSONArray = runCatching {
        JSONArray(preferences.getString(key, "[]"))
    }.getOrDefault(JSONArray())

    private fun writeArray(key: String, source: JSONArray, max: Int) {
        val trimmed = JSONArray()
        val first = (source.length() - max).coerceAtLeast(0)
        for (index in first until source.length()) trimmed.put(source.opt(index))
        preferences.edit().putString(key, trimmed.toString()).apply()
    }

    private fun JSONArray.toObjects(): List<JSONObject> = buildList {
        for (index in 0 until length()) optJSONObject(index)?.let(::add)
    }

    data class QuestionRecord(val question: String, val answer: String)

    private companion object {
        const val PREFERENCES = "equipment_interactions"
        const val RECENT_KEY = "recent"
        const val FEEDBACK_KEY = "detection_feedback"
        const val HISTORY_KEY = "question_history"
        const val GAPS_KEY = "knowledge_gaps"
        const val MAX_RECENT = 8
        const val MAX_FEEDBACK = 120
        const val MAX_HISTORY = 48
        const val MAX_GAPS = 40
        const val MAX_TEXT = 420
        const val JPEG_QUALITY = 84
    }
}
