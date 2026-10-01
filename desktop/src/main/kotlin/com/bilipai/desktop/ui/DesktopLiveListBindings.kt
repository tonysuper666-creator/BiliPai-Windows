package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.data.repository.DesktopOriginalHomeLiveProtocol
import com.android.purebilibili.data.repository.DesktopOriginalLiveListProtocol
import com.android.purebilibili.data.repository.LivePagedResult
import com.android.purebilibili.feature.live.LiveListScreen
import com.android.purebilibili.feature.live.LiveListUiState
import com.android.purebilibili.feature.live.LiveListViewModel
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One retained Home navigation entry and its immutable account epoch. commit must be atomic
 * with the actual SessionStore admission. This view owns no persistence, client or second gate. */
interface DesktopLiveListOwner {
    fun isCurrent(): Boolean
    fun commit(action: () -> Unit): Boolean
}

/** Exact original response types. Transport is the parent ownedHomeService BilibiliApi,
 * backed by Root's existing Call.Factory and CookieJar epoch admission. */
interface DesktopOriginalLiveListRequests {
    suspend fun getLiveFeedHome(page: Int): Result<LiveFeedHomeSnapshot>
    suspend fun getFollowedLivePage(page: Int): Result<LivePagedResult<LiveRoom>>
    suspend fun getLiveSecondHome(parentAreaId: Int, areaId: Int, page: Int, sortType: String?): Result<LiveFeedHomeSnapshot>
    suspend fun getLiveAreaList(): LiveAreaListResponse
}

/** saveCover must use Root's sole original gallery actor and the suspend caller's Job.
 * It must carry the supplied immutable owner to HTTP/file publication, rather than a new client.
 * feedback must atomically reject an entry/account that retired during the operation. */
interface DesktopLiveListPlatform {
    fun isCurrent(): Boolean
    suspend fun saveCover(url: String, title: String): Boolean
    fun feedback(message: String)
}

/** Only the original transient UI flow is wrapped. The wrapper serializes publication with the
 * existing page/account gate; no persistent preference/cache or copied account state is created. */
internal class DesktopOwnedLiveListState(initial: LiveListUiState, private val owner: DesktopLiveListOwner) {
    private val flow = MutableStateFlow(initial)
    var value: LiveListUiState
        get() = flow.value
        set(next) {
            if (!owner.commit { flow.value = next }) throw CancellationException("Live list owner retired")
        }
    fun asStateFlow(): StateFlow<LiveListUiState> = flow.asStateFlow()
}

/** A retained entry constructs this once using raw694's exact same environment. No Retrofit,
 * OkHttp, transport interceptor, API DTO, SessionStore or coroutine scope is constructed here. */
internal class DesktopOwnedOriginalLiveListRequests(private val environment: DesktopHomeProtocolEnvironment) : DesktopOriginalLiveListRequests {
    private val original = DesktopOriginalLiveListProtocol(environment)
    private val existing = DesktopOriginalHomeLiveProtocol(environment.api)
    private suspend fun <T> admitted(call: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        if (!environment.isCurrent()) throw CancellationException("Live list owner retired")
        val value = call()
        currentCoroutineContext().ensureActive()
        if (!environment.isCurrent()) throw CancellationException("Live list owner retired")
        return value
    }
    private suspend fun <T> result(call: suspend () -> Result<T>): Result<T> = admitted(call).also {
        (it.exceptionOrNull() as? CancellationException)?.let { cancelled -> throw cancelled }
    }
    override suspend fun getLiveFeedHome(page: Int) = result { original.getLiveFeedHome(page) }
    override suspend fun getFollowedLivePage(page: Int) = result { existing.getFollowedLivePage(page) }
    override suspend fun getLiveSecondHome(parentAreaId: Int, areaId: Int, page: Int, sortType: String?) = result {
        original.getLiveSecondHome(parentAreaId, areaId, page, sortType)
    }
    override suspend fun getLiveAreaList() = admitted { environment.api.getLiveAreaList() }
}

/** Parent retains this with the same Home Entry; its scope, API, atomic epoch admission and
 * transient VM are exactly one graph. Parent alone retires/cancels that entry's scope. */
internal class DesktopOriginalLiveListBinding(
    environment: DesktopHomeProtocolEnvironment,
    val platform: DesktopLiveListPlatform,
) {
    private val owner = object : DesktopLiveListOwner {
        override fun isCurrent() = environment.isCurrent()
        override fun commit(action: () -> Unit) = environment.commitIfCurrent(action)
    }
    val viewModel = LiveListViewModel(environment.parentScope, DesktopOwnedOriginalLiveListRequests(environment), owner)
}

/** Mount the complete original UI. VM/scope/gate live on the parent's retained entry, while
 * collection, pull refresh, visible-footer paging, shared cover transitions and scroll effects
 * remain in the full original LiveListScreen. All actual navigation actions are mandatory. */
@Composable
internal fun DesktopOriginalLiveListHost(
    viewModel: LiveListViewModel,
    platform: DesktopLiveListPlatform,
    onBack: () -> Unit,
    onLiveClick: (Long, String, String) -> Unit,
    onSearchClick: () -> Unit,
    onAreaListClick: () -> Unit,
    onFollowingClick: () -> Unit,
    onAreaDetailClick: (Int, Int, String) -> Unit,
    onMatchClick: () -> Unit,
    showNavigationBack: Boolean,
    embeddedInHome: Boolean,
    contentTopPadding: Dp,
    scrollToTopRequestId: Int,
    scrollToTopChannel: Channel<Unit>?,
    globalHazeState: HazeState?,
) {
    LiveListScreen(
        onBack = onBack,
        onLiveClick = onLiveClick,
        onSearchClick = onSearchClick,
        onAreaListClick = onAreaListClick,
        onFollowingClick = onFollowingClick,
        onAreaDetailClick = onAreaDetailClick,
        onMatchClick = onMatchClick,
        showNavigationBack = showNavigationBack,
        embeddedInHome = embeddedInHome,
        contentTopPadding = contentTopPadding,
        scrollToTopRequestId = scrollToTopRequestId,
        scrollToTopChannel = scrollToTopChannel,
        viewModel = viewModel,
        platform = platform,
        globalHazeState = globalHazeState,
    )
}
