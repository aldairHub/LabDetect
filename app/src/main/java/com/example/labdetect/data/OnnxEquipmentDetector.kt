package com.example.labdetect.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.example.labdetect.domain.Detection
import com.example.labdetect.domain.EquipmentDetector
import org.json.JSONObject
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

/** Ejecuta el YOLO exportado en el propio teléfono. No envía imágenes a servidores. */
class OnnxEquipmentDetector(context: Context) : EquipmentDetector {
    private val environment = OrtEnvironment.getEnvironment()
    private val session: OrtSession?
    private val inputName: String?
    private val inputSize: Int
    private val canonicalNames: List<String>
    private val displayNames: List<String>
    val isReady: Boolean get() = session != null

    init {
        val assets = context.applicationContext.assets
        val metadata = runCatching {
            JSONObject(assets.open(METADATA_ASSET).bufferedReader().use { it.readText() })
        }.getOrNull()
        inputSize = metadata?.optInt("input_size", DEFAULT_INPUT_SIZE) ?: DEFAULT_INPUT_SIZE
        val canonicalArray = metadata?.optJSONArray("classes")
        canonicalNames = canonicalArray?.let { names ->
            List(names.length()) { index -> names.getString(index) }
        }.orEmpty()
        val displayArray = metadata?.optJSONArray("display_names") ?: canonicalArray
        displayNames = displayArray?.let { names ->
            List(names.length()) { index -> names.getString(index) }
        }.orEmpty()

        session = runCatching {
            val bytes = assets.open(MODEL_ASSET).use { it.readBytes() }
            environment.createSession(bytes, OrtSession.SessionOptions())
        }.getOrNull()
        inputName = session?.inputNames?.firstOrNull()
    }

    override fun detect(bitmap: Bitmap): List<Detection> {
        val fullFrame = detectFrame(bitmap)
        if (fullFrame.firstOrNull()?.confidence?.let { it >= ZOOM_TRIGGER_CONFIDENCE } == true) {
            return fullFrame
        }

        // Una segunda pasada sobre el centro aumenta el tamaño aparente del equipo. Mejora
        // la detección a distancia sin cambiar ni reentrenar el modelo.
        val cropWidth = (bitmap.width * CENTER_CROP_RATIO).toInt().coerceAtLeast(1)
        val cropHeight = (bitmap.height * CENTER_CROP_RATIO).toInt().coerceAtLeast(1)
        val cropLeft = (bitmap.width - cropWidth) / 2
        val cropTop = (bitmap.height - cropHeight) / 2
        val crop = Bitmap.createBitmap(bitmap, cropLeft, cropTop, cropWidth, cropHeight)
        val zoomed = try {
            detectFrame(crop).map { detection ->
                detection.copy(
                    left = (cropLeft + detection.left * cropWidth) / bitmap.width,
                    top = (cropTop + detection.top * cropHeight) / bitmap.height,
                    right = (cropLeft + detection.right * cropWidth) / bitmap.width,
                    bottom = (cropTop + detection.bottom * cropHeight) / bitmap.height
                )
            }
        } finally {
            crop.recycle()
        }
        return mergeDetections(fullFrame + zoomed)
    }

    private fun detectFrame(bitmap: Bitmap): List<Detection> {
        val activeSession = session ?: return emptyList()
        val activeInputName = inputName ?: return emptyList()
        val prepared = letterbox(bitmap)
        val input = bitmapToNchw(prepared.bitmap)

        return try {
            OnnxTensor.createTensor(
                environment,
                FloatBuffer.wrap(input),
                longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())
            ).use { tensor ->
                activeSession.run(mapOf(activeInputName to tensor)).use { output ->
                    val rows = output[0].value.toRows()
                    rows.mapNotNull { row -> parseDetection(row, prepared, bitmap.width, bitmap.height) }
                        .sortedByDescending { it.confidence }
                        .take(MAX_DETECTIONS)
                }
            }
        } finally {
            prepared.bitmap.recycle()
        }
    }

    private fun mergeDetections(detections: List<Detection>): List<Detection> {
        val accepted = mutableListOf<Detection>()
        detections.sortedByDescending { it.confidence }.forEach { candidate ->
            val duplicate = accepted.any {
                it.canonicalId == candidate.canonicalId && intersectionOverUnion(it, candidate) >= 0.45f
            }
            if (!duplicate) accepted += candidate
        }
        return accepted.take(MAX_DETECTIONS)
    }

    private fun intersectionOverUnion(a: Detection, b: Detection): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        val intersection = max(0f, right - left) * max(0f, bottom - top)
        val areaA = max(0f, a.right - a.left) * max(0f, a.bottom - a.top)
        val areaB = max(0f, b.right - b.left) * max(0f, b.bottom - b.top)
        val union = areaA + areaB - intersection
        return if (union > 0f) intersection / union else 0f
    }

    private fun letterbox(source: Bitmap): PreparedBitmap {
        val scale = min(inputSize.toFloat() / source.width, inputSize.toFloat() / source.height)
        val scaledWidth = (source.width * scale).toInt().coerceAtLeast(1)
        val scaledHeight = (source.height * scale).toInt().coerceAtLeast(1)
        val padX = (inputSize - scaledWidth) / 2f
        val padY = (inputSize - scaledHeight) / 2f
        val output = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.rgb(114, 114, 114))
        canvas.drawBitmap(
            source,
            null,
            RectF(padX, padY, padX + scaledWidth, padY + scaledHeight),
            Paint(Paint.FILTER_BITMAP_FLAG)
        )
        return PreparedBitmap(output, scale, padX, padY)
    }

    private fun bitmapToNchw(bitmap: Bitmap): FloatArray {
        val plane = inputSize * inputSize
        val pixels = IntArray(plane)
        bitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        return FloatArray(plane * 3).also { values ->
            pixels.forEachIndexed { index, pixel ->
                values[index] = Color.red(pixel) / 255f
                values[plane + index] = Color.green(pixel) / 255f
                values[plane * 2 + index] = Color.blue(pixel) / 255f
            }
        }
    }

    private fun parseDetection(
        row: FloatArray,
        prepared: PreparedBitmap,
        originalWidth: Int,
        originalHeight: Int
    ): Detection? {
        if (row.size < 6) return null
        val confidence = row[4]
        if (!confidence.isFinite() || confidence < CONFIDENCE_THRESHOLD) return null
        val classId = row[5].toInt()
        if (classId !in canonicalNames.indices || classId !in displayNames.indices) return null

        val left = ((row[0] - prepared.padX) / prepared.scale / originalWidth).coerceIn(0f, 1f)
        val top = ((row[1] - prepared.padY) / prepared.scale / originalHeight).coerceIn(0f, 1f)
        val right = ((row[2] - prepared.padX) / prepared.scale / originalWidth).coerceIn(0f, 1f)
        val bottom = ((row[3] - prepared.padY) / prepared.scale / originalHeight).coerceIn(0f, 1f)
        if (right <= left || bottom <= top) return null

        return Detection(
            canonicalId = canonicalNames[classId],
            label = displayNames[classId],
            confidence = confidence * 100f,
            left = left,
            top = top,
            right = right,
            bottom = bottom
        )
    }

    private fun Any?.toRows(): List<FloatArray> {
        if (this !is Array<*>) return emptyList()
        val first = firstOrNull()
        return when {
            first is FloatArray -> filterIsInstance<FloatArray>()
            first is Array<*> -> first.filterIsInstance<FloatArray>()
            else -> emptyList()
        }
    }

    override fun close() {
        session?.close()
    }

    private data class PreparedBitmap(
        val bitmap: Bitmap,
        val scale: Float,
        val padX: Float,
        val padY: Float
    )

    companion object {
        private const val MODEL_ASSET = "labdetect_yolo26n.onnx"
        private const val METADATA_ASSET = "labdetect_yolo26n.metadata.json"
        private const val DEFAULT_INPUT_SIZE = 640
        private const val CONFIDENCE_THRESHOLD = 0.25f
        private const val CENTER_CROP_RATIO = 0.68f
        private const val ZOOM_TRIGGER_CONFIDENCE = 60f
        private const val MAX_DETECTIONS = 20
    }
}
