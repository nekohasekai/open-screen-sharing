package io.nekohasekai.screensharing.sessionui

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.Menu
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import io.nekohasekai.screensharing.app.AppCommands
import io.nekohasekai.screensharing.app.AppTheme
import io.nekohasekai.screensharing.app.StorageNoticeHost
import io.nekohasekai.screensharing.discovery.LocalNetworkAccess
import io.nekohasekai.screensharing.home.LauncherShortcuts
import io.nekohasekai.screensharing.model.AppSettings
import io.nekohasekai.screensharing.model.KeyShortcuts
import io.nekohasekai.screensharing.session.MicrophoneSender
import io.nekohasekai.screensharing.session.ScreenPicture
import io.nekohasekai.screensharing.session.SessionController
import io.nekohasekai.screensharing.session.SessionNotificationsRequest
import io.nekohasekai.screensharing.session.SessionPhase
import io.nekohasekai.screensharing.session.SessionRoute
import io.nekohasekai.screensharing.session.WindowCredentials
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SessionActivity : ComponentActivity() {
    class Holder : ViewModel() {
        var controller: SessionController? = null

        override fun onCleared() {
            val controller = controller ?: return
            controllers.remove(controller)
            if (!controller.isClosing) controller.abort()
        }
    }

    private var controller: SessionController? = null
    private var screenView: RemoteScreenView? = null
    private var asksForNotifications by mutableStateOf(false)
    private var handledKeyCode: Int? = null
    private var mouseBackReleasedAt: Long? = null

    private val localNetworkAccess = LocalNetworkAccess.permission.requester(this)
    private val microphonePermission = MicrophoneSender.permission.requester(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        val holder = ViewModelProvider(this)[Holder::class.java]
        val controller =
            holder.controller ?: run {
                val route = IntentCompat.getParcelableExtra(intent, EXTRA_ROUTE, SessionRoute::class.java)
                if (route == null) {
                    finishAndRemoveTask()
                    return
                }
                SessionController(WindowCredentials.receive(route)).also {
                    holder.controller = it
                    controllers.add(it)
                }
            }
        this.controller = controller
        LauncherShortcuts.start()
        setTaskDescription(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ActivityManager.TaskDescription
                    .Builder()
                    .setLabel(controller.title)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                ActivityManager.TaskDescription(controller.title)
            },
        )
        volumeControlStream = AudioManager.STREAM_MUSIC
        onBackPressedDispatcher.addCallback(this) {
            val mouseBackAt = mouseBackReleasedAt
            mouseBackReleasedAt = null
            if (mouseBackAt != null && SystemClock.uptimeMillis() - mouseBackAt < SYNTHESIZED_BACK_TIMEOUT) return@addCallback
            if (controller.picture == ScreenPicture.None) {
                controller.close()
            } else if (!controller.isToolbarDocked && !controller.isToolbarVisible) {
                controller.toggleToolbar()
            } else {
                moveTaskToBack(true)
            }
        }
        controller.onClose = { finishAndRemoveTask() }
        controller.microphonePermission = microphonePermission
        if (controller.phase == SessionPhase.Idle) {
            lifecycleScope.launch {
                LocalNetworkAccess.request(controller.route.host, localNetworkAccess)
                controller.start()
            }
        }
        updateFullScreen()
        observe(controller)
        setContent {
            AppTheme(darkTheme = controller.picture == ScreenPicture.None || isSystemInDarkTheme()) {
                SessionScreen(controller) { view ->
                    screenView = view
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        view.setOnCapturedPointerListener { _, event ->
                            noteMouseBack(event)
                            false
                        }
                    }
                }
                if (asksForNotifications) SessionNotificationsRequest()
                StorageNoticeHost()
            }
        }
    }

    private fun observe(controller: SessionController) {
        lifecycleScope.launch {
            snapshotFlow { controller.phase.isActive }.collect { active ->
                if (active) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }
        lifecycleScope.launch {
            snapshotFlow { controller.isFullScreen }.collect { fullScreen ->
                val insetsController = WindowCompat.getInsetsController(window, window.decorView)
                insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                if (fullScreen) {
                    insetsController.hide(WindowInsetsCompat.Type.systemBars())
                } else {
                    insetsController.show(WindowInsetsCompat.Type.systemBars())
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lifecycleScope.launch {
                snapshotFlow { AppSettings.pictureFillsScreen }.collect { fills ->
                    window.attributes =
                        window.attributes.also {
                            it.layoutInDisplayCutoutMode =
                                when {
                                    !fills -> WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                                    else -> WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                                }
                        }
                }
            }
        }
        lifecycleScope.launch {
            snapshotFlow { controller.phase }.first { it == SessionPhase.Connected }
            asksForNotifications = true
        }
    }

    override fun onStart() {
        super.onStart()
        val controller = controller ?: return
        controller.setScreenVisible(true)
        controller.sceneDidBecomeActive()
    }

    override fun onStop() {
        super.onStop()
        val controller = controller ?: return
        controller.setScreenVisible(false)
        controller.sceneDidEnterBackground()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        val controller = controller ?: return
        controller.hasWindowFocus = hasFocus
        if (hasFocus) controller.sceneDidBecomeActive()
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        noteMouseBack(event)
        return super.dispatchGenericMotionEvent(event)
    }

    // InputReader (CursorInputMapper::sync → synthesizeButtonKeys) also delivers the back button as KEYCODE_BACK,
    // whose key up follows this BUTTON_RELEASE.
    private fun noteMouseBack(event: MotionEvent) {
        if (event.actionMasked == MotionEvent.ACTION_BUTTON_RELEASE && event.actionButton == MotionEvent.BUTTON_BACK) {
            mouseBackReleasedAt = SystemClock.uptimeMillis()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateFullScreen()
    }

    override fun onMultiWindowModeChanged(
        isInMultiWindowMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        updateFullScreen()
    }

    private fun updateFullScreen() {
        val controller = controller ?: return
        controller.isFullScreen = !isInMultiWindowMode || windowFillsDisplay()
    }

    private fun windowFillsDisplay(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val current = windowManager.currentWindowMetrics.bounds
        val maximum = windowManager.maximumWindowMetrics.bounds
        return current.width() >= maximum.width() - 1 && current.height() >= maximum.height() - 1
    }

    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == handledKeyCode) {
            if (event.action == KeyEvent.ACTION_UP) handledKeyCode = null
            return true
        }
        if (super.dispatchKeyEvent(event)) return true
        val controller = controller ?: return false
        val action = KeyShortcuts.action(event) ?: return false
        if (!AppCommands.isAvailable(action, controller)) return false
        AppCommands.perform(action, controller)
        handledKeyCode = event.keyCode
        return true
    }

    override fun onProvideKeyboardShortcuts(
        data: MutableList<KeyboardShortcutGroup>?,
        menu: Menu?,
        deviceId: Int,
    ) {
        super.onProvideKeyboardShortcuts(data, menu, deviceId)
        val controller = controller ?: return
        data?.addAll(KeyShortcuts.keyboardShortcutGroups({ AppCommands.isAvailable(it, controller) }, controller.mappingTarget))
    }

    companion object {
        private const val EXTRA_ROUTE = "route"
        private const val SYNTHESIZED_BACK_TIMEOUT = 500L
        private val controllers = ArrayList<SessionController>()

        fun intent(
            context: Context,
            route: SessionRoute,
        ): Intent = Intent(context, SessionActivity::class.java)
            .setData("screensharing-session://${route.id}".toUri())
            .putExtra(EXTRA_ROUTE, route)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_NEW_TASK)

        fun start(
            context: Context,
            route: SessionRoute,
            newWindow: Boolean = false,
        ) {
            val shown =
                controllers.firstOrNull { controller ->
                    val id = route.computerID
                    val computerID = controller.computerID
                    !controller.isClosing &&
                        if (id != null && computerID != null) id == computerID else controller.route.address.matches(route.address)
                }
            if (shown != null) {
                if (shown.phase != SessionPhase.Disconnected && shown.phase !is SessionPhase.Failed) {
                    context.startActivity(intent(context, shown.route))
                    return
                }
                shown.close()
            }
            val intent = intent(context, WindowCredentials.send(route))
            if (newWindow) {
                intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            }
            context.startActivity(intent)
        }
    }
}
