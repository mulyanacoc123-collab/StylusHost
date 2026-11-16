package com.example.stylushost

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer

class ScreenCastService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var wsServer: HostWsServer? = null
    private var bgThread: HandlerThread? = null
    private var lastFrameTime = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification()
        val resultCode = intent?.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED) ?: return START_NOT_STICKY
        val data = intent.getParcelableExtra<Intent>("DATA") ?: return START_NOT_STICKY

        val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = pm.getMediaProjection(resultCode, data)

        wsServer = HostWsServer(8080).apply {
            isReuseAddr = true
            start()
        }

        startCapture()
        return START_NOT_STICKY
    }

    private fun startForegroundNotification() {
        val channelId = "stylus_host_channel"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(channelId, "StylusHost Screen Cast", NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannel(channel)

        val notification = Notification.Builder(this, channelId)
            .setContentTitle("StylusHost Aktif")
            .setContentText("Memancarkan layar HP ke Tablet...")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .build()
        startForeground(101, notification)
    }

    private fun startCapture() {
        bgThread = HandlerThread("ScreenCapture").apply { start() }
        val handler = Handler(bgThread!!.looper)

        // Resolusi 720p Landscape agar super ringan di Tablet Android 7
        val width = 1280
        val height = 720
        val density = resources.displayMetrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "StylusDisplay", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, handler
        )

        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            val now = System.currentTimeMillis()
            // Batasi ~25 FPS (40ms per frame) agar Wi-Fi lokal tidak macet
            if (now - lastFrameTime < 40) {
                image.close()
                return@setOnImageAvailableListener
            }
            lastFrameTime = now

            try {
                if (wsServer?.connections?.isNotEmpty() == true) {
                    val planes = image.planes
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * width

                    val bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
                    bitmap.copyPixelsFromBuffer(buffer)
                    val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)

                    val out = ByteArrayOutputStream()
                    cropped.compress(Bitmap.CompressFormat.JPEG, 60, out)
                    val jpegBytes = out.toByteArray()

                    for (conn in wsServer!!.connections) {
                        if (conn.isOpen) conn.send(ByteBuffer.wrap(jpegBytes))
                    }
                    cropped.recycle()
                    bitmap.recycle()
                }
            } catch (_: Exception) {
            } finally {
                image.close()
            }
        }, handler)
    }

    override fun onDestroy() {
        super.onDestroy()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        wsServer?.stop()
        bgThread?.quitSafely()
    }

    inner class HostWsServer(port: Int) : WebSocketServer(InetSocketAddress(port)) {
        override fun onOpen(conn: WebSocket?, handshake: ClientHandshake?) {}
        override fun onClose(conn: WebSocket?, code: Int, reason: String?, remote: Boolean) {}
        override fun onError(conn: WebSocket?, ex: Exception?) {}
        override fun onStart() {}

        override fun onMessage(conn: WebSocket?, message: String?) {
            try {
                val json = JSONObject(message ?: return)
                val action = json.getString("action")
                val x = json.getDouble("x").toFloat()
                val y = json.getDouble("y").toFloat()
                StylusAccessibilityService.instance?.handleTouch(action, x, y)
            } catch (_: Exception) {}
        }
    }
}
