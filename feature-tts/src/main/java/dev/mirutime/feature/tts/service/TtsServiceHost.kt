package dev.mirutime.feature.tts.service

import dev.mirutime.feature.tts.TtsController

/**
 * Process-local bridge between the app-scoped TtsController and the playback
 * service. The host is memory only, so a restarted process starts empty and
 * the service stops instead of touching a session it no longer owns.
 */
object TtsServiceHost {
    @Volatile
    private var controller: TtsController? = null

    val current: TtsController?
        get() = controller

    fun attach(controller: TtsController) {
        this.controller = controller
    }

    fun detach(controller: TtsController) {
        if (this.controller === controller) this.controller = null
    }
}
