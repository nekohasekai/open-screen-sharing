package io.nekohasekai.screensharing.sessionui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.window.core.layout.WindowSizeClass
import io.nekohasekai.screensharing.session.DebugInfoMonitor
import io.nekohasekai.screensharing.session.RemotePlatform
import io.nekohasekai.screensharing.session.ServerDialect
import io.nekohasekai.screensharing.session.SessionController
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

private const val COLUMNS = 27
private const val DROP_WARNING = 1.0
private const val LATENCY_WARNING = 100.0
private const val LATENCY_SEVERE = 200.0
private const val REMOTE_WARNING = 50.0
private const val REMOTE_SEVERE = 100.0
private const val NETWORK_WARNING = 25.0
private const val NETWORK_SEVERE = 50.0
private const val DEVICE_WARNING = 2000.0 / 60
private const val DEVICE_SEVERE = DebugInfoMonitor.STUTTER * 1000
private const val LOSS_WARNING = 0.01
private const val LOSS_SEVERE = 0.05
private const val STUTTER_SEVERE = 10.0

private val warningColor = Color(0xFFFF9800)
private val severeColor = Color(0xFFF44336)
private val appleColor = Color(0xFF4CAF50)
private val smoothColor = Color(0xFF2196F3)
private val gridColor = Color.White.copy(alpha = 0.3f)

@Composable
fun DebugInfoView(controller: SessionController, modifier: Modifier = Modifier) {
    val compact = !currentWindowAdaptiveInfo().windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
    val fontSize = if (compact) 11.sp else 12.sp
    val style = TextStyle(color = Color.White, fontSize = fontSize, fontFamily = FontFamily.Monospace)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val width = with(density) { (measurer.measure("0", style).size.width * COLUMNS).toDp() }
    val deviceName = if (LocalConfiguration.current.smallestScreenWidthDp >= 600) "This Tablet" else "This Phone"

    val monitor = controller.debugInfo
    LaunchedEffect(controller) {
        monitor.start()
        try {
            while (isActive) {
                controller.sampleDebugInfo()
                delay(1_000)
            }
        } finally {
            monitor.stop()
        }
    }

    val figures = monitor.figures
    val network = figures?.networkMilliseconds
    val remote = figures?.remoteMilliseconds
    val device = figures?.deviceMilliseconds
    val latency = if (network != null && remote != null && device != null) remote + network + device else null
    val stutters = monitor.stutters
    val dialect = controller.info.dialect
    val isVideo = controller.isVideo
    val remoteName = when (controller.info.platform) {
        RemotePlatform.Mac -> "Mac"
        RemotePlatform.Pc -> "PC"
    }
    val imageSize = controller.imageSize.let { if (it.width > 0 && it.height > 0) "${it.width.toInt()}x${it.height.toInt()}" else "—" }
    val dropsFrames = run {
        val ready = figures?.readyPerSecond
        val shown = figures?.shownPerSecond
        isVideo && ready != null && shown != null && ready - shown > DROP_WARNING
    }

    Column(
        modifier
            .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .width(width)
            .semantics(mergeDescendants = true) {},
    ) {
        InfoPair(
            leading = if (dialect == ServerDialect.Rdp) {
                "RDP"
            } else if (isVideo) {
                "Apple VNC"
            } else {
                "VNC"
            },
            trailing = (if (isVideo) controller.info.videoCodec else figures?.encoding) ?: "—",
            style = style,
            leadingColor = if (dialect != ServerDialect.Apple) {
                Color.White
            } else if (isVideo) {
                appleColor
            } else {
                warningColor
            },
        )
        InfoPair(
            leading = imageSize,
            trailing = figures?.shownPerSecond?.let { String.format(Locale.ROOT, "%.0f fps", it) } ?: "—",
            style = style,
            trailingColor = if (dropsFrames) warningColor else Color.White,
        )
        Column(Modifier.padding(top = 4.dp)) {
            InfoPair("Latency", latency?.let(::milliseconds), style, trailingColor = color(latency, LATENCY_WARNING, LATENCY_SEVERE))
            InfoPair("  $remoteName", remote?.let(::milliseconds), style, trailingColor = color(remote, REMOTE_WARNING, REMOTE_SEVERE))
            InfoPair("  Network", network?.let(::milliseconds), style, trailingColor = color(network, NETWORK_WARNING, NETWORK_SEVERE))
            InfoPair("  $deviceName", device?.let(::milliseconds), style, trailingColor = color(device, DEVICE_WARNING, DEVICE_SEVERE))
            InfoPair("Bitrate", figures?.bitsPerSecond?.let(::bitrate), style)
            if (figures?.lossy == true) {
                InfoPair(
                    "Packet Loss",
                    figures.loss?.let { String.format(Locale.ROOT, "%.1f%%", it * 100) },
                    style,
                    trailingColor = color(figures.loss, LOSS_WARNING, LOSS_SEVERE),
                )
            }
            InfoPair("Stutters", "$stutters", style, trailingColor = color(stutters.toDouble(), 1.0, STUTTER_SEVERE))
        }
        StutterTimeline(
            monitor,
            Modifier
                .padding(top = 2.dp)
                .fillMaxWidth()
                .height(with(density) { fontSize.toDp() } * 3)
                .clearAndSetSemantics {},
        )
    }
}

@Composable
private fun InfoPair(
    leading: String,
    trailing: String?,
    style: TextStyle,
    leadingColor: Color = Color.White,
    trailingColor: Color = Color.White,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            leading,
            Modifier
                .weight(1f, fill = false)
                .padding(end = 8.dp),
            style = style.copy(color = leadingColor),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
        )
        Text(trailing ?: "—", style = style.copy(color = trailingColor), maxLines = 1, softWrap = false)
    }
}

private fun color(value: Double?, warning: Double, severe: Double): Color = when {
    value == null -> Color.White
    value >= severe -> severeColor
    value >= warning -> warningColor
    else -> Color.White
}

private fun bitrate(bitsPerSecond: Double): String = if (bitsPerSecond >= 1_000_000) {
    String.format(Locale.ROOT, "%.1fMbps", bitsPerSecond / 1_000_000)
} else {
    String.format(Locale.ROOT, "%.0fkbps", bitsPerSecond / 1000)
}

private fun milliseconds(value: Double): String = if (value >= 0 && value < 1) "<1ms" else String.format(Locale.ROOT, "%.0fms", value)

@Composable
private fun StutterTimeline(monitor: DebugInfoMonitor, modifier: Modifier) {
    val top = DebugInfoMonitor.STUTTER * 4
    val minutes = DebugInfoMonitor.SPANS * DebugInfoMonitor.SPAN_SECONDS / 60
    Canvas(modifier) {
        val spans = monitor.timeline
        val pixel = 1f
        val point = 1.dp.toPx()
        val grid = Path()
        for (minute in 1 until minutes) {
            val x = size.width * minute / minutes
            grid.moveTo(x, 0f)
            grid.lineTo(x, size.height)
        }
        val stutterLine = (size.height - point) * (1 - (DebugInfoMonitor.STUTTER / top).toFloat())
        grid.moveTo(0f, stutterLine)
        grid.lineTo(size.width, stutterLine)
        drawPath(grid, gridColor, style = Stroke(pixel))
        val axes = Path().apply {
            moveTo(pixel / 2, 0f)
            lineTo(pixel / 2, size.height - pixel / 2)
            lineTo(size.width - pixel / 2, size.height - pixel / 2)
            lineTo(size.width - pixel / 2, 0f)
        }
        drawPath(axes, Color.White, style = Stroke(pixel))

        val width = size.width / DebugInfoMonitor.SPANS
        val smooth = Path()
        val stuttered = Path()
        for ((index, span) in spans.withIndex()) {
            val lateness = (if (span.stutters > 0) max(span.lateness ?: 0.0, DebugInfoMonitor.STUTTER) else span.lateness) ?: continue
            val height = max((size.height - point) * min(lateness / top, 1.0).toFloat(), pixel)
            val x = size.width - (spans.size - index) * width
            val bar = Rect(Offset(x + pixel, size.height - pixel - height), Size(max(width - pixel * 2, pixel), height))
            (if (span.stutters > 0) stuttered else smooth).addRect(bar)
        }
        drawPath(smooth, smoothColor)
        drawPath(stuttered, severeColor)
    }
}
