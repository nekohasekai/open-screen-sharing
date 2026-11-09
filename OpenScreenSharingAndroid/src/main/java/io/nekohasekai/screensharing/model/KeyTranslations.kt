package io.nekohasekai.screensharing.model

object KeyTranslations {
    fun output(shortcut: KeyShortcut): KeyShortcut? {
        table[shortcut]?.let { return it }
        if (EventModifiers.Shift !in shortcut.eventModifiers) return null
        val output = table[KeyShortcut(shortcut.key, shortcut.eventModifiers - EventModifiers.Shift)] ?: return null
        return KeyShortcut(output.key, output.eventModifiers + EventModifiers.Shift)
    }

    private val table: Map<KeyShortcut, KeyShortcut> = buildMap {
        val command = EventModifiers.Command
        val control = EventModifiers.Control
        val option = EventModifiers.Option
        val shift = EventModifiers.Shift
        for (character in "zxcvafgsonpwtlrdbiuk0=-,/") {
            put(KeyShortcut("$character", command), KeyShortcut("$character", control))
        }
        val pairs = listOf(
            KeyShortcut("z", shift + command) to KeyShortcut("y", control),
            KeyShortcut("q", command) to KeyShortcut("f4", option),
            KeyShortcut("m", command) to KeyShortcut("downArrow", command),
            KeyShortcut("[", command) to KeyShortcut("leftArrow", option),
            KeyShortcut("]", command) to KeyShortcut("rightArrow", option),
            KeyShortcut("[", shift + command) to KeyShortcut("tab", control + shift),
            KeyShortcut("]", shift + command) to KeyShortcut("tab", control),
            KeyShortcut("return", command) to KeyShortcut("return", control),
            KeyShortcut("tab", command) to KeyShortcut("tab", option),
            KeyShortcut("leftArrow", command) to KeyShortcut("home", EventModifiers.None),
            KeyShortcut("rightArrow", command) to KeyShortcut("end", EventModifiers.None),
            KeyShortcut("upArrow", command) to KeyShortcut("home", control),
            KeyShortcut("downArrow", command) to KeyShortcut("end", control),
            KeyShortcut("leftArrow", option) to KeyShortcut("leftArrow", control),
            KeyShortcut("rightArrow", option) to KeyShortcut("rightArrow", control),
            KeyShortcut("delete", option) to KeyShortcut("delete", control),
            KeyShortcut("deleteForward", option) to KeyShortcut("deleteForward", control),
        )
        putAll(pairs)
    }
}
