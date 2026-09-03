package com.example.labdetect.viewmodel

import android.graphics.Bitmap
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.labdetect.data.KnowledgeApiEquipmentAssistantRepository
import com.example.labdetect.data.LocalEquipmentCatalog
import com.example.labdetect.data.OnnxEquipmentDetector
import com.example.labdetect.domain.ClassificationResult
import com.example.labdetect.domain.Detection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class CameraViewModel(application: Application) : AndroidViewModel(application) {
    private val detector = OnnxEquipmentDetector(application)
    private val assistant = KnowledgeApiEquipmentAssistantRepository()
    private val equipmentCatalog = LocalEquipmentCatalog(application)
    private val inferenceRunning = AtomicBoolean(false)

    private val _classificationResult = MutableLiveData<ClassificationResult?>()
    val classificationResult: LiveData<ClassificationResult?> = _classificationResult

    private val _detections = MutableLiveData<List<Detection>>(emptyList())
    val detections: LiveData<List<Detection>> = _detections

    private val _modelReady = MutableLiveData(detector.isReady)
    val modelReady: LiveData<Boolean> = _modelReady

    private val _assistantAnswer = MutableLiveData<String>()
    val assistantAnswer: LiveData<String> = _assistantAnswer

    fun onImageCaptured(bitmap: Bitmap) {
        if (!inferenceRunning.compareAndSet(false, true)) return
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val results = detector.detect(bitmap)
                _detections.postValue(results)
                _classificationResult.postValue(
                    results.firstOrNull()?.let {
                        ClassificationResult(it.canonicalId, it.label, it.confidence)
                    }
                )
            } finally {
                inferenceRunning.set(false)
            }
        }
    }

    fun askAssistant(question: String) {
        val result = _classificationResult.value
        val profile = result?.let { equipmentCatalog.find(it.canonicalId) }
        val variant = profile?.variants?.singleOrNull()
        if (result == null) {
            _assistantAnswer.value = "Primero enfoca un equipo para poder consultar su manual."
            return
        }
        if (variant == null) {
            _assistantAnswer.value = "Este equipo tiene varias variantes en el laboratorio. Abre Detalles y selecciona la que tienes delante."
            return
        }
        viewModelScope.launch {
            _assistantAnswer.postValue(assistant.ask(question, variant.id))
        }
    }

    override fun onCleared() {
        detector.close()
        super.onCleared()
    }
}
