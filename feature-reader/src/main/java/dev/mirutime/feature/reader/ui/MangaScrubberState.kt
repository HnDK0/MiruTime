package dev.mirutime.feature.reader.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Bridge between the chrome scrubber and whichever manga viewer is active.
 * The viewer binds its seek; the chrome only reports drag pages. Anchor writes
 * stay quiet while [dragging] is true, so a scrub saves one position, not one
 * per pointer move.
 */
@Stable
internal class MangaScrubberState {
    var dragging by mutableStateOf(false)
        private set

    private var seekHandler: ((Int) -> Unit)? = null

    fun bind(seek: (Int) -> Unit) {
        seekHandler = seek
    }

    fun seek(page: Int) {
        dragging = true
        seekHandler?.invoke(page)
    }

    fun finish() {
        dragging = false
    }
}
