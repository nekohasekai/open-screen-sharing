package io.nekohasekai.screensharing.sessionui

import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.AdsClick
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.HdrOn
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.KeyboardHide
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Monitor
import androidx.compose.material.icons.outlined.Mouse
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.ViewCarousel
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import io.nekohasekai.screensharing.R
import io.nekohasekai.screensharing.model.AppSettings
import io.nekohasekai.screensharing.model.RemoteProtocol
import io.nekohasekai.screensharing.model.ShortcutAction
import io.nekohasekai.screensharing.session.KeyChord
import io.nekohasekai.screensharing.session.RemotePlatform
import io.nekohasekai.screensharing.session.SessionCapabilities
import io.nekohasekai.screensharing.session.SessionController
import io.nekohasekai.screensharing.session.SessionMode
import io.nekohasekai.screensharing.session.SessionPhase
import io.nekohasekai.screensharing.session.SystemCommand

enum class SessionCommand(
    val rawValue: String,
) {
    ZoomMode("zoomMode"),
    Scale("scale"),
    Displays("displays"),
    Quality("quality"),
    Resolution("resolution"),
    Hdr("hdr"),
    Audio("audio"),
    SendAudio("sendAudio"),
    NativeTouch("nativeTouch"),
    Observe("control"),
    PointerLock("pointerLock"),
    Keyboard("keyboard"),
    QuickInput("quickInput"),
    MissionControl("missionControl"),
    AppWindows("appWindows"),
    ShowDesktop("showDesktop"),
    Launchpad("launchpad"),
    Home("home"),
    AppSwitcher("appSwitcher"),
    Lock("lock"),
    Clipboard("clipboard"),
    DebugInfo("debugInfo"),
    ;

    @get:StringRes
    val title: Int
        get() =
            when (this) {
                ZoomMode -> R.string.model_zoom_mode
                Scale -> R.string.chrome_scaling
                Displays -> R.string.chrome_displays
                Quality -> R.string.chrome_quality
                Resolution -> R.string.chrome_resolution
                Hdr -> R.string.model_high_dynamic_range
                Audio -> R.string.model_receive_audio
                SendAudio -> R.string.model_send_audio
                Observe -> R.string.model_observe_mode
                PointerLock -> R.string.model_pointer_lock
                NativeTouch -> R.string.chrome_native_touch
                Keyboard -> R.string.settings_keyboard
                QuickInput -> R.string.settings_quick_input
                MissionControl, AppWindows, ShowDesktop, Launchpad -> systemCommand!!.title
                Home -> R.string.model_home_screen
                AppSwitcher -> R.string.model_app_switcher
                Lock -> R.string.model_lock
                Clipboard -> R.string.chrome_clipboard
                DebugInfo -> R.string.model_debug_panel
            }

    val systemImage: ImageVector
        get() =
            when (this) {
                ZoomMode -> Icons.Outlined.ZoomIn
                Scale -> Icons.Outlined.OpenInFull
                Displays -> Icons.Outlined.Monitor
                Quality -> Icons.Outlined.HighQuality
                Resolution -> Icons.Outlined.AspectRatio
                Hdr -> Icons.Outlined.HdrOn
                Audio -> Icons.AutoMirrored.Outlined.VolumeUp
                SendAudio -> Icons.Outlined.Mic
                Observe -> Icons.Outlined.Visibility
                PointerLock -> Icons.Outlined.Mouse
                NativeTouch -> Icons.Outlined.TouchApp
                Keyboard -> Icons.Outlined.Keyboard
                QuickInput -> Icons.Outlined.TextFields
                MissionControl, AppWindows, ShowDesktop, Launchpad -> systemCommand!!.systemImage
                Home -> Icons.Outlined.Home
                AppSwitcher -> Icons.Outlined.ViewCarousel
                Lock -> Icons.Outlined.Lock
                Clipboard -> Icons.Outlined.ContentPaste
                DebugInfo -> Icons.Outlined.Speed
            }

    val category: SessionCommandCategory
        get() =
            when (this) {
                ZoomMode, Scale, Displays, Quality, Resolution, Hdr -> SessionCommandCategory.View
                Audio, SendAudio -> SessionCommandCategory.Sound
                Observe, PointerLock, NativeTouch, Keyboard, QuickInput, MissionControl, AppWindows, ShowDesktop, Launchpad,
                Home, AppSwitcher, Lock,
                -> SessionCommandCategory.Control
                Clipboard -> SessionCommandCategory.Clipboard
                DebugInfo -> SessionCommandCategory.Debug
            }

    val systemCommand: SystemCommand?
        get() =
            when (this) {
                MissionControl -> SystemCommand.MissionControl
                AppWindows -> SystemCommand.AppWindows
                ShowDesktop -> SystemCommand.ShowDesktop
                Launchpad -> SystemCommand.Launchpad
                else -> null
            }

    val shortcutAction: ShortcutAction?
        get() =
            when (this) {
                ZoomMode -> ShortcutAction.ZoomMode
                Scale -> ShortcutAction.ActualSize
                Hdr -> ShortcutAction.Hdr
                Audio -> ShortcutAction.ReceiveAudio
                SendAudio -> ShortcutAction.SendAudio
                Observe -> ShortcutAction.Observe
                PointerLock -> ShortcutAction.PointerLock
                Keyboard -> ShortcutAction.Keyboard
                MissionControl, AppWindows, ShowDesktop, Launchpad -> ShortcutAction.system(systemCommand!!)
                Home -> ShortcutAction.Home
                AppSwitcher -> ShortcutAction.AppSwitcher
                Lock -> ShortcutAction.Lock
                DebugInfo -> ShortcutAction.DebugPanel
                else -> null
            }

    fun applies(platform: RemotePlatform): Boolean = when (this) {
        Home, AppSwitcher, Lock -> platform == RemotePlatform.IOS
        Displays, Quality, Resolution, Hdr -> platform != RemotePlatform.IOS
        MissionControl, AppWindows, ShowDesktop, Launchpad -> platform == RemotePlatform.Mac
        ZoomMode, Scale, Audio, SendAudio, Observe, PointerLock, QuickInput, Clipboard, DebugInfo -> true
        Keyboard -> true
        NativeTouch -> platform != RemotePlatform.IOS
    }

    fun isOffered(controller: SessionController): Boolean {
        if (!applies(controller.platform)) return false
        val capabilities = controller.capabilities
        val known = !capabilities.isEmpty
        fun offers(capability: SessionCapabilities): Boolean = !known || capability in capabilities
        return when (this) {
            ZoomMode, DebugInfo -> {
                true
            }
            Scale -> {
                !controller.pictureMatchesWindow
            }
            Quality -> {
                !controller.usesHighPerformance && offers(SessionCapabilities.Quality)
            }
            Audio -> {
                controller.usesHighPerformance || SessionCapabilities.Audio in capabilities
            }
            SendAudio -> {
                controller.route.remoteProtocol == RemoteProtocol.Rdp
            }
            Displays -> {
                controller.showsDisplayPicker
            }
            Resolution -> {
                controller.offersResolution
            }
            Hdr -> {
                controller.usesHighPerformance && controller.displaySupportsHDR && offers(SessionCapabilities.HighPerformance) &&
                    controller.networkSupportsHDR
            }
            Observe -> {
                offers(SessionCapabilities.Control) && offers(SessionCapabilities.Observe)
            }
            Keyboard, QuickInput, Lock -> {
                offers(SessionCapabilities.Control)
            }
            NativeTouch -> {
                SessionCapabilities.Touch in capabilities
            }
            PointerLock -> {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && offers(SessionCapabilities.Control) && io.nekohasekai.screensharing.sessionui.PointerLock.hasMouse
            }
            Home -> {
                !known || controller.canPerform(SystemCommand.ShowDesktop)
            }
            AppSwitcher -> {
                !known || controller.canPerform(SystemCommand.MissionControl)
            }
            MissionControl, AppWindows, ShowDesktop, Launchpad -> {
                !known || controller.canPerform(systemCommand!!)
            }
            Clipboard -> {
                offers(SessionCapabilities.Clipboard)
            }
        }
    }

    fun isEnabled(controller: SessionController): Boolean {
        val connected = controller.phase == SessionPhase.Connected
        return when (this) {
            ZoomMode, Scale, Quality, Audio, SendAudio, DebugInfo -> {
                true
            }
            Displays, Clipboard -> {
                connected
            }
            Resolution -> {
                controller.isControlling
            }
            Hdr -> {
                controller.canUseHDR
            }
            Observe -> {
                SessionCapabilities.Control in controller.capabilities
            }
            PointerLock -> {
                controller.canLockPointer
            }
            NativeTouch, Keyboard, QuickInput, MissionControl, AppWindows, ShowDesktop, Launchpad, Home, AppSwitcher, Lock -> {
                controller.isControlling
            }
        }
    }

    companion object {
        fun of(rawValue: String): SessionCommand? = entries.firstOrNull { it.rawValue == rawValue }
    }
}

enum class SessionCommandCategory {
    View,
    Sound,
    Control,
    Clipboard,
    Debug,
    ;

    @get:StringRes
    val title: Int
        get() =
            when (this) {
                View -> R.string.model_view
                Sound -> R.string.model_sound
                Control -> R.string.model_control
                Clipboard -> R.string.chrome_clipboard
                Debug -> R.string.model_debug
            }

    val systemImage: ImageVector
        get() =
            when (this) {
                View -> Icons.Outlined.DesktopWindows
                Sound -> Icons.AutoMirrored.Outlined.VolumeUp
                Control -> Icons.Outlined.AdsClick
                Clipboard -> Icons.Outlined.ContentPaste
                Debug -> Icons.Outlined.BugReport
            }

    val commands: List<SessionCommand> get() = SessionCommand.entries.filter { it.category == this }
}

val SessionController.showsDisplayPicker: Boolean
    get() = displays.size > 1 && !usesHighPerformance

val SessionController.offersResolution: Boolean
    get() = platform != RemotePlatform.IOS && wantsVirtualDisplay && (capabilities.isEmpty || SessionCapabilities.VirtualDisplay in capabilities)

enum class SessionCommandStyle {
    BarItem,
    MenuItem,
    ;

    val isBar: Boolean get() = this != MenuItem
}

@Composable
fun SessionCommandView(
    command: SessionCommand,
    controller: SessionController,
    style: SessionCommandStyle = SessionCommandStyle.MenuItem,
    menu: SessionMenuState? = null,
) {
    val enabled = command.isEnabled(controller)
    if (style.isBar) {
        BarCommand(command, controller, enabled)
    } else {
        MenuCommand(command, controller, enabled, checkNotNull(menu))
    }
}

@Composable
private fun BarCommand(
    command: SessionCommand,
    controller: SessionController,
    enabled: Boolean,
) {
    val title = stringResource(command.title)
    val icon = command.systemImage
    when (command) {
        SessionCommand.ZoomMode -> {
            BarToggle(title, icon, controller.isZoomModeOn, enabled) { controller.setZoomMode(it) }
        }
        SessionCommand.Scale -> {
            ScaleButton(controller, SessionCommandStyle.BarItem, enabled, null)
        }
        SessionCommand.Displays, SessionCommand.Quality, SessionCommand.QuickInput, SessionCommand.Clipboard -> {
            val menu = rememberSessionMenuState(SessionMenuPage.Command(command))
            Box {
                BarButton(title, icon, enabled, selected = command == SessionCommand.Clipboard && controller.clipboardSharing) { menu.open() }
                SessionMenu(controller, menu)
            }
        }
        SessionCommand.Resolution -> {
            BarButton(title, icon, enabled) { controller.isShowingResolution = true }
        }
        SessionCommand.Hdr -> {
            BarToggle(title, icon, controller.hdrEnabled && controller.canUseHDR, enabled) { controller.setHDR(it) }
        }
        SessionCommand.Audio -> {
            BarToggle(title, icon, controller.receivesAudio, enabled) { controller.setReceivesAudio(it) }
        }
        SessionCommand.SendAudio -> {
            BarToggle(title, icon, controller.sendsAudio, enabled) { controller.setSendsAudio(it) }
        }
        SessionCommand.Observe -> {
            BarToggle(title, icon, controller.mode == SessionMode.Observe, enabled) {
                controller.setMode(if (it) SessionMode.Observe else SessionMode.Control)
            }
        }
        SessionCommand.PointerLock -> {
            BarToggle(title, icon, controller.isPointerLockOn, enabled) { controller.setPointerLock(it) }
        }
        SessionCommand.NativeTouch -> {
            BarToggle(title, icon, controller.isNativeTouchOn, enabled) { controller.setNativeTouch(it) }
        }
        SessionCommand.Keyboard -> {
            val visible = controller.isKeyboardVisible
            BarButton(
                stringResource(if (visible) R.string.chrome_hide_keyboard else R.string.chrome_show_keyboard),
                if (visible) Icons.Outlined.KeyboardHide else Icons.Outlined.Keyboard,
                enabled,
            ) {
                controller.toggleKeyboard()
                if (controller.isToolbarVisible) controller.toggleToolbar()
            }
        }
        SessionCommand.MissionControl, SessionCommand.AppWindows, SessionCommand.ShowDesktop, SessionCommand.Launchpad -> {
            BarButton(title, icon, enabled) { controller.perform(command.systemCommand!!) }
        }
        SessionCommand.Home -> {
            BarButton(title, icon, enabled) { controller.perform(SystemCommand.ShowDesktop) }
        }
        SessionCommand.AppSwitcher -> {
            BarButton(title, icon, enabled) { controller.perform(SystemCommand.MissionControl) }
        }
        SessionCommand.Lock -> {
            BarButton(title, icon, enabled) { controller.sendChord(KeyChord.sideButton) }
        }
        SessionCommand.DebugInfo -> {
            BarToggle(title, icon, AppSettings.showsDebugInfo, enabled) { AppSettings.showsDebugInfo = it }
        }
    }
}

@Composable
private fun MenuCommand(
    command: SessionCommand,
    controller: SessionController,
    enabled: Boolean,
    menu: SessionMenuState,
) {
    when (command) {
        SessionCommand.ZoomMode -> {
            MenuToggle(command, enabled, menu, controller.isZoomModeOn) { controller.setZoomMode(it) }
        }
        SessionCommand.Scale -> {
            ScaleButton(controller, SessionCommandStyle.MenuItem, enabled, menu)
        }
        SessionCommand.Displays -> {
            DisplayPicker(controller, inOwnMenu = false, enabled, menu)
        }
        SessionCommand.Quality -> {
            QualityPicker(controller, inOwnMenu = false, enabled, menu)
        }
        SessionCommand.QuickInput -> {
            ExpandableMenuItem(
                stringResource(command.title),
                command.systemImage,
                expanded = menu.expandedCommand == command,
                enabled = enabled,
                onToggle = { menu.toggle(command) },
            ) {
                QuickInputItems(controller, menu)
            }
        }
        SessionCommand.Resolution -> {
            MenuButton(command, enabled, menu) { controller.isShowingResolution = true }
        }
        SessionCommand.Hdr -> {
            MenuToggle(command, enabled, menu, controller.hdrEnabled && controller.canUseHDR) { controller.setHDR(it) }
        }
        SessionCommand.Audio -> {
            MenuToggle(command, enabled, menu, controller.receivesAudio) { controller.setReceivesAudio(it) }
        }
        SessionCommand.SendAudio -> {
            MenuToggle(command, enabled, menu, controller.sendsAudio) { controller.setSendsAudio(it) }
        }
        SessionCommand.Observe -> {
            MenuToggle(command, enabled, menu, controller.mode == SessionMode.Observe) {
                controller.setMode(if (it) SessionMode.Observe else SessionMode.Control)
            }
        }
        SessionCommand.PointerLock -> {
            MenuToggle(command, enabled, menu, controller.isPointerLockOn) { controller.setPointerLock(it) }
        }
        SessionCommand.NativeTouch -> {
            MenuToggle(command, enabled, menu, controller.isNativeTouchOn) { controller.setNativeTouch(it) }
        }
        SessionCommand.Keyboard -> {
            val visible = controller.isKeyboardVisible
            SessionMenuItem(
                stringResource(if (visible) R.string.chrome_hide_keyboard else R.string.chrome_show_keyboard),
                if (visible) Icons.Outlined.KeyboardHide else Icons.Outlined.Keyboard,
                enabled,
                command.shortcutAction,
            ) {
                menu.dismiss()
                controller.toggleKeyboard()
            }
        }
        SessionCommand.MissionControl, SessionCommand.AppWindows, SessionCommand.ShowDesktop, SessionCommand.Launchpad -> {
            MenuButton(command, enabled, menu) { controller.perform(command.systemCommand!!) }
        }
        SessionCommand.Home -> {
            MenuButton(command, enabled, menu) { controller.perform(SystemCommand.ShowDesktop) }
        }
        SessionCommand.AppSwitcher -> {
            MenuButton(command, enabled, menu) { controller.perform(SystemCommand.MissionControl) }
        }
        SessionCommand.Lock -> {
            MenuButton(command, enabled, menu) { controller.sendChord(KeyChord.sideButton) }
        }
        SessionCommand.Clipboard -> {
            ClipboardMenuItems(controller, enabled, menu)
        }
        SessionCommand.DebugInfo -> {
            MenuToggle(command, enabled, menu, AppSettings.showsDebugInfo) { AppSettings.showsDebugInfo = it }
        }
    }
}

@Composable
private fun MenuToggle(
    command: SessionCommand,
    enabled: Boolean,
    menu: SessionMenuState,
    isOn: Boolean,
    set: (Boolean) -> Unit,
) {
    SessionMenuItem(stringResource(command.title), command.systemImage, enabled, command.shortcutAction, checked = isOn) {
        menu.dismiss()
        set(!isOn)
    }
}

@Composable
private fun MenuButton(
    command: SessionCommand,
    enabled: Boolean,
    menu: SessionMenuState,
    action: () -> Unit,
) {
    SessionMenuItem(stringResource(command.title), command.systemImage, enabled, command.shortcutAction) {
        menu.dismiss()
        action()
    }
}
