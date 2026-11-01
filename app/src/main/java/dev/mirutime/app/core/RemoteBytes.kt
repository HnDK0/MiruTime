package dev.mirutime.app.core

/**
 * One plain-engine HTTP hop: the transport reports what the hop returned and
 * the caller owns the redirect policy (and its error strings).
 */
data class RemoteBytes(
    val status: Int,
    val bytes: ByteArray,
    val headers: Map<String, String>,
    val finalUrl: String,
    val location: String?,
)
