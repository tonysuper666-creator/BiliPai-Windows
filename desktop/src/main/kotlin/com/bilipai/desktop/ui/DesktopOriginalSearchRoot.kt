package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import com.android.purebilibili.feature.search.*
import com.android.purebilibili.navigation3.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import com.bilipai.desktop.data.AccountSummary
import java.awt.EventQueue
import java.util.concurrent.atomic.AtomicBoolean

/** Retains the ORIGINAL VMs by actual physical key, including keyword/openId. Map contains
 * only page owners; physical Nav3/pager remains the sole navigation authority. Covering a
 * Search with video keeps its VM/filter/list state; actual pop/epoch/restore retires it. */
internal class DesktopOriginalSearchRoot(
    val routes: DesktopOriginalRootRouteAssembly,
    private val published: () -> Boolean,
    val account: StateFlow<AccountSummary?>,
    private val createEnvironment: (owned: () -> Boolean, commit: ((() -> Unit) -> Boolean)) -> DesktopOriginalSearchEnvironment,
) : AutoCloseable {
    internal data class Identity(val key: BiliPaiNavKey, val pagerHosted: Boolean)
    private val closed = AtomicBoolean()
    private val lock = Any()
    private val owners = mutableMapOf<Identity, DesktopOriginalSearchEntry>()
    private val bottomMotion = mutableMapOf<BiliPaiNavKey.Search,Int>()
    fun bottomBarEntry(key: BiliPaiNavKey.Search, motionKey: Int) {
        routes.root.entry.gate.commit {
            if (!closed.get() && published() && routes.containsEntry(key)) synchronized(lock) {
                bottomMotion[key] = motionKey
            }
        }
    }
    fun owns(id: Identity) = !closed.get() && published() && routes.owns() &&
        routes.stack.contains(if (id.pagerHosted) BiliPaiNavKey.MainHost else id.key)
    fun entry(key: BiliPaiNavKey, pagerHosted: Boolean): DesktopOriginalSearchEntry {
        check(EventQueue.isDispatchThread()) { "Original Search lookup requires the actual Window EDT" }
        require(key is BiliPaiNavKey.Search || key == BiliPaiNavKey.SearchTrending)
        val id = Identity(key,pagerHosted)
        check(owns(id)) { "Original Search requires a retained actual Root entry" }
        synchronized(lock) { owners[id] }?.let { return it }
        val env = createEnvironment({ owns(id) }) { action ->
            var applied = false
            val accepted = routes.root.entry.gate.commit { if (owns(id)) { action(); applied = true } }
            accepted && applied
        }
        val motion = synchronized(lock) { (key as? BiliPaiNavKey.Search)?.let(bottomMotion::remove) ?: 0 }
        val created = try { DesktopOriginalSearchEntry(id,env,motion) }
        catch (failure: Throwable) { env.close(); throw failure }
        val result = synchronized(lock) { if (closed.get()) null else owners.getOrPut(id) { created } }
        if (result !== created) created.close()
        return requireNotNull(result) { "Original Search retired during construction" }
    }
    fun prune() {
        val retired = synchronized(lock) {
            bottomMotion.keys.removeAll { !routes.containsEntry(it) }
            owners.keys.filterNot(::owns).mapNotNull(owners::remove)
        }
        retired.forEach { it.close() } // no gate/map lock while cancelling children.
    }
    fun navigate(entry: DesktopOriginalSearchEntry, active: Boolean, block: () -> Unit) {
        val id = entry.identity
        if (active && entry.environment.owns() && owns(id) &&
            routes.currentKey == if (id.pagerHosted) BiliPaiNavKey.MainHost else id.key) block()
    }
    override fun close() {
        if (!closed.compareAndSet(false,true)) return
        val old = synchronized(lock) { owners.values.toList().also { owners.clear(); bottomMotion.clear() } }
        old.forEach { it.close() }
    }
}

internal class DesktopOriginalSearchEntry(val identity: DesktopOriginalSearchRoot.Identity,
    val environment: DesktopOriginalSearchEnvironment, val motionKey: Int = 0) : AutoCloseable {
    val viewModel: SearchViewModel? = if (identity.key is BiliPaiNavKey.Search) SearchViewModel(environment) else null
    val trending: SearchTrendingViewModel? = if (identity.key == BiliPaiNavKey.SearchTrending) SearchTrendingViewModel(environment) else null
    private val consumed = AtomicBoolean()
    var motionSource by mutableStateOf(if(motionKey>0)SearchEntryMotionSource.BOTTOM_BAR else SearchEntryMotionSource.NONE)
        private set
    fun consumeMotion(key: Int) { if(key==motionKey) environment.commit { motionSource=SearchEntryMotionSource.NONE } }
    fun initialKeyword() = if (!consumed.get()) (identity.key as? BiliPaiNavKey.Search)?.keyword.orEmpty() else ""
    fun initialKeywordConsumed() { environment.commit { consumed.set(true) } }
    override fun close() = environment.close()
}

internal val LocalDesktopOriginalSearchRoot = staticCompositionLocalOf<DesktopOriginalSearchRoot> {
    error("Original Search needs the published physical Root")
}

@Composable internal fun DesktopOriginalSearchPhysicalLeaf(key: BiliPaiNavKey, active: Boolean,
    pagerHosted: Boolean, onBack: () -> Unit,
    returningFromVideo: Boolean = false, quickReturningFromVideo: Boolean = false,
    consumeVideoReturn: () -> Unit = {}) {
    val root = LocalDesktopOriginalSearchRoot.current
    val account by root.account.collectAsState()
    val entry = remember(root,key,pagerHosted) { root.entry(key,pagerHosted) }
    val commands = root.routes
    fun navigate(block: () -> Unit) = root.navigate(entry,active,block)
    CompositionLocalProvider(LocalDesktopOriginalSearchEnvironment provides entry.environment,
        LocalDesktopOriginalSearchActive provides active) {
        DesktopOriginalSearchBackToTop { when(key) {
            is BiliPaiNavKey.Search -> SearchScreen(viewModel=checkNotNull(entry.viewModel),userFace=account?.avatar.orEmpty(),
                initialKeyword=entry.initialKeyword(),onInitialKeywordConsumed={ entry.initialKeywordConsumed() },
                onBack={ navigate(onBack) },onOpenTrending={ navigate { commands.push(BiliPaiNavKey.SearchTrending) } },
                onNavigateSearchTarget={ target ->
                    var accepted=false
                    navigate { accepted=dispatchDesktopReadyNativeTarget(commands.root,commands,target) }
                    accepted
                },
                onVideoClick={ bvid,cid,cover -> navigate { commands.video(BiliPaiNavKey.VideoDetail(bvid,cid,cover,sourceRoute=key.toLegacyRoute())) } },
                onWebClick={ url,title -> navigate { commands.push(BiliPaiNavKey.Web(url,title)) } },
                onUpClick={ mid -> navigate { commands.push(BiliPaiNavKey.Space(mid)) } },
                onBangumiClick={ season -> if(season>0L)navigate { commands.push(BiliPaiNavKey.BangumiDetail(season)) } },
                onCheeseClick={ season,ep -> navigate { commands.push(BiliPaiNavKey.BangumiPlayer(season,ep,isCourse=true)) } },
                onLiveClick={ room,title,name -> navigate { commands.push(BiliPaiNavKey.Live(roomId=room.toString(),title=title,uname=name)) } },
                onTopicClick={ id -> if(id>0L)navigate { commands.push(BiliPaiNavKey.TopicDetail(id)) } },
                onArticleClick={ id,title -> navigate { entry.environment.scope.launch {
                    val target=entry.environment.articleResolver.resolve(id)
                    navigate { when(target) {
                        is ArticleNavigationTarget.NativeArticle -> commands.push(BiliPaiNavKey.ArticleDetail(target.articleId,title))
                        is ArticleNavigationTarget.NativeDynamic -> commands.push(BiliPaiNavKey.DynamicDetail(target.dynamicId))
                        null -> Unit
                    } }
                } } },
                onAvatarClick={ navigate { commands.push(if(account!=null)BiliPaiNavKey.Profile else BiliPaiNavKey.Login) } },
                entryMotionSource=entry.motionSource,entryMotionKey=entry.motionKey,onEntryMotionConsumed=entry::consumeMotion,
                isReturningFromVideoDetail=returningFromVideo,isQuickReturningFromVideoDetail=quickReturningFromVideo,
                onVideoDetailReturnAnimationConsumed={ navigate(consumeVideoReturn) })
            BiliPaiNavKey.SearchTrending -> SearchTrendingScreen(onBack={ navigate(onBack) },
                onKeywordClick={ query -> navigate { commands.push(BiliPaiNavKey.Search(query)) } },viewModel=checkNotNull(entry.trending))
            else -> throw CancellationException("Original Search key retired")
        } }
    }
}
