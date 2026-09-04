package com.example.labdetect.data

import android.content.Context
import org.json.JSONObject

/** Lista local de los vector stores por equipo. No usa ni requiere una base de datos. */
class DocumentIndex(context: Context) {
    private val stores: Map<String, String> = runCatching {
        val root = JSONObject(
            context.applicationContext.assets.open(ASSET_NAME)
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
        )
        val equipment = root.getJSONObject("equipment")
        buildMap {
            val ids = equipment.keys()
            while (ids.hasNext()) {
                val equipmentId = ids.next()
                equipment.getJSONObject(equipmentId)
                    .optString("vector_store_id")
                    .takeIf { it.isNotBlank() }
                    ?.let { put(equipmentId, it) }
            }
        }
    }.getOrDefault(emptyMap())

    fun vectorStoreIdFor(equipmentId: String): String? = stores[equipmentId]

    companion object {
        private const val ASSET_NAME = "document_index.json"
    }
}
