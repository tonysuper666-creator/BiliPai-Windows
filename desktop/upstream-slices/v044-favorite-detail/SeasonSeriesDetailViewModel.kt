package com.android.purebilibili.feature.space

import android.app.Application
import com.android.purebilibili.core.network.NetworkModule
import com.android.purebilibili.core.store.TokenManager
import com.android.purebilibili.data.model.response.FavFolder
import com.android.purebilibili.data.model.response.VideoItem
import com.android.purebilibili.data.repository.FavoriteRepository
import com.android.purebilibili.feature.list.BaseListViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException

class SeasonSeriesDetailViewModel(application: Application) : BaseListViewModel(application, "") {

    private val spaceApi = NetworkModule.spaceApi
    
    // Parameters
    private var type: String = "" // "season" or "series"
    private var id: Long = 0
    private var mid: Long = 0
    private var ownerName: String = ""
    private var pageTitle: String = ""

    val isFavoriteDetail: Boolean
        get() = type == SpaceCollectionDetailType.FAVORITE.raw ||
            type == SpaceCollectionDetailType.FAVORITE_SEASON.raw

    val canCopyFavoriteResources: Boolean
        get() = id > 0L && mid > 0L && canCopySpaceFavoriteResources(type, TokenManager.midCache)

    val canManageFavoriteResources: Boolean
        get() = id > 0L && canManageSpaceFavoriteResources(type, mid, TokenManager.midCache)

    // Pagination
    private var currentPage = 1
    private var hasMore = true
    private var isLoadingMore = false
    
    private val _isLoadingMoreState = MutableStateFlow(false)
    val isLoadingMoreState = _isLoadingMoreState.asStateFlow()
    
    private val _hasMoreState = MutableStateFlow(true)
    val hasMoreState = _hasMoreState.asStateFlow()
    override fun onAccountChanged() {
        currentPage = 1
        hasMore = true
        isLoadingMore = false
        _isLoadingMoreState.value = false
        _hasMoreState.value = true
        _favoriteDetailProgressState.value = FavoriteDetailProgressState()
        _transferFolders.value = emptyList()
        _isManagingState.value = false
        _uiState.value = _uiState.value.copy(
            title = pageTitle,
            canRemoveItems = canManageFavoriteResources,
        )
        if (id > 0L) loadData()
    }


    data class FavoriteDetailProgressState(
        val loadedCount: Int = 0,
        val expectedCount: Int = 0,
        val currentPage: Int = 1,
        val lastAddedCount: Int = 0,
        val invalidCount: Int = 0,
        val hasMore: Boolean = false
    )

    private val _favoriteDetailProgressState = MutableStateFlow(FavoriteDetailProgressState())
    val favoriteDetailProgressState = _favoriteDetailProgressState.asStateFlow()

    fun init(type: String, id: Long, mid: Long, title: String, ownerName: String = "") {
        val request = resolveSpaceCollectionDetailRequest(type, id, mid, title)
        if (request == null) {
            this.type = ""
            this.id = 0L
            this.mid = 0L
            this.ownerName = ownerName
            this.pageTitle = title
            _uiState.value = _uiState.value.copy(title = title, items = emptyList(), canRemoveItems = false)
            return
        }
        this.type = request.type.raw
        this.id = request.id
        this.mid = request.mid
        this.ownerName = ownerName
        this.pageTitle = request.title
        _favoriteDetailProgressState.value = FavoriteDetailProgressState()
        
        _uiState.value = _uiState.value.copy(
            title = request.title,
            canRemoveItems = canManageFavoriteResources,
        )
        loadData()
    }

    override suspend fun fetchItems(): List<VideoItem> {
        if (id == 0L) return emptyList()

        if (type == "season") {
            return fetchSeasonArchives()
        } else if (type == "series") {
            return fetchSeriesArchives()
        } else if (isFavoriteDetail) {
            return fetchFavoriteResources()
        }
        return emptyList()
    }

    private suspend fun fetchSeasonArchives(): List<VideoItem> {
        currentPage = 1
        try {
            val response = spaceApi.getSeasonArchives(mid, id, currentPage)
            if (response.code == 0) {
                val data = response.data
                if (data != null) {
                    val archives = data.archives
                    hasMore = archives.size >= 30 // Assumption based on page size
                     _hasMoreState.value = hasMore
                     
                    return archives.map { item -> mapSeasonArchiveToVideoItem(item, mid, ownerName) }
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            e.printStackTrace()
        }
        return emptyList()
    }

    private suspend fun fetchSeriesArchives(): List<VideoItem> {
        currentPage = 1
         try {
            val response = spaceApi.getSeriesArchives(mid, id, currentPage)
            if (response.code == 0) {
                val data = response.data
                if (data != null) {
                    val archives = data.archives
                    hasMore = archives.size >= 30
                    _hasMoreState.value = hasMore

                    return archives.map { item -> mapSeriesArchiveToVideoItem(item, mid, ownerName) }
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            e.printStackTrace()
        }
        return emptyList()
    }

    private suspend fun fetchFavoriteResources(
        page: Int = 1,
        append: Boolean = false,
    ): List<VideoItem> {
        val response = if (type == SpaceCollectionDetailType.FAVORITE.raw) {
            FavoriteRepository.getFavoriteList(mediaId = id, pn = page)
        } else {
            FavoriteRepository.getFavoriteSeasonList(seasonId = id, pn = page)
        }.getOrThrow()
        if (type == SpaceCollectionDetailType.FAVORITE.raw) {
            mid = response.info?.upper?.mid?.takeIf { it > 0L }
                ?: response.info?.mid?.takeIf { it > 0L }
                ?: mid
            _uiState.value = _uiState.value.copy(canRemoveItems = canManageFavoriteResources)
        }
        val mediaItems = response.medias.orEmpty()
        val items = mediaItems.map {
            it.toVideoItem(ownerFallbackMid = mid, ownerFallbackName = ownerName)
        }
        val previousProgress = if (append) _favoriteDetailProgressState.value else FavoriteDetailProgressState()
        hasMore = resolveSpaceCollectionHasMore(type, items.size, response.has_more)
        _hasMoreState.value = hasMore
        currentPage = page
        _favoriteDetailProgressState.value = FavoriteDetailProgressState(
            loadedCount = if (append) _uiState.value.items.size + items.size else items.size,
            expectedCount = response.info?.media_count ?: previousProgress.expectedCount,
            currentPage = page,
            lastAddedCount = items.size,
            invalidCount = previousProgress.invalidCount + mediaItems.count { it.attr != 0 },
            hasMore = hasMore,
        )
        return items
    }
    
    // ---- 收藏夹详情批量管理（对齐 PiliPlus fav_detail）----

    private val _transferFolders = MutableStateFlow<List<FavFolder>>(emptyList())
    val transferFolders = _transferFolders.asStateFlow()

    private val _isManagingState = MutableStateFlow(false)
    val isManagingState = _isManagingState.asStateFlow()

    /** 加载复制/移动的目标收藏夹列表（排除当前收藏夹）。 */
    fun loadTransferFolders() {
        if (!canCopyFavoriteResources) return
        accountRequests.launch {
            val accountMid = TokenManager.midCache?.takeIf { it > 0L } ?: return@launch
            val folders = FavoriteRepository.getFavFolders(accountMid).getOrNull().orEmpty()
            _transferFolders.value = folders.filter { it.id != id }
        }
    }

    /** 批量取消收藏（移除所选内容），成功后刷新列表。 */
    fun removeFavoriteResources(resourceIds: Set<Long>) {
        if (!canManageFavoriteResources || resourceIds.isEmpty() || _isManagingState.value) return
        _isManagingState.value = true
        accountRequests.launch {
            try {
                if (!canManageFavoriteResources) return@launch
                FavoriteRepository.removeResources(id, resourceIds).getOrThrow()
                loadData()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                android.widget.Toast.makeText(
                    getApplication(), e.message ?: "移除失败，请稍后重试", android.widget.Toast.LENGTH_SHORT
                ).show()
            } finally {
                _isManagingState.value = false
            }
        }
    }

    /** 批量复制/移动所选内容到目标收藏夹，移动成功后刷新列表。 */
    fun copyOrMoveFavoriteResources(resourceIds: Set<Long>, targetMediaId: Long, copy: Boolean) {
        if (
            !canCopyFavoriteResources || (!copy && !canManageFavoriteResources) ||
            resourceIds.isEmpty() || targetMediaId <= 0L || _isManagingState.value
        ) return
        _isManagingState.value = true
        accountRequests.launch {
            try {
                if (!canCopyFavoriteResources || (!copy && !canManageFavoriteResources)) return@launch
                FavoriteRepository.copyOrMoveResources(
                    sourceMediaId = id,
                    targetMediaId = targetMediaId,
                    mid = mid,
                    resourceIds = resourceIds,
                    copy = copy,
                ).getOrThrow()
                if (!copy) loadData()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                android.widget.Toast.makeText(
                    getApplication(), e.message ?: "操作失败，请稍后重试", android.widget.Toast.LENGTH_SHORT
                ).show()
            } finally {
                _isManagingState.value = false
            }
        }
    }

    fun retryLoadMore() {
        _uiState.value = _uiState.value.copy(loadMoreError = null)
        loadMore()
    }

    fun loadMore() {
        if (isLoadingMore || !hasMore || _uiState.value.isLoading || _uiState.value.loadMoreError != null) return
        isLoadingMore = true
        _isLoadingMoreState.value = true
        val nextPage = currentPage + 1
        accountRequests.launch {
            try {
                val newItems = when (type) {
                    "season" -> {
                        val response = spaceApi.getSeasonArchives(mid, id, nextPage)
                        check(response.code == 0) { "加载更多失败：${response.code}" }
                        val data = checkNotNull(response.data) { "合集内容响应不完整，请重试" }
                        data.archives.map { mapSeasonArchiveToVideoItem(it, mid, ownerName) }
                    }
                    "series" -> {
                        val response = spaceApi.getSeriesArchives(mid, id, nextPage)
                        check(response.code == 0) { "加载更多失败：${response.code}" }
                        val data = checkNotNull(response.data) { "系列内容响应不完整，请重试" }
                        data.archives.map { mapSeriesArchiveToVideoItem(it, mid, ownerName) }
                    }
                    "favorite", "favorite_season" -> fetchFavoriteResources(nextPage, append = true)
                    else -> return@launch
                }
                if (!isFavoriteDetail) {
                    hasMore = resolveSpaceCollectionHasMore(type, newItems.size)
                    _hasMoreState.value = hasMore
                }
                currentPage = nextPage
                _uiState.value = _uiState.value.copy(
                    items = _uiState.value.items + newItems,
                    loadMoreError = null,
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // 失败不推进页码、不关闭 hasMore，保留已加载内容供同页重试。
                _uiState.value = _uiState.value.copy(
                    loadMoreError = e.message ?: "加载更多失败，请稍后重试"
                )
            } finally {
                isLoadingMore = false
                _isLoadingMoreState.value = false
            }
        }
    }

    init {
        loadData()
    }
}
