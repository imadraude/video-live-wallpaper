package io.github.codeg0blin.videowallpaper

/**
 * Rendering policy kept separate from Android's lifecycle code so it can be
 * tested without a device. GIFs require the application's OpenGL renderer.
 * Unknown settings intentionally fall back to the fully compatible renderer.
 */
object RenderModePolicy {
    const val COMPATIBLE = 0
    const val EFFICIENT = 1

    fun requiresOpenGl(mode: Int, isGif: Boolean): Boolean =
        mode != EFFICIENT || isGif
}
