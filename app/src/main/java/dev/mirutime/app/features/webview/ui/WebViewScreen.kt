package dev.mirutime.app.features.webview.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.PublicOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.mirutime.app.R
import dev.mirutime.uikit.LocalMiruExtraColors
import dev.mirutime.uikit.MiruRadius
import dev.mirutime.uikit.MiruSize
import dev.mirutime.uikit.MiruSpace
import dev.mirutime.uikit.AppBottomSheet
import dev.mirutime.uikit.MiruActionRow
import dev.mirutime.uikit.MiruCard
import dev.mirutime.uikit.MiruRowDivider
import dev.mirutime.uikit.SkeletonBar
import dev.mirutime.app.features.webview.data.PAGE_TRANSLATOR_JS
import dev.mirutime.app.features.webview.data.PageTranslationBridge
import dev.mirutime.app.features.webview.data.resolvePageTranslationFor
import dev.mirutime.core.log.MiruLog
import dev.mirutime.translator.TranslationManager
import java.util.Locale
import dev.mirutime.uikit.MiruTextField

// The in-app browser route. Query values are encoded at the call site.
// bookUrl/sourceId keep translation context when the user browses from a book
// or a source page; they stay fixed for the route, address edits do not clear
// them.
object WebViewRoute {
    const val Pattern = "webview?url={url}&title={title}&bookUrl={bookUrl}&sourceId={sourceId}"

    fun build(
        url: String,
        title: String,
        bookUrl: String = "",
        sourceId: String = "",
    ): String = buildString {
        append("webview?url=").append(Uri.encode(url))
        append("&title=").append(Uri.encode(title))
        append("&bookUrl=").append(Uri.encode(bookUrl))
        append("&sourceId=").append(Uri.encode(sourceId))
    }
}

// Adds https:// when the address has no scheme. Host:port input stays a host
// (Uri would read "example.com:8080" as a scheme named "example.com").
internal fun normalizeAddress(raw: String): String? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    val lower = text.lowercase()
    if (lower.startsWith("http:") || lower.startsWith("https:")) return text
    if (text.startsWith("//")) return "https:$text"
    // mailto:, tel:, intent:, ... and anything with an explicit :// scheme is
    // handed to an external app instead of being forced into https.
    val colon = text.indexOf(':')
    if (colon > 0) {
        val scheme = text.substring(0, colon)
        val tail = text.substring(colon + 1)
        val known = scheme.lowercase() in KnownExternalSchemes
        if (known || tail.startsWith("//")) return text
    }
    return "https://$text"
}

private val KnownExternalSchemes = setOf(
    "mailto", "tel", "sms", "smsto", "geo", "intent", "market", "magnet", "viber", "whatsapp", "tg",
)

private const val TRANSLATE_INTERFACE = "MiruTranslate"

// Native-client UA for a URL (per-host override -> preset registry -> browser
// default). cf_clearance is bound to the UA fingerprint, so the visible browser
// must present the same value the plugin requests use; null leaves the WebView
// default (unparseable URL, or a fake/local screen with no provider).
val LocalWebUserAgent = staticCompositionLocalOf<(String) -> String?> { { null } }

// A page load only ever adopts a non-blank UA; a blank URL never reaches the
// resolver.
internal fun webViewUserAgent(url: String?, resolve: (String) -> String?): String? =
    url?.takeIf { it.isNotBlank() }?.let(resolve)?.takeIf { it.isNotBlank() }

// History traversal bypasses shouldOverrideUrlLoading, so the destination host
// comes from the back/forward list; a step past either end has no target.
internal fun historyTargetIndex(currentIndex: Int, size: Int, step: Int): Int? =
    (currentIndex + step).takeIf { it in 0 until size }

// A concrete parameter gives lint's WebView check a resolvable type; the
// Compose `remember` expression type alone reads as the generic `T`.
private fun WebView.attachTranslationBridge(bridge: PageTranslationBridge) {
    addJavascriptInterface(bridge, TRANSLATE_INTERFACE)
}

private fun webViewPath(raw: String): String {
    val uri = Uri.parse(raw)
    return (uri.host.orEmpty() + uri.path.orEmpty()).lowercase()
}

private enum class BrowserSheet { None, Actions, ClearData }

// One WebView for a source's website. The shell header keeps the source name
// and host; this route owns the browser chrome: an editable address plate,
// navigation/history controls and page actions. System back and the header
// back both walk the page history first, then leave the route.
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewScreen(
    url: String,
    contentPadding: PaddingValues,
    translationManager: TranslationManager,
    bookUrl: String = "",
    sourceId: String = "",
    onClose: () -> Unit,
    onBackAction: ((() -> Unit)?) -> Unit,
    onUrlChange: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val extra = LocalMiruExtraColors.current
    val clipboard = LocalClipboardManager.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    // The same UA resolution the native client uses for this host.
    val resolveUserAgent = LocalWebUserAgent.current

    // The factory loads the live route, not the original deep link, so a
    // configuration change keeps the current SPA page instead of reloading
    // the URL the screen was opened with.
    var liveUrl by rememberSaveable(url) { mutableStateOf(url) }
    var address by rememberSaveable(url, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(url))
    }
    var addressFocused by remember(url) { mutableStateOf(false) }
    var loading by remember(url) { mutableStateOf(true) }
    var failedUrl by remember(url) { mutableStateOf<String?>(null) }
    var failed by remember(url) { mutableStateOf(false) }
    var hasLoadedOnce by remember(url) { mutableStateOf(false) }
    var pageTitle by remember(url) { mutableStateOf("") }
    var canGoBack by remember(url) { mutableStateOf(false) }
    var canGoForward by remember(url) { mutableStateOf(false) }
    var sheet by remember(url) { mutableStateOf(BrowserSheet.None) }
    var fullscreenView by remember(url) { mutableStateOf<View?>(null) }
    var fullscreenCallback by remember(url) {
        mutableStateOf<WebChromeClient.CustomViewCallback?>(null)
    }
    var pendingFileCallback by remember(url) {
        mutableStateOf<ValueCallback<Array<Uri>>?>(null)
    }
    var translated by remember(url) { mutableStateOf(false) }
    var translating by remember(url) { mutableStateOf(false) }
    // SPA routes update the address without a page load. Remember the last
    // document path so a route change can restart translation, while hash or
    // query-only history updates leave the current session alone. Persisted so
    // a recreation does not treat the restored route as a route change.
    var historyPath by rememberSaveable(url) { mutableStateOf(webViewPath(url)) }

    val currentOnUrlChange by rememberUpdatedState(onUrlChange)
    val currentClose by rememberUpdatedState(onClose)
    val currentBackAction by rememberUpdatedState(onBackAction)

    fun toast(messageRes: Int) {
        Toast.makeText(context, messageRes, Toast.LENGTH_SHORT).show()
    }

    // Bind the native UA before the request leaves the WebView. A cross-host
    // navigation (the browser follows a link/redirect to another source) gets
    // the target host's UA, not the one the route started with.
    fun applyUserAgent(view: WebView, target: String?) {
        val ua = webViewUserAgent(target, resolveUserAgent)
        // The UA value is never logged: only whether the host bound one.
        MiruLog.event("webview", "ua", "bound" to (ua != null))
        if (ua == null) return
        if (view.settings.userAgentString != ua) view.settings.userAgentString = ua
    }

    fun refreshHistory(view: WebView?) {
        canGoBack = view?.canGoBack() == true
        canGoForward = view?.canGoForward() == true
    }

    fun publishUrl(raw: String?) {
        val next = raw?.takeIf { it.isNotBlank() } ?: return
        liveUrl = next
        // Never overwrite a half-typed address; blur and navigation settle it.
        if (!addressFocused) {
            address = TextFieldValue(next, TextRange(next.length))
        }
        currentOnUrlChange(next)
    }

    fun removeFullscreenView() {
        fullscreenView?.let { view -> (view.parent as? ViewGroup)?.removeView(view) }
        fullscreenView = null
        fullscreenCallback = null
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val callback = pendingFileCallback
        pendingFileCallback = null
        if (callback == null) return@rememberLauncherForActivityResult
        val uris = if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            when {
                data?.clipData != null -> {
                    val clip = data.clipData!!
                    Array(clip.itemCount) { clip.getItemAt(it).uri }
                }
                data?.data != null -> arrayOf(data.data!!)
                else -> null
            }
        } else {
            null
        }
        callback.onReceiveValue(uris)
    }

    // The page translator stays dormant until the user taps Translate; the
    // bridge gate is what makes the exposed JS interface harmless before that.
    val translationBridge: PageTranslationBridge = remember(url) {
        PageTranslationBridge(
            manager = translationManager,
            onBusyChanged = { busy -> translating = busy },
            onApplied = { translated = true },
            onFailed = { toast(R.string.webview_translate_failed) },
        )
    }

    // A same-document route change keeps the old page's batches and queue
    // alive, so the new page can sit behind work for content that no longer
    // exists. Starting a fresh generation drops that work and walks the
    // current DOM; already translated spans simply stay in place.
    fun restartTranslationAfterRouteChange(view: WebView, nextUrl: String) {
        val nextPath = webViewPath(nextUrl)
        if (nextPath == historyPath) return
        historyPath = nextPath
        if (loading || failed || !(translated || translating)) return
        val config = resolvePageTranslationFor(
            bookUrl = bookUrl.ifBlank { null },
            sourceId = sourceId.ifBlank { null },
            deviceLanguage = Locale.getDefault().language,
        ) ?: return
        val generation = translationBridge.activate(config, chapterKey = nextUrl)
        translating = true
        view.evaluateJavascript(
            "window.__miruPageTranslator && window.__miruPageTranslator.start($generation)",
            null,
        )
    }

    val webView = remember(url) {
        WebView(context).apply {
            applyUserAgent(this, liveUrl)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            attachTranslationBridge(translationBridge)
            translationBridge.attach(this)
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    loading = true
                    failed = false
                    pageTitle = ""
                    // A new document starts untranslated; any late batch from
                    // the previous one is now behind the bridge generation.
                    translated = false
                    translating = false
                    translationBridge.deactivate()
                    view?.let { applyUserAgent(it, url ?: it.url) }
                    publishUrl(url ?: view?.url)
                    refreshHistory(view)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    loading = false
                    hasLoadedOnce = true
                    publishUrl(url ?: view?.url)
                    refreshHistory(view)
                    // Injected after the document settles, never during load.
                    // The script is inert until Translate is tapped.
                    view?.evaluateJavascript(PAGE_TRANSLATOR_JS, null)
                }

                // SPA history.pushState()/replaceState() fires neither page
                // callback, but the address field still has to follow the URL.
                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                    if (view != null && url != null) {
                        applyUserAgent(view, url)
                        restartTranslationAfterRouteChange(view, url)
                    }
                    publishUrl(url ?: view?.url)
                    refreshHistory(view)
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?,
                ) {
                    if (request?.isForMainFrame == true) {
                        loading = false
                        failed = true
                        failedUrl = request.url?.toString() ?: view?.url
                        translated = false
                        translating = false
                        translationBridge.deactivate()
                    }
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean {
                    val uri = request?.url ?: return false
                    val scheme = uri.scheme?.lowercase()
                    if (scheme == "http" || scheme == "https") {
                        // Set before the WebView starts the navigation.
                        view?.let { applyUserAgent(it, uri.toString()) }
                        return false
                    }
                    if (!startExternal(context, uri)) toast(R.string.webview_no_app)
                    return true
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onReceivedTitle(view: WebView?, title: String?) {
                    pageTitle = title?.trim().orEmpty()
                }

                override fun onShowFileChooser(
                    view: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?,
                ): Boolean {
                    if (filePathCallback == null) return false
                    pendingFileCallback?.onReceiveValue(null)
                    pendingFileCallback = filePathCallback
                    val intent = fileChooserIntent(fileChooserParams)
                    return try {
                        filePicker.launch(intent)
                        true
                    } catch (error: ActivityNotFoundException) {
                        pendingFileCallback = null
                        filePathCallback.onReceiveValue(null)
                        toast(R.string.webview_no_file_picker)
                        false
                    }
                }

                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                    if (view == null || callback == null) return
                    if (fullscreenView != null) {
                        callback.onCustomViewHidden()
                        return
                    }
                    (view.parent as? ViewGroup)?.removeView(view)
                    fullscreenView = view
                    fullscreenCallback = callback
                }

                override fun onHideCustomView() {
                    removeFullscreenView()
                }
            }
            // Exactly one initial load per WebView. On recreation this is the
            // saved route, so the current SPA page is restored, not the
            // original deep link.
            loadUrl(liveUrl)
        }
    }

    fun applyHistoryUserAgent(step: Int) {
        val history = webView.copyBackForwardList()
        val target = historyTargetIndex(history.currentIndex, history.size, step) ?: return
        applyUserAgent(webView, history.getItemAtIndex(target)?.url)
    }

    fun clearSiteData() {
        CookieManager.getInstance().removeAllCookies {
            webView.post {
                CookieManager.getInstance().flush()
                webView.clearCache(true)
                webView.reload()
            }
        }
        loading = true
        failed = false
        toast(R.string.webview_clear_done)
    }

    fun openAddress(raw: String) {
        val target = normalizeAddress(raw) ?: return
        val scheme = Uri.parse(target).scheme?.lowercase()
        if (scheme == "http" || scheme == "https") {
            liveUrl = target
            address = TextFieldValue(target, TextRange(target.length))
            pageTitle = ""
            failed = false
            failedUrl = null
            loading = true
            currentOnUrlChange(target)
            focusManager.clearFocus()
            keyboard?.hide()
            applyUserAgent(webView, target)
            webView.loadUrl(target)
        } else {
            if (!startExternal(context, Uri.parse(target))) toast(R.string.webview_no_app)
            focusManager.clearFocus()
            keyboard?.hide()
            address = TextFieldValue(liveUrl, TextRange(liveUrl.length))
        }
    }

    fun openExternalPage() {
        if (!startExternal(context, Uri.parse(liveUrl))) toast(R.string.webview_no_app)
    }

    fun togglePageTranslation() {
        if (translating || translated) {
            translated = false
            translating = false
            translationBridge.deactivate()
            webView.evaluateJavascript(
                "window.__miruPageTranslator && window.__miruPageTranslator.restore()",
                null,
            )
            return
        }
        if (loading || !hasLoadedOnce || failed) return
        val config = resolvePageTranslationFor(
            bookUrl = bookUrl.ifBlank { null },
            sourceId = sourceId.ifBlank { null },
            deviceLanguage = Locale.getDefault().language,
        )
        if (config == null) {
            toast(R.string.webview_translate_no_target)
            return
        }
        val generation = translationBridge.activate(config, chapterKey = liveUrl)
        // The active style only turns on once a batch actually lands; a page
        // where every batch failed must not pretend it was translated.
        translating = true
        webView.evaluateJavascript(
            "window.__miruPageTranslator && window.__miruPageTranslator.start($generation)",
            null,
        )
    }

    val goBack: () -> Unit = {
        if (webView.canGoBack()) {
            applyHistoryUserAgent(step = -1)
            webView.goBack()
            refreshHistory(webView)
        } else {
            currentClose()
        }
    }

    BackHandler(enabled = fullscreenView == null) { goBack() }

    DisposableEffect(webView) {
        currentBackAction(goBack)
        onDispose {
            currentBackAction(null)
            pendingFileCallback?.onReceiveValue(null)
            fullscreenView?.let { view -> (view.parent as? ViewGroup)?.removeView(view) }
            translationBridge.close()
            webView.removeJavascriptInterface(TRANSLATE_INTERFACE)
            CookieManager.getInstance().flush()
            webView.stopLoading()
            webView.webViewClient = WebViewClient()
            webView.destroy()
        }
    }

    val titleFallback = remember(liveUrl) {
        Uri.parse(liveUrl).host.orEmpty().ifBlank { liveUrl }
    }
    val pageLabel = pageTitle.ifBlank { titleFallback }
    val secure = liveUrl.startsWith("https://", ignoreCase = true)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(contentPadding),
    ) {
        MiruCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MiruSpace.M, vertical = MiruSpace.S),
            contentPadding = PaddingValues(MiruSpace.Xs),
        ) {
            MiruTextField(
                value = address,
                onValueChange = { address = it },
                singleLine = true,
                placeholder = stringResource(R.string.webview_address_hint),
                leadingIcon = {
                    Icon(
                        imageVector = if (secure) Icons.Outlined.Lock else Icons.Outlined.Public,
                        contentDescription = null,
                        tint = if (secure) extra.accent else extra.muted,
                        modifier = Modifier.size(MiruSize.IconCompact),
                    )
                },
                showClear = addressFocused,
                onClear = { address = TextFieldValue("", TextRange.Zero) },
                clearContentDescription = stringResource(R.string.webview_clear_address),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(onGo = { openAddress(address.text) }),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { state ->
                        if (state.isFocused) {
                            if (!addressFocused) {
                                address = address.copy(
                                    selection = TextRange(0, address.text.length),
                                )
                            }
                            addressFocused = true
                        } else if (addressFocused) {
                            addressFocused = false
                            // Dropping focus discards the draft: the field
                            // always mirrors the live page.
                            address = TextFieldValue(liveUrl, TextRange(liveUrl.length))
                        }
                    },
                plate = false,
            )
            Spacer(modifier = Modifier.height(MiruSpace.Xs))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = MiruSize.Touch),
            ) {
                BrowserIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.webview_back),
                    enabled = canGoBack,
                    onClick = {
                        focusManager.clearFocus()
                        keyboard?.hide()
                        goBack()
                    },
                )
                BrowserIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = stringResource(R.string.webview_forward),
                    enabled = canGoForward,
                    onClick = {
                        focusManager.clearFocus()
                        keyboard?.hide()
                        if (webView.canGoForward()) {
                            applyHistoryUserAgent(step = 1)
                            webView.goForward()
                            refreshHistory(webView)
                        }
                    },
                )
                BrowserIconButton(
                    icon = if (loading) Icons.Filled.Close else Icons.Filled.Refresh,
                    contentDescription = stringResource(
                        if (loading) R.string.webview_stop else R.string.webview_reload,
                    ),
                    tint = if (loading) extra.accent else null,
                    onClick = {
                        focusManager.clearFocus()
                        keyboard?.hide()
                        if (loading) {
                            webView.stopLoading()
                            loading = false
                            refreshHistory(webView)
                        } else {
                            val retryFailed = failed
                            failed = false
                            failedUrl = null
                            loading = true
                            if (retryFailed) {
                                webView.loadUrl(liveUrl)
                            } else {
                                webView.reload()
                            }
                        }
                    },
                )
                Text(
                    text = pageLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = extra.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = MiruSpace.Xs),
                )
                BrowserIconButton(
                    icon = Icons.Filled.Translate,
                    contentDescription = stringResource(
                        if (translated || translating) {
                            R.string.webview_show_original
                        } else {
                            R.string.webview_translate_page
                        },
                    ),
                    enabled = !loading && hasLoadedOnce && !failed,
                    tint = if (translated) extra.accent else null,
                    working = translating,
                    onClick = {
                        focusManager.clearFocus()
                        keyboard?.hide()
                        togglePageTranslation()
                    },
                )
                BrowserIconButton(
                    icon = Icons.Filled.MoreHoriz,
                    contentDescription = stringResource(R.string.webview_actions),
                    onClick = {
                        focusManager.clearFocus()
                        keyboard?.hide()
                        sheet = BrowserSheet.Actions
                    },
                )
            }
        }
        // Reserved 4dp slot: the page never shifts when the bar appears.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp),
        ) {
            if (loading) {
                LinearProgressIndicator(
                    color = extra.accent,
                    trackColor = extra.accent.copy(alpha = 0.16f),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Box(modifier = Modifier.weight(1f)) {
            // First paint only: a light page skeleton under the transparent
            // WebView instead of an empty background. Later navigations keep
            // the existing content visible while the thin bar loads.
            if (loading && !hasLoadedOnce) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(MiruSize.ScreenPad),
                ) {
                    SkeletonBar(
                        modifier = Modifier.fillMaxWidth(0.55f),
                        height = 22.dp,
                        corner = 8.dp,
                    )
                    Spacer(modifier = Modifier.height(MiruSpace.L))
                    SkeletonBar(modifier = Modifier.fillMaxWidth(), height = 12.dp)
                    Spacer(modifier = Modifier.height(MiruSpace.S))
                    SkeletonBar(modifier = Modifier.fillMaxWidth(0.92f), height = 12.dp)
                    Spacer(modifier = Modifier.height(MiruSpace.S))
                    SkeletonBar(modifier = Modifier.fillMaxWidth(0.68f), height = 12.dp)
                }
            }
            AndroidView(
                factory = { webView },
                modifier = Modifier.fillMaxSize(),
            )
            if (failed) {
                val errorHost = remember(failedUrl, liveUrl) {
                    val raw = failedUrl ?: liveUrl
                    Uri.parse(raw).host.orEmpty().ifBlank { raw }
                }
                WebViewError(host = errorHost, onRetry = {
                    failed = false
                    loading = true
                    webView.loadUrl(failedUrl ?: liveUrl)
                    failedUrl = null
                })
            }
        }
    }

    when (sheet) {
        BrowserSheet.Actions -> AppBottomSheet(
            title = stringResource(R.string.webview_actions),
            description = stringResource(R.string.webview_actions_desc),
            onClose = { sheet = BrowserSheet.None },
        ) {
            MiruActionRow(
                icon = Icons.Filled.ContentCopy,
                title = stringResource(R.string.webview_copy_url),
                subtitle = stringResource(R.string.webview_copy_url_desc),
                onClick = {
                    clipboard.setText(AnnotatedString(liveUrl))
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        toast(R.string.webview_url_copied)
                    }
                    sheet = BrowserSheet.None
                },
            )
            MiruRowDivider()
            MiruActionRow(
                icon = Icons.Filled.ContentPaste,
                title = stringResource(R.string.webview_paste_open),
                subtitle = stringResource(R.string.webview_paste_open_desc),
                onClick = {
                    val text = clipboard.getText()?.text?.trim().orEmpty()
                    sheet = BrowserSheet.None
                    if (text.isEmpty()) {
                        toast(R.string.webview_clipboard_empty)
                    } else {
                        openAddress(text)
                    }
                },
            )
            MiruRowDivider()
            MiruActionRow(
                icon = Icons.Filled.OpenInBrowser,
                title = stringResource(R.string.webview_open_external),
                subtitle = stringResource(R.string.webview_open_external_desc),
                onClick = {
                    sheet = BrowserSheet.None
                    openExternalPage()
                },
            )
            MiruRowDivider()
            MiruActionRow(
                icon = Icons.Filled.Share,
                title = stringResource(R.string.webview_share),
                subtitle = stringResource(R.string.webview_share_desc),
                onClick = {
                    sheet = BrowserSheet.None
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, liveUrl)
                    }
                    val chooser = Intent.createChooser(
                        send,
                        resources.getString(R.string.webview_share_chooser),
                    )
                    if (runCatching { context.startActivity(chooser) }.isFailure) {
                        toast(R.string.webview_no_app)
                    }
                },
            )
            MiruRowDivider()
            MiruActionRow(
                icon = Icons.Filled.DeleteSweep,
                title = stringResource(R.string.webview_clear_data),
                subtitle = stringResource(R.string.webview_clear_data_desc),
                onClick = { sheet = BrowserSheet.ClearData },
            )
        }

        BrowserSheet.ClearData -> AppBottomSheet(
            title = stringResource(R.string.webview_clear_data),
            description = stringResource(R.string.webview_clear_data_desc),
            onClose = { sheet = BrowserSheet.None },
            doneLabel = stringResource(R.string.webview_clear_data_confirm),
            doneDanger = true,
            onDone = {
                sheet = BrowserSheet.None
                clearSiteData()
            },
        ) {
            Text(
                text = stringResource(R.string.webview_clear_data_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        BrowserSheet.None -> Unit
    }

    val video = fullscreenView
    if (video != null) {
        Dialog(
            onDismissRequest = {
                fullscreenCallback?.onCustomViewHidden()
                removeFullscreenView()
            },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
            ) {
                AndroidView(
                    factory = { dialogContext ->
                        FrameLayout(dialogContext).apply {
                            setBackgroundColor(android.graphics.Color.BLACK)
                            (video.parent as? ViewGroup)?.removeView(video)
                            addView(
                                video,
                                FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                ),
                            )
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

// 40dp visible control inside the standard 48dp hit box. The toolbar itself
// is the plate, so these stay bare (the header uses the same bare-icon voice).
@Composable
private fun BrowserIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color? = null,
    working: Boolean = false,
) {
    val extra = LocalMiruExtraColors.current
    val ink = when {
        !enabled -> extra.muted.copy(alpha = 0.38f)
        tint != null -> tint
        else -> MaterialTheme.colorScheme.onSurface
    }
    val label = contentDescription
    Box(
        modifier = modifier
            .size(MiruSize.Touch)
            .clip(RoundedCornerShape(MiruRadius.Control))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (working) {
            CircularProgressIndicator(
                color = extra.accent,
                strokeWidth = 2.dp,
                modifier = Modifier
                    .size(18.dp)
                    .semantics { this.contentDescription = label },
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = ink,
                modifier = Modifier.size(21.dp),
            )
        }
    }
}

@Composable
private fun WebViewError(host: String, onRetry: () -> Unit) {
    val extra = LocalMiruExtraColors.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(MiruSize.ScreenPad),
        contentAlignment = Alignment.Center,
    ) {
        MiruCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(MiruRadius.Inner))
                        .background(extra.accent.copy(alpha = 0.14f)),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.PublicOff,
                        contentDescription = null,
                        tint = extra.accent,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Spacer(modifier = Modifier.height(MiruSpace.M))
                Text(
                    text = stringResource(R.string.webview_error_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(MiruSpace.Xs))
                Text(
                    text = stringResource(R.string.webview_error_body_fmt, host),
                    style = MaterialTheme.typography.bodyMedium,
                    color = extra.muted,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(MiruSpace.L))
                Button(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.onBackground,
                        contentColor = MaterialTheme.colorScheme.background,
                    ),
                    modifier = Modifier.heightIn(min = MiruSize.Button),
                ) {
                    Text(text = stringResource(R.string.webview_retry))
                }
            }
        }
    }
}

private fun startExternal(context: Context, uri: Uri): Boolean {
    val intent = if (uri.scheme.equals("intent", ignoreCase = true)) {
        runCatching {
            Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
        }.getOrNull()
    } else {
        Intent(Intent.ACTION_VIEW, uri)
    } ?: return false
    if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return runCatching { context.startActivity(intent) }.isSuccess
}

private fun fileChooserIntent(params: WebChromeClient.FileChooserParams?): Intent {
    val mimeTypes = params?.acceptTypes.orEmpty()
        .flatMap { it.split(',') }
        .mapNotNull { accept ->
            val trimmed = accept.trim()
            when {
                trimmed.isEmpty() -> null
                trimmed.contains('/') -> trimmed
                trimmed.startsWith(".") -> MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(trimmed.drop(1).lowercase())
                else -> null
            }
        }
        .distinct()
    return Intent(Intent.ACTION_GET_CONTENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = if (mimeTypes.size == 1) mimeTypes.first() else "*/*"
        if (mimeTypes.isNotEmpty()) putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toTypedArray())
        putExtra(
            Intent.EXTRA_ALLOW_MULTIPLE,
            params?.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE,
        )
    }
}
