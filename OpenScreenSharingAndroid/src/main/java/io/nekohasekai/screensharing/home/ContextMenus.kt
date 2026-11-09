package io.nekohasekai.screensharing.home

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

@Composable
internal fun Modifier.secondaryClick(onClick: (Offset) -> Unit): Modifier {
    val current by rememberUpdatedState(onClick)
    return pointerInput(Unit) {
        awaitEachGesture {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                event.changes.forEach { it.consume() }
                current(event.changes.first().position)
            }
        }
    }
}

@Composable
internal fun menuOffset(position: Offset?, anchor: IntSize): DpOffset {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    if (position == null || !position.isSpecified) return DpOffset(16.dp, 0.dp)
    val x = if (layoutDirection == LayoutDirection.Rtl) anchor.width - position.x else position.x
    return with(density) { DpOffset(x.toDp(), (position.y - anchor.height).toDp()) }
}
