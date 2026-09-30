// GENERATED from app/src/main/java/com/android/purebilibili/feature/dynamic/DynamicViewModel.kt; do not edit.
// LF-normalized SHA-256: 9981e964c6b90b51b93bfc7808490043fe60b460a8cc6491027afbbe2d0d13f5
package com.android.purebilibili.feature.dynamic
import com.android.purebilibili.data.model.response.DynamicItem
import kotlinx.collections.immutable.*

data class DynamicTimelinePageState(
    val items: ImmutableList<DynamicItem> = persistentListOf(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val hasMore: Boolean = true,
    val incrementalRefreshBoundaryKey: String? = null,
    val incrementalPrependedCount: Int = 0,
    val errorSource: DynamicFeedErrorSource = DynamicFeedErrorSource.NONE,
    val isCachePlaceholder: Boolean = false
)
