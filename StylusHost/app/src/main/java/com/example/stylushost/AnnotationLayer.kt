package com.example.stylushost

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/**
 * Lapisan anotasi. Titik disimpan dalam koordinat normalisasi sehingga
 * independen dari resolusi frame. Thread-safe (dipanggil dari thread WebSocket
 * dan thread analisis kamera).
 */
class AnnotationLayer {

    private class Pt(val x: Float, val y: Float, val p: Float)
    private class Stroke(val color: Int) { val pts = ArrayList<Pt>() }

    private val strokes = ArrayList<Stroke>()
    private var current: Stroke? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    var defaultColor: Int = Color.RED
    /** Lebar garis pada tekanan 1.0, sebagai rasio terhadap lebar frame. */
    var baseWidthRatio: Float = 0.012f
    private val maxStrokes = 500

    @Synchronized
    fun onStylus(e: StylusEvent, color: Int?) {
        val pt = Pt(e.x, e.y, e.pressure)
        when (e.action) {
            ActionType.DOWN -> {
                val s = Stroke(color ?: defaultColor).also { it.pts.add(pt) }
                strokes.add(s)
                current = s
                if (strokes.size > maxStrokes) strokes.removeAt(0)
            }
            ActionType.MOVE -> {
                val s = current ?: Stroke(color ?: defaultColor).also {
                    strokes.add(it); current = it
                }
                s.pts.add(pt)
            }
            ActionType.UP -> {
                current?.pts?.add(pt)
                current = null
            }
        }
    }

    @Synchronized
    fun clear() { strokes.clear(); current = null }

    @Synchronized
    fun undo() {
        if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex)
        current = null
    }

    @Synchronized
    fun draw(canvas: Canvas, w: Int, h: Int) {
        for (s in strokes) {
            paint.color = s.color
            if (s.pts.size == 1) {
                val p = s.pts[0]
                paint.style = Paint.Style.FILL
                canvas.drawCircle(p.x * w, p.y * h, widthFor(p.p, w) / 2f, paint)
                continue
            }
            paint.style = Paint.Style.STROKE
            for (i in 1 until s.pts.size) {
                val a = s.pts[i - 1]
                val b = s.pts[i]
                paint.strokeWidth = widthFor((a.p + b.p) / 2f, w)
                canvas.drawLine(a.x * w, a.y * h, b.x * w, b.y * h, paint)
            }
        }
    }

    private fun widthFor(pressure: Float, frameW: Int): Float =
        (0.25f + 0.75f * pressure) * frameW * baseWidthRatio
}
