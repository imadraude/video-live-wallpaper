package io.github.codeg0blin.videowallpaper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackReusePolicyTest {
    @Test fun preparedVideoIsReusable() {
        assertTrue(PlaybackReusePolicy.canReuseVideoDecoder(prepared = true, selectedGif = false))
    }

    @Test fun preparingVideoMustStartFresh() {
        assertFalse(PlaybackReusePolicy.canReuseVideoDecoder(prepared = false, selectedGif = false))
    }

    @Test fun gifUsesIndependentDecoderAndCannotReuseMediaPlayer() {
        assertFalse(PlaybackReusePolicy.canReuseVideoDecoder(prepared = true, selectedGif = true))
        assertFalse(PlaybackReusePolicy.canReuseVideoDecoder(prepared = false, selectedGif = true))
    }
}
