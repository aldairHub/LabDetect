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
import com.example.labdetect.data.EquipmentInteractionStore
import com.example.labdetect.domain.EquipmentVariant
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class DetailFragment : Fragment() {

    private var _binding: FragmentDetailBinding? = null
    private val binding get() = _binding!!

    private val viewModel: DetailViewModel by viewModels()
    private lateinit var speechEngine: AndroidSpeechEngine
    private lateinit var favoriteStore: FavoriteEquipmentStore
    private lateinit var interactionStore: EquipmentInteractionStore
    private var selectedVariant: EquipmentVariant? = null
    private var localManual: LocalManual? = null
    private var equipmentId: String = ""
    private var lastQuestion: String = ""

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
        interactionStore = EquipmentInteractionStore(requireContext())
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
        binding.quickActionsGroup.visibility = if (localManual != null) View.VISIBLE else View.GONE
        binding.btnQuestionHistory.visibility = if (interactionStore.historyFor(equipmentId).isNotEmpty()) View.VISIBLE else View.GONE
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
            interactionStore.rememberQuestion(equipmentId, lastQuestion, response)
            if (response.startsWith("No cuento con esa información dentro de mis manuales")) {
                interactionStore.rememberMissingInformation(equipmentId, lastQuestion)
            }
            binding.btnQuestionHistory.visibility = if (interactionStore.historyFor(equipmentId).isNotEmpty()) View.VISIBLE else View.GONE
            speechEngine.speak(response)
        }

        binding.btnOpenManual.setOnClickListener {
            localManual?.let(::showOfflineManualMenu)
        }
        binding.btnQuickPurpose.setOnClickListener {
            localManual?.let { showOfflineSection("Para qué sirve", concise(it.function)) }
        }
        binding.btnQuickBeforeUse.setOnClickListener {
            localManual?.let(::showProcedureGuide)
        }
        binding.btnQuickSafety.setOnClickListener {
            localManual?.let { showOfflineSection("Uso seguro", concise(it.safety)) }
        }
        binding.btnQuickFinish.setOnClickListener {
            localManual?.let { showOfflineSection("Al terminar", concise(it.maintenance)) }
        }
        binding.btnQuestionHistory.setOnClickListener { showQuestionHistory() }
        binding.btnReportMissing.setOnClickListener {
            if (lastQuestion.isNotBlank()) {
                interactionStore.rememberMissingInformation(equipmentId, lastQuestion)
                binding.btnReportMissing.visibility = View.GONE
                android.widget.Toast.makeText(context, "Listo, la tendremos en cuenta para ampliar el manual.", android.widget.Toast.LENGTH_SHORT).show()
            }
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
                lastQuestion = question.trim()
                binding.btnReportMissing.visibility = View.VISIBLE
                viewModel.askAssistant(question, equipmentId, selectedVariant?.id)
                binding.tietQuestion.text?.clear()
            }
    }

    private fun showOfflineManualMenu(manual: LocalManual) {
        val options = listOf(
            "Qué es y para qué sirve",
            "Características principales",
            "Uso general",
            "Seguridad",
            "Mantenimiento"
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(manual.displayName)
            .setMessage("Elige solo la parte que necesitas consultar.")
            .setItems(options.toTypedArray()) { _, position ->
                val title = options[position]
                val content = when (position) {
                    0 -> manual.function
                    1 -> manual.specifications
                    2 -> manual.procedure
                    3 -> manual.safety
                    else -> manual.maintenance
                }
                showOfflineSection(title, concise(content))
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun showOfflineSection(title: String, sectionContent: String) {
        val bodyView = TextView(requireContext()).apply {
            text = sectionContent.ifBlank { "Esta sección todavía no está disponible para este equipo." }
            textSize = 15f
            setTextIsSelectable(true)
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        val scroll = ScrollView(requireContext()).apply { addView(bodyView) }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton("Cerrar", null)
            .show()
    }

    private fun showProcedureGuide(manual: LocalManual) {
        val steps = manual.procedure
            .split(Regex("(?<=[.!?])\\s+|\\n+|•"))
            .map { it.trim().removePrefix("-").trim() }
            .filter { it.length >= 12 }
            .take(6)
            .ifEmpty { listOf("La guía offline de este equipo todavía no contiene pasos detallados.") }
        fun showStep(index: Int) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Antes de usar · ${index + 1} de ${steps.size}")
                .setMessage(steps[index])
                .setNegativeButton("Cerrar", null)
                .setPositiveButton(if (index == steps.lastIndex) "Listo" else "Siguiente") { _, _ ->
                    if (index < steps.lastIndex) showStep(index + 1)
                }
                .show()
        }
        showStep(0)
    }

    private fun showQuestionHistory() {
        val history = interactionStore.historyFor(equipmentId).take(6)
        if (history.isEmpty()) return
        val text = history.joinToString("\n\n") { "Tú: ${it.question}\nLabDetect: ${it.answer}" }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Consultas recientes")
            .setMessage(text)
            .setPositiveButton("Cerrar", null)
            .show()
    }

    private fun concise(value: String): String {
        val normalized = value.replace(Regex("\\s+"), " ").trim()
        if (normalized.length <= OFFLINE_SECTION_LIMIT) return normalized
        val end = normalized.lastIndexOf('.', OFFLINE_SECTION_LIMIT).takeIf { it > OFFLINE_SECTION_LIMIT / 2 }
            ?: normalized.lastIndexOf(' ', OFFLINE_SECTION_LIMIT)
        return normalized.take(end.coerceAtLeast(OFFLINE_SECTION_LIMIT)) + "…"
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

    companion object {
        private const val OFFLINE_SECTION_LIMIT = 850
    }
}
