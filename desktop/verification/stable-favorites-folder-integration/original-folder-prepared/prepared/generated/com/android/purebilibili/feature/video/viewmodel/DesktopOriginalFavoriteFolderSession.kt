package com.android.purebilibili.feature.video.viewmodel
import com.android.purebilibili.feature.video.policy.resolveFavoriteFolderMediaId
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import com.bilipai.desktop.ui.DesktopFavoriteFolderEnvironment

/** Original drawer state only. Root remains the sole video/account/count authority. */
class DesktopOriginalFavoriteFolderSession(private val environment:DesktopFavoriteFolderEnvironment) {
    private val viewModelScope:CoroutineScope get()=environment.scope
    private fun toast(message:String)=environment.showFeedback(message)
    private var loadRequestId=0L
    private var saveRequestId=0L
    private var loadJob:Job?=null
    private var saveJob:Job?=null
    private val createJobs=java.util.concurrent.ConcurrentHashMap.newKeySet<Job>()
    fun close() { environment.close(); ++loadRequestId; ++saveRequestId; loadJob?.cancel(); saveJob?.cancel(); createJobs.forEach { it.cancel() }; createJobs.clear() }
    private val _favoriteFolderDialogVisible = MutableStateFlow(false)
    val favoriteFolderDialogVisible = _favoriteFolderDialogVisible.asStateFlow()
    
    private val _favoriteFolders = MutableStateFlow<List<com.android.purebilibili.data.model.response.FavFolder>>(emptyList())
    val favoriteFolders = _favoriteFolders.asStateFlow()
    
    private val _isFavoriteFoldersLoading = MutableStateFlow(false)
    val isFavoriteFoldersLoading = _isFavoriteFoldersLoading.asStateFlow()

    private val _favoriteSelectedFolderIds = MutableStateFlow<Set<Long>>(emptySet())
    val favoriteSelectedFolderIds = _favoriteSelectedFolderIds.asStateFlow()

    private val _isSavingFavoriteFolders = MutableStateFlow(false)
    val isSavingFavoriteFolders = _isSavingFavoriteFolders.asStateFlow()

    private val _favoriteFolderSaveEvent = MutableStateFlow<FavoriteFolderSaveEvent?>(null)
    internal val favoriteFolderSaveEvent = _favoriteFolderSaveEvent.asStateFlow()

    private var lastSavedFavoriteFolderIds: Set<Long> = emptySet()
    private var favoriteFoldersBoundAid: Long? = null
    private var favoriteFolderSaveEventVersion: Long = 0L

    fun showFavoriteFolderDialog(requestedAid: Long? = null) {
        environment.assertOwned()
        val currentAid = environment.currentAid()
        val targetAid = resolveFavoriteFolderDialogTargetAid(
            requestedAid = requestedAid,
            currentAid = currentAid
        ) ?: return
        if (favoriteFoldersBoundAid != null && favoriteFoldersBoundAid != targetAid) {
            lastSavedFavoriteFolderIds = emptySet()
            _favoriteSelectedFolderIds.value = emptySet()
            _favoriteFolders.value = emptyList()
        }
        _favoriteFolderDialogVisible.value = true
        _favoriteSelectedFolderIds.value = lastSavedFavoriteFolderIds
        val hasCacheForCurrentAid =
            favoriteFoldersBoundAid == targetAid && _favoriteFolders.value.isNotEmpty()
        if (!hasCacheForCurrentAid) {
            loadFavoriteFolders(aid = targetAid)
        }
    }

    fun dismissFavoriteFolderDialog() {
        _favoriteFolderDialogVisible.value = false
    }

    fun invalidateFavoriteFolderCache() {
        ++loadRequestId
        loadJob?.cancel()
        _isFavoriteFoldersLoading.value = false
        favoriteFoldersBoundAid = null
        lastSavedFavoriteFolderIds = emptySet()
        _favoriteFolders.value = emptyList()
        _favoriteSelectedFolderIds.value = emptySet()
    }

    private fun loadFavoriteFolders(aid: Long? = null, keepCurrentSelection: Boolean = false) {
        val request = ++loadRequestId
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
            favoriteFoldersBoundAid = aid
            _isFavoriteFoldersLoading.value = true
            val result = environment.getFavoriteFolders(aid)
            currentCoroutineContext().ensureActive()
            if (request != loadRequestId) return@launch
            environment.assertOwned()
            result.fold(
                onSuccess = { folders ->
                    _favoriteFolders.value = folders
                    val selectedFromServer = folders
                        .asSequence()
                        .filter { it.fav_state == 1 }
                        .map { resolveFavoriteFolderMediaId(it) }
                        .filter { it > 0L }
                        .toSet()

                    lastSavedFavoriteFolderIds = selectedFromServer

                    _favoriteSelectedFolderIds.value = if (keepCurrentSelection) {
                        val availableFolderIds = folders
                            .asSequence()
                            .map { resolveFavoriteFolderMediaId(it) }
                            .filter { it > 0L }
                            .toSet()
                        val keptSelection = _favoriteSelectedFolderIds.value.intersect(availableFolderIds)
                        if (keptSelection.isEmpty() && selectedFromServer.isNotEmpty()) {
                            selectedFromServer
                        } else {
                            keptSelection
                        }
                    } else {
                        selectedFromServer
                    }

                    updateFavoriteUiState(
                        targetAid = aid,
                        selectedFolderIds = lastSavedFavoriteFolderIds
                    )
                },
                onFailure = { e ->
                    toast("加载收藏夹失败: ${e.message}")
                }
            )
            } finally {
                if (request == loadRequestId) _isFavoriteFoldersLoading.value = false
            }
        }
    }

    fun toggleFavoriteFolderSelection(folderId: Long) {
        environment.assertOwned()
        if (folderId <= 0L) return
        _favoriteSelectedFolderIds.update { selected ->
            if (selected.contains(folderId)) {
                selected - folderId
            } else {
                selected + folderId
            }
        }
    }

    fun toggleFavoriteFolderSelection(folder: com.android.purebilibili.data.model.response.FavFolder) {
        toggleFavoriteFolderSelection(resolveFavoriteFolderMediaId(folder))
    }

    fun saveFavoriteFolderSelection() {
        environment.assertOwned()
        if (_isSavingFavoriteFolders.value) return
        val currentAid = environment.currentAid()
        val targetAid = resolveFavoriteFolderDialogTargetAid(
            requestedAid = favoriteFoldersBoundAid,
            currentAid = currentAid
        ) ?: return

        val selectedFolderIds = _favoriteSelectedFolderIds.value
        val originalFolderIds = lastSavedFavoriteFolderIds
        val mutation = resolveFavoriteFolderMutation(
            original = originalFolderIds,
            selected = selectedFolderIds
        )

        if (mutation.addFolderIds.isEmpty() && mutation.removeFolderIds.isEmpty()) {
            dismissFavoriteFolderDialog()
            toast("收藏夹未变更")
            return
        }

        val request = ++saveRequestId
        _isSavingFavoriteFolders.value = true
        saveJob = viewModelScope.launch {
            try {
            val result = environment.updateFavoriteFolders(
                aid = targetAid,
                addFolderIds = mutation.addFolderIds,
                removeFolderIds = mutation.removeFolderIds
            )

            currentCoroutineContext().ensureActive()
            if (request != saveRequestId) return@launch
            environment.assertOwned()
            result.onSuccess {
                lastSavedFavoriteFolderIds = selectedFolderIds
                _favoriteFolders.update { folders ->
                    folders.map { folder ->
                        folder.copy(
                            fav_state = if (selectedFolderIds.contains(resolveFavoriteFolderMediaId(folder))) 1 else 0
                        )
                    }
                }
                if (shouldSyncFavoriteFolderUiState(targetAid = targetAid, currentAid = currentAid)) {
                    applyFavoriteSaveUiState(
                        originalFolderIds = originalFolderIds,
                        selectedFolderIds = selectedFolderIds
                    )
                }
                favoriteFolderSaveEventVersion += 1L
                _favoriteFolderSaveEvent.value = FavoriteFolderSaveEvent(
                    aid = targetAid,
                    isFavorited = selectedFolderIds.isNotEmpty(),
                    version = favoriteFolderSaveEventVersion
                )
                dismissFavoriteFolderDialog()
                toast(if (selectedFolderIds.isEmpty()) "已取消收藏" else "收藏设置已保存")
            }.onFailure { e ->
                toast("收藏失败: ${e.message}")
            }
            } finally {
                if (request == saveRequestId) _isSavingFavoriteFolders.value = false
            }
        }
    }

    private fun applyFavoriteSaveUiState(
        originalFolderIds: Set<Long>,
        selectedFolderIds: Set<Long>
    ) {
        val resolvedState = resolveFavoriteSaveUiState(
            originalFolderIds = originalFolderIds,
            selectedFolderIds = selectedFolderIds,
            currentFavoriteCount = environment.currentFavoriteCount()
        )
        environment.confirmFavoriteSave(resolvedState.isFavorited, resolvedState.favoriteCount)
    }

    private fun updateFavoriteUiState(targetAid: Long?, selectedFolderIds: Set<Long>) {
        val currentAid = environment.currentAid()
        if (!shouldSyncFavoriteFolderUiState(targetAid = targetAid, currentAid = currentAid)) {
            return
        }
        environment.confirmFavoriteLoaded(selectedFolderIds.isNotEmpty())
    }

    fun createFavoriteFolder(title: String, intro: String = "", isPrivate: Boolean = false) {
        environment.assertOwned()
        val createJob = viewModelScope.launch {
            val result = environment.createFavFolder(title, intro, isPrivate)
            currentCoroutineContext().ensureActive()
            environment.assertOwned()
            result.onSuccess {
                toast("创建收藏夹成功")
                loadFavoriteFolders(aid = favoriteFoldersBoundAid, keepCurrentSelection = true)
            }.onFailure { e ->
                toast("创建失败: ${e.message}")
            }
        }
        createJobs.add(createJob)
        createJob.invokeOnCompletion { createJobs.remove(createJob) }
    }
}
