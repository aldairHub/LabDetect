package com.example.labdetect.domain

import android.graphics.Bitmap
import java.io.Closeable

interface EquipmentDetector : Closeable {
    fun detect(bitmap: Bitmap): List<Detection>
}
