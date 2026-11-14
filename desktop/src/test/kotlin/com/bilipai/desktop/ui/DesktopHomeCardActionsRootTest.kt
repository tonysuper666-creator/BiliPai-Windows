@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.android.purebilibili.core.events.BrandSuccessEvents
import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.data.model.response.FavFolder
import com.android.purebilibili.data.model.response.FavFolderList
import com.android.purebilibili.data.model.response.FavFolderResponse
import com.android.purebilibili.data.model.response.SimpleApiResponse
import com.android.purebilibili.feature.video.player.ExternalPlaylistSource
import com.android.purebilibili.feature.video.player.PlaylistItem
import com.android.purebilibili.feature.video.player.ShuffleProgress
import com.android.purebilibili.navigation3.BiliPaiNavKey
import com.bilipai.desktop.appearance.*
import com.bilipai.desktop.data.*
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.plugins.asAndroidContext
import kotlinx.coroutines.*
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.jupiter.api.io.TempDir
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

/*
 * Slice K3i at the Root: DesktopHomeCardActionsHost over the real Root actors it is given (a real DesktopRepository
 * with a synthetic login, the original favourite folder drawer on a proxy BilibiliApi, a real but not installed
 * DesktopOriginalVideoShellOwner), and the Root mini adapter on the real Shell playlist.
 *   * the port exists only on the active Home of a Root with services while card_interactions is ON;
 *   * 收藏 without a login only asks the Root login; with a login it opens the original drawer, whose membership comes
 *     from its own getFavFolders(rid = aid); a read failure shows the original error; nothing writes a favourite;
 *   * another account, leaving Home or OFF closes the drawer with zero writes;
 *   * 小窗播放 goes through the Root navigation admission; a Root without a ready player opens nothing and says so;
 *     an open mini rejects before the admission;
 *   * the adapter reads the ordinary click semantics from the Shell playlist (NONE is not UNKNOWN), correlates by
 *     playlist session, and excludes other media with exactly the Home -> VideoDetail keys of an ordinary click.
 * Not covered: an installed player (assembly, accepted source, mini window), a native window, real AWT input.
 */
class DesktopHomeCardActionsRootTest {
    @TempDir lateinit var directory: Path

    private val bvid = "BV1xx411c7mD"

    private fun target(aid: Long = 11L) =
        DesktopHomeCardTarget(bvid, aid, 71L, VideoCard(bvid, "K3i", "https://example.invalid/k3i.png", "up", 1, 60,
            preferredCid = 71L))

    private inner class Root : AutoCloseable {
        val sessions = DesktopSessionStore.temporary()
        val repository = DesktopRepository(sessions)
        val apiCalls = CopyOnWriteArrayList<String>()
        /** When set, a membership read blocks on its IO thread until the test releases it. */
        @Volatile var gate: CountDownLatch? = null
        val readEntered = CountDownLatch(1)
        @Volatile var folders: () -> Any = {
            FavFolderResponse(data = FavFolderList(list = listOf(FavFolder(id = 9, title = "K3i 默认收藏夹"))))
        }
        val api = Proxy.newProxyInstance(BilibiliApi::class.java.classLoader, arrayOf(BilibiliApi::class.java)) { _, method, args ->
            val list = args.orEmpty().toList()
            when (method.name) {
                "getFavFolders" -> {
                    apiCalls += "getFavFolders:mid=${list[0]}/type=${list[1]}/rid=${list[2]}"
                    readEntered.countDown(); gate?.await(5, TimeUnit.SECONDS); folders()
                }
                "toString" -> "K3i synthetic api"
                else -> { apiCalls += method.name; SimpleApiResponse() }
            }
        } as BilibiliApi
        val logins = CopyOnWriteArrayList<Unit>()
        val feedback = CopyOnWriteArrayList<String>()
        val admissions = CopyOnWriteArrayList<Unit>()
        val commits = CopyOnWriteArrayList<Pair<BiliPaiNavKey, BiliPaiNavKey>>()
        @Volatile var admit = true
        @Volatile var pip = false
        val services = DesktopHomeCardActionServices(repository, api,
            navigationAdmission = { block -> admissions += Unit; if (admit) { block(); true } else false },
            beforeNavigationCommit = { from, to -> commits += from to to },
            login = { logins += Unit }, feedback = { feedback += it }, isPipActiveOrPending = { pip })
        val shellScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val shell = DesktopOriginalVideoShellOwner(repository, DesktopPluginStore(directory.resolve("shell-${System.nanoTime()}")),
            shellScope, rootAlive = { true }, rootAdmission = { block -> block(); true }, metadata = {}, feedback = {})

        fun login(mid: Long) {
            sessions.saveAccount(mapOf("SESSDATA" to "synthetic-session-$mid", "bili_jct" to "synthetic-csrf-$mid"),
                AccountSummary(mid, "Fixture $mid", "", isVip = true))
            sessions.saveSpiCookies(mapOf("buvid3" to "synthetic-buvid", "buvid4" to "synthetic-buvid4"), sessions.generation)
        }

        fun writes() = apiCalls.filterNot { it.startsWith("getFavFolders") }

        override fun close() { shellScope.cancel(); repository.logout() }
    }

    private inner class Scene(val root: Root, withServices: Boolean = true, active: Boolean = true,
        interactions: DesktopWindowsUxCardInteractions = DesktopWindowsUxCardInteractions.ON) : AutoCloseable {
        private val scheduler = TestCoroutineScheduler()
        private var time = 0L
        private val errors = mutableListOf<Throwable>()
        private val context = DesktopPluginContext(DesktopPluginStore(directory.resolve("plugins-${System.nanoTime()}")))
        private val owner = root.repository.upstreamOwner(DesktopUpstreamPurpose.Main, { true })
        private val busScope = CoroutineScope(SupervisorJob())
        private val bus = BrandSuccessEvents { busScope.isActive }
        var active by mutableStateOf(active)
        var port: DesktopHomeCardActions? = null
        private val services = if (withServices) root.services else null

        private val scene = DesktopUpstreamCalls.withOwner(owner) {
            ImageComposeScene(900, 900, Density(1f), coroutineContext = StandardTestDispatcher(scheduler) +
                DesktopUpstreamScope(owner) + CoroutineExceptionHandler { _, error -> errors += error }) {
                DesktopAppearanceTheme(DesktopThemeSettings(hapticFeedbackEnabled = false),
                    systemLanguageTags = listOf("zh-CN"), windowSmallestWidthDp = 900) {
                    val original = LocalWindowInfo.current
                    val window = remember(original) { object : WindowInfo by original { override val isWindowFocused = true } }
                    val ux = remember { DesktopWindowsUxMemory(DesktopWindowsUxSettings(cardInteractions = interactions)) }
                    CompositionLocalProvider(LocalWindowInfo provides window, LocalDesktopWindowsUx provides ux,
                        LocalDesktopUpstreamAndroidContext provides context.asAndroidContext(),
                        LocalDesktopBrandSuccessEvents provides bus,
                        LocalDesktopOriginalVideoShellOwner provides root.shell) {
                        DesktopHomeCardActionsHost(services, this.active) {
                            val current = LocalDesktopHomeCardActions.current
                            SideEffect { port = current }
                        }
                    }
                }
            }
        }

        private fun frame() {
            Snapshot.sendApplyNotifications()
            scheduler.advanceUntilIdle(); time += 16_000_000
            DesktopUpstreamCalls.withOwner(owner) { scene.render(time).close() }
            scheduler.advanceUntilIdle()
            errors.firstOrNull()?.let { throw AssertionError("Home card actions composition failed", it) }
        }
        /** Frames in real time: the original protocol reads on Dispatchers.IO. */
        fun pumpUntil(timeoutMs: Long = 4_000, condition: () -> Boolean): Boolean {
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            while (true) {
                frame()
                if (condition()) return true
                if (System.nanoTime() > deadline) return false
                Thread.sleep(5)
            }
        }
        fun settle(ms: Long = 300) { pumpUntil(ms) { false } }
        private fun flatten(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::flatten)
        fun texts() = scene.semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }
            .mapNotNull { node -> node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } }
        fun drawerShown() = texts().any { it == "添加到收藏夹" }
        fun requirePort(): DesktopHomeCardActions = assertNotNull(port, "The active Home has the port")

        override fun close() { scene.close(); bus.close(); busScope.cancel() }
    }

    // ---- The port --------------------------------------------------------------------------------------------

    @Test fun onlyTheActiveHomeOfARootWithServicesWhileInteractionsAreOnHasThePort() {
        Root().use { root ->
            Scene(root).use { s -> s.settle(); assertNotNull(s.port, "Active Home, services, ON") }
            Scene(root, withServices = false).use { s -> s.settle(); assertNull(s.port, "A page without Root services") }
            Scene(root, active = false).use { s -> s.settle(); assertNull(s.port, "An inactive Home") }
            Scene(root, interactions = DesktopWindowsUxCardInteractions.OFF).use { s ->
                s.settle(); assertNull(s.port, "card_interactions OFF keeps the vendored menu")
            }
            Scene(root).use { s ->
                s.settle(); assertNotNull(s.port)
                s.active = false; s.settle(); assertNull(s.port, "Leaving Home drops the port")
            }
        }
    }

    // ---- 收藏 ----------------------------------------------------------------------------------------------

    @Test fun favoriteWithoutALoginOnlyAsksTheRootLogin() {
        Root().use { root ->
            Scene(root).use { s ->
                s.settle(); s.requirePort().favorite(target()); s.settle()
                assertEquals(1, root.logins.size, "The Root login, once")
                assertTrue(root.apiCalls.isEmpty(), "No protocol call without a login: ${root.apiCalls}")
                assertFalse(s.drawerShown())
            }
        }
    }

    @Test fun favoriteOpensTheOriginalDrawerWhoseMembershipComesFromItsOwnReadAndWritesNothing() {
        Root().use { root ->
            root.login(42)
            Scene(root).use { s ->
                s.settle(); s.requirePort().favorite(target())
                assertTrue(s.pumpUntil { s.drawerShown() && s.texts().any { it.contains("K3i 默认收藏夹") } },
                    "The original drawer with the folders it read: ${s.texts()}")
                assertEquals(listOf("getFavFolders:mid=42/type=2/rid=11"), root.apiCalls,
                    "One membership read for this aid and no other call")
                assertTrue(root.logins.isEmpty())
                assertTrue(root.writes().isEmpty(), "Opening the drawer never favourites: ${root.writes()}")
            }
        }
    }

    @Test fun aCardWithoutAidOpensNothing() {
        Root().use { root ->
            root.login(42)
            Scene(root).use { s ->
                s.settle(); s.requirePort().favorite(target(aid = 0)); s.settle()
                assertTrue(root.apiCalls.isEmpty() && root.logins.isEmpty()); assertFalse(s.drawerShown())
            }
        }
    }

    @Test fun aFailedMembershipReadShowsTheOriginalError() {
        Root().use { root ->
            root.login(42)
            root.folders = { FavFolderResponse(code = -1, message = "synthetic failure") }
            Scene(root).use { s ->
                s.settle(); s.requirePort().favorite(target())
                assertTrue(s.pumpUntil { root.feedback.isNotEmpty() }, "The read failure is reported")
                assertEquals(listOf("加载收藏夹失败: synthetic failure"), root.feedback.toList())
                assertTrue(root.writes().isEmpty())
            }
        }
    }

    @Test fun anotherAccountClosesTheDrawerWithZeroWrites() {
        Root().use { root ->
            root.login(42)
            Scene(root).use { s ->
                s.settle(); s.requirePort().favorite(target())
                assertTrue(s.pumpUntil { s.drawerShown() }, "The drawer of account 42")
                root.login(43)
                assertTrue(s.pumpUntil { !s.drawerShown() }, "Another session closes the drawer: ${s.texts()}")
                s.settle()
                assertFalse(s.drawerShown(), "It does not come back for the new account")
                assertTrue(root.writes().isEmpty(), "Zero writes: ${root.writes()}")
                assertTrue(root.feedback.isEmpty(), "The retired drawer reports nothing: ${root.feedback}")
            }
        }
    }

    @Test fun aReadInFlightAcrossAnAccountSwitchCommitsNothingForTheNewAccount() {
        Root().use { root ->
            root.login(42)
            val gate = CountDownLatch(1).also { root.gate = it }
            root.folders = { FavFolderResponse(code = -1, message = "synthetic failure") }
            Scene(root).use { s ->
                s.settle(); s.requirePort().favorite(target())
                assertTrue(s.pumpUntil { root.readEntered.count == 0L }, "The drawer of account 42 is reading")
                // The account changes while the read is on its IO thread; the read then comes back before any frame.
                root.login(43)
                gate.countDown()
                Thread.sleep(300)
                assertTrue(s.pumpUntil { !s.drawerShown() }, "Another session closes the drawer")
                s.settle()
                assertTrue(root.feedback.isEmpty(), "The result of account 42 is not committed under 43: ${root.feedback}")
                assertTrue(root.writes().isEmpty())
            }
        }
    }

    @Test fun aLogoutClosesTheDrawerWithZeroWrites() {
        Root().use { root ->
            root.login(42)
            Scene(root).use { s ->
                s.settle(); s.requirePort().favorite(target())
                assertTrue(s.pumpUntil { s.drawerShown() })
                root.repository.logout()
                assertTrue(s.pumpUntil { !s.drawerShown() }, "Logout closes the drawer")
                assertTrue(root.writes().isEmpty())
            }
        }
    }

    @Test fun leavingHomeClosesTheDrawerWithZeroWrites() {
        Root().use { root ->
            root.login(42)
            Scene(root).use { s ->
                s.settle(); s.requirePort().favorite(target())
                assertTrue(s.pumpUntil { s.drawerShown() })
                s.active = false
                assertTrue(s.pumpUntil { !s.drawerShown() }, "Leaving Home closes the drawer")
                s.active = true; s.settle()
                assertFalse(s.drawerShown(), "Coming back does not replay the request")
                assertTrue(root.writes().isEmpty())
            }
        }
    }

    // ---- 小窗播放 ---------------------------------------------------------------------------------------------

    @Test fun miniOnARootWithoutAReadyPlayerGoesThroughTheAdmissionAndOpensNothing() {
        Root().use { root ->
            Scene(root).use { s ->
                s.settle(); s.requirePort().mini(target(), Any()); s.settle()
                assertEquals(1, root.admissions.size, "The Root navigation admission, once")
                assertTrue(root.commits.isEmpty(), "No media exclusion without a ready player")
                assertEquals(listOf(DESKTOP_HOME_MINI_NOT_READY), root.feedback.toList())
                assertNull(root.shell.playlist.getCurrentItem(), "No queue was opened")
            }
        }
    }

    @Test fun aRefusedAdmissionRunsNoExclusionAndNoQueue() {
        Root().use { root ->
            root.admit = false
            Scene(root).use { s ->
                s.settle(); s.requirePort().mini(target(), Any()); s.settle()
                assertEquals(1, root.admissions.size)
                assertTrue(root.commits.isEmpty())
                assertEquals(listOf(DESKTOP_HOME_MINI_NOT_READY), root.feedback.toList())
                assertNull(root.shell.playlist.getCurrentItem())
            }
        }
    }

    @Test fun anOpenMiniRejectsBeforeTheAdmission() {
        Root().use { root ->
            root.pip = true
            Scene(root).use { s ->
                s.settle(); s.requirePort().mini(target(), Any()); s.settle()
                assertTrue(root.admissions.isEmpty(), "No admission while a mini is open")
                assertEquals(listOf(DESKTOP_HOME_MINI_PIP_BUSY), root.feedback.toList())
            }
        }
    }

    // ---- The Root mini adapter on the real Shell playlist ------------------------------------------------------

    private fun item(id: String = bvid, cid: Long = 71L) = PlaylistItem(id, cid, "K3i", "", "up")

    @Test fun theAdapterReadsTheOrdinaryClickSemanticsFromTheShellPlaylist() {
        Root().use { root ->
            val adapter = DesktopHomeCardRootMiniPlayback(root.shell) { root.services }
            val playlist = root.shell.playlist
            // The ordinary click (owner == null) adopts its queue with the NONE source.
            playlist.adoptQueue(listOf(item()), 0, ExternalPlaylistSource.NONE, false, ShuffleProgress())
            assertTrue(adapter.sourceIsNone(), "An ordinary click queue is NONE")
            assertEquals(bvid to 71L, adapter.currentItem())
            // An owned queue (owner != null) is UNKNOWN: not the ordinary click.
            playlist.setExternalPlaylist(listOf(item()), 0, ExternalPlaylistSource.UNKNOWN)
            assertFalse(adapter.sourceIsNone(), "UNKNOWN is not the ordinary click semantics")
        }
    }

    @Test fun theAdapterCorrelatesByPlaylistSession() {
        Root().use { root ->
            val adapter = DesktopHomeCardRootMiniPlayback(root.shell) { root.services }
            val playlist = root.shell.playlist
            val before = playlist.captureSession()
            assertTrue(adapter.isSessionCurrent(before))
            val after = playlist.adoptQueue(listOf(item()), 0, ExternalPlaylistSource.NONE, false, ShuffleProgress())
            assertFalse(adapter.isSessionCurrent(before), "A new queue retires the earlier session")
            assertTrue(adapter.isSessionCurrent(after))
            playlist.adoptQueue(listOf(item("BV1yy411c7mE")), 0, ExternalPlaylistSource.NONE, false, ShuffleProgress())
            assertFalse(adapter.isSessionCurrent(after), "Another click retires this one")
        }
    }

    @Test fun theAdapterIsNotReadyWithoutAnInstalledPlayer() {
        Root().use { root ->
            val adapter = DesktopHomeCardRootMiniPlayback(root.shell) { root.services }
            assertFalse(root.shell.navigationReady())
            assertNull(adapter.captureSession(), "No session without a ready owned player")
            assertNull(adapter.accepted()); assertNull(adapter.sourceVersion())
            assertFalse(adapter.loadFailed(bvid))
            assertFalse(adapter.enterMini(DesktopHomeCardMiniAccepted(bvid, 71L, 1L)), "No mini without a player")
        }
    }

    @Test fun theAdapterExcludesOtherMediaWithTheKeysOfAnOrdinaryClick() {
        Root().use { root ->
            val adapter = DesktopHomeCardRootMiniPlayback(root.shell) { root.services }
            adapter.excludeOtherMedia(target())
            assertEquals(listOf<Pair<BiliPaiNavKey, BiliPaiNavKey>>(BiliPaiNavKey.Home to
                BiliPaiNavKey.VideoDetail(bvid, 71L, "https://example.invalid/k3i.png", fullscreen = false,
                    sourceRoute = "home")), root.commits.toList())
            root.pip = true
            assertTrue(adapter.pipActiveOrPending())
        }
    }
}
