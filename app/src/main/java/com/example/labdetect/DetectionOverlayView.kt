package com.example.labdetect

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.example.labdetect.domain.Detection

class DetectionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0, 230, 118)
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val candidatePaint = Paint(boxPaint).apply {
        color = Color.rgb(255, 193, 7)
    }
    private var detections: List<Detection> = emptyList()
    private var sourceWidth = 0
    private var sourceHeight = 0

    fun submitDetections(value: List<Detection>) {
        detections = value
        invalidate()
    }

    /** Mantiene las cajas alineadas con el recorte FILL_CENTER de PreviewView. */
    fun setSourceFrameSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || (sourceWidth == width && sourceHeight == height)) return
        sourceWidth = width
        sourceHeight = height
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        detections.forEach { detection ->
            val box = mapToPreview(detection)
            canvas.drawRect(
                box,
                if (detection.confirmed) boxPaint else candidatePaint
            )
        }
    }

    private fun mapToPreview(detection: Detection): RectF {
        if (sourceWidth <= 0 || sourceHeight <= 0 || width == 0 || height == 0) {
            return RectF(
                detection.left * width,
                detection.top * height,
                detection.right * width,
                detection.bottom * height
            )
        }
        // PreviewView usa FILL_CENTER: escala la imagen hasta cubrir la pantalla y
        // recorta solo los bordes sobrantes. Aplicamos la misma matriz a la caja.
        val scale = maxOf(width.toFloat() / sourceWidth, height.toFloat() / sourceHeight)
        val offsetX = (width - sourceWidth * scale) / 2f
        val offsetY = (height - sourceHeight * scale) / 2f
        return RectF(
            detection.left * sourceWidth * scale + offsetX,
            detection.top * sourceHeight * scale + offsetY,
            detection.right * sourceWidth * scale + offsetX,
            detection.bottom * sourceHeight * scale + offsetY
        )
    }

}
