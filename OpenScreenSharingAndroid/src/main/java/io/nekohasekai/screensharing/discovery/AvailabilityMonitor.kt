package io.nekohasekai.screensharing.discovery

import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.nekohasekai.screensharing.App
import io.nekohasekai.screensharing.model.ComputerAddress
import io.nekohasekai.screensharing.model.PreferenceStore
import io.nekohasekai.screensharing.model.RemoteProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.FileDescriptor
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

enum class ComputerAvailability {
    Available,
    Unavailable,
}

// With its blacklist enabled, TigerVNC counts banner-only connections until authentication succeeds
// (tigervnc 086c692, common/rfb/Blacklist.cxx:52-89, common/rfb/VNCServerST.cxx:141-161,
// common/rfb/VNCServerST.cxx:741-745).
class AvailabilityMonitor(private val nearby: NearbyBrowser) {
    private val availability = mutableStateMapOf<ComputerAddress, ComputerAvailability>()
    private var servers by mutableStateOf(emptyMap<ComputerAddress, RemoteProtocol>())
    private val probes = mutableMapOf<ComputerAddress, Job>()
    private var activeClients = 0
    private val pingedServers = PreferenceStore.value<List<String>>(PINGED_SERVERS_KEY).orEmpty().toMutableSet()
    private val connectivity by lazy { App.instance.getSystemService(ConnectivityManager::class.java) }
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var network: Network? = null

    private val silence: ComputerAvailability?
        get() = if (LocalNetworkAccess.permission.isGranted) ComputerAvailability.Unavailable else null

    fun availability(address: ComputerAddress): ComputerAvailability? {
        if (servers[address] == RemoteProtocol.Vnc && nearby.hasCurrentService(address, RemoteProtocol.Vnc)) {
            return ComputerAvailability.Available
        }
        if (servers[address] == RemoteProtocol.Vnc && address.isLocal && nearby.lacksHost(address)) {
            return ComputerAvailability.Unavailable
        }
        return availability[address]
    }

    fun start() {
        activeClients += 1
        if (activeClients != 1) return
        network = connectivity.activeNetwork
        val handler = Handler(Looper.getMainLooper())
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (Looper.myLooper() !== handler.looper) {
                    handler.post { onAvailable(network) }
                    return
                }
                if (networkCallback !== this || network == this@AvailabilityMonitor.network) return
                this@AvailabilityMonitor.network = network
                probeAll()
            }

            override fun onLost(network: Network) {
                if (Looper.myLooper() !== handler.looper) {
                    handler.post { onLost(network) }
                    return
                }
                if (networkCallback !== this || network != this@AvailabilityMonitor.network) return
                this@AvailabilityMonitor.network = null
                servers.keys.forEach { probe(it) }
            }
        }
        networkCallback = callback
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            connectivity.registerDefaultNetworkCallback(callback, handler)
        } else {
            connectivity.registerDefaultNetworkCallback(callback)
        }
        probeAll()
    }

    fun stop() {
        activeClients = maxOf(0, activeClients - 1)
        if (activeClients != 0) return
        networkCallback?.let { connectivity.unregisterNetworkCallback(it) }
        networkCallback = null
        network = null
        probes.values.forEach { it.cancel() }
        probes.clear()
        availability.clear()
    }

    fun retry() {
        if (activeClients > 0) probeAll()
    }

    fun update(servers: Map<ComputerAddress, RemoteProtocol>) {
        for (address in this.servers.keys - servers.keys) {
            probes.remove(address)?.cancel()
            availability.remove(address)
        }
        val changed = servers.keys.filter { this.servers[it] != servers[it] }
        this.servers = servers
        if (activeClients == 0) return
        for (address in changed) {
            availability.remove(address)
            probe(address)
        }
    }

    private fun probeAll() {
        for (address in servers.keys) {
            availability.remove(address)
            probe(address)
        }
    }

    private fun probe(address: ComputerAddress) {
        probes.remove(address)?.cancel()
        if (network == null) {
            availability[address] = ComputerAvailability.Unavailable
            return
        }
        probes[address] = App.scope.launch {
            while (true) {
                val result = check(address)
                ensureActive()
                if (availability[address] != result) {
                    if (result == null) availability.remove(address) else availability[address] = result
                }
                delay(REFRESH_INTERVAL_MILLIS)
            }
        }
    }

    private suspend fun check(address: ComputerAddress): ComputerAvailability? {
        if (servers[address] == RemoteProtocol.Rdp) {
            return if (AvailabilityProbe.check(address, RemoteProtocol.Rdp) == AvailabilityProbe.Answer.Nothing) silence else ComputerAvailability.Available
        }
        if (nearby.hasCurrentService(address, RemoteProtocol.Vnc)) {
            return ComputerAvailability.Available
        }
        if (address.isLocal && nearby.lacksHost(address)) {
            return ComputerAvailability.Unavailable
        }
        if (address.serverKey in pingedServers) {
            return PingProbe.check(address.host)
        }
        return when (AvailabilityProbe.check(address, RemoteProtocol.Vnc)) {
            AvailabilityProbe.Answer.Mac -> ComputerAvailability.Available
            AvailabilityProbe.Answer.OtherServer -> {
                rememberPingedServer(address)
                ComputerAvailability.Available
            }
            AvailabilityProbe.Answer.Nothing -> silence
        }
    }

    private fun rememberPingedServer(address: ComputerAddress) {
        if (!pingedServers.add(address.serverKey)) return
        PreferenceStore.set(PINGED_SERVERS_KEY, pingedServers.sorted())
    }

    companion object {
        val shared by lazy { AvailabilityMonitor(NearbyBrowser.shared) }

        private const val PINGED_SERVERS_KEY = "availabilityPingedServers"
        private const val REFRESH_INTERVAL_MILLIS = 1000L
    }
}

private val ComputerAddress.isLocal: Boolean
    get() {
        if (bonjourServiceName != null) return true
        val lowered = host.lowercase()
        return lowered.endsWith(".local") || lowered.endsWith(".local.")
    }

private val ComputerAddress.serverKey: String
    get() = "${host.lowercase()}:$port"

object AvailabilityProbe {
    enum class Answer {
        // Apple's Screen Sharing, which announces RFB 3.889 (screensharingd 756.36.5.2, macOS 27.0.1 26A434,
        // UUID 44908B06-F788-3822-ABC4-2285001F6A8D, sub_10002fcb4 0x10003061c..0x10003062c).
        Mac,
        OtherServer,
        Nothing,
    }

    private const val CONNECTION_TIMEOUT_MILLIS = 15_000
    private const val BANNER_LENGTH = 12

    suspend fun check(address: ComputerAddress, remoteProtocol: RemoteProtocol): Answer = suspendCancellableCoroutine { continuation ->
        val socket = Socket()
        continuation.invokeOnCancellation { runCatching { socket.close() } }
        Dispatchers.IO.asExecutor().execute {
            val answer = try {
                socket.connect(InetSocketAddress(address.host, address.port), CONNECTION_TIMEOUT_MILLIS)
                if (remoteProtocol == RemoteProtocol.Rdp) {
                    Answer.OtherServer
                } else {
                    socket.soTimeout = CONNECTION_TIMEOUT_MILLIS
                    val input = socket.getInputStream()
                    val banner = ByteArray(BANNER_LENGTH)
                    var length = 0
                    while (length < banner.size) {
                        val read = input.read(banner, length, banner.size - length)
                        if (read < 0) break
                        length += read
                    }
                    answer(banner.decodeToString(0, length))
                }
            } catch (_: Exception) {
                Answer.Nothing
            } finally {
                runCatching { socket.close() }
            }
            continuation.resume(answer)
        }
    }

    private fun answer(banner: String): Answer = when {
        !banner.startsWith("RFB ") -> Answer.Nothing
        banner.startsWith("RFB 003.889") -> Answer.Mac
        else -> Answer.OtherServer
    }
}

object PingProbe {
    private const val RETRY_INTERVAL_MILLIS = 1000L
    private const val REQUEST_LENGTH = 64
    private const val SILENCE_TIMEOUT_MILLIS = 5000L
    private const val IP_RECVERR = 11
    private const val IPV6_RECVERR = 25
    private const val ICMP_ECHOREPLY = 0
    private const val ICMP_ECHO = 8
    private const val ICMP6_ECHO_REQUEST = 128
    private const val ICMP6_ECHO_REPLY = 129

    private val sequence = AtomicInteger()

    suspend fun check(host: String): ComputerAvailability? = runInterruptible(Dispatchers.IO) { Attempt(host).run() }

    private class Attempt(private val host: String) {
        private class Probe(val descriptor: FileDescriptor, val address: InetAddress, val request: ByteArray, val sequence: Int, val isIPv6: Boolean)

        private val probes = mutableListOf<Probe>()
        private var inconclusive = false

        fun run(): ComputerAvailability? {
            val addresses = try {
                InetAddress.getAllByName(host)
            } catch (_: UnknownHostException) {
                return ComputerAvailability.Unavailable
            }
            try {
                addresses.distinct().forEach { add(it) }
                val start = SystemClock.elapsedRealtime()
                val deadline = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O && probes.any { it.isIPv6 }) start + SILENCE_TIMEOUT_MILLIS else Long.MAX_VALUE
                var nextSend = start + RETRY_INTERVAL_MILLIS
                while (!Thread.currentThread().isInterrupted) {
                    if (probes.isEmpty()) return if (inconclusive) null else ComputerAvailability.Unavailable
                    val now = SystemClock.elapsedRealtime()
                    if (now >= deadline) return null
                    if (now >= nextSend) {
                        probes.toList().forEach { send(it) }
                        nextSend = now + RETRY_INTERVAL_MILLIS
                        continue
                    }
                    val polled = probes.toList()
                    val descriptors = polled.map { probe ->
                        StructPollfd().apply {
                            fd = probe.descriptor
                            events = OsConstants.POLLIN.toShort()
                        }
                    }
                    try {
                        Os.poll(descriptors.toTypedArray(), (nextSend - now).toInt())
                    } catch (error: ErrnoException) {
                        if (error.errno == OsConstants.EINTR) continue
                        return null
                    }
                    for ((index, descriptor) in descriptors.withIndex()) {
                        if ((descriptor.revents.toInt() and (OsConstants.POLLIN or OsConstants.POLLERR)) != 0 && read(polled[index])) {
                            return ComputerAvailability.Available
                        }
                    }
                }
                return null
            } finally {
                probes.forEach { runCatching { Os.close(it.descriptor) } }
            }
        }

        private fun add(address: InetAddress) {
            val isIPv6 = address is Inet6Address
            val descriptor = try {
                if (isIPv6) {
                    Os.socket(OsConstants.AF_INET6, OsConstants.SOCK_DGRAM, OsConstants.IPPROTO_ICMPV6)
                } else {
                    Os.socket(OsConstants.AF_INET, OsConstants.SOCK_DGRAM, OsConstants.IPPROTO_ICMP)
                }
            } catch (_: ErrnoException) {
                inconclusive = true
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    if (isIPv6) {
                        Os.setsockoptInt(descriptor, OsConstants.IPPROTO_IPV6, IPV6_RECVERR, 1)
                    } else {
                        Os.setsockoptInt(descriptor, OsConstants.IPPROTO_IP, IP_RECVERR, 1)
                    }
                } catch (_: ErrnoException) {
                    runCatching { Os.close(descriptor) }
                    inconclusive = true
                    return
                }
            }
            val number = sequence.incrementAndGet() and 0xffff
            val request = ByteArray(REQUEST_LENGTH) { index -> if (index < 8) 0 else index.toByte() }
            request[0] = (if (isIPv6) ICMP6_ECHO_REQUEST else ICMP_ECHO).toByte()
            request[6] = (number shr 8).toByte()
            request[7] = number.toByte()
            val probe = Probe(descriptor, address, request, number, isIPv6)
            probes += probe
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O && !isIPv6) {
                try {
                    Os.connect(descriptor, address, 0)
                } catch (error: ErrnoException) {
                    fail(probe, error.errno)
                    return
                } catch (_: SocketException) {
                    fail(probe, 0)
                    return
                }
            }
            send(probe)
        }

        private fun send(probe: Probe) {
            try {
                Os.sendto(probe.descriptor, probe.request, 0, probe.request.size, 0, probe.address, 0)
            } catch (error: ErrnoException) {
                fail(probe, error.errno)
            } catch (_: SocketException) {
                fail(probe, 0)
            }
        }

        private fun read(probe: Probe): Boolean {
            val reply = ByteArray(2048)
            val received = try {
                Os.recvfrom(probe.descriptor, reply, 0, reply.size, 0, null)
            } catch (error: ErrnoException) {
                fail(probe, error.errno)
                return false
            } catch (_: SocketException) {
                fail(probe, 0)
                return false
            }
            if (received <= 0) {
                fail(probe, 0)
                return false
            }
            val type = if (probe.isIPv6) ICMP6_ECHO_REPLY else ICMP_ECHOREPLY
            if (received < 8 || (reply[0].toInt() and 0xff) != type) return false
            return (((reply[6].toInt() and 0xff) shl 8) or (reply[7].toInt() and 0xff)) == probe.sequence
        }

        private fun fail(probe: Probe, errno: Int) {
            when (errno) {
                OsConstants.EAGAIN, OsConstants.EINTR -> return
                OsConstants.EHOSTUNREACH, OsConstants.ENETUNREACH, OsConstants.ENETDOWN -> Unit
                else -> inconclusive = true
            }
            probes.remove(probe)
            runCatching { Os.close(probe.descriptor) }
        }
    }
}
