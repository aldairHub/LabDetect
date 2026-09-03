package com.example.labdetect.domain

interface EquipmentAssistantRepository {
    suspend fun ask(question: String, variantId: String): String
}
