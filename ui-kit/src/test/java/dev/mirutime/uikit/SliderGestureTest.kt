package dev.mirutime.uikit

import org.junit.Assert.assertEquals
import org.junit.Test

class SliderGestureTest {
    private val slop = 10f

    @Test
    fun `movement inside the slop stays undecided`() {
        assertEquals(SliderGesture.Pending, sliderGesture(dx = 4f, dy = 3f, slop = slop))
    }

    @Test
    fun `horizontal movement starts the drag`() {
        assertEquals(SliderGesture.Drag, sliderGesture(dx = 24f, dy = 6f, slop = slop))
    }

    @Test
    fun `vertical movement goes to the parent`() {
        assertEquals(SliderGesture.Parent, sliderGesture(dx = 4f, dy = 30f, slop = slop))
    }

    @Test
    fun `diagonal with a vertical lead goes to the parent`() {
        assertEquals(SliderGesture.Parent, sliderGesture(dx = 18f, dy = 22f, slop = slop))
    }

    @Test
    fun `equal movement goes to the parent`() {
        assertEquals(SliderGesture.Parent, sliderGesture(dx = 20f, dy = 20f, slop = slop))
    }
}
