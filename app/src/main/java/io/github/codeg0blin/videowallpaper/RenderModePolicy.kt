package io.github.codeg0blin.videowallpaper

import kotlin.math.abs

/**
 * Wallpaper surfaces are not guaranteed to respect a decoder's crop mode.
 * Native output is safe only when video and surface already share the same
 * aspect ratio; otherwise even a requested "Fill" may visibly stretch.
 *
 * OpenGL applies exact aspect-preserving crop for Fill and black borders for
 * Fit. The efficient pipeline still bypasses OpenGL for verified matching
 * video/surface dimensions. Unknown video geometry fails safely to OpenGL.
 */
object RenderModePolicy {
    const val COMPATIBLE = 0
    const val EFFICIENT = 1

    fun requiresOpenGl(
        mode: Int,
        isGif: Boolean,
        fit: Boolean,
        videoWidth: Int = 0,
        videoHeight: Int = 0,
        surfaceWidth: Int = 0,
        surfaceHeight: Int = 0
    ): Boolean =
        mode != EFFICIENT || isGif || fit ||
            !hasMatchingAspect(videoWidth, videoHeight, surfaceWidth, surfaceHeight)

    /**
     * Allow only subpixel rounding discrepancies, not meaningful cropping.
     * Cross products avoid repeated float division and handle large sizes.
     */
    fun hasMatchingAspect(videoWidth: Int, videoHeight: Int, surfaceWidth: Int, surfaceHeight: Int): Boolean {
        if (videoWidth <= 0 || videoHeight <= 0 || surfaceWidth <= 0 || surfaceHeight <= 0) {
            return false
        }
        val left = videoWidth.toDouble() * surfaceHeight
        val right = surfaceWidth.toDouble() * videoHeight
        return abs(left - right) / maxOf(left, right) <= ASPECT_RATIO_TOLERANCE
    }

    private const val ASPECT_RATIO_TOLERANCE = 0.003
}
