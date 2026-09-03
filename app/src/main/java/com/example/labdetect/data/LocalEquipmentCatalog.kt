package com.example.labdetect.data

import android.content.Context
import com.example.labdetect.domain.EquipmentProfile
import com.example.labdetect.domain.EquipmentVariant
import org.json.JSONObject

class LocalEquipmentCatalog(context: Context) {
    private val profiles: Map<String, EquipmentProfile> = runCatching {
        val json = JSONObject(
            context.applicationContext.assets.open("equipment_catalog.json")
                .bufferedReader().use { it.readText() }
        )
        val generalReferenceAvailable = json.optBoolean("general_reference_all_equipment", false)
        val items = json.getJSONArray("equipment")
        buildMap {
            for (index in 0 until items.length()) {
                val item = items.getJSONObject(index)
                val variantsJson = item.getJSONArray("variants")
                val variants = buildList {
                    for (variantIndex in 0 until variantsJson.length()) {
                        val variant = variantsJson.getJSONObject(variantIndex)
                        add(
                            EquipmentVariant(
                                id = variant.getString("id"),
                                displayName = variant.getString("display_name"),
                                manufacturer = variant.optString("manufacturer").takeIf { it.isNotBlank() },
                                model = variant.optString("model").takeIf { it.isNotBlank() },
                                manualStatus = variant.optString("manual_status", "pending"),
                                manualTitle = variant.optString("manual_title").takeIf { it.isNotBlank() },
                                manualUrl = variant.optString("manual_url").takeIf { it.isNotBlank() },
                                generalReferenceAvailable = generalReferenceAvailable
                            )
                        )
                    }
                }
                val profile = EquipmentProfile(
                    id = item.getString("id"),
                    displayName = item.getString("display_name"),
                    variants = variants
                )
                put(profile.id, profile)
            }
        }
    }.getOrDefault(emptyMap())

    fun find(id: String): EquipmentProfile? = profiles[id]

    fun equipmentNames(): List<String> = profiles.values.map { it.displayName }
}
