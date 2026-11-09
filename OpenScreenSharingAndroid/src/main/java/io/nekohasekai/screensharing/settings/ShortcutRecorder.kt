package io.nekohasekai.screensharing.settings

import android.media.AudioManager
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalView
import io.nekohasekai.screensharing.R
import io.nekohasekai.screensharing.model.EventModifiers
import io.nekohasekai.screensharing.model.KeyShortcut
import io.nekohasekai.screensharing.model.KeyShortcuts

sealed interface ShortcutPress {
    data object Cancel : ShortcutPress

    data object Clear : ShortcutPress

    data class Shortcut(val shortcut: KeyShortcut) : ShortcutPress
}

@Composable
fun <T : Any> ShortcutRecorder(
    recording: T?,
    onRecordingChange: (T?) -> Unit,
    onPress: (T, ShortcutPress) -> Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    val focusRequester = remember { FocusRequester() }
    val currentRecording by rememberUpdatedState(recording)
    val currentOnPress by rememberUpdatedState(onPress)
    val currentOnRecordingChange by rememberUpdatedState(onRecordingChange)
    val isActive = recording != null

    LaunchedEffect(isActive) {
        KeyShortcuts.isRecording = isActive
        if (isActive) focusRequester.requestFocus()
    }
    DisposableEffect(Unit) {
        onDispose {
            KeyShortcuts.isRecording = false
            if (currentRecording != null) currentOnRecordingChange(null)
        }
    }

    Box(
        modifier
            .onPreviewKeyEvent { keyEvent ->
                val target = currentRecording ?: return@onPreviewKeyEvent false
                val event = keyEvent.nativeKeyEvent
                when (event.keyCode) {
                    KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE ->
                        return@onPreviewKeyEvent false
                }
                if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0 || KeyEvent.isModifierKey(event.keyCode)) {
                    return@onPreviewKeyEvent true
                }
                val plain = EventModifiers.of(event.metaState).isEmpty
                val press = when {
                    event.keyCode == KeyEvent.KEYCODE_ESCAPE && plain -> ShortcutPress.Cancel
                    (event.keyCode == KeyEvent.KEYCODE_DEL || event.keyCode == KeyEvent.KEYCODE_FORWARD_DEL) && plain -> ShortcutPress.Clear
                    else -> KeyShortcut.of(event)?.let { ShortcutPress.Shortcut(it) }
                }
                if (press == null || !currentOnPress(target, press)) {
                    view.performHapticFeedback(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS,
                    )
                    view.context.getSystemService(AudioManager::class.java)?.playSoundEffect(AudioManager.FX_KEYPRESS_INVALID)
                }
                true
            }
            .focusRequester(focusRequester)
            .focusProperties { canFocus = isActive }
            .focusable(),
    ) {
        content()
    }
    BackHandler(enabled = isActive) { currentOnRecordingChange(null) }
}
