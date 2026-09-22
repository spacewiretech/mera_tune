package com.spacewire.meratune.util

import com.spacewire.meratune.data.Tune
import kotlin.random.Random

object TuneStatsUtils {
    private const val MIN_COUNT = 100
    private const val MAX_COUNT = 3_999

    fun withRandomStats(tunes: List<Tune>): List<Tune> {
        return tunes.map { tune ->
            val (likes, views) = randomStatsForTune(tune.id)
            tune.copy(likesCount = likes, viewsCount = views)
        }
    }

    private fun randomStatsForTune(tuneId: String): Pair<Int, Int> {
        val random = Random(tuneId.hashCode())
        val likes = random.nextInt(MIN_COUNT, MAX_COUNT + 1)
        val maxViews = MAX_COUNT.coerceAtLeast(likes)
        val views = (likes + random.nextInt(50, 601)).coerceAtMost(maxViews)
        return likes to views
    }
}
