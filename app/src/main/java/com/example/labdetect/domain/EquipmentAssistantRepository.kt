package com.example.labdetect.domain

interface EquipmentAssistantRepository {
    suspend fun ask(question: String, equipmentContext: String): String
}