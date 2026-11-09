package io.nekohasekai.screensharing.session

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ServiceCompat
import io.nekohasekai.screensharing.R
import io.nekohasekai.screensharing.app.ContinuedTask
import io.nekohasekai.screensharing.sessionui.SessionActivity
import kotlin.math.max

class SessionService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val notificationManager: NotificationManager get() = getSystemService(NotificationManager::class.java)
    private var foregroundID = 0
    private var requestedTypes = 0
    private var posted = emptySet<Int>()
    private var startedForForeground = false
    private var stopped = false
    private var updateScheduled = false
    private var lastUpdate = 0L
    private var lastStartID = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        SessionActivityRegistry.serviceCreated(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartID = startId
        if (intent?.getBooleanExtra(EXTRA_FOREGROUND, false) == true) startedForForeground = true
        val id = intent?.getIntExtra(EXTRA_ID, 0) ?: 0
        when (intent?.action) {
            ACTION_DISCONNECT -> SessionActivityRegistry.disconnect(id)
            ACTION_CANCEL -> SessionActivityRegistry.cancelTask(id)
        }
        if (stopped) {
            stopSelf(startId)
        } else {
            update()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        posted.forEach { notificationManager.cancel(it) }
        posted = emptySet()
        SessionActivityRegistry.serviceDestroyed(this)
        super.onDestroy()
    }

    internal fun scheduleUpdate() {
        if (updateScheduled || stopped) return
        updateScheduled = true
        handler.postDelayed({ update() }, max(0L, lastUpdate + UPDATE_INTERVAL - SystemClock.uptimeMillis()))
    }

    internal fun update() {
        updateScheduled = false
        if (stopped) return
        lastUpdate = SystemClock.uptimeMillis()
        val notifications = SessionActivityRegistry.sessionEntries.map { it.id to sessionNotification(it) } +
            SessionActivityRegistry.taskEntries.map { it.notificationID to taskNotification(it) }
        if (notifications.isEmpty()) {
            stop()
            return
        }
        val (id, notification) = notifications.first()
        val types = foregroundTypes()
        if (id != foregroundID || types != requestedTypes) {
            if (!enterForeground(id, notification, types)) {
                SessionActivityRegistry.foregroundFailed()
                stop()
                return
            }
        } else {
            notificationManager.notify(id, notification)
        }
        SessionActivityRegistry.foregroundStarted()
        for ((otherID, other) in notifications.drop(1)) {
            notificationManager.notify(otherID, other)
        }
        val shown = notifications.mapTo(HashSet()) { it.first }
        (posted - shown).forEach { notificationManager.cancel(it) }
        posted = shown
    }

    private fun enterForeground(id: Int, notification: Notification, types: Int): Boolean {
        val attempts = if (types and MICROPHONE != 0) listOf(types, types and MICROPHONE.inv()) else listOf(types)
        for (attempt in attempts) {
            try {
                ServiceCompat.startForeground(this, id, notification, attempt)
                foregroundID = id
                requestedTypes = types
                return true
            } catch (error: Exception) {
                Log.w(TAG, "start the foreground service with types $attempt", error)
            }
        }
        return false
    }

    internal fun stop() {
        if (stopped) return
        stopped = true
        updateScheduled = false
        handler.removeCallbacksAndMessages(null)
        if (startedForForeground && foregroundID == 0) {
            try {
                ServiceCompat.startForeground(this, PLACEHOLDER_ID, placeholderNotification(), foregroundTypes())
            } catch (error: Exception) {
                Log.w(TAG, "satisfy the foreground service start", error)
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        posted.forEach { notificationManager.cancel(it) }
        posted = emptySet()
        foregroundID = 0
        stopSelf(lastStartID)
    }

    private fun foregroundTypes(): Int {
        var types = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            types = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && RemoteAudioSession.recorders > 0 &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        return types
    }

    private fun builder(channel: String): Notification.Builder {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channel)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).setPriority(Notification.PRIORITY_LOW)
        }
        return builder
            .setSmallIcon(R.drawable.bridge_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setLocalOnly(true)
            .also {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    it.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                }
            }
    }

    private fun action(label: Int, action: String, id: Int): Notification.Action = Notification.Action.Builder(
        Icon.createWithResource(this, R.drawable.bridge_notification),
        getString(label),
        PendingIntent.getService(this, id, Intent(this, SessionService::class.java).setAction(action).putExtra(EXTRA_ID, id), PENDING_FLAGS),
    ).build()

    private fun sessionNotification(entry: SessionActivityRegistry.SessionEntry): Notification {
        val controller = entry.controller
        val content = SessionActivity.intent(this, controller.route).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return builder(SessionActivityRegistry.CHANNEL_SESSIONS)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentTitle(controller.title)
            .setContentText(getString(if (controller.phase is SessionPhase.Reconnecting) R.string.bridge_reconnecting else R.string.bridge_return))
            .setContentIntent(PendingIntent.getActivity(this, entry.id, content, PENDING_FLAGS))
            .setWhen(entry.since)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .addAction(action(R.string.disconnect, ACTION_DISCONNECT, entry.id))
            .build()
    }

    private fun taskNotification(task: ContinuedTask): Notification {
        val builder = builder(SessionActivityRegistry.CHANNEL_TRANSFERS)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setContentTitle(task.title)
            .setContentText(task.subtitle)
            .addAction(action(R.string.cancel, ACTION_CANCEL, task.notificationID))
        val content = task.contentIntent ?: packageManager.getLaunchIntentForPackage(packageName)
        if (content != null) {
            builder.setContentIntent(PendingIntent.getActivity(this, task.notificationID, Intent(content).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PENDING_FLAGS))
        }
        val indeterminate = task.total <= 0
        val progress = if (indeterminate) 0 else (task.completed.coerceIn(0, task.total).toDouble() / task.total * PROGRESS_MAX).toInt()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            builder.setStyle(
                Notification.ProgressStyle()
                    .setProgressSegments(listOf(Notification.ProgressStyle.Segment(PROGRESS_MAX)))
                    .setProgress(progress)
                    .setProgressIndeterminate(indeterminate),
            )
        } else {
            builder.setProgress(PROGRESS_MAX, progress, indeterminate)
        }
        return builder.build()
    }

    private fun placeholderNotification(): Notification = builder(SessionActivityRegistry.CHANNEL_SESSIONS)
        .setCategory(Notification.CATEGORY_SERVICE)
        .setContentTitle(getString(R.string.app_name))
        .build()

    companion object {
        internal const val EXTRA_FOREGROUND = "foreground"
        private const val TAG = "SessionService"
        private const val ACTION_DISCONNECT = "io.nekohasekai.screensharing.action.DISCONNECT"
        private const val ACTION_CANCEL = "io.nekohasekai.screensharing.action.CANCEL_TASK"
        private const val EXTRA_ID = "id"
        private const val PLACEHOLDER_ID = 999
        private const val PROGRESS_MAX = 1000
        private const val UPDATE_INTERVAL = 500L
        private const val MICROPHONE = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        private const val PENDING_FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    }
}
