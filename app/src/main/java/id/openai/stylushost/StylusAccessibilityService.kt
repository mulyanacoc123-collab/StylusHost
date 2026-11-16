package id.openai.stylushost

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Point
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.max

class StylusAccessibilityService : AccessibilityService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val queuedPoints = ArrayDeque<PointF>()
    private var activeStroke: GestureDescription.StrokeDescription? = null
    private var isDispatching = false
    private var endingStroke = false
    private var lastX = 0f
    private var lastY = 0f

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Accessibility gesture service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        resetStroke()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        resetStroke()
        super.onDestroy()
    }

    private fun receive(action: String, nx: Float, ny: Float, pressure: Float) {
        // Pressure is accepted from the protocol, but GestureDescription exposes no pressure field to apply it.
        val (screenWidth, screenHeight) = displaySize()
        val x = (nx.coerceIn(0f, 1f) * (screenWidth - 1)).coerceIn(0f, (screenWidth - 1).toFloat())
        val y = (ny.coerceIn(0f, 1f) * (screenHeight - 1)).coerceIn(0f, (screenHeight - 1).toFloat())
        when (action.lowercase()) {
            "down" -> beginStroke(x, y)
            "move" -> {
                if (activeStroke != null && !endingStroke) {
                    appendPoint(x, y)
                    dispatchNextSegment()
                }
            }
            "up", "cancel" -> {
                if (activeStroke != null && !endingStroke) {
                    endingStroke = true
                    appendPoint(x, y)
                    dispatchNextSegment()
                }
            }
        }
    }

    private fun beginStroke(x: Float, y: Float) {
        resetStroke()
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + 0.25f, y)
        }
        lastX = x + 0.25f
        lastY = y
        val first = GestureDescription.StrokeDescription(path, 0, SEGMENT_DURATION_MS, true)
        activeStroke = first
        dispatching(first)
    }

    private fun appendPoint(x: Float, y: Float) {
        if (queuedPoints.size >= MAX_QUEUED_POINTS) queuedPoints.removeFirst()
        queuedPoints.addLast(PointF(x, y))
    }

    private fun dispatchNextSegment() {
        if (isDispatching) return
        val previous = activeStroke ?: return
        if (queuedPoints.isEmpty()) return

        val point = queuedPoints.removeFirst()
        var targetX = point.x
        var targetY = point.y
        if (abs(targetX - lastX) < 0.05f && abs(targetY - lastY) < 0.05f) {
            targetX = lastX + 0.2f
        }
        val path = Path().apply {
            moveTo(lastX, lastY)
            lineTo(targetX, targetY)
        }
        val willContinue = !(endingStroke && queuedPoints.isEmpty())
        val next = try {
            previous.continueStroke(path, 0, SEGMENT_DURATION_MS, willContinue)
        } catch (error: Exception) {
            Log.w(TAG, "Tidak dapat melanjutkan gesture", error)
            resetStroke()
            return
        }
        activeStroke = next
        lastX = targetX
        lastY = targetY
        dispatching(next)
    }

    private fun dispatching(stroke: GestureDescription.StrokeDescription) {
        isDispatching = true
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                isDispatching = false
                if (!stroke.willContinue()) {
                    resetStroke()
                } else {
                    dispatchNextSegment()
                }
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.d(TAG, "Gesture dibatalkan oleh sistem/perangkat")
                resetStroke()
            }
        }, mainHandler)
        if (!accepted) {
            Log.w(TAG, "dispatchGesture menolak gesture")
            resetStroke()
        }
    }

    private fun displaySize(): Pair<Int, Int> {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        return Pair(max(1, metrics.widthPixels), max(1, metrics.heightPixels))
    }

    private fun resetStroke() {
        queuedPoints.clear()
        activeStroke = null
        isDispatching = false
        endingStroke = false
    }

    companion object {
        private const val TAG = "StylusGesture"
        private const val SEGMENT_DURATION_MS = 16L
        private const val MAX_QUEUED_POINTS = 24
        @Volatile private var instance: StylusAccessibilityService? = null

        fun enqueueRemoteTouch(action: String, x: Float, y: Float, pressure: Float) {
            val service = instance ?: return
            service.mainHandler.post {
                service.receive(action, x, y, pressure.coerceIn(0f, 1f))
            }
        }
    }
}
