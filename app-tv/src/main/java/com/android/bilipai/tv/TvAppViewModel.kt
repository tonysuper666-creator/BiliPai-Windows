package com.android.bilipai.tv

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.android.purebilibili.core.store.TokenManager
import com.android.purebilibili.core.player.SharedPlaybackState
import com.android.purebilibili.data.model.response.FavFolder
import com.android.purebilibili.data.model.response.HistoryCursor
import com.android.purebilibili.data.model.response.NavData
import com.android.purebilibili.data.model.response.VideoItem
import com.android.purebilibili.data.model.response.ViewInfo
import com.android.purebilibili.data.repository.FavoriteRepository
import com.android.purebilibili.data.repository.HistoryRepository
import com.android.purebilibili.data.repository.QrLoginRepository
import com.android.purebilibili.data.repository.SearchRepository
import com.android.purebilibili.data.repository.SessionRepository
import com.android.purebilibili.data.repository.SharedContentRepository
import com.android.purebilibili.data.repository.WatchLaterRepository
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class TvScreen { Home, Search, History, Folders, Favorites, WatchLater, Settings, Login, Detail, Player }

@Serializable
data class TvRoute(
    val screen: TvScreen = TvScreen.Home, val bvid: String = "", val aid: Long = 0,
    val cid: Long = 0, val folderId: Long = 0, val label: String = "",
) {
    val key: String get() = "$screen:$bvid:$aid:$cid:$folderId"
}

data class TvCatalogState(
    val items: List<VideoItem> = emptyList(), val folders: List<FavFolder> = emptyList(),
    val loading: Boolean = false, val error: String? = null, val page: Int = 0, val hasMore: Boolean = true,
    val focusedId: String? = null, val focusedIndex: Int = 0, val resetVersion: Long = 0, val firstVisibleIndex: Int = 0, val firstVisibleOffset: Int = 0,
    val historyCursor: HistoryCursor? = null,
)

enum class QrPhase { Loading, Waiting, Scanned, Expired, Success, Failed }
data class TvQrState(val phase: QrPhase = QrPhase.Loading, val bitmap: Bitmap? = null, val error: String? = null)

data class TvUiState(
    val route: TvRoute = TvRoute(), val rootScreen: TvScreen = TvScreen.Home, val catalog: TvCatalogState = TvCatalogState(),
    val detail: ViewInfo? = null, val detailLoading: Boolean = false, val detailError: String? = null,
    val account: NavData? = null, val accountError: String? = null, val qr: TvQrState = TvQrState(),
    val query: String = "", val searchHistory: List<String> = emptyList(), val trending: List<String> = emptyList(),
    val quality: Int = 64, val autoContinue: Boolean = false, val privacyMode: Boolean = false,
    val danmakuEnabled: Boolean = true,
    val notice: String? = null,
)

fun VideoItem.tvId(): String = bvid.takeIf { it.isNotBlank() } ?: "aid:${aid.takeIf { it > 0 } ?: id}"

class TvAppViewModel(application: Application, private val savedState: SavedStateHandle) : AndroidViewModel(application) {
    private val preferences = TvPreferences(application)
    private val stack = runCatching {
        Json.decodeFromString<List<TvRoute>>(savedState.get<String>("routes") ?: "[]")
    }.getOrDefault(emptyList()).ifEmpty { listOf(TvRoute()) }.toMutableList()
    private val catalogs = mutableMapOf<String, TvCatalogState>()
    private val details = mutableMapOf<String, ViewInfo>()
    private val mutableState = MutableStateFlow(TvUiState(route = stack.last(), rootScreen = stack.first().screen,
        query = savedState["query"] ?: "", searchHistory = preferences.searchHistory,
        quality = preferences.quality, autoContinue = preferences.autoContinue, privacyMode = preferences.privacyMode,
        danmakuEnabled = preferences.danmakuEnabled))
    val state = mutableState.asStateFlow()
    private var contentJob: Job? = null
    private var qrJob: Job? = null
    private var accountJob: Job? = null
    private var accountRevision = 0L
    private var started = false
    private var revision = 0L
    // 自动分页失败后阻断继续自动请求，避免滚动位置未变时形成重试风暴；刷新/重试后解除
    private var autoLoadBlocked = false

    fun start() {
        if (started) return
        started = true
        viewModelScope.launch {
            withContext(Dispatchers.IO) { TokenManager.awaitRestore() }
            refreshAccount()
            loadRoute()
        }
    }

    fun navigate(route: TvRoute, root: Boolean = false) {
        if (route == mutableState.value.route) return
        contentJob?.cancel(); qrJob?.cancel(); revision++
        catalogs[mutableState.value.route.key] = mutableState.value.catalog.copy(loading = false)
        if (root) { stack.clear(); stack.add(route) } else stack.add(route)
        savedState["routes"] = Json.encodeToString(stack.toList())
        mutableState.update { it.copy(route = route, rootScreen = stack.first().screen, catalog = catalogs[route.key] ?: TvCatalogState(),
            detail = details[route.key], detailLoading = false, detailError = null, notice = null) }
        loadRoute()
    }

    /** Root sections return to recommendations; only Home lets the system leave. */
    fun back(): Boolean {
        if (stack.size <= 1) {
            if (stack.last().screen == TvScreen.Home) return false
            navigate(TvRoute(), root = true)
            return true
        }
        contentJob?.cancel(); qrJob?.cancel(); revision++
        catalogs[mutableState.value.route.key] = mutableState.value.catalog.copy(loading = false)
        stack.removeAt(stack.lastIndex)
        val route = stack.last()
        savedState["routes"] = Json.encodeToString(stack.toList())
        mutableState.update { it.copy(route = route, rootScreen = stack.first().screen, catalog = catalogs[route.key] ?: TvCatalogState(),
            detail = details[route.key], detailLoading = false, detailError = null, notice = null) }
        loadRoute()
        return true
    }

    fun open(video: VideoItem) = navigate(TvRoute(TvScreen.Detail, video.bvid,
        video.aid.takeIf { it > 0 } ?: video.id, video.cid))
    /** banner 主操作：推荐流带 cid 时直达播放器，否则走详情兜底。 */
    fun playItem(video: VideoItem) {
        val cid = video.cid.takeIf { it > 0 } ?: return open(video)
        navigate(TvRoute(TvScreen.Player, video.bvid,
            video.aid.takeIf { it > 0 } ?: video.id, cid, label = video.title))
    }
    fun play(cid: Long) {
        val info = mutableState.value.detail ?: return
        navigate(TvRoute(TvScreen.Player, info.bvid, info.aid, cid, label = info.title))
    }

    fun checkpointPlayback(snapshot: SharedPlaybackState) {
        val info = snapshot.info ?: return
        preferences.rememberPart(info.bvid, info.aid, info.cid)
        details.entries.forEach { entry ->
            if (entry.value.bvid == info.bvid) entry.setValue(entry.value.copy(cid = info.cid))
        }
        // Updating cached cards preserves the originating history page and its focus anchor.
        catalogs.entries.forEach { entry ->
            if (entry.key.startsWith("History:")) entry.setValue(entry.value.copy(items = entry.value.items.map { item ->
                if (item.bvid == info.bvid) item.copy(cid = info.cid, progress = (snapshot.positionMs / 1000).toInt()) else item
            }))
        }
        viewModelScope.launch {
            if (!TokenManager.sessDataCache.isNullOrBlank()) HistoryRepository.reportPlayback(
                info.bvid, info.cid, snapshot.positionMs / 1000, snapshot.realPlayedMs / 1000,
                snapshot.startTsSec, info.aid)
        }
    }

    fun finishPlayback(snapshot: SharedPlaybackState) { checkpointPlayback(snapshot); back() }

    fun focusItem(id: String, routeKey: String = mutableState.value.route.key) = mutableState.update {
        if (it.route.key != routeKey) return@update it
        val index = if (id.startsWith("folder:")) it.catalog.folders.indexOfFirst { folder -> "folder:${folder.id}" == id }
            else it.catalog.items.indexOfFirst { item -> item.tvId() == id }
        it.copy(catalog = it.catalog.copy(focusedId = id, focusedIndex = index.coerceAtLeast(0)))
    }
    fun saveScroll(index: Int, offset: Int, routeKey: String = mutableState.value.route.key) = mutableState.update {
        if (it.route.key != routeKey) return@update it
        it.copy(catalog = it.catalog.copy(firstVisibleIndex = index, firstVisibleOffset = offset))
    }

    private fun loadRoute() {
        when (mutableState.value.route.screen) {
            TvScreen.Detail -> loadDetail()
            TvScreen.Login -> {
                if (TokenManager.sessDataCache.isNullOrBlank()) refreshQr()
                else {
                    contentJob = viewModelScope.launch {
                        refreshAccount().join()
                        if (mutableState.value.route.screen == TvScreen.Login && mutableState.value.account == null) refreshQr()
                    }
                }
            }
            TvScreen.Settings, TvScreen.Player -> Unit
            TvScreen.Search -> {
                loadTrending()
                if (mutableState.value.query.isNotBlank() && mutableState.value.catalog.page == 0) loadCatalog()
            }
            else -> if (mutableState.value.catalog.page == 0) loadCatalog()
        }
    }

    fun refresh() {
        if (mutableState.value.route.screen == TvScreen.Detail) loadDetail(force = true)
        else loadCatalog(reset = true)
    }

    fun search(query: String) {
        val clean = query.trim()
        if (clean.isBlank()) return
        preferences.recordSearch(clean); savedState["query"] = clean
        mutableState.update { it.copy(query = clean, searchHistory = preferences.searchHistory) }
        loadCatalog(reset = true)
    }

    fun loadMore() {
        if (autoLoadBlocked) return
        val catalog = mutableState.value.catalog
        if (!catalog.loading && catalog.hasMore) loadCatalog(reset = false)
    }

    private fun loadCatalog(reset: Boolean = false) {
        val route = mutableState.value.route
        if (route.screen !in setOf(TvScreen.Home, TvScreen.Search, TvScreen.History, TvScreen.Folders, TvScreen.Favorites, TvScreen.WatchLater)) return
        if (route.screen in setOf(TvScreen.History, TvScreen.Folders, TvScreen.Favorites, TvScreen.WatchLater)
            && TokenManager.sessDataCache.isNullOrBlank()) {
            mutableState.update { it.copy(catalog = it.catalog.copy(error = "请先扫码登录", loading = false, hasMore = false)) }
            return
        }
        contentJob?.cancel()
        val ticket = ++revision
        val previous = mutableState.value.catalog
        val page = if (reset) 1 else previous.page + 1
        val query = mutableState.value.query
        if (reset) autoLoadBlocked = false
        mutableState.update { it.copy(catalog = it.catalog.copy(loading = true, error = null)) }
        contentJob = viewModelScope.launch {
            try {
                var hasMore = true
                var cursor = if (reset) null else previous.historyCursor
                var folders = emptyList<FavFolder>()
                val items = when (route.screen) {
                    TvScreen.Home -> SharedContentRepository.recommendations(page).getOrThrow()
                    TvScreen.Search -> SearchRepository.search(query, page = page).getOrThrow().let {
                        hasMore = it.second.hasMore; it.first
                    }
                    TvScreen.History -> HistoryRepository.getHistoryList(max = cursor?.max ?: 0,
                        viewAt = cursor?.view_at ?: 0, business = cursor?.business, type = "archive").getOrThrow().let {
                        cursor = it.cursor; hasMore = it.list.isNotEmpty() && (reset || cursor != previous.historyCursor)
                        it.list.filter { item -> item.history?.business == "archive" }.map { item -> item.toVideoItem() }
                    }
                    TvScreen.Folders -> {
                        val account = SessionRepository.account().getOrThrow() ?: error("登录已失效，请重新扫码登录")
                        folders = FavoriteRepository.getFavFolders(account.mid).getOrThrow(); hasMore = false; emptyList()
                    }
                    TvScreen.Favorites -> FavoriteRepository.getFavoriteList(mediaId = route.folderId, pn = page).getOrThrow().let {
                        hasMore = it.has_more; it.medias.orEmpty().filter { item -> item.type == 2 }.map { item -> item.toVideoItem() }
                    }
                    TvScreen.WatchLater -> WatchLaterRepository.getPage(page, viewed = 0, keyword = "", ascending = false).getOrThrow().let {
                        hasMore = it.hasMore; it.items
                    }
                    else -> emptyList()
                }
                if (ticket != revision) return@launch
                val validItems = items.filter { it.bvid.isNotBlank() || it.aid > 0 }
                mutableState.update { current -> current.copy(catalog = current.catalog.copy(
                    items = ((if (reset) emptyList() else previous.items) + validItems).distinctBy { it.tvId() },
                    folders = folders, loading = false, error = null, page = page,
                    hasMore = hasMore && (items.isNotEmpty() || folders.isNotEmpty()), historyCursor = cursor,
                    focusedId = if (reset) null else current.catalog.focusedId,
                    focusedIndex = if (reset) 0 else current.catalog.focusedIndex,
                    resetVersion = current.catalog.resetVersion + if (reset) 1 else 0,
                    firstVisibleIndex = if (reset) 0 else current.catalog.firstVisibleIndex,
                    firstVisibleOffset = if (reset) 0 else current.catalog.firstVisibleOffset,
                )) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (ticket == revision) {
                    if (!reset) autoLoadBlocked = true
                    mutableState.update { it.copy(catalog = it.catalog.copy(loading = false, error = error.message ?: "加载失败")) }
                }
            }
        }
    }

    private fun loadDetail(force: Boolean = false) {
        val route = mutableState.value.route
        if (!force && details[route.key] != null) return
        contentJob?.cancel()
        val ticket = ++revision
        mutableState.update { it.copy(detailLoading = true, detailError = null) }
        contentJob = viewModelScope.launch {
            val requestedCid = preferences.lastPlayedCid(route.bvid, route.aid).takeIf { it > 0 } ?: route.cid
            val result = SharedContentRepository.detail(route.bvid, route.aid, requestedCid)
            if (ticket != revision) return@launch
            result.fold(onSuccess = { info ->
                details[route.key] = info
                mutableState.update { it.copy(detail = info, detailLoading = false) }
            }, onFailure = { error -> mutableState.update { it.copy(detailLoading = false, detailError = error.message ?: "详情加载失败") } })
        }
    }

    private fun loadTrending() = viewModelScope.launch {
        val result = SearchRepository.getTrendingKeywords(12)
        result.getOrNull()?.let { bundle -> mutableState.update { it.copy(trending = bundle.allItems.map { item -> item.keyword }) } }
    }

    fun refreshAccount(): Job {
        accountJob?.cancel()
        val ticket = ++accountRevision
        return viewModelScope.launch {
            withContext(Dispatchers.IO) { TokenManager.awaitRestore() }
            val result = SessionRepository.account()
            if (ticket != accountRevision) return@launch
            result.fold(onSuccess = { account ->
                mutableState.update { it.copy(account = account, accountError = null) }
            }, onFailure = { error -> mutableState.update { it.copy(accountError = error.message) } })
        }.also { accountJob = it }
    }

    fun refreshQr() {
        qrJob?.cancel()
        mutableState.update { it.copy(qr = TvQrState()) }
        qrJob = viewModelScope.launch {
            try {
                val response = withContext(Dispatchers.IO) { QrLoginRepository.generate() }
                check(response.code == 0) { response.message.ifBlank { "二维码申请失败" } }
                val data = response.data ?: error("二维码数据为空")
                val code = data.authCode?.takeIf { it.isNotBlank() } ?: error("二维码凭据为空")
                val bitmap = withContext(Dispatchers.Default) {
                    val matrix = QRCodeWriter().encode(data.url ?: error("二维码地址为空"), BarcodeFormat.QR_CODE, 360, 360)
                    val pixels = IntArray(360 * 360) { index -> if (matrix[index % 360, index / 360]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
                    Bitmap.createBitmap(pixels, 360, 360, Bitmap.Config.ARGB_8888)
                }
                mutableState.update { it.copy(qr = TvQrState(QrPhase.Waiting, bitmap)) }
                val expiresAt = android.os.SystemClock.elapsedRealtime() + 180_000L
                while (android.os.SystemClock.elapsedRealtime() < expiresAt) {
                    delay(2_000)
                    val poll = withContext(Dispatchers.IO) { QrLoginRepository.poll(code) }
                    when (poll.code) {
                        0 -> {
                            SessionRepository.completeQrLogin(getApplication(), poll)
                            mutableState.update { it.copy(qr = TvQrState(QrPhase.Success)) }
                            catalogs.clear(); refreshAccount(); return@launch
                        }
                        86039 -> Unit
                        86090 -> mutableState.update { it.copy(qr = it.qr.copy(phase = QrPhase.Scanned)) }
                        86038 -> break
                        else -> error(poll.message.ifBlank { "扫码登录失败（${poll.code}）" })
                    }
                }
                mutableState.update { it.copy(qr = TvQrState(QrPhase.Expired)) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) { mutableState.update { it.copy(qr = TvQrState(QrPhase.Failed, error = error.message ?: "登录失败")) } }
        }
    }

    fun signOut() = viewModelScope.launch {
        accountJob?.cancel(); accountRevision++
        qrJob?.cancel()
        SessionRepository.signOut(getApplication()); catalogs.clear()
        mutableState.update { it.copy(account = null, accountError = null, qr = TvQrState()) }
        navigate(TvRoute(), root = true)
        if (mutableState.value.route.screen == TvScreen.Home) loadCatalog(reset = true)
    }

    fun addWatchLater() = viewModelScope.launch {
        val info = mutableState.value.detail ?: return@launch
        try {
            val response = com.android.purebilibili.core.network.NetworkModule.api.addToWatchLater(info.aid, TokenManager.csrfCache ?: error("请先扫码登录"))
            check(response.code == 0) { response.message }
            catalogs.entries.removeAll { it.key.startsWith("WatchLater:") }
            mutableState.update { it.copy(notice = "已加入稍后再看") }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) { mutableState.update { it.copy(notice = error.message ?: "操作失败") } }
    }

    fun updateQuality(value: Int) { preferences.quality = value; mutableState.update { it.copy(quality = value) } }
    fun toggleAutoContinue() { preferences.autoContinue = !preferences.autoContinue; mutableState.update { it.copy(autoContinue = preferences.autoContinue) } }
    fun toggleDanmaku() { preferences.danmakuEnabled = !preferences.danmakuEnabled; mutableState.update { it.copy(danmakuEnabled = preferences.danmakuEnabled) } }
    fun togglePrivacy() { preferences.privacyMode = !preferences.privacyMode; mutableState.update { it.copy(privacyMode = preferences.privacyMode) } }
    fun clearSearchHistory() { preferences.clearSearchHistory(); mutableState.update { it.copy(searchHistory = emptyList()) } }

    override fun onCleared() { qrJob?.cancel(); contentJob?.cancel(); accountJob?.cancel(); super.onCleared() }
}
