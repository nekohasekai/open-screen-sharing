package io.nekohasekai.screensharing.home

import android.graphics.BitmapFactory
import android.icu.text.DisplayContext
import android.icu.text.NumberFormat
import android.icu.text.RelativeDateTimeFormatter
import android.icu.text.RelativeDateTimeFormatter.RelativeDateTimeUnit
import android.icu.text.RelativeDateTimeFormatter.RelativeUnit
import android.icu.util.ULocale
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import io.nekohasekai.screensharing.R
import io.nekohasekai.screensharing.app.groupItemColors
import io.nekohasekai.screensharing.discovery.ComputerAvailability
import io.nekohasekai.screensharing.model.Computer
import io.nekohasekai.screensharing.model.DeviceAppearance
import io.nekohasekai.screensharing.model.DeviceIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.sqrt

const val ComputerCardAspectRatio = 16f / 10f

private val CardCornerRadius = 16.dp

@Composable
fun ComputerCard(
    name: String,
    detail: String?,
    device: DeviceAppearance,
    modifier: Modifier = Modifier,
    thumbnail: ByteArray? = null,
    availability: ComputerAvailability? = null,
    isPC: Boolean = false,
) {
    val shape = RoundedCornerShape(CardCornerRadius)
    Column(modifier.availabilitySemantics(availability), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .aspectRatio(ComputerCardAspectRatio)
                .availabilityDot(availability, dotSize = 12.dp, inset = CardCornerRadius * (1 - 1 / sqrt(2f)))
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            if (thumbnail != null) {
                ComputerThumbnail(thumbnail, dimmed = availability == ComputerAvailability.Unavailable)
            } else {
                DeviceIcon(
                    device,
                    DpSize(maxWidth * 0.7f, maxHeight * 0.7f),
                    Modifier.align(Alignment.Center),
                    dimmed = availability == ComputerAvailability.Unavailable,
                    isPC = isPC,
                )
            }
        }
        ComputerLabel(name, detail)
    }
}

@Composable
fun ComputerRow(
    name: String,
    detail: String?,
    device: DeviceAppearance,
    modifier: Modifier = Modifier,
    availability: ComputerAvailability? = null,
    isPC: Boolean = false,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    val iconSize = 36.dp
    val dotSize = 8.dp
    ListItem(
        headlineContent = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        modifier = modifier.availabilitySemantics(availability),
        supportingContent = if (detail == null) {
            null
        } else {
            { Text(detail, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        },
        leadingContent = {
            DeviceIcon(
                device,
                DpSize(iconSize, iconSize),
                Modifier.availabilityDot(availability, dotSize = dotSize, inset = dotSize / 4),
                dimmed = availability == ComputerAvailability.Unavailable,
                isPC = isPC,
            )
        },
        trailingContent = trailingContent,
        colors = groupItemColors(),
    )
}

@Composable
private fun ComputerLabel(name: String, detail: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (detail != null) {
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Modifier.availabilitySemantics(availability: ComputerAvailability?): Modifier {
    val description = when (availability) {
        ComputerAvailability.Available -> stringResource(R.string.home_available)
        ComputerAvailability.Unavailable -> stringResource(R.string.home_not_available)
        null -> return this
    }
    return semantics { stateDescription = description }
}

@Composable
private fun Modifier.availabilityDot(availability: ComputerAvailability?, dotSize: Dp, inset: Dp): Modifier {
    val color = when (availability) {
        ComputerAvailability.Available -> Color(0xFF34A853)
        ComputerAvailability.Unavailable -> MaterialTheme.colorScheme.outline
        null -> return this
    }
    return drawWithContent {
        drawContent()
        drawCircle(color, radius = dotSize.toPx() / 2, center = Offset(size.width - inset.toPx(), size.height - inset.toPx()))
    }
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val radius = dotSize.toPx() / 2
            drawCircle(
                Color.Black,
                radius = radius + radius / 2,
                center = Offset(size.width - inset.toPx(), size.height - inset.toPx()),
                blendMode = BlendMode.Clear,
            )
        }
}

@Composable
private fun ComputerThumbnail(data: ByteArray, dimmed: Boolean) {
    val image by produceState<ImageBitmap?>(null, data) {
        value = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(data, 0, data.size)?.asImageBitmap() }
    }
    val bitmap = image ?: return
    Image(
        bitmap,
        contentDescription = null,
        modifier = Modifier.fillMaxSize(),
        alignment = Alignment.TopCenter,
        contentScale = if (bitmap.height > bitmap.width) ContentScale.Fit else ContentScale.Crop,
        alpha = if (dimmed) 0.5f else 1f,
        colorFilter = if (dimmed) ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) else null,
    )
}

val Computer.detailText: String
    @Composable get() {
        val lastConnected = lastConnected ?: return addressDescription
        return stringResource(R.string.home_last_connected, relativeDate(lastConnected))
    }

private fun relativeDate(time: Long): String {
    val locale = ULocale.forLocale(Locale.getDefault())
    val formatter = RelativeDateTimeFormatter.getInstance(
        locale,
        NumberFormat.getInstance(locale),
        RelativeDateTimeFormatter.Style.LONG,
        DisplayContext.CAPITALIZATION_FOR_MIDDLE_OF_SENTENCE,
    )
    val seconds = (System.currentTimeMillis() - time) / 1000
    val (unit, length) = when {
        seconds < 60 -> RelativeUnit.SECONDS to 1L
        seconds < 3600 -> RelativeUnit.MINUTES to 60L
        seconds < 86400 -> RelativeUnit.HOURS to 3600L
        seconds < 7 * 86400 -> RelativeUnit.DAYS to 86400L
        seconds < 30 * 86400 -> RelativeUnit.WEEKS to 7 * 86400L
        seconds < 365 * 86400 -> RelativeUnit.MONTHS to 30 * 86400L
        else -> RelativeUnit.YEARS to 365 * 86400L
    }
    val quantity = (seconds.coerceAtLeast(0) / length).toDouble()
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
        return formatter.format(quantity, RelativeDateTimeFormatter.Direction.LAST, unit)
    }
    val dateTimeUnit = when (unit) {
        RelativeUnit.SECONDS -> RelativeDateTimeUnit.SECOND
        RelativeUnit.MINUTES -> RelativeDateTimeUnit.MINUTE
        RelativeUnit.HOURS -> RelativeDateTimeUnit.HOUR
        RelativeUnit.DAYS -> RelativeDateTimeUnit.DAY
        RelativeUnit.WEEKS -> RelativeDateTimeUnit.WEEK
        RelativeUnit.MONTHS -> RelativeDateTimeUnit.MONTH
        else -> RelativeDateTimeUnit.YEAR
    }
    return formatter.format(-quantity, dateTimeUnit)
}
