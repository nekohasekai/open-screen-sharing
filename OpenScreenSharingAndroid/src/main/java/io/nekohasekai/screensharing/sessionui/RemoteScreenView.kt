package io.nekohasekai.screensharing.sessionui

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.DragEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import io.nekohasekai.screensharing.R
import io.nekohasekai.screensharing.model.AppSettings
import io.nekohasekai.screensharing.session.CursorShape
import io.nekohasekai.screensharing.session.Letterbox
import io.nekohasekai.screensharing.session.PointerButtons
import io.nekohasekai.screensharing.session.RemotePlatform
import io.nekohasekai.screensharing.session.SessionController
import io.nekohasekai.screensharing.session.SessionMode
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@SuppressLint("ViewConstructor")
class RemoteScreenView(context: Context, val controller: SessionController) : FrameLayout(context) {
    val surface: RemoteScreenSurface = object : RemoteScreenSurface(context, controller) {
        override fun setKeyboardVisible(visible: Boolean) = keyInput.setKeyboardVisible(visible)

        override fun forgetTypedText() = keyInput.forgetTypedText()
    }

    val windowTracker = ScreenWindowTracker(
        this,
        report = { area, scale, screen -> controller.displayAreaDidChange(area, scale, screen) },
        windowResized = { size -> controller.windowSizeDidChange(size) },
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val density = resources.displayMetrics.density
    private val loupePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ScreenObstructions.LOUPE_BORDER_WIDTH * density
        color = android.graphics.Color.argb(217, 255, 255, 255)
    }
    private var loupeFrame: Rect? = null
    private var lastInsets: WindowInsets? = null
    private val lastLocation = IntArray(2)
    private val location = IntArray(2)
    private var animatedImeBottom: Int? = null
    private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
        getLocationInWindow(location)
        if (rootWindowInsets != lastInsets || !location.contentEquals(lastLocation)) {
            geometryDidChange()
        }
        true
    }

    init {
        setBackgroundColor(android.graphics.Color.BLACK)
        addView(surface, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        isFocusable = true
        isFocusableInTouchMode = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) defaultFocusHighlightEnabled = false
    }

    val input = ScreenInput(this, controller)
    val keyInput = KeyInputProxy(this, controller)

    init {
        val inputDelegate = surface.delegate
        surface.delegate = object : RemoteScreenSurface.Delegate {
            override fun drawnCursor(shape: CursorShape?, visible: Boolean, scale: Float): DrawnCursor? {
                val shownBySystem = input.showsCursorAsPointer(shape, visible, scale)
                if (controller.hidesPointerForTouch) return null
                val cursor = input.localPointer(shape, scale) ?: shape ?: return null
                return DrawnCursor(cursor, shownBySystem)
            }

            override fun cursorDidRender(rect: Rect?) = inputDelegate.cursorDidRender(rect)

            override fun loupe(icon: Rect?): LoupeSpot? = this@RemoteScreenView.loupe(icon)

            override fun geometryDidChange() {
                inputDelegate.geometryDidChange()
                controller.letterbox = measureLetterbox()
                controller.debugInfoObstructions = windowTracker.obstructions.takeIf { windowTracker.fillsScreen }
            }
        }
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = context.getString(R.string.screen_screen_of, controller.title)
        ViewCompat.addAccessibilityAction(this, context.getString(R.string.screen_click)) { _, _ ->
            controller.click(PointerButtons.Primary)
            controller.isControlling
        }
        ViewCompat.addAccessibilityAction(this, context.getString(R.string.screen_right_click)) { _, _ ->
            controller.click(PointerButtons.Secondary)
            controller.isControlling
        }
        ViewCompat.addAccessibilityAction(this, context.getString(R.string.screen_show_or_hide_keyboard)) { _, _ ->
            controller.toggleKeyboard()
            true
        }
        ViewCompat.setWindowInsetsAnimationCallback(
            this,
            object : WindowInsetsAnimationCompat.Callback(WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                override fun onProgress(insets: WindowInsetsCompat, runningAnimations: List<WindowInsetsAnimationCompat>): WindowInsetsCompat {
                    if (runningAnimations.any { it.typeMask and WindowInsetsCompat.Type.ime() != 0 }) {
                        animatedImeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                        surface.keyboardTop = coveredKeyboardTop()
                    }
                    return insets
                }

                override fun onEnd(animation: WindowInsetsAnimationCompat) {
                    if (animation.typeMask and WindowInsetsCompat.Type.ime() == 0) return
                    animatedImeBottom = null
                    geometryDidChange()
                }
            },
        )
    }

    private val bounds: Rect get() = Rect(0f, 0f, width.toFloat(), height.toFloat())

    fun geometryDidChange() {
        lastInsets = rootWindowInsets
        getLocationInWindow(lastLocation)
        windowTracker.geometryDidChange()
        surface.screenArea = windowTracker.screenArea
        surface.keyboardTop = coveredKeyboardTop()
        surface.setNeedsRender()
    }

    var accessoryBarWindowTop: Float? = null
        set(value) {
            if (value == field) return
            field = value
            surface.keyboardTop = coveredKeyboardTop()
        }

    private fun coveredKeyboardTop(): Float? {
        if (!isFocused) return null
        getLocationInWindow(location)
        val imeBottom = animatedImeBottom ?: rootWindowInsets
            ?.let { WindowInsetsCompat.toWindowInsetsCompat(it, this) }
            ?.takeIf { it.isVisible(WindowInsetsCompat.Type.ime()) }
            ?.getInsets(WindowInsetsCompat.Type.ime())
            ?.bottom
        val imeTop = imeBottom?.takeIf { it > 0 }?.let { (rootView.height - it - location[1]).toFloat() }
        val barTop = accessoryBarWindowTop?.let { it - location[1] }
        val top = listOfNotNull(imeTop, barTop).minOrNull() ?: return null
        val area = windowTracker.screenArea ?: bounds
        return if (top > area.top && top < area.bottom) top else null
    }

    private fun measureLetterbox(): Letterbox? {
        val imageSize = controller.imageSize
        val insets = rootWindowInsets
        if (insets == null || imageSize.width <= 0 || imageSize.height <= 0) return null
        val picture = surface.imageRect
        val safe = WindowInsetsCompat.toWindowInsetsCompat(insets, this)
            .getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        getLocationInWindow(location)
        val window = rootView
        val row = ScreenInsets(
            left = max(0, safe.left - location[0]).toFloat(),
            top = max(0, safe.top - location[1]).toFloat(),
            right = max(0, safe.right - (window.width - location[0] - width)).toFloat(),
            bottom = max(0, safe.bottom - (window.height - location[1] - height)).toFloat(),
        )
        val bounds = bounds
        val column = row.inset(bounds)
        val side = max(column.left - bounds.left, bounds.right - column.right)
        val cornerSide = max(row.left, row.right)
        val bottom = min(column.bottom, surface.keyboardTop ?: column.bottom)
        return Letterbox(
            left = Rect(column.left, column.top, column.left + max(0f, picture.left - column.left), column.top + max(0f, bottom - column.top)),
            top = Rect(side, column.top, side + max(0f, bounds.width - 2 * side), column.top + max(0f, picture.top - column.top)),
            topBetweenCorners = Rect(cornerSide, row.top, cornerSide + max(0f, bounds.width - 2 * cornerSide), row.top + max(0f, picture.top - row.top)),
        )
    }

    private fun loupe(icon: Rect?): LoupeSpot? {
        val bounds = bounds
        val spot = if (windowTracker.fillsScreen && icon != null && bounds.overlaps(icon)) windowTracker.obstructions.loupe(icon, bounds) else null
        if (spot == null) {
            setLoupeFrame(null)
            return null
        }
        fun pixelAligned(rect: Rect): Rect {
            val left = rect.left.roundToInt().toFloat()
            val top = rect.top.roundToInt().toFloat()
            return Rect(left, top, left + rect.width.roundToInt(), top + rect.height.roundToInt())
        }
        val aligned = LoupeSpot(pixelAligned(spot.source), pixelAligned(spot.frame))
        setLoupeFrame(aligned.frame)
        return aligned
    }

    private fun setLoupeFrame(frame: Rect?) {
        if (frame == loupeFrame) return
        loupeFrame = frame
        invalidate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val frame = loupeFrame ?: return
        val outset = loupePaint.strokeWidth / 2
        canvas.drawRect(frame.left - outset, frame.top - outset, frame.right + outset, frame.bottom + outset, loupePaint)
    }

    fun takeKeyFocus() {
        if (!isAttachedToWindow || isFocused) return
        requestFocus()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(preDrawListener)
        windowTracker.windowDidChange()
        geometryDidChange()
        post { takeKeyFocus() }
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(preDrawListener)
        controller.releaseAllKeys()
        windowTracker.detach()
        super.onDetachedFromWindow()
        mainHandler.post { input.windowDidChange() }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        geometryDidChange()
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val applied = super.onApplyWindowInsets(insets)
        geometryDidChange()
        return applied
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        geometryDidChange()
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (!gainFocus) {
            controller.releaseAllKeys()
        }
        surface.keyboardTop = coveredKeyboardTop()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        keyInput.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) {
            controller.releaseHeldKeys()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = input.onTouchEvent(event)

    override fun onGenericMotionEvent(event: MotionEvent): Boolean = input.onGenericMotionEvent(event) || super.onGenericMotionEvent(event)

    override fun onHoverEvent(event: MotionEvent): Boolean = input.onHoverEvent(event) || super.onHoverEvent(event)

    override fun onCapturedPointerEvent(event: MotionEvent): Boolean = input.onCapturedPointerEvent(event)

    override fun onPointerCaptureChange(hasCapture: Boolean) {
        super.onPointerCaptureChange(hasCapture)
        input.onPointerCaptureChange(hasCapture)
    }

    override fun onResolvePointerIcon(event: MotionEvent, pointerIndex: Int): PointerIcon? = input.onResolvePointerIcon(event, pointerIndex)

    override fun onDragEvent(event: DragEvent): Boolean = input.drop.onDragEvent(event)

    override fun dispatchKeyEventPreIme(event: KeyEvent): Boolean = keyInput.dispatchKeyEventPreIme(event) || super.dispatchKeyEventPreIme(event)

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = keyInput.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)

    override fun onCheckIsTextEditor(): Boolean = keyInput.onCheckIsTextEditor()

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? = keyInput.onCreateInputConnection(outAttrs)
}

@Composable
fun RemoteScreen(controller: SessionController, modifier: Modifier = Modifier, onView: (RemoteScreenView) -> Unit = {}) {
    val state = stringResource(if (controller.mode == SessionMode.Control) R.string.screen_controlling else R.string.screen_observing)
    key(controller) {
        AndroidView(
            factory = { context -> RemoteScreenView(context, controller).also(onView) },
            modifier = modifier,
            onRelease = { view -> view.input.detach() },
            update = { view ->
                val tracker = view.windowTracker
                tracker.setMatchesWindow(controller.wantsVirtualDisplay)
                tracker.setTurnsLandscape(controller.platform != RemotePlatform.IOS)
                tracker.setPreferredOrientation(controller.preferredOrientation.takeIf { it != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED })
                val fillsScreen = AppSettings.pictureFillsScreen
                if (fillsScreen != tracker.fillsScreen) {
                    tracker.fillsScreen = fillsScreen
                    view.geometryDidChange()
                }
                ViewCompat.setStateDescription(view, state)
            },
        )
    }
}
