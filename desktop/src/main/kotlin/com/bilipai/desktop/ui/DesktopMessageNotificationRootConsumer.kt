package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.core.network.DesktopDynamicAuthorUpdatesApi
import com.android.purebilibili.core.network.DesktopDynamicAuthorUpdatesService
import com.android.purebilibili.core.network.DynamicApi
import com.android.purebilibili.core.network.MessageApi
import com.android.purebilibili.core.network.grpc.DesktopDynamicBiliGrpcClient
import com.android.purebilibili.core.network.grpc.DesktopDynamicPersonalFeedApi
import com.android.purebilibili.feature.message.notification.*
import com.bilipai.desktop.data.DesktopCommunityRepository
import com.bilipai.desktop.data.DesktopDynamicGrpcRequestSource
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import java.awt.Frame
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.event.WindowStateListener
import java.util.logging.Level
import java.util.logging.Logger

internal val LocalDesktopMessageNotificationContext =
    staticCompositionLocalOf<DesktopMessageNotificationContext?> { null }

/** Windows host of upstream's message notification files for one actual retained Root lifetime. Upstream syncs the
 * schedule at interactive startup and after every settings change; here both are this Root's settings flow. The
 * Worker's periodic work and the resident Service then run as this window's tasks; nothing polls after it exits. */
@Composable
internal fun DesktopMessageNotificationRootConsumer(context: DesktopMessageNotificationContext?,
    community: DesktopCommunityRepository, window: Window) {
    val port = remember(context, window) { context?.let { DesktopWindowsMessageNotifications(it, window) } }
    // Same keys as the Root lifetime: disposing closes the context for good.
    DisposableEffect(context, port) {
        val actual = context
        val watcher = if (actual != null && port != null) {
            actual.attach(port, DesktopNetworkMessageNotificationSources(community))
            DesktopMessageNotificationWindowWatcher(window, actual::windowShownChanged)
        } else null
        onDispose { watcher?.close(); context?.close(); port?.close() }
    }
    LaunchedEffect(context, community, port) {
        val actual = context ?: return@LaunchedEffect
        port ?: return@LaunchedEffect
        MessageNotificationSettingsStore.getSettings(actual).distinctUntilChanged().collectLatest { settings ->
            actual.check()
            try { MessageNotificationSync.sync(actual) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                Logger.getLogger("MessageNotification").log(Level.WARNING, "Notification scheduling failed", failure)
            }
            if (settings.enabled && settings.notifyDynamicUpdates && actual.hasAuthenticatedSession()) {
                // Token-dependent path: without an App access token the original preload refuses before
                // any request. Say so once per settings session instead of failing silently every cycle.
                desktopDynamicPreloadBlockedReason(actual.appAccessToken())?.let { reason ->
                    Logger.getLogger("MessageNotification").log(Level.INFO,
                        "Dynamic update notifications are inactive for this account: $reason")
                }
            }
        }
    }
}

/** "Screen on" of upstream's resident Service: the window is displayable, visible and not minimised. EDT only. */
private class DesktopMessageNotificationWindowWatcher(private val window: Window, private val changed: (Boolean) -> Unit) :
    AutoCloseable {
    private fun publish() {
        changed(window.isDisplayable && window.isVisible && (window !is Frame || window.extendedState and Frame.ICONIFIED == 0))
    }
    private val state = WindowStateListener { publish() }
    private val components = object : ComponentAdapter() {
        override fun componentShown(e: ComponentEvent) = publish()
        override fun componentHidden(e: ComponentEvent) = publish()
    }
    private val windows = object : WindowAdapter() {
        override fun windowIconified(e: WindowEvent) = publish()
        override fun windowDeiconified(e: WindowEvent) = publish()
        override fun windowClosed(e: WindowEvent) = publish()
    }
    init {
        window.addWindowStateListener(state)
        window.addComponentListener(components)
        window.addWindowListener(windows)
        publish()
    }
    override fun close() {
        window.removeWindowStateListener(state)
        window.removeComponentListener(components)
        window.removeWindowListener(windows)
        changed(false)
    }
}

internal suspend fun <T> desktopMessageNotificationPoll(context: android.content.Context,
    block: suspend (MessageNotificationSource) -> T): T {
    val actual = desktopMessageNotificationContext(context)
    val sources = actual.sources ?: throw CancellationException("Message notification window detached")
    return sources.poll(actual, block)
}

/** One original Checker poll over this window's Root: a notification child of the message pages (same Store/Root
 * admission, the original Repository Call.Factory), retired when the poll ends. */
internal class DesktopNetworkMessageNotificationSources(private val community: DesktopCommunityRepository) :
    DesktopMessageNotificationSources {
    override suspend fun <T> poll(context: DesktopMessageNotificationContext, block: suspend (MessageNotificationSource) -> T): T {
        val actual = context
        val mid = actual.mid ?: throw CancellationException("Message notification Root has no account")
        val admission = DesktopMessagePageAdmission(actual.repository, actual.epoch, mid,
            actual.root.entry.gate.scope, actual::isCurrent, actual::isCurrent,
            actual::admit,
            { error("Notification snapshots do not issue user-profile reads") },
            { error("Notification snapshots do not issue video-detail reads") })
        try {
            admission.requests = community.originalMessagePages(admission).requests
            val result = java.util.concurrent.atomic.AtomicReference<Result<T>>()
            admission.awaitRead("message-notification-check") {
                val caller = currentCoroutineContext()
                fun sameSession() { caller.ensureActive(); actual.check(); admission.isOwned().also {
                    if (!it) throw CancellationException("Message notification request retired")
                } }
                val source = DesktopOwnedMessageNotificationSource(actual, admission,
                    requireNotNull(caller[Job]), ::sameSession)
                result.set(Result.success(block(source)))
            }
            return result.get().getOrThrow()
        } finally {
            withContext(NonCancellable) { admission.retireAndJoin() }
        }
    }
}

/** Exactly the original read-only source mappings (upstream's NetworkMessageNotificationSource), replacing Android
 * singleton services by the existing same-Root request admission and Repository's original Call.Factory. */
private class DesktopOwnedMessageNotificationSource(
    private val context: DesktopMessageNotificationContext,
    private val owner: DesktopMessagePageAdmission,
    private val requestJob: Job,
    private val checkRequest: () -> Unit,
) : MessageNotificationSource {
    private val dynamic = context.repository.ownedHomeService(DynamicApi::class.java,
        "https://api.bilibili.com/", context.epoch, { context.isCurrent() && owner.isOwned() && currentCallerActive() })
    private val api = context.repository.ownedHomeService(BilibiliApi::class.java,
        "https://api.bilibili.com/", context.epoch, { context.isCurrent() && owner.isOwned() && currentCallerActive() })
    private val messages = context.repository.ownedHomeService(MessageApi::class.java,
        "https://api.vc.bilibili.com/", context.epoch, ::owned)
    private fun owned() = context.isCurrent() && owner.isOwned() && currentCallerActive()
    // Recent-author red dots: read-only web endpoints over the account cookies. The endpoints need no App
    // token, but the Poller only reaches them after the token-gated following scan, so web-only accounts never do.
    private val authorUpdates = context.repository.ownedHomeService(DesktopDynamicAuthorUpdatesService::class.java,
        "https://api.bilibili.com/", context.epoch, ::owned)
    /** The App access token of this account (null for a web-only session), read under the same admission. */
    private fun accessToken(): String? {
        checkRequest()
        return context.repository.ownedHomeAccessToken(context.epoch, ::owned)
    }
    private val grpc = DesktopDynamicBiliGrpcClient(
        context.repository.ownedHomeCallFactory(context.epoch, ::owned),
        {
            checkRequest()
            listOf("SESSDATA", "bili_jct", "buvid3").mapNotNull { name ->
                context.repository.ownedHomeCookie(name, context.epoch, ::owned)?.let { name to it }
            }.toMap()
        },
        { checkRequest(); context.mid },
        ::accessToken,
        ::owned,
    )
    // The preload gate and the gRPC authorization header read the same token. Without one the
    // original preload fails before any request; nothing here substitutes a web cookie or a result.
    private val personal = DesktopDynamicPersonalFeedApi(grpc::request, ::accessToken)
    // Captured once in the real awaitRead body. Every child/HTTP call still uses this actual
    // executing request Job; it is not a lifecycle owner or a completed UI nav request.
    private fun currentCallerActive() = requestJob.isActive
    private suspend fun <T> read(block: suspend () -> T): T {
        val caller = currentCoroutineContext()
        caller.ensureActive(); checkRequest()
        val result = block()
        caller.ensureActive(); checkRequest()
        return result
    }
    override suspend fun sessions() = read { owner.requests.getSessions(sessionType = 4, size = 20)
        .getOrThrow().session_list.orEmpty() }
    override suspend fun sessionDetail(talkerId: Long, sessionType: Int) = read {
        val response = messages.getSessionDetail(talkerId, sessionType)
        check(response.code == 0) { "Session detail code ${response.code}" }
        checkNotNull(response.data).also {
            check(it.talker_id == talkerId && it.session_type == sessionType) { "会话详情标识不匹配" }
        }
    }
    override suspend fun feedUnread() = read { owner.requests.getFeedUnread().getOrThrow() }
    override suspend fun systemNotices() = read { owner.requests.getSystemNotices().getOrThrow() }
    override suspend fun dynamicUpdateCount(baseline: String) = read {
        val response = dynamic.getDynamicUpdateCount(type = "all", updateBaseline = baseline)
        check(response.code == 0) { "Dynamic update code ${response.code}" }
        checkNotNull(response.data).update_num
    }
    override suspend fun dynamicAuthorUpdates() = read { DesktopDynamicAuthorUpdatesApi(authorUpdates).load() }
    override suspend fun followings(selfMid: Long, page: Int) = read {
        // Only the dynamic category reads the following list, and its per-author preload needs an App token.
        requireDesktopDynamicPreloadAuthorization(accessToken())
        val response = api.getFollowings(vmid = selfMid, pn = page, ps = 50)
        check(response.code == 0) { "Followings code ${response.code}" }
        checkNotNull(response.data)
    }
    override suspend fun personalDynamicFeed(hostUid: Long, offset: String, page: Int) = read {
        withContext(DesktopDynamicGrpcRequestSource(::owned, context::admit)) {
            personal.load(hostUid, offset, page)
        }
    }
    override suspend fun followedLive(page: Int) = read {
        val response = api.getFollowedLive(page = page, pageSize = 10)
        check(response.code == 0) { "Followed live code ${response.code}" }
        checkNotNull(response.data)
    }
}
