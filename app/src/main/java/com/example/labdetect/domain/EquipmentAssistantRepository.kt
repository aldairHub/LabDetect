package com.example.labdetect.domain

interface EquipmentAssistantRepository {
    suspend fun ask(question: String, equipmentId: String, variantId: String? = null): String
}
