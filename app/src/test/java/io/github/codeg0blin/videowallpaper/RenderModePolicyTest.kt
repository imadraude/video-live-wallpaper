package io.github.codeg0blin.videowallpaper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderModePolicyTest {
    @Test fun compatibleAlwaysUsesOpenGl() {
        assertTrue(RenderModePolicy.requiresOpenGl(RenderModePolicy.COMPATIBLE, false, false, 1080, 2400, 1080, 2400))
        assertTrue(RenderModePolicy.requiresOpenGl(RenderModePolicy.COMPATIBLE, false, true, 1920, 1080, 1080, 2400))
    }

    @Test fun efficientMatchingAspectUsesDirectSurface() {
        assertFalse(RenderModePolicy.requiresOpenGl(RenderModePolicy.EFFICIENT, false, false, 1080, 2400, 1440, 3200))
        assertFalse(RenderModePolicy.requiresOpenGl(RenderModePolicy.EFFICIENT, false, false, 1080, 1920, 720, 1280))
        assertFalse(RenderModePolicy.requiresOpenGl(RenderModePolicy.EFFICIENT, false, false, 1080, 2397, 1080, 2400))
    }

    @Test fun efficientFillMismatchedAspectMustUseOpenGlCropping() {
        assertTrue(RenderModePolicy.requiresOpenGl(RenderModePolicy.EFFICIENT, false, false, 1920, 1080, 1080, 2400))
        assertTrue(RenderModePolicy.requiresOpenGl(RenderModePolicy.EFFICIENT, false, false, 1080, 1920, 1080, 2400))
        assertTrue(RenderModePolicy.requiresOpenGl(RenderModePolicy.EFFICIENT, false, false, 2160, 1080, 1080, 1920))
    }

    @Test fun unknownDimensionsDefaultToSafeOpenGl() {
        assertTrue(RenderModePolicy.requiresOpenGl(RenderModePolicy.EFFICIENT, false, false))
        assertTrue(RenderModePolicy.requiresOpenGl(RenderModePolicy.EFFICIENT, false, false, 0, 1920, 1080, 2400))
        assertTrue(RenderModePolicy.requiresOpenGl(RenderModePolicy.EFFICIENT, false, false, 1080, 1920, 0, 2400))
        assertFalse(RenderModePolicy.hasMatchingAspect(-1080, 1920, 1080, 1920))
    }

    @Test fun efficientFitAlwaysUsesOpenGlLetterboxing() {
        assertTrue(RenderModePolicy.requiresOpenGl(RenderModePolicy.EFFICIENT, false, true, 1080, 2400, 1080, 2400))
    }

    @Test fun gifAlwaysUsesOpenGl() {
        assertTrue(RenderModePolicy.requiresOpenGl(RenderModePolicy.EFFICIENT, true, false, 1080, 2400, 1080, 2400))
        assertTrue(RenderModePolicy.requiresOpenGl(RenderModePolicy.EFFICIENT, true, true, 1080, 2400, 1080, 2400))
    }

    @Test fun unknownRenderingModeIsConservative() {
        assertTrue(RenderModePolicy.requiresOpenGl(1234, false, false, 1080, 2400, 1080, 2400))
    }
}
