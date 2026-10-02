package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.*
import coil3.PlatformContext
import com.android.purebilibili.core.database.dao.SearchHistoryDao
import com.android.purebilibili.core.database.entity.BlockedUp
import com.android.purebilibili.core.store.*
import com.android.purebilibili.data.repository.DesktopOriginalSearchRepository
import com.bilipai.desktop.data.DesktopSearchPreferences
import com.bilipai.desktop.plugins.DesktopPluginContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Captured child Job follows dispatchers. A cancelled older request cannot publish merely
 * because its retained page/Root is still current. No result/state authority lives here. */
internal class DesktopSearchCaller : ThreadContextElement<Job?>, AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<DesktopSearchCaller> {
        private val current = ThreadLocal<Job?>()
        fun check() { current.get()?.ensureActive() }
    }
    override fun updateThreadContext(context: CoroutineContext): Job? = current.get().also { current.set(context[Job]) }
    override fun restoreThreadContext(context: CoroutineContext, oldState: Job?) { current.set(oldState) }
}

internal class DesktopOriginalSearchBlocked(val records: StateFlow<List<BlockedUp>>) {
    fun getAllBlockedUps() = records
}

/** Views over ONE retained Root and existing history/global preferences/HTTP/WBI actors.
 * Lifetime only owns this route's jobs; closing it never closes any global service. */
internal class DesktopOriginalSearchEnvironment(
    parentScope: CoroutineScope,
    parentOwned: () -> Boolean,
    parentCommit: ((() -> Unit) -> Boolean),
    val pluginContext: DesktopPluginContext,
    val settings: DesktopHomeSettingsPort,
    preferences: DesktopSearchPreferences,
    capturedMid: Long?,
    val blocked: DesktopOriginalSearchBlocked,
    articleFactory: (() -> Boolean) -> DesktopPersonalArticleResolver,
    val backToTop: DesktopFavoritePreferences,
    requestFactory: (() -> Boolean) -> DesktopOriginalSearchRepository,
    val configuration: StateFlow<DesktopProfileWindowConfiguration>,
    val platformContext: PlatformContext,
    val renderEffectsSupported: Boolean,
    private val watchLater: suspend (Long, Boolean) -> Result<Boolean>,
    private val screenEvent: (String) -> Unit,
    private val searchEvent: (String) -> Unit,
    private val feedback: (String) -> Unit,
) : AutoCloseable {
    private val lifetime = DesktopHomeEmbeddedLifetime(parentScope, parentOwned, parentCommit)
    val scope = CoroutineScope(lifetime.scope.coroutineContext + DesktopSearchCaller())
    val requests: DesktopOriginalSearchRepository
    val history: SearchHistoryDao
    val articleResolver: DesktopPersonalArticleResolver
    init {
        try {
            assertCurrent()
            requests = requestFactory(::owns)
            articleResolver = articleFactory(::owns)
            history = preferences.originalHistory(capturedMid, ::assertCurrent, ::commit)
        } catch (failure: Throwable) { lifetime.close(); throw failure }
    }
    val suggestionsEnabled = preferences.suggestionsEnabled
    private val privacy = preferences::isPrivacyModeEnabledSync
    val originalSettings = DesktopOriginalPlayerSettingsContext(pluginContext, ::owns, ::commit)
    fun owns() = lifetime.owns()
    fun assertCurrent() { DesktopSearchCaller.check(); if (!owns()) throw CancellationException("Search retained entry retired") }
    fun commit(block: () -> Unit): Boolean {
        DesktopSearchCaller.check()
        return lifetime.commit { assertCurrent(); block() }
    }
    fun isPrivacyModeEnabledSync(): Boolean { assertCurrent(); return privacy().also { assertCurrent() } }
    fun logScreenView(name: String) { commit { screenEvent(name) } }
    fun logSearch(query: String) { commit { searchEvent(query) } }
    fun message(text: String) { commit { feedback(text) } }
    suspend fun toggleWatchLater(aid: Long, add: Boolean): Result<Boolean> {
        currentCoroutineContext().ensureActive(); assertCurrent()
        return watchLater(aid, add).also { currentCoroutineContext().ensureActive(); assertCurrent() }
    }
    override fun close() = lifetime.close()

    /** Same global BackToTop owner; file staging occurs outside the route admission lock. */
    suspend fun setBackToTopOffset(x: Float, y: Float) {
        currentCoroutineContext().ensureActive(); assertCurrent()
        originalSettings.settingsDataStore.edit { values ->
            values[playerFloatPreferencesKey("back_to_top_button_offset_x_dp")] = x
            values[playerFloatPreferencesKey("back_to_top_button_offset_y_dp")] = y
        }
        commit { backToTop.updateBackToTopOffset(x,y) }
    }
}

/** Optional platform boundary for the two shared original BackToTop consumers. Favorites
 * keeps its existing owner; Search supplies that SAME preferences owner without a list VM. */
internal data class DesktopBackToTopBindings(
    val enabled: Flow<Boolean>, val initialEnabled: Boolean,
    val offset: Flow<Pair<Float,Float>>, val initialOffset: Pair<Float,Float>,
    val setOffset: suspend(Float,Float) -> Unit, val updateOffset: (Float,Float) -> Unit,
    val viewport: DesktopFavoriteViewport,
)
internal val LocalDesktopBackToTopBindings = staticCompositionLocalOf<DesktopBackToTopBindings?> { null }
@Composable internal fun desktopBackToTopBindings(): DesktopBackToTopBindings {
    LocalDesktopBackToTopBindings.current?.let { return it }
    val old = LocalDesktopFavoriteBindings.current
    val viewport = LocalDesktopFavoriteViewport.current
    return DesktopBackToTopBindings(old.backToTopEnabled,old.initialBackToTopEnabled,
        old.backToTopOffset,old.initialBackToTopOffset,old.setBackToTopOffset,old.updateBackToTopOffset,viewport)
}
@Composable internal fun DesktopOriginalSearchBackToTop(content: @Composable () -> Unit) {
    val env = LocalDesktopOriginalSearchEnvironment.current
    val config by env.configuration.collectAsState()
    val port = DesktopBackToTopBindings(env.backToTop.backToTopEnabled,env.backToTop.initialBackToTopEnabled(),
        env.backToTop.backToTopOffset,env.backToTop.initialBackToTopOffset(),env::setBackToTopOffset,
        { x,y -> env.commit { env.backToTop.updateBackToTopOffset(x,y) } },
        DesktopFavoriteViewport(config.screenWidthDp,config.screenHeightDp))
    CompositionLocalProvider(LocalDesktopBackToTopBindings provides port,content=content)
}

internal val LocalDesktopOriginalSearchEnvironment = staticCompositionLocalOf<DesktopOriginalSearchEnvironment> {
    error("Full original Search requires its retained actual Root entry")
}
internal val LocalDesktopOriginalSearchActive = staticCompositionLocalOf { false }

/** Common NavigationEvent dispatcher already belongs to the actual Window/Root. */
@Composable internal fun DesktopOriginalSearchBackHandler(onBack: () -> Unit) {
    val state = rememberNavigationEventState(currentInfo = NavigationEventInfo.None)
    NavigationBackHandler(state = state, isBackEnabled = LocalDesktopOriginalSearchActive.current, onBackCompleted = onBack)
}

/** Original leaf getters use existing decoded Home settings, same navigation getters and
 * original Search preference methods. There is no parallel Settings state/store. */
internal object DesktopOriginalSearchSettings {
    fun getSearchFilterTabOrder(context: DesktopOriginalSearchEnvironment) =
        DesktopOriginalFullNavigationSettings.getSearchFilterTabOrder(context.pluginContext)
    fun getCardAnimationEnabled(context: DesktopOriginalSearchEnvironment) =
        DesktopOriginalFullNavigationSettings.getCardAnimationEnabled(context.pluginContext)
    fun getCardTransitionEnabled(context: DesktopOriginalSearchEnvironment) =
        DesktopOriginalFullNavigationSettings.getCardTransitionEnabled(context.pluginContext)
    fun getHomeDurationStyle(context: DesktopOriginalSearchEnvironment) = context.settings.homeSettings.map { it.homeDurationStyle }
    fun getCompactVideoStatsOnCover(context: DesktopOriginalSearchEnvironment) = context.settings.homeSettings.map { it.compactVideoStatsOnCover }
    fun getShowOnlineCount(context: DesktopOriginalSearchEnvironment) = context.settings.showOnlineCount
    fun getHomeFeedCardStyle(context: DesktopOriginalSearchEnvironment) = context.settings.homeFeedCardStyle
    fun getHomeSettings(context: DesktopOriginalSearchEnvironment) = context.settings.homeSettings
    fun getSearchHotSectionEnabled(context: DesktopOriginalSearchEnvironment) = DesktopOriginalSearchPreferenceMethods.getSearchHotSectionEnabled(context.originalSettings)
    fun getSearchDiscoverSectionEnabled(context: DesktopOriginalSearchEnvironment) = DesktopOriginalSearchPreferenceMethods.getSearchDiscoverSectionEnabled(context.originalSettings)
    suspend fun setSearchHotSectionEnabled(context: DesktopOriginalSearchEnvironment, value: Boolean) = DesktopOriginalSearchPreferenceMethods.setSearchHotSectionEnabled(context.originalSettings,value)
    suspend fun setSearchDiscoverSectionEnabled(context: DesktopOriginalSearchEnvironment, value: Boolean) = DesktopOriginalSearchPreferenceMethods.setSearchDiscoverSectionEnabled(context.originalSettings,value)
    fun getSearchSuggestionsEnabled(context: DesktopOriginalSearchEnvironment) = context.suggestionsEnabled
    fun isPrivacyModeEnabledSync(context: DesktopOriginalSearchEnvironment) = context.isPrivacyModeEnabledSync()
    fun isEasterEggEnabledSync(context: DesktopOriginalSearchEnvironment) = DesktopOriginalSearchPreferenceMethods.isEasterEggEnabledSync(context.originalSettings)
    fun getDefaultHintEnabled(context: DesktopOriginalSearchEnvironment) = SearchHintSettingsStore.isEnabled(context.pluginContext)
    suspend fun setGridColumnCountCompact(context: DesktopOriginalSearchEnvironment, value: Int) = DesktopOriginalSearchPreferenceMethods.setGridColumnCountCompact(context.originalSettings,value)
    suspend fun setGridColumnCount(context: DesktopOriginalSearchEnvironment, value: Int) = DesktopOriginalSearchPreferenceMethods.setGridColumnCount(context.originalSettings,value)
}
