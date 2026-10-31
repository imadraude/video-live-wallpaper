package io.github.codeg0blin.videowallpaper

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackEnginePolicyTest {
    @Test fun media3IsDefaultForNewAndUnsetInstallations() {
        assertEquals(PlaybackEnginePolicy.MEDIA3, PlaybackEnginePolicy.DEFAULT)
        assertEquals(PlaybackEnginePolicy.MEDIA3, PlaybackEnginePolicy.resolve(null))
    }

    @Test fun anExplicitLegacyMediaPlayerSelectionIsPreserved() {
        assertEquals(
            PlaybackEnginePolicy.MEDIA_PLAYER,
            PlaybackEnginePolicy.resolve(PlaybackEnginePolicy.MEDIA_PLAYER)
        )
    }

    @Test fun anExplicitMedia3SelectionIsPreserved() {
        assertEquals(PlaybackEnginePolicy.MEDIA3, PlaybackEnginePolicy.resolve(PlaybackEnginePolicy.MEDIA3))
    }

    @Test fun unrecognizedValueFallsBackToMedia3() {
        assertEquals(PlaybackEnginePolicy.MEDIA3, PlaybackEnginePolicy.resolve(-1))
        assertEquals(PlaybackEnginePolicy.MEDIA3, PlaybackEnginePolicy.resolve(42))
    }
}
