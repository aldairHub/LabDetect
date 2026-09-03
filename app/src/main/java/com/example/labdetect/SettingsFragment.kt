package com.example.labdetect

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import android.widget.AdapterView
import android.widget.ArrayAdapter
import com.example.labdetect.databinding.FragmentSettingsBinding
import com.example.labdetect.speech.AndroidSpeechEngine
import com.example.labdetect.speech.SpanishVoice

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var speechEngine: AndroidSpeechEngine
    private var voiceOptions: List<SpanishVoice> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        speechEngine = AndroidSpeechEngine(requireContext()) { voices, selectedId ->
            if (_binding != null) {
                voiceOptions = voices
                binding.voiceSpinner.adapter = ArrayAdapter(
                    requireContext(), android.R.layout.simple_spinner_dropdown_item,
                    voices.map { it.description }
                )
                val selectedIndex = voices.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
                if (voices.isNotEmpty()) binding.voiceSpinner.setSelection(selectedIndex, false)
                binding.tvVoiceStatus.text = if (voices.isEmpty()) {
                    "No se encontraron voces en español. Instala una voz TTS desde Android."
                } else {
                    "${voices.size} voces en español disponibles"
                }
            }
        }
        binding.voiceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                voiceOptions.getOrNull(position)?.let { speechEngine.selectVoice(it.id) }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        binding.btnTestVoice.setOnClickListener {
            speechEngine.speak("Hola, soy el asistente de LabDetect. ¿En qué puedo ayudarte con este equipo?")
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        speechEngine.close()
        _binding = null
    }
}
