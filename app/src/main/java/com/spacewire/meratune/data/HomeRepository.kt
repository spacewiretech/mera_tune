package com.spacewire.meratune.data

import com.spacewire.meratune.network.SupabaseProvider
import com.spacewire.meratune.util.TuneStatsUtils
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class GenerationLanguageRow(
    val language: String,
    @SerialName("tts_enabled") val ttsEnabled: Boolean = false,
)

class HomeRepository {
    suspend fun fetchActiveCategories(): List<Category> {
        return SupabaseProvider.client
            .from("category")
            .select {
                filter {
                    eq("is_active", true)
                }
                order("sort_order", Order.ASCENDING)
                order("name", Order.ASCENDING)
            }
            .decodeList()
    }

    /**
     * Active tunes for Home. Uses an explicit column list so the payload does not grow with the
     * authoring columns on `tune`, and so a later column-level lockdown of those columns
     * (see migration 20260923121000, section 2) cannot break this query.
     */
    suspend fun fetchActiveTunes(categoryId: String? = null): List<Tune> {
        return SupabaseProvider.client
            .from("tune")
            .select(Columns.raw(TUNE_COLUMNS)) {
                filter {
                    eq("is_active", true)
                    categoryId?.let { eq("category_id", it) }
                }
                order("created_at", Order.DESCENDING)
            }
            .decodeList<Tune>()
            .let(TuneStatsUtils::withRandomStats)
    }

    /**
     * Songs that have name-slot assets and can be personalized. Ordered by curated
     * `featured_rank` (nulls last) then newest first. Stats are left as stored.
     */
    suspend fun fetchPersonalizableTunes(): List<Tune> {
        return SupabaseProvider.client
            .from("tune")
            .select(Columns.raw(PERSONALIZABLE_TUNE_COLUMNS)) {
                filter {
                    eq("is_active", true)
                    eq("is_personalizable", true)
                }
                order("featured_rank", Order.ASCENDING, nullsFirst = false)
                order("created_at", Order.DESCENDING)
            }
            .decodeList<Tune>()
    }

    /** Storage language value (e.g. `"Hindi"`) -> whether TTS generation is enabled for it. */
    suspend fun fetchGenerationLanguages(): Map<String, Boolean> {
        return SupabaseProvider.client
            .from("generation_languages")
            .select(Columns.raw("language, tts_enabled"))
            .decodeList<GenerationLanguageRow>()
            .associate { row -> row.language.trim() to row.ttsEnabled }
    }

    private companion object {
        const val TUNE_COLUMNS =
            "id, name, category_id, gender, language, tune_url, is_active, likes_count, views_count, created_at, " +
                "category:category_id(id, name, image_url)"
        const val PERSONALIZABLE_TUNE_COLUMNS =
            "$TUNE_COLUMNS, is_personalizable, featured_rank, sample_name, title_template, assets_version"
    }
}
