package com.spacewire.meratune.util

import com.spacewire.meratune.util.GradientTextHelper.Direction

/**
 * Pure geometry behind [GradientTextHelper] (no Android types, so it is JVM-testable). Bounds are in
 * the TextView layout's coordinates, which is the space the paint shader is evaluated in.
 */
internal object GradientGeometry {

    data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float)

    /** `LinearGradient` start point (x0, y0) and end point (x1, y1); colours[0] sits at the start. */
    data class Endpoints(val x0: Float, val y0: Float, val x1: Float, val y1: Float) {
        val isDegenerate: Boolean get() = x0 == x1 && y0 == y1
    }

    fun endpoints(direction: Direction, bounds: Bounds): Endpoints = when (direction) {
        Direction.HORIZONTAL -> Endpoints(bounds.left, bounds.top, bounds.right, bounds.top)
        Direction.VERTICAL -> Endpoints(bounds.left, bounds.top, bounds.left, bounds.bottom)
        Direction.DIAGONAL_UP -> Endpoints(bounds.left, bounds.bottom, bounds.right, bounds.top)
        Direction.DIAGONAL_DOWN -> Endpoints(bounds.left, bounds.top, bounds.right, bounds.bottom)
    }

    /**
     * [positions] if they can be passed to `LinearGradient` with [colorCount] colours: one per
     * colour, each in 0..1, non-decreasing. Otherwise null, which means evenly spaced stops.
     */
    fun validPositions(positions: FloatArray?, colorCount: Int): FloatArray? {
        if (positions == null || positions.size != colorCount) return null
        for (index in positions.indices) {
            val position = positions[index]
            if (position.isNaN() || position < 0f || position > 1f) return null
            if (index > 0 && position < positions[index - 1]) return null
        }
        return positions
    }

    /** Range of the first occurrence of [highlight] in [fullText]; null if absent or empty. */
    fun highlightRange(fullText: CharSequence, highlight: CharSequence): IntRange? {
        if (highlight.isEmpty()) return null
        val start = fullText.indexOf(highlight.toString())
        if (start < 0) return null
        return start until start + highlight.length
    }
}
