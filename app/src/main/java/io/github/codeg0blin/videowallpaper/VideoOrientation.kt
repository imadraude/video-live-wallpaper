package io.github.codeg0blin.videowallpaper

import kotlin.math.abs

/**
 * Determine whether SurfaceTexture's UV transform rotates the displayed
 * image by 90 or 270 degrees. MediaPlayer's reported video dimensions can
 * describe encoded pixels, whereas the transform rotates those pixels for
 * display; Fit/Fill geometry must use the displayed orientation.
 *
 * Compare matrix basis directions rather than assuming any particular sign,
 * since crop, vertical flips and device codec layouts may vary.
 */
object VideoOrientation {
    fun hasQuarterTurn(textureMatrix: FloatArray): Boolean {
        if (textureMatrix.size < 16) return false
        return abs(textureMatrix[1]) > abs(textureMatrix[0]) &&
            abs(textureMatrix[4]) > abs(textureMatrix[5])
    }
}
