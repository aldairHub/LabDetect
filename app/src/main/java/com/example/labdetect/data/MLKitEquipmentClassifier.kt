package com.example.labdetect.data

import android.graphics.Bitmap
import com.example.labdetect.domain.ClassificationResult
import com.example.labdetect.domain.EquipmentClassifier
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MLKitEquipmentClassifier : EquipmentClassifier {
    private val labeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)

    override fun classify(bitmap: Bitmap): ClassificationResult {
        val image = InputImage.fromBitmap(bitmap, 0)
        return try {
            val labels = Tasks.await(labeler.process(image))
            if (labels.isNullOrEmpty()) {
                ClassificationResult("Nada detectado", 0f)
            } else {
                // Tomamos la etiqueta con mayor confianza
                val topLabel = labels.maxByOrNull { it.confidence }
                if (topLabel != null) {
                    ClassificationResult(
                        label = topLabel.text,
                        confidence = topLabel.confidence * 100
                    )
                } else {
                    ClassificationResult("Nada detectado", 0f)
                }
            }
        } catch (e: Exception) {
            ClassificationResult("Nada detectado", 0f)
        }
    }
}
