package io.nekohasekai.screensharing.app

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.Menu
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.nekohasekai.screensharing.home.HomeRequest
import io.nekohasekai.screensharing.home.HomeView
import io.nekohasekai.screensharing.home.LauncherShortcuts
import io.nekohasekai.screensharing.model.AppSettings
import io.nekohasekai.screensharing.model.ComputerStore
import io.nekohasekai.screensharing.model.KeyShortcuts
import io.nekohasekai.screensharing.model.ShortcutAction
import io.nekohasekai.screensharing.session.SessionRoute
import io.nekohasekai.screensharing.sessionui.SessionActivity

class MainActivity : ComponentActivity() {
    private var pendingLink by mutableStateOf<String?>(null)
    private var handledKeyCode: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        LauncherShortcuts.start()
        if (savedInstanceState == null) {
            receive(intent)
        }
        setContent {
            AppTheme {
                HomeView(pendingLink) { pendingLink = null }
                StorageNoticeHost()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receive(intent)
    }

    private fun receive(intent: Intent) {
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        when (intent.action) {
            Intent.ACTION_VIEW -> pendingLink = intent.dataString
            LauncherShortcuts.ACTION_NEW_CONNECTION -> HomeRequest.pending = HomeRequest.Pending.NewConnection
            LauncherShortcuts.ACTION_CONNECT -> {
                val computer = LauncherShortcuts.computerID(intent)?.let { ComputerStore.computer(it) } ?: return
                SessionActivity.start(this, SessionRoute(computer))
            }
        }
    }

    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == handledKeyCode) {
            if (event.action == KeyEvent.ACTION_UP) handledKeyCode = null
            return true
        }
        val handled = when (KeyShortcuts.action(event)) {
            ShortcutAction.NewConnection -> {
                HomeRequest.pending = HomeRequest.Pending.NewConnection
                true
            }
            ShortcutAction.DebugPanel -> {
                AppSettings.showsDebugInfo = !AppSettings.showsDebugInfo
                true
            }
            else -> false
        }
        if (handled) {
            handledKeyCode = event.keyCode
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onProvideKeyboardShortcuts(data: MutableList<KeyboardShortcutGroup>?, menu: Menu?, deviceId: Int) {
        super.onProvideKeyboardShortcuts(data, menu, deviceId)
        data?.addAll(KeyShortcuts.keyboardShortcutGroups({ it == ShortcutAction.NewConnection || it == ShortcutAction.DebugPanel }))
    }
}
