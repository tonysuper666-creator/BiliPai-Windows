package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import com.android.purebilibili.feature.message.*
import com.android.purebilibili.feature.message.feed.*
import com.android.purebilibili.data.repository.DesktopOriginalMessageRepository
import com.android.purebilibili.data.model.response.ViewInfo
import com.android.purebilibili.navigation3.*
import com.bilipai.desktop.data.DesktopRepository
import com.bilipai.desktop.data.DesktopCommunityRepository
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import java.awt.EventQueue
import java.nio.file.Files

internal class DesktopMessagePageServices(val requests: DesktopOriginalMessageRepository,
    val userInfo: suspend (Long) -> UserBasicInfo?, val videoInfo: suspend (String) -> Result<ViewInfo>)

internal val LocalDesktopMessagePageOwner = staticCompositionLocalOf<DesktopOriginalMessagePageOwner> {
    error("Full message pages require the actual retained Root entry")
}

/** Exactly one child per real retained message NavEntry. Covered pages stay alive; removed
 * entries are retired. Network services borrow Community's existing transport and Jar. */
internal class DesktopOriginalMessagePagesRoot(internal val repository: DesktopRepository,
    private val community: DesktopCommunityRepository, private val routes: DesktopOriginalRootRouteAssembly,
    private val home: DesktopHomeEnvironment, private val openLink: (BiliPaiNavKey,String) -> Unit,
    private val supportsRenderEffects: Boolean) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val entriesLock = Any()
    private val entries = mutableMapOf<BiliPaiNavKey, DesktopOriginalMessagePageOwner>()
    private val retiring = mutableListOf<DesktopOriginalMessagePageOwner>()
    fun isOwned() = !closed.get() && routes.owns() && routes.root.isCurrentOwner()
    fun entry(key: BiliPaiNavKey): DesktopOriginalMessagePageOwner {
        check(isOwned() && routes.containsEntry(key))
        return synchronized(entriesLock) {
            check(isOwned() && routes.containsEntry(key))
            entries.getOrPut(key) {
            val mid = requireNotNull(routes.root.entry.gate.mid) { "请先登录" }
            lateinit var owner: DesktopOriginalMessagePageOwner
            owner = DesktopOriginalMessagePageOwner(repository, community, routes.root.capturedEpoch, mid,
                routes.root.entry.gate.scope, { isOwned() && routes.containsEntry(key) },
                { isOwned() && routes.currentKey == key }, routes.root.entry.gate::commit, home, {url->openLink(key,url)}, supportsRenderEffects)
            owner
        } }
    }
    fun prune() {
        val removed=synchronized(entriesLock) {
            retiring.removeAll { it.isDrained() }
            entries.keys.filterNot(routes::containsEntry).mapNotNull(entries::remove).also { retiring.addAll(it) }
        }
        removed.forEach { it.close() }
    }
    override fun close() {
        val removed=synchronized(entriesLock) {
            if (!closed.compareAndSet(false,true)) return
            entries.values.toList().also { retiring.addAll(it);entries.clear() }
        }
        removed.forEach { it.close() }
    }
    suspend fun closeAndJoin() {
        close()
        val removed=synchronized(entriesLock) { retiring.toList() }
        withContext(NonCancellable) { removed.forEach { it.closeAndJoin() } }
        synchronized(entriesLock) { retiring.removeAll(removed.toSet()) }
    }
}

internal class DesktopOriginalMessagePageOwner(private val repository: DesktopRepository,
    private val community: DesktopCommunityRepository, val epoch: Long, val mid: Long,
    private val parent: CoroutineScope, private val retained: () -> Boolean,
    private val visible: () -> Boolean, private val commitEntry: ((() -> Unit) -> Boolean), val home: DesktopHomeEnvironment,
    private val link: (String) -> Unit, val supportsRenderEffects: Boolean) : AutoCloseable {
    private val live=AtomicBoolean(true)
    private val admission=createAdmission { live.get() && retained() }
    private val chatsLock=Any()
    private val chats=mutableMapOf<Pair<Long,Int>,Pair<DesktopMessagePageAdmission,ChatViewModel>>()
    private val retiringChats=mutableListOf<DesktopMessagePageAdmission>()
    val inbox by lazy { InboxViewModel(admission) }
    val replyMe by lazy { ReplyMeViewModel(admission) }
    val atMe by lazy { AtMeViewModel(admission) }
    val likeMe by lazy { LikeMeViewModel(admission) }
    val systemNotice by lazy { SystemNoticeViewModel(admission) }
    fun isOwned() = admission.isOwned()
    private fun createAdmission(owns:()->Boolean): DesktopMessagePageAdmission {
        lateinit var services:DesktopMessagePageServices
        val child=DesktopMessagePageAdmission(repository,epoch,mid,parent,owns,visible,commitEntry,
            { services.userInfo(it) }, { services.videoInfo(it) })
        services=community.originalMessagePages(child);child.requests=services.requests
        return child
    }
    fun chat(talkerId:Long,sessionType:Int):ChatViewModel {
        if(!isOwned()) throw CancellationException("Message entry retired")
        val id=talkerId to sessionType
        return synchronized(chatsLock) {
            if(!isOwned()) throw CancellationException("Message entry retired")
            chats.getOrPut(id) {
            val child=createAdmission { live.get() && retained() }
            val slot=child to ChatViewModel(talkerId,sessionType,child)
            slot
        }.second }
    }
    fun keepPaneChat(talkerId:Long,sessionType:Int) {
        if(!isOwned()) return
        val selected=talkerId to sessionType
        val removed=synchronized(chatsLock) {
            retiringChats.removeAll {it.isDrained()}
            chats.keys.filter { talkerId==0L || it!=selected }.mapNotNull {chats.remove(it)?.first}.also {retiringChats.addAll(it)}
        }
        removed.forEach {it.retire()}
    }
    fun openLink(url:String) { if (isOwned() && visible()) link(url) }
    fun feedback(text:String) { if (isOwned() && visible()) home.feedback(text) }
    fun isVisible()=isOwned() && visible()
    override fun close() { if(live.compareAndSet(true,false)) {
        admission.retire()
        val children=synchronized(chatsLock) {chats.values.map {it.first}+retiringChats}
        children.forEach {it.retire()}
    } }
    fun isDrained() = admission.isDrained() && synchronized(chatsLock) {
        chats.values.all { it.first.isDrained() } && retiringChats.all {it.isDrained()}
    }
    suspend fun closeAndJoin() {
        close()
        check(admission.retireAndJoin()) { "Message entry did not drain" }
        val children=synchronized(chatsLock) {chats.values.map {it.first}+retiringChats}
        children.forEach { check(it.retireAndJoin()) { "Chat entry did not drain" } }
    }
}

@Composable internal fun rememberDesktopOriginalMessagePagesRoot(repository:DesktopRepository,
    community:DesktopCommunityRepository,routes:DesktopOriginalRootRouteAssembly,
    supportsRenderEffects:Boolean):DesktopOriginalMessagePagesRoot {
    val home=routes.root.environment
    // Reuse the sole installed original typed message dispatcher with this captured entry.
    // Root-scoped creation precedes leaf composition, so no leaf-local fallback is read here.
    val links:(BiliPaiNavKey,String)->Unit=remember(routes) { {key,url->
        routes.callbackFor(key) { desktopOriginalOpenMessageLink(url,routes,key.toLegacyRoute()) }
    } }
    val owner=remember(repository,community,routes,home,links,supportsRenderEffects) {
        DesktopOriginalMessagePagesRoot(repository,community,routes,home,links,supportsRenderEffects)
    }
    LaunchedEffect(owner,routes) { snapshotFlow { routes.stack.toList() }.collect { owner.prune() } }
    DisposableEffect(owner) { onDispose { owner.close() } }
    return owner
}

@Composable internal fun DesktopOriginalMessagePageRootHost(key:BiliPaiNavKey,
    owner:DesktopOriginalMessagePagesRoot,routes:DesktopOriginalRootRouteAssembly,active:Boolean) {
    if (!owner.isOwned()) return
    CommunityLoginGate(owner.repository, { if(active) routes.callbackFor(key) { routes.push(BiliPaiNavKey.Login) } }) {
        DesktopOriginalAuthenticatedMessagePageRootHost(key,owner,routes,active)
    }
}
@Composable private fun DesktopOriginalAuthenticatedMessagePageRootHost(key:BiliPaiNavKey,
    owner:DesktopOriginalMessagePagesRoot,routes:DesktopOriginalRootRouteAssembly,active:Boolean) {
    val page=owner.entry(key)
    fun back() { if(active) routes.callbackFor(key) { routes.back() } }
    fun push(next:BiliPaiNavKey) { if(active) routes.callbackFor(key) { routes.push(next) } }
    val links=LocalDesktopOriginalMessageLinkNavigation.current
    CompositionLocalProvider(LocalDesktopMessagePageOwner provides page) {
        when(key) {
            BiliPaiNavKey.Inbox -> MessageCenterScreen(::back,
                { destination -> push(when(destination) {
                    MessageCenterDestination.ReplyMe -> BiliPaiNavKey.ReplyMe
                    MessageCenterDestination.AtMe -> BiliPaiNavKey.AtMe
                    MessageCenterDestination.LikeMe -> BiliPaiNavKey.LikeMe
                    MessageCenterDestination.SystemNotice -> BiliPaiNavKey.SystemNotice
                }) }, { talker,type,name -> push(BiliPaiNavKey.Chat(talker,type,name)) },
                { bvid -> if(active) routes.callbackFor(key) { links("https://www.bilibili.com/video/$bvid") } },links)
            BiliPaiNavKey.ReplyMe -> ReplyMeScreen(::back,links,{ push(BiliPaiNavKey.Space(it)) })
            BiliPaiNavKey.AtMe -> AtMeScreen(::back,links,{ push(BiliPaiNavKey.Space(it)) })
            BiliPaiNavKey.LikeMe -> LikeMeScreen(::back,links,{ push(BiliPaiNavKey.Space(it)) })
            BiliPaiNavKey.SystemNotice -> SystemNoticeScreen(::back,links)
            is BiliPaiNavKey.Chat -> ChatScreen(key.talkerId,key.sessionType,key.userName,::back,
                { bvid -> if(active) routes.callbackFor(key) { links("https://www.bilibili.com/video/$bvid") } },links)
            else -> error("Unsupported original message NavKey")
        }
    }
}

internal data class DesktopMessageWindowConfiguration(val screenWidthDp:Int,val screenHeightDp:Int)
@Composable internal fun desktopMessageWindowConfiguration():DesktopMessageWindowConfiguration {
    val size=LocalWindowInfo.current.containerSize;val density=LocalDensity.current.density
    return DesktopMessageWindowConfiguration((size.width/density).toInt(),(size.height/density).toInt())
}
internal class DesktopMessageImagePicker(private val launchPicker:()->Unit) { fun launch()=launchPicker() }
@Composable internal fun rememberDesktopMessageImagePicker(onImage:(DesktopMessageLocalImage?)->Unit):DesktopMessageImagePicker {
    val current by rememberUpdatedState(onImage)
    val owner=LocalDesktopMessagePageOwner.current
    return remember(owner) { DesktopMessageImagePicker {
        if(owner.isVisible()) EventQueue.invokeLater {
            if(owner.isVisible()) {
                val chooser=JFileChooser().apply { fileFilter=FileNameExtensionFilter("图片","jpg","jpeg","png","gif","webp");isMultiSelectionEnabled=false }
                if(chooser.showOpenDialog(null)==JFileChooser.APPROVE_OPTION && owner.isVisible()) {
                    val path=chooser.selectedFile.toPath()
                    current(DesktopMessageLocalImage(path,Files.probeContentType(path) ?: "image/jpeg"))
                }
            }
        }
    } }
}
