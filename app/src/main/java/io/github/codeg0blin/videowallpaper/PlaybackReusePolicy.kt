package io.github.codeg0blin.videowallpaper

/**
 * Decoder lifetime decision independent of Android framework APIs so it can
 * be unit-tested. A prepared MediaPlayer can swap video output Surfaces
 * without reopening the source; a GIF or a preparing player cannot.
 */
object PlaybackReusePolicy {
    fun canReuseVideoDecoder(prepared: Boolean, selectedGif: Boolean): Boolean =
        prepared && !selectedGif
}
