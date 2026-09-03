package com.example.labdetect

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.example.labdetect.databinding.FragmentCameraBinding
import com.example.labdetect.viewmodel.CameraViewModel
import com.example.labdetect.speech.AndroidSpeechEngine
import java.util.Locale

class CameraFragment : Fragment() {

    private var _binding: FragmentCameraBinding? = null
    private val binding get() = _binding!!

    private val classificationHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val classificationInterval = 1000L // cada 1 segundo

    private val viewModel: CameraViewModel by viewModels()

    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var speechEngine: AndroidSpeechEngine
    private var isListening = false
    private var partialTranscript = ""

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
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCameraBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        speechEngine = AndroidSpeechEngine(requireContext())
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(requireContext())
        configureSpeechRecognizer()

        if (cameraPermissionGranted()) {
            startCamera()
            startPeriodicClassification()
        }
        binding.fabMic.isEnabled = audioPermissionGranted()
        val missingPermissions = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        ).filter {
            ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isNotEmpty()) {
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        }

        binding.btnDetails.setOnClickListener {
            val name = binding.tvEquipmentName.text.toString()
            val bundle = Bundle().apply {
                putString("equipmentName", name)
                putString("equipmentId", viewModel.classificationResult.value?.canonicalId)
            }
            findNavController().navigate(R.id.action_cameraFragment_to_detailFragment, bundle)
        }

        binding.fabMic.setOnClickListener { startVoiceQuestion() }

        viewModel.classificationResult.observe(viewLifecycleOwner) { result ->
            if (result != null) {
                binding.resultCard.visibility = View.VISIBLE
                binding.tvEquipmentName.text = result.label
                binding.tvConfidence.text = "Confianza: ${"%.1f".format(result.confidence)}%"
            } else {
                binding.resultCard.visibility = View.GONE
            }
        }

        viewModel.detections.observe(viewLifecycleOwner) { detections ->
            binding.detectionOverlay.submitDetections(detections)
        }

        viewModel.modelReady.observe(viewLifecycleOwner) { ready ->
            if (!ready) {
                Toast.makeText(
                    context,
                    "Modelo de detección pendiente de instalar",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        viewModel.assistantAnswer.observe(viewLifecycleOwner) { answer ->
            speechEngine.speak(answer)
        }
    }

    private fun configureSpeechRecognizer() {
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                isListening = true
                binding.fabMic.alpha = 0.55f
                Toast.makeText(context, "Te escucho…", Toast.LENGTH_SHORT).show()
            }

            override fun onResults(results: Bundle?) {
                finishListening()
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty().ifBlank { partialTranscript }
                if (text.isNotBlank()) viewModel.askAssistant(text)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                partialTranscript = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
            }

            override fun onError(error: Int) {
                val usablePartial = partialTranscript.takeIf { it.length >= 3 }
                finishListening()
                if (usablePartial != null) {
                    viewModel.askAssistant(usablePartial)
                } else {
                    val message = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                            "No alcancé a escucharte. Toca el micrófono y habla de nuevo."
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                            "La escucha necesita conexión. Intenta otra vez."
                        else -> "No pude escuchar bien. Intenta otra vez."
                    }
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            }

            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
    }

    private fun startVoiceQuestion() {
        if (!audioPermissionGranted() || isListening) return
        if (!SpeechRecognizer.isRecognitionAvailable(requireContext())) {
            Toast.makeText(context, "El reconocimiento de voz no está disponible.", Toast.LENGTH_SHORT).show()
            return
        }
        partialTranscript = ""
        speechEngine.stop()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale("es", "EC").toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, Locale("es", "EC").toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1_200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 700L)
        }
        runCatching { speechRecognizer.startListening(intent) }
            .onFailure {
                finishListening()
                Toast.makeText(context, "No pude iniciar el micrófono.", Toast.LENGTH_SHORT).show()
            }
    }

    private fun finishListening() {
        isListening = false
        if (_binding != null) binding.fabMic.alpha = 1f
    }

    private fun startPeriodicClassification() {
        classificationHandler.postDelayed(object : Runnable {
            override fun run() {
                binding.viewFinder.bitmap?.let { bitmap ->
                    viewModel.onImageCaptured(bitmap)
                }
                classificationHandler.postDelayed(this, classificationInterval)
            }
        }, classificationInterval)
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview
                )
                Toast.makeText(context, "Cámara iniciada", Toast.LENGTH_SHORT).show()
            } catch (exc: Exception) {
                Log.e("CameraFragment", "Use case binding failed", exc)
                Toast.makeText(context, "Error al iniciar cámara: ${exc.message}", Toast.LENGTH_SHORT).show()
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
        super.onDestroyView()
        classificationHandler.removeCallbacksAndMessages(null)
        speechRecognizer.destroy()
        speechEngine.close()
        _binding = null
    }
}
