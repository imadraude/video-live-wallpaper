package io.github.codeg0blin.videowallpaper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackReusePolicyTest {
    @Test fun preparedVideoIsReusableAfterAnyIdleDuration() {
        assertTrue(PlaybackReusePolicy.canReuseVideoDecoder(prepared = true, selectedGif = false))
    }

    @Test fun preparingVideoMustStartFresh() {
        assertFalse(PlaybackReusePolicy.canReuseVideoDecoder(prepared = false, selectedGif = false))
    }

    @Test fun gifUsesIndependentDecoderAndCannotReuseMediaPlayer() {
        assertFalse(PlaybackReusePolicy.canReuseVideoDecoder(prepared = true, selectedGif = true))
        assertFalse(PlaybackReusePolicy.canReuseVideoDecoder(prepared = false, selectedGif = true))
    }

    @Test fun screenHiddenIsNotMemoryPressure() {
        assertFalse(PlaybackReusePolicy.shouldReleaseDetachedForMemory(20))
        assertFalse(PlaybackReusePolicy.shouldReleaseDetachedForMemory(5))
        assertFalse(PlaybackReusePolicy.shouldReleaseDetachedForMemory(0))
    }

    @Test fun releaseIdleCodecsOnRealMemoryPressure() {
        assertTrue(PlaybackReusePolicy.shouldReleaseDetachedForMemory(10))
        assertTrue(PlaybackReusePolicy.shouldReleaseDetachedForMemory(15))
        assertTrue(PlaybackReusePolicy.shouldReleaseDetachedForMemory(40))
        assertTrue(PlaybackReusePolicy.shouldReleaseDetachedForMemory(60))
        assertTrue(PlaybackReusePolicy.shouldReleaseDetachedForMemory(80))
    }
}
