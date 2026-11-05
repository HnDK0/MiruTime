package dev.mirutime.app.design.components

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Precision
import dev.mirutime.app.R
import dev.mirutime.uikit.LocalMiruExtraColors
import dev.mirutime.uikit.MiruRadius
import dev.mirutime.uikit.MiruSize
import dev.mirutime.uikit.MiruRoundIconButton
import dev.mirutime.uikit.CardProgressBar
import kotlinx.coroutines.launch

// Builds a cover ImageRequest. Auth and per-host headers no longer live here:
// the app's Coil NetworkClient (PluginImageNetworkClient) resolves them per
// request, so local/fake covers and remote ones share one path.
// INEXACT lets a cached bitmap larger than the slot satisfy the request
// (covers are always cropped by AsyncImage), which raises the memory-cache
// hit rate on a scrolling grid.
internal fun coverImageRequest(context: Context, url: String): ImageRequest =
    ImageRequest.Builder(context)
        .data(url)
        .precision(Precision.INEXACT)
        .build()

// Cover placeholder: tinted surface with a serif initial until real image
// loading (Coil3) lands with the data layer. Keeps badge + progress slots so
// screens do not get rewritten when covers arrive.
@Composable
fun BookCover(
    title: String,
    modifier: Modifier = Modifier,
    badge: String? = null,
    progress: Float? = null,
    // Update dot on covers (dots pair the dashboard
    // updates panel; downloaded badges stay on chapters, not covers).
    hasUpdateDot: Boolean = false,
    corner: Dp = MiruRadius.Cover,
    coverUrl: String? = null,
    badgeAlignment: Alignment = Alignment.TopStart,
    // Migration results pass a neutral book mark instead of a letter while
    // a search hit has no cover yet.
    showInitial: Boolean = true,
    placeholderIcon: ImageVector? = null,
    // One retry owner: the caller asks the source for a repaired cover and
    // returns the fresh URI (null when repair failed; the caller shows the
    // typed error). A failed remote load repairs automatically once; the tile
    // stays for an explicit retry.
    onRetry: (suspend () -> String?)? = null,
    // Any change restarts the load even when the URL string is identical: a
    // list reload must retry a cover that failed before, not keep the failed
    // painter cached under the same model.
    reloadKey: Any? = null,
) {
    // The displayed URI can outlive `coverUrl`: a repair returns a new local
    // file with a new version fragment without the caller refetching the page.
    var displayedUrl by remember(coverUrl, reloadKey) { mutableStateOf(coverUrl) }
    var attempt by remember(coverUrl, reloadKey) { mutableIntStateOf(0) }
    var failed by remember(coverUrl, reloadKey) { mutableStateOf(false) }
    var repaired by remember(coverUrl, reloadKey) { mutableStateOf(false) }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(corner),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 0.dp,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (showInitial) {
                Text(
                    text = title.firstOrNull()?.uppercase() ?: "?",
                    style = MaterialTheme.typography.displaySmall,
                    color = LocalMiruExtraColors.current.muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else if (placeholderIcon != null) {
                Icon(
                    imageVector = placeholderIcon,
                    contentDescription = null,
                    tint = LocalMiruExtraColors.current.muted,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(MiruSize.Touch),
                )
            }
            // Remote art sits above the initial; a failed/loading load draws
            // nothing, so the placeholder stays visible underneath.
            if (!displayedUrl.isNullOrBlank()) {
                val context = LocalContext.current
                val scope = rememberCoroutineScope()
                val retry: () -> Unit = {
                    val repair = onRetry
                    if (repair != null) {
                        scope.launch {
                            val fresh = repair()
                            if (!fresh.isNullOrBlank()) {
                                displayedUrl = fresh
                                failed = false
                            } else {
                                failed = true
                            }
                            attempt++
                        }
                    }
                }
                val request = remember(displayedUrl, attempt) {
                    coverImageRequest(context, displayedUrl.orEmpty())
                }
                // key() is required: AsyncImage's model equality check ignores
                // attempt, so without it a failed painter is never restarted.
                key(attempt) {
                    AsyncImage(
                        model = request,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                        onError = {
                            failed = true
                            // A dead remote URL is worth one source re-resolve
                            // per composition; the provider cooldown keeps a
                            // scrolling grid from turning it into a storm.
                            if (!repaired && onRetry != null && displayedUrl?.startsWith("http") == true) {
                                repaired = true
                                retry()
                            }
                        },
                    )
                }
                if (failed && onRetry != null) {
                    Box(modifier = Modifier.align(Alignment.Center)) {
                        MiruRoundIconButton(
                            icon = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.lib_cd_retry),
                            onClick = retry,
                        )
                    }
                }
            }
            if (badge != null) {
                Surface(
                    modifier = Modifier
                        .align(badgeAlignment)
                        .padding(6.dp),
                    shape = RoundedCornerShape(MiruRadius.Badge),
                    color = LocalMiruExtraColors.current.accent,
                ) {
                    Text(
                        text = badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalMiruExtraColors.current.onAccent,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                    )
                }
            }
            if (hasUpdateDot) {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(10.dp)
                        .background(MaterialTheme.colorScheme.secondary, RoundedCornerShape(MiruRadius.Split))
                        .border(
                            1.5.dp,
                            MaterialTheme.colorScheme.background,
                            RoundedCornerShape(MiruRadius.Split),
                        ),
                )
            }
            if (progress != null) {
                // Same bar component as everywhere else — LinearProgressIndicator
                // drew round caps, a stop dot and a gap, so covers read as a
                // different bar style than the cards.
                CardProgressBar(
                    fraction = progress.coerceIn(0f, 1f),
                    height = 4.dp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(start = 6.dp, end = 6.dp, bottom = 7.dp),
                )
            }
        }
    }
}
