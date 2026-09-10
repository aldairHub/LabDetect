package com.example.labdetect

import android.Manifest
import android.content.res.ColorStateList
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.util.Size
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Camera
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.view.PreviewView
import androidx.lifecycle.Lifecycle
import android.os.Build
import android.provider.Settings
import android.net.Uri
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.doOnLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.example.labdetect.data.FavoriteEquipmentStore
import com.example.labdetect.data.EquipmentInteractionStore
import com.example.labdetect.data.LocalEquipmentCatalog
import com.example.labdetect.data.LocalManualRepository
import com.example.labdetect.domain.ClassificationResult
import com.example.labdetect.databinding.FragmentCameraBinding
import com.example.labdetect.speech.AndroidSpeechEngine
import com.example.labdetect.viewmodel.CameraViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CameraFragment : Fragment() {
    private var _binding: FragmentCameraBinding? = null
    private val binding get() = _binding!!

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val cameraAnalysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var lastAnalysisAt = 0L
    private val viewModel: CameraViewModel by viewModels()
    private var activeCamera: Camera? = null

    private var speechRecognizer: SpeechRecognizer? = null
    private var recognitionGeneration = 0
    private var recognitionFallbackUsed = false
    private var recognitionUsesDevice = false
    private var recognitionTimeout: Runnable? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var previewUseCase: Preview? = null
    private var analysisUseCase: ImageAnalysis? = null
    @Volatile private var cameraGeneration = 0
    private var cameraStarting = false
    private var cameraRetries = 0
    private var cameraIssue: String? = null
    private var cameraWatchdog: Runnable? = null
    private lateinit var speechEngine: AndroidSpeechEngine
    private lateinit var favoriteStore: FavoriteEquipmentStore
    private lateinit var interactionStore: EquipmentInteractionStore
    private lateinit var equipmentCatalog: LocalEquipmentCatalog
    private var defaultMicTint: ColorStateList? = null
    private var isListening = false
    private var partialTranscript = ""
    private var pendingTranscript = ""
    private var submitWhenReady = false
    private var startListeningAfterPermission = false
    private var voiceState = VoiceState.IDLE
    private var activeQuestionEquipmentId: String? = null
    private var activeQuestionEquipmentName: String? = null
    private var activeQuestionFrame: Bitmap? = null
    private var lastSubmittedQuestion: String = ""
    private var lastRememberedEquipmentId: String? = null
    private lateinit var manualRepository: LocalManualRepository
    private var cardTarget: ClassificationResult? = null
    private var keyboardVisible = false
    private var dismissedCardId: String? = null
    private var latestBackdrop: Bitmap? = null
    private var lastBackdropAt = 0L
    private val feedbackShownFor = mutableSetOf<String>()

    private enum class VoiceState { IDLE, LISTENING, READY_TO_SEND, AWAITING_RESULT, PROCESSING, SPEAKING }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        if (_binding != null) {
            if (cameraPermissionGranted()) startCamera()
            else showCameraIssue("Permiso de cámara pendiente · toca para habilitar")
            val requestedVoice = startListeningAfterPermission
            startListeningAfterPermission = false
            if (requestedVoice) {
                if (audioPermissionGranted() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) beginVoiceCapture()
                else Toast.makeText(context, "El micrófono necesita permiso. Puedes escribir tu pregunta.", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCameraBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        speechEngine = AndroidSpeechEngine(requireContext())
        favoriteStore = FavoriteEquipmentStore(requireContext())
        interactionStore = EquipmentInteractionStore(requireContext())
        equipmentCatalog = LocalEquipmentCatalog(requireContext())
        manualRepository = LocalManualRepository(requireContext())
        defaultMicTint = binding.fabMic.backgroundTintList

        binding.fabMic.isEnabled = true
        binding.viewFinder.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        binding.tvScanStatus.setOnClickListener {
            if (!cameraPermissionGranted()) requestCameraPermission()
            else { cameraRetries = 0; releaseCamera(); startCamera() }
        }
        binding.viewFinder.previewStreamState.observe(viewLifecycleOwner) { state ->
            if (state == PreviewView.StreamState.STREAMING && activeCamera != null) {
                cameraIssue = null
                renderScannerStatus()
            }
        }
        requestMissingPermissions()
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val keyboard = insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())
            keyboardVisible = keyboard
            binding.tvQuestionPrompt.isVisible = !keyboard && viewModel.classificationResult.value != null && voiceState == VoiceState.IDLE
            binding.answerCard.isVisible = !keyboard && cardTarget != null && dismissedCardId != cardTarget?.canonicalId &&
                (viewModel.classificationResult.value != null || voiceState != VoiceState.IDLE)
            if (keyboard) binding.conversationScroll.post { _binding?.conversationScroll?.fullScroll(View.FOCUS_DOWN) }
            insets
        }

        binding.btnDetails.setOnClickListener { openCurrentEquipmentDetails() }
        binding.tvCameraAnswer.setOnClickListener {
            LabSheets.reader(requireContext(), cardTarget?.label ?: "Equipo",
                binding.tvCameraAnswer.text.toString(), "Información de esta conversación")
        }
        binding.btnFavoritesList.setOnClickListener { showFavorites() }
        binding.btnFeedbackYes.setOnClickListener { saveDetectionFeedback(null) }
        binding.btnFeedbackCorrect.setOnClickListener { showCorrectionPicker() }
        binding.fabMic.setOnClickListener { handleMicClick() }
        binding.btnSendQuestion.setOnClickListener { submitTypedQuestion() }
        binding.btnDismissAnswer.setOnClickListener {
            dismissedCardId = cardTarget?.canonicalId
            binding.answerCard.isVisible = false
        }
        binding.viewFinder.setOnTouchListener { viewFinder, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                viewFinder.performClick()
                focusAndMeterAt(event.x, event.y)
                binding.detectionOverlay.showFocus(event.x, event.y)
            }
            true
        }
        binding.tietCameraQuestion.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submitTypedQuestion()
                true
            } else false
        }

        viewModel.classificationResult.observe(viewLifecycleOwner) { result ->
            binding.tvScanStatus.isVisible = result == null || cameraIssue != null
            binding.tvQuestionPrompt.isVisible = !keyboardVisible && result != null && voiceState == VoiceState.IDLE
            if (result == null) {
                if (voiceState == VoiceState.IDLE) binding.answerCard.isVisible = false
            } else if (voiceState == VoiceState.IDLE) {
                binding.tvQuestionPrompt.text = "¿Qué deseas saber de este equipo?"
                if (cardTarget?.canonicalId != result.canonicalId) {
                    cardTarget = result
                    dismissedCardId = null
                    binding.tvCameraAnswer.text = manualRepository.find(result.canonicalId)?.function
                        ?.substringBefore(". ")?.let { it.trimEnd('.') + "." }
                        ?: "Consulta la ficha de ${result.label.lowercase()}."
                    binding.tvCameraAnswer.isVisible = true
                    val thumbnail = captureEquipmentThumbnail()
                    binding.ivEquipmentThumb.setImageBitmap(thumbnail)
                    if (thumbnail != null) {
                        val directory = java.io.File(requireContext().filesDir, "equipment-thumbnails")
                        cameraAnalysisExecutor.execute {
                            runCatching {
                                directory.mkdirs()
                                java.io.File(directory, "${result.canonicalId}.jpg").outputStream().use {
                                    thumbnail.compress(Bitmap.CompressFormat.JPEG, 85, it)
                                }
                            }
                        }
                    }
                    binding.root.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                }
                binding.answerCard.isVisible = !keyboardVisible && dismissedCardId != result.canonicalId
                if (lastRememberedEquipmentId != result.canonicalId) {
                    interactionStore.rememberSeen(result.canonicalId)
                    lastRememberedEquipmentId = result.canonicalId
                }
            }
        }

        viewModel.detections.observe(viewLifecycleOwner) { detections ->
            binding.detectionOverlay.submitDetections(detections)
        }

        viewModel.scannerStatus.observe(viewLifecycleOwner) { renderScannerStatus() }

        viewModel.modelReady.observe(viewLifecycleOwner) { ready ->
            if (!ready) {
                Toast.makeText(context, "Modelo de detección pendiente de instalar", Toast.LENGTH_LONG).show()
            }
        }


        viewModel.assistantLoading.observe(viewLifecycleOwner) { loading ->
            binding.pbAssistant.isVisible = loading
            binding.btnSendQuestion.isEnabled = !loading && voiceState == VoiceState.IDLE
            binding.fabMic.isEnabled = !loading && voiceState == VoiceState.IDLE
            if (loading && !isListening) {
                voiceState = VoiceState.PROCESSING
                showVoiceState("Consultando información del equipo…")
            }
        }

        viewModel.assistantAnswer.observe(viewLifecycleOwner) { event ->
            val answer = event.consume() ?: return@observe
            binding.tvCameraAnswer.text = answer
            binding.tvCameraAnswer.isVisible = true
            binding.answerCard.isVisible = !keyboardVisible
            cardTarget = activeQuestionEquipmentId?.let {
                ClassificationResult(it, activeQuestionEquipmentName ?: "Equipo", 0f)
            } ?: cardTarget
            binding.tvCameraAnswer.alpha = 0f
            binding.tvCameraAnswer.translationY = 8f * resources.displayMetrics.density
            binding.tvCameraAnswer.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(220L)
                .start()
            val equipmentId = activeQuestionEquipmentId.orEmpty()
            if (equipmentId.isNotBlank()) {
                interactionStore.rememberQuestion(equipmentId, lastSubmittedQuestion, answer)
                if (answer.startsWith("No cuento con esa información dentro de mis manuales")) {
                    interactionStore.rememberMissingInformation(equipmentId, lastSubmittedQuestion)
                }
            }
            voiceState = VoiceState.SPEAKING
            binding.fabMic.isEnabled = false
            showVoiceState("Respondiendo sobre ${activeQuestionEquipmentName ?: "el equipo"}…")
            speechEngine.speak(answer) {
                if (_binding != null) {
                    finishInteraction()
                    showDetectionFeedbackIfNeeded()
                }
            }
        }
    }

    private fun requestMissingPermissions() {
        val missing = arrayOf(Manifest.permission.CAMERA).filter {
            ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            val asked = requireContext().getSharedPreferences("permissions", 0).getBoolean("cameraRequested", false)
            if (!asked) requestCameraPermission()
            else showCameraIssue("Permiso de cámara pendiente · toca para habilitar")
        }
    }

    private fun configureSpeechRecognizer(forceSystem: Boolean): Boolean {
        destroyRecognizer()
        recognitionUsesDevice = !forceSystem && Build.VERSION.SDK_INT >= 31 &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(requireContext())
        if (!recognitionUsesDevice && !SpeechRecognizer.isRecognitionAvailable(requireContext())) return false
        speechRecognizer = if (recognitionUsesDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(requireContext())
            else SpeechRecognizer.createSpeechRecognizer(requireContext())
        val session = recognitionGeneration
        speechRecognizer!!.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (_binding == null || session != recognitionGeneration || voiceState != VoiceState.LISTENING) return
                isListening = true
                armRecognitionTimeout(30_000L)
                showListeningFeedback("Escuchando · toca otra vez para enviar")
            }

            override fun onResults(results: Bundle?) {
                if (_binding == null || session != recognitionGeneration || voiceState !in setOf(VoiceState.LISTENING, VoiceState.AWAITING_RESULT)) return
                isListening = false
                val text = bestTranscript(results).ifBlank { partialTranscript }
                handleRecognizedText(text)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (_binding == null || session != recognitionGeneration || voiceState != VoiceState.LISTENING) return
                partialTranscript = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                if (partialTranscript.isNotBlank()) {
                    val preview = partialTranscript.take(64)
                    showVoiceState("🎙 $preview")
                }
            }

            override fun onError(error: Int) {
                if (_binding == null || session != recognitionGeneration || voiceState !in setOf(VoiceState.LISTENING, VoiceState.AWAITING_RESULT)) return
                val usablePartial = partialTranscript.takeIf { it.length >= 3 }
                if (usablePartial != null) {
                    isListening = false
                    handleRecognizedText(usablePartial)
                    return
                }
                Log.w("LabVoice", "Recognition error=$error device=$recognitionUsesDevice")
                if (!recognitionFallbackUsed && error in setOf(
                        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT,
                        SpeechRecognizer.ERROR_SERVER_DISCONNECTED, SpeechRecognizer.ERROR_NETWORK,
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT)) {
                    recognitionFallbackUsed = true
                    voiceState = VoiceState.LISTENING
                    startVoiceQuestion(forceSystem = true)
                    return
                }
                finishInteraction(cancelled = true)
                val message = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        "No alcancé a escucharte. Toca el micrófono e inténtalo de nuevo."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                        "No hay reconocimiento de voz disponible. Puedes escribir la pregunta."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                        "Activa el permiso de micrófono para LabDetect en Ajustes."
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                        "El reconocimiento de español no está instalado. Revisa los idiomas de voz de tu teléfono."
                    SpeechRecognizer.ERROR_AUDIO -> "El micrófono no está disponible. Revisa si otra aplicación lo está usando."
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "El servicio de voz está ocupado. Vuelve a tocar el micrófono."
                    else -> "El servicio de voz no respondió. Vuelve a intentarlo o escribe tu pregunta."
                }
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }

            override fun onRmsChanged(rmsdB: Float) {
                if (voiceState != VoiceState.LISTENING || _binding == null || session != recognitionGeneration) return
                val pulse = (1.05f + (rmsdB.coerceIn(0f, 12f) / 100f))
                binding.fabMic.scaleX = pulse
                binding.fabMic.scaleY = pulse
            }

            override fun onBeginningOfSpeech() = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() {
                if (_binding != null && session == recognitionGeneration && voiceState == VoiceState.LISTENING) {
                    armRecognitionTimeout(5_000L)
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        return true
    }

    private fun handleMicClick() {
        when (voiceState) {
            VoiceState.IDLE -> {
                if (!audioPermissionGranted()) {
                    val prefs = requireContext().getSharedPreferences("permissions", 0)
                    if (prefs.getBoolean("microphoneRequested", false) &&
                        !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle("Permiso de micrófono")
                            .setMessage("Activa Micrófono en los permisos de LabDetect para preguntar por voz.")
                            .setPositiveButton("Abrir ajustes") { _, _ ->
                                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:" + requireContext().packageName)))
                            }.setNegativeButton("Ahora no", null).show()
                        return
                    }
                    prefs.edit().putBoolean("microphoneRequested", true).apply()
                    startListeningAfterPermission = true
                    requestPermissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                    return
                }
                if (!SpeechRecognizer.isRecognitionAvailable(requireContext()) &&
                    !(Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(requireContext()))) {
                    Toast.makeText(context, "La voz no está disponible; escribe tu pregunta.", Toast.LENGTH_SHORT).show()
                    return
                }
                beginVoiceCapture()
            }
            VoiceState.LISTENING -> requestVoiceSubmission()
            VoiceState.READY_TO_SEND -> submitRecognizedQuestion(pendingTranscript)
            VoiceState.AWAITING_RESULT, VoiceState.PROCESSING, VoiceState.SPEAKING -> {
                Toast.makeText(context, "Estoy terminando la respuesta actual.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun beginVoiceCapture() {
        speechEngine.stop()
        viewModel.endQuestionSession()
        if (!viewModel.beginQuestionSession()) {
            Toast.makeText(context, "Primero enfoca un equipo.", Toast.LENGTH_SHORT).show()
            return
        }
        setActiveQuestionEquipment()
        binding.answerCard.isVisible = false
        binding.btnSendQuestion.isEnabled = false
        partialTranscript = ""
        pendingTranscript = ""
        submitWhenReady = false
        recognitionFallbackUsed = false
        voiceState = VoiceState.LISTENING
        binding.fabMic.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        startVoiceQuestion()
    }

    private fun requestVoiceSubmission() {
        submitWhenReady = true
        voiceState = VoiceState.AWAITING_RESULT
        binding.fabMic.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        showVoiceState("Procesando lo que dijiste…")
        binding.fabMic.setImageResource(R.drawable.ic_mic)
        binding.fabMic.animate().scaleX(1f).scaleY(1f).setDuration(100L).start()
        runCatching { speechRecognizer?.stopListening() }
        val session = recognitionGeneration
        mainHandler.postDelayed({
            if (_binding != null && session == recognitionGeneration && voiceState == VoiceState.AWAITING_RESULT) {
                if (partialTranscript.isNotBlank()) {
                    submitRecognizedQuestion(partialTranscript)
                } else {
                    finishInteraction(cancelled = true)
                    Toast.makeText(context, "No escuché una pregunta. Toca para intentarlo otra vez.", Toast.LENGTH_SHORT).show()
                }
            }
        }, RECOGNITION_RESULT_TIMEOUT_MS)
    }

    private fun handleRecognizedText(text: String) {
        destroyRecognizer()
        if (text.isBlank()) {
            finishInteraction(cancelled = true)
            Toast.makeText(context, "No escuché una pregunta. Toca para intentarlo otra vez.", Toast.LENGTH_SHORT).show()
            return
        }
        if (submitWhenReady || voiceState == VoiceState.AWAITING_RESULT) {
            submitRecognizedQuestion(text)
        } else {
            pendingTranscript = text
            voiceState = VoiceState.READY_TO_SEND
            resetMicVisual(sendMode = true)
            showVoiceState("Voz capturada · toca para enviar")
            binding.tvMicHint.text = text.take(72)
            binding.tvMicHint.isVisible = true
        }
    }

    private fun startVoiceQuestion(forceSystem: Boolean = false) {
        if (!runCatching { configureSpeechRecognizer(forceSystem) }.getOrDefault(false)) {
            finishInteraction(cancelled = true)
            Toast.makeText(context, "No hay un servicio de reconocimiento de voz disponible. Revisa los servicios de voz del teléfono.", Toast.LENGTH_LONG).show()
            return
        }
        isListening = true
        showListeningFeedback("Escuchando · toca otra vez para enviar")
        val locale = "es"
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, locale)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, requireContext().packageName)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, recognitionUsesDevice)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1_500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 800L)
            putStringArrayListExtra("android.speech.extra.BIASING_STRINGS", ArrayList(speechVocabulary()))
        }
        armRecognitionTimeout(8_000L)
        runCatching { speechRecognizer?.startListening(intent) }
            .onFailure {
                finishInteraction(cancelled = true)
                Toast.makeText(context, "No pude iniciar el micrófono; escribe tu pregunta.", Toast.LENGTH_SHORT).show()
            }
    }

    private fun destroyRecognizer() {
        recognitionGeneration++
        recognitionTimeout?.let(mainHandler::removeCallbacks)
        recognitionTimeout = null
        runCatching { speechRecognizer?.cancel() }
        runCatching { speechRecognizer?.destroy() }
        speechRecognizer = null
    }

    private fun armRecognitionTimeout(delay: Long) {
        recognitionTimeout?.let(mainHandler::removeCallbacks)
        val session = recognitionGeneration
        recognitionTimeout = Runnable {
            if (_binding != null && session == recognitionGeneration &&
                voiceState in setOf(VoiceState.LISTENING, VoiceState.AWAITING_RESULT)) {
                if (partialTranscript.isNotBlank()) handleRecognizedText(partialTranscript)
                else {
                    finishInteraction(cancelled = true)
                    Toast.makeText(context, "La voz no respondió a tiempo. Toca el micrófono para reintentar.", Toast.LENGTH_LONG).show()
                }
            }
        }.also { mainHandler.postDelayed(it, delay) }
    }

    private fun bestTranscript(results: Bundle?): String {
        val alternatives = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.filter { it.isNotBlank() }.orEmpty()
        if (alternatives.isEmpty()) return ""
        val scores = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
        if (scores == null || scores.none { it >= 0f }) return alternatives.first()
        return alternatives.indices.maxByOrNull { scores.getOrNull(it)?.coerceAtLeast(0f) ?: 0f }
            ?.let(alternatives::get).orEmpty()
    }

    private fun speechVocabulary(): List<String> = buildList {
        viewModel.classificationResult.value?.label?.let(::add)
        addAll(equipmentCatalog.equipmentNames())
        addAll(listOf(
            "bromatología", "laboratorio", "manual", "funcionamiento", "encender", "apagar",
            "temperatura", "seguridad", "limpieza", "mantenimiento", "calibración", "muestra",
            "esterilización", "centrifugación", "procedimiento", "precauciones"
        ))
    }.distinct()

    private fun submitRecognizedQuestion(text: String) {
        if (voiceState in setOf(VoiceState.PROCESSING, VoiceState.SPEAKING) || text.isBlank()) return
        destroyRecognizer()
        isListening = false
        submitWhenReady = false
        pendingTranscript = ""
        partialTranscript = ""
        lastSubmittedQuestion = text.trim()
        voiceState = VoiceState.PROCESSING
        resetMicVisual()
        showVoiceState("Consultando información del equipo…")
        viewModel.askAssistant(text.trim())
    }

    private fun showListeningFeedback(message: String) {
        showVoiceState(message)
        binding.fabMic.setImageResource(R.drawable.ic_mic)
        binding.fabMic.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#35D05B"))
        binding.fabMic.scaleX = 1.1f
        binding.fabMic.scaleY = 1.1f
        binding.tvMicHint.text = "Habla con normalidad · toca otra vez al terminar"
        binding.tvMicHint.isVisible = true
    }

    private fun resetMicVisual(sendMode: Boolean = false) {
        binding.fabMic.animate().cancel()
        binding.fabMic.scaleX = 1f
        binding.fabMic.scaleY = 1f
        binding.fabMic.backgroundTintList = if (sendMode) {
            ColorStateList.valueOf(Color.parseColor("#79BC35"))
        } else defaultMicTint
        binding.fabMic.setImageResource(if (sendMode) R.drawable.ic_send else R.drawable.ic_mic)
    }

    private fun showVoiceState(message: String) {
        if (_binding == null) return
        val animateEntrance = !binding.tvVoiceState.isVisible
        binding.tvVoiceState.text = message
        binding.tvVoiceState.isVisible = true
        if (animateEntrance) {
            binding.tvVoiceState.alpha = 0f
            binding.tvVoiceState.scaleX = 0.94f
            binding.tvVoiceState.scaleY = 0.94f
            binding.tvVoiceState.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160L).start()
        }
    }

    private fun hideVoiceState() {
        if (_binding == null) return
        binding.tvVoiceState.isVisible = false
    }

    private fun finishInteraction(cancelled: Boolean = false) {
        destroyRecognizer()
        if (_binding == null) return
        isListening = false
        submitWhenReady = false
        pendingTranscript = ""
        partialTranscript = ""
        voiceState = VoiceState.IDLE
        resetMicVisual()
        binding.fabMic.isEnabled = true
        binding.btnSendQuestion.isEnabled = true
        binding.tvMicHint.text = "Toca para hablar · toca de nuevo para enviar"
        binding.tvMicHint.isVisible = false
        hideVoiceState()
        if (cancelled) viewModel.cancelQuestionSession() else viewModel.endQuestionSession()
    }

    private fun submitTypedQuestion() {
        val question = binding.tietCameraQuestion.text?.toString()?.trim().orEmpty()
        if (question.isBlank()) return
        if (voiceState != VoiceState.IDLE) return
        speechEngine.stop()
        viewModel.endQuestionSession()
        if (!viewModel.beginQuestionSession()) {
            Toast.makeText(context, "Primero enfoca un equipo.", Toast.LENGTH_SHORT).show()
            return
        }
        setActiveQuestionEquipment()
        binding.tietCameraQuestion.text?.clear()
        binding.tietCameraQuestion.clearFocus()
        requireContext().getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(binding.tietCameraQuestion.windowToken, 0)
        voiceState = VoiceState.PROCESSING
        lastSubmittedQuestion = question
        showVoiceState("Consultando información del equipo…")
        viewModel.askAssistant(question)
    }

    private fun openCurrentEquipmentDetails() {
        val result = cardTarget ?: viewModel.classificationResult.value ?: return
        findNavController().navigate(
            R.id.action_cameraFragment_to_detailFragment,
            Bundle().apply {
                putString("equipmentName", result.label)
                putString("equipmentId", result.canonicalId)
            }
        )
    }

    private fun captureEquipmentThumbnail(): Bitmap? {
        val source = latestBackdrop ?: return null
        val detection = viewModel.detections.value?.firstOrNull { it.confirmed } ?: return source
        val left = (detection.left * source.width).toInt().coerceIn(0, source.width - 1)
        val top = (detection.top * source.height).toInt().coerceIn(0, source.height - 1)
        val right = (detection.right * source.width).toInt().coerceIn(left + 1, source.width)
        val bottom = (detection.bottom * source.height).toInt().coerceIn(top + 1, source.height)
        return Bitmap.createBitmap(source, left, top, right - left, bottom - top)
    }

    private fun showFavorites() {
        val favorites = favoriteStore.all().mapNotNull(equipmentCatalog::find).sortedBy { it.displayName }
        val recent = interactionStore.recentEquipmentIds().mapNotNull(equipmentCatalog::find)
        val profiles = (favorites + recent).distinctBy { it.id }
        LabSheets.favorites(requireContext(), profiles, favoriteStore::contains) { profile ->
            findNavController().navigate(R.id.action_cameraFragment_to_detailFragment, Bundle().apply {
                putString("equipmentName", profile.displayName)
                putString("equipmentId", profile.id)
            })
        }
    }
    private fun setActiveQuestionEquipment() {
        val result = viewModel.classificationResult.value ?: return
        activeQuestionEquipmentId = result.canonicalId
        activeQuestionEquipmentName = result.label
        activeQuestionFrame = binding.viewFinder.bitmap?.copy(Bitmap.Config.ARGB_8888, false)
        binding.ivEquipmentThumb.setImageBitmap(captureEquipmentThumbnail())
        binding.tvQuestionPrompt.isVisible = false
        binding.root.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        binding.feedbackBar.isVisible = false
    }

    private fun showDetectionFeedbackIfNeeded() {
        val id = activeQuestionEquipmentId ?: return
        if (!feedbackShownFor.add(id)) return
        binding.tvFeedbackPrompt.text = "¿Era ${activeQuestionEquipmentName ?: "este equipo"}?"
        binding.feedbackBar.isVisible = true
        binding.feedbackBar.alpha = 0f
        binding.feedbackBar.animate().alpha(1f).setDuration(180L).start()
    }

    private fun saveDetectionFeedback(correctedId: String?) {
        val predictedId = activeQuestionEquipmentId ?: return
        interactionStore.saveDetectionFeedback(predictedId, correctedId, activeQuestionFrame)
        activeQuestionFrame = null
        binding.feedbackBar.isVisible = false
        Toast.makeText(context, if (correctedId == null) "Gracias, quedó confirmado." else "Corrección guardada para mejorar el modelo.", Toast.LENGTH_SHORT).show()
    }

    private fun showCorrectionPicker() {
        val profiles = equipmentCatalog.all().sortedBy { it.displayName }
        val labels = profiles.map { it.displayName } + "Ninguno de estos"
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("¿Qué equipo era?")
            .setItems(labels.toTypedArray()) { _, index ->
                saveDetectionFeedback(profiles.getOrNull(index)?.id ?: "none")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun renderScannerStatus() {
        val current = _binding ?: return
        current.tvScanStatus.text = cameraIssue ?: when {
            viewModel.scannerStatus.value.orEmpty().contains("AJUSTANDO") -> "Reconociendo equipo…"
            viewModel.scannerStatus.value.orEmpty().contains("MODELO") -> "Detector no disponible"
            else -> "Apunta a un equipo del laboratorio"
        }
        current.tvScanStatus.isVisible = cameraIssue != null || viewModel.classificationResult.value == null
    }

    private fun showCameraIssue(message: String) {
        cameraIssue = message
        renderScannerStatus()
    }

    private fun requestCameraPermission() {
        val prefs = requireContext().getSharedPreferences("permissions", 0)
        if (prefs.getBoolean("cameraRequested", false) && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Permiso de cámara")
                .setMessage("Activa Cámara en los permisos de LabDetect para reconocer equipos.")
                .setPositiveButton("Abrir ajustes") { _, _ ->
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + requireContext().packageName)))
                }.setNegativeButton("Ahora no", null).show()
        } else {
            prefs.edit().putBoolean("cameraRequested", true).apply()
            requestPermissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
        }
    }

    private fun startCamera() {
        val current = _binding ?: return
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || !cameraPermissionGranted() ||
            cameraStarting || activeCamera != null) return
        cameraStarting = true
        val session = ++cameraGeneration
        current.viewFinder.doOnLayout {
            if (_binding !== current || session != cameraGeneration) return@doOnLayout
            showCameraIssue("Iniciando cámara…")
            val future = ProcessCameraProvider.getInstance(requireContext())
            future.addListener({
                if (_binding !== current || session != cameraGeneration ||
                    !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@addListener
                try {
                    val provider = future.get()
                    cameraProvider = provider
                    check(provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) { "No rear camera" }
                    val rotation = current.viewFinder.display?.rotation ?: android.view.Surface.ROTATION_0
                    val previewBuilder = Preview.Builder().setTargetRotation(rotation)
                    val analysisBuilder = ImageAnalysis.Builder().setTargetRotation(rotation)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    // CameraX selects a supported resolution pair on a recovery attempt.
                    if (cameraRetries == 0) {
                        previewBuilder.setTargetResolution(Size(1920, 1080))
                        analysisBuilder.setTargetResolution(Size(1280, 720))
                    }
                    val preview = previewBuilder.build()
                    val analysis = analysisBuilder.build()
                    previewUseCase = preview
                    analysisUseCase = analysis
                    preview.setSurfaceProvider(current.viewFinder.surfaceProvider)
                    analysis.setAnalyzer(cameraAnalysisExecutor) { proxy ->
                        if (session == cameraGeneration) analyzeCameraFrame(proxy, session) else proxy.close()
                    }
                    val group = UseCaseGroup.Builder().addUseCase(preview).addUseCase(analysis)
                    current.viewFinder.viewPort?.let { group.setViewPort(it) }
                    activeCamera = provider.bindToLifecycle(viewLifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, group.build())
                    cameraStarting = false
                    viewModel.resumeDetection()
                    activeCamera?.cameraInfo?.cameraState?.observe(viewLifecycleOwner) { state ->
                        if (session == cameraGeneration && state.error != null) {
                            Log.w("LabCamera", "CameraX error=" + state.error?.code)
                            showCameraIssue("Cámara no disponible · revisa acceso o toca para reintentar")
                            mainHandler.postDelayed({
                                if (session == cameraGeneration && _binding != null) recoverCamera()
                            }, 500L)
                        }
                    }
                    cameraWatchdog = Runnable {
                        if (_binding === current && session == cameraGeneration &&
                            lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                            current.viewFinder.previewStreamState.value != PreviewView.StreamState.STREAMING) {
                            recoverCamera()
                        }
                    }.also { mainHandler.postDelayed(it, 5_000L) }
                } catch (error: Exception) {
                    Log.e("LabCamera", "CameraX bind failed", error)
                    recoverCamera()
                }
            }, ContextCompat.getMainExecutor(requireContext()))
        }
    }

    private fun recoverCamera() {
        releaseCamera()
        if (_binding == null || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        if (cameraRetries++ < 2 && cameraPermissionGranted()) {
            val session = cameraGeneration
            mainHandler.postDelayed({
                if (session == cameraGeneration && _binding != null) startCamera()
            }, 500L)
        } else showCameraIssue("No se pudo abrir la cámara · toca para reintentar")
    }

    private fun releaseCamera() {
        cameraGeneration++
        cameraStarting = false
        cameraWatchdog?.let(mainHandler::removeCallbacks)
        cameraWatchdog = null
        activeCamera?.cameraInfo?.cameraState?.removeObservers(viewLifecycleOwner)
        analysisUseCase?.clearAnalyzer()
        val owned = listOfNotNull(previewUseCase, analysisUseCase)
        if (owned.isNotEmpty()) runCatching { cameraProvider?.unbind(*owned.toTypedArray()) }
        activeCamera = null
        previewUseCase = null
        analysisUseCase = null
        viewModel.pauseDetection()
    }

    private fun focusAndMeterAt(x: Float, y: Float) {
        val camera = activeCamera ?: return
        val current = _binding ?: return
        val point = current.viewFinder.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(2, TimeUnit.SECONDS).build()
        if (camera.cameraInfo.isFocusMeteringSupported(action)) runCatching {
            camera.cameraControl.startFocusAndMetering(action)
        }
    }

    /** Analiza el fotograma original que entrega CameraX, no una captura de la vista previa. */
    private fun analyzeCameraFrame(imageProxy: ImageProxy, session: Int) {
        try {
            val now = SystemClock.elapsedRealtime()
            if (!viewModel.canAcceptFrame() || now - lastAnalysisAt < ANALYSIS_INTERVAL_MS) return
            lastAnalysisAt = now
            val bitmap = imageProxy.toUprightBitmap()
            if (bitmap == null) {
                viewModel.reportFrameReadFailure()
                return
            }
            bitmap.let {
                val frameWidth = it.width
                val frameHeight = it.height
                val backdrop = if (now - lastBackdropAt >= 800L) {
                    lastBackdropAt = now
                    Bitmap.createScaledBitmap(it, 120, (120f * frameHeight / frameWidth).toInt().coerceAtLeast(1), true)
                } else null
                mainHandler.post {
                    if (session != cameraGeneration) return@post
                    _binding?.detectionOverlay?.setSourceFrameSize(frameWidth, frameHeight)
                    if (backdrop != null && _binding != null) {
                        latestBackdrop = backdrop
                        binding.answerCard.setBackdrop(backdrop)
                        binding.conversationCard.setBackdrop(backdrop)
                    }
                }
                if (session == cameraGeneration) viewModel.onImageCaptured(it) else it.recycle()
            }
        } finally {
            imageProxy.close()
        }
    }

    private fun ImageProxy.toUprightBitmap(): Bitmap? = runCatching {
        val full = toBitmap()
        val crop = cropRect
        val source = Bitmap.createBitmap(full, crop.left, crop.top, crop.width(), crop.height())
        if (source !== full) full.recycle()
        val degrees = imageInfo.rotationDegrees
        if (degrees == 0) {
            source
        } else {
            Bitmap.createBitmap(
                source,
                0,
                0,
                source.width,
                source.height,
                Matrix().apply { postRotate(degrees.toFloat()) },
                true
            ).also { source.recycle() }
        }
    }.onFailure { error ->
        Log.e("CameraFragment", "CameraX no pudo convertir un fotograma para YOLO", error)
    }.getOrNull()

    private fun cameraPermissionGranted() = ContextCompat.checkSelfPermission(
        requireContext(),
        Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    private fun audioPermissionGranted() = ContextCompat.checkSelfPermission(
        requireContext(),
        Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    override fun onResume() {
        super.onResume()
        cameraRetries = 0
        if (cameraPermissionGranted()) startCamera()
        else showCameraIssue("Permiso de cámara pendiente · toca para habilitar")
    }

    override fun onPause() {
        if (cameraStarting) releaseCamera()
        destroyRecognizer()
        voiceState = VoiceState.IDLE
        viewModel.cancelAssistant()
        speechEngine.stop()
        if (_binding != null) finishInteraction(cancelled = true)
        super.onPause()
    }

    override fun onStop() {
        releaseCamera()
        super.onStop()
    }

    override fun onDestroyView() {
        releaseCamera()
        mainHandler.removeCallbacksAndMessages(null)
        destroyRecognizer()
        speechEngine.close()
        latestBackdrop = null
        cardTarget = null
        activeQuestionFrame = null
        keyboardVisible = false
        _binding = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        cameraAnalysisExecutor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val RECOGNITION_RESULT_TIMEOUT_MS = 1_500L
        private const val ANALYSIS_INTERVAL_MS = 60L
    }
}
