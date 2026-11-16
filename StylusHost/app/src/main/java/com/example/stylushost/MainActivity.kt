package com.example.stylushost

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Size
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.example.stylushost.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val annotation = AnnotationLayer()
    private val composer = FrameComposer(annotation)
    private lateinit var analysisExecutor: ExecutorService

    @Volatile private var server: StreamServer? = null
    private var lastFrameNs = 0L

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else Toast.makeText(this, "Izin kamera diperlukan", Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        analysisExecutor = Executors.newSingleThreadExecutor()

        binding.btnToggle.setOnClickListener {
            if (server == null) startServer() else stopServer()
        }
        binding.btnClear.setOnClickListener { annotation.clear() }
        binding.tvAddress.setOnClickListener { refreshAddress() }
        refreshAddress()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) startCamera() else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    // ---------------------------------------------------------------- Server

    private fun startServer() {
        refreshAddress()
        val s = StreamServer(PORT, object : StreamServer.Listener {
            override fun onClientCountChanged(count: Int) = runOnUiThread {
                binding.tvStatus.text = "Server aktif • $count client terhubung"
            }

            override fun onCommand(cmd: StylusCommand) {
                when (cmd) {
                    is StylusCommand.Point -> annotation.onStylus(cmd.event, cmd.color)
                    StylusCommand.Clear -> annotation.clear()
                    StylusCommand.Undo -> annotation.undo()
                }
            }

            override fun onServerError(e: Exception) = runOnUiThread {
                binding.tvStatus.text = "Error: ${e.message}"
                server = null
                binding.btnToggle.text = "Mulai Server"
            }
        })
        s.start()
        server = s
        binding.btnToggle.text = "Hentikan Server"
        binding.tvStatus.text = "Server aktif • 0 client terhubung"
    }

    private fun stopServer() {
        val s = server ?: return
        server = null
        Thread { try { s.stop(1000) } catch (_: Exception) {} }.start() // stop() memblokir
        binding.btnToggle.text = "Mulai Server"
        binding.tvStatus.text = "Server berhenti"
    }

    private fun refreshAddress() {
        val ip = NetworkUtils.getLocalIpAddress()
        binding.tvAddress.text =
            if (ip != null) "ws://$ip:$PORT" else "Tidak ada jaringan (aktifkan Wi-Fi / Hotspot)"
    }

    // ---------------------------------------------------------------- Kamera

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()

            val resolution = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(1280, 720),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                    )
                ).build()

            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(resolution)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(analysisExecutor) { proxy -> processFrame(proxy) }

            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    /** Berjalan di thread analisis: frame -> komposit anotasi -> JPEG -> broadcast. */
    private fun processFrame(proxy: ImageProxy) {
        try {
            val now = System.nanoTime()
            if (now - lastFrameNs < FRAME_INTERVAL_NS) return // batasi ~30 fps
            lastFrameNs = now

            val bitmap = proxy.toBitmap()
            val jpeg = composer.compose(bitmap, proxy.imageInfo.rotationDegrees)
            bitmap.recycle()

            composer.outputBitmap?.let { binding.previewView.updateFrame(it) }
            server?.broadcastFrame(jpeg)
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "processFrame gagal", e)
        } finally {
            proxy.close()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopServer()
        analysisExecutor.shutdown()
    }

    companion object {
        const val PORT = 8080
        private const val FRAME_INTERVAL_NS = 33_000_000L
    }
}
