package com.spacewire.meratune.data

import com.spacewire.meratune.network.SupabaseProvider
import com.spacewire.meratune.util.TuneStatsUtils
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order

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

    suspend fun fetchActiveTunes(categoryId: String? = null): List<Tune> {
        return SupabaseProvider.client
            .from("tune")
            .select(Columns.raw("*, category:category_id(id, name, image_url)")) {
                filter {
                    eq("is_active", true)
                    categoryId?.let { eq("category_id", it) }
                }
                order("created_at", Order.DESCENDING)
            }
            .decodeList<Tune>()
            .let(TuneStatsUtils::withRandomStats)
    }
}
