package dev.mirutime.app.features.library.data

// Per-format import caps. The binding resource differs: epub/fb2 are parsed in
// memory, while cbz pages and pdf documents are streamed, so their caps keep a
// single import within a sane size rather than protecting the heap.
internal object LocalLimits {
    private const val MB = 1024L * 1024L
    const val FB2 = 64 * MB // must match MAX_FB2_FILE_BYTES in mirutime-core
    const val TXT = 128 * MB
    const val EPUB = 128 * MB
    const val CBZ = 512 * MB
    const val PDF = 512 * MB
}

internal fun importLimit(format: LocalImportFormat): Long = when (format) {
    LocalImportFormat.TXT -> LocalLimits.TXT
    LocalImportFormat.FB2 -> LocalLimits.FB2
    LocalImportFormat.EPUB -> LocalLimits.EPUB
    LocalImportFormat.CBZ -> LocalLimits.CBZ
    LocalImportFormat.PDF -> LocalLimits.PDF
}
