package id.openai.stylushost

import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
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
    
    private var inputManager: Any? = null
    private var injectInputEventMethod: Method? = null
    private var downTime = 0L

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.i(TAG, "Shizuku binder terhubung!")
        initShizuku()
    }

    init {
        // Memastikan listener binder Shizuku aktif
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        } catch (e: Exception) {
            Log.e(TAG, "Gagal mendaftarkan Shizuku listener", e)
        }
    }

    private fun initShizuku() {
        try {
            if (Shizuku.isPreV11() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Shizuku belum memiliki izin atau belum aktif")
                return
            }

            // Menggunakan refleksi ServiceManager Android via Shizuku binder
            val smClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = smClass.getMethod("getService", String::class.java)
            val binder = getServiceMethod.invoke(null, "input") as? IBinder

            if (binder == null) {
                Log.e(TAG, "Gagal mendapatkan binder input service")
                return
            }

            val stubClass = Class.forName("android.hardware.input.IInputManager\$Stub")
            val asInterfaceMethod = stubClass.getMethod("asInterface", IBinder::class.java)
            inputManager = asInterfaceMethod.invoke(null, binder)

            injectInputEventMethod = inputManager?.javaClass?.getMethod(
                "injectInputEvent",
                InputEvent::class.java,
                Int::class.java
            )
            Log.i(TAG, "Shizuku InputManager & inject method berhasil diinisialisasi!")
        } catch (e: Exception) {
            Log.e(TAG, "Gagal menginisialisasi Shizuku InputManager", e)
        }
    }

    fun injectPen(actionStr: String, x: Float, y: Float, pressure: Float) {
        if (inputManager == null || injectInputEventMethod == null) {
            initShizuku()
            if (inputManager == null) {
                Log.w(TAG, "InputManager masih null, injeksi dibatalkan")
                return
            }
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
                    this.pressure = pressure.coerceIn(0f, 1f)
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

            // INJECT_INPUT_EVENT_MODE_ASYNC = 0
            injectInputEventMethod?.invoke(inputManager, ev, 0)
            ev.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "Gagal menyuntikkan event stylus", e)
        }
    }
}
