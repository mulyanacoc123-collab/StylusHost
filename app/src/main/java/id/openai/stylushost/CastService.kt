package id.openai.stylushost

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Collections
import kotlin.math.max
import kotlin.math.min

class CastService : Service() {
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var socketServer: WebSocketServer? = null
    private var pairingCode: String = ""
    private var lastEncodeAtMs: Long = 0L
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private var reusableBitmap: Bitmap? = null
    private val jpegBuffer = ByteArrayOutputStream(256 * 1024)
    private val authenticated = Collections.synchronizedMap(mutableMapOf<WebSocket, Boolean>())

    // Pengaturan Kualitas Dinamis (Bisa diubah via Client saat Auth)
    private var currentJpegQuality = JPEG_QUALITY_DEFAULT
    private var currentFrameIntervalMs = FRAME_INTERVAL_DEFAULT_MS

    override fun onCreate() {
        super.onCreate()
        workerThread = HandlerThread("StylusHost-Capture", android.os.Process.THREAD_PRIORITY_DISPLAY).also { it.start() }
        workerHandler = Handler(workerThread!!.looper)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_START) startProjection(intent)
        return START_NOT_STICKY
    }

    private fun startProjection(intent: Intent) {
        if (projection != null) return
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        @Suppress("DEPRECATION")
        val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            stopSelf()
            return
        }
        pairingCode = intent.getStringExtra(EXTRA_PAIRING_CODE).orEmpty()

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mediaProjection = manager.getMediaProjection(resultCode, resultData)
            projection = mediaProjection
            mediaProjection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopSelf()
                }
            }, workerHandler)

            startWebSocketServer()
            startVirtualDisplay(mediaProjection)
        } catch (error: Exception) {
            Log.e(TAG, "Tidak dapat memulai MediaProjection", error)
            stopSelf()
        }
    }

    private fun computeStreamDimensions(): Pair<Int, Int> {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        val longSide = max(metrics.widthPixels, metrics.heightPixels).toFloat()
        val shortSide = min(metrics.widthPixels, metrics.heightPixels).toFloat()
        val aspect = if (shortSide > 0f) longSide / shortSide else (16f / 9f)
        val targetWidth = TARGET_LONG_SIDE
        val targetHeight = ((targetWidth / aspect).toInt() / 2) * 2
        return Pair(targetWidth, max(480, targetHeight))
    }

    private fun startVirtualDisplay(mediaProjection: MediaProjection) {
        val (width, height) = computeStreamDimensions()
        val densityDpi = resources.displayMetrics.densityDpi
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        imageReader = reader
        reader.setOnImageAvailableListener({ source ->
            val image = try {
                source.acquireLatestImage()
            } catch (error: Exception) {
                null
            } ?: return@setOnImageAvailableListener

            val now = SystemClock.elapsedRealtime()
            if (now - lastEncodeAtMs < currentFrameIntervalMs) {
                image.close()
                return@setOnImageAvailableListener
            }

            val targets = synchronized(authenticated) {
                authenticated.filterValues { it }.keys.toList()
            }
            if (targets.isEmpty()) {
                image.close()
                return@setOnImageAvailableListener
            }

            val readyTargets = targets.filter { it.isOpen && !it.hasBufferedData() }
            if (readyTargets.isEmpty()) {
                image.close()
                return@setOnImageAvailableListener
            }

            lastEncodeAtMs = now

            try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * width
                val paddedWidth = width + max(0, rowPadding / max(1, pixelStride))

                var bmp = reusableBitmap
                if (bmp == null || bmp.isRecycled || bmp.width != paddedWidth || bmp.height != height) {
                    bmp?.recycle()
                    bmp = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
                    reusableBitmap = bmp
                }

                buffer.rewind()
                bmp.copyPixelsFromBuffer(buffer)

                val cropped = if (paddedWidth != width) {
                    Bitmap.createBitmap(bmp, 0, 0, width, height)
                } else {
                    bmp
                }

                jpegBuffer.reset()
                // Menggunakan kualitas JPEG dinamis (bisa disesuaikan lewat preferensi/client)
                cropped.compress(Bitmap.CompressFormat.JPEG, currentJpegQuality, jpegBuffer)
                val bytes = jpegBuffer.toByteArray()

                if (cropped !== bmp) cropped.recycle()

                readyTargets.forEach { socket ->
                    try {
                        socket.send(bytes)
                    } catch (_: Exception) { }
                }
            } catch (error: Exception) {
                Log.w(TAG, "Konversi frame gagal", error)
            } finally {
                image.close()
            }
        }, workerHandler)

        virtualDisplay = mediaProjection.createVirtualDisplay(
            "StylusHost-HD",
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            workerHandler
        )
    }

    private fun startWebSocketServer() {
        val server = object : WebSocketServer(InetSocketAddress("0.0.0.0", SERVER_PORT)) {
            override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
                authenticated[conn] = false
                conn.send(JSONObject().put("type", "hello").put("message", "Kirim kode pairing 4 digit").toString())
            }

            override fun onMessage(conn: WebSocket, message: String) {
                try {
                    val json = JSONObject(message)
                    if (json.optString("type") == "auth") {
                        val supplied = json.optString("token")
                        val match = MessageDigest.isEqual(
                            supplied.toByteArray(Charsets.UTF_8),
                            pairingCode.toByteArray(Charsets.UTF_8)
                        )
                        if (match && pairingCode.isNotBlank()) {
                            authenticated[conn] = true
                            
                            // Menangkap opsi kualitas/FPS opsional yang dikirim dari Client jika ada
                            if (json.has("quality")) {
                                val q = json.optInt("quality", 80)
                                currentJpegQuality = q.coerceIn(40, 95)
                            }
                            if (json.has("fps")) {
                                val fps = json.optInt("fps", 60)
                                currentFrameIntervalMs = if (fps <= 30) 33L else 16L
                            }

                            conn.send(JSONObject().put("type", "auth_ok").toString())
                        } else {
                            conn.send(JSONObject().put("type", "error").put("message", "Kode pairing salah").toString())
                            conn.close(1008, "Pairing code rejected")
                        }
                        return
                    }

                    if (authenticated[conn] != true) return
                    val action = json.optString("action")
                    val x = json.optDouble("x", -1.0).toFloat()
                    val y = json.optDouble("y", -1.0).toFloat()
                    val pressure = json.optDouble("pressure", 1.0).toFloat().coerceIn(0f, 1f)
                    if (x.isFinite() && y.isFinite() && x in 0f..1f && y in 0f..1f) {
                        StylusAccessibilityService.enqueueRemoteTouch(action, x, y, pressure)
                    }
                } catch (error: Exception) {
                    Log.w(TAG, "Pesan WebSocket tidak valid", error)
                }
            }

            override fun onMessage(conn: WebSocket, message: ByteBuffer) = Unit

            override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
                authenticated.remove(conn)
            }

            override fun onError(conn: WebSocket?, ex: Exception?) {
                if (conn != null) authenticated.remove(conn)
            }

            override fun onStart() {
                Log.i(TAG, "WebSocket aktif di port $SERVER_PORT dengan dukungan kustom kualitas & FPS")
            }
        }
        server.isTcpNoDelay = true
        socketServer = server
        server.connectionLostTimeout = 15
        server.start()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "StylusHost Screen Cast",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifikasi saat layar HP dikirim ke tablet"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, CastService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("StylusHost 4-Digit & Kualitas Aktif")
            .setContentText("Port $SERVER_PORT")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopIntent)
            .build()
    }

    override fun onDestroy() {
        workerHandler?.removeCallbacksAndMessages(null)
        try { virtualDisplay?.release() } catch (_: Exception) { }
        try { imageReader?.close() } catch (_: Exception) { }
        try { projection?.stop() } catch (_: Exception) { }
        projection = null
        try { reusableBitmap?.recycle() } catch (_: Exception) { }
        reusableBitmap = null
        try { socketServer?.stop(1000) } catch (_: Exception) { }
        socketServer = null
        authenticated.clear()
        workerThread?.quitSafely()
        workerThread = null
        workerHandler = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "StylusHost"
        const val SERVER_PORT = 8080
        const val ACTION_START = "id.openai.stylushost.action.START"
        const val ACTION_STOP = "id.openai.stylushost.action.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_PAIRING_CODE = "pairing_code"
        private const val CHANNEL_ID = "stylushost_capture"
        private const val NOTIFICATION_ID = 808
        private const val TARGET_LONG_SIDE = 1600
        private const val JPEG_QUALITY_DEFAULT = 80
        private const val FRAME_INTERVAL_DEFAULT_MS = 16L // ~60 FPS
    }
}
