package com.example.labdetect

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.example.labdetect.databinding.FragmentDetailBinding
import com.example.labdetect.viewmodel.DetailViewModel
import com.example.labdetect.speech.AndroidSpeechEngine
import com.example.labdetect.data.LocalEquipmentCatalog
import com.example.labdetect.domain.EquipmentVariant

class DetailFragment : Fragment() {

    private var _binding: FragmentDetailBinding? = null
    private val binding get() = _binding!!

    private val viewModel: DetailViewModel by viewModels()
    private lateinit var speechEngine: AndroidSpeechEngine
    private var selectedVariant: EquipmentVariant? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        speechEngine = AndroidSpeechEngine(requireContext())
        val equipmentName = arguments?.getString("equipmentName") ?: "Desconocido"
        val equipmentId = arguments?.getString("equipmentId").orEmpty()
        val profile = LocalEquipmentCatalog(requireContext()).find(equipmentId)
        binding.tvDetailTitle.text = equipmentName
        binding.tvDetailDescription.text = "Equipo registrado en el laboratorio de Bromatología."

        val variants = profile?.variants.orEmpty()
        selectedVariant = variants.firstOrNull()
        if (variants.size > 1) {
            binding.tvVariantLabel.visibility = View.VISIBLE
            binding.variantSpinner.visibility = View.VISIBLE
            binding.variantSpinner.adapter = ArrayAdapter(
                requireContext(),
                android.R.layout.simple_spinner_dropdown_item,
                variants.map { it.displayName }
            )
            binding.variantSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    selectedVariant = variants.getOrNull(position)
                    updateManualStatus()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        updateManualStatus()

        binding.btnAsk.setOnClickListener {
            val question = binding.tietQuestion.text.toString()
            if (question.isNotBlank()) {
                viewModel.askAssistant(question, equipmentId, selectedVariant?.id)
            }
        }

        viewModel.assistantResponse.observe(viewLifecycleOwner) { response ->
            binding.tvAssistantResponse.text = response
            speechEngine.speak(response)
        }

        binding.btnOpenManual.setOnClickListener {
            selectedVariant?.manualUrl?.let { url ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            binding.pbLoading.visibility = if (isLoading) View.VISIBLE else View.GONE
            binding.btnAsk.isEnabled = !isLoading
        }
    }

    private fun updateManualStatus() {
        val variant = selectedVariant
        binding.tvDetailDescription.text = buildString {
            append(variant?.displayName ?: "Equipo registrado en el laboratorio de Bromatología")
            variant?.manufacturer?.let { append("\nFabricante: ").append(it) }
            variant?.model?.let { append("\nModelo: ").append(it) }
        }
        binding.tvManualStatus.text = when {
            variant == null -> "Identificación y documentación en preparación"
            variant.manualStatus == "verified" -> "Manual exacto y referencia general disponibles"
            variant.generalReferenceAvailable -> "Referencia general disponible; manual exacto pendiente de validar"
            else -> when (variant.manualStatus) {
                "model_verified_manual_pending" -> "Modelo verificado; manual exacto pendiente"
                "model_pending" -> "Marca identificada; modelo exacto pendiente de confirmar"
                "generic_equipment" -> "Equipo genérico; no requiere asociar un modelo comercial"
                else -> "Identificación y manual oficial en preparación"
            }
        }
        binding.btnOpenManual.visibility = if (variant?.manualUrl != null) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        speechEngine.close()
        _binding = null
    }
}
