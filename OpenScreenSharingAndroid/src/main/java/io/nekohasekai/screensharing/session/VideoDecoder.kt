package io.nekohasekai.screensharing.session

import android.hardware.DataSpace
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Looper
import android.util.Log
import android.view.Surface
import io.nekohasekai.screensharing.app.MessageError
import java.nio.ByteBuffer
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.max

data class FrameTiming(val arrival: Long, val capture: Long, val composed: Long?)

class DecodedVideoFrame(
    val pixelBuffer: VideoImage?,
    val strips: List<VideoImage>,
    val canvasHeight: Int,
    val turns: PictureTurns,
    val timing: FrameTiming,
) {
    fun release() {
        pixelBuffer?.release()
        for (strip in strips) strip.release()
    }
}

class VideoDecoder(
    private val context: VideoContext,
    private val output: (DecodedVideoFrame) -> Unit,
    private val failure: () -> Unit,
    private val unsupported: (hdr: Boolean, error: Throwable) -> Unit,
) {
    private class Submission(val frameID: Long, val band: Int)

    private class PendingFrame(
        val id: Long,
        var arrival: Long,
        val capture: Long,
        val composed: Long?,
        val turns: PictureTurns,
        bands: Int,
    ) {
        var submittedMask = 0
        var ended = false
        var remaining = 0
        val latestTickets = LongArray(bands)
        val strips = arrayOfNulls<VideoImage>(bands)

        fun release() {
            for (index in strips.indices) {
                strips[index]?.release()
                strips[index] = null
            }
        }
    }

    private class Input(val ticket: Long, val data: ByteArray)

    private class OutputFormat(val width: Int, val height: Int, val transfer: VideoTransfer, val primaries: VideoPrimaries)

    private class Output(val index: Int, val ticket: Long, val hasPicture: Boolean, val format: OutputFormat) {
        var token = 0L
    }

    private val handler = context.handler
    private var session: MediaCodec? = null
    private var formatDescription: MediaFormat? = null
    private var sequence: SequenceParameterSet? = null
    private var codec: VideoCodec? = null
    private var parameterSets: List<ByteArray> = emptyList()
    private var waitingForSync = true
    private var canvasHeight = 0
    private var bands = 1
    private var isHDR = false
    private var suspended = false
    private var generation = 0L
    private var nextTicket = 0L
    private val inFlight = HashMap<Long, Submission>()
    private val pending = ArrayDeque<PendingFrame>()
    private var base = arrayOfNulls<VideoImage>(0)
    private var lastSubmittedFrameID: Long? = null
    private var lastSubmissionEnded = true
    private var rejections = 0
    private var reportedUnsupported = false
    private val freeInputs = ArrayDeque<Int>()
    private val inputs = ArrayDeque<Input>()
    private val outputs = ArrayDeque<Output>()
    private var rendering: Output? = null
    private var lastToken = 0L
    private var outputFormat = OutputFormat(0, 0, VideoTransfer.Srgb, VideoPrimaries.Srgb)

    private val load = ReentrantLock()
    private val loadChanged = load.newCondition()
    private var queuedUnits = 0
    private var queuedFrames = 0
    private var inFlightCount = 0
    private var pendingCount = 0
    private var lastQueuedFrameID = 0L

    fun reset() {
        handler.post {
            invalidateSession()
            formatDescription = null
            sequence = null
            parameterSets = emptyList()
            codec = null
        }
    }

    fun setFormat(format: VideoFormat) {
        handler.post { applyFormat(format) }
    }

    private fun applyFormat(format: VideoFormat) {
        val changed = format.codec != codec || !sameParameterSets(format.parameterSets) || format.isHDR != isHDR ||
            format.bands != bands || format.canvasHeight != canvasHeight
        if (!changed && formatDescription != null) return
        if (changed) {
            rejections = 0
            reportedUnsupported = false
        }
        invalidateSession()
        formatDescription = null
        sequence = null
        parameterSets = format.parameterSets
        codec = format.codec
        canvasHeight = format.canvasHeight
        bands = format.bands
        isHDR = format.isHDR
        try {
            if ((bands != 1 && bands != 4) || (bands != 1 && canvasHeight <= 0)) {
                throw MessageError("the video stream has an invalid band layout")
            }
            sequence = SequenceParameterSet.parse(format.codec, format.parameterSets)
            formatDescription = makeFormatDescription(format)
        } catch (e: Exception) {
            reportUnsupported(e)
        }
    }

    private fun sameParameterSets(sets: List<ByteArray>): Boolean = sets.size == parameterSets.size && sets.indices.all { sets[it].contentEquals(parameterSets[it]) }

    fun setSuspended(suspended: Boolean) {
        handler.post {
            if (suspended == this.suspended) return@post
            this.suspended = suspended
            if (suspended) {
                invalidateSession()
            }
        }
    }

    fun decode(unit: VideoAccessUnit) {
        val newFrame: Boolean
        load.withLock {
            newFrame = unit.frameID != lastQueuedFrameID
            if (Looper.myLooper() != handler.looper) {
                var remaining = CAPACITY_WAIT
                while (remaining > 0 && (queuedUnits + inFlightCount >= MAX_IN_FLIGHT || (newFrame && queuedFrames + pendingCount >= MAX_PENDING_FRAMES))) {
                    remaining = loadChanged.awaitNanos(remaining)
                }
            }
            queuedUnits += 1
            if (newFrame) queuedFrames += 1
            lastQueuedFrameID = unit.frameID
        }
        handler.post {
            submit(unit)
            load.withLock {
                queuedUnits -= 1
                if (newFrame) queuedFrames -= 1
                publishLoad()
            }
        }
    }

    private fun publishLoad() {
        load.withLock {
            inFlightCount = inFlight.size
            pendingCount = pending.size
            loadChanged.signalAll()
        }
    }

    private fun submit(unit: VideoAccessUnit) {
        if (suspended) return
        if (unit.discontinuity) {
            invalidateSession()
        }
        if (waitingForSync && !unit.isSync) {
            if (unit.discontinuity) failure()
            return
        }
        if (formatDescription == null) return
        val fullMask = (1 shl bands) - 1
        if (unit.bands != bands || unit.band < 0 || unit.band >= bands || unit.frameID <= 0 || unit.changedBands <= 0 ||
            unit.changedBands and fullMask.inv() != 0 || unit.changedBands and (1 shl unit.band) == 0
        ) {
            recover()
            return
        }
        if (session == null) {
            try {
                createSession()
            } catch (e: Exception) {
                reportUnsupported(e)
                return
            }
        }
        if (session == null) return
        if (inFlight.size >= MAX_IN_FLIGHT || (lastSubmittedFrameID != unit.frameID && pending.size >= MAX_PENDING_FRAMES)) {
            recover()
            return
        }
        val sample = makeSampleBuffer(unit.data)
        if (sample == null) {
            recover()
            return
        }
        if (lastSubmittedFrameID != unit.frameID) {
            val previous = lastSubmittedFrameID
            if (previous != null && (!lastSubmissionEnded || previous == Long.MAX_VALUE || unit.frameID != previous + 1)) {
                recover()
                return
            }
            if (pending.size >= MAX_PENDING_FRAMES) {
                recover()
                return
            }
            pending.addLast(PendingFrame(unit.frameID, unit.arrivalNanos, unit.presentationTimeNanos, unit.composedNanos, unit.turns, bands))
            lastSubmittedFrameID = unit.frameID
            lastSubmissionEnded = false
        }
        val frame = pending.lastOrNull()
        if (lastSubmissionEnded || frame == null || frame.id != unit.frameID) {
            recover()
            return
        }
        frame.submittedMask = frame.submittedMask or (1 shl unit.band)
        frame.arrival = max(frame.arrival, unit.arrivalNanos)
        if (unit.changedBands != frame.submittedMask) {
            recover()
            return
        }
        nextTicket += 1
        val ticket = nextTicket
        frame.remaining += 1
        frame.latestTickets[unit.band] = ticket
        frame.ended = unit.frameEnd
        lastSubmissionEnded = unit.frameEnd
        inFlight[ticket] = Submission(unit.frameID, unit.band)
        if (waitingForSync) {
            waitingForSync = false
            base.forEach { it?.release() }
            base = arrayOfNulls(bands)
        }
        inputs.addLast(Input(ticket, sample))
        feedInputs()
    }

    private fun feedInputs() {
        val session = session ?: return
        while (freeInputs.isNotEmpty() && inputs.isNotEmpty()) {
            val index = freeInputs.removeFirst()
            val input = inputs.removeFirst()
            try {
                val buffer = session.getInputBuffer(index) ?: throw MessageError("MediaCodec returned no input buffer $index")
                buffer.clear()
                if (input.data.size > buffer.remaining()) {
                    throw MessageError("a ${input.data.size}-byte access unit is larger than the ${buffer.remaining()}-byte decoder input buffer")
                }
                buffer.put(input.data)
                session.queueInputBuffer(index, 0, input.data.size, input.ticket, 0)
            } catch (e: Exception) {
                rejected(e)
                return
            }
        }
    }

    private inner class Callback(private val generation: Long) : MediaCodec.Callback() {
        private fun current(codec: MediaCodec) = generation == this@VideoDecoder.generation && codec === session

        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            if (!current(codec)) return
            freeInputs.addLast(index)
            feedInputs()
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (!current(codec)) return
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 && info.size == 0) {
                runCatching { codec.releaseOutputBuffer(index, false) }
                return
            }
            outputs.addLast(Output(index, info.presentationTimeUs, info.size > 0, outputFormat))
            renderNext()
            publishLoad()
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            if (!current(codec)) return
            rejected(e)
            publishLoad()
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            if (!current(codec)) return
            outputFormat = parseOutputFormat(format)
        }
    }

    private fun parseOutputFormat(format: MediaFormat): OutputFormat {
        var width = format.integer(MediaFormat.KEY_WIDTH)
        var height = format.integer(MediaFormat.KEY_HEIGHT)
        if (format.containsKey(KEY_CROP_LEFT) && format.containsKey(KEY_CROP_RIGHT)) {
            width = format.getInteger(KEY_CROP_RIGHT) - format.getInteger(KEY_CROP_LEFT) + 1
        }
        if (format.containsKey(KEY_CROP_TOP) && format.containsKey(KEY_CROP_BOTTOM)) {
            height = format.getInteger(KEY_CROP_BOTTOM) - format.getInteger(KEY_CROP_TOP) + 1
        }
        val transfer = when (format.integer(MediaFormat.KEY_COLOR_TRANSFER)) {
            MediaFormat.COLOR_TRANSFER_ST2084 -> VideoTransfer.Pq
            MediaFormat.COLOR_TRANSFER_HLG -> VideoTransfer.Hlg
            0 -> if (isHDR) VideoTransfer.Pq else VideoTransfer.Srgb
            else -> VideoTransfer.Srgb
        }
        val primaries = when (format.integer(MediaFormat.KEY_COLOR_STANDARD)) {
            MediaFormat.COLOR_STANDARD_BT2020 -> VideoPrimaries.Bt2020
            MediaFormat.COLOR_STANDARD_BT709, MediaFormat.COLOR_STANDARD_BT601_PAL, MediaFormat.COLOR_STANDARD_BT601_NTSC -> VideoPrimaries.Srgb
            0 -> if (isHDR) VideoPrimaries.DisplayP3 else VideoPrimaries.Srgb
            else -> VideoPrimaries.DisplayP3
        }
        return OutputFormat(width, height, transfer, primaries)
    }

    private fun renderNext() {
        while (rendering == null) {
            val session = session ?: return
            val next = outputs.removeFirstOrNull() ?: return
            if (!next.hasPicture) {
                runCatching { session.releaseOutputBuffer(next.index, false) }
                completed(next.ticket, null)
                continue
            }
            next.token = max(System.nanoTime(), lastToken + 1)
            lastToken = next.token
            try {
                session.releaseOutputBuffer(next.index, next.token)
            } catch (e: Exception) {
                rejected(e)
                return
            }
            rendering = next
            val epoch = generation
            val token = next.token
            handler.postDelayed({
                if (generation == epoch && rendering?.token == token) {
                    rejected(MessageError("a decoded frame never reached the video surface"))
                    publishLoad()
                }
            }, RENDER_TIMEOUT)
        }
    }

    private fun frameAvailable() {
        val timestamp = context.latch() ?: return
        val current = rendering ?: return
        if (timestamp != current.token) return
        rendering = null
        val format = current.format
        var transfer = format.transfer
        var primaries = format.primaries
        val dataSpace = context.latchedDataSpace()
        if (Build.VERSION.SDK_INT >= 33 && dataSpace != DataSpace.DATASPACE_UNKNOWN) {
            when (DataSpace.getTransfer(dataSpace)) {
                DataSpace.TRANSFER_ST2084 -> transfer = VideoTransfer.Pq
                DataSpace.TRANSFER_HLG -> transfer = VideoTransfer.Hlg
                DataSpace.TRANSFER_UNSPECIFIED -> Unit
                else -> transfer = VideoTransfer.Srgb
            }
            when (DataSpace.getStandard(dataSpace)) {
                DataSpace.STANDARD_BT2020, DataSpace.STANDARD_BT2020_CONSTANT_LUMINANCE -> primaries = VideoPrimaries.Bt2020
                DataSpace.STANDARD_DCI_P3 -> primaries = VideoPrimaries.DisplayP3
                DataSpace.STANDARD_BT709, DataSpace.STANDARD_BT601_625, DataSpace.STANDARD_BT601_625_UNADJUSTED,
                DataSpace.STANDARD_BT601_525, DataSpace.STANDARD_BT601_525_UNADJUSTED,
                -> primaries = VideoPrimaries.Srgb
            }
        }
        completed(current.ticket, context.copy(format.width, format.height, transfer, primaries))
        renderNext()
        publishLoad()
    }

    private fun completed(ticket: Long, image: VideoImage?) {
        val submission = inFlight.remove(ticket)
        if (submission == null) {
            image?.release()
            return
        }
        val frame = pending.firstOrNull { it.id == submission.frameID }
        if (image == null || frame == null) {
            image?.release()
            recover()
            return
        }
        rejections = 0
        frame.remaining -= 1
        if (frame.latestTickets[submission.band] == ticket) {
            frame.strips[submission.band]?.release()
            frame.strips[submission.band] = image
        } else {
            image.release()
        }
        publishCompletedFrames()
    }

    private fun publishCompletedFrames() {
        while (true) {
            val first = pending.firstOrNull() ?: return
            if (!first.ended || first.remaining != 0) return
            for (band in 0 until bands) {
                if (first.submittedMask and (1 shl band) == 0) continue
                val strip = first.strips[band]
                if (strip == null) {
                    recover()
                    return
                }
                base[band]?.release()
                base[band] = strip
                first.strips[band] = null
            }
            pending.removeFirst()
            first.release()
            val strips = base.filterNotNull()
            if (strips.size == bands) {
                val width = strips[0].width
                val height = strips[0].height
                if (width <= 0 || height <= 0 || strips.any { it.width != width || it.height != height }) {
                    recover()
                    return
                }
                val frame = DecodedVideoFrame(
                    pixelBuffer = if (bands == 1) strips[0].retain() else null,
                    strips = if (bands == 1) emptyList() else strips.map { it.retain() },
                    canvasHeight = canvasHeight,
                    turns = first.turns,
                    timing = FrameTiming(first.arrival, first.capture, first.composed),
                )
                output(frame)
            }
        }
    }

    private fun recover() {
        invalidateSession()
        failure()
    }

    private fun rejected(error: Throwable) {
        Log.e(TAG, "MediaCodec rejected a frame", error)
        rejections += 1
        recover()
        if (rejections >= REJECTION_LIMIT) {
            reportUnsupported(error)
        }
    }

    private fun reportUnsupported(error: Throwable) {
        if (reportedUnsupported) return
        reportedUnsupported = true
        unsupported(isHDR, error)
    }

    private fun invalidateSession() {
        generation += 1
        for (frame in pending) frame.release()
        pending.clear()
        for (image in base) image?.release()
        base = arrayOfNulls(0)
        lastSubmittedFrameID = null
        lastSubmissionEnded = true
        inFlight.clear()
        inputs.clear()
        freeInputs.clear()
        outputs.clear()
        rendering = null
        waitingForSync = true
        session?.let { runCatching { it.release() } }
        session = null
        publishLoad()
    }

    private fun createSession() {
        val formatDescription = formatDescription ?: return
        val codec = codec ?: return
        val surface = context.surface ?: throw context.failure ?: MessageError("the video surface is unavailable")
        val sequence = sequence
        val name = DecoderCapabilities.current.decoderName(
            codec,
            fullChroma = sequence?.chromaFormat == 3,
            highBitDepth = (sequence?.bitDepth ?: 8) > 8 || isHDR,
        ) ?: throw MessageError("this device has no hardware decoder for ${codec.label}")
        outputFormat = OutputFormat(
            formatDescription.getInteger(MediaFormat.KEY_WIDTH),
            formatDescription.getInteger(MediaFormat.KEY_HEIGHT),
            if (isHDR) VideoTransfer.Pq else VideoTransfer.Srgb,
            if (isHDR) VideoPrimaries.DisplayP3 else VideoPrimaries.Srgb,
        )
        context.frameAvailable = ::frameAvailable
        session = try {
            configured(name, formatDescription, surface, lowLatency = true)
        } catch (e: Exception) {
            Log.w(TAG, "configure $name for low latency", e)
            configured(name, formatDescription, surface, lowLatency = false)
        }
    }

    private fun configured(name: String, description: MediaFormat, surface: Surface, lowLatency: Boolean): MediaCodec {
        val created = MediaCodec.createByCodecName(name)
        try {
            val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaFormat(description)
            } else {
                MediaFormat.createVideoFormat(description.getString(MediaFormat.KEY_MIME)!!, description.getInteger(MediaFormat.KEY_WIDTH), description.getInteger(MediaFormat.KEY_HEIGHT)).also { copy ->
                    for (key in listOf("csd-0", "csd-1")) description.getByteBuffer(key)?.let { copy.setByteBuffer(key, it) }
                    copy.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, description.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                }
            }
            if (lowLatency) {
                val capabilities = created.codecInfo.getCapabilitiesForType(description.getString(MediaFormat.KEY_MIME)!!)
                if (Build.VERSION.SDK_INT >= 30 && capabilities.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)) {
                    format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
                format.setInteger(MediaFormat.KEY_PRIORITY, 0)
                format.setInteger(MediaFormat.KEY_OPERATING_RATE, OPERATING_RATE * bands)
            }
            created.setCallback(Callback(generation), handler)
            created.configure(format, surface, null, 0)
            created.start()
            return created
        } catch (e: Exception) {
            created.release()
            throw e
        }
    }

    private class SequenceParameterSet(val width: Int, val height: Int, val chromaFormat: Int, val bitDepth: Int) {
        companion object {
            fun parse(codec: VideoCodec, sets: List<ByteArray>): SequenceParameterSet? = sets.firstNotNullOfOrNull { set ->
                try {
                    when {
                        codec == VideoCodec.Hevc && set.size > 2 && (set[0].toInt() shr 1) and 0x3f == HEVC_NAL_SPS -> hevc(BitReader(set, 2))
                        codec == VideoCodec.H264 && set.isNotEmpty() && set[0].toInt() and 0x1f == H264_NAL_SPS -> h264(BitReader(set, 1))
                        else -> null
                    }
                } catch (e: IndexOutOfBoundsException) {
                    null
                }
            }

            private fun hevc(reader: BitReader): SequenceParameterSet {
                reader.skip(4)
                val maxSubLayersMinus1 = reader.bits(3).toInt()
                reader.skip(1)
                reader.skip(8 + 32 + 48 + 8)
                val profilePresent = BooleanArray(maxSubLayersMinus1)
                val levelPresent = BooleanArray(maxSubLayersMinus1)
                for (layer in 0 until maxSubLayersMinus1) {
                    profilePresent[layer] = reader.flag()
                    levelPresent[layer] = reader.flag()
                }
                if (maxSubLayersMinus1 > 0) reader.skip(2 * (8 - maxSubLayersMinus1))
                for (layer in 0 until maxSubLayersMinus1) {
                    if (profilePresent[layer]) reader.skip(88)
                    if (levelPresent[layer]) reader.skip(8)
                }
                reader.unsignedGolomb()
                val chromaFormat = reader.unsignedGolomb()
                if (chromaFormat == 3) reader.skip(1)
                var width = reader.unsignedGolomb()
                var height = reader.unsignedGolomb()
                if (reader.flag()) {
                    val left = reader.unsignedGolomb()
                    val right = reader.unsignedGolomb()
                    val top = reader.unsignedGolomb()
                    val bottom = reader.unsignedGolomb()
                    width -= (if (chromaFormat == 1 || chromaFormat == 2) 2 else 1) * (left + right)
                    height -= (if (chromaFormat == 1) 2 else 1) * (top + bottom)
                }
                return SequenceParameterSet(width, height, chromaFormat, 8 + reader.unsignedGolomb())
            }

            private fun h264(reader: BitReader): SequenceParameterSet {
                val profile = reader.bits(8).toInt()
                reader.skip(16)
                reader.unsignedGolomb()
                var chromaFormat = 1
                var bitDepth = 8
                var separatePlanes = false
                if (profile in HIGH_PROFILES) {
                    chromaFormat = reader.unsignedGolomb()
                    if (chromaFormat == 3) separatePlanes = reader.flag()
                    bitDepth = 8 + reader.unsignedGolomb()
                    reader.unsignedGolomb()
                    reader.skip(1)
                    if (reader.flag()) {
                        for (list in 0 until if (chromaFormat == 3) 12 else 8) {
                            if (!reader.flag()) continue
                            var last = 8
                            var next = 8
                            repeat(if (list < 6) 16 else 64) {
                                if (next != 0) next = (last + reader.signedGolomb() + 256) % 256
                                if (next != 0) last = next
                            }
                        }
                    }
                }
                reader.unsignedGolomb()
                when (reader.unsignedGolomb()) {
                    0 -> reader.unsignedGolomb()
                    1 -> {
                        reader.skip(1)
                        reader.signedGolomb()
                        reader.signedGolomb()
                        val cycle = reader.unsignedGolomb()
                        if (cycle > 255) throw IndexOutOfBoundsException()
                        repeat(cycle) { reader.signedGolomb() }
                    }
                }
                reader.unsignedGolomb()
                reader.skip(1)
                val widthInMacroblocks = reader.unsignedGolomb() + 1
                val heightInMapUnits = reader.unsignedGolomb() + 1
                val frameMacroblocksOnly = reader.flag()
                if (!frameMacroblocksOnly) reader.skip(1)
                reader.skip(1)
                val fieldFactor = if (frameMacroblocksOnly) 1 else 2
                var width = widthInMacroblocks * 16
                var height = heightInMapUnits * 16 * fieldFactor
                if (reader.flag()) {
                    val left = reader.unsignedGolomb()
                    val right = reader.unsignedGolomb()
                    val top = reader.unsignedGolomb()
                    val bottom = reader.unsignedGolomb()
                    val arrayType = if (separatePlanes) 0 else chromaFormat
                    val cropX = if (arrayType == 0 || chromaFormat == 3) 1 else 2
                    val cropY = (if (arrayType == 0 || chromaFormat != 1) 1 else 2) * fieldFactor
                    width -= cropX * (left + right)
                    height -= cropY * (top + bottom)
                }
                return SequenceParameterSet(width, height, chromaFormat, bitDepth)
            }

            private val HIGH_PROFILES = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)
        }
    }

    private class BitReader(source: ByteArray, offset: Int) {
        private val data: ByteArray
        private var position = 0

        init {
            val unescaped = java.io.ByteArrayOutputStream(source.size)
            var zeros = 0
            for (index in offset until source.size) {
                val value = source[index].toInt() and 0xff
                if (zeros >= 2 && value == 3) {
                    zeros = 0
                    continue
                }
                unescaped.write(value)
                zeros = if (value == 0) zeros + 1 else 0
            }
            data = unescaped.toByteArray()
        }

        fun bit(): Int {
            if (position >= data.size * 8) throw IndexOutOfBoundsException()
            val value = (data[position / 8].toInt() shr (7 - position % 8)) and 1
            position += 1
            return value
        }

        fun bits(count: Int): Long {
            var value = 0L
            repeat(count) { value = (value shl 1) or bit().toLong() }
            return value
        }

        fun skip(count: Int) {
            if (position + count > data.size * 8) throw IndexOutOfBoundsException()
            position += count
        }

        fun flag(): Boolean = bit() == 1

        fun unsignedGolomb(): Int {
            var zeros = 0
            while (bit() == 0) {
                zeros += 1
                if (zeros > 31) throw IndexOutOfBoundsException()
            }
            return ((1L shl zeros) - 1 + bits(zeros)).toInt()
        }

        fun signedGolomb(): Int {
            val value = unsignedGolomb()
            return if (value and 1 == 1) (value + 1) / 2 else -(value / 2)
        }
    }

    companion object {
        private const val TAG = "VideoDecoder"
        private const val MAX_IN_FLIGHT = 8
        private const val MAX_PENDING_FRAMES = 4
        private const val REJECTION_LIMIT = 10
        private const val CAPACITY_WAIT = 1_000_000_000L
        private const val RENDER_TIMEOUT = 500L
        private const val OPERATING_RATE = 120
        private const val HEVC_NAL_SPS = 33
        private const val H264_NAL_SPS = 7
        private const val H264_NAL_PPS = 8
        private const val KEY_CROP_LEFT = "crop-left"
        private const val KEY_CROP_RIGHT = "crop-right"
        private const val KEY_CROP_TOP = "crop-top"
        private const val KEY_CROP_BOTTOM = "crop-bottom"
        private val startCode = byteArrayOf(0, 0, 0, 1)

        private val VideoCodec.label: String get() = name.uppercase()

        private fun MediaFormat.integer(name: String): Int = if (containsKey(name)) getInteger(name) else 0

        private fun annexB(sets: List<ByteArray>): ByteBuffer {
            val buffer = ByteBuffer.allocate(sets.sumOf { startCode.size + it.size })
            for (set in sets) {
                buffer.put(startCode)
                buffer.put(set)
            }
            buffer.flip()
            return buffer
        }

        fun makeFormatDescription(format: VideoFormat): MediaFormat {
            val sets = format.parameterSets.filter { it.isNotEmpty() }
            if (sets.isEmpty()) throw MessageError("the ${format.codec.label} stream came without parameter sets")
            val sequence = SequenceParameterSet.parse(format.codec, sets)
            val width = sequence?.width?.takeIf { it > 0 } ?: DEFAULT_WIDTH
            val height = sequence?.height?.takeIf { it > 0 }
                ?: if (format.canvasHeight > 0) (format.canvasHeight + format.bands - 1) / format.bands else DEFAULT_HEIGHT
            val description = when (format.codec) {
                VideoCodec.Hevc -> MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, width, height).apply {
                    setByteBuffer("csd-0", annexB(sets))
                }
                VideoCodec.H264 -> {
                    val sequenceSets = sets.filter { it[0].toInt() and 0x1f == H264_NAL_SPS }
                    val pictureSets = sets.filter { it[0].toInt() and 0x1f == H264_NAL_PPS }
                    if (sequenceSets.isEmpty() || pictureSets.isEmpty()) {
                        throw MessageError("the ${format.codec.label} stream came without a sequence and a picture parameter set")
                    }
                    MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                        setByteBuffer("csd-0", annexB(sequenceSets))
                        setByteBuffer("csd-1", annexB(pictureSets))
                    }
                }
            }
            description.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, max(width * height * 3 / 2, MINIMUM_INPUT_SIZE))
            return description
        }

        fun makeSampleBuffer(payload: ByteArray): ByteArray? {
            if (payload.isEmpty()) return null
            val sample = payload.copyOf()
            var position = 0
            while (position < sample.size) {
                if (sample.size - position < 4) return null
                val length = ((sample[position].toInt() and 0xff) shl 24) or ((sample[position + 1].toInt() and 0xff) shl 16) or
                    ((sample[position + 2].toInt() and 0xff) shl 8) or (sample[position + 3].toInt() and 0xff)
                if (length <= 0 || length > sample.size - position - 4) return null
                startCode.copyInto(sample, position)
                position += 4 + length
            }
            return sample
        }

        private const val DEFAULT_WIDTH = 1920
        private const val DEFAULT_HEIGHT = 1080
        private const val MINIMUM_INPUT_SIZE = 64 * 1024
    }
}
