package com.spacewire.meratune.util

import com.spacewire.meratune.util.GradientGeometry.Bounds
import com.spacewire.meratune.util.GradientGeometry.Endpoints
import com.spacewire.meratune.util.GradientTextHelper.Direction
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GradientGeometryTest {

    private val rect = Bounds(left = 10f, top = 20f, right = 110f, bottom = 60f)

    @Test
    fun horizontalRunsLeftToRightAlongTheTop() {
        assertEquals(Endpoints(10f, 20f, 110f, 20f), GradientGeometry.endpoints(Direction.HORIZONTAL, rect))
    }

    @Test
    fun verticalRunsTopToBottomAlongTheLeft() {
        assertEquals(Endpoints(10f, 20f, 10f, 60f), GradientGeometry.endpoints(Direction.VERTICAL, rect))
    }

    @Test
    fun diagonalUpStartsBottomLeft() {
        assertEquals(Endpoints(10f, 60f, 110f, 20f), GradientGeometry.endpoints(Direction.DIAGONAL_UP, rect))
    }

    @Test
    fun diagonalDownStartsTopLeft() {
        assertEquals(Endpoints(10f, 20f, 110f, 60f), GradientGeometry.endpoints(Direction.DIAGONAL_DOWN, rect))
    }

    @Test
    fun zeroSizeBoundsAreDegenerate() {
        val point = Bounds(5f, 5f, 5f, 5f)
        Direction.values().forEach { direction ->
            assertTrue(direction.name, GradientGeometry.endpoints(direction, point).isDegenerate)
        }
        assertFalse(GradientGeometry.endpoints(Direction.HORIZONTAL, rect).isDegenerate)
    }

    @Test
    fun nullPositionsMeanEvenStops() {
        assertNull(GradientGeometry.validPositions(null, 2))
    }

    @Test
    fun matchingMonotonicPositionsAreKept() {
        val positions = floatArrayOf(0f, 0.4f, 0.4f, 1f)
        assertArrayEquals(positions, GradientGeometry.validPositions(positions, 4), 0f)
    }

    @Test
    fun positionCountMismatchFallsBackToEvenStops() {
        assertNull(GradientGeometry.validPositions(floatArrayOf(0f, 1f), 3))
    }

    @Test
    fun nonMonotonicPositionsFallBackToEvenStops() {
        assertNull(GradientGeometry.validPositions(floatArrayOf(0f, 0.7f, 0.3f), 3))
    }

    @Test
    fun outOfRangePositionsFallBackToEvenStops() {
        assertNull(GradientGeometry.validPositions(floatArrayOf(-0.1f, 1f), 2))
        assertNull(GradientGeometry.validPositions(floatArrayOf(0f, 1.5f), 2))
        assertNull(GradientGeometry.validPositions(floatArrayOf(0f, Float.NaN), 2))
    }

    @Test
    fun highlightRangeCoversTheFirstOccurrence() {
        val range = GradientGeometry.highlightRange("Aapka Ringtone Ready Hai", "Ringtone Ready Hai")
        assertEquals(6 until 24, range)
        assertEquals("Ringtone Ready Hai", "Aapka Ringtone Ready Hai".substring(range!!.first, range.last + 1))
        assertEquals(0 until 3, GradientGeometry.highlightRange("OTP OTP", "OTP"))
    }

    @Test
    fun missingOrEmptyHighlightHasNoRange() {
        assertNull(GradientGeometry.highlightRange("Aapka naam dale", "Ringtone"))
        assertNull(GradientGeometry.highlightRange("Aapka naam dale", ""))
    }
}
