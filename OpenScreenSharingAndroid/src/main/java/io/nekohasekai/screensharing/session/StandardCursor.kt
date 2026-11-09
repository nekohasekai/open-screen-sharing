package io.nekohasekai.screensharing.session

sealed interface StandardCursor {
    data object Arrow : StandardCursor
    data object ContextualMenu : StandardCursor
    data object PointingHand : StandardCursor
    data object OpenHand : StandardCursor
    data object ClosedHand : StandardCursor
    data object DragLink : StandardCursor
    data object DragCopy : StandardCursor
    data object OperationNotAllowed : StandardCursor
    data object IBeam : StandardCursor
    data object VerticalIBeam : StandardCursor
    data object Crosshair : StandardCursor
    data object ZoomIn : StandardCursor
    data object ZoomOut : StandardCursor
    data class Resize(val directions: ResizeDirections) : StandardCursor

    @JvmInline
    value class ResizeDirections(val rawValue: Int) {
        operator fun contains(other: ResizeDirections): Boolean = rawValue and other.rawValue == other.rawValue
        operator fun plus(other: ResizeDirections): ResizeDirections = ResizeDirections(rawValue or other.rawValue)

        companion object {
            val Up = ResizeDirections(1 shl 0)
            val Down = ResizeDirections(1 shl 1)
            val Left = ResizeDirections(1 shl 2)
            val Right = ResizeDirections(1 shl 3)
            val UpLeft = ResizeDirections(1 shl 4)
            val UpRight = ResizeDirections(1 shl 5)
            val DownLeft = ResizeDirections(1 shl 6)
            val DownRight = ResizeDirections(1 shl 7)
        }
    }

    companion object {
        private val known: Map<Long, StandardCursor> = mapOf(
            // Cursor images the Mac sends for the AppKit cursors of macOS 27.0.1 26A434: ScreensharingAgent passes the
            // CGSGetGlobalCursorData image and hotspot to screensharingd unscaled (ScreensharingAgent 756.36.5.2,
            // UUID DA1CD9BA-97A1-3758-B425-00FA34B428A3, sub_100029818 0x100029860..0x10002991c).
            0x550cf3e12f82e290uL.toLong() to Arrow,
            0xa1d04e5d7b3638c1uL.toLong() to ContextualMenu,
            0x794a89c5f925811duL.toLong() to PointingHand,
            0x50644df703aa9698uL.toLong() to OpenHand,
            0xbee3f9deb4290ac6uL.toLong() to ClosedHand,
            0xc45e03a98e56c597uL.toLong() to DragLink,
            0x404d0f11c2436172uL.toLong() to DragCopy,
            0x95ad88c4cc9e3d42uL.toLong() to OperationNotAllowed,
            0x892a7ece621ac8c5uL.toLong() to IBeam,
            0x9ae3c16e5e50bde8uL.toLong() to VerticalIBeam,
            0xbb6e1a08616ba322uL.toLong() to Crosshair,
            0x099f7efbca83ccbauL.toLong() to ZoomIn,
            0x1343377190e903aauL.toLong() to ZoomOut,
            0xd39b94f838be4e58uL.toLong() to Resize(ResizeDirections.Left),
            0x42d8bbc7a95a09cauL.toLong() to Resize(ResizeDirections.Right),
            0x95e871169704c06duL.toLong() to Resize(ResizeDirections.Left + ResizeDirections.Right),
            0xc4629ef7e72b4692uL.toLong() to Resize(ResizeDirections.Up),
            0xddefb65d0e8d7aa3uL.toLong() to Resize(ResizeDirections.Down),
            0x99faf011b1d1672auL.toLong() to Resize(ResizeDirections.Up + ResizeDirections.Down),
            0xc272dbe8fb06c3b0uL.toLong() to Resize(ResizeDirections.Up + ResizeDirections.Down),
            0x8d1cc66ab524f521uL.toLong() to Resize(ResizeDirections.Up),
            0x21683648e46facfcuL.toLong() to Resize(ResizeDirections.Down),
            0xbf87b07eefdd81bfuL.toLong() to Resize(ResizeDirections.Left + ResizeDirections.Right),
            0x96f9141fdf6a48f0uL.toLong() to Resize(ResizeDirections.Left),
            0x248f97736835ac0duL.toLong() to Resize(ResizeDirections.Right),
            0x36a33a215de72d90uL.toLong() to Resize(ResizeDirections.UpLeft + ResizeDirections.DownRight),
            0xe533934d212ce831uL.toLong() to Resize(ResizeDirections.UpLeft),
            0x1427a75fc2437d9auL.toLong() to Resize(ResizeDirections.DownRight),
            0x8c24bd91dffa9649uL.toLong() to Resize(ResizeDirections.UpRight + ResizeDirections.DownLeft),
            0x576440b0b8e85e5buL.toLong() to Resize(ResizeDirections.UpRight),
            0x16531b30a60cab6buL.toLong() to Resize(ResizeDirections.DownLeft),
            0x355b7cb015997adcuL.toLong() to Resize(ResizeDirections.UpLeft + ResizeDirections.DownRight),
            0x6f4f0072c53c6f0auL.toLong() to Resize(ResizeDirections.UpLeft + ResizeDirections.DownRight),
            0xd739435cb04bb4c2uL.toLong() to Resize(ResizeDirections.UpRight + ResizeDirections.DownLeft),
            0x765961dff520bfd4uL.toLong() to Resize(ResizeDirections.UpRight + ResizeDirections.DownLeft),
            // RDP pointer updates from Windows 11 26200 for the IDC_* cursors at desktop scale 200.
            0xb2f71b963f1ed1e4uL.toLong() to Arrow,
            0x067aadb2834f6e3buL.toLong() to IBeam,
            0xc5c58c55594312a3uL.toLong() to Crosshair,
            0x0813db11b868f101uL.toLong() to PointingHand,
            0xa19606bd3c410ec1uL.toLong() to OperationNotAllowed,
            0x8898abd59d4ec795uL.toLong() to Resize(ResizeDirections.UpLeft + ResizeDirections.DownRight),
            0x015585096f9142dduL.toLong() to Resize(ResizeDirections.UpRight + ResizeDirections.DownLeft),
            0xca867364a1e80c4cuL.toLong() to Resize(ResizeDirections.Left + ResizeDirections.Right),
            0x42df45dc09d54621uL.toLong() to Resize(ResizeDirections.Up + ResizeDirections.Down),
            0x5047ed4a7be5e306uL.toLong() to Resize(ResizeDirections.Up + ResizeDirections.Down + ResizeDirections.Left + ResizeDirections.Right),
        )

        fun fingerprint(width: Int, height: Int, hotspotX: Int, hotspotY: Int, pixels: ByteArray): Long? {
            if (pixels.size < width * height * 4) return null
            var hash = 0xcbf29ce484222325uL.toLong()
            fun mix(byte: Int) {
                hash = hash xor (byte.toLong() and 0xff)
                hash *= 0x100000001b3L
            }
            for (value in intArrayOf(width, height, hotspotX, hotspotY)) {
                mix(value and 0xff)
                mix((value shr 8) and 0xff)
            }
            for (offset in 0 until width * height * 4 step 4) {
                val alpha = pixels[offset + 3].toInt() and 0xff
                mix(alpha)
                if (alpha == 255) {
                    val luminance = (pixels[offset].toInt() and 0xff) + 2 * (pixels[offset + 1].toInt() and 0xff) + (pixels[offset + 2].toInt() and 0xff)
                    mix(if (luminance >= 512) 1 else 0)
                }
            }
            return hash
        }

        fun of(fingerprint: Long): StandardCursor? = known[fingerprint]
    }
}
