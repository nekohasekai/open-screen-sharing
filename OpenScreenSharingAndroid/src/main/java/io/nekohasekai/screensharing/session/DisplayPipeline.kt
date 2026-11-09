package io.nekohasekai.screensharing.session

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

sealed interface ScreenPicture {
    data object None : ScreenPicture
    data class Previous(val change: PictureChange) : ScreenPicture
    data object Current : ScreenPicture
}

enum class PictureChange {
    Reconnection,
    Resize,
    VideoStart,
}

data class ShownPicture(
    val picture: ScreenPicture = ScreenPicture.None,
    val width: Int = 0,
    val height: Int = 0,
    val turns: PictureTurns = PictureTurns.None,
)

class DisplayPipeline {
    class Snapshot internal constructor(
        val pixelBuffer: VideoImage?,
        val framebuffer: Framebuffer?,
        val strips: List<VideoImage?>,
        val stripHeight: Int,
        val canvasHeight: Int,
        val width: Int,
        val height: Int,
        val cursor: CursorShape?,
        val cursorPosition: Offset,
        val cursorVisible: Boolean,
        val pictureGeneration: Int?,
        val presentationEpoch: Int,
        val turns: PictureTurns,
    ) {
        fun retain(): Snapshot {
            pixelBuffer?.retain()
            framebuffer?.retain()
            for (strip in strips) strip?.retain()
            return this
        }

        fun release() {
            pixelBuffer?.release()
            framebuffer?.release()
            for (strip in strips) strip?.release()
        }
    }

    private class Held(
        val pixelBuffer: VideoImage?,
        val framebuffer: Framebuffer?,
        val strips: List<VideoImage?>,
        val canvasHeight: Int,
        val width: Int,
        val height: Int,
        val turns: PictureTurns,
        val change: PictureChange,
    ) {
        fun release() {
            pixelBuffer?.release()
            framebuffer?.release()
            for (strip in strips) strip?.release()
        }
    }

    private class Scheduled(val frame: DecodedVideoFrame, val due: Long, val ready: (picture: Int) -> Unit)

    private class Copy(val framebuffer: Framebuffer, var stale: Rect?)

    private val videoContextHolder = lazy { VideoContext() }
    val videoContext: VideoContext by videoContextHolder

    private val lock = ReentrantLock()
    private val copyLock = ReentrantLock()
    private val copies = ArrayList<Copy>()
    private var textureSource: Framebuffer? = null
    private var textureMain: Framebuffer? = null
    private var textureStale: Rect? = null
    private var framebuffer: Framebuffer? = null
    private var presented: Framebuffer? = null
    private var framebufferUpdated = false
    private var framebufferHasPicture = false
    private var expectsVideo = false
    private var holdsVideo = false
    private var waitsForDisplay = false
    private var held: Held? = null
    private var videoFrame: VideoImage? = null
    private var strips: List<VideoImage?> = emptyList()
    private var canvasHeight = 0
    private var videoTurns = PictureTurns.None
    private var isVideo = false
    private var width = 0
    private var height = 0
    private var cursor: CursorShape? = null
    private var cursorPosition = Offset.Zero
    private var cursorVisible = true
    private var pictureGeneration = 0
    private var presentationEpoch = 0
    private var dirty = false
    private var wakeHandler: (() -> Unit)? = null
    private var shown = ShownPicture()
    private var shownHandler: (() -> Unit)? = null
    private var schedulesPresentation = false
    private val scheduled = ArrayList<Scheduled>()
    private var consumedThrough = 0L
    private var presentationLatency: Long? = null
    private val latencySamples = ArrayList<Long>()
    private var latencyWindowSince = 0L
    private var latencyWarmingUp = false

    fun setWakeHandler(handler: (() -> Unit)?) {
        lock.withLock { wakeHandler = handler }
    }

    fun setShownHandler(handler: (() -> Unit)?) {
        lock.withLock { shownHandler = handler }
    }

    val screenSize: IntSize get() = lock.withLock { IntSize(width, height) }

    val currentPicture: ShownPicture get() = lock.withLock { shown }

    fun isCurrentPresentation(epoch: Int): Boolean = lock.withLock { epoch == presentationEpoch }

    fun holdPicture() {
        update {
            presentationEpoch += 1
            resetPresentation()
            hold(PictureChange.Reconnection)
            framebufferUpdated = false
            framebufferHasPicture = false
            clearVideoFrame()
        }
    }

    fun attachFramebuffer(framebuffer: Framebuffer) {
        framebuffer.retain()
        copyLock.withLock {
            var previous: Framebuffer? = null
            update {
                presentationEpoch += 1
                resetPresentation()
                hold(PictureChange.Resize)
                previous = this.framebuffer
                this.framebuffer = framebuffer
                presented = null
                framebufferUpdated = false
                framebufferHasPicture = false
                isVideo = false
                clearVideoFrame()
                width = framebuffer.width
                height = framebuffer.height
            }
            for (copy in copies) copy.framebuffer.release()
            copies.clear()
            previous?.release()
        }
    }

    fun beginVideo() {
        update {
            presentationEpoch += 1
            resetPresentation()
            hold(PictureChange.VideoStart)
            isVideo = true
            clearVideoFrame()
            videoTurns = PictureTurns.None
        }
    }

    fun endVideo() {
        update {
            presentationEpoch += 1
            resetPresentation()
            hold(PictureChange.Reconnection)
            isVideo = false
            clearVideoFrame()
            framebufferUpdated = false
            framebufferHasPicture = false
            framebuffer?.let {
                width = it.width
                height = it.height
            }
        }
    }

    val isSchedulingPresentation: Boolean get() = lock.withLock { isVideo && schedulesPresentation }

    fun setSchedulesPresentation(schedules: Boolean) {
        lock.withLock {
            schedulesPresentation = schedules
            resetPresentation()
        }
    }

    fun setVideoFrame(frame: DecodedVideoFrame, ready: (picture: Int) -> Unit) {
        val now = System.nanoTime()
        val handler: (() -> Unit)?
        val horizon: Long
        lock.withLock {
            if (!isVideo) {
                frame.release()
                return
            }
            val due = if (schedulesPresentation) presentationDue(frame.timing.capture, now) else now
            scheduled.add(Scheduled(frame, due, ready))
            handler = wakeHandler
            horizon = max(now, consumedThrough)
        }
        if (!promote(horizon)) {
            handler?.invoke()
        }
    }

    private fun presentationDue(capture: Long, now: Long): Long {
        val offset = now - capture
        val latency = presentationLatency
        if (latency != null && abs(offset - latency) <= MAX_PRESENTATION_SKEW) {
            latencySamples.add(offset)
            val windowEnded = now - latencyWindowSince >= PRESENTATION_WINDOW
            if (windowEnded || latencyWarmingUp) {
                latencySamples.sort()
                presentationLatency = latencySamples[((latencySamples.size - 1) * PRESENTATION_QUANTILE).toInt()] + PRESENTATION_ALLOWANCE
            }
            if (windowEnded) {
                latencySamples.clear()
                latencyWindowSince = now
                latencyWarmingUp = false
            }
        } else {
            presentationLatency = offset
            latencySamples.clear()
            latencyWindowSince = now
            latencyWarmingUp = true
        }
        return capture + (presentationLatency ?: offset)
    }

    private fun resetPresentation() {
        for (item in scheduled) item.frame.release()
        scheduled.clear()
        presentationLatency = null
        latencySamples.clear()
    }

    private fun clearVideoFrame() {
        videoFrame?.release()
        videoFrame = null
        for (strip in strips) strip?.release()
        strips = emptyList()
    }

    private fun dueCount(time: Long): Int = max(scheduled.takeWhile { it.due <= time }.size, scheduled.size - MAX_SCHEDULED)

    private fun promote(time: Long): Boolean {
        val pending = lock.withLock { dueCount(time) }
        if (pending <= 0) return false
        val promoted = ArrayList<Pair<Scheduled, Int>>()
        update {
            val count = dueCount(time)
            if (count <= 0) return@update
            val items = scheduled.subList(0, count).toList()
            for (item in items) {
                pictureGeneration += 1
                promoted.add(item to pictureGeneration)
            }
            scheduled.subList(0, count).clear()
            val frame = items.last().frame
            for (item in items) {
                if (item !== items.last()) item.frame.release()
            }
            clearVideoFrame()
            videoFrame = frame.pixelBuffer
            strips = frame.strips
            canvasHeight = frame.canvasHeight
            videoTurns = frame.turns
        }
        for ((item, picture) in promoted) {
            item.ready(picture)
        }
        return promoted.isNotEmpty()
    }

    fun updateFramebuffer(dirty: Rect): Int? {
        copyLock.withLock {
            for (copy in copies) {
                copy.stale = union(copy.stale, dirty)
            }
            textureStale = union(textureStale, dirty)
            val (source, current, kept) = lock.withLock { Triple(if (isVideo) null else framebuffer, presented, held?.framebuffer) }
            source ?: return null
            var target = copies.firstOrNull { it.framebuffer !== current && it.framebuffer !== kept }
            if (target == null) {
                target = Copy(Framebuffer(source.width, source.height), source.bounds)
                copies.add(target)
            }
            val picture = target.framebuffer
            target.stale?.let { picture.copy(it, source) }
            target.stale = null
            var taken: Int? = null
            update {
                presented = picture
                if (isVideo) return@update
                framebufferUpdated = true
                if (!framebufferHasPicture && expectsVideo) return@update
                pictureGeneration += 1
                taken = pictureGeneration
                framebufferHasPicture = true
            }
            val (newPresented, newKept) = lock.withLock { presented to held?.framebuffer }
            var spares = 0
            for (index in copies.indices.reversed()) {
                val candidate = copies[index].framebuffer
                if (candidate === newPresented || candidate === newKept) continue
                spares += 1
                if (spares > 1) copies.removeAt(index).framebuffer.release()
            }
            return taken
        }
    }

    internal fun uploadFramebuffer(picture: Framebuffer, textureCurrent: Boolean, upload: (Rect) -> Unit) {
        copyLock.withLock {
            val copy = copies.firstOrNull { it.framebuffer === picture }
            val region = when {
                !textureCurrent -> picture.bounds
                copy != null && textureMain != null && textureMain === framebuffer -> union(textureStale, copy.stale)
                copy == null && textureSource === picture -> null
                else -> picture.bounds
            }
            region?.intersect(picture.bounds)?.takeUnless { it.isEmpty }?.let(upload)
            textureSource = picture
            if (copy != null) {
                textureMain = framebuffer
                textureStale = copy.stale
            } else {
                textureMain = null
                textureStale = null
            }
        }
    }

    fun setExpectsVideo(expects: Boolean) {
        update {
            expectsVideo = expects
            if (expects || isVideo || !framebufferUpdated || framebufferHasPicture) return@update
            pictureGeneration += 1
            framebufferHasPicture = true
        }
    }

    fun setHoldsVideo(holds: Boolean) {
        update { holdsVideo = holds }
    }

    fun setWaitsForDisplay(waits: Boolean) {
        update {
            if (waits) {
                hold(PictureChange.Resize)
            }
            waitsForDisplay = waits
        }
    }

    fun setCursor(cursor: CursorShape?) {
        update { this.cursor = cursor }
    }

    fun setCursorPosition(position: Offset, visible: Boolean) {
        update {
            cursorPosition = position
            cursorVisible = visible
        }
    }

    fun consume(time: Long): Snapshot? {
        promote(time)
        lock.withLock {
            consumedThrough = max(consumedThrough, time)
            if (!dirty) return null
            dirty = false
            return snapshotLocked()
        }
    }

    fun snapshot(): Snapshot = lock.withLock { snapshotLocked() }

    suspend fun makeThumbnail(): ByteArray? {
        val snapshot = lock.withLock { if (shownLocked().picture == ScreenPicture.None) null else snapshotLocked() } ?: return null
        try {
            val framebuffer = snapshot.framebuffer
            if (framebuffer != null) {
                val image = copyLock.withLock { framebuffer.bitmap() }
                return withContext(Dispatchers.Default) { PixelBufferImage.makeThumbnail(image, snapshot.turns, bgra = true) }
            }
            val image = videoContext.compose(snapshot.pixelBuffer, snapshot.strips, snapshot.stripHeight, snapshot.canvasHeight, snapshot.turns) ?: return null
            return withContext(Dispatchers.Default) { PixelBufferImage.makeThumbnail(image, snapshot.turns) }
        } finally {
            snapshot.release()
        }
    }

    fun close() {
        copyLock.withLock {
            lock.withLock {
                resetPresentation()
                held?.release()
                held = null
                clearVideoFrame()
                framebuffer?.release()
                framebuffer = null
                presented = null
                wakeHandler = null
                shownHandler = null
            }
            for (copy in copies) copy.framebuffer.release()
            copies.clear()
        }
        if (videoContextHolder.isInitialized()) videoContext.close()
    }

    private val hasPicture: Boolean
        get() {
            if (waitsForDisplay) return false
            return if (isVideo) {
                !holdsVideo && (videoFrame != null || (strips.isNotEmpty() && strips.all { it != null }))
            } else {
                framebufferHasPicture
            }
        }

    private val turns: PictureTurns get() = if (isVideo) videoTurns else PictureTurns.None

    private fun hold(change: PictureChange) {
        if (!hasPicture) return
        val picture = Held(
            pixelBuffer = if (isVideo) videoFrame?.retain() else null,
            framebuffer = if (isVideo) null else presented?.retain(),
            strips = if (isVideo) strips.map { it?.retain() } else emptyList(),
            canvasHeight = canvasHeight,
            width = width,
            height = height,
            turns = turns,
            change = change,
        )
        held?.release()
        held = picture
    }

    private fun shownLocked(): ShownPicture {
        val held = held
        return when {
            hasPicture -> ShownPicture(ScreenPicture.Current, width, height, turns)
            held != null -> ShownPicture(ScreenPicture.Previous(held.change), held.width, held.height, held.turns)
            else -> ShownPicture(ScreenPicture.None, width, height, turns)
        }
    }

    private fun snapshotLocked(): Snapshot {
        val held = held
        if (!hasPicture && held != null) {
            return Snapshot(
                pixelBuffer = held.pixelBuffer?.retain(),
                framebuffer = held.framebuffer?.retain(),
                strips = held.strips.map { it?.retain() },
                stripHeight = stripHeight(held.strips),
                canvasHeight = held.canvasHeight,
                width = held.width,
                height = held.height,
                cursor = cursor,
                cursorPosition = cursorPosition,
                cursorVisible = cursorVisible,
                pictureGeneration = null,
                presentationEpoch = presentationEpoch,
                turns = held.turns,
            )
        }
        if (!hasPicture) {
            return Snapshot(
                pixelBuffer = null,
                framebuffer = null,
                strips = emptyList(),
                stripHeight = 0,
                canvasHeight = 0,
                width = width,
                height = height,
                cursor = cursor,
                cursorPosition = cursorPosition,
                cursorVisible = cursorVisible,
                pictureGeneration = null,
                presentationEpoch = presentationEpoch,
                turns = turns,
            )
        }
        return Snapshot(
            pixelBuffer = if (isVideo) videoFrame?.retain() else null,
            framebuffer = if (isVideo) null else presented?.retain(),
            strips = if (isVideo) strips.map { it?.retain() } else emptyList(),
            stripHeight = stripHeight(strips),
            canvasHeight = canvasHeight,
            width = width,
            height = height,
            cursor = cursor,
            cursorPosition = cursorPosition,
            cursorVisible = cursorVisible,
            pictureGeneration = pictureGeneration,
            presentationEpoch = presentationEpoch,
            turns = turns,
        )
    }

    private fun update(body: () -> Unit) {
        val handler: (() -> Unit)?
        val shownHandler: (() -> Unit)?
        val wasDirty: Boolean
        val shownChanged: Boolean
        lock.withLock {
            body()
            if (hasPicture) {
                held?.release()
                held = null
            }
            wasDirty = dirty
            dirty = true
            handler = wakeHandler
            val newShown = shownLocked()
            shownChanged = newShown != shown
            shown = newShown
            shownHandler = this.shownHandler
        }
        if (!wasDirty) {
            handler?.invoke()
        }
        if (shownChanged) {
            shownHandler?.invoke()
        }
    }

    private companion object {
        const val PRESENTATION_WINDOW = 2_000_000_000L
        const val PRESENTATION_QUANTILE = 0.9
        const val PRESENTATION_ALLOWANCE = 1_000_000L
        const val MAX_PRESENTATION_SKEW = 500_000_000L
        const val MAX_SCHEDULED = 4

        fun stripHeight(strips: List<VideoImage?>): Int = strips.firstNotNullOfOrNull { it }?.height ?: 0

        fun union(first: Rect?, second: Rect?): Rect? = when {
            first == null || first.isEmpty -> second?.takeUnless { it.isEmpty }
            second == null || second.isEmpty -> first
            else -> Rect(min(first.left, second.left), min(first.top, second.top), max(first.right, second.right), max(first.bottom, second.bottom))
        }
    }
}
