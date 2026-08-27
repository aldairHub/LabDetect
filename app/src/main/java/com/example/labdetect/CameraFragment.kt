package com.example.labdetect

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraFragment : Fragment() {

    private var _binding: FragmentCameraBinding? = null
    private val binding get() = _binding!!
    
    private val viewModel: CameraViewModel by viewModels()
    private lateinit var cameraExecutor: ExecutorService

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

        if (allPermissionsGranted()) {
            startCamera()
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
        
        // Simulación de detección al hacer clic en el preview (para probar sin cámara real)
        binding.viewFinder.setOnClickListener {
            viewModel.onImageCaptured(android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888))
        }

        viewModel.classificationResult.observe(viewLifecycleOwner) { result ->
            if (result != null) {
                binding.resultCard.visibility = View.VISIBLE
                binding.tvEquipmentName.text = result.label
                binding.tvConfidence.text = "Confianza: ${"%.1f".format(result.confidence)}%"
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())

        cameraProviderFuture.addListener({
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
                }

            // Select back camera as a default
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
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
        _binding = null
    }
}