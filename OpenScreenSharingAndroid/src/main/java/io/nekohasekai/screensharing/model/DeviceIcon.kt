package io.nekohasekai.screensharing.model

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.DesktopMac
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.LaptopMac
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.min

@Composable
fun DeviceIcon(device: DeviceAppearance, size: DpSize, modifier: Modifier = Modifier, dimmed: Boolean = false, isPC: Boolean = false) {
    val color = device.color?.let { Color(it.red.toFloat(), it.green.toFloat(), it.blue.toFloat()) }
        ?: MaterialTheme.colorScheme.onSurfaceVariant
    val tint = if (dimmed) {
        val gray = 0.299f * color.red + 0.587f * color.green + 0.114f * color.blue
        Color(gray, gray, gray, color.alpha * 0.5f)
    } else {
        color
    }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Icon(
            device.imageVector(isPC),
            contentDescription = null,
            modifier = Modifier.requiredSize(min(size.width * 1.1f, size.height * 1.2f)),
            tint = tint,
        )
    }
}

fun DeviceAppearance.imageVector(isPC: Boolean): ImageVector = when (kind) {
    DeviceAppearance.Kind.Generic -> if (isPC) Icons.Outlined.DesktopWindows else Icons.Outlined.Computer
    DeviceAppearance.Kind.Laptop -> Icons.Outlined.LaptopMac
    DeviceAppearance.Kind.IMac, DeviceAppearance.Kind.MacMini, DeviceAppearance.Kind.MacStudio, is DeviceAppearance.Kind.MacPro -> Icons.Outlined.DesktopMac
}
