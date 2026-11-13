package dev.mirutime.app.pdf

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.IBinder
import android.os.ParcelFileDescriptor
import dev.mirutime.core.log.MiruLog
import java.io.FileOutputStream
import java.io.IOException

/**
 * The only place in the app that touches `android.graphics.pdf`. The native
 * renderer parses untrusted files, so it runs in an isolated process: no
 * permissions, no network, and no access to the app's own files. Only a
 * duplicated input fd and the caller's output fd cross the boundary.
 */
class PdfRenderService : Service() {

    override fun onBind(intent: Intent?): IBinder = RenderBinder()

    private inner class RenderBinder : IPdfRenderService.Stub() {
        override fun pageCount(pdf: ParcelFileDescriptor?): Int = try {
            // `use` closes the marshalled descriptor on success and failure;
            // the renderer owns (and closes) its own dup.
            requireNotNull(pdf).use { source -> withRenderer(source) { it.pageCount } }
        } catch (e: Throwable) {
            MiruLog.event("pdf", "pdf_open_failed", "error" to e::class.simpleName)
            -1
        }

        override fun renderPage(
            pdf: ParcelFileDescriptor?,
            pageIndex: Int,
            maxWidthPx: Int,
            dst: ParcelFileDescriptor?,
        ) {
            try {
                // Both descriptors belong to this process after marshalling and
                // are closed on every path, including a failed render.
                requireNotNull(pdf).use { source ->
                    requireNotNull(dst).use { target ->
                        withRenderer(source) { renderer ->
                            if (pageIndex < 0 || pageIndex >= renderer.pageCount) {
                                throw IndexOutOfBoundsException("page $pageIndex")
                            }
                            renderer.openPage(pageIndex).use { page ->
                                val bitmap = bitmapFor(page, maxWidthPx)
                                try {
                                    page.render(
                                        bitmap,
                                        null,
                                        null,
                                        PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                                    )
                                    writeJpeg(bitmap, target)
                                } finally {
                                    bitmap.recycle()
                                }
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                // A corrupted page must fail the call, never crash this process.
                MiruLog.event(
                    "pdf",
                    "pdf_render_failed",
                    "page" to pageIndex,
                    "error" to e::class.simpleName,
                )
            }
        }
    }

    private inline fun <T> withRenderer(
        pdf: ParcelFileDescriptor,
        block: (PdfRenderer) -> T,
    ): T {
        // PdfRenderer owns (and closes) the fd it is constructed with; the
        // caller's descriptor is closed by the surrounding `use`.
        val dup = ParcelFileDescriptor.dup(pdf.fileDescriptor)
        val renderer = try {
            PdfRenderer(dup)
        } catch (e: Throwable) {
            dup.close()
            throw e
        }
        return renderer.use(block)
    }

    private fun bitmapFor(page: PdfRenderer.Page, maxWidthPx: Int): Bitmap {
        val width = maxWidthPx.coerceIn(1, MAX_RENDER_WIDTH_PX)
        val height = ((width.toLong() * page.height) / page.width).toInt().coerceAtLeast(1)
        val scale = if (maxOf(width, height) > MAX_BITMAP_DIM_PX) {
            MAX_BITMAP_DIM_PX.toFloat() / maxOf(width, height)
        } else {
            1f
        }
        val w = (width * scale).toInt().coerceAtLeast(1)
        val h = (height * scale).toInt().coerceAtLeast(1)
        // JPEG has no alpha: a page without a white background would go black.
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            it.eraseColor(Color.WHITE)
        }
    }

    // The caller's `dst` owns the fd: the stream only borrows it, so closing
    // the descriptor in the surrounding `use` is the single close.
    private fun writeJpeg(bitmap: Bitmap, dst: ParcelFileDescriptor) {
        val stream = FileOutputStream(dst.fileDescriptor)
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)) {
            throw IOException("JPEG encode failed")
        }
        stream.flush()
    }

    private companion object {
        // A page wider than this is never useful on a phone screen.
        const val MAX_RENDER_WIDTH_PX = 2048
        // Decoding a huge page at full size runs the isolated process out of
        // memory; downscale so neither side exceeds this.
        const val MAX_BITMAP_DIM_PX = 4096
        const val JPEG_QUALITY = 85
    }
}
