package io.nekohasekai.screensharing.discovery

import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import io.nekohasekai.screensharing.App
import io.nekohasekai.screensharing.R
import io.nekohasekai.screensharing.app.MessageError
import io.nekohasekai.screensharing.model.ComputerAddress
import io.nekohasekai.screensharing.model.parseNumericAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random

private const val TAG = "discovery"

private val nsd by lazy { App.instance.getSystemService(NsdManager::class.java) }

class ServiceResolver(
    private val name: String,
    private val type: String = VNC_SERVICE_TYPE,
    private val network: Network? = null,
    private val resolved: (host: String, port: Int) -> Unit,
    hostName: ((String) -> Unit)? = null,
    addresses: ((Set<InetAddress>) -> Unit)? = null,
    model: ((String) -> Unit)? = null,
    serviceModel: ((String?) -> Unit)? = null,
    private val failed: ((Exception) -> Unit)? = null,
) {
    private val hostNameChanged = hostName
    private val addressesChanged = addresses
    private val modelChanged = model
    private val serviceModelChanged = serviceModel
    private var cancelled = false
    private var callback: NsdManager.ServiceInfoCallback? = null
    private var legacyRequest: LegacyResolution.Request? = null
    private var modelLookup: Job? = null
    private var hostNameLookup: Job? = null
    private var currentAddresses = emptySet<InetAddress>()

    fun start() {
        if (cancelled || callback != null || legacyRequest != null) return
        val info = NsdServiceInfo()
        info.serviceName = name
        info.serviceType = type
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) info.network = network
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val callback = object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                    if (this@ServiceResolver.callback === this) fail(errorCode)
                }

                override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                    if (this@ServiceResolver.callback === this) update(serviceInfo)
                }

                override fun onServiceLost() {
                }

                override fun onServiceInfoCallbackUnregistered() {
                }
            }
            this.callback = callback
            nsd.registerServiceInfoCallback(info, App.instance.mainExecutor, callback)
        } else {
            legacyRequest = LegacyResolution.enqueue(info) { serviceInfo, errorCode ->
                legacyRequest = null
                if (serviceInfo != null) update(serviceInfo) else fail(errorCode)
            }
        }
        lookUpModel()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) lookUpHostName()
    }

    @Suppress("DEPRECATION")
    private fun update(serviceInfo: NsdServiceInfo) {
        val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            serviceInfo.hostAddresses.toSet()
        } else {
            setOfNotNull(serviceInfo.host)
        }
        val hostName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            serviceInfo.hostname?.takeUnless { isLoopbackName(it) }?.let { "$it.local" }
        } else {
            null
        }
        val host = hostName
            ?: (addresses.firstOrNull { it is Inet4Address } ?: addresses.firstOrNull { !it.isLinkLocalAddress } ?: addresses.firstOrNull())?.hostAddress
        serviceModelChanged?.invoke(serviceInfo.attributes["model"]?.decodeToString()?.takeIf { it.isNotEmpty() })
        if (cancelled) return
        if (hostName != null) hostNameChanged?.invoke(hostName)
        if (cancelled) return
        if (host != null) resolved(host, serviceInfo.port)
        if (cancelled || addresses == currentAddresses) return
        currentAddresses = addresses
        addressesChanged?.invoke(addresses)
    }

    // A Mac answered a named _device-info._tcp TXT query with its model while a service browse found nothing
    // (observed on macOS 27.0.1 26A434).
    private fun lookUpModel() {
        val modelChanged = modelChanged ?: return
        modelLookup = App.scope.launch {
            val model = try {
                queryRecord(listOf(name, "_device-info", "_tcp", "local"), DNS_TYPE_TXT) { packet, data, length -> readModel(packet, data, length) }
            } catch (error: IOException) {
                Log.e(TAG, "model query failed", error)
                null
            }
            if (model != null) modelChanged(model)
        }
    }

    private fun lookUpHostName() {
        val hostNameChanged = hostNameChanged ?: return
        hostNameLookup = App.scope.launch {
            val hostName = try {
                queryRecord(listOf(name) + type.split('.') + "local", DNS_TYPE_SRV) { packet, data, _ -> readName(packet, data + 6)?.first }
            } catch (error: IOException) {
                Log.e(TAG, "host name query failed", error)
                null
            }
            if (hostName != null && !isLoopbackName(hostName)) hostNameChanged(hostName)
        }
    }

    // Windows advertises _dosvc._tcp with the SRV target localhost.local and answers mDNS address queries for it,
    // while Android's resolver never resolves localhost names (observed on Windows 11 and Android 17).
    private fun isLoopbackName(hostName: String): Boolean = hostName.substringBefore('.').equals("localhost", ignoreCase = true)

    private suspend fun <T> queryRecord(labels: List<String>, recordType: Int, read: (packet: ByteArray, data: Int, length: Int) -> T?): T? = withContext(Dispatchers.IO) {
        val id = Random.nextInt(0x10000)
        val output = ByteArrayOutputStream()
        DataOutputStream(output).run {
            writeShort(id)
            writeShort(0)
            writeShort(1)
            writeShort(0)
            writeShort(0)
            writeShort(0)
            for (label in labels) {
                val bytes = label.encodeToByteArray()
                if (bytes.isEmpty() || bytes.size > 63) return@withContext null
                writeByte(bytes.size)
                write(bytes)
            }
            writeByte(0)
            writeShort(recordType)
            writeShort(DNS_CLASS_IN)
        }
        val query = output.toByteArray()
        val owner = labels.joinToString(".")
        MulticastSocket().use { socket ->
            network?.bindSocket(socket)
            socket.timeToLive = 255
            socket.soTimeout = QUERY_TIMEOUT_MILLIS
            val buffer = ByteArray(9000)
            repeat(QUERY_ATTEMPTS) {
                ensureActive()
                socket.send(DatagramPacket(query, query.size, mdnsGroup, MDNS_PORT))
                while (true) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        break
                    }
                    val value = try {
                        parseRecord(buffer.copyOf(packet.length), id, owner, recordType, read)
                    } catch (_: IndexOutOfBoundsException) {
                        null
                    }
                    if (value != null) return@withContext value
                }
            }
            null
        }
    }

    private fun fail(errorCode: Int) {
        if (cancelled) return
        Log.e(TAG, "service resolution failed: $errorCode")
        val error = if (errorCode == NsdManager.FAILURE_PERMISSION_DENIED) {
            LocalNetworkAccessDenied()
        } else {
            MessageError("NsdManager resolution failed with $errorCode")
        }
        cancel()
        failed?.invoke(error)
    }

    fun cancel() {
        cancelled = true
        val callback = callback
        if (callback != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                nsd.unregisterServiceInfoCallback(callback)
            } catch (_: IllegalArgumentException) {
            }
        }
        this.callback = null
        legacyRequest?.let { LegacyResolution.cancel(it) }
        legacyRequest = null
        modelLookup?.cancel()
        modelLookup = null
        hostNameLookup?.cancel()
        hostNameLookup = null
    }

    companion object {
        const val VNC_SERVICE_TYPE = "_rfb._tcp"

        private const val RESOLVE_TIMEOUT_MILLIS = 10_000L
        private const val QUERY_ATTEMPTS = 3
        private const val QUERY_TIMEOUT_MILLIS = 1000
        private const val MDNS_PORT = 5353
        private const val DNS_TYPE_TXT = 16
        private const val DNS_TYPE_SRV = 33
        private const val DNS_CLASS_IN = 1

        private val mdnsGroup: InetAddress = parseNumericAddress("224.0.0.251")!!

        suspend fun resolve(name: String): ComputerAddress = withContext(Dispatchers.Main.immediate) {
            if (!LocalNetworkAccess.permission.isGranted) throw LocalNetworkAccessDenied()
            var resolver: ServiceResolver? = null
            try {
                withTimeoutOrNull(RESOLVE_TIMEOUT_MILLIS) {
                    suspendCancellableCoroutine<ComputerAddress> { continuation ->
                        val started = ServiceResolver(
                            name,
                            resolved = { host, port ->
                                if (continuation.isActive) continuation.resume(ComputerAddress(host, port))
                            },
                            failed = { error ->
                                if (continuation.isActive) continuation.resumeWithException(error)
                            },
                        )
                        resolver = started
                        started.start()
                    }
                } ?: throw MessageError(App.instance.getString(R.string.discovery_cant_find, name))
            } finally {
                resolver?.cancel()
            }
        }

        private fun <T> parseRecord(packet: ByteArray, id: Int, owner: String, recordType: Int, read: (ByteArray, Int, Int) -> T?): T? {
            fun u16(offset: Int): Int = ((packet[offset].toInt() and 0xff) shl 8) or (packet[offset + 1].toInt() and 0xff)
            if (u16(0) != id || (u16(2) and 0x8000) == 0) return null
            var offset = 12
            repeat(u16(4)) {
                offset = (readName(packet, offset) ?: return null).second + 4
            }
            repeat(u16(6) + u16(8) + u16(10)) {
                val (recordName, end) = readName(packet, offset) ?: return null
                val length = u16(end + 8)
                val data = end + 10
                if (u16(end) == recordType && recordName.equals(owner, ignoreCase = true)) {
                    read(packet, data, length)?.let { return it }
                }
                offset = data + length
            }
            return null
        }

        private fun readModel(packet: ByteArray, data: Int, length: Int): String? {
            var entry = data
            while (entry < data + length) {
                val size = packet[entry].toInt() and 0xff
                val text = packet.decodeToString(entry + 1, entry + 1 + size)
                val separator = text.indexOf('=')
                if (separator > 0 && text.substring(0, separator).equals("model", ignoreCase = true) && separator + 1 < text.length) {
                    return text.substring(separator + 1)
                }
                entry += 1 + size
            }
            return null
        }

        private fun readName(packet: ByteArray, start: Int): Pair<String, Int>? {
            val labels = mutableListOf<String>()
            var offset = start
            var segment = start
            var end = -1
            while (true) {
                val size = packet[offset].toInt() and 0xff
                if (size == 0) break
                if ((size and 0xc0) == 0xc0) {
                    if (end < 0) end = offset + 2
                    val target = ((size and 0x3f) shl 8) or (packet[offset + 1].toInt() and 0xff)
                    if (target >= segment) return null
                    segment = target
                    offset = target
                    continue
                }
                labels += packet.decodeToString(offset + 1, offset + 1 + size)
                offset += 1 + size
            }
            return labels.joinToString(".") to (if (end < 0) offset + 1 else end)
        }
    }
}

private object LegacyResolution {
    private const val RETRY_DELAY_MILLIS = 1000L

    class Request(val info: NsdServiceInfo, val completion: (NsdServiceInfo?, Int) -> Unit) {
        var cancelled = false
    }

    private val pending = ArrayDeque<Request>()
    private var active: Request? = null

    fun enqueue(info: NsdServiceInfo, completion: (NsdServiceInfo?, Int) -> Unit): Request {
        val request = Request(info, completion)
        pending.addLast(request)
        next()
        return request
    }

    fun cancel(request: Request) {
        request.cancelled = true
        pending.remove(request)
    }

    @Suppress("DEPRECATION")
    private fun next() {
        if (active != null) return
        val request = pending.removeFirstOrNull() ?: return
        active = request
        nsd.resolveService(
            request.info,
            object : NsdManager.ResolveListener {
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    App.scope.launch { finish(request, serviceInfo, 0) }
                }

                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    App.scope.launch { finish(request, null, errorCode) }
                }
            },
        )
    }

    private fun finish(request: Request, serviceInfo: NsdServiceInfo?, errorCode: Int) {
        if (active !== request) return
        active = null
        if (serviceInfo == null && errorCode == NsdManager.FAILURE_ALREADY_ACTIVE && !request.cancelled) {
            App.scope.launch {
                delay(RETRY_DELAY_MILLIS)
                if (!request.cancelled) pending.addFirst(request)
                next()
            }
            return
        }
        if (!request.cancelled) request.completion(serviceInfo, errorCode)
        next()
    }
}
