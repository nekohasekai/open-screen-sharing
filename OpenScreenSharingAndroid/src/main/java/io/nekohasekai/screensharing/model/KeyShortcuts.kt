package io.nekohasekai.screensharing.model

import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.nekohasekai.screensharing.App
import io.nekohasekai.screensharing.R
import io.nekohasekai.screensharing.session.KeyChord
import io.nekohasekai.screensharing.session.Keysym
import io.nekohasekai.screensharing.session.MacKey
import io.nekohasekai.screensharing.session.ModifierKey
import io.nekohasekai.screensharing.session.SystemCommand
import io.nekohasekai.screensharing.session.namedKeyCode
import io.nekohasekai.screensharing.session.typedText
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

enum class ShortcutGroup {
    File,
    Edit,
    View,
    Sound,
    Control,
    Debug,
    ;

    val title: String
        get() = App.instance.getString(
            when (this) {
                File -> R.string.model_file
                Edit -> R.string.model_edit_menu
                View -> R.string.model_view
                Sound -> R.string.model_sound
                Control -> R.string.model_control
                Debug -> R.string.model_debug
            },
        )

    val actions: List<ShortcutAction> get() = ShortcutAction.entries.filter { it.group == this }
}

enum class ShortcutAction(val rawValue: String) {
    NewConnection("newConnection"),
    Disconnect("disconnect"),
    SyncClipboard("syncClipboard"),
    ReceiveClipboard("receiveClipboard"),
    SendClipboard("sendClipboard"),
    ZoomMode("zoomMode"),
    ActualSize("actualSize"),
    Display1("display1"),
    Display2("display2"),
    Display3("display3"),
    Display4("display4"),
    Display5("display5"),
    Display6("display6"),
    Display7("display7"),
    Display8("display8"),
    AllDisplays("allDisplays"),
    AdaptiveQuality("adaptiveQuality"),
    FullQuality("fullQuality"),
    Hdr("hdr"),
    Toolbar("toolbar"),
    CustomizeToolbar("customizeToolbar"),
    ReceiveAudio("receiveAudio"),
    SendAudio("sendAudio"),
    Observe("observe"),
    PointerLock("pointerLock"),
    Keyboard("keyboard"),
    MissionControl("missionControl"),
    AppWindows("appWindows"),
    ShowDesktop("showDesktop"),
    Launchpad("launchpad"),
    DebugPanel("debugPanel"),
    ;

    val group: ShortcutGroup
        get() = when (this) {
            NewConnection, Disconnect -> ShortcutGroup.File
            SyncClipboard, ReceiveClipboard, SendClipboard -> ShortcutGroup.Edit
            ReceiveAudio, SendAudio -> ShortcutGroup.Sound
            Observe, PointerLock, Keyboard, MissionControl, AppWindows, ShowDesktop, Launchpad -> ShortcutGroup.Control
            DebugPanel -> ShortcutGroup.Debug
            else -> ShortcutGroup.View
        }

    val title: String
        get() {
            val resources = App.instance.resources
            return when (this) {
                NewConnection -> resources.getString(R.string.model_new_connection)
                Disconnect -> resources.getString(R.string.disconnect)
                SyncClipboard -> resources.getString(R.string.model_sync_clipboard)
                ReceiveClipboard -> resources.getString(R.string.model_receive_clipboard)
                SendClipboard -> resources.getString(R.string.model_send_clipboard)
                ZoomMode -> resources.getString(R.string.model_zoom_mode)
                ActualSize -> resources.getString(R.string.model_zoom_to_fit_actual_size)
                Display1, Display2, Display3, Display4, Display5, Display6, Display7, Display8 ->
                    resources.getString(R.string.model_display_number, displays.indexOf(this) + 1)
                AllDisplays -> resources.getString(R.string.model_all_displays)
                AdaptiveQuality -> resources.getString(R.string.model_adaptive_quality)
                FullQuality -> resources.getString(R.string.model_full_quality)
                Hdr -> resources.getString(R.string.model_high_dynamic_range)
                Toolbar -> resources.getString(R.string.model_show_hide_toolbar)
                CustomizeToolbar -> resources.getString(R.string.model_customize_toolbar)
                Keyboard -> resources.getString(R.string.model_show_hide_keyboard)
                ReceiveAudio -> resources.getString(R.string.model_receive_audio)
                SendAudio -> resources.getString(R.string.model_send_audio)
                Observe -> resources.getString(R.string.model_observe_mode)
                PointerLock -> resources.getString(R.string.model_pointer_lock)
                MissionControl -> resources.getString(R.string.model_mission_control)
                AppWindows -> resources.getString(R.string.model_app_windows)
                ShowDesktop -> resources.getString(R.string.model_desktop)
                Launchpad -> resources.getString(R.string.model_apps)
                DebugPanel -> resources.getString(R.string.model_debug_panel)
            }
        }

    companion object {
        val displays = listOf(Display1, Display2, Display3, Display4, Display5, Display6, Display7, Display8)

        fun display(index: Int): ShortcutAction? = displays.getOrNull(index)

        fun quality(quality: SessionQuality): ShortcutAction = when (quality) {
            SessionQuality.Adaptive -> AdaptiveQuality
            SessionQuality.Full -> FullQuality
        }

        fun system(command: SystemCommand): ShortcutAction = when (command) {
            SystemCommand.MissionControl -> MissionControl
            SystemCommand.AppWindows -> AppWindows
            SystemCommand.ShowDesktop -> ShowDesktop
            SystemCommand.Launchpad -> Launchpad
        }
    }
}

@Serializable
@JvmInline
value class EventModifiers(val rawValue: Int) {
    operator fun contains(other: EventModifiers): Boolean = rawValue and other.rawValue == other.rawValue
    operator fun plus(other: EventModifiers): EventModifiers = EventModifiers(rawValue or other.rawValue)
    operator fun minus(other: EventModifiers): EventModifiers = EventModifiers(rawValue and other.rawValue.inv())
    val isEmpty: Boolean get() = rawValue == 0

    fun isDisjoint(other: EventModifiers): Boolean = rawValue and other.rawValue == 0

    val symbols: String
        get() = buildString {
            if (Control in this@EventModifiers) append("⌃")
            if (Option in this@EventModifiers) append("⌥")
            if (Shift in this@EventModifiers) append("⇧")
            if (Command in this@EventModifiers) append("⌘")
        }

    val metaState: Int
        get() {
            var metaState = 0
            if (Control in this) metaState = metaState or KeyEvent.META_CTRL_ON
            if (Option in this) metaState = metaState or KeyEvent.META_ALT_ON
            if (Shift in this) metaState = metaState or KeyEvent.META_SHIFT_ON
            if (Command in this) metaState = metaState or KeyEvent.META_META_ON
            return metaState
        }

    companion object {
        val None = EventModifiers(0)
        val Shift = EventModifiers(1 shl 1)
        val Control = EventModifiers(1 shl 2)
        val Option = EventModifiers(1 shl 3)
        val Command = EventModifiers(1 shl 4)

        fun of(metaState: Int): EventModifiers {
            var modifiers = None
            if (metaState and KeyEvent.META_CTRL_ON != 0) modifiers += Control
            if (metaState and KeyEvent.META_ALT_ON != 0) modifiers += Option
            if (metaState and KeyEvent.META_SHIFT_ON != 0) modifiers += Shift
            if (metaState and KeyEvent.META_META_ON != 0) modifiers += Command
            return modifiers
        }
    }
}

@Serializable
data class KeyShortcut(val key: String, @SerialName("modifiers") val eventModifiers: EventModifiers) {
    class NamedKey internal constructor(
        val name: String,
        private val symbolText: () -> String,
        private val titleText: (() -> String)?,
        private val pc: String?,
        val output: MacKey?,
        val targets: Set<MappingTarget>,
        val standalone: Boolean,
        val command: SystemCommand?,
    ) {
        val id: String get() = name

        val symbol: String
            get() {
                val symbol = symbolText()
                return if (symbol.codePointCount(0, symbol.length) == 1) symbol + "\uFE0E" else symbol
            }

        val title: String get() = titleText?.invoke() ?: symbol

        val pcLabel: String get() = pc ?: title

        fun label(target: MappingTarget): String = if (target == MappingTarget.Pc) pcLabel else title
    }

    enum class KeyGroup {
        Editing,
        Navigation,
        FunctionKeys,
        Keypad,
        System,
        Control,
        ;

        val title: String
            get() = App.instance.getString(
                when (this) {
                    Editing -> R.string.model_editing
                    Navigation -> R.string.model_navigation
                    FunctionKeys -> R.string.model_function_keys
                    Keypad -> R.string.model_keypad
                    System -> R.string.model_system
                    Control -> R.string.model_control
                },
            )

        private val names: List<String>
            get() = when (this) {
                Editing -> listOf("return", "tab", "space", "delete", "deleteForward", "escape", "insert")
                Navigation -> listOf("home", "end", "pageUp", "pageDown", "upArrow", "downArrow", "leftArrow", "rightArrow")
                FunctionKeys -> (1..24).map { "f$it" }
                Keypad ->
                    listOf("numLock") + (0..9).map { "keypad$it" } +
                        listOf("keypadDecimal", "keypadPlus", "keypadMinus", "keypadMultiply", "keypadDivide", "keypadEqual", "keypadEnter")
                System -> listOf("printScreen", "scrollLock", "pause", "menu", "capsLock")
                Control -> listOf("missionControl", "appWindows", "showDesktop", "launchpad")
            }

        fun keys(target: MappingTarget): List<NamedKey> = names.mapNotNull { namedKeys[it] }.filter { target in it.targets }
    }

    val isUnset: Boolean get() = key.isEmpty()

    val isNamed: Boolean get() = namedKeys[key] != null

    val command: SystemCommand? get() = namedKeys[key]?.command

    val isValidTrigger: Boolean get() = hasCommandModifier || namedKeys[key]?.standalone == true

    fun chord(target: MappingTarget): KeyChord? {
        val named = namedKeys[key]
        val output = when {
            named != null -> if (target in named.targets) named.output else null
            key.isNotEmpty() && key.codePointCount(0, key.length) == 1 -> MacKey(Keysym.character(key.codePointAt(0)))
            else -> null
        }
        return output?.let { KeyChord(chordModifiers, it) }
    }

    private val chordModifiers: List<ModifierKey>
        get() = ModifierKey.entries.filter { modifier ->
            when (modifier) {
                ModifierKey.Control -> EventModifiers.Control in eventModifiers
                ModifierKey.Option -> EventModifiers.Option in eventModifiers
                ModifierKey.Shift -> EventModifiers.Shift in eventModifiers
                ModifierKey.Command -> EventModifiers.Command in eventModifiers
            }
        }

    val hasCommandModifier: Boolean
        get() = !eventModifiers.isDisjoint(EventModifiers.Command + EventModifiers.Option + EventModifiers.Control)

    val displayName: String get() = eventModifiers.symbols + (namedKeys[key]?.symbol ?: key.uppercase())

    val pcDisplayName: String
        get() = buildList {
            if (EventModifiers.Control in eventModifiers) add("Ctrl")
            if (EventModifiers.Option in eventModifiers) add("Alt")
            if (EventModifiers.Shift in eventModifiers) add("Shift")
            if (EventModifiers.Command in eventModifiers) add("Win")
            add(namedKeys[key]?.pcLabel ?: key.uppercase())
        }.joinToString("+")

    fun displayName(target: MappingTarget): String = if (target == MappingTarget.Pc) pcDisplayName else displayName

    val localDisplayName: String
        get() = buildList {
            if (EventModifiers.Command in eventModifiers) add("Meta")
            if (EventModifiers.Control in eventModifiers) add("Ctrl")
            if (EventModifiers.Option in eventModifiers) add("Alt")
            if (EventModifiers.Shift in eventModifiers) add("Shift")
            add(namedKeys[key]?.pcLabel ?: key.uppercase())
        }.joinToString("+")

    fun shortcutInfo(label: CharSequence): KeyboardShortcutInfo? {
        if (isNamed) return nameKeyCodes[key]?.let { KeyboardShortcutInfo(label, it, eventModifiers.metaState) }
        if (key.length != 1) return null
        return KeyboardShortcutInfo(label, key[0], eventModifiers.metaState)
    }

    companion object {
        val unset = KeyShortcut("", EventModifiers.None)

        fun of(event: KeyEvent): KeyShortcut? {
            val modifiers = EventModifiers.of(event.metaState)
            keyCodeNames[event.namedKeyCode]?.let { return KeyShortcut(it, modifiers) }
            val character = event.typedText(0).ifEmpty { event.typedText(KeyEvent.META_NUM_LOCK_ON) }.lowercase()
            if (character.isEmpty() || character.codePointCount(0, character.length) != 1) return null
            val type = Character.getType(character.codePointAt(0))
            if (type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt()) return null
            return KeyShortcut(character, modifiers)
        }
    }
}

private val namedKeys: Map<String, KeyShortcut.NamedKey> = run {
    val keys = mutableListOf<KeyShortcut.NamedKey>()
    val both = setOf(MappingTarget.Apple, MappingTarget.Pc)
    val pc = setOf(MappingTarget.Pc)
    val apple = setOf(MappingTarget.Apple)
    fun string(id: Int): () -> String = { App.instance.getString(id) }
    fun key(
        name: String,
        symbol: () -> String,
        output: MacKey?,
        title: (() -> String)? = null,
        pcLabel: String? = null,
        targets: Set<MappingTarget> = both,
        standalone: Boolean = false,
        command: SystemCommand? = null,
    ) {
        keys.add(KeyShortcut.NamedKey(name, symbol, title, pcLabel, output, targets, standalone, command))
    }
    key("return", { "↩" }, MacKey.returnKey, string(R.string.model_return), "Enter")
    key("tab", { "⇥" }, MacKey.tab, string(R.string.model_tab), "Tab")
    key("space", string(R.string.model_space), MacKey.space, pcLabel = "Space")
    key("delete", { "⌫" }, MacKey.backspace, string(R.string.delete), "Backspace")
    key("deleteForward", { "⌦" }, MacKey.forwardDelete, string(R.string.model_forward_delete), "Delete")
    key("escape", { "⎋" }, MacKey.escape, string(R.string.model_escape), "Esc")
    key("insert", { "Insert" }, MacKey.insert, targets = pc, standalone = true)
    key("help", { "Help" }, null, targets = emptySet(), standalone = true)
    key("home", { "↖" }, MacKey.home, string(R.string.model_home), "Home")
    key("end", { "↘" }, MacKey.end, string(R.string.model_end), "End")
    key("pageUp", { "⇞" }, MacKey.pageUp, string(R.string.model_page_up), "Page Up")
    key("pageDown", { "⇟" }, MacKey.pageDown, string(R.string.model_page_down), "Page Down")
    key("upArrow", { "↑" }, MacKey.up, string(R.string.model_up_arrow), "↑")
    key("downArrow", { "↓" }, MacKey.down, string(R.string.model_down_arrow), "↓")
    key("leftArrow", { "←" }, MacKey.left, string(R.string.model_left_arrow), "←")
    key("rightArrow", { "→" }, MacKey.right, string(R.string.model_right_arrow), "→")
    for (number in 1..24) {
        key("f$number", { "F$number" }, MacKey.function(number), targets = if (number <= 20) both else pc, standalone = true)
    }
    key("numLock", { "⌧" }, MacKey(71, Keysym.numLock), string(R.string.model_clear), "Num Lock")
    listOf(82, 83, 84, 85, 86, 87, 88, 89, 91, 92).forEachIndexed { digit, keyCode ->
        key("keypad$digit", { App.instance.getString(R.string.model_keypad_key, "$digit") }, MacKey(keyCode, Keysym.keypad(digit)), pcLabel = "Numpad $digit")
    }
    val operators = listOf(
        Triple("keypadDecimal", ".", MacKey(65, Keysym.keypadDecimal)),
        Triple("keypadPlus", "+", MacKey(69, Keysym.keypadPlus)),
        Triple("keypadMinus", "-", MacKey(78, Keysym.keypadMinus)),
        Triple("keypadMultiply", "*", MacKey(67, Keysym.keypadMultiply)),
        Triple("keypadDivide", "/", MacKey(75, Keysym.keypadDivide)),
        Triple("keypadEqual", "=", MacKey(81, Keysym.keypadEqual)),
    )
    for ((name, legend, output) in operators) {
        key(
            name,
            { App.instance.getString(R.string.model_keypad_key, legend) },
            output,
            pcLabel = "Numpad $legend",
            targets = if (name == "keypadEqual") apple else both,
        )
    }
    key("keypadEnter", { "⌤" }, MacKey(76, Keysym.keypadEnter), string(R.string.model_keypad_enter), "Numpad Enter")
    key("printScreen", { "Print Screen" }, MacKey.printScreen, targets = pc, standalone = true)
    key("scrollLock", { "Scroll Lock" }, MacKey(null, Keysym.scrollLock), targets = pc, standalone = true)
    key("pause", { "Pause" }, MacKey(null, Keysym.pause), targets = pc, standalone = true)
    key("menu", { "Menu" }, MacKey.menu, targets = pc, standalone = true)
    key("capsLock", { "Caps Lock" }, MacKey(null, Keysym.capsLock), targets = pc)
    key("missionControl", string(R.string.model_mission_control), null, targets = apple, command = SystemCommand.MissionControl)
    key("appWindows", string(R.string.model_app_windows), null, targets = apple, command = SystemCommand.AppWindows)
    key("showDesktop", string(R.string.model_desktop), null, targets = apple, command = SystemCommand.ShowDesktop)
    key("launchpad", string(R.string.model_apps), null, targets = apple, command = SystemCommand.Launchpad)
    keys.associateBy { it.name }
}

private val keyCodeNames: Map<Int, String> = buildMap {
    put(KeyEvent.KEYCODE_ENTER, "return")
    put(KeyEvent.KEYCODE_TAB, "tab")
    put(KeyEvent.KEYCODE_SPACE, "space")
    put(KeyEvent.KEYCODE_DEL, "delete")
    put(KeyEvent.KEYCODE_FORWARD_DEL, "deleteForward")
    put(KeyEvent.KEYCODE_ESCAPE, "escape")
    put(KeyEvent.KEYCODE_INSERT, "insert")
    put(KeyEvent.KEYCODE_HELP, "help")
    put(KeyEvent.KEYCODE_MOVE_HOME, "home")
    put(KeyEvent.KEYCODE_MOVE_END, "end")
    put(KeyEvent.KEYCODE_PAGE_UP, "pageUp")
    put(KeyEvent.KEYCODE_PAGE_DOWN, "pageDown")
    put(KeyEvent.KEYCODE_DPAD_UP, "upArrow")
    put(KeyEvent.KEYCODE_DPAD_DOWN, "downArrow")
    put(KeyEvent.KEYCODE_DPAD_LEFT, "leftArrow")
    put(KeyEvent.KEYCODE_DPAD_RIGHT, "rightArrow")
    put(KeyEvent.KEYCODE_NUM_LOCK, "numLock")
    put(KeyEvent.KEYCODE_NUMPAD_ENTER, "keypadEnter")
    put(KeyEvent.KEYCODE_SYSRQ, "printScreen")
    put(KeyEvent.KEYCODE_SCROLL_LOCK, "scrollLock")
    put(KeyEvent.KEYCODE_BREAK, "pause")
    put(KeyEvent.KEYCODE_MENU, "menu")
    for (number in 1..12) {
        put(KeyEvent.KEYCODE_F1 + number - 1, "f$number")
    }
    for (number in 13..24) {
        put(KeyEvent.KEYCODE_F13 + number - 13, "f$number")
    }
}

private val nameKeyCodes: Map<String, Int> = keyCodeNames.entries.associate { (keyCode, name) -> name to keyCode }

@Serializable
enum class MappingTarget {
    @SerialName("apple")
    Apple,

    @SerialName("pc")
    Pc,
    ;

    val title: String
        get() = App.instance.getString(
            when (this) {
                Apple -> R.string.model_apple_key_mappings
                Pc -> R.string.model_pc_key_mappings
            },
        )
}

@Serializable
data class KeyCombo(val keys: List<String>, @SerialName("modifiers") val eventModifiers: EventModifiers) {
    val shortcuts: List<KeyShortcut> get() = keys.map { KeyShortcut(it, eventModifiers) }

    val command: SystemCommand? get() = if (keys.size == 1) shortcuts[0].command else null

    fun displayName(target: MappingTarget): String {
        val first = keys.firstOrNull() ?: return ""
        return (listOf(KeyShortcut(first, eventModifiers).displayName(target)) + keys.drop(1).map { KeyShortcut(it, EventModifiers.None).displayName(target) })
            .joinToString("+")
    }

    companion object {
        val empty = KeyCombo(emptyList(), EventModifiers.None)
    }
}

@Serializable
data class KeyMapping(
    @Serializable(with = UUIDSerializer::class) val id: UUID,
    val trigger: KeyShortcut,
    val steps: List<Step>,
) {
    @Serializable
    sealed interface Step {
        @Serializable
        @SerialName("key")
        data class Key(val combo: KeyCombo) : Step

        @Serializable
        @SerialName("text")
        data class Text(val text: String) : Step

        fun displayName(target: MappingTarget): String = when (this) {
            is Key -> combo.displayName(target)
            is Text -> "“" + text.replace("\r\n", "↩").replace('\n', '↩').replace('\r', '↩').replace('\t', '⇥') + "”"
        }
    }

    fun displayName(target: MappingTarget): String = steps.joinToString(" ") { it.displayName(target) }
}

object KeyShortcuts {
    private const val KEY = "keyboardShortcuts"

    private fun mappingsKey(target: MappingTarget): String = when (target) {
        MappingTarget.Apple -> "keyboardMappings"
        MappingTarget.Pc -> "pcKeyboardMappings"
    }

    private var assigned: Map<String, KeyShortcut> by mutableStateOf(PreferenceStore.value<Map<String, KeyShortcut>>(KEY) ?: emptyMap())
    private var mappings: Map<MappingTarget, List<KeyMapping>> by mutableStateOf(
        MappingTarget.entries.associateWith { PreferenceStore.value<List<KeyMapping>>(mappingsKey(it)) ?: emptyList() },
    )
    var isRecording by mutableStateOf(false)

    val isCustomized: Boolean get() = assigned.isNotEmpty() || mappings.values.any { it.isNotEmpty() }

    fun shortcut(action: ShortcutAction): KeyShortcut? = assigned[action.rawValue]

    fun action(shortcut: KeyShortcut): ShortcutAction? {
        val assigned = assigned
        return ShortcutAction.entries.firstOrNull { assigned[it.rawValue] == shortcut }
    }

    fun action(event: KeyEvent): ShortcutAction? {
        if (isRecording || event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return null
        return KeyShortcut.of(event)?.let { action(it) }
    }

    fun set(shortcut: KeyShortcut?, action: ShortcutAction) {
        assigned = if (shortcut == null) assigned - action.rawValue else assigned + (action.rawValue to shortcut)
        PreferenceStore.set(KEY, assigned)
    }

    fun mappings(target: MappingTarget): List<KeyMapping> = mappings[target].orEmpty()

    fun mapping(triggeredBy: KeyShortcut, target: MappingTarget): KeyMapping? = mappings(target).firstOrNull { it.trigger == triggeredBy }

    fun add(mapping: KeyMapping, target: MappingTarget) {
        setMappings(mappings(target) + mapping, target)
    }

    fun update(mapping: KeyMapping, target: MappingTarget) {
        val list = mappings(target)
        val index = list.indexOfFirst { it.id == mapping.id }
        if (index < 0) return
        setMappings(list.toMutableList().also { it[index] = mapping }, target)
    }

    fun removeMapping(id: UUID, target: MappingTarget) {
        setMappings(mappings(target).filter { it.id != id }, target)
    }

    private fun setMappings(list: List<KeyMapping>, target: MappingTarget) {
        mappings = mappings + (target to list)
        PreferenceStore.set(mappingsKey(target), list)
    }

    fun restoreDefaults() {
        assigned = emptyMap()
        PreferenceStore.remove(KEY)
        for (target in MappingTarget.entries) {
            mappings = mappings + (target to emptyList())
            PreferenceStore.remove(mappingsKey(target))
        }
    }

    fun keyboardShortcutGroups(isAvailable: (ShortcutAction) -> Boolean = { true }, target: MappingTarget? = null): List<KeyboardShortcutGroup> {
        val groups = ShortcutGroup.entries.mapNotNull { group ->
            val items = group.actions.filter(isAvailable).mapNotNull { action -> shortcut(action)?.shortcutInfo(action.title) }
            if (items.isEmpty()) null else KeyboardShortcutGroup(group.title, items)
        }
        if (target == null) return groups
        val mapped = mappings(target).mapNotNull { it.trigger.shortcutInfo(it.displayName(target)) }
        return if (mapped.isEmpty()) groups else groups + KeyboardShortcutGroup(target.title, mapped)
    }
}

@Composable
fun ActionShortcut(action: ShortcutAction?) {
    if (action == null || KeyShortcuts.isRecording) return
    val shortcut = KeyShortcuts.shortcut(action) ?: return
    Text(shortcut.localDisplayName, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
