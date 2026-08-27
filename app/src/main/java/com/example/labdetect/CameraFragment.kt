package com.example.labdetect

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
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
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraFragment : Fragment(), TextToSpeech.OnInitListener {

    private var _binding: FragmentCameraBinding? = null
    private val binding get() = _binding!!

    private val classificationHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val classificationInterval = 1000L // cada 1 segundo

    private val viewModel: CameraViewModel by viewModels()
    private lateinit var cameraExecutor: ExecutorService

    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var textToSpeech: TextToSpeech

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            startCamera()
        } else {
            Toast.makeText(context, "Permisos denegados", Toast.LENGTH_SHORT).show()
        }
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
        cameraExecutor = Executors.newSingleThreadExecutor()

        textToSpeech = TextToSpeech(requireContext(), this)
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(requireContext())

        if (allPermissionsGranted()) {
            startCamera()
            startPeriodicClassification()
        } else {
            requestPermissionLauncher.launch(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            )
        }

        binding.btnDetails.setOnClickListener {
            val name = binding.tvEquipmentName.text.toString()
            val bundle = Bundle().apply {
                putString("equipmentName", name)
            }
            findNavController().navigate(R.id.action_cameraFragment_to_detailFragment, bundle)
        }

        binding.fabMic.setOnClickListener {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
            }

            speechRecognizer.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    if (!text.isNullOrBlank()) {
                        viewModel.askAssistant(text)
                    }
                }
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    Toast.makeText(context, "No entendí, intenta de nuevo", Toast.LENGTH_SHORT).show()
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            speechRecognizer.startListening(intent)
        }

        viewModel.classificationResult.observe(viewLifecycleOwner) { result ->
            if (result != null) {
                binding.resultCard.visibility = View.VISIBLE
                binding.tvEquipmentName.text = result.label
                binding.tvConfidence.text = "Confianza: ${"%.1f".format(result.confidence)}%"
            }
        }

        viewModel.assistantAnswer.observe(viewLifecycleOwner) { answer ->
            textToSpeech.speak(answer, TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            textToSpeech.language = Locale("es", "ES")
        }
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

    private fun allPermissionsGranted() = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    ).all {
        ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cameraExecutor.shutdown()
        classificationHandler.removeCallbacksAndMessages(null)
        speechRecognizer.destroy()
        textToSpeech.stop()
        textToSpeech.shutdown()
        _binding = null
    }
}