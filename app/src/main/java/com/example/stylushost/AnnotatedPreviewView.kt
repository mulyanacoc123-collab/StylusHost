package com.example.stylushost

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/** Menampilkan hasil komposit (video + anotasi) di layar host. */
class AnnotatedPreviewView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val lock = Any()
    private var frame: Bitmap? = null
    private var frameCanvas: Canvas? = null
    private val dst = RectF()

    /** Aman dipanggil dari thread background. */
    fun updateFrame(source: Bitmap) {
        synchronized(lock) {
            var f = frame
            if (f == null || f.width != source.width || f.height != source.height) {
                f = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
                frame = f
                frameCanvas = Canvas(f)
            }
            frameCanvas!!.drawBitmap(source, 0f, 0f, null)
        }
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        synchronized(lock) {
            val f = frame ?: return
            val s = min(width.toFloat() / f.width, height.toFloat() / f.height)
            val dw = f.width * s
            val dh = f.height * s
            dst.set((width - dw) / 2f, (height - dh) / 2f, (width + dw) / 2f, (height + dh) / 2f)
            canvas.drawBitmap(f, null, dst, null)
        }
    }
}
