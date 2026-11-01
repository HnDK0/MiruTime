package dev.mirutime.app.features.video.ui

/**
 * Emulator fingerprint (goldfish/ranchu/generic): its `c2.goldfish.*` decoder
 * draws pink/green stripes on HLS (ExoPlayer #7903, media #2118), and the
 * retry path never fires because playback itself does not fail.
 */
internal fun isEmulatorDevice(
    fingerprint: String,
    product: String,
    hardware: String,
): Boolean =
    fingerprint.contains("generic", ignoreCase = true) ||
        product.contains("sdk_gphone", ignoreCase = true) ||
        product.contains("goldfish", ignoreCase = true) ||
        hardware.contains("goldfish", ignoreCase = true) ||
        hardware.contains("ranchu", ignoreCase = true)

/**
 * Drops goldfish decoders on an emulator. An empty result would mean no
 * playback at all, so the full list wins over a decoration fix.
 */
internal fun filterEmulatorDecoders(
    names: List<String>,
    isEmulator: Boolean,
): List<String> {
    if (!isEmulator) return names
    return names
        .filterNot { it.contains("goldfish", ignoreCase = true) }
        .ifEmpty { names }
}
