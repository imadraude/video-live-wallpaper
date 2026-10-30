package io.github.codeg0blin.videowallpaper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderModePolicyTest {
    @Test fun compatibleVideoUsesOpenGl() {
        assertTrue(RenderModePolicy.requiresOpenGl(RenderModePolicy.COMPATIBLE, false))
    }

    @Test fun efficientVideoBypassesOpenGl() {
        assertFalse(RenderModePolicy.requiresOpenGl(RenderModePolicy.EFFICIENT, false))
    }

    @Test fun gifAlwaysUsesOpenGl() {
        assertTrue(RenderModePolicy.requiresOpenGl(RenderModePolicy.EFFICIENT, true))
        assertTrue(RenderModePolicy.requiresOpenGl(RenderModePolicy.COMPATIBLE, true))
    }

    @Test fun unknownModeFallsBackToCompatible() {
        assertTrue(RenderModePolicy.requiresOpenGl(1234, false))
    }
}
