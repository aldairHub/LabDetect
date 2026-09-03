package com.example.labdetect.domain

data class Detection(
    val canonicalId: String,
    val label: String,
    val confidence: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)
