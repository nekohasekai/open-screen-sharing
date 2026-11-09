package io.nekohasekai.screensharing.session

import java.nio.ByteBuffer

object NativeMemory {
    init {
        go.Seq.touch()
    }

    @JvmStatic
    external fun address(buffer: ByteBuffer): Long
}
