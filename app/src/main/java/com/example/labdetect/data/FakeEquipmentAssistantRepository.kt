package com.example.labdetect.data

import com.example.labdetect.domain.EquipmentAssistantRepository
import kotlinx.coroutines.delay

class FakeEquipmentAssistantRepository : EquipmentAssistantRepository {
    override suspend fun ask(question: String, equipmentId: String, variantId: String?): String {
        delay(1000) // Simular latencia de red
        return "Puedo ayudarte con el uso seguro y la documentación de este equipo."
    }
}
