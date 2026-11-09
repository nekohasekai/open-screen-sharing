package io.nekohasekai.screensharing.discovery

import android.Manifest
import android.os.Build
import io.nekohasekai.screensharing.App
import io.nekohasekai.screensharing.R
import io.nekohasekai.screensharing.app.RuntimePermission
import io.nekohasekai.screensharing.model.parseNumericAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Inet6Address
import java.net.InetAddress

object LocalNetworkAccess {
    val permission =
        RuntimePermission(Manifest.permission.ACCESS_LOCAL_NETWORK, Build.VERSION_CODES.CINNAMON_BUN) {
            NearbyBrowser.shared.retry()
            AvailabilityMonitor.shared.retry()
        }

    suspend fun request(host: String, requester: RuntimePermission.Requester) {
        if (permission.isGranted) return
        val local = when (val address = parseNumericAddress(host)) {
            null -> host.lowercase().removeSuffix(".").endsWith(".local") || resolvesLocally(host)
            else -> isLocal(address)
        }
        if (local) requester.request()
    }

    private suspend fun resolvesLocally(host: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { InetAddress.getAllByName(host).any(::isLocal) }.getOrDefault(false)
    }

    private fun isLocal(address: InetAddress): Boolean = address.isSiteLocalAddress || address.isLinkLocalAddress ||
        (address is Inet6Address && (address.address[0].toInt() and 0xfe) == 0xfc)
}

class LocalNetworkAccessDenied : Exception(App.instance.getString(R.string.discovery_local_network_denied))
