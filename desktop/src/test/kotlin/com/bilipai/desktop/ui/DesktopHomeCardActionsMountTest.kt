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
    @Test fun generatedHomeCardMiniUsesActualShellFactoryRequestAndHolder() = onEdt {
        DesktopHomeCardRootFixture().use { fixture ->
            val video = fixture.installVideo()
            Scene(fixture).use { scene ->
                scene.awaitHome()
                scene.click("⋮")
                scene.await("Root mini action") { scene.text("小窗播放") != null }
                scene.click("小窗播放")
                scene.await("real accepted source enters Holder Mini") { video.entries.isNotEmpty() }
                assertEquals(1, video.entries.size)
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
                    val card = fixture.root.entry.viewModel.uiState.value.videos.single()
                    fixture.shell.playback.openVideoDetail(discoveryVideoCard(card), 0L, keepMatchingSource = false)
                    video.factory.sourceVersions(assembly).first { it != null && it > accepted.sourceVersion }
                    val replacement = assertNotNull(assembly.native.current())
                    assertNotSame(accepted, replacement)
                    assertTrue(video.factory.isPresentationCurrent(assembly, replacement))
                    assertFailsWith<CancellationException> { request.assertCurrent() }
                    assertFalse(video.factory.withPresentationAdmission(assembly, accepted) {
                        fail("Replaced source admitted to Mini")
                    })
                    assertEquals(2, video.entries.size, "Replacement alone must not enter Mini")
                }
                fixture.shell.retire()
                assertFalse(video.factory.withPresentationAdmission(assembly, accepted) { fail("Retired source admitted") })
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
        private fun frame() {
            DesktopHomeCardRootFixture.pumpEdt()
            Snapshot.sendApplyNotifications(); scheduler.runCurrent(); time += 30_000_000
            DesktopUpstreamCalls.withOwner(owner) { scene.render(time).close() }
            scheduler.runCurrent()
            DesktopHomeCardRootFixture.pumpEdt()
            errors.firstOrNull()?.let { throw AssertionError("Real Root Home composition failed", it) }
        }
        fun await(label: String, condition: () -> Boolean) {
            val deadline = System.nanoTime() + 10_000_000_000L
            while (true) {
                frame()
                if (condition()) return
                assertTrue(System.nanoTime() < deadline, "$label: ${nodes().mapNotNull(::textOf)}; feedback=${root.feedback}; video=${root.video?.let { "state=${it.assembly.playback.uiState.value},accepted=${it.assembly.native.current()},version=${it.player.currentSourceVersion},owns=${it.assembly.owns()},ready=${root.shell.navigationReady()}" }}; calls=${root.calls}")
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
        fun click(value: String) {
            val node = assertNotNull(text(value), "Missing $value")
            var current: SemanticsNode? = node
            while (current != null) {
                val click = current.config.getOrNull(SemanticsActions.OnClick)?.action
                if (click != null) { assertTrue(click()); frame(); return }
                current = current.parent
            }
            error("No actual click action for $value")
        }
        override fun close() { DesktopUpstreamCalls.withOwner(owner) { scene.close() }; busScope.cancel() }
    }

    private fun <T> onEdt(block: () -> T): T {
        val task = FutureTask(block)
        EventQueue.invokeAndWait(task)
        return task.get()
    }
}
