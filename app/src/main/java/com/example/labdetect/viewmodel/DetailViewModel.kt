package com.example.labdetect.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.labdetect.data.KnowledgeApiEquipmentAssistantRepository
import com.example.labdetect.domain.EquipmentAssistantRepository
import kotlinx.coroutines.launch

class DetailViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: EquipmentAssistantRepository =
        KnowledgeApiEquipmentAssistantRepository(application)

    private val _assistantResponse = MutableLiveData<String>()
    val assistantResponse: LiveData<String> = _assistantResponse

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    fun askAssistant(question: String, equipmentId: String, variantId: String?) {
        viewModelScope.launch {
            _isLoading.value = true
            val response = repository.ask(question, equipmentId, variantId)
            _assistantResponse.value = response
            _isLoading.value = false
        }
    }
}
