// GENERATED from app/src/main/java/com/android/purebilibili/feature/story/StoryViewModel.kt; do not edit.
// LF-normalized SHA-256: d16fd532890ca4b229dca79b0deb00aac7b8acd2a68d2316b0e507fb75fc4bef
package com.android.purebilibili.feature.story
import com.android.purebilibili.data.model.response.StoryItem
import android.util.Log as Logger
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class StoryUiState(
    val items: List<StoryItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val currentIndex: Int = 0
)

internal class StoryViewModel(private val environment: com.bilipai.desktop.ui.DesktopOriginalStoryEnvironment) {
    private val viewModelScope get() = environment.scope
    private val VideoRepository get() = environment.requests
    private fun <T> MutableStateFlow(initial:T): MutableStateFlow<T> =
        com.bilipai.desktop.ui.DesktopHomeOwnedMutableStateFlow(initial, environment::commit)

    private val _uiState = MutableStateFlow(StoryUiState())
    val uiState: StateFlow<StoryUiState> = _uiState.asStateFlow()

    /** Home feed page index for load-more (0-based). */
    private var nextPageIndex: Int = 0

    init {
        loadInitialStories()
    }

    private fun loadInitialStories() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            nextPageIndex = 0

            environment.assertCurrent()
            val result = VideoRepository.getHomeVideos(idx = 0)
            environment.assertCurrent()
            result.onSuccess { videos ->
                val items = videoItemsToStoryItems(videos)
                _uiState.value = _uiState.value.copy(
                    items = items,
                    isLoading = false,
                    error = if (items.isEmpty()) "暂时没有可播放的推荐视频" else null
                )
                nextPageIndex = 1
                Logger.d("StoryVM", "首页推荐竖屏流: ${items.size} 条")
                items.firstOrNull()?.let { first ->
                    Logger.d(
                        "StoryVM",
                        "首条: title=${first.title.take(20)} bvid=${first.playerArgs?.bvid}"
                    )
                }
            }.onFailure { e ->
                Logger.e("StoryVM", "加载首页推荐失败: ${e.message}")
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "加载失败"
                )
            }
        }
    }

    fun loadMoreStories() {
        if (_uiState.value.isLoading) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val page = nextPageIndex

            environment.assertCurrent()
            val result = VideoRepository.getHomeVideos(idx = page)
            environment.assertCurrent()
            result.onSuccess { videos ->
                val newItems = videoItemsToStoryItems(videos)
                val currentItems = _uiState.value.items
                val mergedItems = mergeStoryFeedItems(
                    existingItems = currentItems,
                    newItems = newItems
                )
                _uiState.value = _uiState.value.copy(
                    items = mergedItems,
                    isLoading = false
                )
                if (newItems.isNotEmpty()) {
                    nextPageIndex = page + 1
                }
                Logger.d(
                    "StoryVM",
                    "加载更多推荐: page=$page +${newItems.size}，合计 ${mergedItems.size}"
                )
            }.onFailure { e ->
                Logger.e("StoryVM", "加载更多失败: ${e.message}")
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
    }

    fun updateCurrentIndex(index: Int) {
        _uiState.value = _uiState.value.copy(currentIndex = index)

        val items = _uiState.value.items
        if (index >= items.size - 3 && items.isNotEmpty()) {
            loadMoreStories()
        }
    }

    fun refresh() {
        nextPageIndex = 0
        loadInitialStories()
    }
}
