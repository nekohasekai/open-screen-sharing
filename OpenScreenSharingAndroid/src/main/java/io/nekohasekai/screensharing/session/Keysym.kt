package io.nekohasekai.screensharing.session

import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.annotation.StringRes
import io.nekohasekai.screensharing.R

object Keysym {
    const val backSpace = 0xff08
    const val tab = 0xff09
    const val returnKey = 0xff0d
    const val escape = 0xff1b
    const val forwardDelete = 0xffff
    const val home = 0xff50
    const val left = 0xff51
    const val up = 0xff52
    const val right = 0xff53
    const val down = 0xff54
    const val pageUp = 0xff55
    const val pageDown = 0xff56
    const val end = 0xff57
    const val insert = 0xff63
    const val menu = 0xff67
    const val help = 0xff6a
    const val numLock = 0xff7f
    const val scrollLock = 0xff14
    const val pause = 0xff13
    const val printScreen = 0xff61
    const val keypadEnter = 0xff8d
    const val keypadMultiply = 0xffaa
    const val keypadPlus = 0xffab
    const val keypadMinus = 0xffad
    const val keypadDecimal = 0xffae
    const val keypadDivide = 0xffaf
    const val keypadEqual = 0xffbd
    const val space = 0x20

    const val shiftLeft = 0xffe1
    const val shiftRight = 0xffe2
    const val controlLeft = 0xffe3
    const val controlRight = 0xffe4
    const val capsLock = 0xffe5
    const val optionLeft = 0xffe9
    const val optionRight = 0xffea
    const val commandLeft = 0xffeb
    const val commandRight = 0xffec

    const val powerOff = 0x1008ff2a

    fun function(number: Int): Int = 0xffbe + number.coerceIn(1, 35) - 1

    fun keypad(digit: Int): Int = 0xffb0 + digit.coerceIn(0, 9)

    fun character(codePoint: Int): Int = when (codePoint) {
        0x0a, 0x0d -> returnKey
        0x09 -> tab
        0x08, 0x7f -> backSpace
        0x1b -> escape
        in 0x20..0x7e, in 0xa0..0xff -> codePoint
        else -> 0x01000000 or codePoint
    }

    // ScreensharingAgent's PostKeyEvent rejects the Unicode fallback under secure input
    // (ScreensharingAgent 756.36.5.2, macOS 27.0.1 26A434, UUID DA1CD9BA-97A1-3758-B425-00FA34B428A3,
    // sub_100027410 0x100027720..0x1000277a4; ScreensharingAgent 766.8, macOS 27.2 26B5101f,
    // UUID D8791A89-6EDB-3A0A-A720-EC3F5FB1AD4F, sub_10002749c 0x1000277ac..0x100027830).
    fun typesInSecureInput(keysym: Int): Boolean = keysym < 0x80 || keysym in 0xff00..0xffff

    fun isModifier(keysym: Int): Boolean = keysym in shiftLeft..0xffee

    fun special(event: KeyEvent): Int? = specialKeyCodes[event.namedKeyCode]

    fun of(event: KeyEvent, capsLockEngaged: Boolean): Int? {
        special(event)?.let { return it }
        val base = event.typedText(0)
        var text: String
        if (event.isCtrlPressed || event.isMetaPressed) {
            text = if (event.isShiftPressed) {
                val typed = event.typedText(event.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_META_MASK).inv())
                if (isPrintable(typed)) typed.uppercase() else base.uppercase()
            } else {
                base.lowercase()
            }
        } else {
            val typed = event.typedText(event.metaState)
            text = if (isPrintable(typed)) typed else base
            if (text.codePointCount(0, text.length) == 1 && Character.isLetter(text.codePointAt(0))) {
                val cased = if (event.isShiftPressed != capsLockEngaged) text.uppercase() else text.lowercase()
                if (cased.codePointCount(0, cased.length) == 1) text = cased
            }
        }
        if (text.isEmpty()) return null
        return character(text.codePointAt(0))
    }

    private fun isPrintable(text: String): Boolean {
        if (text.isEmpty()) return false
        val codePoint = text.codePointAt(0)
        return codePoint >= 0x20 && codePoint != 0x7f
    }

    private val specialKeyCodes: Map<Int, Int> = buildMap {
        put(KeyEvent.KEYCODE_ENTER, returnKey)
        put(KeyEvent.KEYCODE_ESCAPE, escape)
        put(KeyEvent.KEYCODE_DEL, backSpace)
        put(KeyEvent.KEYCODE_TAB, tab)
        put(KeyEvent.KEYCODE_SPACE, space)
        put(KeyEvent.KEYCODE_FORWARD_DEL, forwardDelete)
        put(KeyEvent.KEYCODE_MOVE_HOME, home)
        put(KeyEvent.KEYCODE_MOVE_END, end)
        put(KeyEvent.KEYCODE_PAGE_UP, pageUp)
        put(KeyEvent.KEYCODE_PAGE_DOWN, pageDown)
        put(KeyEvent.KEYCODE_DPAD_LEFT, left)
        put(KeyEvent.KEYCODE_DPAD_RIGHT, right)
        put(KeyEvent.KEYCODE_DPAD_UP, up)
        put(KeyEvent.KEYCODE_DPAD_DOWN, down)
        put(KeyEvent.KEYCODE_INSERT, insert)
        put(KeyEvent.KEYCODE_SYSRQ, printScreen)
        put(KeyEvent.KEYCODE_SCROLL_LOCK, scrollLock)
        put(KeyEvent.KEYCODE_BREAK, pause)
        put(KeyEvent.KEYCODE_HELP, help)
        put(KeyEvent.KEYCODE_MENU, menu)
        put(KeyEvent.KEYCODE_NUM_LOCK, numLock)
        put(KeyEvent.KEYCODE_NUMPAD_ENTER, keypadEnter)
        put(KeyEvent.KEYCODE_NUMPAD_MULTIPLY, keypadMultiply)
        put(KeyEvent.KEYCODE_NUMPAD_ADD, keypadPlus)
        put(KeyEvent.KEYCODE_NUMPAD_SUBTRACT, keypadMinus)
        put(KeyEvent.KEYCODE_NUMPAD_DOT, keypadDecimal)
        put(KeyEvent.KEYCODE_NUMPAD_DIVIDE, keypadDivide)
        put(KeyEvent.KEYCODE_NUMPAD_EQUALS, keypadEqual)
        for (digit in 0..9) {
            put(KeyEvent.KEYCODE_NUMPAD_0 + digit, keypad(digit))
        }
        put(KeyEvent.KEYCODE_SHIFT_LEFT, shiftLeft)
        put(KeyEvent.KEYCODE_SHIFT_RIGHT, shiftRight)
        put(KeyEvent.KEYCODE_CTRL_LEFT, controlLeft)
        put(KeyEvent.KEYCODE_CTRL_RIGHT, controlRight)
        put(KeyEvent.KEYCODE_ALT_LEFT, optionLeft)
        put(KeyEvent.KEYCODE_ALT_RIGHT, optionRight)
        put(KeyEvent.KEYCODE_META_LEFT, commandLeft)
        put(KeyEvent.KEYCODE_META_RIGHT, commandRight)
        for (number in 1..12) {
            put(KeyEvent.KEYCODE_F1 + number - 1, function(number))
        }
        for (number in 13..24) {
            put(KeyEvent.KEYCODE_F13 + number - 13, function(number))
        }
    }
}

internal val KeyEvent.namedKeyCode: Int
    get() {
        if (keyCode != KeyEvent.KEYCODE_UNKNOWN) return keyCode
        return when (scanCode) {
            in 183..194 -> KeyEvent.KEYCODE_F13 + scanCode - 183
            138 -> KeyEvent.KEYCODE_HELP
            else -> KeyEvent.KEYCODE_UNKNOWN
        }
    }

internal fun KeyEvent.typedText(metaState: Int): String {
    var character = getUnicodeChar(metaState)
    if (character == 0) return ""
    if (character and KeyCharacterMap.COMBINING_ACCENT != 0) {
        character = character and KeyCharacterMap.COMBINING_ACCENT_MASK
    }
    return String(Character.toChars(character))
}

enum class ModifierKey {
    Control,
    Option,
    Shift,
    Command,
    ;

    data class PcLabel(val text: String, @param:StringRes val title: Int)

    val keysym: Int
        get() = when (this) {
            Control -> Keysym.controlLeft
            Option -> Keysym.optionLeft
            Shift -> Keysym.shiftLeft
            Command -> Keysym.commandLeft
        }

    val symbol: String
        get() = when (this) {
            Control -> "⌃"
            Option -> "⌥"
            Shift -> "⇧"
            Command -> "⌘"
        }

    @get:StringRes
    val title: Int
        get() = when (this) {
            Control -> R.string.model_control_key
            Option -> R.string.model_option_key
            Shift -> R.string.model_shift_key
            Command -> R.string.model_command_key
        }

    val pcLabel: PcLabel?
        get() = when (this) {
            Control -> PcLabel("ctrl", R.string.model_ctrl_key)
            Option -> PcLabel("alt", R.string.model_alt_key)
            Shift -> null
            Command -> PcLabel("win", R.string.model_windows_key)
        }

    companion object {
        val pcOrder = listOf(Control, Command, Option, Shift)

        fun of(keysym: Int): ModifierKey? = when (keysym) {
            Keysym.controlLeft, Keysym.controlRight -> Control
            Keysym.optionLeft, Keysym.optionRight -> Option
            Keysym.shiftLeft, Keysym.shiftRight -> Shift
            Keysym.commandLeft, Keysym.commandRight -> Command
            else -> null
        }

        fun held(metaState: Int): Set<ModifierKey> = buildSet {
            if (metaState and KeyEvent.META_CTRL_ON != 0) add(Control)
            if (metaState and KeyEvent.META_ALT_ON != 0) add(Option)
            if (metaState and KeyEvent.META_SHIFT_ON != 0) add(Shift)
            if (metaState and KeyEvent.META_META_ON != 0) add(Command)
        }
    }
}

data class KeyChord(val modifiers: List<ModifierKey>, val key: MacKey) {
    constructor(modifiers: List<ModifierKey>, keysym: Int) : this(modifiers, MacKey(keysym))

    companion object {
        val previousInputSource = KeyChord(listOf(ModifierKey.Control), MacKey.space)

        val sideButton = KeyChord(emptyList(), MacKey(null, Keysym.powerOff))
    }
}

val SystemCommand.fallbackChord: KeyChord?
    get() = when (this) {
        SystemCommand.MissionControl -> KeyChord(listOf(ModifierKey.Control), Keysym.up)
        SystemCommand.AppWindows -> KeyChord(listOf(ModifierKey.Control), Keysym.down)
        SystemCommand.ShowDesktop -> KeyChord(emptyList(), Keysym.function(11))
        SystemCommand.Launchpad -> null
    }
