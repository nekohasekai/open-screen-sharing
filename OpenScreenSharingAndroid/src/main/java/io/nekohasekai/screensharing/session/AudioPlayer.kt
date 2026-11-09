package io.nekohasekai.screensharing.session

import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioRouting
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import io.nekohasekai.screensharing.App
import io.nekohasekai.screensharing.app.MessageError
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import android.media.AudioFormat as MediaAudioFormat

class AudioPlayer(
    format: AudioFormat,
    private val failed: (Throwable) -> Unit,
    private val stopped: () -> Unit,
) {
    private val channels = max(1, format.channels)
    private val outputChannels = if (channels == 1) 1 else 2
    private val packetFrames = max(1, format.framesPerPacket)
    private val sampleRate = format.sampleRate
    private val fadeFrames = AudioOutput.fadeFrames(format.sampleRate)
    private val name: String
    private val decoderFormat: MediaFormat?
    private val decoder: MediaCodec?
    private val ring = AudioRing(channels, (2 * format.sampleRate).toInt())
    private val output = AudioOutput(ring, format.sampleRate)
    private val audioManager = App.instance.getSystemService(AudioManager::class.java)
    private val thread: HandlerThread
    private val queue: Handler
    private val modeListener: Any?

    @Volatile
    private var playback: Playback? = null

    @Volatile
    private var started = false
    private var released = false
    private var foreground = false
    private var blocked = false
    private var previous: PcmBuffer? = null
    private var undecoded = 0
    private var origin: Double? = null
    private var lastTimestamp = 0L
    private var lastArrival = 0.0
    private var mediaFrames = 0L
    private var lastLag = 0.0
    private val lags = DoubleArray(LAG_WINDOW)
    private var lagCount = 0
    private var nextLag = 0
    private var earliestLag = 0.0
    private var unreviewed = 0
    private var target = MINIMUM_TARGET
    private var lastSound = Double.NEGATIVE_INFINITY
    private val quietCheck = Runnable { stopWhenQuiet() }

    private val info = MediaCodec.BufferInfo()
    private var decodedChannels = channels
    private var decodedFloat = false
    private var inputTime = 0L
    private var scratch = Array(channels) { FloatArray(packetFrames * 2) }

    init {
        val pcm = format.formatID == LINEAR_PCM
        name = "${format.sampleRate.toInt()} Hz ${format.channels}-channel ${if (pcm) "16-bit PCM" else "AAC-ELD"}"
        if (pcm) {
            decoderFormat = null
            decoder = null
        } else {
            val mediaFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, format.sampleRate.toInt(), channels)
            mediaFormat.setByteBuffer("csd-0", ByteBuffer.wrap(audioSpecificConfig(format.magicCookie)))
            decoderFormat = mediaFormat
            decoder = try {
                MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).also { codec ->
                    try {
                        codec.configure(mediaFormat, null, null, 0)
                        codec.start()
                    } catch (error: Exception) {
                        codec.release()
                        throw error
                    }
                }
            } catch (error: Exception) {
                throw MessageError("MediaCodec can't decode $name: ${error.message}")
            }
        }
        ring.target.set((MINIMUM_TARGET * format.sampleRate).toLong())
        thread = HandlerThread("AudioPlayer").apply { start() }
        queue = Handler(thread.looper)
        modeListener = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AudioManager.OnModeChangedListener { mode ->
                if (mode == AudioManager.MODE_NORMAL) resume() else interrupt()
            }.also { listener -> audioManager.addOnModeChangedListener(Executor(Runnable::run), listener) }
        } else {
            null
        }
    }

    fun stop() {
        queue.post {
            foreground = false
            halt()
            released = true
            decoder?.release()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (modeListener as? AudioManager.OnModeChangedListener)?.let { audioManager.removeOnModeChangedListener(it) }
            }
            thread.quitSafely()
        }
    }

    val isPlaying: Boolean
        get() = started && playback?.track?.playState == AudioTrack.PLAYSTATE_PLAYING

    fun setInBackground(background: Boolean) {
        queue.post {
            foreground = !background
            if (!background) {
                blocked = false
            }
        }
    }

    private fun play() {
        if (started) return
        try {
            val playback = makePlayback()
            this.playback = playback
            started = true
            stopWhenQuiet()
        } catch (error: Exception) {
            blocked = true
            failed(error)
            stopped()
        }
    }

    private fun makePlayback(): Playback {
        val rate = sampleRate.toInt()
        val mask = if (outputChannels == 1) MediaAudioFormat.CHANNEL_OUT_MONO else MediaAudioFormat.CHANNEL_OUT_STEREO
        val encoding = MediaAudioFormat.ENCODING_PCM_FLOAT
        val minimum = AudioTrack.getMinBufferSize(rate, mask, encoding)
        if (minimum <= 0) throw MessageError("AudioTrack rejected $name")
        val burst = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull()?.takeIf { it > 0 } ?: DEFAULT_BURST
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            @Suppress("DEPRECATION")
            attributes.setFlags(AudioAttributes.FLAG_LOW_LATENCY)
        }
        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes.build())
            .setAudioFormat(MediaAudioFormat.Builder().setEncoding(encoding).setSampleRate(rate).setChannelMask(mask).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .also {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) it.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            }
            .setBufferSizeInBytes(max(minimum, 4 * burst * outputChannels * Float.SIZE_BYTES))
            .build()
        try {
            if (track.state != AudioTrack.STATE_INITIALIZED) throw MessageError("AudioTrack can't play $name")
            track.setBufferSizeInFrames(min(2 * burst, track.bufferCapacityInFrames))
            return Playback(track, burst).also { it.start() }
        } catch (error: Exception) {
            track.release()
            throw error
        }
    }

    private fun restart() {
        if (!started) return
        playback?.stop()
        playback = null
        started = false
        ring.flushRequested.set(true)
        play()
    }

    private fun interrupt() {
        queue.post {
            blocked = true
            if (!started) return@post
            ring.flushRequested.set(true)
            halt()
        }
    }

    private fun resume() {
        queue.post { blocked = false }
    }

    private fun halt() {
        queue.removeCallbacks(quietCheck)
        if (!started) return
        playback?.stop()
        playback = null
        started = false
        stopped()
    }

    private fun writeFailed(playback: Playback, code: Int) {
        if (this.playback !== playback) return
        if (code == AudioTrack.ERROR_DEAD_OBJECT) {
            restart()
            return
        }
        blocked = true
        failed(MessageError("AudioTrack.write failed with $code"))
        halt()
    }

    private fun stopWhenQuiet() {
        val quiet = now() - lastSound
        if (quiet >= QUIET_LIMIT) {
            halt()
            return
        }
        queue.removeCallbacks(quietCheck)
        queue.postDelayed(quietCheck, ceil((QUIET_LIMIT - quiet) * 1000).toLong())
    }

    fun enqueue(unit: AudioAccessUnit) {
        val arrival = now()
        queue.post {
            if (released) return@post
            measure(arrival, unit.timestamp, unit.lost)
            val lost = unit.lost + undecoded
            val pcm = decode(unit.data)
            if (pcm == null) {
                undecoded = lost + 1
                return@post
            }
            undecoded = 0
            if (isAudible(pcm)) {
                lastSound = arrival
                if (foreground && !blocked) {
                    play()
                }
            }
            if (started) {
                if (lost > 0) {
                    conceal(lost, pcm)
                }
                write(pcm, pcm.frames)
            }
            previous = pcm
        }
    }

    private fun isAudible(buffer: PcmBuffer): Boolean {
        for (samples in buffer.data) {
            for (frame in 0 until buffer.frames) {
                if (abs(samples[frame]) > AUDIBLE_LEVEL) return true
            }
        }
        return false
    }

    private fun measure(arrival: Double, timestamp: Long, lost: Int) {
        val origin = origin
        if (origin == null) {
            restartMeasuring(arrival, timestamp)
            return
        }
        val counted = (lost + 1).toLong() * packetFrames
        val step = timestamp - lastTimestamp
        val pause = (step - counted).toDouble() / sampleRate
        val paused = pause > MINIMUM_PAUSE && pause < MAXIMUM_PAUSE && arrival - lastArrival > pause / 2
        lastTimestamp = timestamp
        lastArrival = arrival
        mediaFrames += if (paused) step else counted
        val lag = arrival - origin - mediaFrames.toDouble() / sampleRate
        if (abs(lag - lastLag) >= RESTART_JUMP) {
            restartMeasuring(arrival, timestamp)
            return
        }
        lastLag = lag
        lags[nextLag] = lag
        lagCount = min(lagCount + 1, LAG_WINDOW)
        nextLag = (nextLag + 1) % LAG_WINDOW
        earliestLag = min(earliestLag, lag)
        target = max(target, min(lag - earliestLag + MINIMUM_TARGET, MAXIMUM_TARGET))
        unreviewed += 1
        if (unreviewed >= REVIEW_INTERVAL) {
            unreviewed = 0
            var lowest = lags[0]
            var highest = lags[0]
            for (index in 1 until lagCount) {
                lowest = min(lowest, lags[index])
                highest = max(highest, lags[index])
            }
            earliestLag = lowest
            val wanted = min(highest - earliestLag + MINIMUM_TARGET, MAXIMUM_TARGET)
            val decay = TARGET_DECAY * (REVIEW_INTERVAL * packetFrames).toDouble() / sampleRate
            target = max(wanted, target - decay)
        }
        ring.target.set((target * sampleRate).toLong())
    }

    private fun restartMeasuring(arrival: Double, timestamp: Long) {
        origin = arrival
        lastTimestamp = timestamp
        lastArrival = arrival
        mediaFrames = 0
        lastLag = 0.0
        lagCount = 0
        nextLag = 0
        earliestLag = 0.0
        unreviewed = 0
    }

    private fun conceal(lost: Int, next: PcmBuffer) {
        val previous = previous ?: return
        val length = previous.frames
        if (length <= 1) return
        val lostFrames = lost * packetFrames
        val filled = if (lostFrames.toDouble() / sampleRate <= max(MAXIMUM_FILLED_LOSS, target)) lostFrames else 0
        val joined = min(fadeFrames, next.frames)
        val filler = PcmBuffer(Array(channels) { FloatArray(filled + joined) }, filled + joined)
        val last = length - 1
        val fading = (FADED_PACKETS * packetFrames).toFloat()
        for (channel in 0 until channels) {
            val source = previous.data[channel]
            val fill = filler.data[channel]
            val destination = next.data[channel]
            for (frame in 0 until filled + joined) {
                val turn = (frame + 1) % (2 * last)
                val index = if (turn <= last) last - turn else turn - last
                fill[frame] = source[index] * max(0f, 1 - frame / fading)
            }
            for (frame in 0 until joined) {
                val weight = (frame + 1).toFloat() / (joined + 1)
                destination[frame] = fill[filled + frame] * (1 - weight) + destination[frame] * weight
            }
        }
        if (filled > 0) {
            write(filler, filled)
        }
    }

    private fun write(buffer: PcmBuffer, frames: Int) {
        if (!ring.write(buffer.data, frames)) {
            ring.flushRequested.set(true)
        }
    }

    private fun decode(data: ByteArray): PcmBuffer? {
        if (data.isEmpty()) return null
        val codec = decoder ?: return decodePCM(data)
        return try {
            decodeCompressed(codec, data)
        } catch (error: IllegalStateException) {
            try {
                codec.reset()
                codec.configure(decoderFormat, null, null, 0)
                codec.start()
            } catch (resetError: Exception) {
            }
            null
        }
    }

    private fun decodePCM(data: ByteArray): PcmBuffer? {
        val frames = data.size / (2 * channels)
        if (frames == 0) return null
        val planar = Array(channels) { FloatArray(frames) }
        for (frame in 0 until frames) {
            for (channel in 0 until channels) {
                val offset = (frame * channels + channel) * 2
                val sample = (data[offset].toInt() and 0xff) or (data[offset + 1].toInt() shl 8)
                planar[channel][frame] = sample / 32768f
            }
        }
        return PcmBuffer(planar, frames)
    }

    private fun decodeCompressed(codec: MediaCodec, data: ByteArray): PcmBuffer? {
        val input = codec.dequeueInputBuffer(DECODER_TIMEOUT)
        if (input < 0) return null
        val buffer = codec.getInputBuffer(input) ?: return null
        buffer.clear()
        buffer.put(data)
        codec.queueInputBuffer(input, 0, data.size, inputTime, 0)
        inputTime += (packetFrames * 1_000_000.0 / sampleRate).toLong()
        var frames = 0
        var timeout = DECODER_TIMEOUT
        while (true) {
            val index = codec.dequeueOutputBuffer(info, timeout)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val outputFormat = codec.outputFormat
                    decodedChannels = max(1, outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                    decodedFloat = outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) == MediaAudioFormat.ENCODING_PCM_FLOAT
                }
                index >= 0 -> {
                    val decoded = codec.getOutputBuffer(index)
                    if (decoded != null && info.size > 0) {
                        frames = append(decoded, frames)
                        timeout = 0
                    }
                    codec.releaseOutputBuffer(index, false)
                }
            }
        }
        if (frames == 0) return null
        return PcmBuffer(Array(channels) { scratch[it].copyOf(frames) }, frames)
    }

    private fun append(buffer: ByteBuffer, start: Int): Int {
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        buffer.order(ByteOrder.nativeOrder())
        val frames = info.size / ((if (decodedFloat) Float.SIZE_BYTES else Short.SIZE_BYTES) * decodedChannels)
        if (scratch[0].size < start + frames) {
            scratch = Array(channels) { scratch[it].copyOf(max(start + frames, scratch[it].size * 2)) }
        }
        if (decodedFloat) {
            val samples = buffer.asFloatBuffer()
            for (frame in 0 until frames) {
                for (channel in 0 until channels) {
                    scratch[channel][start + frame] = samples.get(frame * decodedChannels + min(channel, decodedChannels - 1))
                }
            }
        } else {
            val samples = buffer.asShortBuffer()
            for (frame in 0 until frames) {
                for (channel in 0 until channels) {
                    scratch[channel][start + frame] = samples.get(frame * decodedChannels + min(channel, decodedChannels - 1)) / 32768f
                }
            }
        }
        return start + frames
    }

    private inner class Playback(val track: AudioTrack, private val burst: Int) {
        @Volatile
        private var running = true
        private var routedDevice: Int? = null
        private val renderer = Thread(::render, "AudioPlayer.render")
        private val routingListener = AudioRouting.OnRoutingChangedListener { router ->
            val device = router.routedDevice?.id ?: return@OnRoutingChangedListener
            val previous = routedDevice
            routedDevice = device
            if (previous != null && previous != device && playback === this@Playback) {
                restart()
            }
        }

        fun start() {
            track.addOnRoutingChangedListener(routingListener, queue)
            track.play()
            renderer.start()
        }

        fun stop() {
            running = false
            track.removeOnRoutingChangedListener(routingListener)
            track.pause()
            renderer.join()
            track.flush()
            track.release()
        }

        private fun render() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val samples = FloatArray(burst * outputChannels)
            var underruns = track.underrunCount
            while (running) {
                output.render(burst, samples, outputChannels)
                var offset = 0
                while (offset < samples.size) {
                    val written = track.write(samples, offset, samples.size - offset, AudioTrack.WRITE_BLOCKING)
                    if (!running) return
                    if (written < 0) {
                        queue.post { writeFailed(this@Playback, written) }
                        return
                    }
                    offset += written
                }
                val count = track.underrunCount
                if (count > underruns) {
                    underruns = count
                    track.setBufferSizeInFrames(min(track.bufferSizeInFrames + burst, track.bufferCapacityInFrames))
                }
            }
        }
    }

    private class PcmBuffer(val data: Array<FloatArray>, val frames: Int)

    companion object {
        private const val LINEAR_PCM = 0x6c70636dL
        private const val MINIMUM_TARGET = 0.015
        private const val MAXIMUM_TARGET = 0.25
        private const val LAG_WINDOW = 1500
        private const val REVIEW_INTERVAL = 25
        private const val TARGET_DECAY = 0.003
        private const val MAXIMUM_FILLED_LOSS = 0.11
        private const val FADED_PACKETS = 3
        private const val MINIMUM_PAUSE = 0.05
        private const val MAXIMUM_PAUSE = 600.0
        private const val RESTART_JUMP = 1.0
        private const val QUIET_LIMIT = 60.0
        private const val AUDIBLE_LEVEL = 1f / 32768
        private const val DECODER_TIMEOUT = 10_000L
        private const val DEFAULT_BURST = 256

        private fun now(): Double = System.nanoTime() / 1e9

        private fun audioSpecificConfig(cookie: ByteArray): ByteArray {
            var offset = 0
            var end = cookie.size
            while (offset < end) {
                val tag = cookie[offset++].toInt() and 0xff
                var length = 0
                for (index in 0 until 4) {
                    if (offset >= cookie.size) return cookie
                    val byte = cookie[offset++].toInt() and 0xff
                    length = (length shl 7) or (byte and 0x7f)
                    if (byte and 0x80 == 0) break
                }
                val body = offset
                if (body + length > cookie.size) return cookie
                when (tag) {
                    0x03 -> {
                        if (length < 3) return cookie
                        val flags = cookie[body + 2].toInt() and 0xff
                        offset = body + 3
                        if (flags and 0x80 != 0) offset += 2
                        if (flags and 0x40 != 0) offset += 1 + (cookie.getOrNull(offset)?.toInt()?.and(0xff) ?: return cookie)
                        if (flags and 0x20 != 0) offset += 2
                        end = body + length
                    }
                    0x04 -> {
                        offset = body + 13
                        end = body + length
                    }
                    0x05 -> return cookie.copyOfRange(body, body + length)
                    else -> offset = body + length
                }
            }
            return cookie
        }
    }
}

class AudioRing(channels: Int, frames: Int) {
    val channels = max(1, channels)
    private val capacity: Int
    private val mask: Long
    private val samples: FloatArray
    val written = AtomicLong(0)
    val read = AtomicLong(0)
    val target = AtomicLong(0)
    val flushRequested = AtomicBoolean(false)

    init {
        var capacity = 1
        while (capacity < frames) {
            capacity = capacity shl 1
        }
        this.capacity = capacity
        mask = (capacity - 1).toLong()
        samples = FloatArray(this.channels * capacity)
    }

    fun write(source: Array<FloatArray>, frames: Int): Boolean {
        if (frames <= 0 || source.isEmpty()) return true
        val end = written.get()
        if (end + frames - read.get() > capacity - HISTORY) return false
        for (channel in 0 until channels) {
            val from = source[min(channel, source.size - 1)]
            val base = channel * capacity
            var done = 0
            var offset = (end and mask).toInt()
            while (done < frames) {
                val count = min(frames - done, capacity - offset)
                System.arraycopy(from, done, samples, base + offset, count)
                done += count
                offset = 0
            }
        }
        written.set(end + frames)
        return true
    }

    fun sample(channel: Int, index: Long): Float = samples[channel * capacity + (index and mask).toInt()]

    companion object {
        private const val HISTORY = 4
    }
}

class AudioOutput(private val ring: AudioRing, private val sampleRate: Double) {
    private val fadeFrames = fadeFrames(sampleRate)
    private val skipFrames = SKIP_THRESHOLD * sampleRate
    private var position = 0L
    private var phase = 0.0
    private var playing = false
    private var fadeIn = 0
    private var smoothedSlack = 0.0
    private var adjusting = false

    fun render(count: Int, output: FloatArray, channels: Int): Boolean {
        if (ring.flushRequested.getAndSet(false)) {
            position = ring.written.get()
            phase = 0.0
            playing = false
            ring.read.set(position)
        }
        val written = ring.written.get()
        val target = ring.target.get().toDouble()
        val reserve = (fadeFrames + LOOKAHEAD).toDouble()
        var slack = (written - position).toDouble() - phase - count - reserve
        if (!playing) {
            if (slack < target) {
                silence(output, channels, 0, count)
                return true
            }
            if (slack - target > skipFrames) {
                val skipped = (slack - target).toLong()
                position += skipped
                slack -= skipped
            }
            playing = true
            fadeIn = fadeFrames
            smoothedSlack = slack
            adjusting = false
        }
        smoothedSlack += (slack - smoothedSlack) * min(1.0, count / (sampleRate * SLACK_SMOOTHING))
        var skip = 0
        if (smoothedSlack - target > skipFrames && slack - target > fadeFrames) {
            skip = (slack - target).toInt()
            smoothedSlack -= skip
        }
        val error = (smoothedSlack - target) / sampleRate
        adjusting = if (adjusting) abs(error) >= DRIFT_SETTLED else abs(error) > DRIFT_START
        val rate = if (adjusting) 1 + min(max(error * RATE_GAIN, -SLOWER_LIMIT), FASTER_LIMIT) else 1.0
        val playable = max(0, (((written - position - skip - LOOKAHEAD).toDouble() - phase) / rate).toInt())
        val runningDry = playable < count + fadeFrames
        val frames = min(count, playable)
        val fadeOut = if (runningDry) min(fadeFrames, frames) else 0
        val crossfade = if (skip > 0) min(fadeFrames, frames) else 0
        var index = position
        var fraction = phase
        if (skip > 0 && crossfade == 0) {
            index += skip
        }
        for (frame in 0 until frames) {
            var gain = 1f
            if (fadeIn > 0) {
                gain = 1 - fadeIn.toFloat() / fadeFrames
                fadeIn -= 1
            }
            if (frame >= frames - fadeOut) {
                gain *= (frames - frame).toFloat() / (fadeOut + 1)
            }
            val weight = if (frame < crossfade) (frame + 1).toFloat() / (crossfade + 1) else 0f
            for (channel in 0 until channels) {
                val source = min(channel, ring.channels - 1)
                var value = interpolate(source, index, fraction)
                if (frame < crossfade) {
                    value = value * (1 - weight) + interpolate(source, index + skip, fraction) * weight
                }
                output[frame * channels + channel] = value * gain
            }
            fraction += rate
            val whole = fraction.toInt()
            index += whole
            fraction -= whole
            if (frame + 1 == crossfade) {
                index += skip
            }
        }
        silence(output, channels, frames, count)
        position = index
        phase = fraction
        ring.read.set(position)
        if (runningDry) {
            playing = false
        }
        return frames == 0
    }

    private fun interpolate(channel: Int, index: Long, phase: Double): Float {
        val b = ring.sample(channel, index)
        if (phase <= 0) return b
        val a = ring.sample(channel, index - 1)
        val c = ring.sample(channel, index + 1)
        val d = ring.sample(channel, index + 2)
        val t = phase.toFloat()
        return b + 0.5f * t * (c - a + t * (2 * a - 5 * b + 4 * c - d + t * (3 * (b - c) + d - a)))
    }

    private fun silence(output: FloatArray, channels: Int, start: Int, end: Int) {
        if (start >= end) return
        output.fill(0f, start * channels, end * channels)
    }

    companion object {
        private const val FADE_DURATION = 0.005
        private const val LOOKAHEAD = 3
        private const val SLACK_SMOOTHING = 0.5
        private const val RATE_GAIN = 0.25
        private const val FASTER_LIMIT = 0.01
        private const val SLOWER_LIMIT = 0.005
        private const val DRIFT_START = 0.01
        private const val DRIFT_SETTLED = 0.002
        private const val SKIP_THRESHOLD = 0.08

        fun fadeFrames(sampleRate: Double): Int = max(1, Math.round(FADE_DURATION * sampleRate).toInt())
    }
}

object RemoteAudioSession {
    var recorders by mutableIntStateOf(0)
        private set

    fun beginRecording() {
        App.scope.launch { recorders += 1 }
    }

    fun endRecording() {
        App.scope.launch { recorders -= 1 }
    }
}
