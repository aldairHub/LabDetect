package com.example.labdetect.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.labdetect.data.GeminiEquipmentAssistantRepository
import com.example.labdetect.domain.EquipmentAssistantRepository
import kotlinx.coroutines.launch

class DetailViewModel : ViewModel() {

    // Reemplazando Fake por Gemini
    private val repository: EquipmentAssistantRepository = GeminiEquipmentAssistantRepository()

    private val _assistantResponse = MutableLiveData<String>()
    val assistantResponse: LiveData<String> = _assistantResponse

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    fun askAssistant(question: String, equipmentName: String) {
        viewModelScope.launch {
            _isLoading.value = true
            val response = repository.ask(question, equipmentName)
            _assistantResponse.value = response
            _isLoading.value = false
        }
    }
}
