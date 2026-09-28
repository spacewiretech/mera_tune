package com.spacewire.meratune.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AuthImeLayoutTest {

    @Test
    fun carouselFillsTheRoomLeftWithinItsBounds() {
        assertEquals(1073, AuthImeLayout.carouselHeight(2219, 1146, 394, 1087, imeVisible = false))
        assertEquals(1087, AuthImeLayout.carouselHeight(3000, 1146, 394, 1087, imeVisible = false))
        assertEquals(394, AuthImeLayout.carouselHeight(1300, 1146, 394, 1087, imeVisible = false))
    }

    @Test
    fun keyboardOpenLetsTheSlotShrinkBelowTheMinimum() {
        // The form then ends right above the keyboard: content height == viewport height.
        assertEquals(360, AuthImeLayout.carouselHeight(1506, 1146, 394, 1087, imeVisible = true))
        assertEquals(0, AuthImeLayout.carouselHeight(1000, 1146, 394, 1087, imeVisible = true))
    }

    @Test
    fun revealScrollsOnlyAsFarAsTheCtaNeeds() {
        // CTA bottom 1600 in content, 1506 visible, content 1700 tall.
        assertEquals(94, AuthImeLayout.revealScrollY(0, 1600, 1506, 1700))
        // Already visible: no scroll.
        assertEquals(0, AuthImeLayout.revealScrollY(0, 852, 1506, 1506))
    }

    @Test
    fun revealKeepsALargerScrollWithinRange() {
        assertEquals(150, AuthImeLayout.revealScrollY(150, 1600, 1506, 1700))
        assertEquals(194, AuthImeLayout.revealScrollY(400, 1600, 1506, 1700))
    }

    @Test
    fun revealPullsAStaleOffsetBackIntoRange() {
        // A scroll made against the taller content (carousel still showing) must not survive.
        assertEquals(0, AuthImeLayout.revealScrollY(713, 852, 1506, 1506))
    }
}
