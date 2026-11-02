// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.translate

import android.text.Editable
import android.text.Selection
import android.text.SpannableStringBuilder
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest

/**
 * Input connection backed by an internal text buffer. While the translator is active, the keyboard
 * (including gesture typing and suggestions) writes into this buffer instead of the app's text field.
 */
class TranslatorInputConnection(
    view: View,
    private val onChanged: () -> Unit,
    private val onEnter: () -> Unit,
    /** like InputMethodService.onUpdateSelection: oldSelStart, oldSelEnd, newSelStart, newSelEnd, composingStart, composingEnd */
    private val onSelectionUpdate: (Int, Int, Int, Int, Int, Int) -> Unit,
) : BaseInputConnection(view, true) {

    private val buffer = SpannableStringBuilder()
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var reportedSelStart = 0
    private var reportedSelEnd = 0
    private var reportPending = false
    private var closed = false
    private val reportRunnable = Runnable {
        reportPending = false
        if (closed) return@Runnable
        val s = Selection.getSelectionStart(buffer).coerceIn(0, buffer.length)
        val e = Selection.getSelectionEnd(buffer).coerceIn(0, buffer.length)
        val oldS = reportedSelStart
        val oldE = reportedSelEnd
        reportedSelStart = s
        reportedSelEnd = e
        onSelectionUpdate(oldS, oldE, s, e, getComposingSpanStart(buffer), getComposingSpanEnd(buffer))
    }

    /** stop reporting to the keyboard (translator closed) */
    fun close() {
        closed = true
        handler.removeCallbacks(reportRunnable)
    }

    /** place the cursor, e.g. after the user tapped inside the translator field */
    fun moveCursorTo(offset: Int) {
        val o = offset.coerceIn(0, buffer.length)
        if (Selection.getSelectionStart(buffer) == o && Selection.getSelectionEnd(buffer) == o) return
        Selection.setSelection(buffer, o)
        changed(true)
    }

    init { Selection.setSelection(buffer, 0) }

    override fun getEditable(): Editable = buffer

    fun text(): String = buffer.toString()

    fun cursor(): Int = Selection.getSelectionEnd(buffer).coerceIn(0, buffer.length)

    fun clear() {
        BaseInputConnection.removeComposingSpans(buffer)
        buffer.clear()
        buffer.clearSpans()
        Selection.setSelection(buffer, 0)
        reportedSelStart = 0
        reportedSelEnd = 0
    }

    private fun <T> changed(result: T): T {
        onChanged()
        if (!reportPending && !closed) {
            reportPending = true
            handler.post(reportRunnable)
        }
        return result
    }

    override fun commitText(text: CharSequence?, newCursorPosition: Int) =
        changed(super.commitText(text, newCursorPosition))

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int) =
        changed(super.setComposingText(text, newCursorPosition))

    override fun finishComposingText() = changed(super.finishComposingText())

    override fun setComposingRegion(start: Int, end: Int) = changed(super.setComposingRegion(start, end))

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int) =
        changed(super.deleteSurroundingText(beforeLength, afterLength))

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int) =
        changed(super.deleteSurroundingTextInCodePoints(beforeLength, afterLength))

    override fun setSelection(start: Int, end: Int): Boolean {
        val len = buffer.length
        Selection.setSelection(buffer, start.coerceIn(0, len), end.coerceIn(0, len))
        return changed(true)
    }

    override fun performEditorAction(actionCode: Int): Boolean {
        onEnter()
        return true
    }

    override fun performContextMenuAction(id: Int): Boolean {
        if (id == android.R.id.selectAll) {
            Selection.setSelection(buffer, 0, buffer.length)
            return changed(true)
        }
        return false
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return true
        val start = Selection.getSelectionStart(buffer).coerceIn(0, buffer.length)
        val end = Selection.getSelectionEnd(buffer).coerceIn(0, buffer.length)
        val a = minOf(start, end)
        val b = maxOf(start, end)
        when (event.keyCode) {
            KeyEvent.KEYCODE_DEL -> {
                BaseInputConnection.removeComposingSpans(buffer)
                if (a != b) buffer.delete(a, b)
                else if (a > 0) buffer.delete(Character.offsetByCodePoints(buffer, a, -1), a)
            }
            KeyEvent.KEYCODE_FORWARD_DEL -> {
                BaseInputConnection.removeComposingSpans(buffer)
                if (a != b) buffer.delete(a, b)
                else if (a < buffer.length) buffer.delete(a, Character.offsetByCodePoints(buffer, a, 1))
            }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { onEnter(); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> if (a > 0) Selection.setSelection(buffer, Character.offsetByCodePoints(buffer, a, -1))
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (b < buffer.length) Selection.setSelection(buffer, Character.offsetByCodePoints(buffer, b, 1))
            KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_DPAD_UP -> Selection.setSelection(buffer, 0)
            KeyEvent.KEYCODE_MOVE_END, KeyEvent.KEYCODE_DPAD_DOWN -> Selection.setSelection(buffer, buffer.length)
            else -> {
                val chars = event.characters
                val unicode = event.unicodeChar
                when {
                    chars != null -> return commitText(chars, 1)
                    unicode != 0 && !Character.isISOControl(unicode) -> return commitText(String(Character.toChars(unicode)), 1)
                    else -> return true
                }
            }
        }
        return changed(true)
    }

    override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText {
        val et = ExtractedText()
        et.text = buffer.toString()
        et.startOffset = 0
        et.partialStartOffset = -1
        et.partialEndOffset = -1
        et.selectionStart = Selection.getSelectionStart(buffer).coerceAtLeast(0)
        et.selectionEnd = Selection.getSelectionEnd(buffer).coerceAtLeast(0)
        return et
    }

    override fun requestCursorUpdates(cursorUpdateMode: Int) = false
}
