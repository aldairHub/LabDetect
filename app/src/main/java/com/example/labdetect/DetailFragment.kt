package com.example.labdetect

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import android.widget.ScrollView
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.example.labdetect.databinding.FragmentDetailBinding
import com.example.labdetect.viewmodel.DetailViewModel
import com.example.labdetect.speech.AndroidSpeechEngine
import com.example.labdetect.data.LocalEquipmentCatalog
import com.example.labdetect.data.LocalManual
import com.example.labdetect.data.LocalManualRepository
import com.example.labdetect.data.FavoriteEquipmentStore
import com.example.labdetect.domain.EquipmentVariant
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class DetailFragment : Fragment() {

    private var _binding: FragmentDetailBinding? = null
    private val binding get() = _binding!!

    private val viewModel: DetailViewModel by viewModels()
    private lateinit var speechEngine: AndroidSpeechEngine
    private lateinit var favoriteStore: FavoriteEquipmentStore
    private var selectedVariant: EquipmentVariant? = null
    private var localManual: LocalManual? = null
    private var equipmentId: String = ""

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
        favoriteStore = FavoriteEquipmentStore(requireContext())
        val equipmentName = arguments?.getString("equipmentName") ?: "Desconocido"
        equipmentId = arguments?.getString("equipmentId").orEmpty()
        val profile = LocalEquipmentCatalog(requireContext()).find(equipmentId)
        localManual = LocalManualRepository(requireContext()).find(equipmentId)
        binding.tvDetailTitle.text = equipmentName
        binding.tvDetailDescription.text = "Equipo registrado en el laboratorio de Bromatología."
        binding.tvOfflineCharacteristics.text = localManual?.characteristics()
            ?.replace(" •", "\n•")
            ?: "La ficha offline de este equipo todavía no está disponible."
        binding.tvOfflineBadge.visibility = if (localManual != null) View.VISIBLE else View.GONE
        binding.btnOpenManual.visibility = if (localManual != null) View.VISIBLE else View.GONE
        updateFavoriteButton()

        binding.btnFavorite.setOnClickListener {
            if (equipmentId.isNotBlank()) favoriteStore.toggle(equipmentId)
            updateFavoriteButton()
        }

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
            submitQuestion()
        }
        binding.tietQuestion.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submitQuestion()
                true
            } else false
        }

        viewModel.assistantResponse.observe(viewLifecycleOwner) { response ->
            binding.tvAssistantResponse.text = response
            speechEngine.speak(response)
        }

        binding.btnOpenManual.setOnClickListener {
            localManual?.let(::showOfflineManual)
        }

        binding.btnOpenOfficialManual.setOnClickListener {
            selectedVariant?.manualUrl?.let { url ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            binding.pbLoading.visibility = if (isLoading) View.VISIBLE else View.GONE
            binding.btnAsk.isEnabled = !isLoading
        }
    }

    private fun submitQuestion() {
            val question = binding.tietQuestion.text.toString()
            if (question.isNotBlank()) {
                viewModel.askAssistant(question, equipmentId, selectedVariant?.id)
                binding.tietQuestion.text?.clear()
            }
    }

    private fun showOfflineManual(manual: LocalManual) {
        val content = TextView(requireContext()).apply {
            text = manual.readableDocument()
            textSize = 15f
            setTextIsSelectable(true)
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        val scroll = ScrollView(requireContext()).apply { addView(content) }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(manual.displayName)
            .setView(scroll)
            .setPositiveButton("Cerrar", null)
            .show()
    }

    private fun updateFavoriteButton() {
        val favorite = equipmentId.isNotBlank() && favoriteStore.contains(equipmentId)
        binding.btnFavorite.text = if (favorite) "Guardado" else "Guardar"
        binding.btnFavorite.setIconResource(
            if (favorite) R.drawable.ic_star_filled else R.drawable.ic_star_outline
        )
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
        binding.btnOpenOfficialManual.visibility =
            if (variant?.manualUrl != null) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        speechEngine.close()
        _binding = null
    }
}
