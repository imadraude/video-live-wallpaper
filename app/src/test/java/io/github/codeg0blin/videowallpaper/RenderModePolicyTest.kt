package io.github.codeg0blin.videowallpaper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderModePolicyTest {
    @Test fun compatibleVideoAlwaysUsesOpenGl() {
        assertTrue(RenderModePolicy.requiresOpenGl(RenderModePolicy.COMPATIBLE, false, false))
        assertTrue(RenderModePolicy.requiresOpenGl(RenderModePolicy.COMPATIBLE, false, true))
    }

    @Test fun efficientFillVideoBypassesOpenGl() {
        assertFalse(RenderModePolicy.requiresOpenGl(RenderModePolicy.EFFICIENT, false, false))
    }

    @Test fun efficientFitVideoUsesOpenGlForCorrectAspectRatio() {
        assertTrue(RenderModePolicy.requiresOpenGl(RenderModePolicy.EFFICIENT, false, true))
    }

    @Test fun gifAlwaysUsesOpenGl() {
        assertTrue(RenderModePolicy.requiresOpenGl(RenderModePolicy.EFFICIENT, true, false))
        assertTrue(RenderModePolicy.requiresOpenGl(RenderModePolicy.EFFICIENT, true, true))
        assertTrue(RenderModePolicy.requiresOpenGl(RenderModePolicy.COMPATIBLE, true, false))
    }

    @Test fun unknownModeFallsBackToCompatible() {
        assertTrue(RenderModePolicy.requiresOpenGl(1234, false, false))
        assertTrue(RenderModePolicy.requiresOpenGl(1234, false, true))
    }
}
