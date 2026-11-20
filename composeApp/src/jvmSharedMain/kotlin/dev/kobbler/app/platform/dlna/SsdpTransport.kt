package dev.kobbler.app.platform.dlna

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal interface SsdpTransport : AutoCloseable {
    suspend fun search(message: String)

    /** Every datagram that reaches this device: search replies and the group's NOTIFYs. */
    val datagrams: Flow<String>
}

/**
 * One search socket per LAN interface, so the M-SEARCH goes out of each one rather than
 * whichever the default route picks — on a desktop that is often a container bridge.
 */
internal class UdpSsdpTransport private constructor(
    private val searchSockets: List<DatagramSocket>,
    private val notifySocket: MulticastSocket?,
) : SsdpTransport {
    private val group = InetSocketAddress(InetAddress.getByName(SsdpGroup), SsdpPort)

    override suspend fun search(message: String) = withContext(Dispatchers.IO) {
        val bytes = message.encodeToByteArray()
        searchSockets.forEach { socket -> runCatching { socket.send(DatagramPacket(bytes, bytes.size, group)) } }
    }

    override val datagrams: Flow<String> = channelFlow {
        (searchSockets + listOfNotNull(notifySocket)).forEach { socket ->
            launch(Dispatchers.IO) {
                val buffer = ByteArray(4096)
                while (isActive && !socket.isClosed) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    } catch (_: Exception) {
                        break
                    }
                    send(packet.data.decodeToString(0, packet.length))
                }
            }
        }
        awaitClose()
    }

    override fun close() {
        searchSockets.forEach { runCatching { it.close() } }
        notifySocket?.let { runCatching { it.close() } }
    }

    companion object {
        private const val ReceiveTimeoutMs = 1000

        fun open(): UdpSsdpTransport? = runCatching {
            val lan = lanInterfaces()
            val search = lan.mapNotNull { (_, address) ->
                runCatching { DatagramSocket(InetSocketAddress(address, 0)) }.getOrNull()
            }.ifEmpty { listOf(DatagramSocket()) }
            search.forEach { it.soTimeout = ReceiveTimeoutMs }
            UdpSsdpTransport(search, notifyListener(lan.map { it.first }))
        }.getOrNull()

        private fun lanInterfaces(): List<Pair<NetworkInterface, Inet4Address>> =
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { runCatching { it.isUp && !it.isLoopback && it.supportsMulticast() }.getOrDefault(false) }
                .flatMap { iface -> iface.inetAddresses.toList().filterIsInstance<Inet4Address>().map { iface to it } }

        // NOTIFYs only reach a socket bound to 1900, which another SSDP stack on this
        // machine may hold; searching every minute covers for not hearing them.
        private fun notifyListener(interfaces: List<NetworkInterface>): MulticastSocket? = runCatching {
            MulticastSocket(SsdpPort).apply {
                soTimeout = ReceiveTimeoutMs
                val group = InetSocketAddress(InetAddress.getByName(SsdpGroup), SsdpPort)
                interfaces.distinct().forEach { runCatching { joinGroup(group, it) } }
            }
        }.getOrNull()
    }
}
