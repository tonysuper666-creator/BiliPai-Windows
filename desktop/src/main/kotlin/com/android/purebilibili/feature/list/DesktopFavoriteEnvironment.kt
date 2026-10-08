package com.android.purebilibili.feature.list

import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.core.network.SpaceApi
import com.android.purebilibili.core.network.DynamicApi
import com.android.purebilibili.core.network.BangumiApi
import com.android.purebilibili.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

/** A page-owned view of Root's existing API/identity. It owns no transport, cookie or data store. */
class DesktopFavoriteEnvironment @JvmOverloads constructor(
    val scope: CoroutineScope,
    api: BilibiliApi,
    spaceApi: SpaceApi?,
    dynamicApi: DynamicApi?,
    bangumiApi: BangumiApi?,
    private val stillOwned: () -> Boolean,
    private val readCsrf: () -> String?,
    private val readMid: () -> Long?,
    private val feedback: (String) -> Unit,
    historyChanges: Flow<Long>?,
    getCachedPosition: ((String, Long) -> Long)?,
    privacyModeEnabled: (() -> Boolean)?,
    watchLaterChanged: (() -> Unit)?,
    private val readAccessToken: (() -> String?)?,
    private val readAccessTokenPlatform: (() -> String)?,
    private val followStateChanged: ((FollowStateChange) -> Unit)? = null,
) {
    private var historyReadCapture: ((Job, com.bilipai.desktop.ui.DesktopHistoryReadParameters) -> com.bilipai.desktop.ui.DesktopHistoryReadSource)? = null
    internal fun mountHistoryReadCapture(capture: (Job, com.bilipai.desktop.ui.DesktopHistoryReadParameters) -> com.bilipai.desktop.ui.DesktopHistoryReadSource) {
        assertOwned(); check(historyReadCapture == null)
        historyReadCapture = capture
    }
    internal suspend fun beginHistoryRead(parameters: com.bilipai.desktop.ui.DesktopHistoryReadParameters): com.bilipai.desktop.ui.DesktopHistoryReadSource? {
        val context = currentCoroutineContext(); context.ensureActive(); assertOwned()
        val capture = historyReadCapture ?: return null
        val caller = requireNotNull(context[Job]) { "History read requires its actual caller" }
        val source = capture(caller, parameters)
        check(source.environment === this && source.caller === caller && source.parameters == parameters)
        return source
    }
    private var brandEvents: com.android.purebilibili.core.events.BrandSuccessEvents? = null
    private var brandAdmission: ((() -> Unit) -> Boolean)? = null
    fun mountBrandFeedback(events: com.android.purebilibili.core.events.BrandSuccessEvents,
        admission: (() -> Unit) -> Boolean) {
        assertOwned(); check(brandEvents == null || brandEvents === events)
        brandEvents = events; brandAdmission = admission
    }
    suspend fun captureBrandFeedback(): com.bilipai.desktop.ui.DesktopBrandSuccessOrigin? {
        currentCoroutineContext().ensureActive(); assertOwned()
        val caller = currentCoroutineContext()[Job] ?: return null
        val permit = brandAdmission ?: return null
        return com.bilipai.desktop.ui.DesktopBrandSuccessOrigin(caller, ::isOwned, permit)
    }
    fun confirmBrandFavorite(origin: com.bilipai.desktop.ui.DesktopBrandSuccessOrigin?) {
        if (origin != null) brandEvents?.favoriteSaved(origin)
    }
    fun confirmBrandFollow(origin: com.bilipai.desktop.ui.DesktopBrandSuccessOrigin?, following: Boolean, detail: String? = null) {
        if (origin != null) brandEvents?.followChanged(origin, following, detail)
    }
    fun isOwned(): Boolean = stillOwned() && scope.isActive
    fun assertOwned() { if (!isOwned()) throw CancellationException("Favorites owner retired") }
    fun csrf(): String? { assertOwned(); return readCsrf() }
    fun currentMid(): Long? { assertOwned(); return readMid() }
    fun confirmFollow(change:FollowStateChange) {
        assertOwned(); requireNotNull(followStateChanged) { "Follow action notification is not mounted for this owner" }(change)
    }
    fun showFeedback(message: String) { assertOwned(); feedback(message) }
    val api: BilibiliApi = ownedApi(api, BilibiliApi::class.java)
    private val space = spaceApi?.let { ownedApi(it, SpaceApi::class.java) }
    private val dynamic = dynamicApi?.let { ownedApi(it, DynamicApi::class.java) }
    private val bangumi = bangumiApi?.let { ownedApi(it, BangumiApi::class.java) }
    val spaceApi: SpaceApi get() = requireNotNull(space) { "Space API is not mounted for this owner" }
    val dynamicApi: DynamicApi get() = requireNotNull(dynamic) { "Dynamic API is not mounted for this owner" }
    val bangumiApi: BangumiApi get() = requireNotNull(bangumi) { "PGC API is not mounted for this owner" }
    private val historyChangeBinding = historyChanges
    val historyChanges: Flow<Long> get() = requireNotNull(historyChangeBinding) { "History is not mounted for this owner" }
    private val cachedPositionBinding = getCachedPosition
    fun getCachedPosition(bvid:String,cid:Long):Long = requireNotNull(cachedPositionBinding) { "History is not mounted for this owner" }(bvid,cid)
    private val privacyBinding = privacyModeEnabled
    fun privacyModeEnabled():Boolean = requireNotNull(privacyBinding) { "History is not mounted for this owner" }()
    private val watchLaterBinding = watchLaterChanged
    fun watchLaterChanged() = requireNotNull(watchLaterBinding) { "WatchLater is not mounted for this owner" }()
    val favorite by lazy { DesktopOriginalFavoriteRepository(this) }
    val personal by lazy { DesktopOriginalPersonalFavoriteRepository(this) }
    val pgc by lazy { DesktopOriginalFavoritePgc(this) }
    val actions by lazy { DesktopOriginalFavoriteActions(this) }
    val history by lazy { DesktopOriginalHistoryRepository(this) }
    val liked by lazy { DesktopOriginalLikedVideosRepository(this) }
    suspend fun getSpaceLikedArchive(mid:Long,page:Int,pageSize:Int) = spaceApi.getSpaceLikedArchive(
        com.android.purebilibili.core.network.buildSpaceLikedArchiveParams(mid,page,pageSize,requireNotNull(readAccessToken)(),requireNotNull(readAccessTokenPlatform)()))
    suspend fun getSpaceCoinArchive(mid:Long,page:Int,pageSize:Int) = spaceApi.getSpaceCoinArchive(
        com.android.purebilibili.core.network.buildSpaceLikedArchiveParams(mid,page,pageSize,requireNotNull(readAccessToken)(),requireNotNull(readAccessTokenPlatform)()))

    companion object {
        /** Same parent API/action/identity adapter for the video drawer. Unmounted
         * categories/history dependencies are absent, never fake response objects. */
        fun forFolderDrawer(scope:CoroutineScope,api:BilibiliApi,stillOwned:()->Boolean,
            readCsrf:()->String?,readMid:()->Long?,feedback:(String)->Unit) =
            DesktopFavoriteEnvironment(scope,api,null,null,null,stillOwned,readCsrf,readMid,feedback,
                null,null,null,null,null,null)
    }

    /** Retirement is checked before transport and before a suspended or synchronous response
     * reaches the unchanged original VM. The supplied API remains Root's same API graph. */
    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> ownedApi(delegate: T, type: Class<T>): T = Proxy.newProxyInstance(
        type.classLoader, arrayOf(type)
    ) { _, method, arguments ->
        assertOwned()
        val args = arguments?.clone() ?: emptyArray()
        val continuation = args.lastOrNull() as? Continuation<Any?>
        if (continuation != null) args[args.lastIndex] = object : Continuation<Any?> {
            override val context = continuation.context
            override fun resumeWith(result: Result<Any?>) {
                val guarded = try { assertOwned(); result } catch (cancelled: CancellationException) { Result.failure(cancelled) }
                continuation.resumeWith(guarded)
            }
        }
        try {
            val result = method.invoke(delegate, *args)
            if (result !== COROUTINE_SUSPENDED) assertOwned()
            result
        } catch (failure: InvocationTargetException) { throw failure.targetException }
    } as T
}

/** Android ViewModel scope replacement; Root cancels this one owned page scope on disposal. */
abstract class DesktopFavoriteScopedOwner(protected val environment: DesktopFavoriteEnvironment) {
    protected val viewModelScope: CoroutineScope get() = environment.scope
}
