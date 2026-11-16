package com.example.stylushost

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : Activity() {

    private val REQUEST_CAPTURE = 1001
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }

        val ipText = TextView(this).apply {
            text = "IP Server HP:\n${getLocalIpAddress()}:8080"
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 32)
        }

        statusText = TextView(this).apply {
            text = "1. Aktifkan Izin Aksesibilitas dulu\n2. Lalu tekan Mulai Screen Cast"
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 48)
        }

        val btnAcc = Button(this).apply {
            text = "1. Buka Pengaturan Aksesibilitas"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        val btnStart = Button(this).apply {
            text = "2. Mulai Screen Cast (Mulai Server)"
            setOnClickListener {
                val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                startActivityForResult(pm.createScreenCaptureIntent(), REQUEST_CAPTURE)
            }
        }

        val btnStop = Button(this).apply {
            text = "Hentikan Server"
            setOnClickListener {
                stopService(Intent(this@MainActivity, ScreenCastService::class.java))
                statusText.text = "Server Dihentikan"
            }
        }

        layout.addView(ipText)
        layout.addView(statusText)
        layout.addView(btnAcc)
        layout.addView(btnStart)
        layout.addView(btnStop)
        setContentView(layout)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CAPTURE && resultCode == RESULT_OK && data != null) {
            val serviceIntent = Intent(this, ScreenCastService::class.java).apply {
                putExtra("RESULT_CODE", resultCode)
                putExtra("DATA", data)
            }
            startForegroundService(serviceIntent)
            statusText.text = "SERVER AKTIF!\nSekarang buka ibisPaint X di HP dalam posisi Landscape, lalu sambungkan Tablet."
        }
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (intf in interfaces) {
                for (addr in intf.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: "192.168.43.1"
                    }
                }
            }
        } catch (_: Exception) {}
        return "192.168.43.1"
    }
}
