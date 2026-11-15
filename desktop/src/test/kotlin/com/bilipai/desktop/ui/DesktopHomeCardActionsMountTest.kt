@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import com.android.purebilibili.core.events.BrandSuccessEvents
import com.android.purebilibili.feature.video.player.PlaylistSession
import com.android.purebilibili.feature.video.player.PlaylistItem
import com.android.purebilibili.feature.video.player.ExternalPlaylistSource
import com.android.purebilibili.data.model.response.ViewInfo
import com.android.purebilibili.data.model.response.RelatedVideo
import com.android.purebilibili.navigation3.BiliPaiProgrammaticBackDispatcher
import com.bilipai.desktop.appearance.*
import com.bilipai.desktop.data.*
import com.bilipai.desktop.plugins.asAndroidContext
import java.awt.EventQueue
import java.util.concurrent.FutureTask
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlin.test.*

/** Production pages -> actual RootStack/MainHost -> retained Home -> generated card -> cloud drawer.
 * The physical ReadyRootMount/Windows entry remains outside this headless test. */
class DesktopHomeCardActionsMountTest {
    /** Invoke the generated VM with its real Root environment/binding, without network timing. */
    private fun refreshQueue(video: DesktopHomeCardRootFixture.RootVideo, bvid: String, cid: Long) {
        val vm = video.assembly.playback
        val update = vm.javaClass.declaredMethods.single { it.name == "updatePlaylist" && it.parameterCount == 3 }
        update.isAccessible = true
        update.invoke(vm, ViewInfo(bvid = bvid, cid = cid), emptyList<RelatedVideo>(), null)
    }

    @Test fun generatedRefreshPreservesMatchingOrdinarySessionAndUpdatesQueue() = onEdt {
        DesktopHomeCardRootFixture().use { fixture ->
            val video = fixture.installVideo()
            val queue = fixture.shell.playlist
            queue.setPlaylist(listOf(PlaylistItem("BV1xx411c7mD", 71L, "old title", "", "")), 0)
            val session = queue.captureSession()
            refreshQueue(video, "BV1xx411c7mD", 71L)
            assertTrue(queue.isSessionCurrent(session))
            assertEquals("", queue.getCurrentItem()?.title, "The generated update must actually replace the queue")
            assertEquals(71L, queue.getCurrentItem()?.cid)
        }
    }

    @Test fun generatedRefreshReplacesSessionForDifferentOrUnresolvedCid() = onEdt {
        DesktopHomeCardRootFixture().use { fixture ->
            val video = fixture.installVideo()
            val queue = fixture.shell.playlist
            for ((queuedCid, loadedCid) in listOf(72L to 71L, 71L to 0L, 0L to 71L)) {
                queue.setPlaylist(listOf(PlaylistItem("BV1xx411c7mD", queuedCid, "", "", "")), 0)
                val session = queue.captureSession()
                refreshQueue(video, "BV1xx411c7mD", loadedCid)
                assertFalse(queue.isSessionCurrent(session), "cid $queuedCid -> $loadedCid must retire the session")
            }
        }
    }

    @Test fun generatedRefreshKeepsMatchingExternalQueueAndResetsOutsideIt() = onEdt {
        DesktopHomeCardRootFixture().use { fixture ->
            val video = fixture.installVideo()
            val queue = fixture.shell.playlist
            val a = PlaylistItem("BV1xx411c7mD", 71L, "", "", "")
            val external = queue.setExternalPlaylist(listOf(a), 0, ExternalPlaylistSource.FAVORITE)
            assertEquals(ExternalPlaylistSource.FAVORITE, queue.externalPlaylistSource.value)
            refreshQueue(video, a.bvid, a.cid)
            assertTrue(queue.isSessionCurrent(external), "Existing matching external queue returns before the edited branch")
            assertTrue(queue.isExternalPlaylist.value)
            refreshQueue(video, "BV1xx411c7mE", 71L)
            assertFalse(queue.isSessionCurrent(external), "A different video outside the external queue resets it")
            assertEquals(ExternalPlaylistSource.NONE, queue.externalPlaylistSource.value)
        }
    }

    @Test fun ordinaryHomeAToBWithdrawsActualRootMiniRequest() = onEdt {
        DesktopHomeCardRootFixture().use { fixture ->
            fixture.installVideo()
            val card = VideoCard("BV1xx411c7mD", "A", "", "", 0, 60, preferredCid = 71L)
            val playback = DesktopHomeCardRootMiniPlayback(fixture.shell) { checkNotNull(fixture.pages.homeCardActions) }
            val intent = DesktopHomeCardMiniIntent(DesktopHomeCardTarget(card.bvid, 11L, 71L, card), playback)
            val waiting = assertIs<DesktopHomeCardMiniResult.Waiting<PlaylistSession>>(
                intent.start(checkNotNull(fixture.pages.homeCardActions).navigationAdmission))
            fixture.shell.playback.openVideoDetail(card.copy(bvid = "BV1xx411c7mE"), 0L, false)
            assertFalse(fixture.shell.playlist.isSessionCurrent(waiting.after))
            assertIs<DesktopHomeCardMiniResult.Withdrawn>(intent.check(waiting))
            Unit
        }
    }

    @Test fun originalPlayNextCannotEnterMiniForPreviousVideo() = onEdt {
        DesktopHomeCardRootFixture().use { fixture ->
            val video = fixture.installVideo()
            val card = VideoCard("BV1xx411c7mD", "A", "", "", 0, 60, preferredCid = 71L)
            val playback = DesktopHomeCardRootMiniPlayback(fixture.shell) { checkNotNull(fixture.pages.homeCardActions) }
            val intent = DesktopHomeCardMiniIntent(DesktopHomeCardTarget(card.bvid, 11L, 71L, card), playback)
            val waiting = assertIs<DesktopHomeCardMiniResult.Waiting<PlaylistSession>>(
                intent.start(checkNotNull(fixture.pages.homeCardActions).navigationAdmission))
            DesktopHomeCardRootFixture.awaitEdt {
                video.factory.sourceVersions(video.assembly).first { it != null && it > 0L }
            }
            assertTrue(sourcePublished(video))
            val queue = fixture.shell.playlist
            assertTrue(queue.isSessionCurrent(waiting.after))
            assertTrue(queue.replaceQueueIfCurrent(listOf(
                PlaylistItem(card.bvid, 71L, "A", "", ""),
                PlaylistItem("BV1xx411c7mE", 71L, "B", "", "")), 0, waiting.after))
            assertTrue(video.assembly.playback.playNextRecommended())
            assertEquals("BV1xx411c7mE", queue.getCurrentItem()?.bvid)
            assertTrue(queue.isSessionCurrent(waiting.after), "Original playNext does not advance the generation")
            val result = intent.check(waiting)
            assertFalse(result is DesktopHomeCardMiniResult.Entered, "A must not enter after playNext selected B")
            assertEquals(0, video.entries.size)
        }
    }

    @Test fun generatedHomeCardMiniUsesActualShellFactoryRequestAndHolder() = verifyRootMiniSourceReplacement(false)

    @Test fun generatedHomeCardMiniRejectsReplacedSourceAfterActualNavigation() = verifyRootMiniSourceReplacement(true)

    private fun verifyRootMiniSourceReplacement(navigateToB: Boolean) = onEdt {
        DesktopHomeCardRootFixture().use { fixture ->
            val video = fixture.installVideo()
            Scene(fixture).use { scene ->
                // Hold the related-video response so its setPlaylist cannot race the request unseen.
                fixture.transport.hold("/archive/related")
                val marks = mutableListOf<String>()
                fun mark(name: String) { marks += miniTrace(name, fixture, video, null) }
                try {
                    scene.awaitHome()
                    scene.click("⋮")
                    scene.await("Root mini action") { scene.text("小窗播放") != null }
                    val before = runCatching { fixture.shell.playlist.captureSession() }.getOrNull()
                    mark("before-click")
                    scene.click("小窗播放")
                    val request = runCatching { fixture.shell.playlist.captureSession() }.getOrNull()
                    marks += miniTrace("request", fixture, video, request)
                    scene.await("playback or session change while related is held", { marks.joinToString(" || ") }) {
                        val now = miniTrace("held", fixture, video, request)
                        if (marks.lastOrNull()?.substringAfter(" ") != now.substringAfter(" ")) marks += now
                        video.entries.isNotEmpty() || requestRetired(fixture, request) ||
                            (fixture.transport.arrived("/archive/related") && sourcePublished(video))
                    }
                    marks += miniTrace("held-terminal", fixture, video, request)
                    if (video.entries.isEmpty()) {
                        fixture.transport.release("/archive/related")
                        scene.await("related response applied", { marks.joinToString(" || ") }) {
                            val now = miniTrace("after-release", fixture, video, request)
                            if (marks.lastOrNull()?.substringAfter(" ") != now.substringAfter(" ")) marks += now
                            fixture.transport.responded("/archive/related") || fixture.transport.blockedOnEdt("/archive/related") ||
                                video.entries.isNotEmpty()
                        }
                        marks += miniTrace("after-related", fixture, video, request)
                    }
                    println(marks.joinToString(" || "))
                    assertEquals(1, video.entries.size, buildString {
                        append("real accepted source enters Holder Mini. ")
                        append("outcome=${miniOutcome(fixture, video, request)} ")
                        append("before=$before request=$request. ")
                        append(marks.joinToString(" || "))
                    })
                } finally { fixture.transport.release("/archive/related") }
                val (assembly, accepted) = video.entries.single()
                assertSame(video.assembly, assembly)
                assertSame(assembly, fixture.shell.slot.currentAssembly())
                assertSame(video.factory, fixture.shell.factoryFor(assembly))
                assertSame(accepted, assembly.native.current())
                assertTrue(video.factory.isPresentationCurrent(assembly, accepted))
                assertEquals("BV1xx411c7mD", accepted.request.bvid)
                assertEquals(71L, accepted.request.cid)
                val receipt = assertNotNull(accepted.nativeSource.source.authorizationReceipt)
                assertEquals(fixture.repository.sessionEpoch, receipt.accountEpoch)
                assertEquals(accepted.sourceVersion, video.player.currentSourceVersion)
                assertSame(assembly.section, video.holderMini.player)
                assertTrue(assembly.playback.uiState.value === video.retained)
                assertTrue(fixture.calls.any { it.contains("/playurl") })
                val playRequests = fixture.calls.count { it.contains("/playurl") }
                video.closeMiniWindow() // Physical-window IO only; keep the actual source and playlist.
                scene.click("⋮")
                scene.await("same-source mini action") { scene.text("小窗播放") != null }
                scene.click("小窗播放")
                scene.await("same accepted source reused by Holder Mini") { video.entries.size == 2 }
                assertSame(accepted, video.entries.last().second)
                assertEquals(playRequests, fixture.calls.count { it.contains("/playurl") })
                assertEquals(accepted.sourceVersion, video.player.currentSourceVersion)
                // Capture a real source-bound request while its caller Job remains alive, then replace
                // the source through the ordinary facade (no manufactured receipt or publication).
                DesktopHomeCardRootFixture.awaitEdt {
                    val request = video.factory.captureSourceRequest(assembly, accepted) { assembly.owns() }
                    request.assertCurrent()
                    val homeCard = discoveryVideoCard(fixture.root.entry.viewModel.uiState.value.videos.single())
                    val card = if (navigateToB) homeCard.copy(bvid = "BV1xx411c7mE", preferredCid = 71L) else homeCard
                    val beforeOpenSession = fixture.shell.playlist.captureSession()
                    fixture.shell.playback.openVideoDetail(card, 0L, keepMatchingSource = false)
                    if (navigateToB) {
                        video.factory.sourceVersions(assembly).first { it != null && it > accepted.sourceVersion }
                        val replacement = assertNotNull(assembly.native.current())
                        assertNotSame(accepted, replacement)
                        assertEquals(card.bvid, replacement.request.bvid)
                        assertEquals(card.preferredCid, replacement.request.cid)
                        assertTrue(video.factory.isPresentationCurrent(assembly, replacement))
                        assertFailsWith<CancellationException> { request.assertCurrent() }
                        assertFalse(video.factory.withPresentationAdmission(assembly, accepted) {
                            fail("Replaced source admitted to Mini")
                        })
                        assertEquals(2, video.entries.size, "Replacement alone must not enter Mini")
                    } else {
                        // openQueue adopts the queue and synchronously calls loadVideo for this
                        // resolved CID; return means its keepLoadedUi decision has been reached.
                        assertFalse(fixture.shell.playlist.isSessionCurrent(beforeOpenSession))
                        assembly.playback.uiState.first { state ->
                            state is com.android.purebilibili.feature.video.viewmodel.VideoPlaybackUiState.Success &&
                                state.info.bvid == card.bvid && state.info.cid == card.preferredCid
                        }
                        assertEquals(accepted.sourceVersion, video.player.currentSourceVersion)
                        assertSame(accepted, assembly.native.current())
                        assertEquals(playRequests, fixture.calls.count { it.contains("/playurl") })
                        assertTrue(video.factory.isPresentationCurrent(assembly, accepted))
                        assertEquals(2, video.entries.size, "Reopening alone must not enter Mini")
                        // Source request ownership follows the accepted publication and account,
                        // not the playlist generation; a same-source open keeps it current.
                        request.assertCurrent()
                    }
                }
                fixture.shell.retire()
                assertFalse(video.factory.withPresentationAdmission(assembly, accepted) { fail("Retired source admitted") })
            }
        }
    }

    /** Related response is released only after the source is up, and the compose waiter
     * is not scheduled until that response has been applied. The success assertion stays. */
    @Test fun generatedHomeCardMiniWithdrawsWhenRelatedRefreshReplacesSession() = onEdt {
        DesktopHomeCardRootFixture().use { fixture ->
            val video = fixture.installVideo()
            Scene(fixture).use { scene ->
                fixture.transport.hold("/archive/related")
                val marks = mutableListOf<String>()
                var request: PlaylistSession? = null
                try {
                    scene.awaitHome()
                    scene.click("⋮")
                    scene.await("Root mini action") { scene.text("小窗播放") != null }
                    val before = runCatching { fixture.shell.playlist.captureSession() }.getOrNull()
                    marks += miniTrace("before-click", fixture, video, null)
                    scene.click("小窗播放", schedule = false) {
                        request = runCatching { fixture.shell.playlist.captureSession() }.getOrNull()
                    }
                    marks += miniTrace("request", fixture, video, request)
                    val sourceReady = scene.pumpFor("source while related held") {
                        sourcePublished(video) || requestRetired(fixture, request)
                    }
                    marks += miniTrace("after-source", fixture, video, request)
                    fixture.transport.release("/archive/related")
                    val relatedApplied = scene.pumpFor("related response") {
                        fixture.transport.responded("/archive/related") || fixture.transport.blockedOnEdt("/archive/related")
                    }
                    val retired = scene.pumpFor("session after related") { requestRetired(fixture, request) }
                    marks += miniTrace("after-related", fixture, video, request)
                    scene.await("waiter sees the controlled order", { marks.joinToString(" || ") }) {
                        video.entries.isNotEmpty() || requestRetired(fixture, request)
                    }
                    marks += miniTrace("after-waiter", fixture, video, request)
                    println(marks.joinToString(" || "))
                    assertEquals(1, video.entries.size, buildString {
                        append("real accepted source enters Holder Mini. ")
                        append("outcome=${miniOutcome(fixture, video, request)} ")
                        append("before=$before request=$request sourceReady=$sourceReady ")
                        append("relatedApplied=$relatedApplied sessionRetired=$retired. ")
                        append(marks.joinToString(" || "))
                    })
                } finally { fixture.transport.release("/archive/related") }
            }
        }
    }

    @Test fun generatedHomeCardMenuUsesProductionPagesAndRetainedRoot() = onEdt {
        DesktopHomeCardRootFixture().use { fixture ->
            Scene(fixture).use { scene ->
                scene.awaitHome()
                val cover = scene.nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == "home_video_cover" }
                assertNotNull(cover, "The generated Home card must expose its actual cover")
                scene.click("⋮")
                scene.await("收藏 and 小窗播放 from Root services") {
                    scene.text("收藏") != null && scene.text("小窗播放") != null
                }
                scene.click("收藏")
                scene.await("original cloud folder drawer") { scene.text("Root synthetic folder") != null }
                scene.click("Root synthetic folder")
                scene.click("保存")
                scene.await("actual cloud favorite write") { fixture.calls.any { it.contains("/resource/deal") } }
                val writes = fixture.calls.filter { it.contains("/resource/deal") }
                assertEquals(1, writes.size)
                assertTrue(writes.single().contains("rid=11"), writes.toString())
                assertTrue(writes.single().contains("add_media_ids=9"), writes.toString())
                assertTrue(writes.single().contains("csrf=synthetic-csrf"), writes.toString())
                assertSame(fixture.root, fixture.retainer.current())
                fixture.retainer.retire()
                assertNull(fixture.retainer.current(), "The fixture uses the real retainer ownership gate")
            }
        }
    }

    private class Scene(private val root: DesktopHomeCardRootFixture) : AutoCloseable {
        private val scheduler = TestCoroutineScheduler()
        private val errors = mutableListOf<Throwable>()
        private val owner = root.repository.upstreamOwner(DesktopUpstreamPurpose.Main, { root.alive })
        private val busScope = CoroutineScope(SupervisorJob())
        private val bus = BrandSuccessEvents { busScope.isActive }
        private var time = 0L
        private val scene = DesktopUpstreamCalls.withOwner(owner) {
            ImageComposeScene(1200, 900, Density(1f), coroutineContext = StandardTestDispatcher(scheduler) +
                DesktopUpstreamScope(owner) + CoroutineExceptionHandler { _, error -> errors += error }) {
                DesktopAppearanceTheme(DesktopThemeSettings(hapticFeedbackEnabled = false),
                    systemLanguageTags = listOf("zh-CN"), windowSmallestWidthDp = 900) {
                    val original = LocalWindowInfo.current
                    val window = remember(original) { object : WindowInfo by original { override val isWindowFocused = true } }
                    val ux = remember { DesktopWindowsUxMemory(DesktopWindowsUxSettings(cardInteractions = DesktopWindowsUxCardInteractions.ON)) }
                    CompositionLocalProvider(LocalWindowInfo provides window, LocalDesktopWindowsUx provides ux,
                        LocalDesktopOriginalVideoShellOwner provides root.shell,
                        LocalDesktopUpstreamAndroidContext provides root.runtime.context.asAndroidContext(),
                        LocalDesktopBrandSuccessEvents provides bus) {
                        val nav = root.navigation
                        val environment = remember { DesktopNavigationHostEnvironment(root.runtime.context,
                            nav, nav, nav, nav, { null }, { root.routes.owns() }) }
                        DesktopOriginalRootStack(root.routes, environment, root.pages, true, true, 0,
                            remember { BiliPaiProgrammaticBackDispatcher() }, false, { false }, {}, Modifier,
                            root.chrome, {}, rememberSaveableStateHolder(), leafContent = { _, _, _, _ ->
                                error("This test must remain in the real Home route")
                            })
                    }
                }
            }
        }
        private fun frame(schedule: Boolean = true) {
            DesktopHomeCardRootFixture.pumpEdt()
            if (schedule) {
                Snapshot.sendApplyNotifications(); scheduler.runCurrent(); time += 30_000_000
                DesktopUpstreamCalls.withOwner(owner) { scene.render(time).close() }
                scheduler.runCurrent()
            }
            DesktopHomeCardRootFixture.pumpEdt()
            errors.firstOrNull()?.let { throw AssertionError("Real Root Home composition failed", it) }
        }
        fun await(label: String, note: () -> String = { "" }, condition: () -> Boolean) {
            val deadline = System.nanoTime() + 10_000_000_000L
            while (true) {
                frame()
                if (condition()) return
                val extra = note()
                assertTrue(System.nanoTime() < deadline, "$label: ${nodes().mapNotNull(::textOf)}; feedback=${root.feedback}; video=${root.video?.let { "state=${it.assembly.playback.uiState.value},accepted=${it.assembly.native.current()},version=${it.player.currentSourceVersion},owns=${it.assembly.owns()},ready=${root.shell.navigationReady()}" }}; calls=${root.calls}${if (extra.isEmpty()) "" else "; trace=$extra"}")
                Thread.sleep(10)
            }
        }
        fun awaitHome() = await("settled real generated Home card") {
            val model = root.root.entry.viewModel
            text(DesktopHomeCardRootFixture.TITLE) != null &&
                !model.uiState.value.isLoading && !model.isRefreshing.value
        }
        private fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
        fun nodes() = scene.semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }
        private fun textOf(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
        fun text(value: String) = nodes().firstOrNull { textOf(it) == value }
        fun click(value: String, schedule: Boolean = true, afterAction: () -> Unit = {}) {
            val node = assertNotNull(text(value), "Missing $value")
            var current: SemanticsNode? = node
            while (current != null) {
                val click = current.config.getOrNull(SemanticsActions.OnClick)?.action
                if (click != null) { assertTrue(click()); afterAction(); frame(schedule); return }
                current = current.parent
            }
            error("No actual click action for $value")
        }
        /** EDT pumps only. The compose waiter stays unscheduled until [frame]. */
        fun pumpFor(label: String, condition: () -> Boolean): Boolean {
            val deadline = System.nanoTime() + 10_000_000_000L
            while (!condition()) {
                DesktopHomeCardRootFixture.pumpEdt()
                if (System.nanoTime() >= deadline) return false
                Thread.sleep(10)
            }
            return true
        }
        override fun close() { DesktopUpstreamCalls.withOwner(owner) { scene.close() }; busScope.cancel() }
    }

    /** Facts the production mini check uses. Withdrawn is that check's only session-retirement result. */
    private fun miniTrace(mark: String, fixture: DesktopHomeCardRootFixture,
        video: DesktopHomeCardRootFixture.RootVideo, request: PlaylistSession?): String {
        val session = runCatching { fixture.shell.playlist.captureSession() }.getOrNull()
        val current = if (request == null) null else runCatching { fixture.shell.playlist.isSessionCurrent(request) }.getOrNull()
        val load = runCatching { video.assembly.captureLoadState() }.getOrNull()
        val native = runCatching { video.assembly.native.current() }.getOrNull()
        val item = runCatching { fixture.shell.playlist.getCurrentItem() }.getOrNull()
        val ui = runCatching { video.assembly.playback.uiState.value::class.simpleName }.getOrNull()
        return "$mark session=$session requestCurrent=$current loadToken=${load?.currentLoadRequestToken} " +
            "bvid=${load?.currentBvid} cid=${load?.currentCid} item=${item?.bvid}/${item?.cid} " +
            "sourceVersion=${native?.sourceVersion ?: runCatching { video.player.currentSourceVersion }.getOrNull()} " +
            "assembly=${System.identityHashCode(video.assembly)} ui=$ui entries=${video.entries.size} " +
            "relatedArrived=${fixture.transport.arrived("/archive/related")} " +
            "relatedResponded=${fixture.transport.responded("/archive/related")} " +
            "relatedOnEdt=${fixture.transport.blockedOnEdt("/archive/related")} feedback=${fixture.feedback.toList()} " +
            "outcome=${miniOutcome(fixture, video, request)}"
    }

    private fun requestRetired(fixture: DesktopHomeCardRootFixture, request: PlaylistSession?) =
        request != null && runCatching { fixture.shell.playlist.isSessionCurrent(request) }.getOrDefault(false).not()

    private fun sourcePublished(video: DesktopHomeCardRootFixture.RootVideo): Boolean {
        val native = runCatching { video.assembly.native.current() }.getOrNull() ?: return false
        val ui = runCatching { video.assembly.playback.uiState.value::class.simpleName }.getOrNull()
        return ui == "Success" && native.sourceVersion > 0L
    }

    private fun miniOutcome(fixture: DesktopHomeCardRootFixture, video: DesktopHomeCardRootFixture.RootVideo,
        request: PlaylistSession?): String = when {
        video.entries.isNotEmpty() -> "Entered"
        fixture.feedback.isNotEmpty() -> "Rejected"
        requestRetired(fixture, request) && sourcePublished(video) -> "Withdrawn"
        else -> "Waiting"
    }

    private fun <T> onEdt(block: () -> T): T {
        val task = FutureTask(block)
        EventQueue.invokeAndWait(task)
        return task.get()
    }
}
