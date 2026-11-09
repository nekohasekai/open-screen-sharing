package io.nekohasekai.screensharing.sessionui

import android.graphics.Paint
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toAndroidRectF
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.window.core.layout.WindowSizeClass
import io.nekohasekai.screensharing.R
import io.nekohasekai.screensharing.app.AppTheme
import io.nekohasekai.screensharing.model.AppSettings
import io.nekohasekai.screensharing.session.SessionMemory
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

enum class SessionTutorial(val rawValue: String) {
    PressToDrag("pressToDrag"),
    CustomInput("customInput"),
    NativeTouch("nativeTouch"),
    ;

    companion object {
        fun unseen(): List<SessionTutorial> {
            val seen = AppSettings.seenTutorials
            return entries.filter { it.rawValue !in seen }
        }
    }
}

@Composable
fun SessionTutorialView(pages: List<SessionTutorial>, onDone: () -> Unit) {
    if (pages.isEmpty()) return
    var index by rememberSaveable { mutableIntStateOf(0) }
    val current = index.coerceIn(0, pages.lastIndex)
    val page = pages[current]
    val windowSizeClass = currentWindowAdaptiveInfo().windowSizeClass
    val stacked = !windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND) &&
        windowSizeClass.isHeightAtLeastBreakpoint(WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND)
    val isLast = current == pages.lastIndex

    val button: @Composable () -> Unit = {
        Button(
            onClick = {
                AppSettings.seenTutorials = AppSettings.seenTutorials + page.rawValue
                if (isLast) onDone() else index = current + 1
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(if (isLast) R.string.ok else R.string.screen_next_tip))
        }
    }

    Dialog(
        onDismissRequest = {
            AppSettings.seenTutorials = AppSettings.seenTutorials + pages.map { it.rawValue }
            onDone()
        },
        properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.padding(16.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Box(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
            ) {
                if (stacked) {
                    Column(Modifier.width(ILLUSTRATION_WIDTH.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        TutorialIllustration(pages, current)
                        TutorialText(page)
                        button()
                    }
                } else {
                    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        TutorialIllustration(pages, current)
                        Column(
                            Modifier
                                .width(244.dp)
                                .fillMaxHeight(),
                        ) {
                            TutorialText(page)
                            Spacer(
                                Modifier
                                    .weight(1f)
                                    .heightIn(min = 16.dp),
                            )
                            button()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TutorialIllustration(pages: List<SessionTutorial>, index: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        AppTheme(darkTheme = true) {
            Crossfade(
                pages[index],
                Modifier
                    .size(ILLUSTRATION_WIDTH.dp, ILLUSTRATION_HEIGHT.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clearAndSetSemantics {},
                label = "illustration",
            ) { page ->
                when (page) {
                    SessionTutorial.PressToDrag -> PressToDragIllustration()
                    SessionTutorial.CustomInput -> CustomInputIllustration()
                    SessionTutorial.NativeTouch -> NativeTouchIllustration()
                }
            }
        }
        if (pages.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (i in pages.indices) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .background(
                                if (i == index) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                CircleShape,
                            ),
                    )
                }
            }
        }
    }
}

@Composable
private fun TutorialText(page: SessionTutorial) {
    val (title, detail) = when (page) {
        SessionTutorial.PressToDrag -> stringResource(R.string.screen_press_gesture) to stringResource(R.string.screen_press_gesture_detail)
        SessionTutorial.CustomInput -> stringResource(R.string.screen_custom_keys_and_text) to stringResource(
            R.string.screen_custom_input_detail,
            stringResource(R.string.settings),
            stringResource(R.string.settings_keyboard),
            stringResource(R.string.settings),
            stringResource(R.string.settings_quick_input),
        )
        SessionTutorial.NativeTouch -> stringResource(R.string.screen_native_touch_for_mac) to if (SessionMemory.defaultUsesNativeTouch) {
            stringResource(R.string.screen_native_touch_supported)
        } else {
            stringResource(R.string.screen_native_touch_detail, stringResource(R.string.model_control), stringResource(R.string.chrome_native_touch))
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private const val ILLUSTRATION_WIDTH = 310f
private const val ILLUSTRATION_HEIGHT = 210f

private object TutorialPalette {
    val wallpaper = listOf(Color(0xFF24476E), Color(0xFF5E3B78))
    val window = Color(0xFFF5F5F7)
    val line = Color(0xFFD1D1D6)
    val lights = listOf(Color(0xFFFF5F57), Color(0xFFFEBC2E), Color(0xFF28C840))
    val apps = listOf(Color(0xFF2196F3), Color(0xFF4CAF50), Color(0xFFFF9800), Color(0xFFE91E63), Color(0xFF3F51B5))
    val macApps = listOf(Color(0xFF00BCD4), Color(0xFFFF9800), Color(0xFF4CAF50), Color(0xFF9C27B0), Color(0xFFF44336))

    val arrow = Path().apply {
        moveTo(0f, 0f)
        lineTo(0f, 17f)
        lineTo(4.2f, 13.2f)
        lineTo(7f, 19.5f)
        lineTo(9.6f, 18.4f)
        lineTo(6.9f, 12.2f)
        lineTo(12.4f, 12.2f)
        close()
    }

    fun DrawScope.drawArrow(tip: Offset, factor: Float = 1f, alpha: Float = 1f) {
        translate(tip.x, tip.y) {
            scale(factor, pivot = Offset.Zero) {
                drawPath(arrow, Color.Black.copy(alpha = alpha))
                drawPath(arrow, Color.White.copy(alpha = alpha), style = Stroke(1.2f, join = StrokeJoin.Round))
            }
        }
    }

    fun DrawScope.drawFinger(center: Offset, radius: Float, alpha: Float = 1f) {
        drawCircle(Color.White.copy(alpha = 0.35f * alpha), radius, center)
        drawCircle(Color.White.copy(alpha = 0.9f * alpha), radius, center, style = Stroke(1.5f))
    }
}

@Composable
private fun PressToDragIllustration() {
    val reduceMotion = remember { !animatorsEnabled() }
    var time by remember { mutableFloatStateOf(if (reduceMotion) 2.2f else 0f) }
    if (!reduceMotion) {
        LaunchedEffect(Unit) {
            val start = withFrameNanos { it }
            while (true) {
                withFrameNanos { now -> time = ((now - start) / 1e9 % PRESS_PERIOD).toFloat() }
            }
        }
    }
    Canvas(Modifier.fillMaxSize()) {
        val t = time
        scale(size.width / ILLUSTRATION_WIDTH, pivot = Offset.Zero) {
            drawPressToDrag(t)
        }
    }
}

private const val PRESS_PERIOD = 5.2
private val pressWindowOrigin = Offset(34f, 44f)
private val pressFingerOrigin = Offset(196f, 148f)
private val pressTravel = Offset(84f, 8f)
private val pressPointerOffset = Offset(72f, 10f)

private fun DrawScope.drawPressToDrag(t: Float) {
    fun progress(from: Float, to: Float): Float = ((t - from) / (to - from)).coerceIn(0f, 1f)
    fun eased(x: Float): Float = if (x < 0.5f) 4 * x * x * x else 1 - (-2 * x + 2).pow(3) / 2

    val move = eased(progress(1.45f, 2.75f))
    val lift = progress(3.1f, 3.3f)
    val press = progress(0.8f, 0.9f) - progress(3.1f, 3.2f)
    val reset = t >= 4.6f
    val fade = if (reset) progress(4.6f, 4.9f) else 1 - progress(4.3f, 4.6f)
    val offset = if (reset) Offset.Zero else Offset(pressTravel.x * move, pressTravel.y * move - 12 * sin(PI * move).toFloat())
    val bounds = Size(ILLUSTRATION_WIDTH, ILLUSTRATION_HEIGHT)

    drawRect(Brush.linearGradient(TutorialPalette.wallpaper, Offset.Zero, Offset(bounds.width, bounds.height)), size = bounds)
    drawRect(Color.White.copy(alpha = 0.2f), size = Size(bounds.width, 14f))

    val window = pressWindowOrigin + offset
    val frame = Rect(window, Size(132f, 88f))
    drawIntoCanvas { canvas ->
        val layer = canvas.nativeCanvas.saveLayerAlpha(Rect(Offset.Zero, bounds).toAndroidRectF(), (fade * 255).roundToInt())
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TutorialPalette.window.toArgb()
            setShadowLayer(3 + 4 * press, 0f, 3 + 6 * press, Color.Black.copy(alpha = 0.3f + 0.2f * press).toArgb())
        }
        canvas.nativeCanvas.drawRoundRect(frame.toAndroidRectF(), 8f, 8f, shadow)
        clipPath(Path().apply { addRoundRect(RoundRect(frame, CornerRadius(8f))) }) {
            val bar = (227 - 19 * press) / 255
            drawRect(Color(bar, bar, (232 - 16 * press) / 255), frame.topLeft, Size(frame.width, 19f))
            for ((i, color) in TutorialPalette.lights.withIndex()) {
                drawCircle(color, 3.3f, Offset(frame.left + 11 + i * 10, frame.top + 9.5f))
            }
            for ((y, width) in listOf(32f to 90f, 44f to 108f, 56f to 70f, 68f to 96f)) {
                drawRoundRect(TutorialPalette.line, Offset(frame.left + 12, frame.top + y), Size(width, 5f), CornerRadius(2.5f))
            }
        }
        canvas.nativeCanvas.restoreToCount(layer)
    }

    val pointer = window + pressPointerOffset
    val pointerCenter = pointer + TutorialPalette.arrow.getBounds().center
    with(TutorialPalette) { drawArrow(pointer, alpha = fade) }

    fun drawIndicator(opacity: Float, hold: Float, pulse: Float?) {
        drawCircle(Color.White.copy(alpha = 0.2f * press * opacity), 20f, pointerCenter)
        drawCircle(Color.Black.copy(alpha = 0.3f * opacity), 20f, pointerCenter, style = Stroke(3f))
        if (pulse != null) {
            drawCircle(Color.White.copy(alpha = (1 - pulse) * opacity), 20 * (1 + 0.4f * pulse), pointerCenter, style = Stroke(2f))
        }
        drawIntoCanvas { canvas ->
            val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 3f
                strokeCap = Paint.Cap.ROUND
                color = Color.White.copy(alpha = opacity).toArgb()
                setShadowLayer(2f, 0f, 0f, Color.Black.copy(alpha = 0.4f * opacity).toArgb())
            }
            canvas.nativeCanvas.drawArc(Rect(pointerCenter, 20f).toAndroidRectF(), -90f, 360 * hold, false, ring)
        }
    }

    fun drawFinger(opacity: Float, scale: Float) {
        with(TutorialPalette) { drawFinger(pressFingerOrigin + offset, 17 * scale, opacity) }
    }

    if (t >= 0.5f && t < 3.3f) {
        val appear = progress(0.5f, 0.65f)
        drawFinger(min(appear, 1 - lift), if (t < 1) 1.25f - 0.25f * appear else 1 + 0.2f * lift)
    }
    if (t >= 0.65f && t < 3.3f) {
        drawIndicator(
            opacity = min(progress(0.65f, 0.7f), 1 - lift),
            hold = progress(0.65f, 0.8f),
            pulse = if (t >= 0.8f && t < 1.15f) progress(0.8f, 1.15f) else null,
        )
    }
}

@Composable
private fun NativeTouchIllustration() {
    val reduceMotion = remember { !animatorsEnabled() }
    var time by remember { mutableFloatStateOf(if (reduceMotion) 2.1f else 0f) }
    if (!reduceMotion) {
        LaunchedEffect(Unit) {
            val start = withFrameNanos { it }
            while (true) {
                withFrameNanos { now -> time = ((now - start) / 1e9 % NATIVE_TOUCH_PERIOD).toFloat() }
            }
        }
    }
    Canvas(Modifier.fillMaxSize()) {
        val t = time
        scale(size.width / ILLUSTRATION_WIDTH, pivot = Offset.Zero) {
            drawNativeTouch(t)
        }
    }
}

private const val NATIVE_TOUCH_PERIOD = 4.6
private val nativeTouchWindow = Rect(Offset(55f, 36f), Size(200f, 146f))
private val nativeTouchFocus = Offset(155f, 125f)
private val nativeTouchDirection = Offset(0.82f, -0.57f)
private const val NATIVE_TOUCH_SPREAD_FROM = 24f
private const val NATIVE_TOUCH_SPREAD_TO = 43f
private val nativeTouchSky = listOf(Color(0xFF6BA8EC), Color(0xFFCDE3FA))
private val nativeTouchSun = Color(0xFFFFCC5A)
private val nativeTouchMountain = Color(0xFF7384B8)
private val nativeTouchHill = Color(0xFF5C9E6B)

private fun DrawScope.drawNativeTouch(t: Float) {
    fun progress(from: Float, to: Float): Float = ((t - from) / (to - from)).coerceIn(0f, 1f)
    fun eased(x: Float): Float = if (x < 0.5f) 4 * x * x * x else 1 - (-2 * x + 2).pow(3) / 2

    val reset = t >= 4f
    val pinch = if (reset) 0f else eased(progress(0.75f, 1.95f))
    val spread = NATIVE_TOUCH_SPREAD_FROM + (NATIVE_TOUCH_SPREAD_TO - NATIVE_TOUCH_SPREAD_FROM) * pinch
    val zoom = spread / NATIVE_TOUCH_SPREAD_FROM
    val fade = if (reset) progress(4f, 4.3f) else 1 - progress(3.7f, 4f)
    val bounds = Size(ILLUSTRATION_WIDTH, ILLUSTRATION_HEIGHT)

    drawRect(Brush.linearGradient(TutorialPalette.wallpaper, Offset.Zero, Offset(bounds.width, bounds.height)), size = bounds)
    drawRect(Color.White.copy(alpha = 0.2f), size = Size(bounds.width, 14f))

    val frame = nativeTouchWindow
    val content = Rect(frame.left, frame.top + 19, frame.right, frame.bottom)
    drawIntoCanvas { canvas ->
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TutorialPalette.window.toArgb()
            setShadowLayer(3f, 0f, 3f, Color.Black.copy(alpha = 0.3f).toArgb())
        }
        canvas.nativeCanvas.drawRoundRect(frame.toAndroidRectF(), 8f, 8f, shadow)
    }
    clipPath(Path().apply { addRoundRect(RoundRect(frame, CornerRadius(8f))) }) {
        clipRect(content.left, content.top, content.right, content.bottom) {
            drawIntoCanvas { canvas ->
                val layer = canvas.nativeCanvas.saveLayerAlpha(content.toAndroidRectF(), (fade * 255).roundToInt())
                translate(nativeTouchFocus.x, nativeTouchFocus.y) {
                    scale(zoom, pivot = Offset.Zero) {
                        translate(content.left - nativeTouchFocus.x, content.top - nativeTouchFocus.y) {
                            val width = content.width
                            val height = content.height
                            drawRect(Brush.verticalGradient(nativeTouchSky, startY = 0f, endY = height), size = content.size)
                            drawOval(nativeTouchSun, Offset(139f, 19f), Size(22f, 22f))
                            val mountains = Path().apply {
                                moveTo(0f, 88f)
                                lineTo(48f, 40f)
                                lineTo(82f, 70f)
                                lineTo(122f, 30f)
                                lineTo(172f, 76f)
                                lineTo(width, 58f)
                                lineTo(width, height)
                                lineTo(0f, height)
                                close()
                            }
                            drawPath(mountains, nativeTouchMountain)
                            val hills = Path().apply {
                                moveTo(0f, 104f)
                                quadraticTo(50f, 80f, 110f, 96f)
                                quadraticTo(160f, 108f, width, 92f)
                                lineTo(width, height)
                                lineTo(0f, height)
                                close()
                            }
                            drawPath(hills, nativeTouchHill)
                        }
                    }
                }
                canvas.nativeCanvas.restoreToCount(layer)
            }
        }
        drawRect(Color(227, 227, 227), frame.topLeft, Size(frame.width, 19f))
        for ((i, color) in TutorialPalette.lights.withIndex()) {
            drawCircle(color, 3.3f, Offset(frame.left + 11 + i * 10, frame.top + 9.5f))
        }
    }

    if (t >= 0.4f && t < 2.45f) {
        val appear = progress(0.4f, 0.55f)
        val lift = progress(2.25f, 2.45f)
        val radius = 17 * if (t < 1) 1.25f - 0.25f * appear else 1 + 0.2f * lift
        for (sign in listOf(-1f, 1f)) {
            with(TutorialPalette) {
                drawFinger(nativeTouchFocus + nativeTouchDirection * (sign * spread), radius, min(appear, 1 - lift))
            }
        }
    }
}

@Composable
private fun CustomInputIllustration() {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val keySize = with(density) { 24.dp.toSp() }
    val stepSize = with(density) { 17.dp.toSp() }
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.surfaceContainer),
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            for (label in listOf("Ctrl", "J")) {
                KeyCap(Modifier.defaultMinSize(58.dp, 54.dp).padding(horizontal = 8.dp)) {
                    Text(label, color = Color.White, fontSize = keySize, fontWeight = FontWeight.Medium, maxLines = 1)
                }
            }
            Text("/", Modifier.padding(horizontal = 6.dp), color = colors.onSurfaceVariant, fontSize = keySize, fontWeight = FontWeight.Medium)
            KeyCap(Modifier.size(58.dp, 54.dp)) {
                Icon(Icons.Outlined.TextFields, contentDescription = null, Modifier.size(26.dp), tint = Color.White)
            }
        }
        Icon(Icons.Filled.ArrowDownward, contentDescription = null, Modifier.size(20.dp), tint = colors.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (step in listOf("⌃C", "“hello”", "↩︎")) {
                Box(
                    Modifier
                        .height(40.dp)
                        .background(colors.surfaceContainerHighest, RoundedCornerShape(10.dp))
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(step, color = Color.White, fontSize = stepSize, fontWeight = FontWeight.Medium, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun KeyCap(modifier: Modifier, content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .background(colors.surfaceContainerHighest, RoundedCornerShape(12.dp))
            .border(2.dp, colors.primary, RoundedCornerShape(12.dp))
            .then(modifier),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
