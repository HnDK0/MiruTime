package dev.mirutime.app.features.webview.data

import android.webkit.JavascriptInterface
import android.webkit.WebView
import dev.mirutime.core.log.MiruLog
import dev.mirutime.core.api.Outcome
import dev.mirutime.translator.MetadataTranslationRequest
import dev.mirutime.translator.TranslationManager
import dev.mirutime.translator.TranslationUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject

// Must match the page-side MAX_INFLIGHT in PAGE_TRANSLATOR_JS.
private const val MAX_INFLIGHT_BATCHES = 3

/**
 * Kotlin half of the in-app browser translator, registered as the
 * `MiruTranslate` JavaScript interface.
 *
 * The page is only served while a session is [active]: before the first tap and
 * after Original / navigation every call from page scripts is ignored. Each
 * session gets a generation; a result that comes back after deactivation (or
 * after a newer session started) is dropped instead of painting stale text.
 *
 * Batches run on [Dispatchers.IO], results are delivered back with
 * `evaluateJavascript` on the WebView thread. Up to [MAX_INFLIGHT_BATCHES]
 * page batches may run at once (the page uses the same cap); each one is a
 * single `translateMetadata` call whose provider splits it by the configured
 * character budget and runs those chunks with the configured concurrency.
 *
 * A failed batch is reported once (toast) and dropped on the page without
 * ending the session: the remaining queue and other in-flight batches keep
 * running. Only [deactivate], [close] or a newer [activate] (new generation)
 * abort everything.
 *
 * A web page is metadata, not a chapter: the batch goes through
 * [TranslationManager.translateMetadata], which honors a free provider but
 * never spends an LLM or paid provider. The resolved provider is still passed
 * as the preference, and the prompt is forwarded for completeness (free
 * providers ignore it).
 */
class PageTranslationBridge(
    private val manager: TranslationManager,
    private val onBusyChanged: (Boolean) -> Unit,
    private val onApplied: () -> Unit,
    private val onFailed: () -> Unit,
) {
    private data class Session(
        val generation: Int,
        val config: PageTranslationConfig,
        val chapterKey: String,
    )

    @Volatile
    private var webView: WebView? = null

    @Volatile
    private var active = false

    @Volatile
    private var generation = 0

    @Volatile
    private var session: Session? = null

    private val errorNotified = AtomicBoolean(false)
    // Matches the page-side MAX_INFLIGHT. The semaphore also keeps a script
    // that calls the interface directly from stacking provider requests.
    private val translationSemaphore = Semaphore(MAX_INFLIGHT_BATCHES)
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(supervisor + Dispatchers.IO)

    fun attach(view: WebView) {
        webView = view
    }

    /** Opens the page-side gate and returns the generation to pass to JS. */
    fun activate(config: PageTranslationConfig, chapterKey: String): Int {
        generation += 1
        errorNotified.set(false)
        scope.coroutineContext.cancelChildren()
        session = Session(generation, config, chapterKey)
        active = true
        return generation
    }

    /** Closes the gate and cancels every in-flight batch. */
    fun deactivate() {
        active = false
        generation += 1
        session = null
        scope.coroutineContext.cancelChildren()
    }

    fun close() {
        deactivate()
        webView = null
        supervisor.cancel()
    }

    @JavascriptInterface
    fun translateAsync(batchId: Int, jsonTexts: String) {
        if (!active || batchId <= 0) return
        val current = session ?: return
        val texts = runCatching {
            val array = JSONArray(jsonTexts)
            List(array.length()) { index -> array.optString(index, "") }
        }.getOrNull() ?: return
        val units = texts.mapIndexedNotNull { index, text ->
            text.takeIf { it.isNotBlank() }?.let { TranslationUnit(id = "u$index", text = it) }
        }
        if (units.isEmpty()) return

        scope.launch {
            val outcome = try {
                translationSemaphore.withPermit {
                    manager.translateMetadata(
                        MetadataTranslationRequest(
                            bookId = 0L,
                            key = current.chapterKey,
                            units = units,
                            source = current.config.source,
                            target = current.config.target,
                            provider = current.config.provider,
                            prompt = current.config.prompt,
                        ),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                MiruLog.w("webview", "page translation batch failed", error)
                null
            }
            if (!active || session?.generation != current.generation) return@launch
            when (outcome) {
                is Outcome.Ok -> deliver(batchId, units, outcome.value.translations, current.generation)
                is Outcome.Err, null -> fail(batchId, current.generation, outcome)
            }
        }
    }

    /** Busy state and queue idle notifications from the page. */
    @JavascriptInterface
    fun onState(state: String, generation: Int) {
        if (!active || this.generation != generation) return
        val busy = state == "working"
        webView?.post {
            if (active && this.generation == generation) onBusyChanged(busy)
        }
    }

    private fun deliver(
        batchId: Int,
        units: List<TranslationUnit>,
        translations: Map<String, String>,
        generation: Int,
    ) {
        val payload = JSONObject()
        units.forEach { unit ->
            translations[unit.id]?.takeIf { it.isNotBlank() }?.let { translated ->
                payload.put(unit.text, translated)
            }
        }
        // org.json leaves U+2028/U+2029 unescaped and they would split the JS
        // literal; the rare node containing one stays untranslated.
        val json = payload.toString()
            .replace('\u2028', ' ')
            .replace('\u2029', ' ')
        val view = webView ?: return
        view.post {
            if (!active || this.generation != generation) return@post
            runCatching {
                view.evaluateJavascript(
                    "window.__miruPageTranslator && window.__miruPageTranslator.applyResult($batchId, $json)",
                    null,
                )
            }
            // At least one string came back: the toolbar may show Original.
            if (payload.length() > 0) onApplied()
        }
    }

    private fun fail(batchId: Int, generation: Int, outcome: Outcome<*>?) {
        if (!active || this.generation != generation) return
        if (outcome is Outcome.Err) {
            MiruLog.w("webview", "page translation failed: ${outcome.error}")
        }
        // Drop only this batch on the page: its entry and slot are freed and
        // the rest of the queue keeps running. The session stays active.
        val view = webView ?: return
        if (errorNotified.compareAndSet(false, true)) {
            view.post { onFailed() }
        }
        view.post {
            // A newer generation may have started while this was queued; do
            // not let a stale batch id hit a fresh batch in the new session.
            if (!active || this.generation != generation) return@post
            runCatching {
                view.evaluateJavascript(
                    "window.__miruPageTranslator && window.__miruPageTranslator.failResult($batchId)",
                    null,
                )
            }
        }
    }
}
