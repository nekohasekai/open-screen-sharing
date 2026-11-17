package io.nekohasekai.screensharing.app

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.LocaleList
import android.text.TextUtils
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.Menu
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.nekohasekai.screensharing.demo.Demo
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
        val demo = Demo.configure(intent)
        super.onCreate(savedInstanceState)
        LauncherShortcuts.start()
        if (savedInstanceState == null && demo == null) {
            receive(intent)
        }
        demo?.startIfNeeded(this)
        setContent {
            AppTheme {
                val demoLocale = demo?.locale
                if (demoLocale != null) {
                    val base = this@MainActivity
                    val localizedContext = remember(demoLocale) {
                        val config = Configuration(base.resources.configuration).apply {
                            setLocales(LocaleList(demoLocale))
                            setLayoutDirection(demoLocale)
                        }
                        base.createConfigurationContext(config)
                    }
                    val direction = if (TextUtils.getLayoutDirectionFromLocale(demoLocale) == View.LAYOUT_DIRECTION_RTL) {
                        LayoutDirection.Rtl
                    } else {
                        LayoutDirection.Ltr
                    }
                    CompositionLocalProvider(
                        LocalConfiguration provides localizedContext.resources.configuration,
                        LocalContext provides localizedContext,
                        LocalLayoutDirection provides direction,
                        LocalActivity provides base,
                        LocalActivityResultRegistryOwner provides base,
                        LocalLifecycleOwner provides base,
                    ) {
                        HomeView(pendingLink) { pendingLink = null }
                    }
                } else {
                    HomeView(pendingLink) { pendingLink = null }
                }
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
