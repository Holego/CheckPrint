package io.github.holego.checkprint.net

import java.net.Inet4Address
import java.net.NetworkInterface

/** Enumerates the phone's own IPv4 addresses so the scan range can be pre-filled. */
object LocalNetwork {
    data class Iface(val name: String, val ip: String, val prefix: Int) {
        override fun toString() = "$name $ip/$prefix"
    }

    /** Wi-Fi first, then Ethernet, hotspot, everything else; mobile-data interfaces are dropped. */
    fun interfaces(): List<Iface> = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { nif ->
                nif.interfaceAddresses.mapNotNull { ia ->
                    val addr = ia.address as? Inet4Address ?: return@mapNotNull null
                    val host = addr.hostAddress ?: return@mapNotNull null
                    if (addr.isLinkLocalAddress) return@mapNotNull null
                    Iface(nif.name, host, ia.networkPrefixLength.toInt())
                }
            }
            .filter { priority(it.name) < 9 }
            .sortedBy { priority(it.name) }
    } catch (e: Exception) {
        emptyList()
    }

    private fun priority(name: String): Int = when {
        name.startsWith("wlan") -> 0
        name.startsWith("eth") -> 1
        name.startsWith("ap") || name.startsWith("swlan") || name.startsWith("wifi") -> 2
        name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("pdp") -> 9
        else -> 5
    }

    /**
     * Default range: the /24 around the phone's own address. Networks smaller than /24 keep their
     * real prefix; anything larger is clipped to /24 so the first scan finishes in seconds.
     */
    fun defaultRange(): String? = interfaces().firstOrNull()?.let { iface ->
        IpRange.cidrOf(iface.ip, if (iface.prefix in 25..30) iface.prefix else 24)
    }
}
