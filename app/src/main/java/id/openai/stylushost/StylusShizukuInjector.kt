package id.openai.stylushost

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import rikka.shizuku.Shizuku
import java.lang.reflect.Method

object StylusShizukuInjector {
    private const val TAG = "StylusShizuku"
    private val mainHandler = Handler(Looper.getMainLooper())
    
    private var inputManager: Any? = null
    private var injectInputEventMethod: Method? = null

    private var downTime = 0L

    // Inisialisasi refleksi untuk mengambil IInputManager via Shizuku binder
    fun initShizuku() {
        try {
            if (Shizuku.isPreV11() || Shizuku.checkSelfPermission() != Shizuku.PERMISSION_GRANTED) {
                Log.w(TAG, "Shizuku belum memiliki izin atau belum aktif")
                return
            }

            // Mendapatkan system service "input" menggunakan binder Shizuku
            val binder = rikka.shizuku.ShizukuBinderWrapper(
                rikka.core.os.ServiceManager.getService("input")
            )
            val stubClass = Class.forName("android.hardware.input.IInputManager\$Stub")
            val asInterfaceMethod = stubClass.getMethod("asInterface", android.os.IBinder::class.java)
            inputManager = asInterfaceMethod.invoke(null, binder)

            // Menyiapkan method injectInputEvent
            injectInputEventMethod = inputManager?.javaClass?.getMethod(
                "injectInputEvent",
                InputEvent::class.java,
                Int::class.java
            )
            Log.i(TAG, "Shizuku InputManager berhasil diinisialisasi")
        } catch (e: Exception) {
            Log.e(TAG, "Gagal menginisialisasi Shizuku InputManager", e)
        }
    }

    fun injectPen(actionStr: String, x: Float, y: Float, pressure: Float) {
        if (inputManager == null || injectInputEventMethod == null) {
            initShizuku()
            if (inputManager == null) return
        }

        val action = when (actionStr.lowercase()) {
            "down" -> MotionEvent.ACTION_DOWN
            "move" -> MotionEvent.ACTION_MOVE
            "up", "cancel" -> MotionEvent.ACTION_UP
            else -> return
        }

        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) {
            downTime = now
        }

        try {
            val props = arrayOf(
                MotionEvent.PointerProperties().apply {
                    id = 0
                    toolType = MotionEvent.TOOL_TYPE_STYLUS
                }
            )
            val coords = arrayOf(
                MotionEvent.PointerCoords().apply {
                    this.x = x
                    this.y = y
                    this.pressure = pressure.coerceIn(0f, 1f) // Nilai pressure 0.0 - 1.0
                    this.size = 1f
                }
            )

            val ev = MotionEvent.obtain(
                downTime,
                now,
                action,
                1,
                props,
                coords,
                0,
                0,
                1f,
                1f,
                0,
                0,
                InputDevice.SOURCE_STYLUS,
                0
            )

            // INJECT_INPUT_EVENT_MODE_ASYNC = 0 (agar tidak memblokir thread utama)
            injectInputEventMethod?.invoke(inputManager, ev, 0)
            ev.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "Gagal menyuntikkan event stylus", e)
        }
    }
}
