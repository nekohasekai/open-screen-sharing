package io.nekohasekai.screensharing.sessionui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Point
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import java.util.WeakHashMap
import kotlin.math.abs

class ScreenWindowTracker(
    private val view: View,
    private val report: (area: Size, scale: Float, screen: Size) -> Unit,
    private val windowResized: (size: Size) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var activity: Activity? = null
    private val reportTask = Runnable {
        reportPending = false
        reportArea()
    }
    private var reportPending = false
    private var scheduledArea: Size? = null
    private var reportedArea: Size? = null
    private var reportedScale: Float? = null
    private var reportedWindow: Size? = null
    private var matchesWindow = false
    var fillsScreen = false
    var obstructions = ScreenObstructions()
        private set
    private var turnsLandscape = true
    private var preferredOrientation: Int? = null
    private var awaitsRotation = false
    private val rotationRefused = Runnable {
        if (!awaitsRotation) return@Runnable
        awaitsRotation = false
        geometryDidChange()
    }

    val screenArea: Rect?
        get() {
            val insets = view.rootWindowInsets ?: return null
            val compat = WindowInsetsCompat.toWindowInsetsCompat(insets, view)
            val location = IntArray(2).also { view.getLocationInWindow(it) }
            val window = view.rootView
            var area = Rect(-location[0].toFloat(), -location[1].toFloat(), (window.width - location[0]).toFloat(), (window.height - location[1]).toFloat())
            // A status bar hidden through WindowInsetsController keeps reporting its tappableElement source as visible.
            val tappable = Insets.min(compat.getInsets(WindowInsetsCompat.Type.tappableElement()), compat.getInsets(WindowInsetsCompat.Type.systemBars()))
            var edges = ScreenInsets.of(tappable).union(ScreenInsets.of(compat.getInsets(WindowInsetsCompat.Type.captionBar())))
            if (!fillsScreen) {
                edges = obstructions.clearingCorners(edges.union(ScreenInsets.of(compat.getInsets(WindowInsetsCompat.Type.displayCutout()))))
            }
            area = edges.inset(area)
            val visible = area.intersect(Rect(0f, 0f, view.width.toFloat(), view.height.toFloat()))
            return if (visible.isEmpty) null else visible
        }

    fun windowDidChange() {
        val newActivity = if (view.isAttachedToWindow) view.context.findActivity() else null
        if (newActivity !== activity) {
            detach()
            activity = newActivity
        }
        geometryDidChange()
    }

    fun geometryDidChange() {
        val shape = DisplayShape.of(view)
        obstructions = ScreenObstructions(
            occlusions = shape?.cutouts.orEmpty(),
            topLeft = shape?.topLeft ?: 0f,
            topRight = shape?.topRight ?: 0f,
            bottomLeft = shape?.bottomLeft ?: 0f,
            bottomRight = shape?.bottomRight ?: 0f,
            density = view.resources.displayMetrics.density,
        )
        val activity = activity ?: return
        val area = screenArea ?: return
        if (area.width <= 0 || area.height <= 0) return
        updateOrientation(activity)
        val awaitsOrientation = isPhone && matchesWindow && preferredOrientation == null
        if (awaitsRotation || awaitsOrientation) {
            handler.removeCallbacks(reportTask)
            reportPending = false
            return
        }
        if (activity.isInMultiWindowMode) {
            if (reportPending && area.size == scheduledArea) return
            scheduledArea = area.size
            handler.removeCallbacks(reportTask)
            handler.postDelayed(reportTask, RESIZE_SETTLE)
            reportPending = true
        } else if (!reportPending) {
            reportPending = true
            handler.post(reportTask)
        }
    }

    fun setTurnsLandscape(turns: Boolean) {
        if (turns == turnsLandscape) return
        turnsLandscape = turns
        geometryDidChange()
    }

    fun setPreferredOrientation(orientation: Int?) {
        if (orientation == preferredOrientation) return
        preferredOrientation = orientation
        geometryDidChange()
    }

    fun setMatchesWindow(matches: Boolean) {
        if (matches == matchesWindow) return
        matchesWindow = matches
        geometryDidChange()
    }

    fun detach() {
        handler.removeCallbacks(reportTask)
        handler.removeCallbacks(rotationRefused)
        reportPending = false
        reportedArea = null
        reportedScale = null
        reportedWindow = null
        awaitsRotation = false
        val activity = activity ?: return
        SessionOrientation.update(activity, null)
        this.activity = null
    }

    private fun reportArea() {
        val activity = activity ?: return
        val area = screenArea ?: return
        val scale = view.resources.displayMetrics.density
        val window = view.rootView
        if (activity.isInMultiWindowMode) {
            val size = Size(window.width / scale, window.height / scale)
            if (size != reportedWindow) {
                reportedWindow = size
                windowResized(size)
            }
        }
        val size = Size(area.width / scale, area.height / scale)
        if (size == reportedArea && scale == reportedScale) return
        reportedArea = size
        reportedScale = scale
        val screen = screenPixels(activity)
        report(size, scale, Size(screen.x / scale, screen.y / scale))
    }

    private val isPhone: Boolean
        get() = view.resources.configuration.smallestScreenWidthDp < LARGE_SCREEN_WIDTH

    private fun windowCoversScreen(activity: Activity): Boolean {
        if (activity.isInMultiWindowMode) return false
        val screen = screenPixels(activity)
        val window = view.rootView
        return (abs(window.width - screen.x) <= 1 && abs(window.height - screen.y) <= 1) ||
            (abs(window.width - screen.y) <= 1 && abs(window.height - screen.x) <= 1)
    }

    private fun updateOrientation(activity: Activity) {
        val honored = !activity.isInMultiWindowMode && !(Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA && !isPhone)
        val orientation = when {
            !honored -> null
            isPhone -> preferredOrientation
            turnsLandscape -> if (windowCoversScreen(activity)) ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE else null
            else -> null
        }
        val requested = SessionOrientation.update(activity, orientation)
        val landscape = view.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        awaitsRotation = (requested || awaitsRotation) && orientation?.let { !SessionOrientation.allows(it, landscape) } ?: false
        if (!awaitsRotation) {
            handler.removeCallbacks(rotationRefused)
        } else if (requested) {
            handler.removeCallbacks(rotationRefused)
            handler.postDelayed(rotationRefused, ROTATION_TIMEOUT)
        }
    }

    private companion object {
        const val LARGE_SCREEN_WIDTH = 600
        const val RESIZE_SETTLE = 300L
        const val ROTATION_TIMEOUT = 1_500L

        fun screenPixels(activity: Activity): Point = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = activity.windowManager.maximumWindowMetrics.bounds
            Point(bounds.width(), bounds.height())
        } else {
            Point().also {
                @Suppress("DEPRECATION")
                activity.windowManager.defaultDisplay.getRealSize(it)
            }
        }
    }
}

object SessionOrientation {
    private val lockedActivities = WeakHashMap<Activity, Int>()

    fun update(activity: Activity, orientation: Int?): Boolean {
        if (lockedActivities[activity] == orientation) return false
        if (orientation != null) {
            lockedActivities[activity] = orientation
        } else {
            lockedActivities.remove(activity)
        }
        activity.requestedOrientation = orientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        return orientation != null
    }

    fun allows(orientation: Int, landscape: Boolean): Boolean = when (orientation) {
        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
        ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
        ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE,
        -> landscape
        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
        ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT,
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
        ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT,
        -> !landscape
        else -> true
    }
}
