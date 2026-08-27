package com.example.labdetect.viewmodel

import android.graphics.Bitmap
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.labdetect.data.GeminiEquipmentAssistantRepository
import com.example.labdetect.data.MLKitEquipmentClassifier
import com.example.labdetect.domain.ClassificationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CameraViewModel : ViewModel() {
    private val classifier = MLKitEquipmentClassifier()
    private val assistant = GeminiEquipmentAssistantRepository()

    private val _classificationResult = MutableLiveData<ClassificationResult?>()
    val classificationResult: LiveData<ClassificationResult?> = _classificationResult

    private val _assistantAnswer = MutableLiveData<String>()
    val assistantAnswer: LiveData<String> = _assistantAnswer

    fun onImageCaptured(bitmap: Bitmap) {
        viewModelScope.launch(Dispatchers.Default) {
            val result = classifier.classify(bitmap)
            _classificationResult.postValue(result)
        }
    }

    fun askAssistant(question: String) {
        val equipmentName = _classificationResult.value?.label ?: "objeto desconocido"
        viewModelScope.launch {
            _assistantAnswer.postValue(assistant.ask(question, equipmentName))
        }
    }
}