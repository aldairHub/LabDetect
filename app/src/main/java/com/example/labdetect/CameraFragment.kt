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
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
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
import com.example.labdetect.databinding.FragmentCameraBinding
import com.example.labdetect.speech.AndroidSpeechEngine
import com.example.labdetect.viewmodel.CameraViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraFragment : Fragment() {
    private var _binding: FragmentCameraBinding? = null
    private val binding get() = _binding!!

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val cameraAnalysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var lastAnalysisAt = 0L
    private val viewModel: CameraViewModel by viewModels()

    private lateinit var speechRecognizer: SpeechRecognizer
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
    private val feedbackShownFor = mutableSetOf<String>()

    private enum class VoiceState { IDLE, LISTENING, READY_TO_SEND, AWAITING_RESULT, PROCESSING, SPEAKING }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.CAMERA] == true) {
            startCamera()
        } else if (!cameraPermissionGranted()) {
            Toast.makeText(context, "Se necesita la cámara para detectar equipos", Toast.LENGTH_SHORT).show()
        }
        binding.fabMic.isEnabled = true
        if (startListeningAfterPermission) {
            startListeningAfterPermission = false
            if (audioPermissionGranted()) beginVoiceCapture()
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
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(requireContext())
        favoriteStore = FavoriteEquipmentStore(requireContext())
        interactionStore = EquipmentInteractionStore(requireContext())
        equipmentCatalog = LocalEquipmentCatalog(requireContext())
        defaultMicTint = binding.fabMic.backgroundTintList
        configureSpeechRecognizer()

        if (cameraPermissionGranted()) {
            startCamera()
        }
        binding.fabMic.isEnabled = true
        requestMissingPermissions()

        binding.btnDetails.setOnClickListener { openCurrentEquipmentDetails() }
        binding.btnQuickFavorite.setOnClickListener { toggleCurrentFavorite() }
        binding.btnFavoritesList.setOnClickListener { showFavorites() }
        binding.btnFeedbackYes.setOnClickListener { saveDetectionFeedback(null) }
        binding.btnFeedbackCorrect.setOnClickListener { showCorrectionPicker() }
        binding.fabMic.setOnClickListener { handleMicClick() }
        binding.btnSendQuestion.setOnClickListener { submitTypedQuestion() }
        binding.tietCameraQuestion.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submitTypedQuestion()
                true
            } else false
        }

        viewModel.classificationResult.observe(viewLifecycleOwner) { result ->
            if (result == null) {
                binding.resultCard.isVisible = false
            } else {
                val animateEntrance = !binding.resultCard.isVisible
                binding.resultCard.isVisible = true
                binding.tvEquipmentName.text = result.label
                binding.tvConfidence.text = "${"%.1f".format(result.confidence)}% de confianza"
                updateQuickFavorite(result.canonicalId)
                if (lastRememberedEquipmentId != result.canonicalId) {
                    interactionStore.rememberSeen(result.canonicalId)
                    lastRememberedEquipmentId = result.canonicalId
                }
                if (animateEntrance) {
                    binding.resultCard.alpha = 0f
                    binding.resultCard.translationY = -8f * resources.displayMetrics.density
                    binding.resultCard.animate().alpha(1f).translationY(0f).setDuration(220L).start()
                }
            }
        }

        viewModel.detections.observe(viewLifecycleOwner) { detections ->
            binding.detectionOverlay.submitDetections(detections)
        }

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
                showVoiceState("Preparando la respuesta…")
            }
        }

        viewModel.assistantAnswer.observe(viewLifecycleOwner) { event ->
            val answer = event.consume() ?: return@observe
            binding.tvCameraAnswer.text = answer
            binding.tvCameraAnswer.isVisible = true
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
            showVoiceState("Respondiendo sobre ${binding.tvEquipmentName.text}…")
            speechEngine.speak(answer) {
                if (_binding != null) {
                    finishInteraction()
                    showDetectionFeedbackIfNeeded()
                }
            }
        }
    }

    private fun requestMissingPermissions() {
        val missing = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).filter {
            ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) requestPermissionLauncher.launch(missing.toTypedArray())
    }

    private fun configureSpeechRecognizer() {
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (voiceState != VoiceState.LISTENING) return
                isListening = true
                showListeningFeedback("Escuchando · toca otra vez para enviar")
            }

            override fun onResults(results: Bundle?) {
                if (voiceState !in setOf(VoiceState.LISTENING, VoiceState.AWAITING_RESULT)) return
                isListening = false
                val text = bestTranscript(results).ifBlank { partialTranscript }
                handleRecognizedText(text)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (voiceState != VoiceState.LISTENING) return
                partialTranscript = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                if (partialTranscript.isNotBlank()) {
                    val preview = partialTranscript.take(64)
                    showVoiceState("🎙 $preview")
                }
            }

            override fun onError(error: Int) {
                if (voiceState !in setOf(VoiceState.LISTENING, VoiceState.AWAITING_RESULT)) return
                val usablePartial = partialTranscript.takeIf { it.length >= 3 }
                if (usablePartial != null) {
                    isListening = false
                    handleRecognizedText(usablePartial)
                    return
                }
                finishInteraction(cancelled = true)
                val message = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        "No alcancé a escucharte. Toca el micrófono e inténtalo de nuevo."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                        "No hay reconocimiento de voz disponible. Puedes escribir la pregunta."
                    else -> "No pude escuchar bien. También puedes escribir la pregunta."
                }
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }

            override fun onRmsChanged(rmsdB: Float) {
                if (voiceState != VoiceState.LISTENING || _binding == null) return
                val pulse = (1.05f + (rmsdB.coerceIn(0f, 12f) / 100f))
                binding.fabMic.scaleX = pulse
                binding.fabMic.scaleY = pulse
            }

            override fun onBeginningOfSpeech() = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
    }

    private fun handleMicClick() {
        when (voiceState) {
            VoiceState.IDLE -> {
                if (!audioPermissionGranted()) {
                    startListeningAfterPermission = true
                    requestPermissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                    return
                }
                if (!SpeechRecognizer.isRecognitionAvailable(requireContext())) {
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
        binding.tvCameraAnswer.isVisible = false
        binding.btnSendQuestion.isEnabled = false
        partialTranscript = ""
        pendingTranscript = ""
        submitWhenReady = false
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
        runCatching { speechRecognizer.stopListening() }
        mainHandler.postDelayed({
            if (_binding != null && voiceState == VoiceState.AWAITING_RESULT) {
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
        }
    }

    private fun startVoiceQuestion() {
        isListening = true
        showListeningFeedback("Escuchando · toca otra vez para enviar")
        val locale = Locale("es", "EC").toLanguageTag()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, locale)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, requireContext().packageName)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 10_000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 10_000L)
            putStringArrayListExtra("android.speech.extra.BIASING_STRINGS", ArrayList(speechVocabulary()))
        }
        runCatching { speechRecognizer.startListening(intent) }
            .onFailure {
                finishInteraction(cancelled = true)
                Toast.makeText(context, "No pude iniciar el micrófono; escribe tu pregunta.", Toast.LENGTH_SHORT).show()
            }
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
        isListening = false
        submitWhenReady = false
        pendingTranscript = ""
        partialTranscript = ""
        lastSubmittedQuestion = text.trim()
        voiceState = VoiceState.PROCESSING
        resetMicVisual()
        showVoiceState("Preparando la respuesta…")
        viewModel.askAssistant(text.trim())
    }

    private fun showListeningFeedback(message: String) {
        showVoiceState(message)
        binding.fabMic.setImageResource(R.drawable.ic_mic)
        binding.fabMic.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#35D05B"))
        binding.fabMic.scaleX = 1.1f
        binding.fabMic.scaleY = 1.1f
        binding.tvMicHint.text = "Habla con normalidad · toca otra vez al terminar"
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
        isListening = false
        submitWhenReady = false
        pendingTranscript = ""
        partialTranscript = ""
        voiceState = VoiceState.IDLE
        resetMicVisual()
        binding.fabMic.isEnabled = true
        binding.btnSendQuestion.isEnabled = true
        binding.tvMicHint.text = "Toca para hablar · toca de nuevo para enviar"
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
        showVoiceState("Preparando la respuesta…")
        viewModel.askAssistant(question)
    }

    private fun openCurrentEquipmentDetails() {
        val result = viewModel.classificationResult.value ?: return
        findNavController().navigate(
            R.id.action_cameraFragment_to_detailFragment,
            Bundle().apply {
                putString("equipmentName", result.label)
                putString("equipmentId", result.canonicalId)
            }
        )
    }

    private fun toggleCurrentFavorite() {
        val id = viewModel.classificationResult.value?.canonicalId ?: return
        favoriteStore.toggle(id)
        updateQuickFavorite(id)
    }

    private fun updateQuickFavorite(id: String) {
        val isFavorite = favoriteStore.contains(id)
        binding.btnQuickFavorite.setIconResource(
            if (isFavorite) R.drawable.ic_star_filled else R.drawable.ic_star_outline
        )
    }

    private fun showFavorites() {
        val favorites = favoriteStore.all().mapNotNull(equipmentCatalog::find).sortedBy { it.displayName }
        val recent = interactionStore.recentEquipmentIds().mapNotNull(equipmentCatalog::find)
        val profiles = (favorites + recent).distinctBy { it.id }
        if (profiles.isEmpty()) {
            Toast.makeText(context, "Aún no tienes equipos recientes ni favoritos.", Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Favoritos y recientes")
            .setItems(profiles.map { profile ->
                val prefix = if (favoriteStore.contains(profile.id)) "★ " else "◷ "
                prefix + profile.displayName
            }.toTypedArray()) { _, index ->
                val profile = profiles[index]
                findNavController().navigate(
                    R.id.action_cameraFragment_to_detailFragment,
                    Bundle().apply {
                        putString("equipmentName", profile.displayName)
                        putString("equipmentId", profile.id)
                    }
                )
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun setActiveQuestionEquipment() {
        val result = viewModel.classificationResult.value ?: return
        activeQuestionEquipmentId = result.canonicalId
        activeQuestionEquipmentName = result.label
        activeQuestionFrame = binding.viewFinder.bitmap?.copy(Bitmap.Config.ARGB_8888, false)
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

    private fun startCamera() {
        val currentBinding = _binding ?: return
        if (!currentBinding.viewFinder.isLaidOut) {
            currentBinding.viewFinder.doOnLayout {
                if (_binding === currentBinding) startCamera()
            }
            return
        }
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            if (_binding !== currentBinding) return@addListener
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                // YOLO recibe 640 px; pedir a CameraX una salida cercana evita convertir
                // fotos de varios megapíxeles para luego reducirlas al mismo tamaño.
                .setTargetResolution(Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetRotation(binding.viewFinder.display.rotation)
                .build()
                .also { useCase ->
                    useCase.setAnalyzer(cameraAnalysisExecutor) { imageProxy ->
                        analyzeCameraFrame(imageProxy)
                    }
                }
            try {
                cameraProvider.unbindAll()
                val group = UseCaseGroup.Builder().addUseCase(preview).addUseCase(analysis)
                binding.viewFinder.viewPort?.let { group.setViewPort(it) }
                cameraProvider.bindToLifecycle(viewLifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, group.build())
            } catch (exception: Exception) {
                Log.e("CameraFragment", "No se pudo iniciar CameraX", exception)
                Toast.makeText(context, "No pude iniciar la cámara.", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    /** Analiza el fotograma original que entrega CameraX, no una captura de la vista previa. */
    private fun analyzeCameraFrame(imageProxy: ImageProxy) {
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
                mainHandler.post {
                    _binding?.detectionOverlay?.setSourceFrameSize(frameWidth, frameHeight)
                }
                viewModel.onImageCaptured(it)
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

    override fun onDestroyView() {
        mainHandler.removeCallbacksAndMessages(null)
        runCatching { speechRecognizer.cancel() }
        speechRecognizer.destroy()
        speechEngine.close()
        viewModel.endQuestionSession()
        _binding = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        cameraAnalysisExecutor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val RECOGNITION_RESULT_TIMEOUT_MS = 4_000L
        private const val ANALYSIS_INTERVAL_MS = 250L
    }
}
