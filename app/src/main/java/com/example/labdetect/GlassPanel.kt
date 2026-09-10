package com.example.labdetect

import android.content.Context
import android.graphics.*
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout

/** Only the small backdrop is blurred; text and controls remain sharp. */
class GlassPanel @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    private var frame: Bitmap? = null
    private val backdrop = object : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val location = IntArray(2)
        private val origin = IntArray(2)
        override fun onDraw(canvas: Canvas) {
            val bitmap = frame ?: return
            val surface = this@GlassPanel.rootView.findViewById<View>(R.id.viewFinder) ?: return
            surface.getLocationInWindow(origin)
            getLocationInWindow(location)
            val scale = maxOf(surface.width.toFloat() / bitmap.width, surface.height.toFloat() / bitmap.height)
            val left = (surface.width - bitmap.width * scale) / 2 + origin[0] - location[0]
            val top = (surface.height - bitmap.height * scale) / 2 + origin[1] - location[1]
            canvas.drawBitmap(bitmap, null, RectF(left, top, left + bitmap.width * scale, top + bitmap.height * scale), paint)
        }
    }
    init {
        background = context.getDrawable(R.drawable.bg_glass)
        clipToOutline = true
        if (Build.VERSION.SDK_INT >= 31) {
            backdrop.setRenderEffect(RenderEffect.createBlurEffect(12f, 12f, Shader.TileMode.CLAMP))
        }
        addView(backdrop, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(View(context).apply { background = context.getDrawable(R.drawable.bg_glass) },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }
    fun setBackdrop(bitmap: Bitmap) {
        // Older devices use the same glass tint without a costly software blur.
        if (Build.VERSION.SDK_INT < 31) return
        frame = bitmap
        backdrop.invalidate()
    }
}
