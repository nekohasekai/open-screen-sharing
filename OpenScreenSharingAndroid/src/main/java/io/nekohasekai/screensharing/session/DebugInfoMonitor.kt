package io.nekohasekai.screensharing.session

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

class DebugInfoMonitor {
    data class Figures(
        val readyPerSecond: Double?,
        val shownPerSecond: Double?,
        val bitsPerSecond: Double?,
        val encoding: String?,
        val networkMilliseconds: Double?,
        val remoteMilliseconds: Double?,
        val deviceMilliseconds: Double?,
        val lossy: Boolean,
        val loss: Double?,
    )

    data class Span(
        val lateness: Double? = null,
        val stutters: Int = 0,
        val seconds: Int = 0,
    )

    private class Ready(val picture: Int, val arrival: Long, val capture: Long?, val composed: Long?)

    private class Shown(val capture: Long, val presentation: Long)

    var figures by mutableStateOf<Figures?>(null)
        private set
    var timeline by mutableStateOf<List<Span>>(emptyList())
        private set

    val stutters: Int get() = timeline.sumOf { it.stutters }

    val isRunning = AtomicBoolean(false)
    private var run = 0
    private var startedAt = 0L
    private var lastPicture: Int? = null
    private var lastPresentation: Long? = null
    private var lastShown: Shown? = null
    private var waiting = ArrayDeque<Ready>()
    private var ready = 0
    private var shown = 0
    private val delays = ArrayList<Double>()
    private val remoteDelays = ArrayList<Double>()
    private var lateness: Double? = null
    private var hitches = 0
    private var remoteMilliseconds: Double? = null
    private var deviceMilliseconds: Double? = null
    private var windowStart = 0L
    private var previous: SessionStatistics? = null

    val presentationRun: Int? get() = if (isRunning.get()) run else null

    fun start() {
        isRunning.set(true)
        run += 1
        startedAt = System.nanoTime()
        windowStart = startedAt
        figures = null
        timeline = emptyList()
        lastPicture = null
        lastPresentation = null
        lastShown = null
        waiting = ArrayDeque()
        ready = 0
        shown = 0
        delays.clear()
        remoteDelays.clear()
        lateness = null
        hitches = 0
        remoteMilliseconds = null
        deviceMilliseconds = null
        previous = null
    }

    fun stop() {
        isRunning.set(false)
        run += 1
        waiting = ArrayDeque()
    }

    fun pictureReady(picture: Int, arrival: Long, capture: Long?, composed: Long?) {
        if (!isRunning.get()) return
        lastPicture?.let { if (picture <= it) return }
        ready += 1
        if (waiting.size == MAX_WAITING) {
            waiting.removeFirst()
        }
        waiting.addLast(Ready(picture, arrival, capture, composed))
    }

    fun picturePresented(picture: Int, time: Long, run: Int) {
        if (!isRunning.get() || run != this.run || time <= 0 || time < startedAt) return
        lastPicture?.let { if (picture <= it) return }
        lastPresentation?.let { if (time <= it) return }
        lastPicture = picture
        lastPresentation = time
        shown += 1
        val kept = ArrayDeque<Ready>()
        var newest: Ready? = null
        var late = 0.0
        for (waited in waiting) {
            if (waited.picture > picture) {
                kept.addLast(waited)
                continue
            }
            val delay = (time - waited.arrival) / NANOSECONDS
            delays.add(delay)
            waited.composed?.let { remoteDelays.add((waited.arrival - it) / NANOSECONDS) }
            late = max(late, delay)
            newest = waited
        }
        waiting = kept
        if (newest == null) return
        val capture = newest.capture
        if (capture != null) {
            val last = lastShown
            lastShown = Shown(capture, time)
            if (last == null || capture < last.capture) return
            late = max((time - last.presentation - (capture - last.capture)) / NANOSECONDS, 0.0)
        } else {
            lastShown = null
        }
        lateness = max(lateness ?: 0.0, late)
        if (late > STUTTER) {
            hitches += 1
        }
    }

    fun sample(statistics: SessionStatistics?) {
        val now = System.nanoTime()
        try {
            if (statistics == null) {
                figures = null
                return
            }
            val elapsed = (now - windowStart) / NANOSECONDS
            val earlier = previous
            val measured = earlier != null && elapsed > 0
            var bitsPerSecond: Double? = null
            if (earlier != null && measured) {
                val added = if (statistics.receivedBytes >= earlier.receivedBytes) statistics.receivedBytes - earlier.receivedBytes else statistics.receivedBytes
                bitsPerSecond = added.toDouble() * 8 / elapsed
            }
            var loss: Double? = null
            val packets = statistics.packets
            if (packets != null) {
                var expected = packets.expected
                var received = packets.received
                val earlierPackets = earlier?.packets
                if (earlierPackets != null && expected >= earlierPackets.expected && received >= earlierPackets.received) {
                    expected -= earlierPackets.expected
                    received -= earlierPackets.received
                }
                loss = if (expected > 0) max(expected - received, 0L).toDouble() / expected.toDouble() else null
            }
            var losses = 0
            val stream = statistics.video
            val earlierStream = earlier?.video
            if (stream != null && earlierStream != null && stream.losses > earlierStream.losses) {
                losses = (stream.losses - earlierStream.losses).toInt()
            }
            if (delays.isNotEmpty()) {
                deviceMilliseconds = delays.average() * 1000
            }
            if (remoteDelays.isNotEmpty()) {
                remoteMilliseconds = remoteDelays.average() * 1000
            }
            figures = Figures(
                readyPerSecond = if (measured) ready / elapsed else null,
                shownPerSecond = if (measured) shown / elapsed else null,
                bitsPerSecond = bitsPerSecond,
                encoding = statistics.encoding,
                networkMilliseconds = statistics.roundTripMilliseconds?.let { it / 2 },
                remoteMilliseconds = remoteMilliseconds,
                deviceMilliseconds = deviceMilliseconds,
                lossy = packets != null,
                loss = loss,
            )
            if (!measured) return
            val spans = timeline.toMutableList()
            if (spans.lastOrNull()?.let { it.seconds >= SPAN_SECONDS } != false) {
                if (spans.size == SPANS) {
                    spans.removeAt(0)
                }
                spans.add(Span())
            }
            val index = spans.lastIndex
            val span = spans[index]
            val windowLateness = lateness
            spans[index] = span.copy(
                seconds = span.seconds + 1,
                stutters = span.stutters + max(hitches, losses),
                lateness = if (windowLateness != null) max(span.lateness ?: 0.0, windowLateness) else span.lateness,
            )
            timeline = spans
        } finally {
            previous = statistics
            windowStart = now
            ready = 0
            shown = 0
            delays.clear()
            remoteDelays.clear()
            lateness = null
            hitches = 0
        }
    }

    companion object {
        const val STUTTER = 0.05
        const val SPANS = 60
        const val SPAN_SECONDS = 5
        private const val MAX_WAITING = 64
        private const val NANOSECONDS = 1_000_000_000.0
    }
}
