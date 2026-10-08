package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.core.network.DynamicApi
import com.android.purebilibili.feature.message.notification.*
import com.bilipai.desktop.data.DesktopCommunityRepository
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
            val admission = DesktopMessagePageAdmission(actual.repository, actual.epoch, mid,
                actual.root.entry.gate.scope, actual::isCurrent, actual::isCurrent,
                actual::admit,
                { error("Notification snapshots do not issue user-profile reads") },
                { error("Notification snapshots do not issue video-detail reads") })
            try {
                admission.requests = community.originalMessagePages(admission).requests
                while (currentCoroutineContext().isActive) {
                    actual.check()
                    var batch = emptyList<PendingMessageNotification>()
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
                                MessageNotificationStateStore.getState(actual, mid), mid)
                        }
                        currentCoroutineContext().ensureActive(); actual.check()
                        val value = requireNotNull(result)
                        retry = value.shouldRetry
                        if (MessageNotificationSettingsStore.getSettings(actual).first() == settings && notifications.canPost()) {
                            MessageNotificationStateStore.putState(actual, mid, value.state)
                            batch = value.notifications
                        }
                    }
                    // Shell/OS delivery is outside Store/entry and state mutex. The actual
                    // settings coroutine and fixed same-account receipt are checked on EDT.
                    currentCoroutineContext().ensureActive(); actual.check()
                    if (MessageNotificationSettingsStore.getSettings(actual).first() == settings)
                        notifications.postAll(batch)
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
    context: DesktopMessageNotificationContext,
    private val owner: DesktopMessagePageAdmission,
    private val requestJob: Job,
    private val checkRequest: () -> Unit,
) : MessageNotificationSource {
    private val dynamic = context.repository.ownedHomeService(DynamicApi::class.java,
        "https://api.bilibili.com/", context.epoch, { context.isCurrent() && owner.isOwned() && currentCallerActive() })
    private val api = context.repository.ownedHomeService(BilibiliApi::class.java,
        "https://api.bilibili.com/", context.epoch, { context.isCurrent() && owner.isOwned() && currentCallerActive() })
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
    override suspend fun replyIds() = read { owner.requests.getReplyFeed().getOrThrow().items.orEmpty().map { it.id } }
    override suspend fun atIds() = read { owner.requests.getAtFeed().getOrThrow().items.orEmpty().map { it.id } }
    override suspend fun likeIds() = read {
        val data = owner.requests.getLikeFeed().getOrThrow()
        (data.latest?.items.orEmpty() + data.total?.items.orEmpty()).map { it.id }.distinct()
    }
    override suspend fun systemNotices() = read { owner.requests.getSystemNotices().getOrThrow() }
    override suspend fun dynamicUpdateCount(baseline: String) = read {
        val response = dynamic.getDynamicUpdateCount(type = "all", updateBaseline = baseline)
        check(response.code == 0) { "Dynamic update code ${response.code}" }
        checkNotNull(response.data).update_num
    }
    override suspend fun dynamicFeed(baseline: String, offset: String) = read {
        val response = dynamic.getDynamicFeed(type = "all", offset = offset, updateBaseline = baseline)
        check(response.code == 0) { "Dynamic feed code ${response.code}" }
        checkNotNull(response.data)
    }
    override suspend fun followedLive(page: Int) = read {
        val response = api.getFollowedLive(page = page, pageSize = 10)
        check(response.code == 0) { "Followed live code ${response.code}" }
        checkNotNull(response.data)
    }
}
