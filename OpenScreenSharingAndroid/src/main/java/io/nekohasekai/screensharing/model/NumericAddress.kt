package io.nekohasekai.screensharing.model

import android.net.InetAddresses
import android.os.Build
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface

fun parseNumericAddress(text: String): InetAddress? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        return if (InetAddresses.isNumericAddress(text)) InetAddresses.parseNumericAddress(text) else null
    }
    val literal = if (text.startsWith("[") && text.endsWith("]") && text.contains(':')) text.substring(1, text.length - 1) else text
    if (!literal.contains(':')) return parseIPv4(literal)?.let { InetAddress.getByAddress(it) }
    val bytes = parseIPv6(literal.substringBefore('%')) ?: return null
    if (!literal.contains('%')) return InetAddress.getByAddress(bytes)
    val scope = literal.substringAfter('%')
    val scopeId = scope.takeIf { it.isNotEmpty() && it.all { character -> character in '0'..'9' } }?.toUIntOrNull()?.toInt()
        ?: runCatching { NetworkInterface.getByName(scope) }.getOrNull()?.index
        ?: return null
    return Inet6Address.getByAddress(null, bytes, scopeId)
}

private fun parseIPv4(text: String): ByteArray? {
    val parts = text.split('.')
    if (parts.size != 4) return null
    val bytes = ByteArray(4)
    for ((index, part) in parts.withIndex()) {
        if (part.length !in 1..3 || part.any { it !in '0'..'9' } || (part.length > 1 && part[0] == '0')) return null
        val value = part.toInt()
        if (value > 255) return null
        bytes[index] = value.toByte()
    }
    return bytes
}

private fun parseIPv6(text: String): ByteArray? {
    val halves = text.split("::")
    if (halves.size > 2) return null
    val head = parseIPv6Groups(halves[0], allowsIPv4 = halves.size == 1) ?: return null
    val tail = if (halves.size == 2) parseIPv6Groups(halves[1], allowsIPv4 = true) ?: return null else emptyList()
    val missing = 16 - head.size - tail.size
    if (if (halves.size == 2) missing < 2 else missing != 0) return null
    return (head + List(missing) { 0.toByte() } + tail).toByteArray()
}

private fun parseIPv6Groups(text: String, allowsIPv4: Boolean): List<Byte>? {
    if (text.isEmpty()) return emptyList()
    val tokens = text.split(':')
    val bytes = mutableListOf<Byte>()
    for ((index, token) in tokens.withIndex()) {
        if (allowsIPv4 && index == tokens.lastIndex && token.contains('.')) {
            bytes += parseIPv4(token)?.asList() ?: return null
            continue
        }
        if (token.length !in 1..4 || token.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) return null
        val value = token.toInt(16)
        bytes += (value shr 8).toByte()
        bytes += value.toByte()
    }
    return bytes
}
