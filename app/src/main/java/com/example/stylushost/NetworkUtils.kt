package com.example.stylushost

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {
    /**
     * Mengambil IPv4 lokal. Bekerja untuk Wi-Fi client (wlan0) maupun saat HP
     * menjadi hotspot (ap0 / wlan1 / swlan0 / rndis0 untuk USB tethering).
     */
    fun getLocalIpAddress(): String? {
        val candidates = ArrayList<Pair<Int, String>>()
        val ifaces = try {
            NetworkInterface.getNetworkInterfaces()?.toList()
        } catch (e: Exception) { null } ?: return null

        for (nif in ifaces) {
            try {
                if (!nif.isUp || nif.isLoopback) continue
                val name = nif.name.lowercase()
                for (addr in nif.inetAddresses.toList()) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                        val prio = when {
                            name.startsWith("wlan") || name.startsWith("swlan") || name.startsWith("ap") -> 0
                            name.startsWith("eth") || name.startsWith("rndis") -> 1
                            else -> 2
                        }
                        addr.hostAddress?.let { candidates.add(prio to it) }
                    }
                }
            } catch (_: Exception) { }
        }
        return candidates.minByOrNull { it.first }?.second
    }
}
