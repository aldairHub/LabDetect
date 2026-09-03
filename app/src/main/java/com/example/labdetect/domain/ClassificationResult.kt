package com.example.labdetect.domain

data class ClassificationResult(
    val canonicalId: String,
    val label: String,
    val confidence: Float
)
