package com.spacewire.meratune.ui

/**
 * Virtual-loop paging maths for [OnboardingCarouselView]: the ViewPager2 adapter reports
 * [virtualCount] pages and maps each virtual position back to a real slide with [realIndex], so
 * swiping and auto-advance can run past the last slide and wrap to the first. Pure, so it is
 * unit-tested.
 */
object CarouselPaging {

    /** Pages per real slide; large enough that nobody swipes to either end in one session. */
    private const val LOOP_MULTIPLIER = 10_000

    /** Adapter item count for [slideCount] slides. A single slide does not loop. */
    fun virtualCount(slideCount: Int): Int =
        if (slideCount <= 1) slideCount.coerceAtLeast(0) else slideCount * LOOP_MULTIPLIER

    /** The real slide (0 until [slideCount]) shown at virtual [position]; also safe for negatives. */
    fun realIndex(position: Int, slideCount: Int): Int {
        if (slideCount <= 0) return 0
        return ((position % slideCount) + slideCount) % slideCount
    }

    /** A virtual position near the middle of the range that shows real slide [real]. */
    fun startPosition(real: Int, slideCount: Int): Int {
        if (slideCount <= 1) return 0
        val middle = virtualCount(slideCount) / 2
        return middle - realIndex(middle, slideCount) + realIndex(real, slideCount)
    }

    /** True within one loop of either end, where the pager should silently jump back to the middle. */
    fun needsRecentre(position: Int, slideCount: Int): Boolean {
        if (slideCount <= 1) return false
        return position < slideCount || position >= virtualCount(slideCount) - slideCount
    }
}
