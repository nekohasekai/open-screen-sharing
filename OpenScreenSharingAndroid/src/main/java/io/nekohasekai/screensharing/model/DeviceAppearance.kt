package io.nekohasekai.screensharing.model

data class DeviceAppearance(val kind: Kind, val color: RGB?) {
    sealed interface Kind {
        data object Generic : Kind
        data object Laptop : Kind
        data object IMac : Kind
        data object MacMini : Kind
        data object MacStudio : Kind
        data class MacPro(val generation: Int) : Kind
        data object IPhone : Kind
        data object IPad : Kind
    }

    data class RGB(val red: Double, val green: Double, val blue: Double)

    constructor(model: String, enclosureColor: String = "") : this(kind(model), color(enclosureColor))

    companion object {
        private val appleSiliconKinds: Map<String, Kind> = buildMap {
            fun add(kind: Kind, vararg identifiers: String) = identifiers.forEach { put(it, kind) }
            add(Kind.MacStudio, "13,1", "13,2", "14,13", "14,14", "15,14", "16,9")
            add(Kind.MacMini, "14,3", "14,12", "16,10", "16,11")
            add(Kind.MacPro(3), "14,8")
            add(Kind.IMac, "15,4", "15,5", "16,2", "16,3")
            add(
                Kind.Laptop, "14,2", "14,5", "14,6", "14,7", "14,9", "14,10", "14,15", "15,3", "15,6", "15,7", "15,8", "15,9", "15,10",
                "15,11", "15,12", "15,13", "16,1", "16,5", "16,6", "16,7", "16,8", "16,12", "16,13", "17,2",
            )
        }

        private fun kind(model: String): Kind {
            val trimmed = model.trim()
            return when {
                trimmed.startsWith("iPhone") -> Kind.IPhone
                trimmed.startsWith("iPad") -> Kind.IPad
                trimmed.startsWith("MacBook") -> Kind.Laptop
                trimmed.startsWith("iMac") -> Kind.IMac
                trimmed.startsWith("Macmini") -> Kind.MacMini
                trimmed.startsWith("MacPro") -> {
                    val major = trimmed.removePrefix("MacPro").takeWhile { it.isDigit() }.toIntOrNull() ?: 0
                    Kind.MacPro(
                        if (major >= 7) {
                            3
                        } else if (major == 6) {
                            2
                        } else {
                            1
                        },
                    )
                }
                trimmed.startsWith("Mac") && trimmed.getOrNull(3)?.isDigit() == true -> appleSiliconKinds[trimmed.substring(3)] ?: Kind.Generic
                else -> Kind.Generic
            }
        }

        private val namedColors = mapOf(
            "silver" to RGB(0.62, 0.63, 0.65),
            "spacegray" to RGB(0.40, 0.41, 0.43),
            "spacegrey" to RGB(0.40, 0.41, 0.43),
            "spaceblack" to RGB(0.22, 0.22, 0.23),
            "black" to RGB(0.22, 0.22, 0.23),
            "midnight" to RGB(0.18, 0.22, 0.31),
            "starlight" to RGB(0.78, 0.72, 0.62),
            "gold" to RGB(0.82, 0.68, 0.48),
            "rosegold" to RGB(0.85, 0.63, 0.58),
            "skyblue" to RGB(0.52, 0.67, 0.80),
            "blue" to RGB(0.27, 0.45, 0.68),
            "green" to RGB(0.33, 0.56, 0.43),
            "pink" to RGB(0.86, 0.47, 0.53),
            "purple" to RGB(0.52, 0.44, 0.70),
            "orange" to RGB(0.89, 0.50, 0.27),
            "yellow" to RGB(0.86, 0.69, 0.24),
        )

        private fun color(text: String): RGB? {
            val trimmed = text.trim()
            val key = trimmed.lowercase().filter { it.isLetter() }
            if (key.isNotEmpty()) namedColors[key]?.let { return it }
            if (trimmed.startsWith("#") && trimmed.length == 7) {
                val value = trimmed.substring(1).toIntOrNull(16)
                if (value != null) {
                    return RGB((value shr 16 and 0xFF) / 255.0, (value shr 8 and 0xFF) / 255.0, (value and 0xFF) / 255.0)
                }
            }
            val parts = trimmed.split(',').mapNotNull { it.trim().toDoubleOrNull() }
            if (parts.size == 3 && parts.all { it in 0.0..255.0 }) {
                return RGB(parts[0] / 255, parts[1] / 255, parts[2] / 255)
            }
            return null
        }
    }
}
