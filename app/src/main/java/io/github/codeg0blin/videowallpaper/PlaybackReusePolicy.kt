package io.github.codeg0blin.videowallpaper

/**
 * Decoder lifetime decisions, independent of Android for local unit tests.
 * A paused and detached prepared player can reconnect to a new video Surface
 * without opening the source again, even after a long period of inactivity.
 */
object PlaybackReusePolicy {
    fun canReuseVideoDecoder(prepared: Boolean, selectedGif: Boolean): Boolean =
        prepared && !selectedGif

    // Android's trim levels: BACKGROUND=40, RUNNING_LOW=10,
    // RUNNING_CRITICAL=15. UI_HIDDEN=20 should not force the codec cold
    // merely because the screen is locked, but memory pressure must win.
    fun shouldReleaseDetachedForMemory(level: Int): Boolean =
        level >= 40 || level == 10 || level == 15
}
