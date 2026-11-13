package io.nekohasekai.screensharing.home

import android.content.Intent
import android.util.Log
import androidx.compose.runtime.snapshotFlow
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import io.nekohasekai.screensharing.App
import io.nekohasekai.screensharing.R
import io.nekohasekai.screensharing.app.MainActivity
import io.nekohasekai.screensharing.model.Computer
import io.nekohasekai.screensharing.model.ComputerStore
import io.nekohasekai.screensharing.model.DeviceAppearance
import io.nekohasekai.screensharing.model.RemoteProtocol
import kotlinx.coroutines.launch
import java.util.UUID

object LauncherShortcuts {
    const val ACTION_CONNECT = "io.nekohasekai.screensharing.action.CONNECT"
    const val ACTION_NEW_CONNECTION = "io.nekohasekai.screensharing.action.NEW_CONNECTION"
    private const val EXTRA_COMPUTER_ID = "computer"
    private const val RECENT_COUNT = 4
    private const val TAG = "LauncherShortcuts"

    private var isStarted = false
    private var known: Map<UUID, Computer>? = null

    fun start() {
        if (isStarted) return
        isStarted = true
        App.scope.launch {
            snapshotFlow { ComputerStore.computers }.collect { update(it) }
        }
    }

    fun computerID(intent: Intent): UUID? = intent.getStringExtra(EXTRA_COMPUTER_ID)?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    private fun update(computers: List<Computer>) {
        val current = computers.associateBy { it.id }
        val previous = known
        known = current
        try {
            if (previous == null) {
                synchronize(current)
                return
            }
            remove((previous.keys - current.keys).map { it.toString() })
            for (computer in computers) {
                val old = previous[computer.id]
                if (computer.lastConnected != null && computer.lastConnected != old?.lastConnected) {
                    ShortcutManagerCompat.pushDynamicShortcut(App.instance, shortcut(computer, rank = 0))
                } else if (old != null && appearance(old) != appearance(computer)) {
                    ShortcutManagerCompat.updateShortcuts(App.instance, listOf(shortcut(computer, rank = 0)))
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "update shortcuts", error)
        }
    }

    private fun synchronize(current: Map<UUID, Computer>) {
        val context = App.instance
        val published = ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_DYNAMIC or ShortcutManagerCompat.FLAG_MATCH_PINNED)
        val ids = published.mapNotNull { shortcut -> runCatching { UUID.fromString(shortcut.id) }.getOrNull() }.distinct()
        remove(ids.filter { it !in current }.map { it.toString() })
        val existing = ids.mapNotNull { current[it] }
        if (existing.isNotEmpty()) {
            val shortcuts = existing.map { shortcut(it, rank = 0) }
            ShortcutManagerCompat.enableShortcuts(context, shortcuts)
            ShortcutManagerCompat.updateShortcuts(context, shortcuts)
        }
        if (ShortcutManagerCompat.getDynamicShortcuts(context).isEmpty()) {
            val recent = current.values.filter { it.lastConnected != null }.sortedByDescending { it.lastConnected }.take(RECENT_COUNT)
            if (recent.isNotEmpty()) {
                ShortcutManagerCompat.setDynamicShortcuts(context, recent.mapIndexed { index, computer -> shortcut(computer, rank = index) })
            }
        }
    }

    private fun remove(ids: List<String>) {
        if (ids.isEmpty()) return
        ShortcutManagerCompat.removeDynamicShortcuts(App.instance, ids)
        ShortcutManagerCompat.disableShortcuts(App.instance, ids, null)
    }

    private fun appearance(computer: Computer): Triple<String, DeviceAppearance, RemoteProtocol> = Triple(computer.displayName, computer.device, computer.remoteProtocol)

    private fun shortcut(computer: Computer, rank: Int): ShortcutInfoCompat {
        val context = App.instance
        val icon = when (computer.device.kind) {
            DeviceAppearance.Kind.Generic -> if (computer.remoteProtocol == RemoteProtocol.Rdp) R.drawable.ic_shortcut_desktop_windows else R.drawable.ic_shortcut_computer
            DeviceAppearance.Kind.Laptop -> R.drawable.ic_shortcut_laptop_mac
            DeviceAppearance.Kind.IMac, DeviceAppearance.Kind.MacMini, DeviceAppearance.Kind.MacStudio, is DeviceAppearance.Kind.MacPro -> R.drawable.ic_shortcut_desktop_mac
        }
        return ShortcutInfoCompat.Builder(context, computer.id.toString())
            .setShortLabel(computer.displayName)
            .setLongLabel(computer.displayName)
            .setIcon(IconCompat.createWithResource(context, icon))
            .setIntent(Intent(ACTION_CONNECT, null, context, MainActivity::class.java).putExtra(EXTRA_COMPUTER_ID, computer.id.toString()))
            .setRank(rank)
            .build()
    }
}
