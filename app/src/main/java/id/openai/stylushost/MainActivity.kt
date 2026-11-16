package id.openai.stylushost

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom

class MainActivity : AppCompatActivity() {
    private lateinit var ipAddressText: TextView
    private lateinit var pairingCodeText: TextView
    private lateinit var statusText: TextView
    private val pairingCode: String by lazy {
        val value = SecureRandom().nextInt(90_000_000) + 10_000_000
        value.toString()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Screen casting can still run if notification permission is denied. */ }

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, CastService::class.java).apply {
                action = CastService.ACTION_START
                putExtra(CastService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(CastService.EXTRA_RESULT_DATA, result.data)
                putExtra(CastService.EXTRA_PAIRING_CODE, pairingCode)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
            statusText.text = "Status: memulai screen cast. Server WebSocket port 8080."
        } else {
            statusText.text = "Status: izin screen capture dibatalkan."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ipAddressText = findViewById(R.id.ipAddressText)
        pairingCodeText = findViewById(R.id.pairingCodeText)
        statusText = findViewById(R.id.statusText)
        pairingCodeText.text = pairingCode
        refreshIpAddress()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        findViewById<Button>(R.id.accessibilityButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "Pilih dan aktifkan StylusHost Remote Stylus", Toast.LENGTH_LONG).show()
        }

        findViewById<Button>(R.id.startButton).setOnClickListener {
            val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            captureLauncher.launch(projectionManager.createScreenCaptureIntent())
        }

        findViewById<Button>(R.id.stopButton).setOnClickListener {
            stopService(Intent(this, CastService::class.java))
            statusText.text = "Status: screen cast dihentikan."
        }
    }

    override fun onResume() {
        super.onResume()
        refreshIpAddress()
        val enabled = isAccessibilityServiceEnabled()
        if (enabled) {
            statusText.text = "Status: Accessibility aktif. Siap memulai screen cast."
        }
    }

    private fun refreshIpAddress() {
        val addresses = try {
            val all = NetworkInterface.getNetworkInterfaces()
            val found = mutableListOf<String>()
            while (all.hasMoreElements()) {
                val network = all.nextElement()
                if (!network.isUp || network.isLoopback) continue
                val entries = network.inetAddresses
                while (entries.hasMoreElements()) {
                    val address = entries.nextElement()
                    if (address is Inet4Address && !address.isLoopbackAddress && address.isSiteLocalAddress) {
                        found.add(address.hostAddress ?: "")
                    }
                }
            }
            found.distinct().sorted().filter { it.isNotBlank() }
        } catch (_: Exception) {
            emptyList()
        }
        ipAddressText.text = if (addresses.isEmpty()) {
            "Tidak terdeteksi — periksa Wi-Fi"
        } else {
            addresses.joinToString("\n")
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = ComponentName(this, StylusAccessibilityService::class.java)
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        while (splitter.hasNext()) {
            if (splitter.next().equals(expected.flattenToString(), ignoreCase = true)) return true
        }
        return false
    }
}
