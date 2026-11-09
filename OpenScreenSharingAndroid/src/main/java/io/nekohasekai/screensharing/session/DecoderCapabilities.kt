package io.nekohasekai.screensharing.session

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecInfo.VideoCapabilities.PerformancePoint
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicBoolean

class DecoderCapabilities private constructor() {
    private class Probe(val parameterSets: List<String>, val frame: String)

    private val hevcDecoders: List<MediaCodecInfo>
    private val h264Decoders: List<MediaCodecInfo>
    private val fullChromaDecoder: String?
    private val hdrDecoder: String?
    val supportsHEVC: Boolean
    val supportsH264: Boolean
    val supportsFullChroma: Boolean
    val supportsHDR: Boolean
    val maximumPixelRate: Long

    init {
        val decoders = try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder && it.isHardwareDecoder() }
        } catch (e: RuntimeException) {
            emptyList()
        }
        hevcDecoders = decoders.filter { it.supports(MediaFormat.MIMETYPE_VIDEO_HEVC) }
        h264Decoders = decoders.filter { it.supports(MediaFormat.MIMETYPE_VIDEO_AVC) }
        supportsHEVC = hevcDecoders.isNotEmpty()
        supportsH264 = h264Decoders.isNotEmpty()
        fullChromaDecoder = hevcDecoders.firstOrNull { decodesInHardware(it, fullChromaProbe) ?: it.supportsProfile(HEVC_PROFILE_MAIN_444) }?.name
        supportsFullChroma = fullChromaDecoder != null
        hdrDecoder = hevcDecoders.firstOrNull {
            decodesInHardware(it, hdrProbe) ?: (it.supportsProfile(HEVC_PROFILE_MAIN_444) && it.supportsTenBit())
        }?.name
        supportsHDR = hdrDecoder != null
        maximumPixelRate = if (supportsHEVC) decoderMaximumPixelRate() else 0
    }

    val pixelFormats: Int
        get() {
            var formats = (1 shl 0) or (1 shl 1)
            if (supportsFullChroma) formats = formats or (1 shl 3) or (1 shl 4)
            if (supportsHDR) formats = formats or (1 shl 5)
            return formats
        }

    internal fun decoderName(codec: VideoCodec, fullChroma: Boolean, highBitDepth: Boolean): String? = when (codec) {
        VideoCodec.H264 -> h264Decoders.firstOrNull()?.name
        VideoCodec.Hevc -> when {
            fullChroma && highBitDepth -> hdrDecoder
            fullChroma -> fullChromaDecoder
            highBitDepth -> hevcDecoders.firstOrNull { it.supportsTenBit() }?.name
            else -> null
        } ?: hevcDecoders.firstOrNull()?.name
    }

    private fun decoderMaximumPixelRate(): Long = hevcDecoders.maxOf { decoder ->
        val video = runCatching { decoder.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC).videoCapabilities }.getOrNull()
        if (video == null) {
            0L
        } else {
            probeSizes.firstNotNullOfOrNull { (width, height) ->
                maximumFrameRate(video, width, height)?.let { (it * width * height).toLong() }
            } ?: 0L
        }
    }

    private fun maximumFrameRate(video: MediaCodecInfo.VideoCapabilities, width: Int, height: Int): Double? {
        if (!video.isSizeSupported(width, height)) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val points = video.supportedPerformancePoints
            if (!points.isNullOrEmpty()) {
                var low = 0
                var high = MAXIMUM_PROBED_FRAME_RATE
                while (low < high) {
                    val rate = (low + high + 1) / 2
                    if (points.any { it.covers(PerformancePoint(width, height, rate)) }) low = rate else high = rate - 1
                }
                return low.takeIf { it > 0 }?.toDouble()
            }
        }
        val achievable = runCatching { video.getAchievableFrameRatesFor(width, height) }.getOrNull()
        if (achievable != null) return achievable.upper
        return runCatching { video.getSupportedFrameRatesFor(width, height).upper }.getOrNull()
    }

    private fun decodesInHardware(decoder: MediaCodecInfo, probe: Probe): Boolean? {
        val video = runCatching { decoder.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC).videoCapabilities }.getOrNull()
        if (video == null || !video.isSizeSupported(PROBE_SIZE, PROBE_SIZE)) return null
        val format = try {
            VideoDecoder.makeFormatDescription(VideoFormat(VideoCodec.Hevc, probe.parameterSets.map { hexData(it) }, bands = 1))
        } catch (e: Exception) {
            return false
        }
        val sample = VideoDecoder.makeSampleBuffer(hexData(probe.frame)) ?: return false
        val texture = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) SurfaceTexture(false) else SurfaceTexture(0)
        val surface = Surface(texture)
        var codec: MediaCodec? = null
        try {
            codec = MediaCodec.createByCodecName(decoder.name)
            codec.configure(format, surface, null, 0)
            codec.start()
            val input = codec.dequeueInputBuffer(PROBE_TIMEOUT)
            if (input < 0) return false
            codec.getInputBuffer(input)!!.apply {
                clear()
                put(sample)
            }
            codec.queueInputBuffer(input, 0, sample.size, 0, 0)
            val end = codec.dequeueInputBuffer(PROBE_TIMEOUT)
            if (end >= 0) codec.queueInputBuffer(end, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            val info = MediaCodec.BufferInfo()
            val deadline = System.nanoTime() + PROBE_DEADLINE
            while (System.nanoTime() < deadline) {
                val output = codec.dequeueOutputBuffer(info, PROBE_TIMEOUT)
                if (output < 0) continue
                val decoded = info.size > 0
                codec.releaseOutputBuffer(output, false)
                if (decoded) return true
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return false
            }
            return false
        } catch (e: Exception) {
            return false
        } finally {
            codec?.release()
            surface.release()
            texture.release()
        }
    }

    companion object {
        private const val PROBE_SIZE = 64
        private const val PROBE_TIMEOUT = 10_000L
        private const val PROBE_DEADLINE = 1_000_000_000L
        private const val MAXIMUM_PROBED_FRAME_RATE = 960
        private const val HEVC_PROFILE_MAIN_444 = 16

        // ScreenSharing's GetHEVCDecoderMaxSupportedFrameRate probes the decoder at 3840×2160 (ScreenSharing 776.27,
        // macOS 27.0.1 26A434, UUID EC0B2C20-F816-3FCE-822D-F9403C09EB50, _GetHEVCDecoderMaxSupportedFrameRate
        // 0x1cc0fb880..0x1cc0fb930, _dummy_hvcc_mem 0x1cc1b62f8).
        private val probeSizes = listOf(3840 to 2160, 2560 to 1440, 1920 to 1080)

        private val fullChromaProbe = Probe(
            listOf(
                "40010c01ffff0408000003009e2800000300001eba0240",
                "4201010408000003009e2800000300001e90041020b2dd49265780b4040000030004000003000420",
                "4401c172860c0224",
            ),
            "0000000c2801af78ebaefbfe7557cb4f",
        )

        private val hdrProbe = Probe(
            listOf(
                "40010c01ffff0408000003009c0800000300001e959809",
                "4201010408000003009c0800000300001e900410209b2cacd24995e02d010000030001000003000108",
                "4401c172860c4224",
            ),
            "0000000e2801af1380e668e3ff1cfc7f6cf0",
        )

        private val started = AtomicBoolean(false)
        private val computation = FutureTask { DecoderCapabilities() }

        val current: DecoderCapabilities
            get() {
                prepare()
                return computation.get()
            }

        fun prepare() {
            if (started.compareAndSet(false, true)) Thread(computation, "decoder-capabilities").start()
        }

        private fun MediaCodecInfo.isHardwareDecoder(): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return isHardwareAccelerated && !isAlias
            val name = this.name.lowercase()
            return when {
                name.startsWith("arc.") -> true
                name.startsWith("omx.google.") || name.startsWith("omx.ffmpeg.") || name.startsWith("c2.android.") || name.startsWith("c2.google.") -> false
                name.startsWith("omx.sec.") && ".sw." in name -> false
                name == "omx.qcom.video.decoder.hevcswvdec" -> false
                else -> name.startsWith("omx.") || name.startsWith("c2.")
            }
        }

        private fun MediaCodecInfo.supports(type: String) = supportedTypes.any { it.equals(type, ignoreCase = true) }

        private fun MediaCodecInfo.supportsProfile(profile: Int) = runCatching {
            getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC).profileLevels.any { it.profile == profile }
        }.getOrDefault(false)

        private fun MediaCodecInfo.supportsTenBit() = supportsProfile(CodecProfileLevel.HEVCProfileMain10) || supportsProfile(CodecProfileLevel.HEVCProfileMain10HDR10)

        private fun hexData(hex: String) = ByteArray(hex.length / 2) { index -> hex.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }
}
