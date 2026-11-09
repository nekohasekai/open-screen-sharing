package io.nekohasekai.screensharing.session

import androidx.compose.ui.geometry.Size
import io.nekohasekai.screensharing.App
import io.nekohasekai.screensharing.model.SessionQuality

data class SessionMemory(
    val quality: SessionQuality = SessionQuality.Adaptive,
    val observes: Boolean = false,
    val sharesClipboard: Boolean = true,
    val resolutionScale: Double = VirtualDisplayMode.DEFAULT_RESOLUTION_SCALE,
    val display: DisplaySelection? = null,
    val usesHDR: Boolean = false,
    val receivesAudio: Boolean = true,
    val sendsAudio: Boolean = false,
    val windowSize: Size? = null,
    val locksPointer: Boolean = false,
    val usesNativeTouch: Boolean = defaultUsesNativeTouch,
) {
    companion object {
        val defaultUsesNativeTouch: Boolean
            get() = App.instance.resources.configuration.smallestScreenWidthDp >= 600
    }
}
