package com.bilipai.desktop.ui

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.geometry.Offset
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.android.purebilibili.core.network.SearchApi
import com.android.purebilibili.core.network.grpc.DynamicPersonalFeedPage
import com.android.purebilibili.core.ui.transition.VideoCardTransitionClock
import com.android.purebilibili.data.model.response.FollowedLiveData
import com.android.purebilibili.data.model.response.FollowingsData
import com.android.purebilibili.data.model.response.MessageFeedUnreadData
import com.android.purebilibili.data.model.response.SessionAccountInfo
import com.android.purebilibili.data.model.response.SessionItem
import com.android.purebilibili.data.model.response.SessionMessage
import com.android.purebilibili.data.model.response.SystemNoticeItem
import com.android.purebilibili.feature.message.notification.*
import com.android.purebilibili.navigation.ScreenRoutes
import com.android.purebilibili.navigation3.BiliPaiNavKey
import com.bilipai.desktop.compat.UnsupportedDesktopCapability
import com.bilipai.desktop.data.*
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.JsonPrimitive

/** Upstream's message notification Sync, Scheduler (Worker), Service, Checker and stores running on Windows:
 * the persistent tasks of one window, the switches that start and stop them, and a new private message reaching
 * the Windows presentation. No Windows shell, GPU or network: the presentation and the network source are fakes. */
class DesktopMessageNotificationSchedulingTest {
    // --- The persistent tasks (upstream WorkManager / resident service) on virtual time --------------------------

    private class Clocked {
        val test = TestScope(StandardTestDispatcher())
        val tasks = DesktopMessageNotificationTasks(test, StandardTestDispatcher(test.testScheduler)) { test.testScheduler.currentTime }
        val runs = mutableListOf<Long>()
        fun at(ms: Long) { test.testScheduler.advanceTimeBy(ms - test.testScheduler.currentTime); test.testScheduler.runCurrent() }
    }

    @Test
    fun `the periodic work runs at once, then once every period`() {
        val clock = Clocked()
        clock.tasks.enqueueUniquePeriodicWork("poll", periodMs = 30 * MINUTE, backoffMs = 5 * MINUTE) {
            clock.runs += clock.test.testScheduler.currentTime; true
        }
        clock.at(0)
        assertEquals(listOf(0L), clock.runs)
        clock.at(30 * MINUTE - 1)
        assertEquals(listOf(0L), clock.runs)
        clock.at(90 * MINUTE)
        assertEquals(listOf(0L, 30 * MINUTE, 60 * MINUTE, 90 * MINUTE), clock.runs)
        clock.tasks.close()
    }

    @Test
    fun `a retry waits the linear backoff and a success returns to the period`() {
        val clock = Clocked()
        val answers = ArrayDeque(listOf(false, false, true, true))
        clock.tasks.enqueueUniquePeriodicWork("poll", periodMs = 15 * MINUTE, backoffMs = 5 * MINUTE) {
            clock.runs += clock.test.testScheduler.currentTime; answers.removeFirst()
        }
        clock.at(44 * MINUTE)
        // 0 (retry) -> +5 (retry) -> +10 (success) -> +15 (success)
        assertEquals(listOf(0L, 5 * MINUTE, 15 * MINUTE, 30 * MINUTE), clock.runs)
        clock.tasks.close()
    }

    @Test
    fun `enqueueing the same period keeps the schedule and a new period starts from the last run`() {
        val clock = Clocked()
        fun enqueue(period: Long) = clock.tasks.enqueueUniquePeriodicWork("poll", period, 5 * MINUTE) {
            clock.runs += clock.test.testScheduler.currentTime; true
        }
        enqueue(30 * MINUTE)
        clock.at(10 * MINUTE)
        enqueue(30 * MINUTE) // A settings change that keeps the mode must not trigger an extra check.
        clock.at(29 * MINUTE)
        assertEquals(listOf(0L), clock.runs)
        enqueue(15 * MINUTE) // Last run at 0: the new 15-minute period is already due.
        clock.at(29 * MINUTE)
        assertEquals(listOf(0L, 29 * MINUTE), clock.runs)
        clock.at(44 * MINUTE)
        assertEquals(listOf(0L, 29 * MINUTE, 44 * MINUTE), clock.runs)
        clock.tasks.close()
    }

    @Test
    fun `cancelling the work and closing the window stop every task`() {
        val clock = Clocked()
        clock.tasks.enqueueUniquePeriodicWork("poll", 15 * MINUTE, 5 * MINUTE) { clock.runs += clock.test.testScheduler.currentTime; true }
        var residentStarts = 0
        clock.tasks.startResident { residentStarts++; awaitCancellation() }
        clock.tasks.startResident { residentStarts++; awaitCancellation() } // Already running: kept, not doubled.
        clock.at(0)
        assertEquals(1, residentStarts)
        assertTrue(clock.tasks.isScheduled("poll"))
        assertTrue(clock.tasks.isResidentRunning())
        clock.tasks.cancelUniqueWork("poll")
        clock.at(60 * MINUTE)
        assertEquals(listOf(0L), clock.runs)
        assertFalse(clock.tasks.isScheduled("poll"))
        clock.tasks.enqueueUniquePeriodicWork("poll", 15 * MINUTE, 5 * MINUTE) { clock.runs += clock.test.testScheduler.currentTime; true }
        clock.at(60 * MINUTE)
        clock.tasks.close()
        clock.at(240 * MINUTE)
        assertEquals(listOf(0L, 60 * MINUTE), clock.runs)
        assertFalse(clock.tasks.isScheduled("poll"))
        assertFalse(clock.tasks.isResidentRunning())
    }

    // --- Upstream's files over an actual retained Root -------------------------------------------------------------

    @Test
    fun `upstream settings and state stores write this Root's namespaces of the settings document`() = withRoot { fixture ->
        val context = fixture.context
        assertEquals(MessageNotificationSettings(), MessageNotificationSettingsStore.getSettings(context).first())
        MessageNotificationSettingsStore.setEnabled(context, true)
        MessageNotificationSettingsStore.setMode(context, MessageNotificationMode.MORE_TIMELY)
        assertEquals(JsonPrimitive(true), fixture.store.preferences("message_notification_settings")["enabled"])
        assertEquals(JsonPrimitive("MORE_TIMELY"), fixture.store.preferences("message_notification_settings")["mode"])
        assertEquals(MessageNotificationSettings(enabled = true, mode = MessageNotificationMode.MORE_TIMELY),
            MessageNotificationSettingsStore.getSettings(context).first())

        val state = AccountNotificationState(initialized = setOf("dm"), sessionMsgKeys = mapOf("9_1" to 3L))
        MessageNotificationStateStore.putState(context, 47L, state)
        assertEquals(state, MessageNotificationStateStore.getState(context, 47L))
        // The original 30-second suppression of adjacent checks works on the desktop clock.
        assertFalse(MessageNotificationStateStore.checkedRecently(context, 47L))
        MessageNotificationStateStore.markChecked(context, 47L)
        assertTrue(MessageNotificationStateStore.checkedRecently(context, 47L))
        assertFalse(MessageNotificationStateStore.checkedRecently(context, 48L))
        MessageNotificationStateStore.clearAll(context)
        assertEquals(AccountNotificationState(), MessageNotificationStateStore.getState(context, 47L))
        assertTrue(fixture.store.preferences("message_notification_state").isEmpty())
    }

    @Test
    fun `a retired Root can neither read nor write its notification settings`() = withRoot { fixture ->
        val context = fixture.context
        MessageNotificationSettingsStore.setEnabled(context, true)
        fixture.replaceAccount()
        assertFalse(context.isCurrent())
        assertFailsWith<CancellationException> { MessageNotificationSettingsStore.setEnabled(context, false) }
        assertFailsWith<CancellationException> { MessageNotificationSettingsStore.getSettings(context).first() }
        assertEquals(JsonPrimitive(true), fixture.store.preferences("message_notification_settings")["enabled"])
    }

    @Test
    fun `Sync runs the periodic work while enabled, the resident lane only while it can post, and disabling resets all`() =
        withRoot { fixture ->
            val context = fixture.context
            val tasks = context.tasks
            MessageNotificationSync.sync(context)
            assertFalse(tasks.isScheduled(MessageNotificationScheduler.UNIQUE_WORK_NAME), "Notifications are off by default")
            assertFalse(tasks.isResidentRunning())

            MessageNotificationSettingsStore.setEnabled(context, true)
            MessageNotificationSync.sync(context)
            assertTrue(tasks.isScheduled(MessageNotificationScheduler.UNIQUE_WORK_NAME))
            assertFalse(tasks.isResidentRunning(), "The resident lane is its own switch")

            MessageNotificationSettingsStore.setResidentEnabled(context, true)
            MessageNotificationSync.sync(context)
            assertTrue(tasks.isResidentRunning())

            fixture.presentation.postable = false // No notification area (e.g. the window is gone): stop polling fast.
            MessageNotificationSync.sync(context)
            assertFalse(tasks.isResidentRunning())
            assertTrue(tasks.isScheduled(MessageNotificationScheduler.UNIQUE_WORK_NAME))
            fixture.presentation.postable = true
            MessageNotificationSync.sync(context)
            assertTrue(tasks.isResidentRunning())

            MessageNotificationStateStore.putState(context, 47L, AccountNotificationState(initialized = setOf("dm")))
            MessageNotificationSettingsStore.setEnabled(context, false)
            MessageNotificationSync.sync(context)
            assertFalse(tasks.isScheduled(MessageNotificationScheduler.UNIQUE_WORK_NAME))
            assertFalse(tasks.isResidentRunning())
            // Original contract: off resets every baseline, so enabling again never replays old history.
            assertEquals(AccountNotificationState(), MessageNotificationStateStore.getState(context, 47L))
            assertTrue(fixture.presentation.cleared > 0)

            MessageNotificationSettingsStore.setEnabled(context, true)
            MessageNotificationSync.sync(context)
            assertTrue(tasks.isScheduled(MessageNotificationScheduler.UNIQUE_WORK_NAME))
            context.close() // The client exits: nothing keeps polling.
            assertFalse(tasks.isScheduled(MessageNotificationScheduler.UNIQUE_WORK_NAME))
            assertFalse(tasks.isResidentRunning())
        }

    @Test
    fun `a new private message after the first baseline reaches the Windows presentation with its chat route`() =
        withRoot { fixture ->
            val context = fixture.context
            MessageNotificationSettingsStore.setEnabled(context, true)
            for (off in listOf<suspend (android.content.Context, Boolean) -> Unit>(
                MessageNotificationSettingsStore::setRepliesEnabled, MessageNotificationSettingsStore::setAtMeEnabled,
                MessageNotificationSettingsStore::setLikesEnabled, MessageNotificationSettingsStore::setSystemNoticesEnabled,
                MessageNotificationSettingsStore::setDynamicUpdatesEnabled, MessageNotificationSettingsStore::setLiveAlertsEnabled,
            )) off(context, false)
            fixture.source.sessions = listOf(session(msgKey = 1))

            assertTrue(MessageNotificationWorker(context).doWork() == MessageNotificationWorker.Result.SUCCESS)
            assertTrue(fixture.presentation.posted.isEmpty(), "The first check only records the baseline")
            assertEquals(1, fixture.source.polls)

            fixture.source.sessions = listOf(session(msgKey = 2, unread = 2))
            fixture.forgetLastCheck()
            assertTrue(MessageNotificationWorker(context).doWork() == MessageNotificationWorker.Result.SUCCESS)
            val posted = fixture.presentation.posted.single()
            assertEquals("dm:9_1", posted.key)
            assertEquals("dm", posted.group)
            assertEquals("Synthetic UP", posted.title)
            assertTrue(posted.text.startsWith("[2 条新消息] "), posted.text)
            assertEquals(ScreenRoutes.Chat.createRoute(9L, 1, "Synthetic UP"), posted.route)

            // An adjacent check (the resident lane right after the Worker) is suppressed, as on Android.
            MessageNotificationWorker(context).doWork()
            assertEquals(2, fixture.source.polls)

            // The window's presentation is gone: nothing is checked or posted, and no baseline moves.
            fixture.presentation.postable = false
            fixture.source.sessions = listOf(session(msgKey = 3))
            fixture.forgetLastCheck()
            MessageNotificationWorker(context).doWork()
            assertEquals(2, fixture.source.polls)
            assertEquals(1, fixture.presentation.posted.size)
        }

    @Test
    fun `a retired Root posts nothing and keeps its baseline`() = withRoot { fixture ->
        val context = fixture.context
        MessageNotificationSettingsStore.setEnabled(context, true)
        fixture.source.sessions = listOf(session(msgKey = 1))
        MessageNotificationWorker(context).doWork()
        val baseline = fixture.store.preferences("message_notification_state")
        fixture.source.sessions = listOf(session(msgKey = 2))
        fixture.forgetLastCheck()
        fixture.source.beforeSessions = { fixture.replaceAccount() }
        MessageNotificationWorker(context).doWork()
        assertTrue(fixture.presentation.posted.isEmpty())
        assertEquals(baseline.filterKeys { !it.startsWith("last_check") },
            fixture.store.preferences("message_notification_state").filterKeys { !it.startsWith("last_check") })
    }

    @Test
    fun `the Windows client has no notification process and refuses boot recovery loudly`() = withRoot { fixture ->
        assertFalse(isMessageNotificationProcess(fixture.context))
        assertFalse(isLightweightServiceProcess(fixture.context))
        assertFailsWith<UnsupportedDesktopCapability> { MessageNotificationBootReceiver().onReceive(fixture.context) }
        assertFailsWith<UnsupportedDesktopCapability> { initializeMessageNotificationProcess(fixture.context) }
        val other = object : android.content.Context(Files.createTempDirectory("not-a-window-").toFile()) {
            override fun getSharedPreferences(name: String, mode: Int) = error("unused")
        }
        assertFailsWith<UnsupportedDesktopCapability> { MessageNotificationNotifier.canPost(other) }
    }

    // --- Fixture ---------------------------------------------------------------------------------------------------

    private fun session(msgKey: Long, unread: Int = 1) = SessionItem(talker_id = 9, session_type = 1, unread_count = unread,
        last_msg = SessionMessage(sender_uid = 9, msg_key = msgKey, msg_type = 1, content = """{"content":"hello"}"""),
        account_info = SessionAccountInfo(name = "Synthetic UP"))

    private class FakePresentation : DesktopMessageNotificationPresentation {
        @Volatile var postable = true
        val posted = CopyOnWriteArrayList<PendingMessageNotification>()
        @Volatile var cleared = 0
        override fun canPost() = postable
        override suspend fun activeMessageKeys(): Set<String> = posted.mapTo(linkedSetOf()) { it.key }
        override suspend fun postAll(pending: List<PendingMessageNotification>, cancelled: Set<String>,
            pendingCounts: Map<String, Int>?) { posted += pending }
        override suspend fun clear() { cleared++; posted.clear() }
    }

    private class FakeSource : MessageNotificationSource {
        @Volatile var sessions = emptyList<SessionItem>()
        @Volatile var beforeSessions: () -> Unit = {}
        override suspend fun sessions(): List<SessionItem> { beforeSessions(); return sessions }
        override suspend fun sessionDetail(talkerId: Long, sessionType: Int) = sessions.first { it.talker_id == talkerId }
        override suspend fun feedUnread() = MessageFeedUnreadData()
        override suspend fun systemNotices() = emptyList<SystemNoticeItem>()
        override suspend fun dynamicUpdateCount(baseline: String) = 0
        override suspend fun dynamicAuthorUpdates() = emptyMap<Long, Boolean>()
        override suspend fun followings(selfMid: Long, page: Int) = FollowingsData()
        override suspend fun personalDynamicFeed(hostUid: Long, offset: String, page: Int) = DynamicPersonalFeedPage()
        override suspend fun followedLive(page: Int) = FollowedLiveData()
        @Volatile var polls = 0
    }

    private inner class RootFixture : AutoCloseable {
        val folder = Files.createTempDirectory("message-notification-root-47-")
        val sessions = DesktopSessionStore(folder.resolve("synthetic-session.json"), persistent = false)
        val repository = DesktopRepository(sessions)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = DesktopPluginStore(folder.resolve("global"))
        val plugins = DesktopPluginContext(store)
        val presentation = FakePresentation()
        val source = FakeSource()
        val context: DesktopMessageNotificationContext

        init {
            sessions.saveAccount(mapOf("SESSDATA" to "synthetic-47-A", "bili_jct" to "synthetic-only"),
                AccountSummary(47L, "Notification synthetic fixture", ""))
            val blocked = DesktopBlockedUpStore(plugins)
            val discovery = DesktopDiscoveryPreferences(folder.resolve("discovery"), blocked)
            val preferences = runBlocking { DesktopOriginalHomePreferences.create(store, scope, false, { false }) }
            val analytics = DesktopHomeRootAnalytics(store, null, { true })
            val pages = object : DesktopHomeEmbeddedRetainedOwner, DesktopHomeEmbeddedPages by unused<DesktopHomeEmbeddedPages>() {
                override fun close() = Unit
                override suspend fun closeAndJoin() = Unit
            }
            val entry = DesktopHomeRetainedEntry.create(repository, discovery, plugins, scope,
                repository.sessionEpoch, 47L, { true }, preferences, MutableStateFlow(false), { false },
                { analytics }, DesktopBlockedUpRepository(repository, blocked),
                { error("No authentication transport observation") }, { error("No Home feedback") }, { _, _ -> pages })
            val lifecycle = object : LifecycleOwner {
                override val lifecycle: Lifecycle = LifecycleRegistry.createUnsafe(this).apply {
                    currentState = Lifecycle.State.RESUMED
                }
            }
            val environment = DesktopHomeEnvironment(preferences, plugins, lifecycle,
                MutableStateFlow(false), analytics, { DesktopOriginalHomeGlobalNamespace(store, it) },
                MutableStateFlow(null), { _, _ -> error("No wallpaper request") }, {}, { _, _ -> }, pages,
                { _, _ -> error("No image save") }, { error("No rendering feedback") }, unused<DesktopHomeOverlayPorts>())
            val media = DesktopHomeMediaLifetime(entry.gate.scope, entry.gate::owns, entry.gate::commit) {
                error("No native media request")
            }
            val mediaPorts = DesktopHomeActualMediaPorts(media, { error("No wallpaper MIME request") },
                { _, _, _, _ -> error("No wallpaper rendering") }, MutableStateFlow(false), { error("No media feedback") }).ports
            val returns = DesktopHomeReturnNavigationOwner(entry.gate::owns, entry.gate::commit, { action -> action(); true },
                { Offset.Zero }, { 47L }, VideoCardTransitionClock(), { false }, { false }, { true })
            val root = DesktopHomeRetainedRoot(entry, environment, mediaPorts, returns,
                DesktopLiveNavigationBinding(entry.requests.environment, unused<SearchApi>(), store),
                media, DesktopHomeLottieBinding(repository.httpClient, entry.gate::owns, entry.gate::commit,
                    { error("No animation request") }))
            val stack = mutableStateListOf<BiliPaiNavKey>(BiliPaiNavKey.MainHost)
            val routes = DesktopOriginalRootRouteAssembly(root, stack, DesktopOriginalRootRoutePlatform(
                { action -> action(); true }, { _, _ -> }, { _, _ -> error("No video resolution") }, {},
                { error("Unexpected root Back") }), { setOf("home") })
            context = requireNotNull(DesktopMessageNotificationContext.captureOrNull(repository, store, root, routes))
            context.attach(presentation, object : DesktopMessageNotificationSources {
                override suspend fun <T> poll(context: DesktopMessageNotificationContext,
                    block: suspend (MessageNotificationSource) -> T): T { source.polls++; return block(source) }
            })
        }

        fun replaceAccount() = sessions.saveAccount(mapOf("SESSDATA" to "synthetic-48-B", "bili_jct" to "synthetic-only-B"),
            AccountSummary(48L, "Synthetic replacement", ""))

        /** Time passes: the next check is no longer adjacent to the previous one. */
        suspend fun forgetLastCheck() {
            context.desktopPreferencesDataStore("message_notification_state").edit {
                it.remove(longPreferencesKey("last_check_elapsed_ms"))
            }
        }

        override fun close() {
            context.close()
            scope.cancel()
            folder.toFile().deleteRecursively()
        }
    }

    private fun withRoot(block: suspend (RootFixture) -> Unit) {
        val fixture = RootFixture()
        try { runBlocking { withTimeout(30_000) { block(fixture) } } } finally { fixture.close() }
    }

    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(
        T::class.java.classLoader, arrayOf(T::class.java)
    ) { proxy, method, args ->
        when {
            method.declaringClass == Any::class.java && method.name == "toString" -> "Unused ${T::class.java.simpleName}"
            method.declaringClass == Any::class.java && method.name == "hashCode" -> System.identityHashCode(proxy)
            method.declaringClass == Any::class.java && method.name == "equals" -> proxy === args?.singleOrNull()
            else -> error("Unexpected unrelated ${T::class.java.simpleName}.${method.name}")
        }
    } as T

    private companion object { const val MINUTE = 60_000L }
}
