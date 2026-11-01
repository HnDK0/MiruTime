package dev.mirutime.app.debug

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import dev.mirutime.app.features.webview.data.PAGE_TRANSLATOR_JS
import org.json.JSONArray
import org.json.JSONObject

/**
 * Debug-only, no-production-UI harness for [PAGE_TRANSLATOR_JS].
 *
 * Loads a local fixture into a WebView, installs the real page translator and a
 * fake `window.MiruTranslate` bridge that returns deterministic `RU:` strings.
 * The fixture's JS runner drives start/restore/applyResult and reports every
 * assertion back through [TestBridge.report].
 *
 * Launch:
 * `adb shell am start -n dev.mirutime.app/dev.mirutime.app.debug.TranslatorDebugActivity`
 */
class TranslatorDebugActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var resultsView: TextView
    private var started = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        resultsView = TextView(this).apply {
            textSize = 12f
            setPadding(24, 24, 24, 24)
            setTextColor(Color.DKGRAY)
            text = "RUNNING"
        }

        WebView.setWebContentsDebuggingEnabled(true)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            addJavascriptInterface(TestBridge(), "TestBridge")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    if (started) return
                    started = true
                    view.evaluateJavascript(PAGE_TRANSLATOR_JS, null)
                    view.evaluateJavascript(
                        "window.__miruDebugRun && window.__miruDebugRun()",
                        null,
                    )
                }
            }
        }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(
            resultsView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        root.addView(
            webView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        setContentView(root)

        val html = runCatching {
            assets.open("translator_debug.html").bufferedReader().use { it.readText() }
        }.getOrElse { error ->
            val message = "cannot read translator_debug.html: ${error.message}"
            Log.e(TAG, message, error)
            showResult("FAIL 0/0", message)
            return
        }
        title = "TranslatorDebug - running"
        webView.loadDataWithBaseURL("https://debug.local/", html, "text/html", "UTF-8", null)
    }

    override fun onDestroy() {
        webView.removeJavascriptInterface("TestBridge")
        webView.destroy()
        super.onDestroy()
    }

    private fun showResult(summary: String, details: String) {
        runOnUiThread {
            title = summary
            resultsView.text = if (details.isBlank()) summary else "$summary\n\n$details"
            val passed = summary.startsWith("PASS")
            resultsView.setTextColor(if (passed) Color.rgb(0, 128, 0) else Color.RED)
        }
    }

    inner class TestBridge {

        /**
         * Echoes a deterministic translation for every requested text and feeds
         * the result back into the real page translator.
         */
        @JavascriptInterface
        fun translateAsync(batchId: Int, json: String) {
            val texts = runCatching {
                val array = JSONArray(json)
                List(array.length()) { index -> array.optString(index, "") }
            }.getOrNull() ?: return
            if (texts.isEmpty()) return

            val payload = JSONObject()
            texts.forEach { text -> payload.put(text, "RU:$text") }
            val quoted = JSONObject.quote(payload.toString())
            val script = """
                (function () {
                  try {
                    if (window.MiruTranslate && window.MiruTranslate.onState) {
                      window.MiruTranslate.onState('working', window.MiruTranslate.gen || 1);
                    }
                  } catch (e) {}
                  if (window.__miruPageTranslator) {
                    window.__miruPageTranslator.applyResult($batchId, $quoted);
                  }
                  try {
                    if (window.MiruTranslate && window.MiruTranslate.onState) {
                      window.MiruTranslate.onState('idle', window.MiruTranslate.gen || 1);
                    }
                  } catch (e) {}
                })();
            """.trimIndent()

            Log.i(TAG, "translateAsync batch=$batchId texts=${texts.size}")
            webView.post {
                runCatching { webView.evaluateJavascript(script, null) }
                    .onFailure { Log.e(TAG, "applyResult injection failed", it) }
            }
        }

        /** Renders the fixture's assertion array on screen and in logcat. */
        @JavascriptInterface
        fun report(json: String) {
            val array = runCatching { JSONArray(json) }.getOrNull()
            if (array == null) {
                Log.e(TAG, "report is not valid JSON: $json")
                showResult("FAIL 0/0", "Invalid report JSON: $json")
                return
            }

            var passed = 0
            val details = StringBuilder()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val ok = item.optBoolean("ok", false)
                if (ok) passed++
                details.append(if (ok) "PASS " else "FAIL ")
                    .append(item.optString("name", "unnamed"))
                val detail = item.optString("detail", "")
                if (detail.isNotBlank()) details.append(" - ").append(detail)
                details.append('\n')
            }

            val total = array.length()
            val summary = "${if (total > 0 && passed == total) "PASS" else "FAIL"} $passed/$total"
            val detailText = details.toString().trimEnd()
            Log.i(TAG, "$summary\n$detailText")
            showResult(summary, detailText)
        }
    }

    private companion object {
        const val TAG = "TranslatorDebug"
    }
}
