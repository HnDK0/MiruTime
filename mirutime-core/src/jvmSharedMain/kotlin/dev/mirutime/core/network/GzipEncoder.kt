package dev.mirutime.core.network

import io.ktor.util.ContentEncoder
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import java.util.zip.GZIPInputStream
import kotlin.coroutines.CoroutineContext

// Tolerant gzip decoder: unlike Ktor's built-in one, java.util.zip.GZIPInputStream
// accepts optional FNAME/FCOMMENT header fields. Pre-compressed static assets
// (e.g. hitomi's gg.js) carry the original filename and broke every such fetch.
//
// Decode-only: ContentEncoding is installed in DecompressResponse mode, so the
// encode paths are unreachable and fail loudly.
internal actual val GzipEncoder: ContentEncoder = object : ContentEncoder {
    override val name: String = "gzip"

    override fun decode(source: ByteReadChannel, coroutineContext: CoroutineContext): ByteReadChannel =
        GZIPInputStream(source.toInputStream()).toByteReadChannel(coroutineContext)

    override fun encode(source: ByteReadChannel, coroutineContext: CoroutineContext): ByteReadChannel =
        error("Gzip request compression is not supported (decode-only encoder)")

    override fun encode(source: ByteWriteChannel, coroutineContext: CoroutineContext): ByteWriteChannel =
        error("Gzip request compression is not supported (decode-only encoder)")
}
