package com.example.labdetect

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.example.labdetect.databinding.FragmentDetailBinding
import com.example.labdetect.viewmodel.DetailViewModel

class DetailFragment : Fragment() {

    private var _binding: FragmentDetailBinding? = null
    private val binding get() = _binding!!

    private val viewModel: DetailViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val equipmentName = arguments?.getString("equipmentName") ?: "Desconocido"
        binding.tvDetailTitle.text = equipmentName
        binding.tvDetailDescription.text = "Información detallada sobre el $equipmentName en el laboratorio."

        binding.btnAsk.setOnClickListener {
            val question = binding.tietQuestion.text.toString()
            if (question.isNotBlank()) {
                viewModel.askAssistant(question, equipmentName)
            }
        }

        viewModel.assistantResponse.observe(viewLifecycleOwner) { response ->
            binding.tvAssistantResponse.text = response
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            binding.pbLoading.visibility = if (isLoading) View.VISIBLE else View.GONE
            binding.btnAsk.isEnabled = !isLoading
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}