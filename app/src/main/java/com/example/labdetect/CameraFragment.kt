package com.example.labdetect

import android.Manifest
import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.LayoutInflater
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.example.labdetect.data.FavoriteEquipmentStore
import com.example.labdetect.data.LocalEquipmentCatalog
import com.example.labdetect.databinding.FragmentCameraBinding
import com.example.labdetect.speech.AndroidSpeechEngine
import com.example.labdetect.viewmodel.CameraViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale

class CameraFragment : Fragment() {
    private var _binding: FragmentCameraBinding? = null
    private val binding get() = _binding!!

    private val classificationHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val classificationInterval = 1_000L
    private val viewModel: CameraViewModel by viewModels()

    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var speechEngine: AndroidSpeechEngine
    private lateinit var favoriteStore: FavoriteEquipmentStore
    private lateinit var equipmentCatalog: LocalEquipmentCatalog
    private var defaultMicTint: ColorStateList? = null
    private var isListening = false
    private var recordingLocked = false
    private var partialTranscript = ""
    private var touchStartY = 0f

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.CAMERA] == true) {
            startCamera()
            startPeriodicClassification()
        } else if (!cameraPermissionGranted()) {
            Toast.makeText(context, "Se necesita la cámara para detectar equipos", Toast.LENGTH_SHORT).show()
        }
        binding.fabMic.isEnabled = audioPermissionGranted()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCameraBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        speechEngine = AndroidSpeechEngine(requireContext())
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(requireContext())
        favoriteStore = FavoriteEquipmentStore(requireContext())
        equipmentCatalog = LocalEquipmentCatalog(requireContext())
        defaultMicTint = binding.fabMic.backgroundTintList
        configureSpeechRecognizer()

        if (cameraPermissionGranted()) {
            startCamera()
            startPeriodicClassification()
        }
        binding.fabMic.isEnabled = audioPermissionGranted()
        requestMissingPermissions()

        binding.btnDetails.setOnClickListener { openCurrentEquipmentDetails() }
        binding.btnQuickFavorite.setOnClickListener { toggleCurrentFavorite() }
        binding.btnFavoritesList.setOnClickListener { showFavorites() }
        binding.fabMic.setOnTouchListener { _, event -> handleMicTouch(event) }
        binding.btnSendQuestion.setOnClickListener { submitTypedQuestion() }
        binding.tietCameraQuestion.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submitTypedQuestion()
                true
            } else false
        }

        viewModel.classificationResult.observe(viewLifecycleOwner) { result ->
            binding.resultCard.isVisible = result != null
            if (result != null) {
                binding.tvEquipmentName.text = result.label
                binding.tvConfidence.text = "Confianza: ${"%.1f".format(result.confidence)}%"
                updateQuickFavorite(result.canonicalId)
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
            binding.btnSendQuestion.isEnabled = !loading
            if (loading && !isListening) showVoiceState("Preparando la respuesta…")
        }

        viewModel.assistantAnswer.observe(viewLifecycleOwner) { event ->
            val answer = event.consume() ?: return@observe
            binding.tvCameraAnswer.text = answer
            binding.tvCameraAnswer.isVisible = true
            showVoiceState("Respondiendo sobre ${binding.tvEquipmentName.text}…")
            speechEngine.speak(answer) {
                viewModel.endQuestionSession()
                if (_binding != null) hideVoiceState()
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
                isListening = true
                showListeningFeedback(if (recordingLocked) "Micrófono bloqueado · toca para terminar" else "Escuchando… desliza arriba para bloquear")
            }

            override fun onResults(results: Bundle?) {
                val text = bestTranscript(results).ifBlank { partialTranscript }
                finishListening(processing = text.isNotBlank())
                if (text.isNotBlank()) {
                    viewModel.askAssistant(text)
                } else {
                    viewModel.cancelQuestionSession()
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                partialTranscript = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                if (partialTranscript.isNotBlank()) {
                    val preview = partialTranscript.take(64)
                    showVoiceState(if (recordingLocked) "🔒 $preview" else "🎙 $preview")
                }
            }

            override fun onError(error: Int) {
                val usablePartial = partialTranscript.takeIf { it.length >= 3 }
                finishListening(processing = usablePartial != null)
                if (usablePartial != null) {
                    viewModel.askAssistant(usablePartial)
                    return
                }
                viewModel.cancelQuestionSession()
                hideVoiceState()
                val message = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        "No alcancé a escucharte. Mantén pulsado y habla de nuevo."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                        "No hay reconocimiento de voz disponible. Puedes escribir la pregunta."
                    else -> "No pude escuchar bien. También puedes escribir la pregunta."
                }
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }

            override fun onRmsChanged(rmsdB: Float) {
                if (!isListening || _binding == null) return
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

    private fun handleMicTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (recordingLocked && isListening) {
                    stopVoiceQuestion()
                    return true
                }
                if (!audioPermissionGranted()) {
                    requestPermissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                    return true
                }
                if (!SpeechRecognizer.isRecognitionAvailable(requireContext())) {
                    Toast.makeText(context, "La voz no está disponible; escribe tu pregunta.", Toast.LENGTH_SHORT).show()
                    return true
                }
                speechEngine.stop()
                viewModel.endQuestionSession()
                if (!viewModel.beginQuestionSession()) {
                    Toast.makeText(context, "Primero enfoca un equipo.", Toast.LENGTH_SHORT).show()
                    return true
                }
                touchStartY = event.rawY
                recordingLocked = false
                binding.fabMic.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                binding.fabMic.parent?.requestDisallowInterceptTouchEvent(true)
                startVoiceQuestion()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (isListening && !recordingLocked && touchStartY - event.rawY >= lockDistancePx()) {
                    recordingLocked = true
                    showListeningFeedback("🔒 Micrófono bloqueado · toca para terminar")
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                binding.fabMic.parent?.requestDisallowInterceptTouchEvent(false)
                binding.fabMic.performClick()
                if (isListening && !recordingLocked) stopVoiceQuestion()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                binding.fabMic.parent?.requestDisallowInterceptTouchEvent(false)
                if (isListening && !recordingLocked) stopVoiceQuestion()
                return true
            }
        }
        return false
    }

    private fun startVoiceQuestion() {
        partialTranscript = ""
        isListening = true
        showListeningFeedback("Escuchando… desliza arriba para bloquear")
        val locale = Locale("es", "EC").toLanguageTag()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, locale)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, requireContext().packageName)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 8_000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 8_000L)
            putStringArrayListExtra(
                "android.speech.extra.BIASING_STRINGS",
                ArrayList(speechVocabulary())
            )
        }
        runCatching { speechRecognizer.startListening(intent) }
            .onFailure {
                finishListening(processing = false)
                viewModel.cancelQuestionSession()
                Toast.makeText(context, "No pude iniciar el micrófono; escribe tu pregunta.", Toast.LENGTH_SHORT).show()
            }
    }

    private fun bestTranscript(results: Bundle?): String {
        val alternatives = results
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.filter { it.isNotBlank() }
            .orEmpty()
        if (alternatives.isEmpty()) return ""
        val confidence = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
        if (confidence == null || confidence.none { it >= 0f }) return alternatives.first()
        return alternatives.indices.maxByOrNull { index ->
            confidence.getOrNull(index)?.takeIf { it >= 0f } ?: 0f
        }?.let(alternatives::get).orEmpty()
    }

    private fun speechVocabulary(): List<String> = buildList {
        viewModel.classificationResult.value?.label?.let(::add)
        addAll(equipmentCatalog.equipmentNames())
        addAll(
            listOf(
                "bromatología", "laboratorio", "manual", "funcionamiento", "encender",
                "apagar", "temperatura", "seguridad", "limpieza", "mantenimiento",
                "calibración", "muestra", "esterilización", "centrifugación"
            )
        )
    }.distinct()

    private fun stopVoiceQuestion() {
        recordingLocked = false
        showVoiceState("Procesando lo que dijiste…")
        runCatching { speechRecognizer.stopListening() }
    }

    private fun finishListening(processing: Boolean) {
        isListening = false
        recordingLocked = false
        binding.fabMic.scaleX = 1f
        binding.fabMic.scaleY = 1f
        binding.fabMic.backgroundTintList = defaultMicTint
        binding.tvMicHint.text = "Mantén pulsado para hablar · desliza arriba para bloquear"
        if (processing) showVoiceState("Preparando la respuesta…") else hideVoiceState()
    }

    private fun showListeningFeedback(message: String) {
        showVoiceState(message)
        binding.fabMic.backgroundTintList = ColorStateList.valueOf(
            Color.parseColor(if (recordingLocked) "#FB8C00" else "#D32F2F")
        )
        binding.fabMic.scaleX = 1.1f
        binding.fabMic.scaleY = 1.1f
        binding.tvMicHint.text = if (recordingLocked) {
            "Puedes soltar · toca el micrófono para terminar"
        } else {
            "Suelta para enviar · desliza arriba para bloquear"
        }
    }

    private fun showVoiceState(message: String) {
        if (_binding == null) return
        binding.tvVoiceState.text = message
        binding.tvVoiceState.isVisible = true
    }

    private fun hideVoiceState() {
        if (_binding == null) return
        binding.tvVoiceState.isVisible = false
    }

    private fun submitTypedQuestion() {
        val question = binding.tietCameraQuestion.text?.toString()?.trim().orEmpty()
        if (question.isBlank()) return
        speechEngine.stop()
        viewModel.endQuestionSession()
        if (!viewModel.beginQuestionSession()) {
            Toast.makeText(context, "Primero enfoca un equipo.", Toast.LENGTH_SHORT).show()
            return
        }
        binding.tietCameraQuestion.text?.clear()
        binding.tietCameraQuestion.clearFocus()
        requireContext().getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(binding.tietCameraQuestion.windowToken, 0)
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
        binding.btnQuickFavorite.text = ""
        binding.btnQuickFavorite.contentDescription =
            if (isFavorite) "Quitar de favoritos" else "Guardar en favoritos"
        binding.btnQuickFavorite.setIconResource(
            if (isFavorite) R.drawable.ic_star_filled else R.drawable.ic_star_outline
        )
    }

    private fun showFavorites() {
        val profiles = favoriteStore.all().mapNotNull(equipmentCatalog::find).sortedBy { it.displayName }
        if (profiles.isEmpty()) {
            Toast.makeText(context, "Todavía no tienes equipos favoritos.", Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Equipos favoritos")
            .setItems(profiles.map { it.displayName }.toTypedArray()) { _, index ->
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

    private fun lockDistancePx(): Float = 72f * resources.displayMetrics.density

    private fun startPeriodicClassification() {
        classificationHandler.postDelayed(object : Runnable {
            override fun run() {
                if (_binding == null) return
                binding.viewFinder.bitmap?.let(viewModel::onImageCaptured)
                classificationHandler.postDelayed(this, classificationInterval)
            }
        }, classificationInterval)
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            }
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview)
            } catch (exception: Exception) {
                Log.e("CameraFragment", "No se pudo iniciar CameraX", exception)
                Toast.makeText(context, "No pude iniciar la cámara.", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun cameraPermissionGranted() = ContextCompat.checkSelfPermission(
        requireContext(),
        Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    private fun audioPermissionGranted() = ContextCompat.checkSelfPermission(
        requireContext(),
        Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    override fun onDestroyView() {
        classificationHandler.removeCallbacksAndMessages(null)
        runCatching { speechRecognizer.cancel() }
        speechRecognizer.destroy()
        speechEngine.close()
        viewModel.endQuestionSession()
        _binding = null
        super.onDestroyView()
    }
}
