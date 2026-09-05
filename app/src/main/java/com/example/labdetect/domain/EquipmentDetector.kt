package com.example.labdetect.domain

import android.graphics.Bitmap
import java.io.Closeable

interface EquipmentDetector : Closeable {
    /** `allowCenterCrop` evita una segunda inferencia en todos los fotogramas. */
    fun detect(bitmap: Bitmap, allowCenterCrop: Boolean): List<Detection>
}
