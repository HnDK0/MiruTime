package dev.mirutime.app.pdf;

import android.os.ParcelFileDescriptor;

// Only file descriptors cross this boundary: the isolated renderer never learns
// a path, and the app process never loads android.graphics.pdf.
interface IPdfRenderService {
    // Page count, or -1 when the document cannot be opened.
    int pageCount(in ParcelFileDescriptor pdf);

    // Renders one page into dst as JPEG; a failed render leaves dst empty.
    void renderPage(in ParcelFileDescriptor pdf, int pageIndex, int maxWidthPx, in ParcelFileDescriptor dst);
}
