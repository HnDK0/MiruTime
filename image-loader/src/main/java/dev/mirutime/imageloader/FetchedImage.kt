package dev.mirutime.imageloader

// One image fetch result. `status` is 0 for transport failures (timeouts, DNS,
// SSRF rejections) so callers treat them as transient, exactly like 5xx.
data class FetchedImage(
    val status: Int,
    val bytes: ByteArray? = null,
    val etag: String? = null,
    val lastModified: String? = null,
    // Response headers for consumers that rebuild the response (Coil's
    // NetworkClient needs content-type/etag for its cache strategy).
    val headers: Map<String, String> = emptyMap(),
)
