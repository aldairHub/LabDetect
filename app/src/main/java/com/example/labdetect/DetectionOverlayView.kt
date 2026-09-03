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

    fun submitDetections(value: List<Detection>) {
        detections = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        detections.forEach { detection ->
            val box = RectF(
                detection.left * width,
                detection.top * height,
                detection.right * width,
                detection.bottom * height
            )
            canvas.drawRect(box, boxPaint)
            val text = "${detection.label} ${"%.0f".format(detection.confidence)}%"
            val textWidth = labelPaint.measureText(text)
            val textHeight = labelPaint.fontMetrics.run { bottom - top }
            val labelTop = (box.top - textHeight - 12f).coerceAtLeast(0f)
            canvas.drawRect(box.left, labelTop, box.left + textWidth + 20f, labelTop + textHeight + 12f, backgroundPaint)
            canvas.drawText(text, box.left + 10f, labelTop - labelPaint.fontMetrics.top + 4f, labelPaint)
        }
    }
}
