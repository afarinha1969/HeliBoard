// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.translate

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.graphics.Canvas
import android.graphics.Paint
import android.text.style.ForegroundColorSpan
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.dpToPx

/** Translation bar shown above the suggestion strip: "Português ⇄ Inglês" row and the "Escrever para traduzir" field. */
class TranslatorView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {

    var controller: TranslatorController? = null

    private val sourceLabel = TextView(context)
    private val targetLabel = TextView(context)
    private val swapLabel = TextView(context)
    private val closeButton = ImageButton(context)
    private val input = CursorTextView(context)
    private val status = TextView(context)
    private val fieldBackground: View
    private var textColor = 0
    private var hintColor = 0

    init {
        orientation = VERTICAL
        visibility = GONE
        val res = resources
        val pad = 8.dpToPx(res)

        // row 1: languages
        val langRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad * 2, 0, pad * 2, 0)
        }
        listOf(sourceLabel, swapLabel, targetLabel).forEach {
            it.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            it.gravity = Gravity.CENTER_VERTICAL
            it.setOnClickListener { controller?.swapDirection() }
        }
        swapLabel.text = "⇄"
        swapLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        swapLabel.gravity = Gravity.CENTER
        sourceLabel.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        targetLabel.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        langRow.addView(sourceLabel, LayoutParams(0, 36.dpToPx(res), 1f))
        langRow.addView(swapLabel, LayoutParams(56.dpToPx(res), 36.dpToPx(res)))
        langRow.addView(targetLabel, LayoutParams(0, 36.dpToPx(res), 1f))
        addView(langRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // row 2: close button + input field
        val inputRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, pad, pad / 2)
        }
        closeButton.setImageResource(R.drawable.ic_arrow_left)
        closeButton.background = null
        closeButton.contentDescription = context.getString(R.string.translator_close)
        closeButton.setOnClickListener { controller?.requestClose() }
        inputRow.addView(closeButton, LayoutParams(44.dpToPx(res), 44.dpToPx(res)))

        val field = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad * 3 / 2, pad / 2, pad * 3 / 2, pad / 2)
        }
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        input.maxLines = 3
        input.isVerticalScrollBarEnabled = false
        input.setOnTouchListener { _, event -> onInputTouch(event) }
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        status.maxLines = 2
        status.setTypeface(status.typeface, Typeface.ITALIC)
        status.setPadding(pad, 0, 0, 0)
        status.visibility = GONE
        status.setOnClickListener { controller?.retry() }
        field.addView(input, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        field.addView(status, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        field.minimumHeight = 40.dpToPx(res)
        inputRow.addView(field, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(inputRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        fieldBackground = field
    }

    /** apply current keyboard theme colors */
    fun applyColors() {
        val colors = Settings.getValues().mColors
        setBackgroundColor(colors.get(ColorType.STRIP_BACKGROUND))
        textColor = colors.get(ColorType.KEY_TEXT)
        hintColor = ColorUtils.setAlphaComponent(textColor, 140)
        val accent = colors.get(ColorType.TOOL_BAR_KEY)
        listOf(sourceLabel, swapLabel, targetLabel).forEach { it.setTextColor(accent) }
        closeButton.setColorFilter(accent)
        input.setTextColor(textColor)
        status.setTextColor(hintColor)
        fieldBackground.background = GradientDrawable().apply {
            cornerRadius = 8.dpToPx(resources).toFloat()
            setColor(colors.get(ColorType.KEY_BACKGROUND))
        }
    }

    fun setDirection(toEnglish: Boolean) {
        val pt = context.getString(R.string.translator_portuguese)
        val en = context.getString(R.string.translator_english)
        sourceLabel.text = if (toEnglish) pt else en
        targetLabel.text = if (toEnglish) en else pt
    }

    /** cursor position in the buffer while it is displayed, -1 if showing the hint */
    private var displayedCursor = -1

    private fun onInputTouch(event: android.view.MotionEvent): Boolean {
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_MOVE, android.view.MotionEvent.ACTION_UP -> {
                if (displayedCursor < 0) return true // hint shown, nothing to place
                val offset = input.getOffsetForPosition(event.x, event.y)
                if (offset < 0) return true
                controller?.moveCursorTo(offset)
            }
        }
        return true
    }

    private fun scrollToCursor(displayOffset: Int) {
        input.post {
            val layout = input.layout ?: return@post
            val o = displayOffset.coerceIn(0, input.text.length)
            val line = layout.getLineForOffset(o)
            val top = layout.getLineTop(line)
            val bottom = layout.getLineBottom(line)
            val visible = input.height - input.totalPaddingTop - input.totalPaddingBottom
            var y = input.scrollY
            if (top < y) y = top
            else if (bottom > y + visible) y = bottom - visible
            input.scrollTo(0, y.coerceAtLeast(0))
        }
    }

    fun setInput(text: String, cursor: Int) {
        input.cursorColor = colorAccent()
        if (text.isEmpty()) {
            displayedCursor = -1
            input.scrollTo(0, 0)
            val hint = SpannableStringBuilder(context.getString(R.string.translator_hint))
            hint.setSpan(ForegroundColorSpan(hintColor), 0, hint.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            input.text = hint
            input.setCursor(0)
            return
        }
        val c = cursor.coerceIn(0, text.length)
        input.text = text
        input.setCursor(c)
        displayedCursor = c
        scrollToCursor(c)
    }

    /** TextView that draws a thin blinking cursor exactly at the character position. */
    private class CursorTextView(context: Context) : TextView(context) {
        var cursorColor = 0
        private var cursorOffset = -1
        private var cursorVisible = true
        private val paint = Paint()
        private val width = 2.dpToPx(resources).coerceAtLeast(2).toFloat()
        private val blink = object : Runnable {
            override fun run() {
                cursorVisible = !cursorVisible
                invalidate()
                postDelayed(this, BLINK_MS)
            }
        }

        fun setCursor(offset: Int) {
            cursorOffset = offset
            cursorVisible = true
            removeCallbacks(blink)
            if (isAttachedToWindow) postDelayed(blink, BLINK_MS)
            invalidate()
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            removeCallbacks(blink)
            postDelayed(blink, BLINK_MS)
        }

        override fun onDetachedFromWindow() {
            removeCallbacks(blink)
            super.onDetachedFromWindow()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val layout = layout ?: return
            if (cursorOffset < 0 || !cursorVisible) return
            val o = cursorOffset.coerceIn(0, text.length)
            val line = layout.getLineForOffset(o)
            val x = layout.getPrimaryHorizontal(o) + totalPaddingLeft
            val top = layout.getLineTop(line) + totalPaddingTop
            val bottom = layout.getLineBottom(line) + totalPaddingTop
            paint.color = cursorColor
            // the canvas is already translated by the scroll position
            canvas.drawRect(x - width / 2, top.toFloat(), x + width / 2, bottom.toFloat(), paint)
        }

        companion object { private const val BLINK_MS = 500L }
    }

    private fun colorAccent() = Settings.getValues().mColors.get(ColorType.TOOL_BAR_KEY)

    fun setStatus(message: String?) {
        status.text = message
        status.visibility = if (message.isNullOrEmpty()) GONE else VISIBLE
    }
}
