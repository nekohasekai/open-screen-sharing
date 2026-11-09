package io.nekohasekai.screensharing.sessionui

import android.graphics.Matrix
import android.hardware.input.InputManager
import android.icu.text.BreakIterator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.Selection
import android.text.SpannableStringBuilder
import android.util.TypedValue
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.inputmethod.EditorInfoCompat
import io.nekohasekai.screensharing.session.SessionController
import kotlin.math.max
import kotlin.math.min

class KeyInputProxy(
    private val view: RemoteScreenView,
    private val controller: SessionController,
) {
    private val density = view.resources.displayMetrics.density
    private val inputMethodManager = view.context.getSystemService(InputMethodManager::class.java)
    private val inputManager = view.context.getSystemService(InputManager::class.java)
    private val document = SpannableStringBuilder()
    private val screenLocation = IntArray(2)
    private var typedText = ""
    private var batchDepth = 0
    private var isFirstResponder = false
    private var isImeVisible = false
    private var isOnScreenKeyboardUp = false
    private var monitorsCursor = false
    private var preImeEvent: KeyEvent? = null
    private var observedTree: ViewTreeObserver? = null
    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { keyboardWillChangeFrame() }
    private val keyboardListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) {}

        override fun onInputDeviceChanged(deviceId: Int) {}

        override fun onInputDeviceRemoved(deviceId: Int) {
            val connected = inputManager.inputDeviceIds.any { id ->
                val device = inputManager.getInputDevice(id) ?: return@any false
                !device.isVirtual && device.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC
            }
            if (connected) return
            controller.setUsesHardwareKeyboard(false)
        }
    }

    init {
        Selection.setSelection(document, 0)
        if (Build.VERSION.SDK_INT >= 33) view.isAutoHandwritingEnabled = false
        view.addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    observedTree = view.viewTreeObserver.also { it.addOnGlobalLayoutListener(layoutListener) }
                    inputManager.registerInputDeviceListener(keyboardListener, Handler(Looper.getMainLooper()))
                }

                override fun onViewDetachedFromWindow(v: View) {
                    observedTree?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(layoutListener)
                    observedTree = null
                    inputManager.unregisterInputDeviceListener(keyboardListener)
                    isImeVisible = false
                    if (isFirstResponder) resign()
                }
            },
        )
    }

    fun onCheckIsTextEditor(): Boolean = true

    fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        batchDepth = 0
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        outAttrs.initialSelStart = Selection.getSelectionStart(document)
        outAttrs.initialSelEnd = Selection.getSelectionEnd(document)
        outAttrs.initialCapsMode = 0
        EditorInfoCompat.setInitialSurroundingText(outAttrs, document)
        return Connection()
    }

    fun dispatchKeyEventPreIme(event: KeyEvent): Boolean {
        preImeEvent = event
        return handlePress(event)
    }

    fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event === preImeEvent) return false
        return handlePress(event)
    }

    private fun handlePress(event: KeyEvent): Boolean {
        if (event.isSystem && event.keyCode != KeyEvent.KEYCODE_MENU) return false
        return controller.handlePress(event)
    }

    fun setKeyboardVisible(visible: Boolean) {
        if (!view.isAttachedToWindow) return
        if (visible) {
            if (!isFirstResponder) becomeFirstResponder()
            showSoftInput()
            view.post { if (isFirstResponder && !isImeVisible) showSoftInput() }
        } else if (isFirstResponder) {
            resign()
            val insetsController = insetsController()
            if (insetsController != null) {
                insetsController.hide(WindowInsetsCompat.Type.ime())
            } else {
                inputMethodManager.hideSoftInputFromWindow(view.windowToken, 0)
            }
        }
    }

    fun onWindowFocusChanged(hasFocus: Boolean) {
        keyboardWillChangeFrame()
        if (hasFocus && isFirstResponder && !isImeVisible) showSoftInput()
    }

    private fun becomeFirstResponder() {
        isFirstResponder = true
        view.takeKeyFocus()
        controller.keyboardVisibilityDidChange(true)
    }

    private fun resign() {
        isFirstResponder = false
        isOnScreenKeyboardUp = false
        dropTypedText()
        inputMethodManager.restartInput(view)
        controller.keyboardVisibilityDidChange(false)
    }

    private fun showSoftInput() {
        val insetsController = insetsController()
        if (insetsController != null) {
            insetsController.show(WindowInsetsCompat.Type.ime())
        } else {
            inputMethodManager.showSoftInput(view, 0)
        }
    }

    private fun insetsController() = view.context.findActivity()?.window?.let { WindowCompat.getInsetsController(it, view) }

    private fun keyboardWillChangeFrame() {
        val insets = ViewCompat.getRootWindowInsets(view) ?: return
        val visible = insets.isVisible(WindowInsetsCompat.Type.ime())
        val height = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom - insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
        if (isFirstResponder) {
            val isUp = visible && height > ON_SCREEN_KEYS_HEIGHT * density
            if (isUp && !isOnScreenKeyboardUp) controller.setUsesHardwareKeyboard(false)
            isOnScreenKeyboardUp = isUp
        }
        if (visible == isImeVisible) return
        isImeVisible = visible
        if (visible) {
            if (!isFirstResponder && view.isFocused && view.hasWindowFocus()) becomeFirstResponder()
        } else if (isFirstResponder && view.hasWindowFocus()) {
            resign()
        }
    }

    private val markedRange: IntRange?
        get() {
            val start = BaseInputConnection.getComposingSpanStart(document)
            val end = BaseInputConnection.getComposingSpanEnd(document)
            if (start < 0 || end <= start) return null
            return start until end
        }

    private fun deleteBackward() {
        var start = min(Selection.getSelectionStart(document), Selection.getSelectionEnd(document))
        var end = max(Selection.getSelectionStart(document), Selection.getSelectionEnd(document))
        val marked = markedRange
        if (start == end) {
            if (start <= 0) {
                if (marked == null) deleteBeforeDocument(1)
                return
            }
            val characters = BreakIterator.getCharacterInstance()
            characters.setText(document.toString())
            start = characters.preceding(start).takeIf { it != BreakIterator.DONE } ?: (start - 1)
        }
        if (marked != null) {
            start = max(start, marked.first)
            end = min(end, marked.last + 1)
            if (end <= start) return
        }
        document.delete(start, end)
        if (markedRange == null) BaseInputConnection.removeComposingSpans(document)
        Selection.setSelection(document, start)
        documentDidChange()
    }

    private fun deleteBeforeDocument(count: Int) {
        if (!controller.replaceText(graphemes(typedText).size + count, typedText)) {
            dropTypedText()
            reportSelection()
        }
    }

    private fun excessBefore(length: Int, codePoints: Boolean): Int {
        if (markedRange != null) return 0
        val start = min(Selection.getSelectionStart(document), Selection.getSelectionEnd(document))
        val available = if (codePoints) Character.codePointCount(document, 0, start) else start
        return max(0, length - available)
    }

    private fun documentDidChange() {
        if (batchDepth > 0) return
        sendTypedText()
        reportSelection()
    }

    private fun sendTypedText() {
        val marked = markedRange
        val text = if (marked == null) document.toString() else document.toString().removeRange(marked)
        if (text == typedText) return
        val previous = graphemes(typedText)
        val current = graphemes(text)
        val kept = previous.zip(current).takeWhile { (old, new) -> old == new }.size
        typedText = text
        if (!controller.replaceText(previous.size - kept, current.drop(kept).joinToString(""))) dropTypedText()
    }

    fun forgetTypedText() {
        if (typedText.isEmpty()) return
        if (markedRange != null) {
            dropTypedText()
            reportSelection()
            return
        }
        dropTypedText()
        inputMethodManager.restartInput(view)
    }

    private fun dropTypedText() {
        typedText = ""
        val marked = markedRange
        if (marked == null) {
            BaseInputConnection.removeComposingSpans(document)
            document.clear()
            Selection.setSelection(document, 0)
            return
        }
        val selectionStart = Selection.getSelectionStart(document) - marked.first
        val selectionEnd = Selection.getSelectionEnd(document) - marked.first
        val markedText = document.substring(marked.first, marked.last + 1)
        BaseInputConnection.removeComposingSpans(document)
        document.replace(0, document.length, markedText)
        BaseInputConnection.setComposingSpans(document)
        val start = selectionStart.coerceIn(0, document.length)
        Selection.setSelection(document, start, selectionEnd.coerceIn(start, document.length))
    }

    private fun reportSelection() {
        inputMethodManager.updateSelection(
            view,
            Selection.getSelectionStart(document),
            Selection.getSelectionEnd(document),
            BaseInputConnection.getComposingSpanStart(document),
            BaseInputConnection.getComposingSpanEnd(document),
        )
        if (monitorsCursor) updateCursorAnchorInfo()
    }

    private fun updateCursorAnchorInfo() {
        val point = view.surface.viewPoint(controller.pointerPosition)
        val height = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, CARET_LINE_HEIGHT, view.resources.displayMetrics)
        view.getLocationOnScreen(screenLocation)
        val builder = CursorAnchorInfo.Builder()
            .setMatrix(Matrix().apply { setTranslate(screenLocation[0].toFloat(), screenLocation[1].toFloat()) })
            .setSelectionRange(Selection.getSelectionStart(document), Selection.getSelectionEnd(document))
            .setInsertionMarkerLocation(point.x, point.y - height / 2, point.y + height / 2, point.y + height / 2, CursorAnchorInfo.FLAG_HAS_VISIBLE_REGION)
        markedRange?.let { builder.setComposingText(it.first, document.subSequence(it.first, it.last + 1)) }
        inputMethodManager.updateCursorAnchorInfo(view, builder.build())
    }

    private inner class Connection : BaseInputConnection(view, true) {
        override fun getEditable(): Editable = document

        override fun beginBatchEdit(): Boolean {
            batchDepth += 1
            return true
        }

        override fun endBatchEdit(): Boolean {
            if (batchDepth > 0) batchDepth -= 1
            if (batchDepth == 0) documentDidChange()
            return batchDepth > 0
        }

        private inline fun <T> batch(body: () -> T): T {
            beginBatchEdit()
            try {
                return body()
            } finally {
                endBatchEdit()
            }
        }

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean = batch { super.commitText(text, newCursorPosition) }

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean = batch { super.setComposingText(text, newCursorPosition) }

        override fun setComposingRegion(start: Int, end: Int): Boolean = batch { super.setComposingRegion(start, end) }

        override fun finishComposingText(): Boolean = batch { super.finishComposingText() }

        override fun setSelection(start: Int, end: Int): Boolean = batch { super.setSelection(start, end) }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            val excess = excessBefore(beforeLength, codePoints = false)
            val deleted = batch { super.deleteSurroundingText(beforeLength, afterLength) }
            if (excess > 0) deleteBeforeDocument(excess)
            return deleted
        }

        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
            val excess = excessBefore(beforeLength, codePoints = true)
            val deleted = batch { super.deleteSurroundingTextInCodePoints(beforeLength, afterLength) }
            if (excess > 0) deleteBeforeDocument(excess)
            return deleted
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            val down = event.action == KeyEvent.ACTION_DOWN
            if (!event.hasNoModifiers()) {
                controller.handlePress(event)
                return true
            }
            when (event.keyCode) {
                KeyEvent.KEYCODE_DEL -> if (down) deleteBackward()
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> if (down) commitText("\n", 1)
                else -> controller.handlePress(event)
            }
            return true
        }

        override fun performContextMenuAction(id: Int): Boolean {
            if (id != android.R.id.paste && id != android.R.id.pasteAsPlainText) return false
            controller.pasteFromDevice()
            return true
        }

        override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean {
            monitorsCursor = cursorUpdateMode and InputConnection.CURSOR_UPDATE_MONITOR != 0
            if (cursorUpdateMode and InputConnection.CURSOR_UPDATE_IMMEDIATE != 0) updateCursorAnchorInfo()
            return true
        }

        override fun requestCursorUpdates(cursorUpdateMode: Int, cursorUpdateFilter: Int): Boolean = requestCursorUpdates(cursorUpdateMode or cursorUpdateFilter)

        override fun closeConnection() {
            super.closeConnection()
            batchDepth = 0
            monitorsCursor = false
        }
    }

    private companion object {
        const val ON_SCREEN_KEYS_HEIGHT = 100f
        const val CARET_LINE_HEIGHT = 24f

        fun graphemes(text: String): List<String> {
            val characters = BreakIterator.getCharacterInstance()
            characters.setText(text)
            val result = ArrayList<String>()
            var start = characters.first()
            var end = characters.next()
            while (end != BreakIterator.DONE) {
                result.add(text.substring(start, end))
                start = end
                end = characters.next()
            }
            return result
        }
    }
}
