package com.example.labdetect.data

import com.example.labdetect.domain.EquipmentAssistantRepository
import kotlinx.coroutines.delay

class FakeEquipmentAssistantRepository : EquipmentAssistantRepository {
    override suspend fun ask(question: String, equipmentContext: String): String {
        delay(1000) // Simular latencia de red
        return "El $equipmentContext es un equipo fundamental en bromatología. Se utiliza principalmente para realizar mediciones precisas sobre la composición de la muestra."
    }
}