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
import com.example.labdetect.domain.OneShotEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class CameraViewModel(application: Application) : AndroidViewModel(application) {
    private val detector = OnnxEquipmentDetector(application)
    private val assistant = KnowledgeApiEquipmentAssistantRepository(application)
    private val equipmentCatalog = LocalEquipmentCatalog(application)
    private val inferenceRunning = AtomicBoolean(false)
    private var consecutiveMisses = 0
    @Volatile private var conversationTarget: ClassificationResult? = null
    private var assistantJob: Job? = null

    private val _classificationResult = MutableLiveData<ClassificationResult?>()
    val classificationResult: LiveData<ClassificationResult?> = _classificationResult

    private val _detections = MutableLiveData<List<Detection>>(emptyList())
    val detections: LiveData<List<Detection>> = _detections

    private val _modelReady = MutableLiveData(detector.isReady)
    val modelReady: LiveData<Boolean> = _modelReady

    private val _assistantAnswer = MutableLiveData<OneShotEvent<String>>()
    val assistantAnswer: LiveData<OneShotEvent<String>> = _assistantAnswer

    private val _assistantLoading = MutableLiveData(false)
    val assistantLoading: LiveData<Boolean> = _assistantLoading

    fun onImageCaptured(bitmap: Bitmap) {
        if (!inferenceRunning.compareAndSet(false, true)) return
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val results = detector.detect(bitmap)
                if (results.isNotEmpty()) {
                    consecutiveMisses = 0
                    _detections.postValue(results)
                    if (conversationTarget == null) {
                        _classificationResult.postValue(
                            results.first().let {
                                ClassificationResult(it.canonicalId, it.label, it.confidence)
                            }
                        )
                    }
                } else if (++consecutiveMisses >= MISSES_BEFORE_CLEAR && conversationTarget == null) {
                    _detections.postValue(emptyList())
                    _classificationResult.postValue(null)
                }
            } finally {
                inferenceRunning.set(false)
            }
        }
    }

    fun beginQuestionSession(): Boolean {
        val detected = _classificationResult.value ?: return false
        conversationTarget = detected
        return true
    }

    fun cancelQuestionSession() {
        if (_assistantLoading.value != true) conversationTarget = null
    }

    fun endQuestionSession() {
        conversationTarget = null
    }

    fun askAssistant(question: String) {
        val result = conversationTarget ?: _classificationResult.value
        val profile = result?.let { equipmentCatalog.find(it.canonicalId) }
        if (result == null) {
            _assistantAnswer.value = OneShotEvent("Enfoca un equipo y vuelve a preguntarme.")
            return
        }
        val variant = profile?.variants?.singleOrNull()
        assistantJob?.cancel()
        assistantJob = viewModelScope.launch {
            _assistantLoading.value = true
            val answer = assistant.ask(question, result.canonicalId, variant?.id)
            _assistantAnswer.value = OneShotEvent(answer)
            _assistantLoading.value = false
        }
    }

    override fun onCleared() {
        assistantJob?.cancel()
        detector.close()
        super.onCleared()
    }

    companion object {
        private const val MISSES_BEFORE_CLEAR = 3
    }
}
