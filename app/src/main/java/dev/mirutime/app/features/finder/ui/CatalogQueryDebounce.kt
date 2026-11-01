package dev.mirutime.app.features.finder.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// A typed catalogue query reaches the
// provider only after 1.5 s without typing — never one request per keystroke.
// Explicit actions (clear, sheet Apply, initial query, reload) bypass the timer.
internal const val CatalogSearchDebounceMillis = 1_500L

// Pure timer policy: the screen only owns the text field and `filters`; this
// class owns the pending window, so it is testable with virtual time.
internal class CatalogQueryDebouncer(
    private val scope: CoroutineScope,
    private val onApply: (String) -> Unit,
    private val delayMillis: Long = CatalogSearchDebounceMillis,
) {
    private var pending: Job? = null

    // Every keystroke restarts the window. An input already equal to the
    // applied query needs no request, so it schedules nothing.
    fun onInput(text: String, applied: String) {
        pending?.cancel()
        pending = null
        if (text == applied) return
        pending = scope.launch {
            delay(delayMillis)
            onApply(text)
        }
    }

    // Clear button / explicit apply: drop the timer and land the value now, so
    // there is never a late duplicate request.
    fun applyNow(text: String) {
        cancel()
        onApply(text)
    }

    fun cancel() {
        pending?.cancel()
        pending = null
    }
}
