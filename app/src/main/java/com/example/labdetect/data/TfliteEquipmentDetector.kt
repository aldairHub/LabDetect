package com.example.labdetect.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.example.labdetect.domain.Detection
import com.example.labdetect.domain.EquipmentDetector
import org.json.JSONObject
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/** Ejecuta el YOLO11s TFLite en el teléfono; las imágenes no salen de la APK. */
class TfliteEquipmentDetector(context: Context) : EquipmentDetector {
    private val interpreter: Interpreter?
    private val inputShape: IntArray
    private val outputShape: IntArray
    private val inputSize: Int
    private val classes: List<EquipmentClass>

    val isReady: Boolean get() = interpreter != null

    init {
        val assets = context.applicationContext.assets
        val metadata = runCatching {
            JSONObject(assets.open(METADATA_ASSET).bufferedReader().use { it.readText() })
        }.getOrNull()
        inputSize = metadata?.optInt("input_size", DEFAULT_INPUT_SIZE) ?: DEFAULT_INPUT_SIZE
        classes = metadata?.optJSONArray("classes")?.let { values ->
            List(values.length()) { index ->
                val value = values.getJSONObject(index)
                EquipmentClass(value.getString("id"), value.getString("label"))
            }
        }.orEmpty()

        val created = runCatching {
            val model = assets.open(MODEL_ASSET).use { source ->
                source.readBytes().toDirectBuffer()
            }
            val options = Interpreter.Options().apply {
                setNumThreads(INFERENCE_THREADS)
                setUseXNNPACK(true)
            }
            Interpreter(model, options)
        }.getOrNull()
        interpreter = created
        inputShape = created?.getInputTensor(0)?.shape() ?: intArrayOf()
        outputShape = created?.getOutputTensor(0)?.shape() ?: intArrayOf()
    }

    override fun detect(bitmap: Bitmap): List<Detection> {
        val fullFrame = detectFrame(bitmap)
        // Una señal débil no debe impedir el acercamiento al centro. Así, un equipo
        // lejano puede pasar de candidato a detección válida sin aceptar esa señal
        // débil como una etiqueta visible.
        if (fullFrame.any { it.confidence >= DIRECT_FRAME_CONFIDENCE }) return fullFrame

        // Si el equipo está pequeño, una única pasada ampliada ayuda sin duplicar la
        // inferencia normal de cada fotograma.
        val cropWidth = (bitmap.width * CENTER_CROP_RATIO).toInt().coerceAtLeast(1)
        val cropHeight = (bitmap.height * CENTER_CROP_RATIO).toInt().coerceAtLeast(1)
        val cropLeft = (bitmap.width - cropWidth) / 2
        val cropTop = (bitmap.height - cropHeight) / 2
        val crop = Bitmap.createBitmap(bitmap, cropLeft, cropTop, cropWidth, cropHeight)
        return try {
            val enlarged = detectFrame(crop).map { detection ->
                detection.copy(
                    left = (cropLeft + detection.left * cropWidth) / bitmap.width,
                    top = (cropTop + detection.top * cropHeight) / bitmap.height,
                    right = (cropLeft + detection.right * cropWidth) / bitmap.width,
                    bottom = (cropTop + detection.bottom * cropHeight) / bitmap.height
                )
            }
            nonMaxSuppression(fullFrame + enlarged)
        } finally {
            crop.recycle()
        }
    }

    private fun detectFrame(bitmap: Bitmap): List<Detection> {
        val activeInterpreter = interpreter ?: return emptyList()
        if (!validModelShape()) return emptyList()

        val prepared = letterbox(bitmap)
        return try {
            val input = bitmapToInput(prepared.bitmap)
            val output = ByteBuffer.allocateDirect(activeInterpreter.getOutputTensor(0).numBytes())
                .order(ByteOrder.nativeOrder())
            activeInterpreter.run(input, output)
            output.rewind()
            parseOutput(
                output.asFloatBuffer(),
                prepared,
                bitmap.width,
                bitmap.height
            )
        } finally {
            prepared.bitmap.recycle()
        }
    }

    private fun validModelShape(): Boolean {
        val activeInterpreter = interpreter ?: return false
        return inputShape.size == 4 && outputShape.size == 3 &&
            activeInterpreter.getInputTensor(0).dataType() == DataType.FLOAT32 &&
            activeInterpreter.getOutputTensor(0).dataType() == DataType.FLOAT32
    }

    private fun bitmapToInput(bitmap: Bitmap): ByteBuffer {
        val input = ByteBuffer.allocateDirect(interpreter!!.getInputTensor(0).numBytes())
            .order(ByteOrder.nativeOrder())
        val pixels = IntArray(inputSize * inputSize)
        bitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        val nchw = inputShape[1] == 3
        if (nchw) {
            val plane = inputSize * inputSize
            repeat(3) { channel ->
                pixels.forEach { pixel ->
                    val component = when (channel) {
                        0 -> Color.red(pixel)
                        1 -> Color.green(pixel)
                        else -> Color.blue(pixel)
                    }
                    input.putFloat(component / 255f)
                }
            }
            check(input.position() == plane * 3 * Float.SIZE_BYTES)
        } else {
            pixels.forEach { pixel ->
                input.putFloat(Color.red(pixel) / 255f)
                input.putFloat(Color.green(pixel) / 255f)
                input.putFloat(Color.blue(pixel) / 255f)
            }
        }
        input.rewind()
        return input
    }

    private fun parseOutput(
        values: java.nio.FloatBuffer,
        prepared: PreparedBitmap,
        originalWidth: Int,
        originalHeight: Int
    ): List<Detection> {
        val second = outputShape[1]
        val third = outputShape[2]
        val channelsFirst = second in MIN_FEATURES..MAX_FEATURES && third > second
        val features = if (channelsFirst) second else third
        val candidates = if (channelsFirst) third else second
        if (features < 4 + classes.size || candidates <= 0) return emptyList()

        fun value(feature: Int, candidate: Int): Float = if (channelsFirst) {
            values.get(feature * candidates + candidate)
        } else {
            values.get(candidate * features + feature)
        }

        val parsed = ArrayList<Detection>()
        for (candidate in 0 until candidates) {
            var classIndex = -1
            var score = 0f
            for (index in classes.indices) {
                val candidateScore = value(4 + index, candidate)
                if (candidateScore > score) {
                    score = candidateScore
                    classIndex = index
                }
            }
            if (classIndex < 0 || !score.isFinite() || score < CONFIDENCE_THRESHOLD) continue
            val width = value(2, candidate)
            val height = value(3, candidate)
            if (!width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f) continue

            val centerX = value(0, candidate)
            val centerY = value(1, candidate)
            // Este export de YOLO11 entrega xywh normalizado (0..1), no en píxeles
            // 640x640. Antes se trataba como píxeles y, al quitar el letterbox, cada
            // caja quedaba fuera del fotograma y se descartaba. Se conserva soporte
            // para exports futuros que sí entreguen coordenadas en píxeles.
            val coordinateScale = if (usesNormalizedCoordinates(centerX, centerY, width, height)) {
                inputSize.toFloat()
            } else {
                1f
            }
            val box = classes[classIndex]
            val detection = toDetection(
                left = (centerX - width / 2f) * coordinateScale,
                top = (centerY - height / 2f) * coordinateScale,
                right = (centerX + width / 2f) * coordinateScale,
                bottom = (centerY + height / 2f) * coordinateScale,
                score = score,
                equipment = box,
                prepared = prepared,
                originalWidth = originalWidth,
                originalHeight = originalHeight
            )
            if (detection != null) parsed += detection
        }
        return nonMaxSuppression(parsed)
    }

    private fun usesNormalizedCoordinates(
        centerX: Float,
        centerY: Float,
        width: Float,
        height: Float
    ): Boolean = centerX in 0f..1.5f && centerY in 0f..1.5f &&
        width in 0f..1.5f && height in 0f..1.5f

    private fun toDetection(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        score: Float,
        equipment: EquipmentClass,
        prepared: PreparedBitmap,
        originalWidth: Int,
        originalHeight: Int
    ): Detection? {
        val mappedLeft = ((left - prepared.padX) / prepared.scale / originalWidth).coerceIn(0f, 1f)
        val mappedTop = ((top - prepared.padY) / prepared.scale / originalHeight).coerceIn(0f, 1f)
        val mappedRight = ((right - prepared.padX) / prepared.scale / originalWidth).coerceIn(0f, 1f)
        val mappedBottom = ((bottom - prepared.padY) / prepared.scale / originalHeight).coerceIn(0f, 1f)
        if (mappedRight <= mappedLeft || mappedBottom <= mappedTop) return null
        return Detection(
            canonicalId = equipment.id,
            label = equipment.label,
            confidence = score * 100f,
            left = mappedLeft,
            top = mappedTop,
            right = mappedRight,
            bottom = mappedBottom
        )
    }

    private fun nonMaxSuppression(detections: List<Detection>): List<Detection> {
        val accepted = ArrayList<Detection>()
        detections.sortedByDescending { it.confidence }.forEach { candidate ->
            val duplicate = accepted.any {
                it.canonicalId == candidate.canonicalId && intersectionOverUnion(it, candidate) >= NMS_IOU_THRESHOLD
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
        Canvas(output).apply {
            drawColor(Color.rgb(114, 114, 114))
            drawBitmap(source, null, RectF(padX, padY, padX + scaledWidth, padY + scaledHeight), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        return PreparedBitmap(output, scale, padX, padY)
    }

    private fun ByteArray.toDirectBuffer(): ByteBuffer = ByteBuffer.allocateDirect(size)
        .order(ByteOrder.nativeOrder())
        .apply { put(this@toDirectBuffer); rewind() }

    override fun close() {
        interpreter?.close()
    }

    private data class EquipmentClass(val id: String, val label: String)
    private data class PreparedBitmap(val bitmap: Bitmap, val scale: Float, val padX: Float, val padY: Float)

    companion object {
        private const val MODEL_ASSET = "labdetect_yolo11s.tflite"
        private const val METADATA_ASSET = "labdetect_yolo11s.metadata.json"
        private const val DEFAULT_INPUT_SIZE = 640
        private const val INFERENCE_THREADS = 4
        // Piso interno: conserva señales para confirmarlas entre fotogramas, pero la
        // interfaz solo muestra las que superan un umbral mucho más alto en el ViewModel.
        private const val CONFIDENCE_THRESHOLD = 0.25f
        private const val DIRECT_FRAME_CONFIDENCE = 65f
        private const val NMS_IOU_THRESHOLD = 0.45f
        private const val CENTER_CROP_RATIO = 0.72f
        private const val MAX_DETECTIONS = 20
        private const val MIN_FEATURES = 6
        private const val MAX_FEATURES = 256
    }
}
