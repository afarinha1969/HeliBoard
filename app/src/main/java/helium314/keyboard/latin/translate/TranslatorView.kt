// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.translate

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
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
    private val input = TextView(context)
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
        input.ellipsize = TextUtils.TruncateAt.START
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

    fun setInput(text: String, cursor: Int) {
        if (text.isEmpty()) {
            val hint = SpannableStringBuilder("│ " + context.getString(R.string.translator_hint))
            hint.setSpan(ForegroundColorSpan(hintColor), 1, hint.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            input.text = hint
            return
        }
        val c = cursor.coerceIn(0, text.length)
        val sb = SpannableStringBuilder(text.substring(0, c)).append("│").append(text.substring(c))
        sb.setSpan(ForegroundColorSpan(colorAccent()), c, c + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        input.text = sb
    }

    private fun colorAccent() = Settings.getValues().mColors.get(ColorType.TOOL_BAR_KEY)

    fun setStatus(message: String?) {
        status.text = message
        status.visibility = if (message.isNullOrEmpty()) GONE else VISIBLE
    }
}
