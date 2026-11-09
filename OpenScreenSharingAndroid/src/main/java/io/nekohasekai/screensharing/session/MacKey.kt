package io.nekohasekai.screensharing.session

import android.view.KeyEvent

data class MacKey(val keyCode: Int?, val keysym: Int) {
    constructor(keysym: Int) : this(namedKeyCodes[keysym], keysym)

    val isModifier: Boolean get() = Keysym.isModifier(keysym)

    companion object {
        val returnKey = MacKey(36, Keysym.returnKey)
        val tab = MacKey(48, Keysym.tab)
        val space = MacKey(49, Keysym.space)
        val backspace = MacKey(51, Keysym.backSpace)
        val escape = MacKey(53, Keysym.escape)
        val forwardDelete = MacKey(117, Keysym.forwardDelete)
        val home = MacKey(115, Keysym.home)
        val end = MacKey(119, Keysym.end)
        val pageUp = MacKey(116, Keysym.pageUp)
        val pageDown = MacKey(121, Keysym.pageDown)
        val left = MacKey(123, Keysym.left)
        val right = MacKey(124, Keysym.right)
        val down = MacKey(125, Keysym.down)
        val up = MacKey(126, Keysym.up)
        val command = MacKey(55, Keysym.commandLeft)
        val shift = MacKey(56, Keysym.shiftLeft)
        val option = MacKey(58, Keysym.optionLeft)
        val control = MacKey(59, Keysym.controlLeft)
        val insert = MacKey(null, Keysym.insert)
        val printScreen = MacKey(null, Keysym.printScreen)
        val menu = MacKey(null, Keysym.menu)

        // ScreensharingAgent sets the Caps Lock flag on key-down and clears it on key-up
        // (ScreensharingAgent 756.36.5.2, macOS 27.0.1 26A434, UUID DA1CD9BA-97A1-3758-B425-00FA34B428A3,
        // sub_1000350fc 0x1000351a4..0x10003529c, keyCode 0x39 jump table entry 0x1000352b8;
        // ScreensharingAgent 766.8, macOS 27.2 26B5101f, UUID D8791A89-6EDB-3A0A-A720-EC3F5FB1AD4F,
        // sub_100035484 0x10003552c..0x100035624, keyCode 0x39 jump table entry 0x100035640).
        val capsLock = MacKey(57, 0)

        private val functionKeyCodes = intArrayOf(122, 120, 99, 118, 96, 97, 98, 100, 101, 109, 103, 111, 105, 107, 113, 106, 64, 79, 80, 90)

        fun function(number: Int): MacKey = MacKey(if (number in 1..functionKeyCodes.size) functionKeyCodes[number - 1] else null, Keysym.function(number))

        private val namedKeyCodes: Map<Int, Int> = buildMap {
            val keys = listOf(
                returnKey, tab, space, backspace, escape, forwardDelete, home, end, pageUp, pageDown,
                left, right, down, up, command, shift, option, control,
                MacKey(54, Keysym.commandRight), MacKey(60, Keysym.shiftRight),
                MacKey(61, Keysym.optionRight), MacKey(62, Keysym.controlRight),
                MacKey(114, Keysym.help),
            )
            for (key in keys) {
                put(key.keysym, key.keyCode!!)
            }
            functionKeyCodes.forEachIndexed { index, keyCode -> put(Keysym.function(index + 1), keyCode) }
        }

        fun keyCode(event: KeyEvent, type: MacKeyboardType): Int? {
            if (event.scanCode == 0) {
                return when (event.keyCode) {
                    KeyEvent.KEYCODE_GRAVE -> if (type == MacKeyboardType.Iso) 10 else 50
                    else -> androidKeyCodes[event.keyCode]
                }
            }
            return when (event.scanCode) {
                SCAN_GRAVE -> if (type == MacKeyboardType.Iso) 10 else 50
                SCAN_102ND -> if (type == MacKeyboardType.Iso) 50 else 10
                else -> scanKeyCodes[event.scanCode]
            }
        }

        fun of(event: KeyEvent, type: MacKeyboardType, platform: RemotePlatform, capsLockEngaged: Boolean): MacKey? {
            val typed = Keysym.of(event, capsLockEngaged)
            val keyCode = keyCode(event, type)
            if (keyCode == null || (platform == RemotePlatform.Pc && event.namedKeyCode in pcKeysymOnly)) {
                return typed?.let { MacKey(null, it) }
            }
            return MacKey(keyCode, typed ?: 0)
        }

        private val pcKeysymOnly = setOf(KeyEvent.KEYCODE_SYSRQ, KeyEvent.KEYCODE_SCROLL_LOCK, KeyEvent.KEYCODE_BREAK)

        private const val SCAN_GRAVE = 41
        private const val SCAN_102ND = 86

        private val letterKeyCodes = intArrayOf(0, 11, 8, 2, 14, 3, 5, 4, 34, 38, 40, 37, 46, 45, 31, 35, 12, 15, 1, 17, 32, 9, 13, 7, 16, 6)
        private val digitKeyCodes = intArrayOf(18, 19, 20, 21, 23, 22, 26, 28, 25, 29)

        private val scanKeyCodes: Map<Int, Int> = buildMap {
            val letters = intArrayOf(30, 48, 46, 32, 18, 33, 34, 35, 23, 36, 37, 38, 50, 49, 24, 25, 16, 19, 31, 20, 22, 47, 17, 45, 21, 44)
            letters.forEachIndexed { index, scanCode -> put(scanCode, letterKeyCodes[index]) }
            digitKeyCodes.forEachIndexed { index, keyCode -> put(2 + index, keyCode) }
            val others = listOf(
                28 to 36, 1 to 53, 14 to 51, 15 to 48, 57 to 49, 12 to 27, 13 to 24, 26 to 33, 27 to 30, 43 to 42,
                39 to 41, 40 to 39, 51 to 43, 52 to 47, 53 to 44, 58 to 57,
                59 to 122, 60 to 120, 61 to 99, 62 to 118, 63 to 96, 64 to 97, 65 to 98, 66 to 100, 67 to 101, 68 to 109,
                87 to 103, 88 to 111, 99 to 105, 70 to 107, 119 to 113, 110 to 114, 102 to 115, 104 to 116,
                111 to 117, 107 to 119, 109 to 121, 106 to 124, 105 to 123, 108 to 125, 103 to 126,
                69 to 71, 98 to 75, 55 to 67, 74 to 78, 78 to 69, 96 to 76, 79 to 83, 80 to 84, 81 to 85, 75 to 86,
                76 to 87, 77 to 88, 71 to 89, 72 to 91, 73 to 92, 82 to 82, 83 to 65, 117 to 81, 121 to 95, 127 to 110,
                183 to 105, 184 to 107, 185 to 113, 186 to 106, 187 to 64, 188 to 79, 189 to 80, 190 to 90, 138 to 114,
                89 to 94, 124 to 93, 122 to 104, 123 to 102,
                29 to 59, 42 to 56, 56 to 58, 125 to 55, 97 to 62, 54 to 60, 100 to 61, 126 to 54, 464 to 63,
            )
            putAll(others)
        }

        private val androidKeyCodes: Map<Int, Int> = buildMap {
            letterKeyCodes.forEachIndexed { index, keyCode -> put(KeyEvent.KEYCODE_A + index, keyCode) }
            digitKeyCodes.forEachIndexed { index, keyCode -> put(if (index == 9) KeyEvent.KEYCODE_0 else KeyEvent.KEYCODE_1 + index, keyCode) }
            for (number in 1..12) {
                put(KeyEvent.KEYCODE_F1 + number - 1, functionKeyCodes[number - 1])
            }
            for (number in 13..20) {
                put(KeyEvent.KEYCODE_F13 + number - 13, functionKeyCodes[number - 1])
            }
            val numpad = intArrayOf(82, 83, 84, 85, 86, 87, 88, 89, 91, 92)
            numpad.forEachIndexed { digit, keyCode -> put(KeyEvent.KEYCODE_NUMPAD_0 + digit, keyCode) }
            val others = listOf(
                KeyEvent.KEYCODE_ENTER to 36, KeyEvent.KEYCODE_ESCAPE to 53, KeyEvent.KEYCODE_DEL to 51, KeyEvent.KEYCODE_TAB to 48,
                KeyEvent.KEYCODE_SPACE to 49, KeyEvent.KEYCODE_MINUS to 27, KeyEvent.KEYCODE_EQUALS to 24,
                KeyEvent.KEYCODE_LEFT_BRACKET to 33, KeyEvent.KEYCODE_RIGHT_BRACKET to 30, KeyEvent.KEYCODE_BACKSLASH to 42,
                KeyEvent.KEYCODE_SEMICOLON to 41, KeyEvent.KEYCODE_APOSTROPHE to 39, KeyEvent.KEYCODE_COMMA to 43,
                KeyEvent.KEYCODE_PERIOD to 47, KeyEvent.KEYCODE_SLASH to 44, KeyEvent.KEYCODE_CAPS_LOCK to 57,
                KeyEvent.KEYCODE_SYSRQ to 105, KeyEvent.KEYCODE_SCROLL_LOCK to 107, KeyEvent.KEYCODE_BREAK to 113,
                KeyEvent.KEYCODE_INSERT to 114, KeyEvent.KEYCODE_MOVE_HOME to 115, KeyEvent.KEYCODE_PAGE_UP to 116,
                KeyEvent.KEYCODE_FORWARD_DEL to 117, KeyEvent.KEYCODE_MOVE_END to 119, KeyEvent.KEYCODE_PAGE_DOWN to 121,
                KeyEvent.KEYCODE_DPAD_RIGHT to 124, KeyEvent.KEYCODE_DPAD_LEFT to 123, KeyEvent.KEYCODE_DPAD_DOWN to 125,
                KeyEvent.KEYCODE_DPAD_UP to 126, KeyEvent.KEYCODE_NUM_LOCK to 71, KeyEvent.KEYCODE_NUMPAD_DIVIDE to 75,
                KeyEvent.KEYCODE_NUMPAD_MULTIPLY to 67, KeyEvent.KEYCODE_NUMPAD_SUBTRACT to 78, KeyEvent.KEYCODE_NUMPAD_ADD to 69,
                KeyEvent.KEYCODE_NUMPAD_ENTER to 76, KeyEvent.KEYCODE_NUMPAD_DOT to 65, KeyEvent.KEYCODE_NUMPAD_EQUALS to 81,
                KeyEvent.KEYCODE_NUMPAD_COMMA to 95, KeyEvent.KEYCODE_MENU to 110, KeyEvent.KEYCODE_HELP to 114,
                KeyEvent.KEYCODE_RO to 94, KeyEvent.KEYCODE_YEN to 93, KeyEvent.KEYCODE_KANA to 104, KeyEvent.KEYCODE_EISU to 102,
                KeyEvent.KEYCODE_CTRL_LEFT to 59, KeyEvent.KEYCODE_SHIFT_LEFT to 56, KeyEvent.KEYCODE_ALT_LEFT to 58,
                KeyEvent.KEYCODE_META_LEFT to 55, KeyEvent.KEYCODE_CTRL_RIGHT to 62, KeyEvent.KEYCODE_SHIFT_RIGHT to 60,
                KeyEvent.KEYCODE_ALT_RIGHT to 61, KeyEvent.KEYCODE_META_RIGHT to 54, KeyEvent.KEYCODE_FUNCTION to 63,
            )
            putAll(others)
        }

        fun usKeystroke(character: Char): Pair<MacKey, Boolean>? {
            if (character.code > 0x7f) return null
            val (keyCode, shift) = usKeystrokes[character] ?: return null
            return MacKey(keyCode, character.code) to shift
        }

        private val usKeystrokes: Map<Char, Pair<Int, Boolean>> = buildMap {
            letterKeyCodes.forEachIndexed { index, keyCode ->
                put('a' + index, keyCode to false)
                put('A' + index, keyCode to true)
            }
            val plain = listOf(
                '1' to 18, '2' to 19, '3' to 20, '4' to 21, '5' to 23, '6' to 22, '7' to 26, '8' to 28, '9' to 25, '0' to 29,
                '-' to 27, '=' to 24, '[' to 33, ']' to 30, '\\' to 42, ';' to 41, '\'' to 39, ',' to 43, '.' to 47,
                '/' to 44, '`' to 50, ' ' to 49,
            )
            val shifted = listOf(
                '!' to 18, '@' to 19, '#' to 20, '$' to 21, '%' to 23, '^' to 22, '&' to 26, '*' to 28, '(' to 25, ')' to 29,
                '_' to 27, '+' to 24, '{' to 33, '}' to 30, '|' to 42, ':' to 41, '"' to 39, '<' to 43, '>' to 47,
                '?' to 44, '~' to 50,
            )
            for ((character, keyCode) in plain) put(character, keyCode to false)
            for ((character, keyCode) in shifted) put(character, keyCode to true)
        }
    }
}

enum class MacKeyboardType {
    Ansi,
    Iso,
    Jis,
    ;

    fun learning(event: KeyEvent): MacKeyboardType {
        val scanCode = event.scanCode
        val jis = if (scanCode != 0) scanCode in jisScanCodes else event.keyCode in jisKeyCodes
        return when {
            jis -> Jis
            scanCode == 86 -> if (this == Jis) Jis else Iso
            else -> this
        }
    }

    private companion object {
        val jisScanCodes = setOf(89, 124, 122, 123, 121)
        val jisKeyCodes = setOf(KeyEvent.KEYCODE_RO, KeyEvent.KEYCODE_YEN, KeyEvent.KEYCODE_KANA, KeyEvent.KEYCODE_EISU, KeyEvent.KEYCODE_NUMPAD_COMMA)
    }
}

object WindowsKeyboardLayout {
    const val current = 0
}

val ModifierKey.macKey: MacKey
    get() = when (this) {
        ModifierKey.Control -> MacKey.control
        ModifierKey.Option -> MacKey.option
        ModifierKey.Shift -> MacKey.shift
        ModifierKey.Command -> MacKey.command
    }
