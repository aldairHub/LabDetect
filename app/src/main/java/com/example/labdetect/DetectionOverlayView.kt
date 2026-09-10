package com.example.labdetect

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.View
import com.example.labdetect.domain.Detection

class DetectionOverlayView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val dp = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13 * resources.displayMetrics.scaledDensity
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        color = Color.WHITE
    }
    private var detections: List<Detection> = emptyList()
    private var sourceWidth = 0
    private var sourceHeight = 0
    private var focusX = 0f
    private var focusY = 0f
    private var focusAt = -1000L

    fun submitDetections(value: List<Detection>) {
        detections = value
        contentDescription = value.filter { it.confirmed }.joinToString { "${it.label}, ${it.confidence.toInt()} por ciento de confianza" }
        invalidate()
    }
    fun setSourceFrameSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        sourceWidth = width
        sourceHeight = height
    }
    fun showFocus(x: Float, y: Float) {
        focusX = x; focusY = y; focusAt = SystemClock.uptimeMillis()
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        detections.forEach { detection ->
            val box = mapToPreview(detection)
            if (box.width() < 1 || box.height() < 1) return@forEach
            val radius = minOf(9 * dp, box.width() / 4, box.height() / 4)
            val color = if (detection.confirmed) Color.rgb(70, 238, 112) else Color.rgb(232, 190, 91)
            paint.color = color; paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp; paint.alpha = 170
            canvas.drawRoundRect(box, radius, radius, paint)
            paint.strokeWidth = 3.5f * dp; paint.alpha = 255
            val length = minOf(22 * dp, box.width() / 4, box.height() / 4)
            val path = Path()
            path.moveTo(box.left, box.top + length)
            path.lineTo(box.left, box.top + radius)
            path.quadTo(box.left, box.top, box.left + radius, box.top)
            path.lineTo(box.left + length, box.top)
            path.moveTo(box.right - length, box.top)
            path.lineTo(box.right - radius, box.top)
            path.quadTo(box.right, box.top, box.right, box.top + radius)
            path.lineTo(box.right, box.top + length)
            path.moveTo(box.right, box.bottom - length)
            path.lineTo(box.right, box.bottom - radius)
            path.quadTo(box.right, box.bottom, box.right - radius, box.bottom)
            path.lineTo(box.right - length, box.bottom)
            path.moveTo(box.left + length, box.bottom)
            path.lineTo(box.left + radius, box.bottom)
            path.quadTo(box.left, box.bottom, box.left, box.bottom - radius)
            path.lineTo(box.left, box.bottom - length)
            canvas.drawPath(path, paint)
            paint.strokeWidth = dp; paint.alpha = 160
            for (fraction in listOf(.3f, .5f, .7f)) {
                val y = box.top + box.height() * fraction
                canvas.drawLine(box.left - 2 * dp, y, box.left + 3 * dp, y, paint)
                canvas.drawLine(box.right - 3 * dp, y, box.right + 2 * dp, y, paint)
            }
            if (detection.confirmed) drawLabel(canvas, box, detection, color)
        }
        val age = SystemClock.uptimeMillis() - focusAt
        if (age in 0..650) {
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.5f * dp
            paint.color = Color.WHITE; paint.alpha = (255 * (1 - age / 650f)).toInt()
            canvas.drawCircle(focusX, focusY, 23 * dp, paint)
            postInvalidateDelayed(32)
        }
    }
    private fun drawLabel(canvas: Canvas, box: RectF, detection: Detection, color: Int) {
        val available = (width - 32 * dp).coerceAtLeast(1f)
        val label = TextUtils.ellipsize("${detection.label} · ${detection.confidence.toInt()}%", textPaint,
            (available - 16 * dp).coerceAtLeast(1f), TextUtils.TruncateAt.END).toString()
        val labelWidth = (textPaint.measureText(label) + 16 * dp).coerceAtMost(available)
        val labelHeight = textPaint.fontMetrics.run { descent - ascent } + 10 * dp
        val x = box.left.coerceIn(16 * dp, (width - labelWidth - 16 * dp).coerceAtLeast(16 * dp))
        val preferredY = if (box.top - labelHeight > 64 * dp) box.top - labelHeight else box.top + 8 * dp
        val y = preferredY.coerceIn(64 * dp, (height - labelHeight - 16 * dp).coerceAtLeast(64 * dp))
        val bounds = RectF(x, y, x + labelWidth, y + labelHeight)
        paint.style = Paint.Style.FILL; paint.color = Color.argb(225, 13, 39, 24)
        canvas.drawRoundRect(bounds, 7 * dp, 7 * dp, paint)
        paint.style = Paint.Style.STROKE; paint.color = color; paint.strokeWidth = dp
        canvas.drawRoundRect(bounds, 7 * dp, 7 * dp, paint)
        canvas.drawText(label, x + 8 * dp, y + 5 * dp - textPaint.fontMetrics.ascent, textPaint)
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
