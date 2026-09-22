package com.spacewire.meratune.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Category(
    val id: String,
    val name: String,
    @SerialName("image_url") val imageUrl: String = "",
    @SerialName("is_active") val isActive: Boolean = true,
) {
    companion object {
        const val ALL_CATEGORY_ID = "__all__"
    }
}

@Serializable
data class Tune(
    val id: String,
    val name: String,
    @SerialName("category_id") val categoryId: String,
    val gender: String,
    val language: String,
    @SerialName("tune_url") val tuneUrl: String,
    @SerialName("is_active") val isActive: Boolean = true,
    @SerialName("likes_count") val likesCount: Int = 0,
    @SerialName("views_count") val viewsCount: Int = 0,
    val category: Category? = null,
)
