package com.example.stylushost

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graaphics.Path
import android.view.accessibility.AccessibilityEvent

class StylusAccessibilityService : AccessibilityService() {

    companion object {
        var instance: StylusAccessibilityService? = null
    }

    private var currentPath: Path? = null
    private var lastX = 0f
    private var lastY = 0f

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    fun handleTouch(action: String, normX: Float, normY: Float) {
        val dm = resources.displayMetrics
        val x = (normX * dm.widthPixels).coerceIn(0f, dm.widthPixels.toFloat())
        val y = (normY * dm.heightPixels).coerceIn(0f, dm.heightPixels.toFloat())

        when (action) {
            "DOWN" -> {
                lastX = x
                lastY = y
            }
            "MOVE" -> {
                val path = Path().apply {
                    moveTo(lastX, lastY)
                    lineTo(x, y)
                }
                val stroke = GestureDescription.StrokeDescription(path, 0, 16)
                val gesture = GestureDescription.Builder().addStroke(stroke).build()
                dispatchGesture(gesture, null, null)
                lastX = x
                lastY = y
            }
            "UP" -> {
                val path = Path().apply {
                    moveTo(lastX, lastY)
                    lineTo(x, y)
                }
                val stroke = GestureDescription.StrokeDescription(path, 0, 10)
                val gesture = GestureDescription.Builder().addStroke(stroke).build()
                dispatchGesture(gesture, null, null)
            }
        }
    }
}
