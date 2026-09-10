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
import com.example.labdetect.domain.EquipmentDetector
import com.example.labdetect.domain.OneShotEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class CameraViewModel @JvmOverloads constructor(
    application: Application,
    private val detector: EquipmentDetector = TfliteEquipmentDetector(application)
) : AndroidViewModel(application) {
    private val detectorReady = (detector as? TfliteEquipmentDetector)?.isReady ?: true
    private val assistant = KnowledgeApiEquipmentAssistantRepository(application)
    private val equipmentCatalog = LocalEquipmentCatalog(application)
    private val inferenceRunning = AtomicBoolean(false)
    private var consecutiveMisses = 0
    private var analyzedFrames = 0
    private var previousFrameDetections: List<Detection> = emptyList()
    @Volatile private var lastDetectedResult: ClassificationResult? = null
    @Volatile private var conversationTarget: ClassificationResult? = null
    private var assistantJob: Job? = null
    @Volatile private var detectionPaused = true
    @Volatile private var detectionGeneration = 0

    private val _classificationResult = MutableLiveData<ClassificationResult?>()
    val classificationResult: LiveData<ClassificationResult?> = _classificationResult

    private val _detections = MutableLiveData<List<Detection>>(emptyList())
    val detections: LiveData<List<Detection>> = _detections

    private val _modelReady = MutableLiveData(detectorReady)
    val modelReady: LiveData<Boolean> = _modelReady

    private val _scannerStatus = MutableLiveData(if (detectorReady) "◌  ESCANEANDO" else "MODELO NO DISPONIBLE")
    val scannerStatus: LiveData<String> = _scannerStatus
    private var lastScannerStatus = _scannerStatus.value.orEmpty()

    private val _assistantAnswer = MutableLiveData<OneShotEvent<String>>()
    val assistantAnswer: LiveData<OneShotEvent<String>> = _assistantAnswer

    private val _assistantLoading = MutableLiveData(false)
    val assistantLoading: LiveData<Boolean> = _assistantLoading

    fun onImageCaptured(bitmap: Bitmap) {
        if (detectionPaused || !inferenceRunning.compareAndSet(false, true)) {
            if (!bitmap.isRecycled) bitmap.recycle()
            return
        }
        val generation = detectionGeneration
        viewModelScope.launch(Dispatchers.Default) {
            try {
                // La ampliación central es útil para equipos lejanos, pero cuesta una
                // segunda inferencia completa. Se hace cada tres análisis, no en cada
                // fotograma sin detección; el objeto cercano sigue apareciendo enseguida.
                val allowCenterCrop = ++analyzedFrames % CENTER_CROP_EVERY_N_FRAMES == 0
                val results = detector.detect(bitmap, allowCenterCrop)
                withContext(Dispatchers.Main) {
                if (detectionPaused || generation != detectionGeneration) return@withContext
                val candidates = results.filter { it.confidence >= MIN_CANDIDATE_CONFIDENCE }
                if (candidates.isNotEmpty()) {
                    val confirmed = candidates.filter { candidate ->
                        if (candidate.confidence < MIN_CONFIRMED_CONFIDENCE) return@filter false
                        val matchesPrevious = previousFrameDetections.any { previous ->
                            previous.confidence >= MIN_PREVIOUS_CONFIDENCE &&
                                isSameEquipment(candidate, previous)
                        }
                        matchesPrevious
                    }
                    previousFrameDetections = candidates
                    val preview = candidates.filter { it.confidence >= MIN_PREVIEW_CONFIDENCE }
                    _detections.value =
                        if (confirmed.isNotEmpty()) confirmed.map { it.copy(confirmed = true) } else preview

                    if (preview.isEmpty()) {
                        if (++consecutiveMisses >= MISSES_BEFORE_CLEAR) clearDetection()
                        return@withContext
                    }
                    consecutiveMisses = 0

                    if (confirmed.isNotEmpty()) {
                        val detected = confirmed.first().let {
                            ClassificationResult(it.canonicalId, it.label, it.confidence)
                        }
                        lastDetectedResult = detected
                        _classificationResult.value = detected
                        publishScannerStatus("●  EQUIPO RECONOCIDO")
                    } else {
                        lastDetectedResult = null
                        _classificationResult.value = null
                        preview.maxByOrNull { it.confidence }?.let {
                            publishScannerStatus("◌  AJUSTANDO ${"%.0f".format(it.confidence)}%")
                        }
                    }
                } else {
                    previousFrameDetections = emptyList()
                    if (++consecutiveMisses >= MISSES_BEFORE_CLEAR) {
                        clearDetection()
                    }
                }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.e("LabDetector", "Frame inference failed", error)
                withContext(Dispatchers.Main) {
                    if (generation == detectionGeneration) clearDetection()
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
        _classificationResult.value = detected
        return true
    }

    fun cancelQuestionSession() {
        if (_assistantLoading.value != true) {
            conversationTarget = null
        }
    }

    fun endQuestionSession() {
        conversationTarget = null
    }

    fun canAcceptFrame(): Boolean = !detectionPaused && !inferenceRunning.get()

    fun resumeDetection() {
        detectionPaused = false
    }

    fun pauseDetection() {
        detectionPaused = true
        detectionGeneration++
        previousFrameDetections = emptyList()
        consecutiveMisses = 0
        lastDetectedResult = null
        _classificationResult.value = null
        _detections.value = emptyList()
    }

    fun cancelAssistant() {
        assistantJob?.cancel()
        assistantJob = null
        conversationTarget = null
        _assistantLoading.value = false
    }

    fun reportFrameReadFailure() {
        publishScannerStatus("◌  CÁMARA ACTIVA")
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
        val overlap = if (union > 0f) intersection / union else 0f
        if (overlap >= STABLE_BOX_IOU) return true

        // Al grabar a pulso (especialmente frente a una pantalla), la caja puede
        // desplazarse entre dos fotogramas aunque siga siendo el mismo aparato. La
        // clase debe coincidir y los centros deben mantenerse cerca; no basta con
        // reutilizar el nombre en cualquier parte de la imagen.
        val currentCenterX = (current.left + current.right) / 2f
        val currentCenterY = (current.top + current.bottom) / 2f
        val previousCenterX = (previous.left + previous.right) / 2f
        val previousCenterY = (previous.top + previous.bottom) / 2f
        val centerDistance = sqrt(
            (currentCenterX - previousCenterX) * (currentCenterX - previousCenterX) +
                (currentCenterY - previousCenterY) * (currentCenterY - previousCenterY)
        )
        return centerDistance <= STABLE_CENTER_DISTANCE
    }

    private fun publishScannerStatus(value: String) {
        if (value == lastScannerStatus) return
        lastScannerStatus = value
        _scannerStatus.postValue(value)
    }

    private fun clearDetection() {
        _detections.value = emptyList()
        _classificationResult.value = null
        lastDetectedResult = null
        publishScannerStatus("◌  ESCANEANDO")
    }

    companion object {
        // Una ausencia real basta: la etiqueta no debe quedarse sobre una escena que
        // ya no contiene el equipo. El detector ya exige dos fotogramas estables para
        // confirmar una etiqueta, por lo que esta salida rápida no vuelve inestable
        // el reconocimiento.
        private const val MISSES_BEFORE_CLEAR = 1
        private const val MIN_CANDIDATE_CONFIDENCE = 60f
        private const val MIN_PREVIEW_CONFIDENCE = 70f
        private const val MIN_CONFIRMED_CONFIDENCE = 85f
        private const val MIN_PREVIOUS_CONFIDENCE = 70f
        private const val STABLE_BOX_IOU = 0.28f
        private const val STABLE_CENTER_DISTANCE = 0.18f
        private const val CENTER_CROP_EVERY_N_FRAMES = 3
    }
}
