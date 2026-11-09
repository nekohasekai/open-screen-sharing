package io.nekohasekai.screensharing.session

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import io.nekohasekai.screensharing.app.MessageError
import io.nekohasekai.screensharing.app.RuntimePermission
import kotlin.math.max
import android.media.AudioFormat as MediaAudioFormat

class MicrophoneSender(
    val format: AudioFormat,
    private val send: (ByteArray) -> Unit,
    private val failed: (MicrophoneSender, Throwable) -> Unit,
) {
    private val sampleRate = format.sampleRate.toInt()
    private val channels = max(1, format.channels)
    private val thread: HandlerThread
    private val queue: Handler
    private var running = false
    private var capture: Capture? = null

    init {
        if (AudioRecord.getMinBufferSize(sampleRate, MediaAudioFormat.CHANNEL_IN_MONO, MediaAudioFormat.ENCODING_PCM_16BIT) <= 0) {
            throw MessageError("AudioRecord rejected $sampleRate Hz mono")
        }
        thread = HandlerThread("MicrophoneSender").apply { start() }
        queue = Handler(thread.looper)
    }

    fun start() {
        queue.post {
            try {
                RemoteAudioSession.beginRecording()
                try {
                    capture = startRecording()
                } catch (error: Exception) {
                    RemoteAudioSession.endRecording()
                    throw error
                }
                running = true
            } catch (error: Exception) {
                failed(this@MicrophoneSender, error)
            }
        }
    }

    fun stop() {
        queue.post {
            if (running) {
                running = false
                capture?.stop()
                capture = null
                RemoteAudioSession.endRecording()
            }
            thread.quitSafely()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startRecording(): Capture {
        val chunk = max(1, sampleRate / CHUNKS_PER_SECOND)
        val minimum = AudioRecord.getMinBufferSize(sampleRate, MediaAudioFormat.CHANNEL_IN_MONO, MediaAudioFormat.ENCODING_PCM_16BIT)
        val record = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(
                    MediaAudioFormat.Builder()
                        .setEncoding(MediaAudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(MediaAudioFormat.CHANNEL_IN_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(max(minimum, 4 * chunk * Short.SIZE_BYTES))
                .build()
        } catch (error: UnsupportedOperationException) {
            throw MessageError("AudioRecord can't record $sampleRate Hz mono")
        }
        val effects = mutableListOf<AudioEffect>()
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler.create(record.audioSessionId)?.let { effects.add(it) }
            }
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor.create(record.audioSessionId)?.let { effects.add(it) }
            }
            for (effect in effects) {
                effect.setEnabled(true)
            }
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw MessageError("AudioRecord couldn’t start recording")
            }
            return Capture(record, effects, chunk).also { it.start() }
        } catch (error: Exception) {
            record.stop()
            effects.forEach(AudioEffect::release)
            record.release()
            throw error
        }
    }

    private inner class Capture(val record: AudioRecord, val effects: List<AudioEffect>, private val chunk: Int) {
        @Volatile
        private var active = true
        private val reader = Thread(::read, "MicrophoneSender.read")

        fun start() {
            reader.start()
        }

        fun stop() {
            active = false
            record.stop()
            reader.join()
            effects.forEach(AudioEffect::release)
            record.release()
        }

        private fun read() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val samples = ShortArray(chunk)
            while (active) {
                val count = record.read(samples, 0, chunk, AudioRecord.READ_BLOCKING)
                if (!active) return
                if (count < 0) {
                    queue.post {
                        if (capture === this@Capture) {
                            failed(this@MicrophoneSender, MessageError("AudioRecord.read failed with $count"))
                        }
                    }
                    return
                }
                if (count == 0) continue
                val pcm = ByteArray(count * channels * Short.SIZE_BYTES)
                for (frame in 0 until count) {
                    val sample = samples[frame].toInt()
                    for (channel in 0 until channels) {
                        val offset = (frame * channels + channel) * Short.SIZE_BYTES
                        pcm[offset] = sample.toByte()
                        pcm[offset + 1] = (sample shr 8).toByte()
                    }
                }
                send(pcm)
            }
        }
    }

    companion object {
        private const val CHUNKS_PER_SECOND = 50

        val permission = RuntimePermission(Manifest.permission.RECORD_AUDIO)
    }
}
