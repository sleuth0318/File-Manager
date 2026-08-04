package com.goodwy.filemanager.ftp

import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket

object FtpNetworkUtils {
    fun findAvailablePort(): Int {
        ServerSocket(0).use { socket ->
            socket.reuseAddress = true
            return socket.localPort
        }
    }

    fun getLocalIpAddress(): String {
        return try {
            val candidates = NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { networkInterface ->
                    networkInterface.inetAddresses.toList()
                        .filterIsInstance<Inet4Address>()
                        .filterNot { it.isLoopbackAddress || it.isLinkLocalAddress }
                        .map { address -> NetworkAddressCandidate(networkInterface.name, address.hostAddress ?: "") }
                }
                .filter { it.address.isNotEmpty() }

            candidates.firstOrNull { it.interfaceName.startsWith("wlan", ignoreCase = true) }?.address
                ?: candidates.firstOrNull { it.interfaceName.startsWith("ap", ignoreCase = true) }?.address
                ?: candidates.firstOrNull { it.interfaceName.startsWith("rndis", ignoreCase = true) }?.address
                ?: candidates.firstOrNull { it.address.startsWith("192.168.") }?.address
                ?: candidates.firstOrNull { it.address.startsWith("10.") }?.address
                ?: candidates.firstOrNull { it.address.startsWith("172.") }?.address
                ?: candidates.firstOrNull()?.address
                ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    private data class NetworkAddressCandidate(
        val interfaceName: String,
        val address: String
    )
}

private fun <T> java.util.Enumeration<T>.toList(): List<T> {
    val result = ArrayList<T>()
    while (hasMoreElements()) {
        result.add(nextElement())
    }
    return result
}
