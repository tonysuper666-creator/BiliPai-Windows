package com.android.purebilibili.feature.profile
import com.bilipai.desktop.ui.DesktopProfileEnvironment
import com.bilipai.desktop.ui.DesktopOwnedProfileState
import kotlinx.coroutines.CoroutineScope

import com.android.purebilibili.core.network.WbiUtils
import com.bilipai.desktop.ui.getDesktopProfileSpaceAggregate
import com.android.purebilibili.core.network.DynamicDeleteRequest
import com.android.purebilibili.core.store.StoredAccountSession
import com.android.purebilibili.data.model.response.FavFolder
import com.android.purebilibili.data.model.response.NavData
import com.android.purebilibili.data.model.response.SpaceUserInfo
import com.android.purebilibili.data.model.response.SpaceVideoItem
import com.android.purebilibili.data.model.response.WbiImg
import com.android.purebilibili.feature.dynamic.DynamicDeleteAction
import com.android.purebilibili.feature.bangumi.MY_FOLLOW_TYPE_BANGUMI
import com.android.purebilibili.feature.home.UserState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.UnknownHostException
import java.net.SocketTimeoutException

import com.android.purebilibili.core.ui.wallpaper.ProfileWallpaperTransform
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

sealed class ProfileUiState {
    object Loading : ProfileUiState()
    data class Success(
        val user: UserState,
        val favoriteFolders: List<FavFolder> = emptyList(),
        val space: ProfileSpaceUiState = ProfileSpaceUiState(),
        val editableAccount: ProfileEditableAccountState = ProfileEditableAccountState()
    ) : ProfileUiState()
    // LoggedOut 代表“当前是游客/未登录状态”，UI 应该显示“去登录”
    // [Modified] Support wallpaper in guest mode
    data class LoggedOut(val topPhoto: String = "") : ProfileUiState()
    // 🔧 [新增] 网络错误状态 — 保持登录但显示离线提示
    data class Error(val message: String) : ProfileUiState()
}

internal fun shouldStartProfileLoad(
    hasLoadedOnce: Boolean,
    isLoadInFlight: Boolean,
    force: Boolean
): Boolean {
    if (force) return true
    return !hasLoadedOnce && !isLoadInFlight
}

internal fun shouldForceProfileLoadForAccountSessionRefresh(
    isCurrentPage: Boolean,
    accountSessionRefreshGeneration: Int,
    handledAccountSessionRefreshGeneration: Int
): Boolean {
    return isCurrentPage &&
        accountSessionRefreshGeneration > handledAccountSessionRefreshGeneration
}

internal class ProfileViewModel(private val environment: DesktopProfileEnvironment) {
    private val viewModelScope get() = environment.scope
    private val platform get() = environment.platform
    private val preferences get() = environment.preferences
    private val accountsPort get() = environment.accounts
    private fun <T> ownedFlow(initial: T) = DesktopOwnedProfileState(initial, environment)

    private val _uiState = ownedFlow<ProfileUiState>(ProfileUiState.Loading)
    val uiState = _uiState.asStateFlow()
    private val _accounts = ownedFlow<List<StoredAccountSession>>(emptyList())
    val accounts = _accounts.asStateFlow()
    private val _activeAccountMid = ownedFlow<Long?>(null)
    val activeAccountMid = _activeAccountMid.asStateFlow()
    private val _playbackAccountMid = ownedFlow<Long?>(null)
    val playbackAccountMid = _playbackAccountMid.asStateFlow()
    private var hasLoadedProfileOnce = false
    private var isProfileLoadInFlight = false
    private var profileLoadGeneration = 0L
    private var currentProfileWbiImg: WbiImg? = null

    init {
        refreshSavedAccounts()
    }

    fun refreshSavedAccounts() {
        environment.launchOwned(Dispatchers.IO) {
            val accounts = accountsPort.getAccounts()
            val activeAccountMid = accountsPort.getActiveAccountMid()
            val playbackAccountMid = accountsPort.getPlaybackAccountMid()
            withContext(Dispatchers.Main.immediate) {
                _accounts.value = accounts
                _activeAccountMid.value = activeAccountMid
                _playbackAccountMid.value = playbackAccountMid
            }
        }
    }

    fun setPlaybackAccount(mid: Long?, onSuccess: () -> Unit, onFailure: (String) -> Unit) {
        environment.launchOwned(Dispatchers.IO) {
            val updated = accountsPort.setPlaybackAccountMid(mid)
            withContext(Dispatchers.Main.immediate) {
                if (updated) {
                    refreshSavedAccounts()
                    environment.publishCallback { onSuccess() }
                } else {
                    environment.publishCallback { onFailure("播放账号不可用，请重新登录后再试") }
                }
            }
        }
    }

    fun loadProfile(force: Boolean = false) {
        if (!shouldStartProfileLoad(
                hasLoadedOnce = hasLoadedProfileOnce,
                isLoadInFlight = isProfileLoadInFlight,
                force = force
            )
        ) {
            return
        }
        hasLoadedProfileOnce = true
        isProfileLoadInFlight = true
        val generation = ++profileLoadGeneration
        val requestedMid = accountsPort.currentMid()
        val current = _uiState.value as? ProfileUiState.Success
        _uiState.value = if (current != null && current.user.mid == requestedMid) {
            current.copy(space = current.space.copy(isLoading = true, message = null))
        } else {
            ProfileUiState.Loading
        }
        environment.launchOwned {
            performProfileLoad(generation = generation, requestedMid = requestedMid)
        }
    }

    private suspend fun performProfileLoad(generation: Long, requestedMid: Long?) {
        var customBgUri = ""
        try {
            customBgUri = readProfileBackgroundUri()
            if (!accountsPort.hasSession()) {
                if (generation == profileLoadGeneration) {
                    _uiState.value = ProfileUiState.LoggedOut(
                        topPhoto = resolveProfileTopPhoto(customBgUri, "")
                    )
                }
                return
            }
            val data = environment.api.getNavInfo().data
            if (generation != profileLoadGeneration) return
            if (data == null || !data.isLogin) {
                clearInvalidProfileSession(generation, customBgUri)
                return
            }
            showProfileIdentity(generation, data, customBgUri)
            currentProfileWbiImg = data.wbi_img
            profileRequestOrNull { persistProfileSession(data) }
            loadProfileEnrichment(generation, data.mid, data.wbi_img)
        } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
            handleProfileLoadFailure(generation, requestedMid, customBgUri, e)
        } finally {
            if (generation == profileLoadGeneration) {
                isProfileLoadInFlight = false
            }
        }
    }

    private fun showProfileIdentity(
        generation: Long,
        data: NavData,
        customBgUri: String
    ) {
        if (generation != profileLoadGeneration) return
        val cached = (_uiState.value as? ProfileUiState.Success)
            ?.takeIf { it.user.mid == data.mid }
        val user = UserState(
            isLogin = true,
            face = data.face,
            name = data.uname,
            mid = data.mid,
            level = data.level_info.current_level,
            currentLevelMinExp = data.level_info.current_min ?: 0,
            currentLevelExp = data.level_info.current_exp ?: 0,
            nextLevelExp = data.level_info.next_exp ?: 0,
            coin = data.money,
            bcoin = data.wallet.bcoin_balance,
            following = cached?.user?.following ?: 0,
            follower = cached?.user?.follower ?: 0,
            dynamic = cached?.user?.dynamic ?: 0,
            isVip = data.vip.status == 1,
            vipLabel = data.vip.label.text,
            topPhoto = resolveProfileTopPhoto(customBgUri, data.top_photo)
        )
        _uiState.value = ProfileUiState.Success(
            user = user,
            favoriteFolders = cached?.favoriteFolders.orEmpty(),
            space = cached?.space?.copy(
                isLoading = true,
                message = null,
                contributionLoadState = ProfileContributionLoadState.LOADING
            ) ?: ProfileSpaceUiState(
                isLoading = true,
                contributionLoadState = ProfileContributionLoadState.LOADING
            ),
            editableAccount = cached?.editableAccount
                ?: resolveProfileEditableAccountState(account = null, user = user)
        )
    }

    private suspend fun persistProfileSession(data: NavData) {
        accountsPort.saveMid(data.mid)
        accountsPort.saveVipStatus(data.vip.status == 1)
        accountsPort.upsertCurrentAccount(data)
        refreshSavedAccounts()
    }

    private suspend fun loadProfileEnrichment(
        generation: Long,
        mid: Long,
        wbiImg: WbiImg?
    ) {
        try {
            supervisorScope {
                listOf(
                    launch { loadProfileStats(generation, mid) },
                    launch { loadProfileAccount(generation, mid) },
                    launch { loadProfileSpaceInfo(generation, mid, wbiImg) },
                    launch { loadProfileAggregate(generation, mid) },
                    launch { loadProfileContributions(generation, mid, wbiImg) },
                    launch { loadProfileFavoriteFolders(generation, mid) },
                    launch { loadProfileBangumi(generation, mid) },
                    launch { loadProfileDynamics(generation, mid) }
                ).joinAll()
            }
        } finally {
            updateProfileSuccess(generation, mid) { current ->
                current.copy(space = current.space.copy(isLoading = false))
            }
        }
    }

    private suspend fun loadProfileStats(generation: Long, mid: Long) {
        val stats = profileRequestOrNull { environment.api.getNavStat().data } ?: return
        updateProfileSuccess(generation, mid) { current ->
            current.copy(
                user = current.user.copy(
                    following = stats.following,
                    follower = stats.follower,
                    dynamic = stats.dynamic_count
                )
            )
        }
    }

    private suspend fun loadProfileAccount(generation: Long, mid: Long) {
        val account = profileRequestOrNull { environment.api.getMemberAccount().data } ?: return
        updateProfileSuccess(generation, mid) { current ->
            current.copy(
                editableAccount = current.editableAccount.copy(
                    name = account.uname.ifBlank { current.user.name },
                    birthday = account.birthday,
                    sex = account.sex,
                    sign = account.sign.ifBlank { current.editableAccount.sign }
                )
            )
        }
    }

    private suspend fun loadProfileSpaceInfo(
        generation: Long,
        mid: Long,
        wbiImg: WbiImg?
    ) {
        val info = profileRequestOrNull { fetchProfileSpaceInfo(mid, wbiImg) } ?: return
        updateProfileSuccess(generation, mid) { current ->
            current.copy(
                editableAccount = current.editableAccount.copy(
                    sign = current.editableAccount.sign.ifBlank { info.sign },
                    ipLocation = info.ipLocation.orEmpty()
                )
            )
        }
    }

    private suspend fun loadProfileAggregate(generation: Long, mid: Long) {
        val aggregate = profileRequestOrNull {
            environment.getDesktopProfileSpaceAggregate(mid).data
        } ?: return
        updateProfileSuccess(generation, mid) { current ->
            val nextSpace = mergeProfileAggregateState(current.space, aggregate)
            current.copy(
                favoriteFolders = nextSpace.favoriteFolders,
                space = nextSpace,
                editableAccount = current.editableAccount.copy(
                    sign = current.editableAccount.sign.ifBlank {
                        aggregate.card?.sign.orEmpty()
                    }
                )
            )
        }
    }

    private suspend fun loadProfileContributions(
        generation: Long,
        mid: Long,
        wbiImg: WbiImg?
    ) {
        updateProfileSuccess(generation, mid) { current ->
            current.copy(
                space = current.space.copy(
                    contributionLoadState = ProfileContributionLoadState.LOADING
                )
            )
        }
        val videos = profileRequestOrNull {
            fetchProfileContributionVideos(mid, wbiImg)
        }
        if (videos == null) {
            updateProfileSuccess(generation, mid) { current ->
                current.copy(
                    space = current.space.copy(
                        contributionLoadState = ProfileContributionLoadState.ERROR
                    )
                )
            }
            return
        }
        updateProfileSuccess(generation, mid) { latest ->
            latest.copy(
                space = mergeProfileContributionVideoState(
                    current = latest.space,
                    videos = videos.first,
                    totalCount = videos.second
                )
            )
        }
    }

    fun retryProfileContributions() {
        val current = _uiState.value as? ProfileUiState.Success ?: return
        val generation = profileLoadGeneration
        environment.launchOwned {
            loadProfileContributions(
                generation = generation,
                mid = current.user.mid,
                wbiImg = currentProfileWbiImg
            )
        }
    }

    private suspend fun fetchProfileContributionVideos(
        mid: Long,
        wbiImg: WbiImg?
    ): Pair<List<SpaceVideoItem>, Int>? {
        val imgUrl = wbiImg?.img_url.orEmpty()
        val subUrl = wbiImg?.sub_url.orEmpty()
        val imgKey = imgUrl.substringAfterLast("/").substringBefore(".")
        val subKey = subUrl.substringAfterLast("/").substringBefore(".")
        if (imgKey.isBlank() || subKey.isBlank()) return null
        val params = WbiUtils.sign(
            mapOf(
                "mid" to mid.toString(),
                "pn" to "1",
                "ps" to PROFILE_CONTRIBUTION_PAGE_SIZE.toString(),
                "order" to "pubdate"
            ),
            imgKey,
            subKey
        )
        val response = environment.spaceApi.getSpaceVideos(params)
        val data = response.data ?: return null
        if (response.code != 0) return null
        return data.list.vlist to data.page.count
    }

    private suspend fun loadProfileFavoriteFolders(generation: Long, mid: Long) {
        val folders = environment.favorite.getFavFolders(mid).getOrNull() ?: return
        var mergedFolders = emptyList<FavFolder>()
        updateProfileSuccess(generation, mid) { current ->
            val nextSpace = mergeProfileFavoriteFolderState(current.space, folders)
            mergedFolders = nextSpace.favoriteFolders
            current.copy(
                favoriteFolders = nextSpace.favoriteFolders,
                space = nextSpace
            )
        }
        loadProfileFavoritePreviewCovers(generation, mid, mergedFolders)
    }

    private suspend fun loadProfileFavoritePreviewCovers(
        generation: Long,
        mid: Long,
        folders: List<FavFolder>
    ) {
        val targets = resolveProfileFavoritePreviewCoverTargets(folders)
        if (targets.isEmpty()) return

        // 限制并发：收藏夹 resource/list 对突发并发较敏感，易触发 412/429
        val coverFetchSemaphore = Semaphore(2)
        val coversByMediaId = supervisorScope {
            targets.map { target ->
                async {
                    coverFetchSemaphore.withPermit {
                        val cover = environment.favorite.getFavoriteList(
                            mediaId = target.mediaId,
                            pn = 1,
                            ps = 1
                        ).getOrNull()
                            ?.medias
                            ?.firstOrNull { it.cover.isNotBlank() }
                            ?.cover
                            .orEmpty()
                        target.mediaId to cover
                    }
                }
            }.awaitAll()
        }.filter { (_, cover) -> cover.isNotBlank() }
            .toMap()
        if (coversByMediaId.isEmpty()) return

        updateProfileSuccess(generation, mid) { current ->
            val updatedFolders = mergeProfileFavoritePreviewCovers(
                folders = current.space.favoriteFolders,
                coversByMediaId = coversByMediaId
            )
            current.copy(
                favoriteFolders = updatedFolders,
                space = current.space.copy(favoriteFolders = updatedFolders)
            )
        }
    }

    private suspend fun loadProfileBangumi(generation: Long, mid: Long) {
        val items = environment.bangumi.getMyFollowBangumi(
            type = MY_FOLLOW_TYPE_BANGUMI,
            page = 1,
            pageSize = 12,
            vmid = mid
        ).getOrNull()?.list ?: return
        updateProfileSuccess(generation, mid) { current ->
            current.copy(space = mergeProfileBangumiState(current.space, items))
        }
    }

    private suspend fun loadProfileDynamics(generation: Long, mid: Long) {
        val items = profileRequestOrNull {
            environment.spaceApi.getSpaceDynamic(mid).data?.items
        } ?: return
        updateProfileSuccess(generation, mid) { current ->
            current.copy(space = mergeProfileDynamicState(current.space, items))
        }
    }

    private fun updateProfileSuccess(
        generation: Long,
        mid: Long,
        transform: (ProfileUiState.Success) -> ProfileUiState.Success
    ) {
        val current = _uiState.value as? ProfileUiState.Success ?: return
        if (!shouldApplyProfileLoadResult(generation, profileLoadGeneration, mid, current.user.mid)) {
            return
        }
        _uiState.value = transform(current)
    }

    private suspend fun clearInvalidProfileSession(generation: Long, customBgUri: String) {
        if (generation != profileLoadGeneration) return
        accountsPort.clearCurrentSession()
        accountsPort.clearActiveAccount()
        refreshSavedAccounts()
        _uiState.value = ProfileUiState.LoggedOut(topPhoto = resolveProfileTopPhoto(customBgUri, ""))
    }

    private fun handleProfileLoadFailure(
        generation: Long,
        requestedMid: Long?,
        customBgUri: String,
        error: Exception
    ) {
        if (generation != profileLoadGeneration) return
        val cached = (_uiState.value as? ProfileUiState.Success)
            ?.takeIf { it.user.mid == requestedMid }
        if (cached != null) {
            _uiState.value = cached.copy(
                space = cached.space.copy(
                    isLoading = false,
                    message = if (isNetworkError(error)) {
                        "网络不可用，请检查网络连接"
                    } else {
                        "刷新失败，请稍后重试"
                    }
                )
            )
            return
        }
        _uiState.value = if (!accountsPort.hasSession()) {
            ProfileUiState.LoggedOut(topPhoto = resolveProfileTopPhoto(customBgUri, ""))
        } else {
            ProfileUiState.Error(
                if (isNetworkError(error)) "网络不可用，请检查网络连接" else "加载失败，点击重试"
            )
        }
    }

    private suspend fun readProfileBackgroundUri(): String {
        return try {
            preferences.getProfileBgUri().first().orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ""
        }
    }

    private suspend fun <T> profileRequestOrNull(request: suspend () -> T): T? {
        return try {
            request()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveProfileTopPhoto(customBgUri: String, fallback: String): String {
        if (customBgUri.isBlank()) return fallback
        if (!customBgUri.startsWith("file://")) return customBgUri
        return runCatching {
            val file = File(java.net.URI.create(customBgUri).path.orEmpty())
            if (file.exists()) "$customBgUri?t=${file.lastModified()}" else customBgUri
        }.getOrDefault(customBgUri)
    }

    private suspend fun fetchProfileSpaceInfo(mid: Long, wbiImg: WbiImg?): SpaceUserInfo? {
        val imgUrl = wbiImg?.img_url.orEmpty()
        val subUrl = wbiImg?.sub_url.orEmpty()
        val imgKey = imgUrl.substringAfterLast("/").substringBefore(".")
        val subKey = subUrl.substringAfterLast("/").substringBefore(".")
        if (imgKey.isBlank() || subKey.isBlank()) return null
        val params = WbiUtils.sign(mapOf("mid" to mid.toString()), imgKey, subKey)
        val response = environment.spaceApi.getSpaceInfo(params)
        return if (response.code == 0) response.data else null
    }

    fun selectProfileSpaceTab(tab: ProfileSpaceMainTab) {
        val current = _uiState.value as? ProfileUiState.Success ?: return
        if (current.space.selectedTab == tab) return
        _uiState.value = current.copy(space = current.space.copy(selectedTab = tab))
    }

    fun clearProfileSpaceMessage() {
        val current = _uiState.value as? ProfileUiState.Success ?: return
        _uiState.value = current.copy(space = current.space.copy(signSaveMessage = null, message = null))
    }

    fun deleteProfileDynamic(action: DynamicDeleteAction, onResult: (Boolean, String) -> Unit) {
        environment.launchOwned {
            try {
                if (action.dynamicId.isBlank()) {
                    environment.publishCallback { onResult(false, "无法删除该动态") }
                    return@launchOwned
                }
                val csrf = environment.csrf()
                if (csrf.isNullOrBlank()) {
                    environment.publishCallback { onResult(false, "请先登录") }
                    return@launchOwned
                }

                val response = environment.dynamicApi.deleteDynamic(
                    csrf = csrf,
                    body = DynamicDeleteRequest(
                        dyn_id_str = action.dynamicId,
                        dyn_type = action.dynType,
                        rid_str = action.rid
                    )
                )
                if (response.code == 0) {
                    removeProfileDynamic(action.dynamicId)
                    environment.publishCallback { onResult(true, "已删除动态") }
                } else {
                    environment.publishCallback { onResult(false, response.message.ifBlank { "删除失败" }) }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (e: Exception) {
                environment.publishCallback { onResult(false, e.message ?: "网络错误") }
            }
        }
    }

    private fun removeProfileDynamic(dynamicId: String) {
        val current = _uiState.value as? ProfileUiState.Success ?: return
        val updatedItems = current.space.dynamicItems.filterNot { item ->
            item.id_str == dynamicId ||
                item.modules.module_more?.three_point_items.orEmpty().any { menu ->
                    menu.params?.dyn_id_str == dynamicId
                }
        }
        _uiState.value = current.copy(
            space = current.space.copy(dynamicItems = updatedItems)
        )
    }

    fun updateProfileSign(sign: String) {
        val validationError = validateProfileSign(sign)
        val current = _uiState.value as? ProfileUiState.Success ?: return
        if (validationError != null) {
            _uiState.value = current.copy(
                space = current.space.copy(signSaveMessage = validationError)
            )
            return
        }

        val csrf = environment.csrf().orEmpty()
        if (csrf.isBlank()) {
            _uiState.value = current.copy(
                space = current.space.copy(signSaveMessage = "请先登录后再修改签名")
            )
            return
        }

        environment.launchOwned {
            val beforeSave = _uiState.value as? ProfileUiState.Success ?: return@launchOwned
            _uiState.value = beforeSave.copy(
                space = beforeSave.space.copy(isSavingSign = true, signSaveMessage = null)
            )
            val result = runCatching {
                environment.api.updateMemberSign(userSign = sign.trim(), csrf = csrf)
            }
            (result.exceptionOrNull() as? CancellationException)?.let { throw it }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val latest = _uiState.value as? ProfileUiState.Success ?: return@launchOwned
            val response = result.getOrNull()
            if (response?.code == 0) {
                _uiState.value = latest.copy(
                    editableAccount = latest.editableAccount.copy(sign = sign.trim()),
                    space = latest.space.copy(
                        isSavingSign = false,
                        signSaveMessage = "签名已提交，等待审核后生效"
                    )
                )
            } else {
                _uiState.value = latest.copy(
                    space = latest.space.copy(
                        isSavingSign = false,
                        signSaveMessage = response?.message?.ifBlank { null } ?: "签名保存失败"
                    )
                )
            }
        }
    }
    
    /**
     * 更新自定义背景图
     * 将选中的图片复制到应用私有目录，并更新设置
     */
    fun updateCustomBackground(
        uri: String, 
        mobileTransform: ProfileWallpaperTransform = ProfileWallpaperTransform(),
        tabletTransform: ProfileWallpaperTransform = ProfileWallpaperTransform()
    ) {
        environment.launchOwned(Dispatchers.IO) {
            try {
                        val wallpaper = platform.importWallpaperMedia(uri, File(platform.stateDirectory.toFile(), "profile_wallpaper"))
                preferences.setProfileBgTransform( false, mobileTransform)
                preferences.setProfileBgTransform( true, tabletTransform)
                preferences.setProfileBgUri( wallpaper.toURI().toString())
                loadProfile(force = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                withContext(Dispatchers.Main.immediate) {
                    platform.feedback(error.message ?: "壁纸导入失败")
                }
            }
        }
    }
    
    /**
     * 判断是否为网络相关错误
     */
    private fun isNetworkError(e: Exception): Boolean {
        return e is UnknownHostException ||
               e is SocketTimeoutException ||
               e is java.net.ConnectException ||
               e.cause is UnknownHostException ||
               e.cause is SocketTimeoutException
    }

    fun logout() {
        environment.launchOwned {
            profileLoadGeneration += 1L
            isProfileLoadInFlight = false
            // retain background
            val customBgUri = preferences.getProfileBgUri().first() ?: ""
            accountsPort.upsertCurrentAccount(null)
            accountsPort.clearCurrentSession()
            accountsPort.clearActiveAccount()
            refreshSavedAccounts()
            _uiState.value = ProfileUiState.LoggedOut(topPhoto = customBgUri)
            environment.analytics.syncUserContext(
                mid = null,
                isVip = false,
                privacyModeEnabled = preferences.isPrivacyModeEnabledSync()
            )
            //  记录登出事件
            environment.analytics.logLogout()
        }
    }

    fun switchAccount(
        mid: Long,
        onSuccess: () -> Unit = {},
        onFailure: (String) -> Unit = {}
    ) {
        environment.launchOwned {
            if (mid == accountsPort.currentMid()) {
                environment.publishCallback { onSuccess() }
                return@launchOwned
            }

            val switched = accountsPort.activateAccount(mid)
            if (!switched) {
                environment.publishCallback { onFailure("切换账号失败") }
                return@launchOwned
            }

            refreshSavedAccounts()
            loadProfile(force = true)
            environment.publishCallback { onSuccess() }
        }
    }

    fun removeStoredAccount(
        mid: Long,
        onSuccess: () -> Unit = {},
        onFailure: (String) -> Unit = {}
    ) {
        if (accountsPort.currentMid() == mid) {
            environment.publishCallback { onFailure("请先切换到其他账号后再移除当前账号") }
            return
        }

        val removed = accountsPort.removeAccount(mid)
        if (removed) {
            refreshSavedAccounts()
            environment.publishCallback { onSuccess() }
        } else {
            environment.publishCallback { onFailure("移除账号失败") }
        }
    }
    
    // [新增] 官方壁纸列表
    private val _officialWallpapers = ownedFlow<List<com.android.purebilibili.data.model.response.SplashItem>>(emptyList())
    val officialWallpapers = _officialWallpapers.asStateFlow()
    private val _officialWallpapersLoading = ownedFlow(false)
    val officialWallpapersLoading = _officialWallpapersLoading.asStateFlow()
    private val _officialWallpapersError = ownedFlow<String?>(null)
    val officialWallpapersError = _officialWallpapersError.asStateFlow()

    fun loadOfficialWallpapers() {
        environment.launchOwned {
            _officialWallpapersLoading.value = true
            _officialWallpapersError.value = null
            val result = environment.splash.getOfficialWallpapers()
            if (result.isSuccess) {
                _officialWallpapers.value = result.getOrNull() ?: emptyList()
            } else {
                _officialWallpapersError.value = result.exceptionOrNull()?.message ?: "加载失败，点击重试"
            }
            _officialWallpapersLoading.value = false
        }
    }

    // [新增] 搜索壁纸
    private val _searchWallpapers = ownedFlow<List<com.android.purebilibili.data.model.response.SplashItem>>(emptyList())
    val searchWallpapers = _searchWallpapers.asStateFlow()
    private val _searchLoading = ownedFlow(false)
    val searchLoading = _searchLoading.asStateFlow()

    fun searchWallpapers(query: String) {
        environment.launchOwned {
            if (query.isBlank()) return@launchOwned
            _searchLoading.value = true
            try {
                // 使用通用搜索接口搜索 "query + 壁纸"
                val searchApi = environment.searchApi
                // 这里调用 searchAll 或 searchType 接口，假设 searchAll 可用
                // 注意：B站搜索 API 比较复杂，这里简化处理，假设搜索 "壁纸" 相关内容
                // 实际可能需要解析 SearchResponse 并转换为 SplashItem
                
                // 构造搜索参数
                val params = mutableMapOf<String, String>()
                params["keyword"] = "$query 壁纸"
                
                // 模拟：由于没有直接的 searchWallpaper API，我们这里临时复用 searchAll
                // 真实场景下需解析 SearchResponse 中的 result.video 或 result.article
                // 为了演示，这里先留空或模拟一些数据，或者如果 SearchApi 返回结构匹配的话
                
                // [暂缓] 实际搜索逻辑需要详细解析 SearchResponse。
                // 鉴于 SearchResponse 结构较复杂，我们先模拟一个空列表或 TODO
                // 等待 SearchResponse 结构完全确认。
                
                // 既然用户想要 "搜索B站开屏壁纸"，通常这些资源不在标准搜索里直接以图片形式提供。
                // 我们可以搜 "垂直" 视频的封面? 
                // 让我们尝试搜 "draw" 栏目?
                
                // 简易方案：调用 searchAll，取 result.result 里的数据（需适配）
                // 暂时: 仅作为 UI 展示，不做真实网络请求以免崩溃，或者请求后打 Log
                
                // 真实实现：
                 val result = searchApi.searchAll(params)
                 // TODO: Parse result to SplashItem list
                 // _searchWallpapers.value = parsedList
                 
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (e: Exception) {
                // Root diagnostic actor controls raw exception disclosure.
            } finally {
                _searchLoading.value = false
            }
        }
    }

    // [New] 壁纸保存状态
    private val _wallpaperSaveState = ownedFlow<WallpaperSaveState>(WallpaperSaveState.Idle)
    val wallpaperSaveState = _wallpaperSaveState.asStateFlow()

    /**
     * 保存壁纸 (下载并设置为背景)
     */
    // [New] Alignment State
    fun getProfileBgAlignment(isTablet: Boolean) = preferences.getProfileBgAlignment( isTablet)
    fun getProfileBgTransform(isTablet: Boolean) = preferences.getProfileBgTransform( isTablet)
    fun getProfileBgUri() = preferences.getProfileBgUri()

    fun clearCustomBackground() {
        environment.launchOwned {
                preferences.setProfileBgUri( null)
            preferences.resetProfileBgTransform()
            runCatching {
                platform.deleteOwnedFile(File(platform.stateDirectory.toFile(), "images/profile_bg.jpg"))
            }.onFailure { if (it is CancellationException) throw it }
            loadProfile(force = true)
        }
    }

    /**
     * 保存壁纸 (下载并设置为背景)
     * 支持传入对齐参数
     */
    fun saveWallpaper(
        url: String, 
        mobileTransform: ProfileWallpaperTransform = ProfileWallpaperTransform(),
        tabletTransform: ProfileWallpaperTransform = ProfileWallpaperTransform(),
        onComplete: () -> Unit = {}
    ) {
        environment.launchOwned(Dispatchers.IO) {
            _wallpaperSaveState.value = WallpaperSaveState.Loading
            try {
                // 保存对齐设置
                        preferences.setProfileBgTransform( false, mobileTransform)
                preferences.setProfileBgTransform( true, tabletTransform)
                
                // 修复 URL 协议 (强制 HTTPS)
                var finalUrl = url
                if (finalUrl.startsWith("//")) {
                    finalUrl = "https:$finalUrl"
                } else if (finalUrl.startsWith("http://")) {
                    finalUrl = finalUrl.replace("http://", "https://")
                }
                
                val request = okhttp3.Request.Builder().url(finalUrl).build()
                platform.openOwnedDownload(request).use { response ->
                
                if (response.isSuccessful) {
                    val imagesDir = File(platform.stateDirectory.toFile(), "images")
                    if (!imagesDir.exists()) imagesDir.mkdirs()
                    val destFile = File(imagesDir, "profile_bg.jpg")
                    
                    platform.writeOwnedFile(destFile, response.body)
                    
                    val savedUri = destFile.toURI().toString()
                    preferences.setProfileBgUri( savedUri)
                    
                    loadProfile(force = true) // 刷新
                    
                    withContext(Dispatchers.Main.immediate) {
                        _wallpaperSaveState.value = WallpaperSaveState.Success
                        environment.publishCallback { onComplete() }
                    }
                } else {
                    _wallpaperSaveState.value = WallpaperSaveState.Error("下载失败: ${response.code}")
                }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (e: Exception) {
                // Root diagnostic actor controls raw exception disclosure.
                _wallpaperSaveState.value = WallpaperSaveState.Error(e.message ?: "保存出错")
            } finally {
                // Delay reset to allow UI to show success checkmark if needed, but for now we rely on onDismiss
                if (_wallpaperSaveState.value is WallpaperSaveState.Success) {
                     _wallpaperSaveState.value = WallpaperSaveState.Idle
                }
            }
        }
    }

    fun selectOfficialWallpaper(url: String) {
        saveWallpaper(url)
    }
    
    // [New] Splash Wallpaper Logic
    private val _splashSaveState = ownedFlow<WallpaperSaveState>(WallpaperSaveState.Idle)
    val splashSaveState = _splashSaveState.asStateFlow()

    fun getSplashAlignment(isTablet: Boolean) = preferences.getSplashAlignment( isTablet)

    fun setAsSplashWallpaper(
        url: String,
        saveToGallery: Boolean = false,
        mobileBias: Float? = null,
        tabletBias: Float? = null,
        onComplete: () -> Unit = {}
    ) {
        environment.launchOwned(Dispatchers.IO) {
            _splashSaveState.value = WallpaperSaveState.Loading
            try {
                        var finalUrl = url
                if (finalUrl.startsWith("//")) {
                    finalUrl = "https:$finalUrl"
                } else if (finalUrl.startsWith("http://")) {
                    finalUrl = finalUrl.replace("http://", "https://")
                }

                val request = okhttp3.Request.Builder().url(finalUrl).build()
                platform.openOwnedDownload(request).use { response ->

                if (response.isSuccessful) {
                    // Read bytes once
                    val bytes = platform.readOwnedBytes(response.body)
                    
                    // 1. Save to internal splash directory
                    val splashDir = File(platform.stateDirectory.toFile(), "splash")
                    if (!splashDir.exists()) splashDir.mkdirs()
                    val destFile = File(splashDir, "splash_bg_${System.currentTimeMillis()}.jpg")

                    platform.writeOwnedFile(destFile, bytes)

                    // 2. Update Settings
                    val savedUri = destFile.toURI().toString()
                    preferences.setSplashWallpaperUri( savedUri)
                    preferences.setSplashEnabled( true)
                    mobileBias?.let { preferences.setSplashAlignment( isTablet = false, bias = it) }
                    tabletBias?.let { preferences.setSplashAlignment( isTablet = true, bias = it) }

                    // 3. Save to Gallery if requested
                    if (saveToGallery) {
                         saveImageToGallery( bytes, "bili_splash_${System.currentTimeMillis()}.jpg")
                    }

                    withContext(Dispatchers.Main.immediate) {
                        _splashSaveState.value = WallpaperSaveState.Success
                        environment.publishCallback { onComplete() }
                    }
                } else {
                    _splashSaveState.value = WallpaperSaveState.Error("下载失败: ${response.code}")
                }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (e: Exception) {
                // Root diagnostic actor controls raw exception disclosure.
                _splashSaveState.value = WallpaperSaveState.Error(e.message ?: "保存出错")
            } finally {
                // Delay reset slightly to let UI react if needed, or just reset logic
                 if (_splashSaveState.value is WallpaperSaveState.Success) {
                     _splashSaveState.value = WallpaperSaveState.Idle
                }
            }
        }
    }

    fun setCustomSplashWallpaper(
        uri: String,
        mobileBias: Float? = null,
        tabletBias: Float? = null,
        onComplete: () -> Unit = {}
    ) {
        environment.launchOwned(Dispatchers.IO) {
            _splashSaveState.value = WallpaperSaveState.Loading
            try {
                        val wallpaper = platform.importWallpaperImage(uri, File(platform.stateDirectory.toFile(), "splash"))
                preferences.setSplashWallpaperUri( wallpaper.toURI().toString())
                preferences.setSplashEnabled( true)
                preferences.setSplashRandomEnabled( false)
                mobileBias?.let { preferences.setSplashAlignment( isTablet = false, bias = it) }
                tabletBias?.let { preferences.setSplashAlignment( isTablet = true, bias = it) }

                withContext(Dispatchers.Main.immediate) {
                    _splashSaveState.value = WallpaperSaveState.Success
                    environment.publishCallback { onComplete() }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (e: Exception) {
                // Root diagnostic actor controls raw exception disclosure.
                _splashSaveState.value = WallpaperSaveState.Error(e.message ?: "保存出错")
            } finally {
                if (_splashSaveState.value is WallpaperSaveState.Success) {
                    _splashSaveState.value = WallpaperSaveState.Idle
                }
            }
        }
    }

    fun setAsHomeWallpaper(
        url: String,
        saveToGallery: Boolean = false,
        onComplete: () -> Unit = {}
    ) {
        environment.launchOwned(Dispatchers.IO) {
            _splashSaveState.value = WallpaperSaveState.Loading
            try {
                        var finalUrl = url
                if (finalUrl.startsWith("//")) {
                    finalUrl = "https:$finalUrl"
                } else if (finalUrl.startsWith("http://")) {
                    finalUrl = finalUrl.replace("http://", "https://")
                }

                val request = okhttp3.Request.Builder().url(finalUrl).build()
                platform.openOwnedDownload(request).use { response ->

                if (response.isSuccessful) {
                    val bytes = platform.readOwnedBytes(response.body)
                    val homeWallpaperDir = File(platform.stateDirectory.toFile(), "home_wallpaper")
                    if (!homeWallpaperDir.exists()) homeWallpaperDir.mkdirs()
                    val destFile = File(homeWallpaperDir, "home_bg_${System.currentTimeMillis()}.jpg")

                    platform.writeOwnedFile(destFile, bytes)

                    preferences.setHomeWallpaperUri( destFile.toURI().toString())

                    if (saveToGallery) {
                        saveImageToGallery( bytes, "bili_home_${System.currentTimeMillis()}.jpg")
                    }

                    withContext(Dispatchers.Main.immediate) {
                        _splashSaveState.value = WallpaperSaveState.Success
                        environment.publishCallback { onComplete() }
                    }
                } else {
                    _splashSaveState.value = WallpaperSaveState.Error("下载失败: ${response.code}")
                }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (e: Exception) {
                // Root diagnostic actor controls raw exception disclosure.
                _splashSaveState.value = WallpaperSaveState.Error(e.message ?: "保存出错")
            } finally {
                if (_splashSaveState.value is WallpaperSaveState.Success) {
                    _splashSaveState.value = WallpaperSaveState.Idle
                }
            }
        }
    }

    fun setCustomHomeWallpaper(
        uri: String,
        onComplete: () -> Unit = {}
    ) {
        environment.launchOwned(Dispatchers.IO) {
            _splashSaveState.value = WallpaperSaveState.Loading
            try {
                        val wallpaper = platform.importWallpaperMedia(uri, File(platform.stateDirectory.toFile(), "home_wallpaper"))
                preferences.setHomeWallpaperUri( wallpaper.toURI().toString())

                withContext(Dispatchers.Main.immediate) {
                    _splashSaveState.value = WallpaperSaveState.Success
                    environment.publishCallback { onComplete() }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (e: Exception) {
                // Root diagnostic actor controls raw exception disclosure.
                _splashSaveState.value = WallpaperSaveState.Error(e.message ?: "保存出错")
            } finally {
                if (_splashSaveState.value is WallpaperSaveState.Success) {
                    _splashSaveState.value = WallpaperSaveState.Idle
                }
            }
        }
    }

private suspend fun saveImageToGallery(bytes: ByteArray, fileName: String) {
        platform.saveImageToGallery(bytes, fileName)
    }

    // [New] Easter Egg: Triple Jump Setting
    val tripleJumpEnabled = preferences.getTripleJumpEnabled().stateIn(
        scope = viewModelScope,
        started = kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000),
        initialValue = false
    )

    fun setTripleJumpEnabled(enabled: Boolean) {
        environment.launchOwned {
            preferences.setTripleJumpEnabled( enabled)
        }
    }
}

sealed class WallpaperSaveState {
    object Idle : WallpaperSaveState()
    object Loading : WallpaperSaveState()
    object Success : WallpaperSaveState()
    data class Error(val message: String) : WallpaperSaveState()
}
