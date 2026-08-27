package com.example.labdetect.domain

import android.graphics.Bitmap

interface EquipmentClassifier {
    fun classify(bitmap: Bitmap): ClassificationResult
}