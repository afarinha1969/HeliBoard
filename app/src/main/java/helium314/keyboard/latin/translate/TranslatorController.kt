// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.translate

import android.os.Handler
import android.os.Looper
import android.view.inputmethod.InputConnection
import androidx.core.content.edit
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import java.util.concurrent.Executors

/**
 * Translator mode: the keyboard types into an internal buffer, and the Claude translation of that buffer
 * is written into the app's text field, updated after each short pause. The position of the inserted
 * translation is tracked explicitly (apps like WhatsApp may drop composing spans).
 */
class TranslatorController(private val ime: LatinIME) {

    var isActive = false
        private set

    private var view: TranslatorView? = null
    private var connection: TranslatorInputConnection? = null
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var requestId = 0
    private var inFlight = false
    private var lastTranslatedSource = ""
    private var finalizePending = false
    private var toEnglish = ime.prefs().getBoolean(PREF_DIRECTION_TO_ENGLISH, true)
    private val translateRunnable = Runnable { startTranslation() }

    /** where our translation starts in the app's field, and what we put there */
    private var regionStart = -1
    private var regionText = ""
    /** space to put before the translation when it follows other text */
    private var regionPrefix = ""

    /** Replace the translation we inserted earlier in the app's field with [newText]. */
    private fun replaceRegion(newText: String) {
        val real = ime.realInputConnection ?: return
        val insert = if (newText.isEmpty()) "" else regionPrefix + newText
        if (insert.isEmpty() && regionText.isEmpty()) return
        real.beginBatchEdit()
        try {
            var start = regionStart
            val et = real.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
            val full = et?.text?.toString()
            if (full != null) {
                val off = et.startOffset
                if (start < 0) start = off + maxOf(et.selectionStart, et.selectionEnd)
                if (regionText.isNotEmpty()) {
                    val local = start - off
                    val matches = local >= 0 && local + regionText.length <= full.length &&
                        full.regionMatches(local, regionText, 0, regionText.length)
                    if (!matches) {
                        val idx = full.lastIndexOf(regionText)
                        if (idx >= 0) start = off + idx
                        else { start = off + maxOf(et.selectionStart, et.selectionEnd); regionText = "" }
                    }
                }
            }
            if (start < 0) start = 0
            real.finishComposingText()
            real.setSelection(start, start + regionText.length)
            real.commitText(insert, 1)
            regionStart = start
            regionText = insert
        } finally {
            real.endBatchEdit()
        }
    }

    fun attachView(newView: TranslatorView?) {
        if (newView === view) return
        if (isActive) ime.closeTranslator()
        view?.controller = null
        view = newView
        connection = null
        newView?.controller = this
        newView?.visibility = android.view.View.GONE
    }

    fun canActivate() = view != null

    val inputConnection: InputConnection? get() = connection

    /** Called by LatinIME after it has committed any composing text in the app's field. */
    fun activate() {
        val v = view ?: return
        val ic = TranslatorInputConnection(v, ::onTextChanged, ::onEnter) { oss, ose, nss, nse, cs, ce ->
            if (isActive) ime.onTranslatorSelectionUpdate(oss, ose, nss, nse, cs, ce)
        }
        connection = ic
        val real = ime.realInputConnection
        regionStart = ime.lastRealSelectionEnd
        regionText = ""
        val before = real?.getTextBeforeCursor(1, 0)
        regionPrefix = if (!before.isNullOrEmpty() && !before[before.length - 1].isWhitespace()) " " else ""
        lastTranslatedSource = ""
        finalizePending = false
        inFlight = false
        requestId++
        isActive = true
        v.applyColors()
        v.setDirection(toEnglish)
        v.setStatus(if (apiKey().isBlank()) ime.getString(R.string.translator_no_key) else null)
        v.setInput("", 0)
        v.visibility = android.view.View.VISIBLE
    }

    /**
     * @param finalize true to keep the translation in the app's field (waiting for a pending translation if needed),
     * false to just stop (e.g. the app's field changed).
     */
    fun deactivate(finalize: Boolean) {
        if (!isActive) return
        isActive = false
        handler.removeCallbacks(translateRunnable)
        view?.visibility = android.view.View.GONE
        view?.setStatus(null)
        val source = connection?.text().orEmpty()
        connection?.close()
        connection = null
        if (!finalize) {
            requestId++ // drop any result still in flight
            return
        }
        when {
            source.isBlank() -> {
                requestId++
                replaceRegion("")
            }
            source == lastTranslatedSource && !inFlight -> {}
            else -> {
                finalizePending = true
                startTranslation(source)
            }
        }
    }

    fun requestClose() = ime.closeTranslator()

    /** user tapped / dragged inside the translator field */
    fun moveCursorTo(offset: Int) {
        if (!isActive) return
        connection?.moveCursorTo(offset)
    }

    fun swapDirection() {
        toEnglish = !toEnglish
        ime.prefs().edit { putBoolean(PREF_DIRECTION_TO_ENGLISH, toEnglish) }
        view?.setDirection(toEnglish)
        lastTranslatedSource = ""
        if (connection?.text().orEmpty().isNotBlank()) {
            handler.removeCallbacks(translateRunnable)
            startTranslation()
        }
    }

    fun retry() {
        if (!isActive) return
        lastTranslatedSource = ""
        handler.removeCallbacks(translateRunnable)
        startTranslation()
    }

    private fun onEnter() = ime.closeTranslator()

    private fun onTextChanged() {
        val ic = connection ?: return
        val text = ic.text()
        view?.setInput(text, ic.cursor())
        handler.removeCallbacks(translateRunnable)
        if (text.isBlank()) {
            requestId++
            inFlight = false
            lastTranslatedSource = ""
            replaceRegion("")
            return
        }
        if (text.trim() == lastTranslatedSource.trim() && !inFlight) return
        handler.postDelayed(translateRunnable, DEBOUNCE_MS)
    }

    private fun apiKey() = ime.prefs().getString(Settings.PREF_TRANSLATOR_API_KEY, Defaults.PREF_TRANSLATOR_API_KEY).orEmpty()

    private fun model() = ime.prefs().getString(Settings.PREF_TRANSLATOR_MODEL, Defaults.PREF_TRANSLATOR_MODEL)
        ?.takeIf { it.isNotBlank() } ?: Defaults.PREF_TRANSLATOR_MODEL

    private fun startTranslation(source: String = connection?.text().orEmpty()) {
        if (source.isBlank()) return
        val key = apiKey()
        if (key.isBlank()) {
            view?.setStatus(ime.getString(R.string.translator_no_key))
            finalizePending = false
            return
        }
        val id = ++requestId
        inFlight = true
        if (isActive) view?.setStatus(ime.getString(R.string.translator_working))
        val model = model()
        val direction = toEnglish
        executor.execute {
            val result = ClaudeTranslator.translate(key, model, source, direction)
            handler.post { onResult(id, source, result) }
        }
    }

    private fun onResult(id: Int, source: String, result: ClaudeTranslator.Result) {
        if (id != requestId) return
        inFlight = false
        val out = result.text
        if (out != null) {
            lastTranslatedSource = source
            replaceRegion(out)
            if (isActive) view?.setStatus(null)
        } else if (isActive) {
            view?.setStatus(result.error + " · " + ime.getString(R.string.translator_tap_retry))
        }
        if (finalizePending) {
            finalizePending = false
            return
        }
        // text changed while the request was running
        val current = connection?.text().orEmpty()
        if (isActive && out != null && current.isNotBlank() && current.trim() != source.trim()) {
            handler.removeCallbacks(translateRunnable)
            handler.postDelayed(translateRunnable, DEBOUNCE_MS)
        }
    }

    companion object {
        private const val DEBOUNCE_MS = 600L
        private const val PREF_DIRECTION_TO_ENGLISH = "translator_direction_to_english"
    }
}
