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
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 38f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 0, 0, 0)
        style = Paint.Style.FILL
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
            canvas.drawRect(box, boxPaint)
            val text = "${detection.label} ${"%.0f".format(detection.confidence)}%"
            val textWidth = labelPaint.measureText(text)
            val textHeight = labelPaint.fontMetrics.run { bottom - top }
            val labelTop = (box.top - textHeight - 12f).coerceAtLeast(0f)
            canvas.drawRect(box.left, labelTop, box.left + textWidth + 20f, labelTop + textHeight + 12f, backgroundPaint)
            canvas.drawText(text, box.left + 10f, labelTop - labelPaint.fontMetrics.top + 4f, labelPaint)
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
