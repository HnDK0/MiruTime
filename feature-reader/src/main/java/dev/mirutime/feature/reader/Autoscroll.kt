package dev.mirutime.feature.reader

object Autoscroll {
    const val MinSpeed = 5
    const val MaxSpeed = 200
    const val Step = 5
    const val DefaultSpeed = 40

    /** Intermediate slider stops for the whole speed range. */
    const val SliderSteps = (MaxSpeed - MinSpeed) / Step - 1

    // A delayed frame (GC, a long layout pass) must not skip a paragraph.
    private const val MaxFrameMs = 100L
    private const val EndEpsilonPx = 0.5f

    fun sanitizeSpeed(speed: Int): Int = speed.coerceIn(MinSpeed, MaxSpeed)

    fun distancePx(speed: Int, elapsedMs: Long, density: Float): Float =
        sanitizeSpeed(speed) * density * (elapsedMs / 1000f)

    /** One animation frame, capped so a stalled frame cannot jump the page. */
    fun frameDistancePx(speed: Int, elapsedMs: Long, density: Float): Float =
        distancePx(speed, elapsedMs.coerceIn(0L, MaxFrameMs), density)

    /** True once the loaded feed cannot move any further. */
    fun reachedEnd(requestedPx: Float, consumedPx: Float, canScrollForward: Boolean): Boolean =
        !canScrollForward || consumedPx + EndEpsilonPx < requestedPx
}
