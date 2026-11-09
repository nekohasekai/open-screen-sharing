package io.nekohasekai.screensharing.session

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.nekohasekai.screensharing.App
import io.nekohasekai.screensharing.R
import io.nekohasekai.screensharing.app.ContinuedTask
import io.nekohasekai.screensharing.model.PreferenceStore
import kotlinx.coroutines.launch

object SessionActivityRegistry {
    internal const val CHANNEL_SESSIONS = "sessions"
    internal const val CHANNEL_TRANSFERS = "transfers"
    private const val TAG = "SessionActivity"

    internal class SessionEntry(val controller: SessionController, val id: Int, val since: Long)

    private val sessions = ArrayList<SessionEntry>()
    private val tasks = ArrayList<ContinuedTask>()
    private var nextID = 1000
    private val handler = Handler(Looper.getMainLooper())
    private var refreshPosted = false
    private var service: SessionService? = null
    private var starting = false
    private var startBlocked = false
    private val notificationManager: NotificationManager get() = App.instance.getSystemService(NotificationManager::class.java)
    private val wifiLock: WifiManager.WifiLock? by lazy {
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        try {
            App.instance.applicationContext.getSystemService(WifiManager::class.java)
                ?.createWifiLock(mode, "OpenScreenSharing:session")
                ?.apply { setReferenceCounted(false) }
        } catch (error: Exception) {
            Log.w(TAG, "create the low-latency Wi-Fi lock", error)
            null
        }
    }

    var keepsAlive by mutableStateOf(false)
        private set

    internal val sessionEntries: List<SessionEntry> get() = sessions
    internal val taskEntries: List<ContinuedTask> get() = tasks

    init {
        val context = App.instance
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannels(
                listOf(
                    NotificationChannel(CHANNEL_SESSIONS, context.getString(R.string.bridge_channel_sessions), NotificationManager.IMPORTANCE_LOW).apply {
                        setShowBadge(false)
                    },
                    NotificationChannel(CHANNEL_TRANSFERS, context.getString(R.string.bridge_channel_transfers), NotificationManager.IMPORTANCE_LOW).apply {
                        setShowBadge(false)
                    },
                ),
            )
        }
        notificationManager.activeNotifications
            .filter { Build.VERSION.SDK_INT < Build.VERSION_CODES.O || it.notification.channelId == CHANNEL_SESSIONS || it.notification.channelId == CHANNEL_TRANSFERS }
            .forEach { notificationManager.cancel(it.tag, it.id) }
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    startBlocked = false
                    refresh()
                }
            },
        )
        App.scope.launch {
            snapshotFlow { RemoteAudioSession.recorders }.collect { service?.update() }
        }
    }

    fun set(controller: SessionController, active: Boolean) {
        val index = sessions.indexOfFirst { it.controller === controller }
        if (active) {
            if (index < 0) sessions.add(SessionEntry(controller, nextID++, System.currentTimeMillis()))
        } else {
            if (index < 0) return
            sessions.removeAt(index)
        }
        refresh()
        postRefresh()
    }

    internal fun add(task: ContinuedTask) {
        if (task in tasks) return
        task.notificationID = nextID++
        tasks.add(task)
        postRefresh()
    }

    internal fun remove(task: ContinuedTask) {
        if (!tasks.remove(task)) return
        postRefresh()
    }

    internal fun taskChanged(task: ContinuedTask) {
        if (task in tasks) service?.scheduleUpdate()
    }

    internal fun disconnect(id: Int) {
        sessions.firstOrNull { it.id == id }?.controller?.close()
    }

    internal fun cancelTask(id: Int) {
        tasks.firstOrNull { it.notificationID == id }?.expire()
    }

    private fun postRefresh() {
        if (refreshPosted) return
        refreshPosted = true
        handler.post {
            refreshPosted = false
            refresh()
        }
    }

    internal fun refresh() {
        updateWifiLock()
        val service = service
        if ((sessions.isEmpty() && tasks.isEmpty()) || startBlocked) {
            keepsAlive = false
            service?.stop()
            return
        }
        if (service != null) {
            service.update()
            return
        }
        if (starting) return
        val context = App.instance
        try {
            ContextCompat.startForegroundService(context, Intent(context, SessionService::class.java).putExtra(SessionService.EXTRA_FOREGROUND, true))
            starting = true
            keepsAlive = true
        } catch (error: Exception) {
            Log.w(TAG, "start the session service", error)
            keepsAlive = false
        }
    }

    private fun updateWifiLock() {
        val lock = wifiLock ?: return
        val connected = sessions.any { it.controller.phase == SessionPhase.Connected }
        try {
            if (connected && !lock.isHeld) {
                lock.acquire()
            } else if (!connected && lock.isHeld) {
                lock.release()
            }
        } catch (error: Exception) {
            Log.w(TAG, "update the low-latency Wi-Fi lock", error)
        }
    }

    internal fun serviceCreated(service: SessionService) {
        this.service = service
        starting = false
    }

    internal fun serviceDestroyed(service: SessionService) {
        if (this.service !== service) return
        this.service = null
        starting = false
        keepsAlive = false
        if (sessions.isNotEmpty() || tasks.isNotEmpty()) postRefresh()
    }

    internal fun foregroundStarted() {
        keepsAlive = true
    }

    internal fun foregroundFailed() {
        startBlocked = true
        keepsAlive = false
    }
}

@Composable
fun SessionNotificationsRequest() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        SessionActivityRegistry.refresh()
    }
    LaunchedEffect(launcher) {
        val context = App.instance
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return@LaunchedEffect
        if (PreferenceStore.value<Boolean>(ASKED_NOTIFICATIONS) == true) return@LaunchedEffect
        PreferenceStore.set(ASKED_NOTIFICATIONS, true)
        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

private const val ASKED_NOTIFICATIONS = "askedSessionNotifications"
