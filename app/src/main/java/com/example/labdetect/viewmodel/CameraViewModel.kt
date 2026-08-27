package com.example.labdetect.viewmodel

import android.graphics.Bitmap
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.example.labdetect.data.FakeEquipmentClassifier
import com.example.labdetect.domain.ClassificationResult

class CameraViewModel : ViewModel() {
    private val classifier = FakeEquipmentClassifier()

    private val _classificationResult = MutableLiveData<ClassificationResult?>()
    val classificationResult: LiveData<ClassificationResult?> = _classificationResult

    fun onImageCaptured(bitmap: Bitmap) {
        val result = classifier.classify(bitmap)
        _classificationResult.value = result
    }
}