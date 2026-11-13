package dev.mirutime.app.pdf

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import dev.mirutime.core.log.MiruLog
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Host side of the isolated PDF renderer. Calls are serialized: one binder
 * call at a time keeps a burst of pages from piling up in the service.
 *
 * Fail closed: a service that cannot bind, or a page that cannot be rendered,
 * yields null/false. There is deliberately no in-process fallback — the whole
 * point of the isolated process is that a broken PDF cannot take the app down.
 *
 * One instance is shared by the importer and the reader, and it unbinds after
 * [idleTimeoutMs] without calls: the isolated process only lives while pages are
 * actually being rendered.
 */
class PdfPageRenderer(
    context: Context,
    private val idleTimeoutMs: Long = IDLE_UNBIND_MS,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var service: IPdfRenderService? = null
    private var connection: ServiceConnection? = null
    private var idleJob: Job? = null

    /** Page count, or null when the document or the renderer is unavailable. */
    suspend fun pageCount(file: File): Int? = call(null) { renderer ->
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pdf ->
            renderer.pageCount(pdf).takeIf { it > 0 }
        }
    }

    /** Renders [index] into [outFile]; true only when a non-empty file landed. */
    suspend fun renderPage(
        file: File,
        index: Int,
        maxWidthPx: Int,
        outFile: File,
    ): Boolean {
        withContext(Dispatchers.IO) { outFile.parentFile?.mkdirs() }
        val rendered = call(false) { renderer ->
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pdf ->
                ParcelFileDescriptor.open(
                    outFile,
                    ParcelFileDescriptor.MODE_CREATE or
                        ParcelFileDescriptor.MODE_TRUNCATE or
                        ParcelFileDescriptor.MODE_WRITE_ONLY,
                ).use { dst ->
                    renderer.renderPage(pdf, index, maxWidthPx, dst)
                }
            }
            true
        }
        return rendered && withContext(Dispatchers.IO) {
            outFile.isFile && outFile.length() > 0L
        }
    }

    private suspend fun <T> call(fallback: T, block: (IPdfRenderService) -> T): T =
        mutex.withLock {
            idleJob?.cancel()
            idleJob = null
            try {
                performCall(fallback, block)
            } finally {
                // No in-flight call left: schedule the release of the isolated
                // process without blocking this one.
                if (service != null) scheduleIdleUnbindLocked()
            }
        }

    private suspend fun <T> performCall(fallback: T, block: (IPdfRenderService) -> T): T {
        repeat(2) { attempt ->
            val bound = bindLocked() ?: return fallback
            try {
                return withTimeout(PAGE_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) { block(bound) }
                }
            } catch (e: TimeoutCancellationException) {
                // The timeout cannot interrupt the blocked transact(): unbinding
                // is what stops the call, because the system kills an isolated
                // process that has no clients left. The next page binds again.
                dropLocked()
                MiruLog.event("pdf", "pdf_render_timeout", "ms" to PAGE_TIMEOUT_MS)
                return fallback
            } catch (e: CancellationException) {
                // Caller cancellation is not a render failure.
                throw e
            } catch (e: Exception) {
                dropLocked()
                if (attempt == 1) {
                    MiruLog.event(
                        "pdf",
                        "pdf_renderer_unavailable",
                        "error" to e::class.simpleName,
                    )
                }
            }
        }
        return fallback
    }

    private suspend fun bindLocked(): IPdfRenderService? {
        service?.let { if (it.asBinder().isBinderAlive) return it }
        dropLocked()
        val ready = CompletableDeferred<IPdfRenderService?>()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val bound = binder?.let { IPdfRenderService.Stub.asInterface(it) }
                service = bound
                ready.complete(bound)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
            }

            override fun onBindingDied(name: ComponentName?) {
                service = null
            }

            override fun onNullBinding(name: ComponentName?) {
                ready.complete(null)
            }
        }
        connection = conn
        val bound = runCatching {
            appContext.bindService(
                Intent(appContext, PdfRenderService::class.java),
                conn,
                Context.BIND_AUTO_CREATE,
            )
        }.getOrDefault(false)
        if (!bound) {
            dropLocked()
            return null
        }
        val boundService = withTimeoutOrNull(BIND_TIMEOUT_MS) { ready.await() }
        if (boundService == null) dropLocked()
        return boundService
    }

    private fun scheduleIdleUnbindLocked() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(idleTimeoutMs)
            mutex.withLock { dropLocked() }
        }
    }

    private fun dropLocked() {
        service = null
        connection?.let { runCatching { appContext.unbindService(it) } }
        connection = null
    }

    /**
     * Releases the bound service now; a later call binds again. The scope stays
     * alive because this instance is shared for the whole app session.
     */
    override fun close() {
        idleJob?.cancel()
        idleJob = null
        dropLocked()
    }

    private companion object {
        const val PAGE_TIMEOUT_MS = 20_000L
        const val BIND_TIMEOUT_MS = 5_000L
        const val IDLE_UNBIND_MS = 60_000L
    }
}
