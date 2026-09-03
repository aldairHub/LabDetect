package com.example.labdetect.data

import android.content.Context

/** Favoritos pequeños y privados que permanecen disponibles sin conexión. */
class FavoriteEquipmentStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "labdetect_favorites",
        Context.MODE_PRIVATE
    )

    fun contains(equipmentId: String): Boolean = equipmentId in all()

    fun toggle(equipmentId: String): Boolean {
        val updated = all().toMutableSet()
        val isFavorite = if (equipmentId in updated) {
            updated.remove(equipmentId)
            false
        } else {
            updated.add(equipmentId)
            true
        }
        preferences.edit().putStringSet(KEY_IDS, updated).apply()
        return isFavorite
    }

    fun all(): Set<String> = preferences.getStringSet(KEY_IDS, emptySet()).orEmpty().toSet()

    companion object {
        private const val KEY_IDS = "equipment_ids"
    }
}
