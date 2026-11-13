package io.nekohasekai.screensharing.sessionui

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.core.view.ViewConfigurationCompat
import io.nekohasekai.screensharing.model.AppSettings
import io.nekohasekai.screensharing.model.CursorStyle
import io.nekohasekai.screensharing.session.CursorShape
import io.nekohasekai.screensharing.session.CursorThemeStore
import io.nekohasekai.screensharing.session.GesturePhase
import io.nekohasekai.screensharing.session.PointerButtons
import io.nekohasekai.screensharing.session.RemoteTouch
import io.nekohasekai.screensharing.session.ScaleMode
import io.nekohasekai.screensharing.session.SessionController
import io.nekohasekai.screensharing.session.TouchPhase
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.truncate

class ScreenInput(
    private val view: RemoteScreenView,
    private val controller: SessionController,
) {
    private enum class TouchGesture { SecondaryTap, Pan, Pinch, Rotation }

    private inner class Finger(val id: Int, val start: Offset, val startTime: Long, val startPointer: Offset) {
        var last = start
        var phase = Phase.Pending
        var travel = 0f
        private val samples = ArrayDeque<Pair<Offset, Long>>().apply { addLast(start to startTime) }

        fun record(location: Offset, time: Long) {
            travel = max(travel, (location - start).getDistance())
            last = location
            samples.addLast(location to time)
            samples.removeAll { time - it.second > VELOCITY_WINDOW }
        }

        fun velocity(time: Long): Offset {
            val (oldest, oldestTime) = samples.firstOrNull() ?: return Offset.Zero
            val (newest, newestTime) = samples.last()
            if (time - newestTime >= VELOCITY_STALE || newestTime - oldestTime <= VELOCITY_MINIMUM_SPAN) return Offset.Zero
            return (newest - oldest) / ((newestTime - oldestTime) / 1000f)
        }

        fun isTap(time: Long): Boolean = travel <= tapSlop && time - startTime <= TAP_DURATION
    }

    private enum class Phase { Pending, Pointing, Dragging }

    private class TwoFinger(val ids: IntArray, val starts: Array<Offset>, val startTime: Long) {
        var state = State.Possible
        var lifted = 0
        var tapFailed = false
        var pinches = false
        var rotates = false
        val startCentroid = (starts[0] + starts[1]) / 2f
        val startDistance = (starts[1] - starts[0]).getDistance()
        val startAngle = atan2(starts[1].y - starts[0].y, starts[1].x - starts[0].x)

        enum class State { Possible, Pan, Transform, Ended }
    }

    private class ScrollStream(val usesPhases: Boolean = false) {
        var started = false
        var remainder = Offset.Zero
    }

    private class ZoomTap(val start: Offset, val time: Long, var travel: Float)

    private class PadGesture(val start: Offset) {
        val pointers = LinkedHashSet<Int>()
        var multiFinger = false
        var scrolls = false
        var pinches = false
        var pinchDistance = 0f
        var pinchScale = 1f
    }

    private data class PointerIconKey(val image: Any, val hotspot: Offset, val shapeScale: Float, val scale: Float)

    private val density = view.resources.displayMetrics.density
    private val configuration = ViewConfiguration.get(view.context)
    private val tapSlop = TAP_SLOP * density
    private val touchMargin = TOUCH_MARGIN * density
    private val touchSlop = configuration.scaledTouchSlop.toFloat()
    private val spanSlop = touchSlop * 2
    private val handler = Handler(Looper.getMainLooper())
    private val drag = ScreenDrag(view, controller, this)
    val drop = ScreenDrop(view, controller, drag)
    private val pressIndicator = PressIndicatorView(view.context)
    private val windowLocation = IntArray(2)
    private val nullIcon by lazy { PointerIcon.getSystemIcon(view.context, PointerIcon.TYPE_NULL) }
    private val pointerLockReceiver = Runnable { updatePointerCapture() }
    private val windowFocusListener = ViewTreeObserver.OnWindowFocusChangeListener { updatePointerCapture() }
    private var observedTree: ViewTreeObserver? = null
    private var detached = false

    private val locations = HashMap<Int, Offset>()
    private var velocityTracker: VelocityTracker? = null
    private val fingers = HashSet<Int>()
    private var finger: Finger? = null
        set(value) {
            field = value
            if (value == null) pressIndicator.dismiss()
        }
    private var holdTask: Runnable? = null
    private val nativeTouches = HashMap<Int, Long>()
    private var nextNativeTouchID = 0L
    private var twoFinger: TwoFinger? = null
    private var twoFingerUsed = false
    private val claimedTouches = HashSet<Int>()

    private val pointerTouches = HashSet<Int>()
    private var lockedButtons = PointerButtons.None
    private var handleTouch: Int? = null
    private val toolbarTouches = HashSet<Int>()
    private var systemPointerLocation: Offset? = null
    private var systemPointerOverPicture = false
        set(value) {
            if (value == field) return
            field = value
            view.surface.setNeedsRender()
        }
    private var pointerIcon: PointerIcon? = null
    private var pointerIconKey: Any? = null
    private var locksPointer = false
    private var lastLockedMoveTime = 0L
    private var lockedMouseSpeed = 0f
    private val padTouches = HashMap<Int, Offset>()
    private var padTracker: VelocityTracker? = null
    private var padScrolls = false
    private var padTranslation = Offset.Zero
    private var pad: PadGesture? = null

    private var twoFingerScroll = ScrollStream()
    private var twoFingerPansView = false
    private var twoFingerTranslation = Offset.Zero
    private var hardwareScroll = ScrollStream()
    private var hardwareScrollPansView = false
    private var hardwareScrollTranslation = Offset.Zero
    private var swipeTracker: VelocityTracker? = null
    private var swipeStart: Offset? = null
    private var swipeScrolls = false
    private val wheelScroll = ScrollStream()

    private val zoomTouches = LinkedHashMap<Int, Offset>()
    private var zoomTap: ZoomTap? = null
    private var lastZoomTap: Pair<Offset, Long>? = null

    private var pinchGoesToMac = false
    private var macPinchStarted = false
    private var previousPinchScale = 1f
    private var pinchStartZoom = 1f
    private var pinchLocation = Offset.Zero
    private var pinchAnchor = Offset.Zero
    private var macRotationStarted = false
    private var previousRotation = 0f
    private var touchpadPinchState: Boolean? = null
    private var touchpadPinchScale = 1f

    init {
        view.addView(pressIndicator)
        view.surface.delegate = object : RemoteScreenSurface.Delegate {
            override fun cursorDidRender(rect: Rect?) {
                pressIndicator.center = rect?.center ?: view.surface.viewPoint(controller.pointerPosition)
            }
        }
        view.addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = windowDidChange()

                override fun onViewDetachedFromWindow(v: View) = windowDidChange()
            },
        )
    }

    private val isPointerLocked: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && view.hasPointerCapture()

    fun windowDidChange() {
        if (detached) return
        val attached = view.isAttachedToWindow
        if (attached && observedTree == null) {
            observedTree = view.viewTreeObserver.also { it.addOnWindowFocusChangeListener(windowFocusListener) }
            PointerLock.addReceiver(pointerLockReceiver)
        } else if (!attached && observedTree != null) {
            observedTree?.takeIf { it.isAlive }?.removeOnWindowFocusChangeListener(windowFocusListener)
            observedTree = null
            PointerLock.removeReceiver(pointerLockReceiver)
            cancelHold()
            finger = null
        }
        updatePointerCapture()
    }

    fun detach() {
        detached = true
        observedTree?.takeIf { it.isAlive }?.removeOnWindowFocusChangeListener(windowFocusListener)
        observedTree = null
        PointerLock.removeReceiver(pointerLockReceiver)
        cancelHold()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && view.hasPointerCapture()) view.releasePointerCapture()
        for (tracker in listOfNotNull(velocityTracker, padTracker, swipeTracker)) tracker.recycle()
        velocityTracker = null
        padTracker = null
        swipeTracker = null
    }

    fun setLocksPointer(locks: Boolean) {
        if (locks == locksPointer) return
        locksPointer = locks
        updatePointerCapture()
    }

    private fun updatePointerCapture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val wants = locksPointer && !detached && PointerLock.hasMouse && view.isAttachedToWindow && view.hasWindowFocus()
        if (wants == view.hasPointerCapture()) return
        if (wants) {
            view.takeKeyFocus()
            view.requestPointerCapture()
        } else {
            view.releasePointerCapture()
        }
    }

    fun onTouchEvent(event: MotionEvent): Boolean {
        if (Build.VERSION.SDK_INT >= 34) {
            when (event.classification) {
                MotionEvent.CLASSIFICATION_TWO_FINGER_SWIPE -> {
                    touchpadSwiped(event)
                    return true
                }
                MotionEvent.CLASSIFICATION_PINCH -> {
                    touchpadPinched(event)
                    return true
                }
                CLASSIFICATION_MULTI_FINGER_SWIPE -> return true
            }
        }
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN) {
            velocityTracker?.recycle()
            velocityTracker = VelocityTracker.obtain()
            locations.clear()
            claimedTouches.clear()
            twoFinger = null
            twoFingerUsed = false
            pad = null
        }
        velocityTracker?.addMovement(event)
        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                locations[event.getPointerId(index)] = point(event, index)
                windowTouchBegan(event, index)
                touchesBegan(event, index)
            }
            MotionEvent.ACTION_MOVE -> touchesMoved(event)
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                val cancelled = Build.VERSION.SDK_INT >= 33 && event.flags and MotionEvent.FLAG_CANCELED != 0
                touchesEnded(event, event.actionIndex, cancelled)
            }
            MotionEvent.ACTION_CANCEL -> for (index in 0 until event.pointerCount) touchesEnded(event, index, cancelled = true)
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            velocityTracker?.recycle()
            velocityTracker = null
            locations.clear()
            claimedTouches.clear()
        }
        return true
    }

    private fun touchesBegan(event: MotionEvent, index: Int) {
        if (drag.isDragging) return
        controller.stopScrollMomentum()
        view.takeKeyFocus()
        val id = event.getPointerId(index)
        val location = point(event, index)
        val mouse = event.isFromSource(InputDevice.SOURCE_MOUSE)
        when {
            mouse && isPointerLocked -> Unit
            controller.isToolbarVisible -> toolbarTouches.add(id)
            controller.isZoomModeOn -> zoomTouchBegan(id, location, event.eventTime)
            mouse && event.buttonState == 0 && event.getToolType(index) == MotionEvent.TOOL_TYPE_FINGER -> padBegan(event, index)
            mouse -> pointerBegan(event, index)
            handleTouch == null && isPointerLocked && controller.toolbarHandleFrame?.contains(windowPoint(location)) == true -> handleTouch = id
            controller.touchesNatively -> {
                if (!view.surface.imageRect.inflate(touchMargin).contains(location)) return
                nextNativeTouchID += 1
                nativeTouches[id] = nextNativeTouchID
                controller.sendTouches(listOf(nativeTouch(id, TouchPhase.Began, location)))
            }
            isStylus(event, index) -> pointerBegan(event, index)
            else -> fingerBegan(id, location, event)
        }
    }

    private fun touchesMoved(event: MotionEvent) {
        val moved = (0 until event.pointerCount).filter { point(event, it) != locations[event.getPointerId(it)] }
        if (moved.isEmpty()) return
        zoomTouchesMoved(event, moved)
        controller.sendTouches(
            moved.filter { nativeTouches.containsKey(event.getPointerId(it)) }
                .map { nativeTouch(event.getPointerId(it), TouchPhase.Moved, point(event, it)) },
        )
        val mouse = event.isFromSource(InputDevice.SOURCE_MOUSE)
        for (index in moved) {
            val id = event.getPointerId(index)
            if (zoomTouches.containsKey(id) || nativeTouches.containsKey(id) || id in claimedTouches) continue
            when {
                id in pointerTouches -> pointerMoved(event, index)
                !mouse -> fingerMoved(event, index)
            }
        }
        for (index in moved) locations[event.getPointerId(index)] = point(event, index)
        twoFingerMoved(event)
        padMoved(event)
    }

    private fun touchesEnded(event: MotionEvent, index: Int, cancelled: Boolean) {
        val id = event.getPointerId(index)
        val location = point(event, index)
        val time = event.eventTime
        val native = nativeTouches.remove(id)
        if (native != null) {
            controller.sendTouches(listOf(RemoteTouch(native, if (cancelled) TouchPhase.Cancelled else TouchPhase.Ended, view.surface.imagePoint(location))))
            return
        }
        if (toolbarTouches.remove(id)) {
            if (!cancelled && controller.isToolbarVisible) controller.toggleToolbar()
            return
        }
        if (cancelled && drag.takenTouch == ScreenDrag.TouchKey(event.downTime, id)) {
            fingers.remove(id)
            pointerTouches.remove(id)
            if (id == finger?.id) finger = null
            twoFingerLifted(event, id, cancelled = true)
            return
        }
        if (zoomTouches.containsKey(id)) {
            zoomTouchEnded(id, location, time, cancelled)
            return
        }
        when {
            id in pointerTouches -> pointerEnded(id)
            pad?.pointers?.contains(id) == true -> padLifted(id, location, cancelled)
            event.isFromSource(InputDevice.SOURCE_MOUSE) -> Unit
            id == handleTouch -> {
                handleTouch = null
                if (!cancelled && controller.toolbarHandleFrame?.contains(windowPoint(location)) == true) controller.toggleToolbar()
            }
            id in claimedTouches -> Unit
            else -> fingerEnded(id, location, time, cancelled)
        }
        twoFingerLifted(event, id, cancelled)
    }

    private fun nativeTouch(id: Int, phase: TouchPhase, location: Offset): RemoteTouch = RemoteTouch(nativeTouches[id] ?: 0L, phase, view.surface.imagePoint(location))

    private fun zoomTouchBegan(id: Int, location: Offset, time: Long) {
        zoomTouches[id] = location
        zoomTap = if (zoomTouches.size == 1) ZoomTap(location, time, 0f) else null
    }

    private fun zoomTouchesMoved(event: MotionEvent, moved: List<Int>) {
        val previous = LinkedHashMap(zoomTouches)
        val current = LinkedHashMap(previous)
        for (index in moved) {
            val id = event.getPointerId(index)
            if (previous.containsKey(id)) current[id] = point(event, index)
        }
        if (current == previous) return
        zoomTouches.clear()
        zoomTouches.putAll(current)
        val tap = zoomTap
        val location = current.values.firstOrNull()
        if (tap != null && location != null) tap.travel = max(tap.travel, (location - tap.start).getDistance())
        val from = centroid(previous.values)
        val to = centroid(current.values)
        var factor = 1f
        if (current.size > 1) {
            val spreadBefore = spread(previous.values, from)
            val spreadAfter = spread(current.values, to)
            if (spreadBefore > 0) factor = spreadAfter / spreadBefore
        }
        view.surface.setZoom(view.surface.zoom * factor, view.surface.imagePoint(from), to)
    }

    private fun zoomTouchEnded(id: Int, location: Offset, time: Long, cancelled: Boolean) {
        zoomTouches.remove(id)
        if (zoomTouches.isNotEmpty()) return
        val tap = zoomTap
        zoomTap = null
        if (cancelled || tap == null || tap.travel > tapSlop || time - tap.time > TAP_DURATION) {
            lastZoomTap = null
            return
        }
        val last = lastZoomTap
        if (last != null && time - last.second <= MULTI_CLICK_INTERVAL && (location - last.first).getDistance() <= DOUBLE_TAP_DISTANCE * density) {
            lastZoomTap = null
            view.surface.toggleZoom(location)
        } else {
            lastZoomTap = location to time
        }
    }

    private fun centroid(points: Collection<Offset>): Offset = points.fold(Offset.Zero) { sum, point -> sum + point } / points.size.toFloat()

    private fun spread(points: Collection<Offset>, center: Offset): Float = points.sumOf { (it - center).getDistance().toDouble() }.toFloat() / points.size

    private fun fingerBegan(id: Int, location: Offset, event: MotionEvent) {
        fingers.add(id)
        twoFingerBegan(event)
        if (fingers.size != 1) {
            finger?.let { end(it, null) }
            return
        }
        val next = Finger(id, location, event.eventTime, controller.pointerPosition)
        finger = next
        if (!controller.isControlling) return
        pressIndicator.beginHold(PRESS_INDICATOR_DELAY, PRESS_HOLD - PRESS_INDICATOR_DELAY)
        val task = Runnable { if (finger === next && next.phase == Phase.Pending) beginDrag(next) }
        holdTask = task
        handler.postDelayed(task, PRESS_HOLD)
    }

    private fun fingerMoved(event: MotionEvent, index: Int) {
        val id = event.getPointerId(index)
        val location = point(event, index)
        val finger = finger?.takeIf { it.id == id } ?: return
        val time = event.eventTime
        val delta = location - finger.last
        finger.record(location, time)
        if (finger.phase == Phase.Pending && finger.travel > tapSlop) {
            finger.phase = Phase.Pointing
            pressIndicator.dismiss()
        }
        movePointer(delta, finger.velocity(time).getDistance())
        if (finger.phase == Phase.Dragging) drag.begin(ScreenDrag.TouchKey(event.downTime, id), location, relative = true)
    }

    private fun fingerEnded(id: Int, location: Offset, time: Long, cancelled: Boolean) {
        fingers.remove(id)
        val finger = finger?.takeIf { it.id == id } ?: return
        end(finger, if (cancelled) null else time)
    }

    private fun end(finger: Finger, liftedAt: Long?) {
        this.finger = null
        cancelHold()
        when (finger.phase) {
            Phase.Pending -> if (liftedAt != null && finger.isTap(liftedAt) && controller.isControlling) {
                controller.movePointer(finger.startPointer)
                controller.click(PointerButtons.Primary)
            }
            Phase.Pointing -> Unit
            Phase.Dragging -> controller.release(PointerButtons.Primary)
        }
    }

    private fun beginDrag(finger: Finger) {
        cancelHold()
        finger.phase = Phase.Dragging
        controller.press(PointerButtons.Primary, clickCount = 1)
        pressIndicator.press()
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    private fun cancelHold() {
        holdTask?.let { handler.removeCallbacks(it) }
        holdTask = null
    }

    fun movePointer(delta: Offset, speed: Float) {
        val gain = 1.2f + min(speed / density / 1200f, 1.8f)
        val scale = view.surface.displayScale
        if (scale <= 0f) return
        val position = controller.pointerPosition
        controller.movePointer(Offset(position.x + delta.x * gain / scale, position.y + delta.y * gain / scale))
        view.surface.keepPointerVisible()
    }

    private fun allows(gesture: TouchGesture): Boolean {
        if (controller.isToolbarVisible || drag.isDragging || controller.isZoomModeOn) return false
        if (controller.touchesNatively || nativeTouches.isNotEmpty()) return false
        val transforms = gesture == TouchGesture.Pinch || gesture == TouchGesture.Rotation
        if (transforms && !AppSettings.pinchZooms) return controller.sendsGesturesToMac
        return when (gesture) {
            TouchGesture.Rotation -> false
            TouchGesture.SecondaryTap -> controller.isControlling
            TouchGesture.Pan, TouchGesture.Pinch -> true
        }
    }

    private fun twoFingerBegan(event: MotionEvent) {
        val current = twoFinger
        if (current != null) {
            if (current.state == TwoFinger.State.Possible) current.state = TwoFinger.State.Ended
            return
        }
        if (fingers.size != 2 || twoFingerUsed) return
        val ids = fingers.toIntArray()
        val starts = Array(2) { locations[ids[it]] ?: Offset.Zero }
        twoFinger = TwoFinger(ids, starts, event.downTime)
        twoFingerUsed = true
    }

    private fun twoFingerMoved(event: MotionEvent) {
        val gesture = twoFinger ?: return
        val first = event.findPointerIndex(gesture.ids[0])
        val second = event.findPointerIndex(gesture.ids[1])
        if (first < 0 || second < 0) return
        val a = point(event, first)
        val b = point(event, second)
        val centroid = (a + b) / 2f
        val translation = centroid - gesture.startCentroid
        val distance = (b - a).getDistance()
        val scale = if (gesture.startDistance > 0) distance / gesture.startDistance else 1f
        var rotation = atan2(b.y - a.y, b.x - a.x) - gesture.startAngle
        if (rotation > PI) rotation -= (2 * PI).toFloat()
        if (rotation < -PI) rotation += (2 * PI).toFloat()
        when (gesture.state) {
            TwoFinger.State.Possible -> {
                if ((a - gesture.starts[0]).getDistance() > tapSlop || (b - gesture.starts[1]).getDistance() > tapSlop) gesture.tapFailed = true
                if (gesture.lifted > 0) return
                val pinches = allows(TouchGesture.Pinch)
                val rotates = allows(TouchGesture.Rotation)
                if ((pinches && abs(distance - gesture.startDistance) > spanSlop) || (rotates && abs(rotation) > ROTATION_THRESHOLD)) {
                    gesture.state = TwoFinger.State.Transform
                    gesture.pinches = pinches
                    gesture.rotates = rotates
                    if (pinches) handlePinch(GesturePhase.Began, scale, centroid, 2)
                    if (rotates) handleRotation(GesturePhase.Began, rotation)
                    claim(gesture, event.eventTime)
                } else if (allows(TouchGesture.Pan) && translation.getDistance() > touchSlop) {
                    gesture.state = TwoFinger.State.Pan
                    handleTwoFingerPan(GesturePhase.Began, translation, Offset.Zero)
                    claim(gesture, event.eventTime)
                }
            }
            TwoFinger.State.Pan -> handleTwoFingerPan(GesturePhase.Changed, translation, Offset.Zero)
            TwoFinger.State.Transform -> {
                if (gesture.pinches) handlePinch(GesturePhase.Changed, scale, centroid, 2)
                if (gesture.rotates) handleRotation(GesturePhase.Changed, rotation)
            }
            TwoFinger.State.Ended -> Unit
        }
    }

    private fun claim(gesture: TwoFinger, time: Long) {
        for (id in gesture.ids) {
            if (id !in fingers) continue
            claimedTouches.add(id)
            fingerEnded(id, locations[id] ?: Offset.Zero, time, cancelled = true)
        }
    }

    private fun twoFingerLifted(event: MotionEvent, id: Int, cancelled: Boolean) {
        val gesture = twoFinger ?: return
        if (id !in gesture.ids) return
        when (gesture.state) {
            TwoFinger.State.Possible -> {
                gesture.lifted += 1
                if (cancelled) gesture.tapFailed = true
                if (gesture.lifted < 2) return
                gesture.state = TwoFinger.State.Ended
                if (!gesture.tapFailed && event.eventTime - gesture.startTime <= TAP_DURATION && allows(TouchGesture.SecondaryTap)) {
                    controller.click(PointerButtons.Secondary)
                }
            }
            TwoFinger.State.Pan -> {
                gesture.state = TwoFinger.State.Ended
                if (cancelled) {
                    handleTwoFingerPan(GesturePhase.Cancelled, Offset.Zero, Offset.Zero)
                } else {
                    val tracker = velocityTracker
                    var velocity = Offset.Zero
                    if (tracker != null) {
                        tracker.computeCurrentVelocity(1000)
                        velocity = Offset(
                            gesture.ids.map { tracker.getXVelocity(it) }.average().toFloat(),
                            gesture.ids.map { tracker.getYVelocity(it) }.average().toFloat(),
                        )
                    }
                    handleTwoFingerPan(GesturePhase.Ended, Offset.Zero, velocity)
                }
            }
            TwoFinger.State.Transform -> {
                gesture.state = TwoFinger.State.Ended
                val phase = if (cancelled) GesturePhase.Cancelled else GesturePhase.Ended
                if (gesture.pinches) handlePinch(phase, 1f, Offset.Zero, 2)
                if (gesture.rotates) handleRotation(phase, 0f)
            }
            TwoFinger.State.Ended -> Unit
        }
    }

    private fun scroll(stream: ScrollStream, delta: Offset) {
        val scale = view.surface.displayScale
        if (scale <= 0f) return
        if (stream.usesPhases) {
            controller.scroll(delta / scale, if (stream.started) GesturePhase.Changed else GesturePhase.Began)
            stream.started = true
            return
        }
        stream.remainder += delta
        val sendX = truncate(stream.remainder.x)
        val sendY = truncate(stream.remainder.y)
        if (sendX == 0f && sendY == 0f) return
        stream.remainder -= Offset(sendX, sendY)
        controller.scroll(Offset(sendX / scale, sendY / scale))
    }

    private fun endScroll(stream: ScrollStream, velocity: Offset, cancelled: Boolean) {
        if (!stream.started) return
        stream.started = false
        controller.scroll(Offset.Zero, if (cancelled) GesturePhase.Cancelled else GesturePhase.Ended)
        if (!cancelled) {
            val scale = view.surface.displayScale
            if (scale > 0f) controller.startScrollMomentum(velocity / scale)
        }
    }

    private fun handleTwoFingerPan(phase: GesturePhase, translation: Offset, velocity: Offset) {
        when (phase) {
            GesturePhase.Began -> {
                twoFingerTranslation = Offset.Zero
                twoFingerPansView = !controller.isControlling
                twoFingerScroll = ScrollStream(usesPhases = controller.sendsGesturesToMac)
            }
            GesturePhase.Changed -> {
                val delta = translation - twoFingerTranslation
                twoFingerTranslation = translation
                if (twoFingerPansView) {
                    view.surface.panViewport(delta)
                } else {
                    scroll(twoFingerScroll, delta)
                }
            }
            GesturePhase.Ended -> endScroll(twoFingerScroll, velocity, cancelled = false)
            else -> endScroll(twoFingerScroll, Offset.Zero, cancelled = true)
        }
    }

    private fun handlePinch(phase: GesturePhase, scale: Float, location: Offset, touches: Int) {
        if (phase == GesturePhase.Began) {
            pinchGoesToMac = controller.sendsGesturesToMac && !controller.isZoomModeOn &&
                (touches == 0 || !AppSettings.pinchZooms)
        }
        if (pinchGoesToMac) {
            handleMacPinch(phase, scale)
            return
        }
        when (phase) {
            GesturePhase.Began -> {
                pinchStartZoom = view.surface.zoom / scale
                pinchLocation = location
                pinchAnchor = view.surface.imagePoint(location)
            }
            GesturePhase.Changed -> {
                val zoom = pinchStartZoom * scale
                if (zoom > 1) {
                    view.surface.setZoom(zoom, pinchAnchor, pinchLocation)
                } else if (view.surface.zoom != 1f) {
                    view.surface.applyScale(ScaleMode.Fit)
                    pinchAnchor = view.surface.imagePoint(pinchLocation)
                }
            }
            else -> Unit
        }
    }

    private fun handleMacPinch(phase: GesturePhase, scale: Float) {
        when (phase) {
            GesturePhase.Began -> {
                macPinchStarted = false
                previousPinchScale = 1f
            }
            GesturePhase.Changed -> {
                if (!macPinchStarted) {
                    macPinchStarted = true
                    controller.magnify((scale - 1).toDouble(), GesturePhase.Began)
                } else {
                    controller.magnify((scale / previousPinchScale - 1).toDouble(), GesturePhase.Changed)
                }
                previousPinchScale = scale
            }
            else -> {
                if (macPinchStarted) {
                    controller.magnify(0.0, if (phase == GesturePhase.Ended) GesturePhase.Ended else GesturePhase.Cancelled)
                }
                macPinchStarted = false
            }
        }
    }

    private fun handleRotation(phase: GesturePhase, rotation: Float) {
        when (phase) {
            GesturePhase.Began -> {
                macRotationStarted = false
                previousRotation = 0f
            }
            GesturePhase.Changed -> {
                val sent = if (!macRotationStarted) {
                    if (abs(rotation) <= ROTATION_THRESHOLD) return
                    macRotationStarted = true
                    GesturePhase.Began
                } else {
                    GesturePhase.Changed
                }
                controller.rotate(-(rotation - previousRotation) * 180.0 / PI, sent)
                previousRotation = rotation
            }
            else -> {
                if (macRotationStarted) {
                    controller.rotate(0.0, if (phase == GesturePhase.Ended) GesturePhase.Ended else GesturePhase.Cancelled)
                }
                macRotationStarted = false
            }
        }
    }

    private fun handleHardwareScroll(phase: GesturePhase, translation: Offset, velocity: Offset) {
        if (phase == GesturePhase.Began) {
            hardwareScrollTranslation = Offset.Zero
            hardwareScrollPansView = controller.isZoomModeOn
            hardwareScroll = ScrollStream(usesPhases = controller.sendsGesturesToMac)
        }
        when (phase) {
            GesturePhase.Began, GesturePhase.Changed -> {
                val delta = translation - hardwareScrollTranslation
                hardwareScrollTranslation = translation
                if (hardwareScrollPansView) {
                    view.surface.panViewport(delta)
                } else {
                    scroll(hardwareScroll, delta)
                }
            }
            GesturePhase.Ended -> endScroll(hardwareScroll, velocity, cancelled = false)
            else -> endScroll(hardwareScroll, Offset.Zero, cancelled = true)
        }
    }

    private fun touchpadSwiped(event: MotionEvent) {
        val location = point(event, 0)
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN) {
            swipeTracker?.recycle()
            swipeTracker = VelocityTracker.obtain()
            swipeStart = if (controller.isToolbarVisible || drag.isDragging) null else location
            swipeScrolls = false
        }
        swipeTracker?.addMovement(event)
        val start = swipeStart
        when (action) {
            MotionEvent.ACTION_MOVE -> {
                if (start == null) return
                handleHardwareScroll(if (swipeScrolls) GesturePhase.Changed else GesturePhase.Began, location - start, Offset.Zero)
                swipeScrolls = true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (start != null && swipeScrolls) {
                    if (action == MotionEvent.ACTION_UP) {
                        val tracker = swipeTracker
                        var velocity = Offset.Zero
                        if (tracker != null) {
                            tracker.computeCurrentVelocity(1000)
                            val id = event.getPointerId(0)
                            velocity = Offset(tracker.getXVelocity(id), tracker.getYVelocity(id))
                        }
                        handleHardwareScroll(GesturePhase.Ended, location - start, velocity)
                    } else {
                        handleHardwareScroll(GesturePhase.Cancelled, Offset.Zero, Offset.Zero)
                    }
                }
                swipeScrolls = false
                swipeStart = null
                swipeTracker?.recycle()
                swipeTracker = null
            }
        }
    }

    private fun touchpadPinched(event: MotionEvent) {
        val location = centroid((0 until event.pointerCount).map { point(event, it) })
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchpadPinchScale = 1f
                touchpadPinchState = if (controller.isToolbarVisible || drag.isDragging) false else null
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_MOVE -> {
                if (touchpadPinchState == false) return
                if (touchpadPinchState == null) {
                    touchpadPinchState = true
                    handlePinch(GesturePhase.Began, touchpadPinchScale, location, 0)
                }
                if (event.actionMasked == MotionEvent.ACTION_MOVE && Build.VERSION.SDK_INT >= 34) {
                    val factor = event.getAxisValue(MotionEvent.AXIS_GESTURE_PINCH_SCALE_FACTOR)
                    if (factor > 0f) touchpadPinchScale *= factor
                    handlePinch(GesturePhase.Changed, touchpadPinchScale, location, 0)
                }
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (touchpadPinchState != true) return
                touchpadPinchState = false
                handlePinch(if (event.actionMasked == MotionEvent.ACTION_CANCEL) GesturePhase.Cancelled else GesturePhase.Ended, touchpadPinchScale, location, 0)
            }
        }
    }

    private fun padBegan(event: MotionEvent, index: Int) {
        val gesture = pad ?: PadGesture(point(event, index)).also { pad = it }
        gesture.pointers.add(event.getPointerId(index))
        if (gesture.pointers.size < 2) return
        gesture.multiFinger = true
        if (gesture.scrolls) {
            gesture.scrolls = false
            handleHardwareScroll(GesturePhase.Cancelled, Offset.Zero, Offset.Zero)
        }
        if (gesture.pinches) {
            gesture.pinches = false
            handlePinch(GesturePhase.Cancelled, gesture.pinchScale, Offset.Zero, 0)
        }
        val points = padPoints(event, gesture)
        if (points.size != 2) return
        gesture.pinches = true
        gesture.pinchDistance = (points[1] - points[0]).getDistance()
        gesture.pinchScale = 1f
        handlePinch(GesturePhase.Began, 1f, centroid(points), 0)
    }

    private fun padMoved(event: MotionEvent) {
        val gesture = pad ?: return
        val points = padPoints(event, gesture)
        if (gesture.pinches) {
            if (points.size != 2) return
            if (gesture.pinchDistance > 0f) gesture.pinchScale = (points[1] - points[0]).getDistance() / gesture.pinchDistance
            handlePinch(GesturePhase.Changed, gesture.pinchScale, centroid(points), 0)
            return
        }
        if (gesture.multiFinger) return
        val translation = (points.singleOrNull() ?: return) - gesture.start
        if (!gesture.scrolls && translation.getDistance() <= touchSlop) return
        handleHardwareScroll(if (gesture.scrolls) GesturePhase.Changed else GesturePhase.Began, translation, Offset.Zero)
        gesture.scrolls = true
    }

    private fun padLifted(id: Int, location: Offset, cancelled: Boolean) {
        val gesture = pad ?: return
        gesture.pointers.remove(id)
        if (gesture.pointers.isEmpty()) pad = null
        val phase = if (cancelled) GesturePhase.Cancelled else GesturePhase.Ended
        when {
            gesture.pinches -> {
                gesture.pinches = false
                handlePinch(phase, gesture.pinchScale, location, 0)
            }
            gesture.scrolls -> {
                gesture.scrolls = false
                val tracker = velocityTracker
                var velocity = Offset.Zero
                if (!cancelled && tracker != null) {
                    tracker.computeCurrentVelocity(1000)
                    velocity = Offset(tracker.getXVelocity(id), tracker.getYVelocity(id))
                }
                handleHardwareScroll(phase, location - gesture.start, velocity)
            }
            !cancelled && !gesture.multiFinger -> {
                controller.movePointer(view.surface.imagePoint(gesture.start))
                controller.click(PointerButtons.Primary)
            }
        }
    }

    private fun padPoints(event: MotionEvent, gesture: PadGesture): List<Offset> = gesture.pointers.mapNotNull { id -> event.findPointerIndex(id).takeIf { it >= 0 }?.let { point(event, it) } }

    fun onGenericMotionEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_SCROLL -> {
                if (!event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) return false
                handleWheel(event, point(event, 0))
                return true
            }
            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> {
                if (event.getPointerId(0) in pointerTouches) {
                    val buttons = if (isStylus(event, 0)) buttons(event, 0) else mouseButtons(event.buttonState)
                    if (!buttons.isEmpty && buttons != controller.pointerButtons) controller.setPressedButtons(buttons)
                }
                return true
            }
        }
        return false
    }

    private fun handleWheel(event: MotionEvent, location: Offset) {
        if (controller.isToolbarVisible || drag.isDragging) return
        val delta = Offset(
            -event.getAxisValue(MotionEvent.AXIS_HSCROLL) * ViewConfigurationCompat.getScaledHorizontalScrollFactor(configuration, view.context),
            event.getAxisValue(MotionEvent.AXIS_VSCROLL) * ViewConfigurationCompat.getScaledVerticalScrollFactor(configuration, view.context),
        )
        if (delta == Offset.Zero) return
        if (controller.isZoomModeOn) {
            val factor = 2f.pow(delta.y / (WHEEL_POINTS_PER_ZOOM_DOUBLING * density))
            view.surface.setZoom(view.surface.zoom * factor, view.surface.imagePoint(location), location)
        } else {
            scroll(wheelScroll, delta)
        }
    }

    fun onHoverEvent(event: MotionEvent): Boolean {
        val location = point(event, 0)
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_HOVER_ENTER || action == MotionEvent.ACTION_HOVER_MOVE) {
            trackSystemPointer(location, fromPointer = !isStylus(event, 0))
        }
        if (isPointerLocked || controller.isZoomModeOn) return true
        when (action) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                if (!controller.isToolbarVisible && view.surface.imageRect.inflate(HOVER_MARGIN * density).contains(location)) {
                    controller.movePointer(view.surface.imagePoint(location))
                }
            }
            MotionEvent.ACTION_HOVER_EXIT -> controller.pointerLeftScreen()
        }
        return true
    }

    private fun pointerBegan(event: MotionEvent, index: Int) {
        val buttons = buttons(event, index)
        if (buttons.isEmpty) return
        pointerTouches.add(event.getPointerId(index))
        controller.movePointer(view.surface.imagePoint(point(event, index)))
        controller.setPressedButtons(buttons)
    }

    private fun pointerMoved(event: MotionEvent, index: Int) {
        val id = event.getPointerId(index)
        if (id !in pointerTouches) return
        val location = point(event, index)
        controller.movePointer(view.surface.imagePoint(location))
        val buttons = buttons(event, index)
        if (!buttons.isEmpty && buttons != controller.pointerButtons) controller.setPressedButtons(buttons)
        drag.begin(ScreenDrag.TouchKey(event.downTime, id), location, relative = false)
    }

    private fun pointerEnded(id: Int) {
        if (!pointerTouches.remove(id) || pointerTouches.isNotEmpty()) return
        controller.setPressedButtons(PointerButtons.None)
    }

    private fun buttons(event: MotionEvent, index: Int): PointerButtons {
        val state = event.buttonState
        if (isStylus(event, index)) {
            return when {
                state and MotionEvent.BUTTON_STYLUS_PRIMARY != 0 -> PointerButtons.Secondary
                state and MotionEvent.BUTTON_STYLUS_SECONDARY != 0 -> PointerButtons.Middle
                else -> PointerButtons.Primary
            }
        }
        return if (state == 0) PointerButtons.Primary else mouseButtons(state)
    }

    private fun mouseButtons(state: Int): PointerButtons {
        var buttons = PointerButtons.None
        if (state and MotionEvent.BUTTON_PRIMARY != 0) buttons += PointerButtons.Primary
        if (state and MotionEvent.BUTTON_SECONDARY != 0) buttons += PointerButtons.Secondary
        if (state and MotionEvent.BUTTON_TERTIARY != 0) buttons += PointerButtons.Middle
        return buttons
    }

    fun onCapturedPointerEvent(event: MotionEvent): Boolean {
        if (!isPointerLocked) return false
        if (event.isFromSource(InputDevice.SOURCE_TOUCHPAD)) {
            touchpadCaptured(event)
        } else {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_MOVE -> {
                    var x = event.x
                    var y = event.y
                    for (index in 0 until event.historySize) {
                        x += event.getHistoricalX(index)
                        y += event.getHistoricalY(index)
                    }
                    lockedMouseMoved(Offset(x, y))
                }
                MotionEvent.ACTION_SCROLL -> handleWheel(event, view.surface.viewPoint(controller.pointerPosition))
            }
        }
        updateLockedButtons(event.buttonState)
        return true
    }

    private fun lockedMouseMoved(counts: Offset) {
        if (!isPointerLocked || !counts.x.isFinite() || !counts.y.isFinite()) return
        lockedPointerMoved(counts * (density / MOUSE_COUNTS_PER_POINT))
    }

    private fun lockedPointerMoved(delta: Offset) {
        val now = System.nanoTime()
        val interval = ((now - lastLockedMoveTime) / 1e9f).coerceIn(0.001f, 0.1f)
        lastLockedMoveTime = now
        lockedMouseSpeed += (delta.getDistance() / interval - lockedMouseSpeed) * min(1f, interval / LOCKED_SPEED_SMOOTHING)
        movePointer(delta, lockedMouseSpeed)
    }

    private fun updateLockedButtons(state: Int) {
        val buttons = mouseButtons(state)
        for (button in listOf(PointerButtons.Primary, PointerButtons.Secondary, PointerButtons.Middle)) {
            val pressed = button in buttons
            if (pressed != (button in lockedButtons)) lockedMouseButton(button, pressed)
        }
    }

    private fun lockedMouseButton(button: PointerButtons, pressed: Boolean) {
        if (!isPointerLocked) return
        if (pressed) {
            controller.stopScrollMomentum()
            view.takeKeyFocus()
            lockedButtons += button
            controller.press(button)
        } else if (button in lockedButtons) {
            lockedButtons -= button
            controller.release(button)
        }
    }

    private fun touchpadCaptured(event: MotionEvent) {
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN) {
            padTracker?.recycle()
            padTracker = VelocityTracker.obtain()
            padTouches.clear()
        }
        padTracker?.addMovement(event)
        val resolution = event.device?.getMotionRange(MotionEvent.AXIS_X, event.source)?.resolution ?: 0f
        val pixelsPerUnit = if (resolution > 0f) density * TOUCHPAD_POINTS_PER_MM / resolution else 1f
        val current = (0 until event.pointerCount).associate { event.getPointerId(it) to point(event, it) }
        when (action) {
            MotionEvent.ACTION_MOVE -> {
                val deltas = current.mapNotNull { (id, location) -> padTouches[id]?.let { location - it } }
                when (current.size) {
                    1 -> deltas.firstOrNull()?.let { lockedPointerMoved(it * pixelsPerUnit) }
                    2 -> if (deltas.size == 2) {
                        padTranslation += centroid(deltas) * pixelsPerUnit
                        handleHardwareScroll(if (padScrolls) GesturePhase.Changed else GesturePhase.Began, padTranslation, Offset.Zero)
                        padScrolls = true
                    }
                }
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (padScrolls) {
                    padScrolls = false
                    if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_POINTER_DOWN) {
                        handleHardwareScroll(GesturePhase.Cancelled, Offset.Zero, Offset.Zero)
                    } else {
                        val tracker = padTracker
                        var velocity = Offset.Zero
                        if (tracker != null) {
                            tracker.computeCurrentVelocity(1000)
                            velocity = centroid(current.keys.map { Offset(tracker.getXVelocity(it), tracker.getYVelocity(it)) }) * pixelsPerUnit
                        }
                        handleHardwareScroll(GesturePhase.Ended, padTranslation, velocity)
                    }
                }
                padTranslation = Offset.Zero
            }
        }
        padTouches.clear()
        if (action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL) {
            val lifted = if (action == MotionEvent.ACTION_POINTER_UP) event.getPointerId(event.actionIndex) else null
            for ((id, location) in current) if (id != lifted) padTouches[id] = location
        } else {
            padTracker?.recycle()
            padTracker = null
        }
    }

    fun onPointerCaptureChange(hasCapture: Boolean) {
        lockedMouseSpeed = 0f
        controller.isPointerLocked = hasCapture
        val held = lockedButtons
        lockedButtons = PointerButtons.None
        if (pointerTouches.isNotEmpty()) {
            pointerTouches.clear()
            controller.setPressedButtons(PointerButtons.None)
        } else if (!held.isEmpty) {
            controller.release(held)
        }
        if (padScrolls) {
            padScrolls = false
            handleHardwareScroll(GesturePhase.Cancelled, Offset.Zero, Offset.Zero)
        }
        padTouches.clear()
        padTranslation = Offset.Zero
        val location = systemPointerLocation
        if (hasCapture && location != null) {
            systemPointerLocation = null
            view.getLocationInWindow(windowLocation)
            controller.movePointer(view.surface.imagePoint(Offset(location.x - windowLocation[0], location.y - windowLocation[1])))
            view.surface.keepPointerVisible()
        }
        view.pointerIcon = pointerIcon ?: nullIcon
    }

    private fun trackSystemPointer(location: Offset, fromPointer: Boolean) {
        if (isPointerLocked) return
        val window = windowPoint(location)
        systemPointerLocation = window
        controller.lastSystemPointerLocation = window
        val overPicture = view.surface.imageRect.contains(location)
        controller.isSystemPointerOverPicture = overPicture
        systemPointerOverPicture = overPicture && fromPointer
    }

    private fun windowTouchBegan(event: MotionEvent, index: Int) {
        if (!event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            systemPointerLocation = null
            systemPointerOverPicture = false
        } else if (!isPointerLocked) {
            val window = windowPoint(point(event, index))
            systemPointerLocation = window
            controller.lastSystemPointerLocation = window
        }
    }

    fun onResolvePointerIcon(event: MotionEvent, pointerIndex: Int): PointerIcon? {
        val inside = !controller.isToolbarVisible && view.surface.imageRect.contains(point(event, pointerIndex))
        if (!inside || controller.isZoomModeOn || !controller.isControlling) return null
        if (isStylus(event, pointerIndex)) return nullIcon
        return pointerIcon ?: nullIcon
    }

    fun showsCursorAsPointer(shape: CursorShape?, visible: Boolean, scale: Float): Boolean {
        if (!PointerLock.hasMouse || !systemPointerOverPicture || controller.isZoomModeOn) return false
        val style = AppSettings.cursorStyle
        val iconScale = 2f.pow(round(log2(scale) * POINTER_ICON_SCALE_STEPS) / POINTER_ICON_SCALE_STEPS)
        val cursor = when {
            !visible || shape == null -> null
            style is CursorStyle.Theme -> localPointer(shape, iconScale) ?: shape
            else -> shape
        }
        val standard = style == CursorStyle.System && cursor?.kind != null
        val key: Any? = when {
            cursor == null -> null
            standard -> cursor.kind
            else -> PointerIconKey(cursor.image, cursor.hotspot, cursor.scale, iconScale)
        }
        if (key != pointerIconKey) {
            pointerIconKey = key
            pointerIcon = cursor?.let { PointerShape.icon(view.context, it, iconScale, standard) }
            view.pointerIcon = pointerIcon ?: nullIcon
        }
        return pointerIcon != null && !isPointerLocked && controller.isControlling && !controller.isToolbarVisible
    }

    fun localPointer(shape: CursorShape?, scale: Float): CursorShape? {
        if (shape == null || scale <= 0f) return null
        val style = AppSettings.cursorStyle as? CursorStyle.Theme ?: return null
        val kind = shape.kind ?: return null
        val drawn = CursorThemeStore.theme(style.id)?.image(kind, density) ?: return null
        return CursorShape(
            image = drawn.image,
            hotspot = Offset(drawn.hotspot.x * density / scale, drawn.hotspot.y * density / scale),
            kind = kind,
            scale = drawn.scale * scale / density,
        )
    }

    private fun point(event: MotionEvent, index: Int): Offset = Offset(event.getX(index), event.getY(index))

    private fun windowPoint(location: Offset): Offset {
        view.getLocationInWindow(windowLocation)
        return Offset(location.x + windowLocation[0], location.y + windowLocation[1])
    }

    private fun isStylus(event: MotionEvent, index: Int): Boolean {
        val type = event.getToolType(index)
        return type == MotionEvent.TOOL_TYPE_STYLUS || type == MotionEvent.TOOL_TYPE_ERASER
    }

    private companion object {
        const val TAP_SLOP = 10f
        const val TAP_DURATION = 350L
        const val MULTI_CLICK_INTERVAL = 500L
        const val PRESS_HOLD = 300L
        const val PRESS_INDICATOR_DELAY = 150L
        const val MOUSE_COUNTS_PER_POINT = 2f
        const val TOUCH_MARGIN = 24f
        const val DOUBLE_TAP_DISTANCE = 40f
        const val WHEEL_POINTS_PER_ZOOM_DOUBLING = 100f
        const val HOVER_MARGIN = 40f
        const val ROTATION_THRESHOLD = 0.08f
        const val VELOCITY_WINDOW = 100L

        // MotionEvent.CLASSIFICATION_MULTI_FINGER_SWIPE, @hide in the SDK
        const val CLASSIFICATION_MULTI_FINGER_SWIPE = 4
        const val VELOCITY_STALE = 50L
        const val VELOCITY_MINIMUM_SPAN = 8L
        const val LOCKED_SPEED_SMOOTHING = 0.05f
        const val TOUCHPAD_POINTS_PER_MM = 160f / 25.4f
        const val POINTER_ICON_SCALE_STEPS = 16f
    }
}
