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
    private val assistant = KnowledgeApiEquipmentAssistantRepository(application)
    private val equipmentCatalog = LocalEquipmentCatalog(application)
    private val inferenceRunning = AtomicBoolean(false)
    private var consecutiveMisses = 0

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
                if (results.isNotEmpty()) {
                    consecutiveMisses = 0
                    _detections.postValue(results)
                    _classificationResult.postValue(
                        results.first().let {
                            ClassificationResult(it.canonicalId, it.label, it.confidence)
                        }
                    )
                } else if (++consecutiveMisses >= MISSES_BEFORE_CLEAR) {
                    _detections.postValue(emptyList())
                    _classificationResult.postValue(null)
                }
            } finally {
                inferenceRunning.set(false)
            }
        }
    }

    fun askAssistant(question: String) {
        val result = _classificationResult.value
        val profile = result?.let { equipmentCatalog.find(it.canonicalId) }
        if (result == null) {
            _assistantAnswer.value = "Enfoca un equipo y vuelve a preguntarme."
            return
        }
        val variant = profile?.variants?.singleOrNull()
        viewModelScope.launch {
            _assistantAnswer.postValue(assistant.ask(question, result.canonicalId, variant?.id))
        }
    }

    override fun onCleared() {
        detector.close()
        super.onCleared()
    }

    companion object {
        private const val MISSES_BEFORE_CLEAR = 3
    }
}
