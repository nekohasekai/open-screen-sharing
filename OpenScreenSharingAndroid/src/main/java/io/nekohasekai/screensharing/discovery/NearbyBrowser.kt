package io.nekohasekai.screensharing.discovery

import android.icu.text.Collator
import android.icu.text.RuleBasedCollator
import android.net.Network
import android.net.nsd.DiscoveryRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ext.SdkExtensions
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.nekohasekai.screensharing.App
import io.nekohasekai.screensharing.R
import io.nekohasekai.screensharing.model.ComputerAddress
import io.nekohasekai.screensharing.model.RemoteProtocol
import io.nekohasekai.screensharing.model.parseNumericAddress
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.net.NetworkInterface

data class NearbyComputer(
    val name: String,
    val remoteProtocol: RemoteProtocol = RemoteProtocol.Vnc,
    val hostName: String? = null,
    val host: String? = null,
    val port: Int? = null,
    val model: String? = null,
    val addresses: Set<InetAddress> = emptySet(),
) {
    val id: String get() = "${remoteProtocol.rawValue}:$name"

    val address: ComputerAddress?
        get() {
            val host = host ?: return null
            val port = port ?: return null
            return ComputerAddress(host, port)
        }
}

class NearbyBrowser private constructor() {
    sealed interface State {
        data object Idle : State
        data object Browsing : State
        data class Failed(val message: String) : State
    }

    var computers by mutableStateOf<List<NearbyComputer>>(emptyList())
        private set
    var state by mutableStateOf<State>(State.Idle)
        private set
    var hasSearched by mutableStateOf(false)
        private set

    private val nsd by lazy { App.instance.getSystemService(NsdManager::class.java) }
    private val multicastLock by lazy {
        if (!needsMulticastLock) return@lazy null
        App.instance.getSystemService(WifiManager::class.java)?.createMulticastLock(TAG)?.apply { setReferenceCounted(false) }
    }
    private var browser: Browser? = null
    private var windowsBrowser: Browser? = null
    private val resolvers = mutableMapOf<String, ServiceResolver>()
    private val windowsResolvers = mutableMapOf<String, ServiceResolver>()
    private val windowsProbes = mutableMapOf<String, Job>()
    private val models = mutableMapOf<String, String>()
    private val serviceModels = mutableMapOf<String, String>()
    private val ownNames = mutableSetOf<String>()
    private var activeClients = 0
    private var restart: Job? = null
    private var search: Job? = null
    private var isReady = false
    private var currentResults by mutableStateOf(emptySet<RemoteProtocol>())

    fun start() {
        activeClients += 1
        if (browser != null) return
        browse()
        presentSearch()
    }

    fun stop() {
        activeClients = maxOf(0, activeClients - 1)
        if (activeClients != 0) return
        restart?.cancel()
        restart = null
        search?.cancel()
        search = null
        cancelBrowsing()
        state = State.Idle
        hasSearched = false
    }

    fun retry() {
        if (activeClients == 0 || state == State.Browsing) return
        restart?.cancel()
        restart = null
        cancelBrowsing()
        browse()
        presentSearch()
    }

    private fun presentSearch() {
        search?.cancel()
        hasSearched = false
        search = App.scope.launch {
            delay(SEARCH_PRESENTATION_DELAY_MILLIS)
            hasSearched = true
        }
    }

    private fun browse() {
        isReady = false
        if (!LocalNetworkAccess.permission.isGranted) {
            state = State.Failed(App.instance.getString(R.string.discovery_local_network_denied))
            return
        }
        multicastLock?.acquire()
        state = State.Browsing
        browser = Browser(ServiceResolver.VNC_SERVICE_TYPE).also { it.start() }
        windowsBrowser = Browser(WINDOWS_SERVICE_TYPE).also { it.start() }
    }

    private fun cancelBrowsing() {
        currentResults = emptySet()
        computers = emptyList()
        browser?.cancel()
        browser = null
        windowsBrowser?.cancel()
        windowsBrowser = null
        resolvers.values.forEach { it.cancel() }
        resolvers.clear()
        windowsResolvers.values.forEach { it.cancel() }
        windowsResolvers.clear()
        windowsProbes.values.forEach { it.cancel() }
        windowsProbes.clear()
        models.clear()
        serviceModels.clear()
        ownNames.clear()
        multicastLock?.release()
    }

    private fun restartBrowsing() {
        restart?.cancel()
        restart = App.scope.launch {
            delay(RESTART_DELAY_MILLIS)
            cancelBrowsing()
            browse()
        }
    }

    private fun failed(errorCode: Int) {
        if (errorCode == NsdManager.FAILURE_PERMISSION_DENIED) {
            cancelBrowsing()
            state = State.Failed(App.instance.getString(R.string.discovery_local_network_denied))
            return
        }
        state = State.Failed("NsdManager discovery failed with $errorCode")
    }

    fun hasCurrentService(address: ComputerAddress, remoteProtocol: RemoteProtocol): Boolean = state == State.Browsing && remoteProtocol in currentResults && computer(address, remoteProtocol) != null

    fun computer(address: ComputerAddress, remoteProtocol: RemoteProtocol): NearbyComputer? = computers.firstOrNull {
        it.remoteProtocol == remoteProtocol && (it.port == null || it.port == address.port) && matches(it, address)
    }

    fun host(address: ComputerAddress): NearbyComputer? = computers.firstOrNull { matches(it, address) }

    fun lacksHost(address: ComputerAddress): Boolean = state == State.Browsing && RemoteProtocol.Vnc in currentResults &&
        computers.none { it.remoteProtocol == RemoteProtocol.Vnc && it.hostName == null } &&
        host(address) == null

    private fun update(names: Set<String>) {
        currentResults = currentResults + RemoteProtocol.Vnc
        for (name in resolvers.keys - names) {
            resolvers.remove(name)?.cancel()
            models.remove(name)
            serviceModels.remove(name)
            ownNames.remove(name)
        }
        val macs = (names - ownNames).map { name ->
            val computer = computers.firstOrNull { it.remoteProtocol == RemoteProtocol.Vnc && it.name == name } ?: NearbyComputer(name)
            computer.copy(model = modelOf(name))
        }
        computers = (macs + computers.filter { it.remoteProtocol == RemoteProtocol.Rdp }).sortedWith(nameOrder)
        for (name in names) {
            if (name in resolvers) continue
            val resolver = ServiceResolver(
                name,
                network = browser?.found?.get(name)?.firstOrNull(),
                resolved = { host, port -> replace(RemoteProtocol.Vnc, name) { it.copy(host = host, port = port) } },
                hostName = { hostName -> replace(RemoteProtocol.Vnc, name) { it.copy(hostName = hostName) } },
                addresses = { addresses ->
                    if (isOwn(addresses)) {
                        ownNames += name
                        computers = computers.filterNot { it.remoteProtocol == RemoteProtocol.Vnc && it.name == name }
                    } else {
                        replace(RemoteProtocol.Vnc, name) { it.copy(addresses = addresses) }
                    }
                },
                model = { model -> updateModel(model, name) },
                serviceModel = { model -> updateServiceModel(model, name) },
            )
            resolvers[name] = resolver
            resolver.start()
        }
    }

    private fun updateModel(model: String, name: String) {
        if (models[name] == model) return
        models[name] = model
        replace(RemoteProtocol.Vnc, name) { it.copy(model = modelOf(name)) }
    }

    private fun updateServiceModel(model: String?, name: String) {
        if (serviceModels[name] == model) return
        if (model == null) serviceModels.remove(name) else serviceModels[name] = model
        replace(RemoteProtocol.Vnc, name) { it.copy(model = modelOf(name)) }
    }

    private fun modelOf(name: String): String? = serviceModels[name] ?: models[name]

    private fun updateWindows(names: Set<String>) {
        currentResults = currentResults + RemoteProtocol.Rdp
        computers = computers.filterNot { it.remoteProtocol == RemoteProtocol.Rdp && it.name !in names }
        for (name in windowsResolvers.keys - names) {
            windowsResolvers.remove(name)?.cancel()
            windowsProbes.remove(name)?.cancel()
        }
        for (name in names) {
            if (name in windowsResolvers) continue
            val resolver = ServiceResolver(
                name,
                type = WINDOWS_SERVICE_TYPE,
                network = windowsBrowser?.found?.get(name)?.firstOrNull(),
                resolved = { host, _ -> probeWindows(name, host) },
                addresses = { addresses -> replace(RemoteProtocol.Rdp, name) { it.copy(addresses = addresses) } },
            )
            windowsResolvers[name] = resolver
            resolver.start()
        }
    }

    private fun probeWindows(name: String, host: String) {
        windowsProbes.remove(name)?.cancel()
        windowsProbes[name] = App.scope.launch {
            val address = ComputerAddress(host, RemoteProtocol.Rdp.defaultPort)
            val answer = AvailabilityProbe.check(address, RemoteProtocol.Rdp)
            windowsProbes.remove(name)
            val index = computers.indexOfFirst { it.remoteProtocol == RemoteProtocol.Rdp && it.name == name }
            if (answer == AvailabilityProbe.Answer.Nothing) {
                if (index >= 0) computers = computers.filterIndexed { position, _ -> position != index }
                return@launch
            }
            val computer = (if (index >= 0) computers[index] else NearbyComputer(name, RemoteProtocol.Rdp)).copy(host = host, port = address.port)
            computers = if (index >= 0) {
                computers.toMutableList().also { it[index] = computer }
            } else {
                (computers + computer).sortedWith(nameOrder)
            }
        }
    }

    private fun replace(remoteProtocol: RemoteProtocol, name: String, transform: (NearbyComputer) -> NearbyComputer) {
        val index = computers.indexOfFirst { it.remoteProtocol == remoteProtocol && it.name == name }
        if (index < 0) return
        computers = computers.toMutableList().also { it[index] = transform(it[index]) }
    }

    private inner class Browser(private val type: String) : NsdManager.DiscoveryListener {
        val found = mutableMapOf<String, MutableSet<Network?>>()

        private val isCurrent get() = this === browser || this === windowsBrowser

        fun start() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
                val request = DiscoveryRequest.Builder(type).setFlags(DiscoveryRequest.FLAG_NO_PICKER).build()
                nsd.discoverServices(request, App.instance.mainExecutor, this)
            } else {
                nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, this)
            }
        }

        fun cancel() {
            try {
                nsd.stopServiceDiscovery(this)
            } catch (_: IllegalArgumentException) {
            }
        }

        private fun networkOf(serviceInfo: NsdServiceInfo): Network? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) serviceInfo.network else null

        private fun changed() {
            val names = found.keys.toSet()
            if (this === browser) update(names) else updateWindows(names)
        }

        override fun onDiscoveryStarted(serviceType: String) {
            App.scope.launch {
                if (!isCurrent) return@launch
                isReady = true
                state = State.Browsing
            }
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            App.scope.launch {
                if (isCurrent) failed(errorCode)
            }
        }

        override fun onDiscoveryStopped(serviceType: String) {
            App.scope.launch {
                if (isCurrent && isReady) restartBrowsing()
            }
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            val name = serviceInfo.serviceName
            val network = networkOf(serviceInfo)
            App.scope.launch {
                if (!isCurrent) return@launch
                if (found.getOrPut(name) { mutableSetOf() }.add(network)) changed()
            }
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            val name = serviceInfo.serviceName
            val network = networkOf(serviceInfo)
            App.scope.launch {
                if (!isCurrent) return@launch
                val networks = found[name] ?: return@launch
                if (!networks.remove(network)) return@launch
                if (networks.isEmpty()) found.remove(name)
                changed()
            }
        }
    }

    companion object {
        val shared by lazy { NearbyBrowser() }

        private const val TAG = "discovery"

        // Windows advertises Delivery Optimization's peer cache on _dosvc._tcp by default, never Remote Desktop
        // (observed on Windows 11 26100 and 26200).
        private const val WINDOWS_SERVICE_TYPE = "_dosvc._tcp"
        private const val SEARCH_PRESENTATION_DELAY_MILLIS = 2000L
        private const val RESTART_DELAY_MILLIS = 1000L

        private val needsMulticastLock = Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
            SdkExtensions.getExtensionVersion(Build.VERSION_CODES.TIRAMISU) < 7

        private val nameOrder: Comparator<NearbyComputer> = run {
            val collator = Collator.getInstance()
            (collator as? RuleBasedCollator)?.numericCollation = true
            compareBy<NearbyComputer, String>(collator) { it.name }
        }

        private fun matches(computer: NearbyComputer, address: ComputerAddress): Boolean {
            val host = normalized(address.host)
            if (listOfNotNull(computer.hostName, computer.host).any { normalized(it) == host }) return true
            val raw = parseNumericAddress(address.host)
            if (raw != null && raw in computer.addresses) return true
            return normalized(computer.name) == host || address.bonjourServiceName == computer.name
        }

        private fun normalized(host: String): String = host.lowercase().removeSuffix(".").removeSuffix(".local")

        private fun isOwn(addresses: Set<InetAddress>): Boolean = addresses.isNotEmpty() &&
            addresses.all { address ->
                address.isLoopbackAddress || runCatching { NetworkInterface.getByInetAddress(address) }.getOrNull() != null
            }
    }
}
