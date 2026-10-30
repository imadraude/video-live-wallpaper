package io.github.codeg0blin.videowallpaper

/**
 * Selects the rendering pipeline without depending on Android framework types.
 *
 * The wallpaper's native Surface ignores MediaPlayer's scaling hints on some
 * devices and can stretch a Fit video instead of letterboxing it. Therefore
 * Fit always uses the OpenGL renderer, which draws aspect-correct geometry
 * and black bars. Efficient keeps direct decoding for Fill videos.
 * GIF playback always needs the GL renderer.
 */
object RenderModePolicy {
    const val COMPATIBLE = 0
    const val EFFICIENT = 1

    fun requiresOpenGl(mode: Int, isGif: Boolean, fit: Boolean): Boolean =
        mode != EFFICIENT || isGif || fit
}
