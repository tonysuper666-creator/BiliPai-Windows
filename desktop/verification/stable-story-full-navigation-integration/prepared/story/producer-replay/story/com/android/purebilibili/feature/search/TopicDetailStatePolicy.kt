// GENERATED from app/src/main/java/com/android/purebilibili/feature/search/TopicDetailViewModel.kt; do not edit.
// LF-normalized SHA-256: 16d33fa80b4e92026bb83681f050eb85fd59f397ac15f7b7963dcfa3b6f6cbc3
package com.android.purebilibili.feature.search
import com.android.purebilibili.data.model.response.*

data class TopicDetailUiState(
    val isLoading: Boolean = false,
    val isSwitchingSort: Boolean = false,
    val isLoadingMore: Boolean = false,
    val details: TopicTopDetails? = null,
    val items: List<DynamicItem> = emptyList(),
    val offset: String = "",
    val hasMore: Boolean = false,
    val sortOptions: List<TopicSortOption> = emptyList(),
    val selectedSortBy: Int = 0,
    val isPublishing: Boolean = false,
    val publishError: String? = null,
    val error: String? = null
)

internal fun mergeDynamicItems(
    existing: List<DynamicItem>,
    incoming: List<DynamicItem>
): List<DynamicItem> {
    val seen = LinkedHashSet<String>()
    val merged = ArrayList<DynamicItem>(existing.size + incoming.size)
    (existing + incoming).forEach { item ->
        val key = item.id_str.ifBlank { item.hashCode().toString() }
        if (seen.add(key)) merged += item
    }
    return merged
}
