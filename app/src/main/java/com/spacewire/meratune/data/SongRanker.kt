package com.spacewire.meratune.data

/** Which tier the picker had to fall back to when the requested language had no songs. */
enum class FallbackLevel {
    NONE,
    HINDI,
    ANY,
    ;

    val analyticsValue: String
        get() = name.lowercase()
}

/** Client-side voice filter for the song picker. */
enum class VoiceFilter {
    ALL,
    MALE,
    FEMALE,
    ;

    /** `null` for [ALL] so the property is omitted from analytics. */
    val analyticsValue: String?
        get() = if (this == ALL) null else name.lowercase()

    /** The [Tune.voiceKey] this filter keeps, or `null` for [ALL]. */
    val voiceKey: String?
        get() = when (this) {
            ALL -> null
            MALE -> Tune.VOICE_MALE
            FEMALE -> Tune.VOICE_FEMALE
        }
}

/** @property rank 1-based position of [tune] in the unfiltered tier list (stable across voice filters). */
data class RankedSong(
    val tune: Tune,
    val rank: Int,
)

data class RankedSongs(
    val songs: List<RankedSong>,
    val fallbackLevel: FallbackLevel,
    /** Distinct categories of the returned songs, in order of first appearance. */
    val categories: List<Category>,
    /** Language of the tier that was used; what the generation request should send. */
    val effectiveLanguage: String,
)

/**
 * Pure ranking for the song picker.
 *
 * Tiers: songs in the requested language -> else Hindi -> else any song.
 * Within the tier songs are ordered by `featured_rank` (nulls last) then name, ranked 1..n,
 * then the voice filter is applied and the list is capped at [rank]'s `limit`.
 */
object SongRanker {
    const val DEFAULT_LIMIT = 10
    const val HINDI = "Hindi"

    fun rank(
        all: List<Tune>,
        language: String,
        voiceFilter: VoiceFilter = VoiceFilter.ALL,
        limit: Int = DEFAULT_LIMIT,
    ): RankedSongs {
        val requested = language.trim()

        val exactTier = all.filter { it.language.trim().equals(requested, ignoreCase = true) }
        val hindiTier = if (exactTier.isEmpty()) {
            all.filter { it.language.trim().equals(HINDI, ignoreCase = true) }
        } else {
            emptyList()
        }

        val (tier, fallbackLevel, effectiveLanguage) = when {
            exactTier.isNotEmpty() -> Triple(exactTier, FallbackLevel.NONE, requested)
            hindiTier.isNotEmpty() -> Triple(hindiTier, FallbackLevel.HINDI, HINDI)
            else -> Triple(all, FallbackLevel.ANY, requested)
        }

        val ranked = tier
            .sortedWith(TIER_ORDER)
            .mapIndexed { index, tune -> RankedSong(tune = tune, rank = index + 1) }

        val wantedVoice = voiceFilter.voiceKey
        val songs = ranked
            .filter { wantedVoice == null || it.tune.voiceKey == wantedVoice }
            .take(limit.coerceAtLeast(0))

        val categories = songs
            .mapNotNull { it.tune.category }
            .distinctBy { it.id }

        return RankedSongs(
            songs = songs,
            fallbackLevel = fallbackLevel,
            categories = categories,
            effectiveLanguage = effectiveLanguage,
        )
    }

    private val TIER_ORDER: Comparator<Tune> =
        compareBy<Tune, Int?>(nullsLast()) { it.featuredRank }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            .thenBy { it.name }
            .thenBy { it.id }
}
