package com.example.labdetect

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.example.labdetect.databinding.FragmentDetailBinding

class DetailFragment : Fragment() {

    private var _binding: FragmentDetailBinding? = null
    private val binding get() = _binding!!

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
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}