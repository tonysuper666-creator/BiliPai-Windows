package com.bilipai.desktop.ui

import com.android.purebilibili.core.network.*
import com.android.purebilibili.feature.list.*
import com.android.purebilibili.feature.space.SeasonSeriesDetailViewModel
import com.bilipai.desktop.plugins.DesktopPluginStore
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicBoolean

/** A Windows navigation entry, not a second folder/items/cache authority. Root retains it
 * while the video or Listen child covers its original CommonListScreen. */
internal class DesktopFavoritesRootEntry(
    val epoch: Long,
    private val currentEpoch: () -> Long,
    parentScope: CoroutineScope,
    api: BilibiliApi,
    spaceApi: SpaceApi,
    dynamicApi: DynamicApi,
    bangumiApi: BangumiApi,
    globalStore: DesktopPluginStore,
    readMid: () -> Long?,
    readCsrf: () -> String?,
    feedback: (String) -> Unit,
    private val rootAlive: () -> Boolean,
) : AutoCloseable {
    private val alive = AtomicBoolean(true)
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    val scope = CoroutineScope(parentScope.coroutineContext + job)
    val environment = DesktopFavoriteEnvironment(scope, api, spaceApi, dynamicApi, bangumiApi,
        stillOwned = ::isOwned, readCsrf = readCsrf, readMid = readMid, feedback = feedback,
        historyChanges = null, getCachedPosition = null, privacyModeEnabled = null,
        watchLaterChanged = null, readAccessToken = null, readAccessTokenPlatform = null)
    val preferences = DesktopFavoritePreferences(globalStore)
    val viewModel = FavoriteViewModel(environment)
    val categories = FavoriteCategoryViewModel(environment)
    /** Original channels are retained by the same entry; covered UI has no receiver. */
    val searchChannel = Channel<String>(Channel.CONFLATED)
    val scrollToTopChannel = Channel<Unit>(Channel.CONFLATED)
    val saveableKey = java.util.UUID.randomUUID().toString()
    val savedStateKeys = linkedSetOf<String>()
    var currentDetail: FavoriteCollectionRoute? by mutableStateOf(null)
    private val details = linkedMapOf<FavoriteCollectionRoute, SeasonSeriesDetailViewModel>()

    init { viewModel.loadData() }

    fun stateKey(route: FavoriteCollectionRoute?): String =
        (saveableKey + ":" + (route?.let { "${it.type}:${it.mid}:${it.id}" } ?: "root")).also(savedStateKeys::add)

    fun isOwned(): Boolean = alive.get() && job.isActive && currentEpoch() == epoch && rootAlive()
    fun detailViewModel(route: FavoriteCollectionRoute): SeasonSeriesDetailViewModel {
        environment.assertOwned()
        return details.getOrPut(route) {
            SeasonSeriesDetailViewModel(environment).also { it.init(route.type, route.id, route.mid, route.title, route.ownerName) }
        }
    }

    override fun close() {
        if (!alive.getAndSet(false)) return
        searchChannel.close(); scrollToTopChannel.close(); job.cancel()
        details.clear()
    }

    suspend fun shutdownForRestore() = withContext(NonCancellable) {
        close(); job.join()
    }
}
