package dev.mirutime.app.core

import android.graphics.BitmapFactory

// Header-only decode check for cover bodies: magic bytes pass a truncated or
// corrupt PNG/JPEG, and writing those over a good file would make the cover
// disappear. inJustDecodeBounds reads the header, never allocates pixels.
object AndroidCoverDecoder {
    fun isDecodable(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        return options.outWidth > 0 && options.outHeight > 0
    }
}
