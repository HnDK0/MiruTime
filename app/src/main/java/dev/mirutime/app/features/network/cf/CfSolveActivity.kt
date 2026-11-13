package dev.mirutime.app.features.network.cf

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dev.mirutime.app.MiruApp
import dev.mirutime.app.R
import dev.mirutime.app.features.settings.data.AppLanguage
import dev.mirutime.app.features.settings.data.AppSettings
import dev.mirutime.core.host.CfChallenge
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Notification "Open source" bridge for a download blocked by CF/login/429.
 * Keeps a visible activity alive while [AndroidCfChallengeSolver] runs its own
 * CfChallengeActivity, then re-arms the download. The clearance is already in
 * the shared WebView-backed store, so there is nothing to copy. Non-exported:
 * only the app's own PendingIntent can launch it.
 */
class CfSolveActivity : ComponentActivity() {

    // Same per-app language as the rest of the app: these screens are separate
    // activities, so the activity base context has to be wrapped here too.
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase, AppSettings.appLanguage))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = CfSolveRequestPolicy.from(
            bookId = intent.getLongExtra(EXTRA_BOOK_ID, -1L),
            rawUrl = intent.getStringExtra(EXTRA_SOURCE_URL),
        )
        val app = application as? MiruApp
        if (request == null || app == null) {
            toast(R.string.cf_solve_failed)
            finish()
            return
        }
        setContentView(
            TextView(this).apply {
                text = getString(R.string.cf_solve_progress)
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(Color.DKGRAY)
                setBackgroundColor(Color.WHITE)
            },
        )
        lifecycleScope.launch {
            val solution = try {
                withContext(Dispatchers.IO) {
                    app.pluginGraph.cfSolver.solveVisible(
                        CfChallenge(
                            host = request.host,
                            url = request.url,
                            status = 429,
                            markers = emptyList(),
                            referer = null,
                            // Must match the UA the download client sends, or
                            // the clearance is bound to the wrong fingerprint.
                            userAgent = app.pluginGraph.userAgentFor(request.url),
                        ),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (solution == null) {
                // Cancelled/timed out/unsafe: the rows stay NEEDS_SOURCE_ACTION.
                toast(R.string.cf_solve_failed)
                finish()
                return@launch
            }
            val resumed = runCatching {
                app.downloadEngine.resumeAfterSourceAction(request.bookId)
            }
            if (resumed.isFailure) {
                toast(R.string.cf_solve_failed)
            }
            finish()
        }
    }

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_LONG).show()
    }

    companion object {
        const val EXTRA_BOOK_ID = "dev.mirutime.app.CF_BOOK_ID"
        const val EXTRA_SOURCE_URL = "dev.mirutime.app.CF_SOURCE_URL"
    }
}

// Pure gate for the notification bridge: only an http(s) URL with a real host
// and a positive book id may reach the solver. Everything else is a no-op.
data class CfSolveRequest(
    val bookId: Long,
    val host: String,
    val url: String,
)

object CfSolveRequestPolicy {
    fun from(bookId: Long, rawUrl: String?): CfSolveRequest? {
        if (bookId <= 0) return null
        val url = rawUrl?.trim().orEmpty()
        if (url.isEmpty()) return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return CfSolveRequest(bookId = bookId, host = host, url = url)
    }
}
