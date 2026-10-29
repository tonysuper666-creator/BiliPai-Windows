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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import java.awt.Frame
import java.awt.Window
import java.io.IOException
import java.util.logging.Level
import java.util.logging.Logger

internal val LocalDesktopMessageNotificationContext =
    staticCompositionLocalOf<DesktopMessageNotificationContext?> { null }

/** Windows scheduling adapter for the unchanged original Poller/Policy. One actual retained
 * Root lifetime; no background Android service or promise of polling after this window exits. */
@Composable
internal fun DesktopMessageNotificationRootConsumer(context: DesktopMessageNotificationContext?,
    community: DesktopCommunityRepository, window: Window) {
    val port = remember(context, window) { context?.let { DesktopWindowsMessageNotifications(it, window) } }
    // A new port for a new account retires the previous one; the window going away only detaches it.
    val previousPort = remember(window) { java.util.concurrent.atomic.AtomicReference<DesktopWindowsMessageNotifications?>() }
    SideEffect {
        val before = previousPort.getAndSet(port)
        if (before != null && before !== port) before.retireForAccountSwitch()
    }
    DisposableEffect(context, port) { onDispose { context?.close(); port?.close() } }
    LaunchedEffect(context, community, port) {
        val actual = context ?: return@LaunchedEffect
        val notifications = port ?: return@LaunchedEffect
        MessageNotificationSettingsStore.getSettings(actual).distinctUntilChanged().collectLatest { settings ->
            actual.check()
            if (!settings.enabled) {
                // Original Sync contract: turning notifications off resets every per-account
                // baseline under the SAME mutex. Enabling again never replays old history.
                try { messageNotificationMutex.withLock { MessageNotificationStateStore.clearAll(actual) } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { Logger.getLogger("MessageNotification").log(Level.WARNING, "Notification reset failed", failure) }
                notifications.clear()
                return@collectLatest
            }
            val mid = actual.mid ?: return@collectLatest
            if (!actual.hasAuthenticatedSession()) return@collectLatest
            if (!notifications.canPost()) return@collectLatest
            if (settings.notifyDynamicUpdates) {
                // Token-dependent path: without an App access token the original preload refuses before
                // any request. Say so once per settings session instead of failing silently every cycle.
                desktopDynamicPreloadBlockedReason(actual.appAccessToken())?.let { reason ->
                    Logger.getLogger("MessageNotification").log(Level.INFO,
                        "Dynamic update notifications are inactive for this account: $reason")
                }
            }
            val admission = DesktopMessagePageAdmission(actual.repository, actual.epoch, mid,
                actual.root.entry.gate.scope, actual::isCurrent, actual::isCurrent,
                actual::admit,
                { error("Notification snapshots do not issue user-profile reads") },
                { error("Notification snapshots do not issue video-detail reads") })
            try {
                admission.requests = community.originalMessagePages(admission).requests
                while (currentCoroutineContext().isActive) {
                    actual.check()
                    val activeKeys = notifications.activeMessageKeys()
                    var batch = emptyList<PendingMessageNotification>()
                    var cancelledKeys = emptySet<String>()
                    var pendingCounts: Map<String, Int>? = null
                    var retry = false
                    try {
                    messageNotificationMutex.withLock {
                        val before = MessageNotificationSettingsStore.getSettings(actual).first()
                        if (before != settings || !before.enabled || !actual.hasAuthenticatedSession() || !notifications.canPost()) return@withLock
                        var result: MessageNotificationCheckResult? = null
                        admission.awaitRead("message-notification-check") {
                            val caller = currentCoroutineContext()
                            fun sameSession() { caller.ensureActive(); actual.check(); admission.isOwned().also {
                                if (!it) throw CancellationException("Message notification request retired")
                            } }
                            val source = DesktopOwnedMessageNotificationSource(actual, admission,
                                requireNotNull(caller[Job]), ::sameSession)
                            result = MessageNotificationPoller(source, ::sameSession).check(settings,
                                MessageNotificationStateStore.getState(actual, mid), mid, activeKeys)
                        }
                        currentCoroutineContext().ensureActive(); actual.check()
                        val value = requireNotNull(result)
                        retry = value.shouldRetry
                        if (MessageNotificationSettingsStore.getSettings(actual).first() == settings && notifications.canPost()) {
                            MessageNotificationStateStore.putState(actual, mid, value.state)
                            batch = value.notifications
                            cancelledKeys = value.cancelNotificationKeys
                            pendingCounts = desktopPendingSummaryCounts(value.state)
                        }
                    }
                    // Shell/OS delivery is outside Store/entry and state mutex. The actual
                    // settings coroutine and fixed same-account receipt are checked on EDT.
                    currentCoroutineContext().ensureActive(); actual.check()
                    if (MessageNotificationSettingsStore.getSettings(actual).first() == settings)
                        notifications.postAll(batch, cancelledKeys, pendingCounts)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: IOException) { retry = true }
                    catch (failure: Exception) {
                        // Original Worker boundary logs non-IO failures without throwing through
                        // the UI Root. Preserve the Poller's category-local rollback above.
                        Logger.getLogger("MessageNotification").log(Level.WARNING, "Background check failed", failure)
                    }
                    val delayMs = if (settings.residentEnabled) {
                        val visible = withContext(Dispatchers.Main) {
                            window.isDisplayable && window.isVisible &&
                                (window !is Frame || window.extendedState and Frame.ICONIFIED == 0)
                        }
                        // Window visibility is this platform's fast-poll condition. Charging
                        // is unknown here, never guessed from an Android or GPU capability.
                        resolveResidentPollDelayMs(settings.mode, visible, charging = false)
                    } else if (retry) 5 * 60_000L // Original WorkManager's initial linear retry backoff.
                    else resolveMessageNotificationTiming(settings.mode).periodicMinutes * 60_000L
                    delay(delayMs)
                }
            } finally {
                withContext(NonCancellable) { admission.retireAndJoin() }
            }
        }
    }
}

/** Exactly the original read-only source mappings, replacing Android singleton services by
 * the existing same-Root request admission and Repository's original Call.Factory. */
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
