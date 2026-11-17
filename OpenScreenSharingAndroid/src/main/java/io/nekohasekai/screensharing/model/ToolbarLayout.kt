package io.nekohasekai.screensharing.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.nekohasekai.screensharing.session.RemotePlatform
import io.nekohasekai.screensharing.session.SessionController
import io.nekohasekai.screensharing.sessionui.SessionCommand
import io.nekohasekai.screensharing.sessionui.SessionCommandCategory
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

class ToolbarLayout private constructor(
    private val platform: RemotePlatform,
    private val key: String,
    defaultBar: List<SessionCommand>,
) {
    val defaultBar: List<SessionCommand> = defaultBar.filter { it.applies(platform) }

    var bar: List<SessionCommand> by mutableStateOf(this.defaultBar)
        private set
    var isCustomized: Boolean by mutableStateOf(false)
        private set

    init {
        val stored = PreferenceStore.value<List<String>>(key)
        if (stored != null) {
            bar = stored.mapNotNull { SessionCommand.of(it) }
            isCustomized = true
        }
    }

    fun otherCommands(category: SessionCommandCategory): List<SessionCommand> = category.commands.filter { it !in bar && it.applies(platform) }

    val isDefault: Boolean get() = bar == defaultBar

    fun move(
        fromOffsets: Collection<Int>,
        toOffset: Int,
    ) {
        val moving = fromOffsets.sorted().map { bar[it] }
        val remaining = bar.filterIndexed { index, _ -> index !in fromOffsets }.toMutableList()
        remaining.addAll(toOffset - fromOffsets.count { it < toOffset }, moving)
        bar = remaining
        save()
    }

    fun move(
        command: SessionCommand,
        offset: Int,
    ) {
        val index = bar.indexOf(command)
        if (index < 0) return
        val target = index + offset
        if (target !in bar.indices) return
        bar =
            bar.toMutableList().apply {
                this[index] = bar[target]
                this[target] = command
            }
        save()
    }

    fun remove(command: SessionCommand) {
        bar = bar.filter { it != command }
        save()
    }

    fun add(command: SessionCommand) {
        if (command in bar) return
        bar = bar + command
        save()
    }

    fun reset() {
        bar = defaultBar
        isCustomized = false
        PreferenceStore.remove(key)
    }

    fun plan(
        controller: SessionController,
        slots: Int?,
    ): ToolbarPlan {
        val offered = bar.filter { it.isOffered(controller) && it.isEnabled(controller) }
        val count = min(max(slots ?: offered.size, 0), offered.size)
        return ToolbarPlan(inBar = offered.take(count), overflow = offered.drop(count))
    }

    private fun save() {
        isCustomized = true
        PreferenceStore.set(key, bar.map { it.rawValue })
    }

    companion object {
        val mac =
            ToolbarLayout(
                RemotePlatform.Mac,
                "SessionToolbar",
                listOf(SessionCommand.Displays, SessionCommand.Audio, SessionCommand.PointerLock, SessionCommand.Keyboard, SessionCommand.Clipboard),
            )
        val pc =
            ToolbarLayout(
                RemotePlatform.Pc,
                "SessionToolbar.PC",
                listOf(SessionCommand.Displays, SessionCommand.Audio, SessionCommand.PointerLock, SessionCommand.Keyboard, SessionCommand.Clipboard),
            )

        fun layout(controller: SessionController): ToolbarLayout = when (controller.info.platform) {
            RemotePlatform.Mac -> mac
            RemotePlatform.Pc -> pc
        }

        const val maximumSlots = 8

        fun capsuleSlots(
            width: Dp,
            itemWidth: Dp,
        ): Int {
            val fixed = itemWidth * 2 + capsuleGroupSpacing * 2 + capsuleGroupPadding * 2 + capsuleMargin * 2
            return slots(width - fixed, itemWidth)
        }

        private fun slots(
            width: Dp,
            itemWidth: Dp,
        ): Int {
            if (width <= 0.dp || itemWidth <= 0.dp) return 0
            return min(maximumSlots, floor(width / itemWidth).toInt())
        }

        val itemSize = 48.dp
        val capsuleGroupSpacing = 8.dp
        val capsuleGroupPadding = 4.dp
        val capsuleMargin = 16.dp
        val dockClearance = 4.dp
        val dockMargin = 16.dp
    }
}

data class ToolbarPlan(
    val inBar: List<SessionCommand> = emptyList(),
    val overflow: List<SessionCommand> = emptyList(),
)
