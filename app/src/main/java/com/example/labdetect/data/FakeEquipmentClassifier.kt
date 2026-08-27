package com.example.labdetect.data

import android.graphics.Bitmap
import com.example.labdetect.domain.ClassificationResult
import com.example.labdetect.domain.EquipmentClassifier
import kotlin.random.Random

class FakeEquipmentClassifier : EquipmentClassifier {
    private val labEquipment = listOf(
        "Analizador de fibra",
        "Espectrofotómetro",
        "Refractómetro",
        "Balanza analítica",
        "Centrífuga de laboratorio",
        "Microscopio binocular"
    )

    override fun classify(bitmap: Bitmap): ClassificationResult {
        // En una implementación real, aquí procesaríamos el bitmap con el modelo.
        val randomLabel = labEquipment.random()
        val randomConfidence = Random.nextFloat() * 100
        return ClassificationResult(randomLabel, randomConfidence)
    }
}