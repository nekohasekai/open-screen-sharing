package io.nekohasekai.screensharing.sessionui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.animation.PathInterpolator
import androidx.compose.ui.geometry.Offset
import io.nekohasekai.screensharing.App
import kotlin.math.roundToInt

fun animatorsEnabled(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
    ValueAnimator.areAnimatorsEnabled()
} else {
    Settings.Global.getFloat(App.instance.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
}

class PressIndicatorView(context: Context) : View(context) {
    private class Animation(val from: Float, val to: Float, val start: Long, val duration: Long)

    private val density = resources.displayMetrics.density
    private val radius = RADIUS * density
    private val extent = (RADIUS * PULSE_SCALE + LINE_WIDTH + SHADOW_RADIUS) * density
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = LINE_WIDTH * density
        strokeCap = Paint.Cap.ROUND
        color = Color.argb(77, 0, 0, 0)
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = LINE_WIDTH * density
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
        setShadowLayer(SHADOW_RADIUS * density, 0f, 0f, Color.argb(102, 0, 0, 0))
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(51, 255, 255, 255)
    }
    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = PULSE_LINE_WIDTH * density
        color = Color.WHITE
    }
    private val circle = RectF()
    private val easeOut = PathInterpolator(0f, 0f, 0.58f, 1f)
    private var isShowing = false
    private var opacity = 0f
    private var opacityAnimation: Animation? = null
    private var progressAnimation: Animation? = null
    private var pulseStart: Long? = null
    private var filled = false

    var center = Offset.Zero
        set(value) {
            if (value == field) return
            field = value
            if (opacity > 0f || opacityAnimation != null) invalidate()
        }

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun beginHold(delay: Long, duration: Long) {
        isShowing = true
        val start = SystemClock.uptimeMillis() + delay
        filled = false
        pulseStart = null
        opacity = 1f
        opacityAnimation = Animation(0f, 1f, start, APPEAR_DURATION)
        progressAnimation = Animation(0f, 1f, start, duration)
        postInvalidateOnAnimation()
    }

    fun press() {
        opacityAnimation = null
        opacity = 1f
        progressAnimation = null
        filled = true
        pulseStart = if (animatorsEnabled()) SystemClock.uptimeMillis() else null
        postInvalidateOnAnimation()
    }

    fun dismiss() {
        if (!isShowing) return
        isShowing = false
        val now = SystemClock.uptimeMillis()
        val from = presented(opacityAnimation, opacity, now)
        opacity = 0f
        opacityAnimation = Animation(from, 0f, now, DISMISS_DURATION)
        postInvalidateOnAnimation()
    }

    private fun presented(animation: Animation?, model: Float, now: Long): Float {
        animation ?: return model
        if (now < animation.start) return animation.from
        if (now >= animation.start + animation.duration) return model
        val fraction = (now - animation.start).toFloat() / animation.duration
        return animation.from + (animation.to - animation.from) * fraction
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        var animating = false
        val layerOpacity = presented(opacityAnimation, opacity, now)
        opacityAnimation?.let { if (now >= it.start + it.duration) opacityAnimation = null else animating = true }
        val progress = presented(progressAnimation, 1f, now)
        progressAnimation?.let { if (now >= it.start + it.duration) progressAnimation = null else animating = true }
        if (layerOpacity > 0f) {
            canvas.saveLayerAlpha(
                center.x - extent,
                center.y - extent,
                center.x + extent,
                center.y + extent,
                (layerOpacity * 255).roundToInt().coerceIn(0, 255),
            )
            circle.set(center.x - radius, center.y - radius, center.x + radius, center.y + radius)
            canvas.drawOval(circle, trackPaint)
            if (filled) canvas.drawOval(circle, fillPaint)
            if (progress > 0f) canvas.drawArc(circle, -90f, 360f * progress, false, progressPaint)
            pulseStart?.let { start ->
                val time = (now - start).toFloat() / PULSE_DURATION
                if (time >= 1f) {
                    pulseStart = null
                    return@let
                }
                val eased = easeOut.getInterpolation(time)
                val scale = 1f + (PULSE_SCALE - 1f) * eased
                pulsePaint.alpha = ((1f - eased) * 255).roundToInt().coerceIn(0, 255)
                canvas.save()
                canvas.scale(scale, scale, center.x, center.y)
                canvas.drawOval(circle, pulsePaint)
                canvas.restore()
                animating = true
            }
            canvas.restore()
        }
        if (animating) postInvalidateOnAnimation()
    }

    private companion object {
        const val RADIUS = 22f
        const val LINE_WIDTH = 3f
        const val PULSE_LINE_WIDTH = 2f
        const val SHADOW_RADIUS = 2f
        const val PULSE_SCALE = 1.4f
        const val APPEAR_DURATION = 100L
        const val DISMISS_DURATION = 150L
        const val PULSE_DURATION = 350L
    }
}
