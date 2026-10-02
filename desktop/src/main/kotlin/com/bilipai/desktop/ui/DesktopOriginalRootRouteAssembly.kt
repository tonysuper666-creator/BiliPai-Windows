package com.bilipai.desktop.ui

import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.Snapshot
import com.android.purebilibili.navigation3.*
import com.android.purebilibili.navigation.AppSystemBackAction
import kotlinx.coroutines.*
import java.awt.EventQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Existing Root effects, not an alternative navigation state. beforeCommit applies actual
 * native ownership/leaf parameters while leaving the physical original stack authoritative.
 * resolveVideo must execute original portrait/offline/unknown-dimension routing on this caller
 * Job and the same captured epoch's services; it must not flatten the result to VideoCard. */
internal class DesktopOriginalRootRoutePlatform(
    val admit: ((() -> Unit) -> Boolean),
    val beforeCommit: (BiliPaiNavKey, BiliPaiNavKey) -> Unit,
    val resolveVideo: suspend (BiliPaiNavKey.VideoDetail, Boolean) -> BiliPaiNavKey?,
    val removeSaveableState: (String) -> Unit,
    val backAtRoot: () -> Unit,
)

/** ONE original SnapshotStateList, reused by NavDisplay and Root native/media consumers.
 * This owner follows retained Home epoch, not visible route or video drawing. No mirrored
 * section history or parallel navigation DTO is created. Root keeps the actual physical stack
 * at window scope; retiring this controller only releases its jobs/route VMs. */
internal class DesktopOriginalRootRouteAssembly(
    val root: DesktopHomeRetainedRoot,
    val stack: SnapshotStateList<BiliPaiNavKey>,
    private val platform: DesktopOriginalRootRoutePlatform,
    private val visibleBottomRoutes: () -> Set<String>,
) : DesktopOriginalRootRouteCommands, AutoCloseable {
    private val routeJob = SupervisorJob(root.entry.gate.scope.coroutineContext[Job])
    private val scope = CoroutineScope(root.entry.gate.scope.coroutineContext + routeJob)
    private val closed = AtomicBoolean(false)
    private val sessionKeys = DesktopOriginalRootSessionKeys()
    private val videoRequest = AtomicReference<Job?>(null)
    private val mainHostNavigation = AtomicReference<((BiliPaiNavKey) -> Boolean)?>(null)
    private val mainHostBackAction = AtomicReference<(() -> AppSystemBackAction)?>(null)
    fun bindMainHostBackAction(callback: () -> AppSystemBackAction) { mainHostBackAction.set(callback) }
    fun unbindMainHostBackAction(callback: () -> AppSystemBackAction) { mainHostBackAction.compareAndSet(callback, null) }
    private val categoryLock = Any()
    private val categoryOwners = mutableMapOf<BiliPaiNavKey.Category, DesktopCategoryRouteOwner>()
    val currentKey: BiliPaiNavKey get() = stack.lastOrNull() ?: BiliPaiNavKey.MainHost
    val previousKey: BiliPaiNavKey? get() = stack.getOrNull(stack.lastIndex - 1)
    fun owns() = !closed.get() && routeJob.isActive && root.isCurrentOwner()
    override fun containsEntry(key: BiliPaiNavKey): Boolean = owns() && stack.contains(key)

    /** Called by the genuinely mounted original MainHost pager; unbind by exact callback.
     * Its return false means target is not an original visible tab, so Nav3 must push it. */
    fun bindMainHostNavigation(callback: (BiliPaiNavKey) -> Boolean) { mainHostNavigation.set(callback) }
    fun unbindMainHostNavigation(callback: (BiliPaiNavKey) -> Boolean) {
        mainHostNavigation.compareAndSet(callback, null)
    }

    private fun decorate(key: BiliPaiNavKey) = sessionKeys.decorate(key)

    /** Root checkpoint precedes Store/entry admission. The commit callback must not suspend,
     * acquire a second Store or join a native/coroutine actor. */
    private fun admitted(action: () -> Unit): Boolean {
        check(EventQueue.isDispatchThread()) { "Actual Root navigation must run on its Window EDT" }
        if (!owns()) return false
        var applied = false
        val accepted = platform.admit {
            root.entry.gate.commit { if (owns()) { action(); applied = true } }
        }
        if (applied) pruneCategoryOwners()
        return accepted && applied
    }
    private fun replaceStack(next: List<BiliPaiNavKey>) {
        if (next == stack.toList()) return
        val old = currentKey
        val destination = next.lastOrNull() ?: BiliPaiNavKey.MainHost
        val removed = resolveRemovedNavigation3SaveableStateKeys(stack.toList(), next)
        platform.beforeCommit(old, destination)
        Snapshot.withMutableSnapshot { stack.clear(); stack.addAll(next) }
        removed.forEach(platform.removeSaveableState)
    }
    private fun pushAdmitted(key: BiliPaiNavKey) {
        val next = if (key is BiliPaiNavKey.SettingsCategory)
            pushOrReplaceSettingsCategoryNavKey(stack.toList(), key)
        else BiliPaiNavBackStackController(stack.toList()).push(key).backStack
        replaceStack(next)
    }
    override fun push(key: BiliPaiNavKey): Boolean {
        if (key is BiliPaiNavKey.VideoDetail) { video(key); return owns() }
        return admitted {
            // Original legacy top-level routes select the actual pager, not a second Home key.
            val parameterizedSearch = key is BiliPaiNavKey.Search && (key.keyword.isNotBlank() || key.openId != 0L)
            if (parameterizedSearch || mainHostNavigation.get()?.invoke(key) != true) pushAdmitted(decorate(key))
        }
    }

    /** Original AppNavigation onSeasonClick replaces the current detail top.
     * Use this same controller/stack/Store-entry admission and native beforeCommit. */
    fun replaceBangumiDetail(current: BiliPaiNavKey.BangumiDetail, seasonId: Long): Boolean {
        if (seasonId <= 0L || currentKey != current) return false
        var replaced = false
        val accepted = admitted {
            if (currentKey == current) {
                val next = decorate(BiliPaiNavKey.BangumiDetail(seasonId = seasonId))
                replaceStack(BiliPaiNavBackStackController(stack.toList()).replaceTop(next).backStack)
                replaced = true
            }
        }
        return accepted && replaced
    }

    override fun home(): Boolean = push(BiliPaiNavKey.Home)

    /** Exact stable AppNavigation portrait replacement (3061–3086). decorate
     * uses its original monotonic openId authority; stack replaces only the top. */
    override fun replaceVideoDetail(current: BiliPaiNavKey.VideoDetail, bvid: String,
        cid: Long, cover: String, resumePositionMs: Long): Boolean {
        val normalized = bvid.trim()
        if (normalized.isBlank() || normalized == current.bvid || currentKey != current) return false
        var replaced = false
        val admitted = admitted {
            if (currentKey == current && root.returns.clearVideoSourceForReplacement()) {
                val next = decorate(BiliPaiNavKey.VideoDetail(normalized, cid.coerceAtLeast(0L), cover,
                    resumePositionMs = resumePositionMs.coerceAtLeast(0L)))
                replaceStack(BiliPaiNavBackStackController(stack.toList()).replaceTop(next).backStack)
                replaced = true
            }
        }
        return admitted && replaced
    }
    override fun homeFromVideo(current: BiliPaiNavKey.VideoDetail): Boolean {
        if (currentKey != current) return false
        val accepted = root.returns.returnFromVideo(current, BiliPaiNavKey.MainHost, false) {
            if (owns() && currentKey == current) {
                mainHostNavigation.get()?.invoke(BiliPaiNavKey.Home)
                replaceStack(popBiliPaiNavKeyToRoot(stack.toList()))
            }
        }
        if (accepted) pruneCategoryOwners()
        return accepted
    }
    override fun markVideoReturning(current: BiliPaiNavKey.VideoDetail): Boolean =
        currentKey == current && root.returns.prepareReturnBeforeBack(current, previousKey)
    override fun clearVideoReturning(): Boolean = root.returns.consumeReturning()

    override fun videoRoute(route: String, sourceRoute: String) {
        val key = legacyRouteToBiliPaiNavKey(route)
        if (key is BiliPaiNavKey.VideoDetail) video(key.copy(sourceRoute = sourceRoute), directEntry = false)
        else push(key)
    }
    override fun video(key: BiliPaiNavKey.VideoDetail) = video(key, directEntry = true)
    private fun video(key: BiliPaiNavKey.VideoDetail, directEntry: Boolean) {
        check(EventQueue.isDispatchThread()) { "Actual Root video admission must run on its Window EDT" }
        if (!owns() || key.bvid.isBlank()) return
        videoRequest.getAndSet(null)?.cancel()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            ensureActive()
            if (!owns()) throw CancellationException("Root video route owner retired")
            val resolved = platform.resolveVideo(key, directEntry)
            ensureActive()
            if (!owns()) throw CancellationException("Root video route owner retired")
            when (resolved) {
                is BiliPaiNavKey.VideoDetail -> enterVideoResolved(resolved)
                is BiliPaiNavKey.Story -> enterStoryResolved(resolved)
                null -> Unit // Original unavailable-network feedback performed by resolver.
                else -> push(resolved) // Exact original offline or explicit non-video result.
            }
        }
        videoRequest.set(job)
        job.invokeOnCompletion { videoRequest.compareAndSet(job, null) }
        job.start()
    }
    private fun enterVideoResolved(key: BiliPaiNavKey.VideoDetail) {
        if (!owns()) return
        root.returns.enterVideo(key.bvid, key.sourceRoute, key.coverUrl, currentKey,
            stack.any { it is BiliPaiNavKey.VideoDetail }, visibleBottomRoutes()) { source, _ ->
            if (owns()) pushAdmitted(decorate(key.copy(sourceRoute = source.route)))
        }
        pruneCategoryOwners()
    }
    private fun enterStoryResolved(key: BiliPaiNavKey.Story) {
        // AppNavigation's portrait branch also captures the clicked card/source BEFORE push.
        root.returns.enterVideo(key.seedBvid, key.sourceRoute, key.seedCover, currentKey,
            stack.any { it is BiliPaiNavKey.VideoDetail }, visibleBottomRoutes()) { source, _ ->
            if (owns()) pushAdmitted(decorate(key.copy(sourceRoute = source.route)))
        }
        pruneCategoryOwners()
    }
    override fun back(): Boolean {
        if (!owns()) return false
        if (stack.size <= 1) return admitted {
            when (mainHostBackAction.get()?.invoke()) {
                AppSystemBackAction.RETURN_TO_HOME_TAB ->
                    if (mainHostNavigation.get()?.invoke(BiliPaiNavKey.Home) != true) pushAdmitted(BiliPaiNavKey.Home)
                else -> platform.backAtRoot()
            }
        }
        val before = currentKey
        val target = previousKey
        if (before is BiliPaiNavKey.VideoDetail) {
            val accepted = root.returns.returnFromVideo(before, target,
                isRelatedDetailPop = target is BiliPaiNavKey.VideoDetail) {
                if (owns()) replaceStack(BiliPaiNavBackStackController(stack.toList()).pop().backStack)
            }
            if (accepted) pruneCategoryOwners()
            return accepted
        }
        return admitted { replaceStack(BiliPaiNavBackStackController(stack.toList()).pop().backStack) }
    }
    override fun articleBack(article: BiliPaiNavKey.ArticleDetail, useSharedReturn: Boolean): Boolean {
        if (!owns() || currentKey != article || stack.size <= 1) return false
        return root.returns.returnFromArticle(useSharedReturn) {
            if (owns() && currentKey == article) replaceStack(BiliPaiNavBackStackController(stack.toList()).pop().backStack)
        }
    }
    /** Original sibling-tab selection resets only non-root Nav3 entries. Must be invoked by
     * the bound actual pager after its privacy/Root admission, never from an unrelated section. */
    fun returnToMainHostAdmitted() {
        check(owns())
        replaceStack(listOf(BiliPaiNavKey.MainHost))
    }
    fun category(key: BiliPaiNavKey.Category): DesktopCategoryRouteOwner {
        check(owns() && stack.contains(key)) { "Category requires its retained actual stack entry" }
        check(EventQueue.isDispatchThread()) { "Actual category lookup belongs to its Window EDT" }
        synchronized(categoryLock) { categoryOwners[key] }?.let { return it }
        // Never acquire the Store/gate while holding this map's lock. close may run on IO.
        val created = DesktopCategoryRouteOwner(key.name, DesktopCategoryEnvironment(key.tid,
            root.environment.settings, scope,
            { owns() && stack.contains(key) },
            { block ->
                var applied = false
                val accepted = root.entry.gate.commit {
                    if (owns() && stack.contains(key)) { block(); applied = true }
                }
                accepted && applied
            }, root.entry.requests.ports.video::getRegionVideos))
        val retained = synchronized(categoryLock) {
            if (closed.get()) null else categoryOwners.getOrPut(key) { created }
        }
        if (retained !== created) created.close()
        return requireNotNull(retained) { "Category retired during construction" }
    }
    fun callbackFor(key: BiliPaiNavKey, action: () -> Unit) {
        if (owns() && currentKey == key) action()
    }
    private fun pruneCategoryOwners() {
        val present = stack.toSet()
        val retired = synchronized(categoryLock) {
            categoryOwners.keys.filterNot(present::contains).mapNotNull(categoryOwners::remove)
        }
        retired.forEach { it.close() } // Outside Store/entry navigation admission; no join.
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        mainHostNavigation.set(null)
        mainHostBackAction.set(null)
        videoRequest.getAndSet(null)?.cancel()
        routeJob.cancel()
        val old = synchronized(categoryLock) { categoryOwners.values.toList().also { categoryOwners.clear() } }
        old.forEach { it.close() }
    }
}
