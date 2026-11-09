package io.nekohasekai.screensharing.app

import io.nekohasekai.screensharing.model.AppSettings
import io.nekohasekai.screensharing.model.SessionQuality
import io.nekohasekai.screensharing.model.ShortcutAction
import io.nekohasekai.screensharing.session.DisplaySelection
import io.nekohasekai.screensharing.session.KeyChord
import io.nekohasekai.screensharing.session.RemotePlatform
import io.nekohasekai.screensharing.session.ScaleMode
import io.nekohasekai.screensharing.session.SessionController
import io.nekohasekai.screensharing.session.SessionMode
import io.nekohasekai.screensharing.session.SessionPhase
import io.nekohasekai.screensharing.session.SystemCommand
import io.nekohasekai.screensharing.sessionui.SessionCommand
import io.nekohasekai.screensharing.sessionui.showsDisplayPicker

object AppCommands {
    fun isAvailable(
        action: ShortcutAction,
        session: SessionController,
    ): Boolean {
        val connected = session.phase == SessionPhase.Connected
        return when (action) {
            ShortcutAction.NewConnection -> {
                false
            }
            ShortcutAction.Disconnect, ShortcutAction.SyncClipboard, ShortcutAction.ZoomMode, ShortcutAction.CustomizeToolbar,
            ShortcutAction.DebugPanel,
            -> {
                true
            }
            ShortcutAction.ReceiveClipboard, ShortcutAction.SendClipboard -> {
                connected && !session.clipboardSharing
            }
            ShortcutAction.ActualSize -> {
                !session.pictureMatchesWindow
            }
            ShortcutAction.Display1, ShortcutAction.Display2, ShortcutAction.Display3, ShortcutAction.Display4,
            ShortcutAction.Display5, ShortcutAction.Display6, ShortcutAction.Display7, ShortcutAction.Display8,
            -> {
                session.showsDisplayPicker && connected && ShortcutAction.displays.indexOf(action) < session.displays.size
            }
            ShortcutAction.AllDisplays -> {
                session.showsDisplayPicker && connected
            }
            ShortcutAction.AdaptiveQuality, ShortcutAction.FullQuality -> {
                SessionCommand.Quality.isOffered(session)
            }
            ShortcutAction.Hdr -> {
                session.canUseHDR
            }
            ShortcutAction.Toolbar -> {
                !session.isToolbarDocked
            }
            ShortcutAction.ReceiveAudio -> {
                SessionCommand.Audio.isOffered(session)
            }
            ShortcutAction.SendAudio -> {
                SessionCommand.SendAudio.isOffered(session)
            }
            ShortcutAction.Observe -> {
                SessionCommand.Observe.isOffered(session) && SessionCommand.Observe.isEnabled(session)
            }
            ShortcutAction.PointerLock -> {
                SessionCommand.PointerLock.isOffered(session) && session.canLockPointer
            }
            ShortcutAction.Keyboard -> {
                session.isControlling
            }
            ShortcutAction.Home, ShortcutAction.AppSwitcher, ShortcutAction.Lock -> {
                val command =
                    when (action) {
                        ShortcutAction.Home -> SessionCommand.Home
                        ShortcutAction.AppSwitcher -> SessionCommand.AppSwitcher
                        else -> SessionCommand.Lock
                    }
                session.platform == RemotePlatform.IOS && command.isOffered(session) && command.isEnabled(session)
            }
            ShortcutAction.MissionControl, ShortcutAction.AppWindows, ShortcutAction.ShowDesktop, ShortcutAction.Launchpad -> {
                session.platform == RemotePlatform.Mac && session.isControlling && session.canPerform(systemCommand(action))
            }
        }
    }

    fun perform(
        action: ShortcutAction,
        session: SessionController,
    ) {
        when (action) {
            ShortcutAction.NewConnection -> {
                Unit
            }
            ShortcutAction.Disconnect -> {
                session.close()
            }
            ShortcutAction.SyncClipboard -> {
                session.setClipboardSharing(!session.clipboardSharing)
            }
            ShortcutAction.ReceiveClipboard -> {
                session.getClipboardFromMac()
            }
            ShortcutAction.SendClipboard -> {
                session.sendClipboardToMac()
            }
            ShortcutAction.ZoomMode -> {
                session.setZoomMode(!session.isZoomModeOn)
            }
            ShortcutAction.ActualSize -> {
                session.setScale(if (session.scaleMode == ScaleMode.Fit) ScaleMode.ActualSize else ScaleMode.Fit)
            }
            ShortcutAction.Display1, ShortcutAction.Display2, ShortcutAction.Display3, ShortcutAction.Display4,
            ShortcutAction.Display5, ShortcutAction.Display6, ShortcutAction.Display7, ShortcutAction.Display8,
            -> {
                session.displays.getOrNull(ShortcutAction.displays.indexOf(action))?.let { session.selectDisplay(DisplaySelection.Display(it.id)) }
            }
            ShortcutAction.AllDisplays -> {
                session.selectDisplay(DisplaySelection.All)
            }
            ShortcutAction.AdaptiveQuality -> {
                session.setQuality(SessionQuality.Adaptive)
            }
            ShortcutAction.FullQuality -> {
                session.setQuality(SessionQuality.Full)
            }
            ShortcutAction.Hdr -> {
                session.setHDR(!session.hdrEnabled)
            }
            ShortcutAction.Toolbar -> {
                session.toggleToolbar()
            }
            ShortcutAction.CustomizeToolbar -> {
                session.isCustomizingToolbar = true
            }
            ShortcutAction.ReceiveAudio -> {
                session.setReceivesAudio(!session.receivesAudio)
            }
            ShortcutAction.SendAudio -> {
                session.setSendsAudio(!session.sendsAudio)
            }
            ShortcutAction.Observe -> {
                session.setMode(if (session.mode == SessionMode.Observe) SessionMode.Control else SessionMode.Observe)
            }
            ShortcutAction.PointerLock -> {
                session.setPointerLock(!session.isPointerLockOn)
            }
            ShortcutAction.Keyboard -> {
                session.toggleKeyboard()
            }
            ShortcutAction.Home -> {
                session.perform(SystemCommand.ShowDesktop)
            }
            ShortcutAction.AppSwitcher -> {
                session.perform(SystemCommand.MissionControl)
            }
            ShortcutAction.Lock -> {
                session.sendChord(KeyChord.sideButton)
            }
            ShortcutAction.MissionControl, ShortcutAction.AppWindows, ShortcutAction.ShowDesktop, ShortcutAction.Launchpad -> {
                session.perform(systemCommand(action))
            }
            ShortcutAction.DebugPanel -> {
                AppSettings.showsDebugInfo = !AppSettings.showsDebugInfo
            }
        }
    }

    private fun systemCommand(action: ShortcutAction): SystemCommand = SystemCommand.entries.first { ShortcutAction.system(it) == action }
}
