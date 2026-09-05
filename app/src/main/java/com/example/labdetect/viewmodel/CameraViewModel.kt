package com.example.labdetect.viewmodel

import android.graphics.Bitmap
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.labdetect.data.KnowledgeApiEquipmentAssistantRepository
import com.example.labdetect.data.LocalEquipmentCatalog
import com.example.labdetect.data.TfliteEquipmentDetector
import com.example.labdetect.domain.ClassificationResult
import com.example.labdetect.domain.Detection
import com.example.labdetect.domain.OneShotEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

class CameraViewModel(application: Application) : AndroidViewModel(application) {
    private val detector = TfliteEquipmentDetector(application)
    private val assistant = KnowledgeApiEquipmentAssistantRepository(application)
    private val equipmentCatalog = LocalEquipmentCatalog(application)
    private val inferenceRunning = AtomicBoolean(false)
    private val analysisPaused = AtomicBoolean(false)
    private var consecutiveMisses = 0
    private var previousFrameDetections: List<Detection> = emptyList()
    @Volatile private var lastDetectedResult: ClassificationResult? = null
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
        if (analysisPaused.get() || !inferenceRunning.compareAndSet(false, true)) {
            if (!bitmap.isRecycled) bitmap.recycle()
            return
        }
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val results = detector.detect(bitmap)
                if (analysisPaused.get()) return@launch
                // Las señales medias solo sirven para comprobar estabilidad entre dos
                // fotos. No se dibujan ni se usan para conversar hasta superar 65 %.
                val candidates = results.filter { it.confidence >= MIN_CANDIDATE_CONFIDENCE }
                if (candidates.isNotEmpty()) {
                    consecutiveMisses = 0
                    val confirmed = candidates.filter { candidate ->
                        candidate.confidence >= MIN_CONFIRMED_CONFIDENCE &&
                            previousFrameDetections.any { previous ->
                                isSameEquipment(candidate, previous)
                            }
                    }
                    previousFrameDetections = candidates

                    // Cada cuadro visible debe persistir en dos capturas consecutivas y en
                    // la misma zona. Esto descarta falsos positivos débiles, pero permite
                    // reconocer equipos antes de alcanzar el 80 %.
                    if (confirmed.isNotEmpty() && conversationTarget == null) {
                        _detections.postValue(confirmed)
                        val detected = confirmed.first().let {
                            ClassificationResult(it.canonicalId, it.label, it.confidence)
                        }
                        lastDetectedResult = detected
                        _classificationResult.postValue(detected)
                    } else if (conversationTarget == null) {
                        _detections.postValue(emptyList())
                        _classificationResult.postValue(null)
                    }
                } else {
                    previousFrameDetections = emptyList()
                    if (++consecutiveMisses >= MISSES_BEFORE_CLEAR && conversationTarget == null) {
                        _detections.postValue(emptyList())
                        _classificationResult.postValue(null)
                    }
                }
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
                inferenceRunning.set(false)
            }
        }
    }

    fun beginQuestionSession(): Boolean {
        val detected = _classificationResult.value ?: lastDetectedResult ?: return false
        conversationTarget = detected
        analysisPaused.set(true)
        _classificationResult.value = detected
        _detections.value = emptyList()
        return true
    }

    fun cancelQuestionSession() {
        if (_assistantLoading.value != true) {
            conversationTarget = null
            analysisPaused.set(false)
        }
    }

    fun endQuestionSession() {
        conversationTarget = null
        analysisPaused.set(false)
    }

    fun isAnalysisPaused(): Boolean = analysisPaused.get()

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
            try {
                val answer = assistant.ask(question, result.canonicalId, variant?.id)
                _assistantAnswer.value = OneShotEvent(answer)
            } finally {
                _assistantLoading.value = false
            }
        }
    }

    override fun onCleared() {
        assistantJob?.cancel()
        detector.close()
        super.onCleared()
    }

    private fun isSameEquipment(current: Detection, previous: Detection?): Boolean {
        if (previous == null || current.canonicalId != previous.canonicalId) return false
        val left = max(current.left, previous.left)
        val top = max(current.top, previous.top)
        val right = min(current.right, previous.right)
        val bottom = min(current.bottom, previous.bottom)
        val intersection = max(0f, right - left) * max(0f, bottom - top)
        val currentArea = max(0f, current.right - current.left) * max(0f, current.bottom - current.top)
        val previousArea = max(0f, previous.right - previous.left) * max(0f, previous.bottom - previous.top)
        val union = currentArea + previousArea - intersection
        return union > 0f && intersection / union >= STABLE_BOX_IOU
    }

    companion object {
        private const val MISSES_BEFORE_CLEAR = 3
        private const val MIN_CANDIDATE_CONFIDENCE = 50f
        private const val MIN_CONFIRMED_CONFIDENCE = 65f
        private const val STABLE_BOX_IOU = 0.50f
    }
}
