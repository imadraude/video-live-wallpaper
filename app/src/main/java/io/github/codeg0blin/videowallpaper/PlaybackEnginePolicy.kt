package io.github.codeg0blin.videowallpaper

/**
 * The video playback backend used when no explicit choice has been saved.
 * Existing preferences always win, including a saved MediaPlayer selection.
 */
object PlaybackEnginePolicy {
    const val MEDIA_PLAYER = 0
    const val MEDIA3 = 1
    const val DEFAULT = MEDIA3

    fun resolve(saved: Int?): Int = when (saved) {
        MEDIA_PLAYER -> MEDIA_PLAYER
        MEDIA3 -> MEDIA3
        else -> DEFAULT
    }
}
