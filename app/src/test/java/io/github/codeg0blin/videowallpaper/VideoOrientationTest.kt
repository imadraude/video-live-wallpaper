package io.github.codeg0blin.videowallpaper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoOrientationTest {
    @Test fun plainAndVerticallyFlippedVideoAreNotQuarterTurns() {
        assertFalse(VideoOrientation.hasQuarterTurn(floatArrayOf(
            1f,0f,0f,0f, 0f,1f,0f,0f, 0f,0f,1f,0f, 0f,0f,0f,1f)))
        assertFalse(VideoOrientation.hasQuarterTurn(floatArrayOf(
            1f,0f,0f,0f, 0f,-1f,0f,0f, 0f,0f,1f,0f, 0f,1f,0f,1f)))
    }

    @Test fun quarterTurnsSwapDisplayDimensions() {
        assertTrue(VideoOrientation.hasQuarterTurn(floatArrayOf(
            0f,1f,0f,0f, -1f,0f,0f,0f, 0f,0f,1f,0f, 1f,0f,0f,1f)))
        assertTrue(VideoOrientation.hasQuarterTurn(floatArrayOf(
            0f,-1f,0f,0f, 1f,0f,0f,0f, 0f,0f,1f,0f, 0f,1f,0f,1f)))
    }

    @Test fun invalidMatrixIsNotTreatedAsRotation() {
        assertFalse(VideoOrientation.hasQuarterTurn(floatArrayOf()))
    }
}
