package io.nekohasekai.screensharing.sessionui

import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardTab
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.KeyboardHide
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import io.nekohasekai.screensharing.R
import io.nekohasekai.screensharing.session.MacKey
import io.nekohasekai.screensharing.session.ModifierKey
import io.nekohasekai.screensharing.session.RemotePlatform
import io.nekohasekai.screensharing.session.SessionController
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

@Composable
fun KeyboardAccessoryBar(
    controller: SessionController,
    modifier: Modifier = Modifier,
) {
    val software = !controller.usesHardwareKeyboard
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            Modifier
                .height(64.dp)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KeyGroup {
                TextKey(stringResource(R.string.model_escape), "esc") { controller.typeKey(MacKey.escape) }
                if (software) {
                    IconKey(stringResource(R.string.model_tab), Icons.AutoMirrored.Outlined.KeyboardTab) { controller.typeKey(MacKey.tab) }
                }
                for (modifierKey in if (controller.info.platform == RemotePlatform.Pc) ModifierKey.pcOrder else ModifierKey.entries) {
                    key(modifierKey) { ModifierKeyButton(controller, modifierKey) }
                }
            }
            if (software) {
                KeyGroup {
                    IconKey(stringResource(R.string.model_left_arrow), Icons.Outlined.KeyboardArrowUp, rotation = -90f) { controller.typeKey(MacKey.left) }
                    IconKey(stringResource(R.string.model_up_arrow), Icons.Outlined.KeyboardArrowUp) { controller.typeKey(MacKey.up) }
                    IconKey(stringResource(R.string.model_down_arrow), Icons.Outlined.KeyboardArrowDown) { controller.typeKey(MacKey.down) }
                    IconKey(stringResource(R.string.model_right_arrow), Icons.Outlined.KeyboardArrowUp, rotation = 90f) { controller.typeKey(MacKey.right) }
                }
            }
            KeyGroup {
                val functionKeys = rememberSessionMenuState(SessionMenuPage.FunctionKeys)
                Box {
                    TextKey(stringResource(R.string.model_function_keys), "fn") { functionKeys.open() }
                    SessionMenu(controller, functionKeys, AccessoryMenuProperties)
                }
                val quickInput = rememberSessionMenuState(SessionMenuPage.Command(SessionCommand.QuickInput))
                Box {
                    IconKey(stringResource(SessionCommand.QuickInput.title), SessionCommand.QuickInput.systemImage) { quickInput.open() }
                    SessionMenu(controller, quickInput, AccessoryMenuProperties)
                }
            }
            KeyGroup {
                IconKey(stringResource(R.string.chrome_hide_keyboard), Icons.Outlined.KeyboardHide) { controller.setKeyboardVisible(false) }
            }
        }
    }
}

private val AccessoryMenuProperties = PopupProperties(focusable = false)

@Composable
private fun KeyGroup(content: @Composable RowScope.() -> Unit) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Row(
            Modifier.padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

@Composable
private fun IconKey(
    title: String,
    icon: ImageVector,
    rotation: Float = 0f,
    action: () -> Unit,
) {
    IconButton(onClick = action, modifier = Modifier.size(48.dp)) {
        Icon(icon, contentDescription = title, modifier = Modifier.rotate(rotation))
    }
}

@Composable
private fun TextKey(
    title: String,
    text: String,
    action: () -> Unit,
) {
    Box(
        Modifier
            .defaultMinSize(48.dp, 48.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = action)
            .semantics { contentDescription = title },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun ModifierKeyButton(
    controller: SessionController,
    modifierKey: ModifierKey,
) {
    val isLocked = modifierKey in controller.lockedModifiers
    val isActive = isLocked || modifierKey in controller.pressedModifiers || modifierKey in controller.stickyModifiers
    val pcLabel = if (controller.info.platform == RemotePlatform.Pc) modifierKey.pcLabel else null
    val controlling = controller.isControlling
    val title = stringResource(pcLabel?.title ?: modifierKey.title)
    val state =
        stringResource(
            when {
                isLocked -> R.string.chrome_locked
                isActive -> R.string.settings_on
                else -> R.string.settings_off
            },
        )
    val lockLabel = stringResource(if (isLocked) R.string.chrome_unlock else R.string.model_lock)
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val lastTap = remember { longArrayOf(-DOUBLE_TAP_INTERVAL) }

    LaunchedEffect(controller, modifierKey) {
        snapshotFlow {
            modifierKey in controller.lockedModifiers || modifierKey in controller.pressedModifiers || modifierKey in controller.stickyModifiers
        }.drop(1).collect { active ->
            view.performHapticFeedback(
                when {
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> HapticFeedbackConstants.CLOCK_TICK
                    active -> HapticFeedbackConstants.TOGGLE_ON
                    else -> HapticFeedbackConstants.TOGGLE_OFF
                },
            )
        }
    }

    fun tapped() {
        val now = SystemClock.uptimeMillis()
        if (modifierKey in controller.lockedModifiers) {
            controller.setModifierLocked(modifierKey, false)
        } else if (now - lastTap[0] < DOUBLE_TAP_INTERVAL && modifierKey in controller.stickyModifiers) {
            controller.setModifierLocked(modifierKey, true)
        } else {
            controller.toggleSticky(modifierKey)
        }
        lastTap[0] = now
    }

    Box(
        Modifier
            .size(48.dp)
            .alpha(if (controlling) 1f else 0.4f)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = title
                stateDescription = state
                selected = isActive
                if (controlling) {
                    onClick {
                        controller.toggleSticky(modifierKey)
                        true
                    }
                    customActions =
                        listOf(
                            CustomAccessibilityAction(lockLabel) {
                                controller.setModifierLocked(modifierKey, !isLocked)
                                true
                            },
                        )
                } else {
                    disabled()
                }
            }.pointerInput(controller, modifierKey, controlling) {
                if (!controlling) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var moved = false
                    var holding = false
                    var hold: Job? = null
                    try {
                        hold =
                            scope.launch {
                                delay(HOLD_DELAY)
                                if (moved) return@launch
                                holding = true
                                controller.setModifierPressed(modifierKey, true)
                            }
                        val slop = MOVE_SLOP.toPx()
                        var ended = false
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if ((change.position - down.position).getDistance() > slop) moved = true
                            if (change.changedToUp()) {
                                ended = true
                                break
                            }
                            if (!change.pressed) break
                        }
                        hold.cancel()
                        if (ended && !holding && !moved) tapped()
                    } finally {
                        hold?.cancel()
                        if (holding) controller.setModifierPressed(modifierKey, false)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (isActive) {
            Box(
                Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
            )
        }
        val color = if (isActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
        if (pcLabel != null) {
            Text(pcLabel.text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = color)
        } else {
            Text(modifierKey.symbol, style = MaterialTheme.typography.titleMedium, color = color)
        }
        if (isLocked) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 10.dp)
                    .size(12.dp, 3.dp)
                    .background(MaterialTheme.colorScheme.onPrimary, CircleShape),
            )
        }
    }
}

private const val HOLD_DELAY = 300L
private const val DOUBLE_TAP_INTERVAL = 350L
private val MOVE_SLOP = 10.dp
