package io.nekohasekai.screensharing.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class NetworkQuality {
    @SerialName("localNetwork")
    LocalNetwork,

    // The mode-5 reply sets 6 Mbit/s maximum and 0.333 minimum (observed on iPad17,1, iPadOS 27.2 24B5099f).
    @SerialName("internet")
    Internet,
}

enum class ChromaSampling {
    Yuv420,
    Yuv444,
    ;

    // AVConference mode 7 picks 444f from {420f, 444f}, and 420f from {420f}, in HDR modes 0 and 3
    // (AVConference 2235.63.5.2, macOS 27.0.1 26A434, UUID 93D8C8C0-8B9C-3A5E-A47D-01B5FAF73E1B,
    // -[AVCMediaStreamNegotiator pickBestPixelFormatFromSet:forPayload:] 0x1c12e22a0..0x1c12e24bc,
    // _AVCPixelFormatPreferenceListIPadCompanion 0x1e577943c, _AVCPixelFormatPreferenceListIPadCompanionHDR 0x1e5779444;
    // AVConference 2260.14.1, macOS 27.2 26B5101f, UUID EDFB99EF-2F5E-35DD-8E63-089CBD102EFE,
    // -[AVCMediaStreamNegotiator pickBestPixelFormatFromSet:forPayload:] 0x1c0956090..0x1c09562ac,
    // _AVCPixelFormatPreferenceListIPadCompanion 0x1ec938d74, _AVCPixelFormatPreferenceListIPadCompanionHDR 0x1ec938d7c).
    fun pixelFormats(available: Int): Int = when (this) {
        Yuv420 -> available and 0x07
        Yuv444 -> available
    }
}

enum class MaximumFrameRate(val rawValue: Int) {
    Thirty(30),
    Sixty(60),
    ;

    companion object {
        fun of(rawValue: Int): MaximumFrameRate? = entries.firstOrNull { it.rawValue == rawValue }
    }
}

data class NetworkConfiguration(
    val quality: NetworkQuality = NetworkQuality.LocalNetwork,
    val internetMaximumFrameRate: MaximumFrameRate = MaximumFrameRate.Sixty,
) {
    val usesCoreDevice: Boolean get() = quality == NetworkQuality.Internet

    val bands: Int get() = if (usesCoreDevice) 1 else 4

    val schedulesPresentation: Boolean get() = quality == NetworkQuality.LocalNetwork

    val maximumFrameRate: MaximumFrameRate
        get() = if (quality == NetworkQuality.LocalNetwork) MaximumFrameRate.Sixty else internetMaximumFrameRate

    // Mode 5 negotiated 4:2:0 (observed on iPad17,1, iPadOS 27.2 24B5099f).
    val chroma: ChromaSampling get() = if (usesCoreDevice) ChromaSampling.Yuv420 else ChromaSampling.Yuv444
}
