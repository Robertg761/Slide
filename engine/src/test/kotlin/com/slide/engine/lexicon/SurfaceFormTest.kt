package com.slide.engine.lexicon

import org.junit.Assert.assertEquals
import org.junit.Test

class SurfaceFormTest {

    @Test
    fun `one competing observation cannot override the first useful form`() {
        val surface = SurfaceForm.first("iPhoneX").observe("iphonex")
        assertEquals("iPhoneX", surface.value)
    }

    @Test
    fun `a repeatedly observed form can replace the incumbent`() {
        var surface = SurfaceForm.first("kubectl")
        repeat(3) { surface = surface.observe("kubectl") }
        repeat(5) { surface = surface.observe("kubeCtl") }
        assertEquals("kubeCtl", surface.value)
    }

    @Test
    fun `decay keeps an incumbent when the new observation only creates a tie`() {
        var surface = SurfaceForm.first("B")
        repeat(8) { surface = surface.observe("B") } // B = 9
        repeat(8) { surface = surface.observe("A") } // A = 8

        surface = surface.observe("A")
        assertEquals("B", surface.value)

        // One more observation is real evidence beyond the tie and may switch the surface.
        surface = surface.observe("A")
        assertEquals("A", surface.value)
    }
}
