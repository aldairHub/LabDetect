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
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
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
            val paint = if (detection.confirmed) boxPaint else candidatePaint
            if (detection.confirmed) {
                // La línea tenue conserva el contorno completo y las esquinas hacen
                // que la caja se lea como un escáner preciso, sin texto duplicado.
                paint.alpha = 150
                canvas.drawRoundRect(box, 10f, 10f, paint)
                paint.alpha = 255
                drawCornerMarkers(canvas, box, paint)
            } else {
                canvas.drawRoundRect(box, 10f, 10f, paint)
            }
        }
    }

    private fun drawCornerMarkers(canvas: Canvas, box: RectF, paint: Paint) {
        val length = minOf(box.width(), box.height()) * 0.16f
        canvas.drawLine(box.left, box.top + length, box.left, box.top, paint)
        canvas.drawLine(box.left, box.top, box.left + length, box.top, paint)
        canvas.drawLine(box.right - length, box.top, box.right, box.top, paint)
        canvas.drawLine(box.right, box.top, box.right, box.top + length, paint)
        canvas.drawLine(box.left, box.bottom - length, box.left, box.bottom, paint)
        canvas.drawLine(box.left, box.bottom, box.left + length, box.bottom, paint)
        canvas.drawLine(box.right - length, box.bottom, box.right, box.bottom, paint)
        canvas.drawLine(box.right, box.bottom - length, box.right, box.bottom, paint)
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
