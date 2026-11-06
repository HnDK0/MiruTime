package dev.mirutime.app.design.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mirutime.uikit.R as UiKitR
import dev.mirutime.uikit.LocalMiruExtraColors
import dev.mirutime.uikit.AppBottomSheet
import dev.mirutime.uikit.MiruRadius
import dev.mirutime.uikit.MiruSize
import dev.mirutime.uikit.MiruSpace
import dev.mirutime.app.design.PresetBackground
import dev.mirutime.app.design.PresetRefreshResult
import dev.mirutime.app.design.dedupeBackgroundPaths
import dev.mirutime.app.design.dedupePresets
import dev.mirutime.app.design.applyPreset
import dev.mirutime.app.design.loadCachedPresetBackgrounds
import dev.mirutime.app.design.presetFile
import dev.mirutime.app.design.BackgroundImportFailure
import dev.mirutime.app.design.BackgroundImportResult
import dev.mirutime.app.design.normalizeBackgroundUrl
import dev.mirutime.app.design.refreshPresetBackgrounds
import dev.mirutime.app.design.rememberCachedPresetPreview
import dev.mirutime.uikit.MiruTheme
import dev.mirutime.uikit.ThemeMode
import dev.mirutime.app.design.importBackground
import dev.mirutime.app.design.rememberBackgroundBitmap
import dev.mirutime.uikit.title
import kotlinx.coroutines.launch
import dev.mirutime.uikit.MiruTextField

// Rail 1: every palette theme (Miru originals + NoveLA mirrors). Rail 2: the
// ready-made backgrounds listed from the app's GitHub folder (BackgroundPresets.kt);
// tapping one downloads it into app storage and makes it the active
// background. Rail 3 holds the user's own backgrounds: a "+" tile to add one (file or link) followed by
// every saved picture, each with a small cross to delete it. Pictures are
// stored in app storage and de-duplicated (see ThemeStorage.kt).
private val ClassicThemes = ThemeMode.entries.filter { it != ThemeMode.Custom }

// Two horizontal rails: same vertical space as ~4 rows, every theme
// reachable by sideways scroll, each with a live mini-library preview in
// its own palette.
@Composable
fun ThemePickerRails(
    selected: ThemeMode,
    customBg: String?,
    bgLibrary: List<String>,
    onSelect: (ThemeMode) -> Unit,
    onCustomBg: (String?) -> Unit,
    onRemoveBg: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var addOpen by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }

    // Ready-made backgrounds from GitHub: null = cached list still reading,
    // empty = nothing cached and the last refresh failed. Opening reads the
    // cache only; Refresh downloads the listing and every picture, a tile tap
    // downloads just that one missing picture.
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var presets by remember { mutableStateOf<List<PresetBackground>?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var refreshResult by remember { mutableStateOf<PresetRefreshResult?>(null) }
    var importingUrl by remember { mutableStateOf<String?>(null) }
    var importFailed by remember { mutableStateOf(false) }
    var previewRevision by remember { mutableIntStateOf(0) }

    // Each rail opens on its active tile: the selected theme, the active
    // preset, or the active library background (behind the leading Add tile).
    // First placement jumps; later selection changes animate.
    val themeState = rememberLazyListState()
    val presetState = rememberLazyListState()
    val bgState = rememberLazyListState()
    var themeScrolled by remember { mutableStateOf(false) }
    var presetScrolled by remember { mutableStateOf(false) }
    var bgScrolled by remember { mutableStateOf(false) }
    val presetList = presets?.let(::dedupePresets)
    val backgrounds = remember(bgLibrary) { dedupeBackgroundPaths(bgLibrary) }
    val activeThemeIndex = ClassicThemes.indexOf(selected)
    val activePresetIndex = remember(presets, customBg, selected) {
        if (selected != ThemeMode.Custom || customBg == null) {
            -1
        } else {
            presets?.let(::dedupePresets)
                ?.indexOfFirst { presetFile(context, it).absolutePath == customBg }
                ?: -1
        }
    }
    val activeBgIndex = remember(backgrounds, customBg, selected) {
        if (selected != ThemeMode.Custom || customBg == null) {
            -1
        } else {
            backgrounds.indexOf(customBg).takeIf { it >= 0 }?.plus(1) ?: -1
        }
    }
    LaunchedEffect(activeThemeIndex) {
        if (activeThemeIndex < 0) return@LaunchedEffect
        if (themeScrolled) themeState.animateScrollToItem(activeThemeIndex) else {
            themeState.scrollToItem(activeThemeIndex)
            themeScrolled = true
        }
    }
    LaunchedEffect(activePresetIndex) {
        if (activePresetIndex < 0) return@LaunchedEffect
        if (presetScrolled) presetState.animateScrollToItem(activePresetIndex) else {
            presetState.scrollToItem(activePresetIndex)
            presetScrolled = true
        }
    }
    LaunchedEffect(activeBgIndex) {
        if (activeBgIndex < 0) return@LaunchedEffect
        if (bgScrolled) bgState.animateScrollToItem(activeBgIndex) else {
            bgState.scrollToItem(activeBgIndex)
            bgScrolled = true
        }
    }

    LaunchedEffect(Unit) {
        presets = loadCachedPresetBackgrounds(context)
    }

    fun refreshPresets() {
        if (refreshing) return
        refreshing = true
        importFailed = false
        refreshResult = null
        scope.launch {
            val result = refreshPresetBackgrounds(context)
            presets = result.presets
            refreshResult = result
            previewRevision++
            refreshing = false
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(UiKitR.string.theme_rail_classic),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(MiruSpace.S))
        LazyRow(
            state = themeState,
            horizontalArrangement = Arrangement.spacedBy(MiruSpace.M),
        ) {
            items(ClassicThemes, key = { it.name }) { mode ->
                ThemeTile(
                    mode = mode,
                    selected = mode == selected,
                    onClick = { onSelect(mode) },
                )
            }
        }
        Spacer(modifier = Modifier.height(MiruSpace.L))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(UiKitR.string.theme_rail_presets),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { refreshPresets() }, enabled = !refreshing) {
                if (refreshing) {
                    CircularProgressIndicator(
                        strokeWidth = MiruSize.Stroke * 2,
                        color = LocalMiruExtraColors.current.accent,
                        modifier = Modifier.size(MiruSize.Indicator),
                    )
                } else {
                    Text(text = stringResource(UiKitR.string.theme_presets_refresh))
                }
            }
        }
        Spacer(modifier = Modifier.height(MiruSpace.Xs))
        when {
            presetList == null -> LazyRow(horizontalArrangement = Arrangement.spacedBy(MiruSpace.M)) {
                items(3) { PresetPlaceholderTile() }
            }
            presetList.isEmpty() -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(UiKitR.string.theme_presets_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalMiruExtraColors.current.muted,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { refreshPresets() }, enabled = !refreshing) {
                    Text(text = stringResource(UiKitR.string.theme_presets_retry))
                }
            }
            else -> LazyRow(
                state = presetState,
                horizontalArrangement = Arrangement.spacedBy(MiruSpace.M),
            ) {
                itemsIndexed(presetList, key = { _, preset -> preset.url }) { index, preset ->
                    PresetTile(
                        preset = preset,
                        label = stringResource(UiKitR.string.theme_preset_item_fmt, index + 1),
                        previewRevision = previewRevision,
                        activeSavedPath = if (selected == ThemeMode.Custom) customBg else null,
                        busy = importingUrl == preset.url,
                        onClick = {
                            if (importingUrl != null) return@PresetTile
                            importingUrl = preset.url
                            importFailed = false
                            scope.launch {
                                // Applied in place; never copied into bgLibrary.
                                val local = applyPreset(context, preset)
                                importingUrl = null
                                if (local != null) {
                                    previewRevision++
                                    onCustomBg(local)
                                    onSelect(ThemeMode.Custom)
                                } else {
                                    importFailed = true
                                }
                            }
                        },
                    )
                }
            }
        }
        if (importFailed) {
            Spacer(modifier = Modifier.height(MiruSpace.Xs))
            Text(
                text = stringResource(UiKitR.string.theme_preset_download_failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        val refreshed = refreshResult
        if (refreshed != null && !presetList.isNullOrEmpty()) {
            Spacer(modifier = Modifier.height(MiruSpace.Xs))
            Text(
                text = if (refreshed.listingFetched) {
                    stringResource(
                        UiKitR.string.theme_presets_downloaded_fmt,
                        refreshed.downloaded + refreshed.skipped,
                        refreshed.presets.size,
                    )
                } else {
                    stringResource(UiKitR.string.theme_presets_failed)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (!refreshed.listingFetched || refreshed.failed > 0) {
                    MaterialTheme.colorScheme.error
                } else {
                    LocalMiruExtraColors.current.muted
                },
            )
        }
        Spacer(modifier = Modifier.height(MiruSpace.L))
        Text(
            text = stringResource(UiKitR.string.theme_rail_background),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(MiruSpace.S))
        LazyRow(
            state = bgState,
            horizontalArrangement = Arrangement.spacedBy(MiruSpace.M),
        ) {
            item(key = "add-bg") {
                AddBackgroundTile(onClick = { addOpen = true })
            }
            itemsIndexed(backgrounds, key = { _, source -> source }) { index, source ->
                BackgroundTile(
                    source = source,
                    label = stringResource(UiKitR.string.theme_custom_item_fmt, index + 1),
                    selected = selected == ThemeMode.Custom && source == customBg,
                    onClick = {
                        onCustomBg(source)
                        onSelect(ThemeMode.Custom)
                    },
                    onRemove = { pendingDelete = source },
                )
            }
        }
    }
    if (addOpen) {
        AddBackgroundSheet(
            onClose = { addOpen = false },
            onPick = {
                onCustomBg(it)
                onSelect(ThemeMode.Custom)
                addOpen = false
            },
        )
    }
    pendingDelete?.let { source ->
        AppBottomSheet(
            title = stringResource(UiKitR.string.theme_bg_delete_title),
            description = stringResource(UiKitR.string.theme_bg_delete_desc),
            onClose = { pendingDelete = null },
            doneLabel = stringResource(UiKitR.string.theme_bg_delete),
            doneDanger = true,
            onDone = {
                onRemoveBg(source)
                pendingDelete = null
            },
        ) {}
    }
}

// Tile: live mini-library preview in the theme's own palette + localized
// name; selected tile gets the accent ring. No Color() literals anywhere —
// the preview reads the nested theme's scheme and extra tokens only.
@Composable
private fun ThemeTile(
    mode: ThemeMode,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val ring = LocalMiruExtraColors.current.accent
    Column(
        modifier = Modifier
            .width(132.dp)
            .clip(RoundedCornerShape(MiruRadius.Control))
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) {
                    ring
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                shape = RoundedCornerShape(MiruRadius.Control),
            )
            .clickable(onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MiruTheme(mode = mode) {
            MiniLibrary()
        }
        Spacer(modifier = Modifier.height(MiruSpace.Xs))
        Text(
            text = mode.title(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// Two abstract mini cards in the preview theme: cover block + title bar +
// gold progress bar. Same visual language as LibraryCard, zero literals.
@Composable
private fun MiniLibrary() {
    val extra = LocalMiruExtraColors.current
    Surface(
        shape = RoundedCornerShape(MiruRadius.Chip),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            repeat(2) {
                Column(
                    modifier = Modifier.width(52.dp),
                    horizontalAlignment = Alignment.Start,
                ) {
                    Box(
                        modifier = Modifier
                            .width(52.dp)
                            .height(58.dp)
                            .clip(RoundedCornerShape(MiruRadius.Badge))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(4.dp)
                                .size(width = 18.dp, height = 8.dp)
                                .clip(RoundedCornerShape(MiruRadius.Split))
                                .background(extra.accent),
                        )
                    }
                    Spacer(modifier = Modifier.height(MiruSpace.Xs))
                    Box(
                        modifier = Modifier
                            .width(44.dp)
                            .height(5.dp)
                            .clip(RoundedCornerShape(MiruRadius.Split))
                            .background(
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                            ),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .width(52.dp)
                            .height(3.dp)
                            .clip(RoundedCornerShape(MiruRadius.Split))
                            .background(
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f),
                            ),
                    ) {
                        Box(
                            modifier = Modifier
                                .width(30.dp)
                                .height(3.dp)
                                .clip(RoundedCornerShape(MiruRadius.Split))
                                .background(extra.accent),
                        )
                    }
                }
            }
        }
    }
}

// "+" tile that opens the add-background sheet.
@Composable
private fun AddBackgroundTile(onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(132.dp)
            .clip(RoundedCornerShape(MiruRadius.Control))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(MiruRadius.Control),
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .width(116.dp)
                .height(104.dp)
                .clip(RoundedCornerShape(MiruRadius.Chip))
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline,
                    shape = RoundedCornerShape(MiruRadius.Chip),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "+",
                style = MaterialTheme.typography.displaySmall,
                color = LocalMiruExtraColors.current.muted,
            )
        }
        Spacer(modifier = Modifier.height(MiruSpace.S))
        Text(
            text = stringResource(UiKitR.string.theme_custom_add),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// A saved background: the picture itself as the preview, accent ring when it
// is the active one, a small cross in the corner to delete it. The cross has
// a 40dp hit box over a 24dp scrim disc so it reads on any picture.
@Composable
private fun BackgroundTile(
    source: String,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val ring = LocalMiruExtraColors.current.accent
    val bitmap = rememberBackgroundBitmap(source)
    Column(
        modifier = Modifier
            .width(132.dp)
            .clip(RoundedCornerShape(MiruRadius.Control))
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) ring else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(MiruRadius.Control),
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .width(116.dp)
                .height(104.dp)
                .clip(RoundedCornerShape(MiruRadius.Chip))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .background(Color.Black.copy(alpha = 0.6f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(UiKitR.string.theme_bg_delete),
                        tint = Color.White,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(MiruSpace.S))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// Ready-made background tile: the preset label while nothing is cached, the
// already-downloaded picture when it exists (local read only), a progress ring
// while it is being applied. Accent ring when it is the active background
// (matched by the in-place preset path). No delete cross — a preset stays in
// its own folder and never joins "My backgrounds".
@Composable
private fun PresetTile(
    preset: PresetBackground,
    label: String,
    previewRevision: Int,
    activeSavedPath: String?,
    busy: Boolean,
    onClick: () -> Unit,
) {
    val ring = LocalMiruExtraColors.current.accent
    val preview = rememberCachedPresetPreview(preset, previewRevision)
    val selected = preview != null && preview.savedPath == activeSavedPath
    Column(
        modifier = Modifier
            .width(132.dp)
            .clip(RoundedCornerShape(MiruRadius.Control))
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) ring else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(MiruRadius.Control),
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(MiruSpace.S),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .width(116.dp)
                .height(104.dp)
                .clip(RoundedCornerShape(MiruRadius.Chip))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (preview != null) {
                Image(
                    bitmap = preview.thumb,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                // Text-only placeholder: no source/plugin icon.
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalMiruExtraColors.current.muted,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = MiruSpace.Xs),
                )
            }
            if (busy) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    color = ring,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        Spacer(modifier = Modifier.height(MiruSpace.Xs))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// Same footprint as PresetTile while the folder listing loads, so the rail
// does not jump when the pictures arrive.
@Composable
private fun PresetPlaceholderTile() {
    Column(
        modifier = Modifier
            .width(132.dp)
            .clip(RoundedCornerShape(MiruRadius.Control))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(MiruRadius.Control))
            .padding(MiruSpace.S),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .width(116.dp)
                .height(104.dp)
                .clip(RoundedCornerShape(MiruRadius.Chip))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(modifier = Modifier.height(MiruSpace.Xs))
        Spacer(modifier = Modifier.height(17.dp))
    }
}

// File (SAF image picker) or link (http/https). The picture is copied into
// app storage first (importBackground): unreadable files, dead links and
// non-images are refused with a toast instead of being set as the theme.
@Composable
private fun AddBackgroundSheet(
    onClose: () -> Unit,
    onPick: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<BackgroundImportFailure?>(null) }

    fun startImport(raw: String) {
        if (busy) return
        val source = normalizeBackgroundUrl(raw)
        if (source == null) {
            error = BackgroundImportFailure.InvalidScheme
            return
        }
        error = null
        busy = true
        scope.launch {
            val result = importBackground(context, source)
            busy = false
            when (result) {
                is BackgroundImportResult.Success -> onPick(result.path)
                is BackgroundImportResult.Failure -> error = result.reason
            }
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) startImport(uri.toString()) }

    AppBottomSheet(
        title = stringResource(UiKitR.string.theme_custom_bg),
        description = stringResource(UiKitR.string.theme_custom_desc),
        onClose = onClose,
        doneLabel = stringResource(UiKitR.string.theme_custom_ok),
        doneEnabled = !busy && link.isNotBlank(),
        doneBusy = busy,
        onDone = { startImport(link) },
    ) {
        MiruTextField(
            value = link,
            onValueChange = {
                link = it
                error = null
            },
            placeholder = stringResource(UiKitR.string.theme_custom_link_hint),
            singleLine = true,
            enabled = !busy,
            isError = error != null,
            supportingText = error?.let { stringResource(it.messageRes()) },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(12.dp))
        // Sheet action buttons ride the one size grid: 36dp.
        OutlinedButton(
            onClick = { filePicker.launch(arrayOf("image/*")) },
            enabled = !busy,
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
        ) {
            Text(text = stringResource(UiKitR.string.theme_custom_file))
        }
        Spacer(modifier = Modifier.height(4.dp))
    }
}

private fun BackgroundImportFailure.messageRes(): Int = when (this) {
    BackgroundImportFailure.InvalidScheme -> UiKitR.string.theme_custom_err_link
    BackgroundImportFailure.Unreadable -> UiKitR.string.theme_custom_err_fetch
    BackgroundImportFailure.StoreFailed -> UiKitR.string.theme_custom_failed
    BackgroundImportFailure.TooLarge -> UiKitR.string.theme_custom_err_size
    BackgroundImportFailure.NotAnImage -> UiKitR.string.theme_custom_err_image
}
