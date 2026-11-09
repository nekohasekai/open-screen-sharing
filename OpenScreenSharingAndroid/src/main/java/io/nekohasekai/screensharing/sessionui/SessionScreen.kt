package io.nekohasekai.screensharing.sessionui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.nekohasekai.screensharing.R
import io.nekohasekai.screensharing.app.AlertHost
import io.nekohasekai.screensharing.app.AppTheme
import io.nekohasekai.screensharing.app.FormDialog
import io.nekohasekai.screensharing.model.AppSettings
import io.nekohasekai.screensharing.model.ToolbarLayout
import io.nekohasekai.screensharing.session.ScreenPicture
import io.nekohasekai.screensharing.session.SessionController
import io.nekohasekai.screensharing.settings.QuickInputView
import kotlinx.coroutines.flow.drop
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun SessionScreen(
    controller: SessionController,
    onView: (RemoteScreenView) -> Unit = {},
) {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    var screenView by remember { mutableStateOf<RemoteScreenView?>(null) }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        val itemSize = ToolbarLayout.itemSize
        val dock =
            if (controller.pictureMatchesWindow) {
                null
            } else {
                with(density) { ToolbarDock.of(controller.letterbox, ToolbarDock.barThickness.toPx(), ToolbarLayout.dockClearance.toPx()) }
            }
        val safeInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
        val toolbarWidth =
            with(density) {
                (constraints.maxWidth - safeInsets.getLeft(this, layoutDirection) - safeInsets.getRight(this, layoutDirection)).toDp()
            }
        val toolbarSlots = ToolbarLayout.capsuleSlots(dock?.let { with(density) { it.length.toDp() } } ?: toolbarWidth, itemSize)
        val plan = ToolbarLayout.layout(controller).plan(controller, toolbarSlots)
        val pointerLockAvailable = SessionCommand.PointerLock.isOffered(controller) && controller.canLockPointer
        val pointerLockPaused =
            controller.isToolbarVisible || controller.isZoomModeOn || !controller.isControlling ||
                controller.isKeyboardVisible || controller.picture == ScreenPicture.None || controller.isShowingResolution ||
                controller.isCustomizingToolbar || controller.isEditingQuickInputs || controller.credentialPrompt != null ||
                controller.alert != null
        val locksPointer =
            controller.isPointerLockOn && pointerLockAvailable && !pointerLockPaused && controller.pointerLockCanResume &&
                (dock == null || controller.isSystemPointerOverPicture)

        val available by rememberUpdatedState(pointerLockAvailable)
        val paused by rememberUpdatedState(pointerLockPaused)
        LaunchedEffect(controller) {
            snapshotFlow { available }.drop(1).collect { controller.pointerLockAvailabilityDidChange(it) }
        }
        LaunchedEffect(controller) {
            snapshotFlow { paused }.drop(1).collect { if (!it && !controller.pointerLockCanResume) controller.setPointerLock(false) }
        }
        LaunchedEffect(screenView, locksPointer) {
            screenView?.input?.setLocksPointer(locksPointer)
        }
        LaunchedEffect(controller) {
            snapshotFlow { controller.isFullScreen }.drop(1).collect { controller.isToolbarVisible = false }
        }
        val docked = dock != null
        LaunchedEffect(docked) {
            controller.isToolbarDocked = docked
            if (docked) controller.isToolbarVisible = false
        }
        val resolutionAvailable = SessionCommand.Resolution.isOffered(controller) && SessionCommand.Resolution.isEnabled(controller)
        LaunchedEffect(resolutionAvailable) {
            if (!resolutionAvailable) controller.isShowingResolution = false
        }

        Box(Modifier.fillMaxSize()) {
            RemoteScreen(controller, Modifier.fillMaxSize()) { view ->
                screenView = view
                onView(view)
            }
            if (controller.dropTarget == SessionController.DropTarget.Desktop) {
                DesktopDropHighlight()
            }
            AppTheme(darkTheme = true) {
                SessionStatusOverlay(controller, controller::close)
            }
        }

        if (AppSettings.showsDebugInfo && controller.phase.isActive) {
            val obstructions = controller.debugInfoObstructions
            DebugInfoView(
                controller,
                Modifier.layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                    val rtl = layoutDirection == LayoutDirection.Rtl
                    val inset = obstructions?.topCornerInset(
                        left = rtl,
                        area = Rect(0f, 0f, constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat()),
                        size = Size(placeable.width.toFloat(), placeable.height.toFloat()),
                    )?.roundToInt()
                    val margin = 8.dp.roundToPx()
                    val top = inset ?: (safeInsets.getTop(this) + margin)
                    val end = inset ?: ((if (rtl) safeInsets.getLeft(this, layoutDirection) else safeInsets.getRight(this, layoutDirection)) + margin)
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        placeable.place(if (rtl) end else constraints.maxWidth - end - placeable.width, top)
                    }
                },
            )
        }
        FloatingToolbar(
            controller,
            plan,
            dock,
            closes = WindowInsets.captionBar.getTop(density) == 0,
            onClose = controller::close,
        )
        if (controller.isControlling) {
            val gestures = WindowInsets.systemGestures
            val left = gestures.getLeft(density, LayoutDirection.Ltr)
            val right = gestures.getRight(density, LayoutDirection.Ltr)
            val height = min(constraints.maxHeight, with(density) { MAXIMUM_GESTURE_EXCLUSION.roundToPx() })
            val top = (constraints.maxHeight - height) / 2
            val pictureLeft =
                controller.letterbox
                    ?.left
                    ?.takeIf { it.width > 0 }
                    ?.right ?: 0f
            if (left > 0 && pictureLeft < left) {
                GestureExclusion(IntRect(0, top, left, top + height))
            }
            if (right > 0 && pictureLeft < right) {
                GestureExclusion(IntRect(constraints.maxWidth - right, top, constraints.maxWidth, top + height))
            }
        }
        if (controller.isKeyboardVisible) {
            val view = screenView
            DisposableEffect(view) {
                onDispose { view?.accessoryBarWindowTop = null }
            }
            KeyboardAccessoryBar(
                controller,
                Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                    .onGloballyPositioned { view?.accessoryBarWindowTop = it.boundsInWindow().top },
            )
        }

        if (controller.isCustomizingToolbar) {
            CustomizeToolbarView(controller, toolbarSlots) { controller.isCustomizingToolbar = false }
        }
    }

    if (controller.isEditingQuickInputs) {
        FormDialog(onDismissRequest = { controller.isEditingQuickInputs = false }) {
            QuickInputView(onClose = { controller.isEditingQuickInputs = false })
        }
    }
    if (controller.isShowingResolution) {
        ResolutionPanel(controller) { controller.isShowingResolution = false }
    }
    AlertHost(controller.alert) { controller.alert = null }
    val context = LocalContext.current
    val notice = controller.notice
    var toast by remember { mutableStateOf<Toast?>(null) }
    LaunchedEffect(notice) {
        if (notice == null) return@LaunchedEffect
        controller.notice = null
        toast?.cancel()
        toast = Toast.makeText(context, notice.message, Toast.LENGTH_SHORT).apply { show() }
    }
    controller.credentialPrompt?.let { prompt ->
        key(prompt.id) {
            CredentialsView(
                prompt = prompt,
                computerName = controller.title,
                onSubmit = { credentials, remembers -> controller.submitCredentials(credentials, remembers) },
                onCancel = controller::cancelCredentials,
            )
        }
    }
}

@Composable
private fun DesktopDropHighlight() {
    Box(
        Modifier
            .fillMaxSize()
            .border(4.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(24.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, shadowElevation = 3.dp) {
            Row(
                Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.UploadFile, contentDescription = null)
                Text(stringResource(R.string.chrome_send_to_desktop), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun BoxScope.GestureExclusion(rect: IntRect) {
    val density = LocalDensity.current
    Box(
        Modifier
            .align(AbsoluteAlignment.TopLeft)
            .absoluteOffset { IntOffset(rect.left, rect.top) }
            .size(with(density) { DpSize(rect.width.toDp(), rect.height.toDp()) })
            .systemGestureExclusion(),
    )
}

private val MAXIMUM_GESTURE_EXCLUSION = 200.dp
