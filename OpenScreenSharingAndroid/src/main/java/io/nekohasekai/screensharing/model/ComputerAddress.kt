package io.nekohasekai.screensharing.model

import java.net.URI
import java.net.URLDecoder

enum class AddressKind {
    PrivateNetwork,
    PublicNetwork,
    Tailscale,
    ;

    companion object {
        fun of(host: String): AddressKind {
            var trimmed = host.trim().lowercase()
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                trimmed = trimmed.substring(1, trimmed.length - 1)
            }
            trimmed = trimmed.trimEnd('.')
            if (trimmed.endsWith(".ts.net") || trimmed.endsWith(".tailscale.net")) {
                return Tailscale
            }
            val numeric = parseNumericAddress(trimmed.substringBefore('%'))
            if (numeric != null) {
                val bytes = numeric.address
                if (bytes.size == 4) {
                    return classifyIPv4(bytes)
                }
                if (bytes.size == 16) {
                    if ((0 until 10).all { bytes[it].toInt() == 0 } && (bytes[10].toInt() and 0xff) == 0xff && (bytes[11].toInt() and 0xff) == 0xff) {
                        return classifyIPv4(bytes.copyOfRange(12, 16))
                    }
                    if ((bytes[0].toInt() and 0xff) == 0xfd &&
                        (bytes[1].toInt() and 0xff) == 0x7a &&
                        (bytes[2].toInt() and 0xff) == 0x11 &&
                        (bytes[3].toInt() and 0xff) == 0x5c &&
                        (bytes[4].toInt() and 0xff) == 0xa1 &&
                        (bytes[5].toInt() and 0xff) == 0xe0
                    ) {
                        return Tailscale
                    }
                    val first = bytes[0].toInt() and 0xff
                    val second = bytes[1].toInt() and 0xff
                    if (((0 until 15).all { bytes[it].toInt() == 0 } && bytes[15].toInt() == 1) ||
                        (first == 0xfe && (second and 0xc0) == 0x80) ||
                        (first and 0xfe) == 0xfc
                    ) {
                        return PrivateNetwork
                    }
                }
                return PublicNetwork
            }
            if (trimmed == "localhost" ||
                trimmed.endsWith("._rfb._tcp.local") ||
                trimmed.endsWith(".local") ||
                trimmed.endsWith(".home.arpa") ||
                trimmed.endsWith(".lan") ||
                trimmed.endsWith(".internal") ||
                (trimmed.isNotEmpty() && !trimmed.contains('.'))
            ) {
                return PrivateNetwork
            }
            return PublicNetwork
        }

        private fun classifyIPv4(bytes: ByteArray): AddressKind {
            if (bytes.size != 4) return PublicNetwork
            val first = bytes[0].toInt() and 0xff
            val second = bytes[1].toInt() and 0xff
            if (first == 100 && (second and 0xc0) == 64) {
                return Tailscale
            }
            if (first == 10 ||
                (first == 172 && (second and 0xf0) == 16) ||
                (first == 192 && second == 168) ||
                first == 127 ||
                (first == 169 && second == 254)
            ) {
                return PrivateNetwork
            }
            return PublicNetwork
        }
    }
}

data class ComputerAddress(val host: String, val port: Int = DEFAULT_PORT) {
    val kind: AddressKind get() = AddressKind.of(host)

    override fun toString(): String = description(DEFAULT_PORT)

    fun description(defaultPort: Int): String = when {
        port == defaultPort -> host
        host.contains(':') -> "[$host]:$port"
        else -> "$host:$port"
    }

    val bonjourServiceName: String?
        get() {
            val lowered = host.lowercase()
            for (suffix in listOf("._rfb._tcp.local.", "._rfb._tcp.local")) {
                if (lowered.endsWith(suffix)) return host.dropLast(suffix.length)
            }
            return null
        }

    fun matches(other: ComputerAddress): Boolean = port == other.port && normalized(host) == normalized(other.host)

    companion object {
        const val DEFAULT_PORT = 5900

        private fun normalized(host: String): String = host.lowercase().removeSuffix(".")

        fun parseHost(text: String): String? {
            var host = text.trim()
            if (host.startsWith("[") && host.endsWith("]")) {
                host = host.substring(1, host.length - 1)
                return if (isIPv6(host)) host else null
            }
            if (host.contains(':')) {
                return if (isIPv6(host)) host else null
            }
            return if (isHostName(host)) host else null
        }

        private fun isIPv6(text: String): Boolean = text.contains(':') && parseNumericAddress(text) != null

        private fun isHostName(text: String): Boolean {
            if (text.isEmpty() || text.length > 253) return false
            var labels = text.split('.')
            if (labels.size > 1 && labels.last().isEmpty()) {
                labels = labels.dropLast(1)
            }
            return labels.all { label ->
                label.isNotEmpty() && label.length <= 63 && label.first() != '-' && label.last() != '-' &&
                    label.all { it.isLetterOrDigit() || it == '-' || it == '_' }
            }
        }
    }
}

data class ConnectionTarget(
    val host: String,
    val port: Int? = null,
    val remoteProtocol: RemoteProtocol? = null,
    val username: String? = null,
    val password: String? = null,
) {
    companion object {
        fun parse(text: String): ConnectionTarget? {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return null
            if (trimmed.contains("://")) {
                val link = RemoteLink.parse(trimmed) ?: return null
                return ConnectionTarget(link.address.host, link.address.port, link.remoteProtocol, link.username, link.password)
            }
            var rest = trimmed
            var username: String? = null
            val at = rest.lastIndexOf('@')
            if (at >= 0) {
                val name = rest.substring(0, at)
                if (name.isEmpty()) return null
                username = name
                rest = rest.substring(at + 1)
            }
            val (host, port) = parseEndpoint(rest) ?: return null
            return ConnectionTarget(host, port, username = username)
        }

        fun parseEndpoint(text: String): Pair<String, Int?>? {
            if (text.startsWith("[")) {
                val closing = text.indexOf(']')
                if (closing < 0) return null
                val host = ComputerAddress.parseHost(text.substring(0, closing + 1)) ?: return null
                val suffix = text.substring(closing + 1)
                if (suffix.isEmpty()) return host to null
                if (!suffix.startsWith(":")) return null
                val port = suffix.drop(1).toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
                return host to port
            }
            if (text.count { it == ':' } == 1) {
                val colon = text.indexOf(':')
                val host = ComputerAddress.parseHost(text.substring(0, colon)) ?: return null
                val port = text.substring(colon + 1).toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
                return host to port
            }
            val host = ComputerAddress.parseHost(text) ?: return null
            return host to null
        }
    }
}

data class RemoteLink(
    val address: ComputerAddress,
    val remoteProtocol: RemoteProtocol,
    val username: String? = null,
    val password: String? = null,
) {
    companion object {
        fun parse(string: String): RemoteLink? {
            val separator = string.indexOf("://")
            if (separator < 0) return null
            val remoteProtocol = RemoteProtocol.of(string.substring(0, separator).lowercase()) ?: return null
            if (remoteProtocol == RemoteProtocol.Rdp) {
                val attributes = rdpAttributes(string.substring(separator + 3))
                if (attributes != null) {
                    val full = attributes["full address"] ?: return null
                    val (host, port) = ConnectionTarget.parseEndpoint(full) ?: return null
                    var username: String? = null
                    val user = attributes["username"]
                    if (!user.isNullOrEmpty()) {
                        val domain = attributes["domain"].orEmpty()
                        username = if (user.contains('\\') || domain.isEmpty()) user else "$domain\\$user"
                    }
                    return RemoteLink(ComputerAddress(host, port ?: remoteProtocol.defaultPort), remoteProtocol, username)
                }
            }
            val uri = runCatching { URI(string) }.getOrNull() ?: return null
            val authority = uri.rawAuthority ?: return null
            val hostPart = authority.substringAfterLast('@')
            val host = decode(
                when {
                    hostPart.startsWith("[") -> hostPart.substringBefore(']') + "]"
                    else -> hostPart.substringBefore(':')
                },
            ) ?: return null
            if (host.isEmpty()) return null
            val unbracketed = if (host.startsWith("[") && host.endsWith("]")) host.substring(1, host.length - 1) else host
            val portText = if (hostPart.startsWith("[")) hostPart.substringAfter(']', "").removePrefix(":") else hostPart.substringAfter(':', "")
            val port = if (portText.isEmpty()) remoteProtocol.defaultPort else portText.toIntOrNull() ?: return null
            var username: String? = null
            var password: String? = null
            if (authority.contains('@')) {
                val userInfo = authority.substringBeforeLast('@')
                username = decode(userInfo.substringBefore(':'))?.takeIf { it.isNotEmpty() }
                if (userInfo.contains(':')) {
                    password = decode(userInfo.substringAfter(':'))?.takeIf { it.isNotEmpty() || username != null }
                }
            }
            return RemoteLink(ComputerAddress(unbracketed, port), remoteProtocol, username, password)
        }

        private fun decode(text: String): String? = runCatching { URLDecoder.decode(text.replace("+", "%2B"), "UTF-8") }.getOrNull()

        private fun rdpAttributes(query: String): Map<String, String>? {
            val attributes = mutableMapOf<String, String>()
            for (pair in query.split('&').filter { it.isNotEmpty() }) {
                val equals = pair.indexOf('=')
                if (equals < 0) return null
                val colon = pair.indexOf(':', equals)
                if (colon < 0 || colon - equals != 2) return null
                val key = decode(pair.substring(0, equals)) ?: return null
                val value = decode(pair.substring(colon + 1)) ?: return null
                attributes[key.lowercase()] = value
            }
            return if (attributes["full address"] == null) null else attributes
        }
    }
}
