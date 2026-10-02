package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import com.android.purebilibili.feature.space.*
import com.android.purebilibili.navigation3.*
import com.bilipai.desktop.data.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import java.util.concurrent.atomic.AtomicBoolean

/** One original Space VM per physical retained Space/Rank/Guard NavEntry. The
 * supplied transport is Community's existing no-implicit-retry view of the same
 * global client/dispatcher/pool/Jar. Construction starts no remote request.
 */
internal class DesktopOriginalSpacePagesRoot(
    val repository:DesktopRepository,
    val community:DesktopCommunityRepository,
    val routes:DesktopOriginalRootRouteAssembly,
    private val transport:OkHttpClient,
) : AutoCloseable {
    private val closed=AtomicBoolean(false)
    private val lock=Any()
    private val pages=linkedMapOf<BiliPaiNavKey,DesktopOriginalSpacePageEntry>()
    private val retired=mutableListOf<DesktopOriginalSpaceEnvironment>()
    private fun owns()=!closed.get()&&routes.owns()&&routes.root.isCurrentOwner()
    fun entry(key:BiliPaiNavKey):DesktopOriginalSpacePageEntry {
        val mid=when(key){is BiliPaiNavKey.Space->key.mid;is BiliPaiNavKey.UpowerRank->key.mid;is BiliPaiNavKey.MemberGuard->key.mid;else->error("Not a Space page")}
        require(mid>0)
        synchronized(lock) {
            if(!owns()||!routes.containsEntry(key))throw CancellationException("Space entry removed")
            return pages.getOrPut(key) {
                val gate=routes.root.entry.gate
                DesktopOriginalSpacePageEntry(DesktopOriginalSpaceEnvironment(repository,gate.epoch,gate.mid,mid,gate.scope,
                    {owns()&&routes.containsEntry(key)},{owns()&&routes.currentKey==key},gate::commit,
                    routes.root.environment.feedback,transport))
            }
        }
    }
    fun prune() {
        val removed=synchronized(lock) { pages.keys.filterNot(routes::containsEntry).mapNotNull(pages::remove).map {it.environment}.also {retired.addAll(it)} }
        removed.forEach {it.close()}
    }
    override fun close() {
        val removed=synchronized(lock) {
            if(!closed.compareAndSet(false,true))return
            pages.values.map {it.environment}.also {retired.addAll(it);pages.clear()}
        }
        removed.forEach {it.close()}
    }
    suspend fun closeAndJoin() { close();val removed=synchronized(lock){retired.toList()}
        withContext(NonCancellable){ removed.forEach {check(it.closeAndJoin()) {"Space child did not drain"}} }
        synchronized(lock){retired.removeAll(removed.toSet())}
    }
}

/** The original view models live in the retained physical entry record, not a
 * composable remember whose disposal would erase tab/search/scroll metadata. */
internal class DesktopOriginalSpacePageEntry(val environment:DesktopOriginalSpaceEnvironment) {
    val viewModel by lazy {SpaceViewModel(environment)}
    private var rank:SpaceUpowerRankViewModel?=null
    private var guard:SpaceMemberGuardViewModel?=null
    fun rank(mid:Long,name:String,count:Long):SpaceUpowerRankViewModel {
        environment.requireMid(mid)
        return rank?:SpaceUpowerRankViewModel(environment,mid,name,count).also {rank=it}
    }
    fun guard(mid:Long,name:String,count:Long):SpaceMemberGuardViewModel {
        environment.requireMid(mid)
        return guard?:SpaceMemberGuardViewModel(environment,mid,name,count).also {guard=it}
    }
}

/** Mounted by the real Root leaf dispatcher, not DesktopSection.USER state. The
 * shared comment/gallery session and original playlist are actual required Root
 * owners; a second actor or renderer is never supplied as a fallback.
 */
@Composable internal fun DesktopOriginalSpacePageRootHost(
    key:BiliPaiNavKey,
    pages:DesktopOriginalSpacePagesRoot,
    playlist:DesktopOriginalVideoPlaylistBinding,
    cachedPosition:(String)->Long,
    shareText:(String,String,()->Boolean)->Unit,
    supportsRenderEffects:Boolean,
    active:Boolean,
) {
    val routes=pages.routes
    val entry=pages.entry(key)
    val environment=entry.environment
    val comments=LocalDesktopOriginalCommentRootOwner.current
    val session=checkNotNull(LocalDesktopDynamicCardSession.current)
    val home=routes.root.environment
    val latestActive by rememberUpdatedState(active)
    fun navigate(action:()->Unit){if(latestActive)routes.callbackFor(key,action)}
    val platform=remember(environment,comments,session,playlist,home) {
        DesktopOriginalSpacePlatform(entry,home,supportsRenderEffects,pages.community,session,comments,
            playlist,cachedPosition,shareText,{id->navigate {routes.push(BiliPaiNavKey.DynamicDetail(id))}})
    }
    if(!environment.owns()||!session.matches(pages.repository,environment.epoch))return
    val articleLink=LocalDesktopOriginalMessageLinkNavigation.current
    CompositionLocalProvider(LocalDesktopOriginalSpacePlatform provides platform) {
        when(key) {
            is BiliPaiNavKey.Space->SpaceScreen(key.mid,key.targetBvid,
                onBack={navigate {routes.back()}},
                onVideoClick={bv,cid,progress->navigate {routes.video(BiliPaiNavKey.VideoDetail(bv,cid,resumePositionMs=progress,sourceRoute=key.toLegacyRoute()))}},
                onAudioClick={sid->navigate {routes.push(BiliPaiNavKey.MusicDetail(sid))}},
                onBangumiClick={id->if(id>0)navigate {routes.push(BiliPaiNavKey.BangumiDetail(id))}},
                onCheeseClick={id->if(id>0)navigate {routes.push(BiliPaiNavKey.BangumiPlayer(id,0,isCourse=true))}},
                onWebClick={url,title->navigate {routes.push(BiliPaiNavKey.Web(url,title))}},
                onLiveClick={id,title,name->if(id>0)navigate {routes.push(BiliPaiNavKey.Live(id.toString(),title,name))}},
                onUserClick={mid->navigate {routes.push(BiliPaiNavKey.Space(mid))}},
                onTopicClick={id->if(id>0)navigate {routes.push(BiliPaiNavKey.TopicDetail(id))}},
                onTopicKeywordClick={word->navigate {routes.push(BiliPaiNavKey.Search(word))}},
                onPlayAllAudioClick={bv,progress->navigate {routes.push(BiliPaiNavKey.AudioMode(sourceBvid=bv,sourceResumePositionMs=progress))}},
                onDynamicDetailClick={id->navigate {routes.push(BiliPaiNavKey.DynamicDetail(id))}},
                // Existing original typed dispatcher resolves native article/opus
                // before routing, using its sole captured Root request owner.
                onArticleClick={id,_->if(id>0)navigate {articleLink("https://www.bilibili.com/read/cv$id")}},
                onViewAllClick={type,id,mid,title,name->navigate {routes.push(when(type.lowercase()){
                    "coin"->BiliPaiNavKey.LikedVideos(mid,name,true)
                    "like","liked"->BiliPaiNavKey.LikedVideos(mid,name)
                    else->BiliPaiNavKey.SeasonSeriesDetail(type,id,mid,title,name)
                })}},
                onLikedVideosClick={mid,name->navigate {routes.push(BiliPaiNavKey.LikedVideos(mid,name))}},
                onMessageClick={mid,name,_->navigate {routes.push(BiliPaiNavKey.Chat(mid,1,name))}},
                onFollowingClick={mid->navigate {routes.push(BiliPaiNavKey.Following(mid))}},
                // v023 fans is an explicitly original Web leaf, not a native
                // list promised by the full SpaceScreen closure.
                onFansClick={mid->navigate {routes.push(BiliPaiNavKey.Web("https://space.bilibili.com/$mid/fans/fans","粉丝"))}},
                onUpowerRankClick={mid,name,count->navigate {routes.push(BiliPaiNavKey.UpowerRank(mid,name,count))}},
                onMemberGuardClick={mid,name,count->navigate {routes.push(BiliPaiNavKey.MemberGuard(mid,name,count))}},
                sharedTransitionScope=null,animatedVisibilityScope=null)
            is BiliPaiNavKey.UpowerRank->SpaceUpowerRankScreen(key.mid,key.name,key.count,{navigate {routes.back()}},{mid->navigate {routes.push(BiliPaiNavKey.Space(mid))}})
            is BiliPaiNavKey.MemberGuard->SpaceMemberGuardScreen(key.mid,key.name,key.count,{navigate {routes.back()}},{mid->navigate {routes.push(BiliPaiNavKey.Space(mid))}})
            else->error("Not an original Space page")
        }
    }
}
