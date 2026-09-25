package com.spacewire.meratune.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarouselPagingTest {

    @Test
    fun realIndexWrapsForward() {
        assertEquals(0, CarouselPaging.realIndex(0, 3))
        assertEquals(2, CarouselPaging.realIndex(2, 3))
        assertEquals(0, CarouselPaging.realIndex(3, 3))
        assertEquals(1, CarouselPaging.realIndex(29_998, 3))
    }

    @Test
    fun realIndexWrapsNegativePositions() {
        assertEquals(2, CarouselPaging.realIndex(-1, 3))
        assertEquals(0, CarouselPaging.realIndex(-3, 3))
        assertEquals(1, CarouselPaging.realIndex(-5, 3))
    }

    @Test
    fun startPositionShowsTheRequestedSlideNearTheMiddle() {
        val count = CarouselPaging.virtualCount(3)
        for (real in 0 until 3) {
            val position = CarouselPaging.startPosition(real, 3)
            assertEquals(real, CarouselPaging.realIndex(position, 3))
            assertTrue("position $position should be near the middle", kotlin.math.abs(position - count / 2) < 3)
            assertFalse(CarouselPaging.needsRecentre(position, 3))
        }
    }

    @Test
    fun startPositionNormalisesOutOfRangeSlides() {
        assertEquals(1, CarouselPaging.realIndex(CarouselPaging.startPosition(4, 3), 3))
        assertEquals(2, CarouselPaging.realIndex(CarouselPaging.startPosition(-1, 3), 3))
    }

    @Test
    fun needsRecentreOnlyNearEitherEnd() {
        val count = CarouselPaging.virtualCount(3)
        assertTrue(CarouselPaging.needsRecentre(0, 3))
        assertTrue(CarouselPaging.needsRecentre(2, 3))
        assertTrue(CarouselPaging.needsRecentre(count - 1, 3))
        assertTrue(CarouselPaging.needsRecentre(count - 3, 3))
        assertFalse(CarouselPaging.needsRecentre(3, 3))
        assertFalse(CarouselPaging.needsRecentre(count - 4, 3))
        assertFalse(CarouselPaging.needsRecentre(count / 2, 3))
    }

    @Test
    fun virtualCount() {
        assertEquals(30_000, CarouselPaging.virtualCount(3))
        assertEquals(1, CarouselPaging.virtualCount(1))
        assertEquals(0, CarouselPaging.virtualCount(0))
    }

    @Test
    fun singleSlideDoesNotLoop() {
        assertEquals(0, CarouselPaging.startPosition(0, 1))
        assertEquals(0, CarouselPaging.realIndex(0, 1))
        assertFalse(CarouselPaging.needsRecentre(0, 1))
    }
}
