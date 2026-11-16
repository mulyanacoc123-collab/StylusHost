package com.example.stylushost

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * Menggabungkan frame kamera + anotasi ke satu Bitmap (diputar sesuai orientasi,
 * di-downscale), lalu meng-encode ke JPEG untuk dikirim lewat WebSocket.
 * Hanya boleh dipanggil dari SATU thread (thread analisis).
 */
class FrameComposer(
    private val annotation: AnnotationLayer,
    private val maxSide: Int = 960,
    private val jpegQuality: Int = 60
) {
    var outputBitmap: Bitmap? = null
        private set
    private var canvas: Canvas? = null
    private val matrix = Matrix()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val stream = ByteArrayOutputStream(128 * 1024)

    fun compose(src: Bitmap, rotationDegrees: Int): ByteArray {
        val rw = if (rotationDegrees % 180 == 0) src.width else src.height
        val rh = if (rotationDegrees % 180 == 0) src.height else src.width
        val scale = maxSide.toFloat() / max(rw, rh)
        val ow = (rw * scale).toInt().coerceAtLeast(1)
        val oh = (rh * scale).toInt().coerceAtLeast(1)

        var out = outputBitmap
        if (out == null || out.width != ow || out.height != oh) {
            out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
            outputBitmap = out
            canvas = Canvas(out)
        }
        val c = canvas!!

        matrix.reset()
        matrix.postTranslate(-src.width / 2f, -src.height / 2f)
        matrix.postRotate(rotationDegrees.toFloat())
        matrix.postScale(scale, scale)
        matrix.postTranslate(ow / 2f, oh / 2f)

        c.drawBitmap(src, matrix, paint)   // 1) video
        annotation.draw(c, ow, oh)          // 2) coretan stylus

        stream.reset()
        out.compress(Bitmap.CompressFormat.JPEG, jpegQuality, stream)
        return stream.toByteArray()
    }
}
