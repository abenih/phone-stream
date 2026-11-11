package dev.gamecontrols.link.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dev.gamecontrols.link.protocol.LanAddress
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

data class LanBinding(
    val address: Inet4Address, val prefix: Int, val interfaceName: String, val network: Network?,
) {
    fun permits(peer: InetAddress) = peer != address && LanAddress.sameSubnet(peer, address, prefix)

    fun isAvailable(context: Context): Boolean {
        val iface = NetworkInterface.getByName(interfaceName) ?: return false
        if (!iface.isUp || iface.isLoopback || iface.isPointToPoint) return false
        if (iface.interfaceAddresses.none { it.address == address && it.networkPrefixLength.toInt() == prefix }) return false
        return network == null || allowed(context.getSystemService(ConnectivityManager::class.java).getNetworkCapabilities(network))
    }

    override fun toString() = "$interfaceName: ${address.hostAddress}/$prefix" +
        if (network == null) " (existing hotspot interface)" else " (Wi-Fi / Ethernet)"

    companion object {
        private fun allowed(caps: NetworkCapabilities?): Boolean = caps != null &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
            (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))

        @Suppress("DEPRECATION")
        fun hostCandidates(context: Context): List<LanBinding> {
            val manager = context.getSystemService(ConnectivityManager::class.java)
            val known = manager.allNetworks.mapNotNull { network ->
                val name = manager.getLinkProperties(network)?.interfaceName ?: return@mapNotNull null
                Triple(name, network, allowed(manager.getNetworkCapabilities(network)))
            }
            val result = mutableListOf<LanBinding>()
            for (iface in NetworkInterface.getNetworkInterfaces().toList()) {
                if (!iface.isUp || iface.isLoopback || iface.isPointToPoint) continue
                val attached = known.filter { it.first == iface.name }
                if (attached.any { !it.third }) continue
                val network = attached.firstOrNull { it.third }?.second
                // Android does not expose the existing hotspot as a ConnectivityManager Network.
                // Only named Wi-Fi AP interfaces are eligible; no tethering or routing is changed.
                if (network == null && !iface.name.matches(Regex("(wlan|ap|swlan|softap)[0-9]+"))) continue
                for (link in iface.interfaceAddresses) {
                    val address = link.address as? Inet4Address ?: continue
                    val prefix = link.networkPrefixLength.toInt()
                    if (LanAddress.sameSubnet(address, address, prefix)) {
                        result.add(LanBinding(address, prefix, iface.name, network))
                    }
                }
            }
            return result
        }

        fun client(context: Context, host: InetAddress): LanBinding {
            return hostCandidates(context).filter { it.network != null && it.permits(host) }.singleOrNull()
                ?: error("No single on-link Wi-Fi/Ethernet path to this Host. No cellular, VPN, DNS, or routed fallback is used.")
        }
    }
}
