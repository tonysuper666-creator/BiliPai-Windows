package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import com.android.purebilibili.feature.download.DownloadOptions
import com.android.purebilibili.feature.download.DownloadStatus
import com.android.purebilibili.feature.download.DownloadTask as OriginalDownloadTask
import com.android.purebilibili.feature.download.OfflineMiniPlayerPayload
import com.android.purebilibili.feature.download.resolveOfflineMiniPlayerPayload
import com.bilipai.desktop.data.DesktopRepository
import com.bilipai.desktop.data.DesktopSessionStore
import com.bilipai.desktop.download.DesktopDownloadManager
import com.bilipai.desktop.download.DownloadMuxer
import com.bilipai.desktop.download.DownloadTask
import com.bilipai.desktop.player.DesktopRepositoryPlaybackPublication
import com.bilipai.desktop.player.MpvPlayer
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.settings.DesktopDanmakuBlockPreferences
import com.bilipai.desktop.settings.DesktopOriginalDanmakuPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

/** Actual Root backend/publication and production Compose binding/lifetime.
 * MPV has no attached Canvas: no native window, decoding, GPU or audio is used. */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class DesktopOriginalOfflineBindingsLifetimeTest {
    @Test fun platformAdapterRefreshKeepsTheActualSourceAndOriginalControlIdentity() = fixture { f ->
        BindingScene(f).use { scene ->
            val binding = scene.binding
            val control = scene.control
            val source = assertNotNull(f.player.currentSourceSnapshot())
            assertTrue(control.isOwned())
            scene.ports.value = Ports("successor", DesktopDanmakuPresentation.FULLSCREEN_LANDSCAPE)
            scene.render()
            assertSame(binding, scene.binding)
            assertSame(control, scene.control)
            assertTrue(f.backend.isOwned())
            assertTrue(control.isOwned())
            assertEquals(source, f.player.currentSourceSnapshot())
            control.pause()
            assertTrue(f.player.state.value.paused)
            control.play()
            assertFalse(f.player.state.value.paused)
            assertTrue(assertNotNull(source.source.nativePublication).admit { })
        }
    }

    @Test fun survivingBindingForwardsEveryPlatformOperationToTheLatestAdapter() = fixture { f ->
        BindingScene(f).use { scene ->
            val old = scene.ports.value
            val next = Ports("new", DesktopDanmakuPresentation.FULLSCREEN_PORTRAIT)
            val binding = scene.binding
            old.calls.clear()
            scene.ports.value = next
            scene.render()
            assertSame(binding, scene.binding)
            assertEquals(DesktopDanmakuPresentation.FULLSCREEN_PORTRAIT, binding.presentation.currentPresentation())
            binding.window.applyFullscreen(true)
            binding.window.restoreCurrentRootChrome()
            binding.media.publish(resolveOfflineMiniPlayerPayload(f.task.item), scene.control)
            binding.media.clearIfOwned(scene.control)
            binding.feedback("current")
            assertTrue(next.calls.containsAll(listOf("new:surface", "new:fullscreen:true", "new:chrome", "new:publish", "new:clear", "new:feedback:current")))
            assertTrue(old.calls.isEmpty(), "A retained original control must not call a retired platform adapter")
        }
    }

    @Test fun disposingTheActualBackendLifetimeRetiresItsPublicationButNotTheRootScope() = fixture { f ->
        BindingScene(f).use { scene ->
            val publication = assertNotNull(assertNotNull(f.player.currentSourceSnapshot()).source.nativePublication)
            assertTrue(f.backend.isOwned())
            scene.close()
            assertFalse(f.backend.isOwned())
            assertFalse(publication.admit { fail("Disposed entry cannot dispatch native commands") })
            assertTrue(f.scope.isActive)
        }
    }

    @Test fun currentEntryCanNavigateBackAfterItsMediaBackendRetires() = fixture { f ->
        f.backend.close()
        assertFalse(f.backend.isOwned())
        assertTrue(f.entryScope.isActive)
        var backs = 0
        var pipCloses = 0
        val current = { f.entryScope.isActive && f.gate.owns() }
        desktopOfflineNavigateBack(current, { f.backend.isOwned() && f.backend.ownsAcceptedSource() },
            { pipCloses++ }, { backs++ })
        assertEquals(1, backs)
        assertEquals(0, pipCloses)
        f.gate.close()
        desktopOfflineNavigateBack(current, { true }, { pipCloses++ }, { backs++ })
        assertEquals(1, backs)
        assertEquals(0, pipCloses)
    }

    private class Ports(val name: String, kind: DesktopDanmakuPresentation) {
        val calls = mutableListOf<String>()
        val presentation = DesktopDanmakuPresentationBinding { kind }
        val window = object : DesktopOfflineWindowEffects {
            override fun applyFullscreen(fullscreen: Boolean) { calls += "$name:fullscreen:$fullscreen" }
            override fun restoreCurrentRootChrome() { calls += "$name:chrome" }
        }
        val surface = object : DesktopOriginalOfflineSurface {
            @Composable override fun Render(player: DesktopOfflineMpvControl, modifier: Modifier, foreground: @Composable () -> Unit) {
                calls += "$name:surface"
                foreground()
            }
        }
        val media = object : DesktopOfflineMediaEffects {
            override fun publish(payload: OfflineMiniPlayerPayload, player: DesktopOfflineMpvControl) { calls += "$name:publish" }
            override fun clearIfOwned(player: DesktopOfflineMpvControl) { calls += "$name:clear" }
        }
        val feedback: (String) -> Unit = { calls += "$name:feedback:$it" }
    }

    private class BindingScene(f: Fixture) : AutoCloseable {
        private val scheduler = TestCoroutineScheduler()
        private val errors = mutableListOf<Throwable>()
        private val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(scheduler) +
            CoroutineExceptionHandler { _, error -> errors += error })
        val ports = mutableStateOf(Ports("initial", DesktopDanmakuPresentation.INLINE))
        lateinit var binding: DesktopOriginalOfflinePlayerBindings
        lateinit var control: DesktopOfflineMpvControl
        private var closed = false
        private val scene = ImageComposeScene(96, 64, Density(1f), coroutineContext = scope.coroutineContext) {
            val latest = ports.value
            val actual = rememberDesktopOriginalOfflinePlayerBindings(f.context, f.backend, f.entryScope, f.preferences,
                latest.presentation, latest.window, latest.surface, latest.media, latest.feedback)
            DesktopOfflineBackendLifetime(actual.backend) {
                val originalControl = remember(actual, f.task.id) { actual.control(f.task.id) }
                binding = actual
                control = originalControl
                actual.surface.Render(originalControl, Modifier) { }
            }
        }
        init { render() }
        fun render() {
            Snapshot.sendApplyNotifications()
            scheduler.runCurrent()
            scene.render(scheduler.currentTime * 1_000_000L).close()
            scheduler.runCurrent()
            assertTrue(errors.isEmpty(), "Unexpected Compose/coroutine errors: $errors")
        }
        override fun close() {
            if (closed) return
            closed = true
            try { scene.close() } finally { scope.cancel(); scheduler.runCurrent() }
            assertTrue(errors.isEmpty(), "Unexpected close errors: $errors")
        }
    }

    private class Fixture : AutoCloseable {
        private val directory = Files.createTempDirectory("bp-offline-binding-lifetime-")
        private val sessions = DesktopSessionStore(directory.resolve("session.json"), persistent = false)
        private val repository = DesktopRepository(sessions)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val alive = AtomicBoolean(true)
        val player = MpvPlayer()
        val gate = DesktopHomeRetainedGate(repository.dynamicCacheSessionGuard,
            checkNotNull(repository.dynamicCacheSessionGuard.dynamicCacheOwner()),
            { repository.sessionEpoch }, { repository.account.value?.mid }, alive::get, scope)
        val entryScope = CoroutineScope(SupervisorJob(scope.coroutineContext[Job]) + Dispatchers.Default)
        private val store = DesktopPluginStore(directory.resolve("preferences"))
        val context = DesktopPluginContext(store)
        private val admission: ((() -> Unit) -> Boolean) = { gate.commit(it) }
        private val blocks = DesktopDanmakuBlockPreferences(store, admission)
        val preferences = DesktopOriginalDanmakuPreferences(store, blocks, admission)
        val task: DownloadTask = run {
            val original = OriginalDownloadTask(bvid = "local-lifetime", cid = 1, title = "Private lifetime fixture",
                cover = "", ownerName = "", ownerFace = "", duration = 30, quality = 80, qualityDesc = "local",
                videoUrl = "", audioUrl = "", status = DownloadStatus.COMPLETED, progress = 1f,
                options = DownloadOptions(includeDanmaku = false))
            val wrapper = DownloadTask(original, directory.resolve("cache").toString())
            val folder = java.nio.file.Path.of(wrapper.directory)
            Files.createDirectories(folder)
            Files.writeString(folder.resolve(".bilipai-download"), wrapper.id)
            val media = folder.resolve("private.media")
            Files.write(media, byteArrayOf(1, 2, 3))
            wrapper.copy(item = original.copy(filePath = media.toString(), fileSize = 3, downloadedSize = 3))
        }
        private val stateFile = directory.resolve("downloads.json").also {
            Files.writeString(it, Json.encodeToString(ListSerializer(DownloadTask.serializer()), listOf(task)))
        }
        private val manager = DesktopDownloadManager(repository.playbackHttpClient, stateFile,
            DownloadMuxer { _, _, _ -> error("Local completed fixture must not download or mux") },
            publication = DesktopRepositoryPlaybackPublication(repository))
        private val retained = DesktopRetainedMedia(scope, player) { }
        val backend = DesktopOfflineTaskPlayerBinding(manager, retained, null, entryScope, gate.epoch,
            { repository.sessionEpoch }, gate::owns, gate::commit, { false }, { "No native renderer attached" })
        init {
            runBlocking { checkNotNull(backend.openOriginal(task.id) { error("No next episode") }).join() }
            check(backend.error == null && backend.isOwned() && backend.ownsAcceptedSource())
        }
        override fun close() {
            backend.close(); gate.close(); alive.set(false)
            entryScope.cancel(); scope.cancel(); manager.close(); retained.close(); player.close()
        }
    }

    private fun fixture(block: (Fixture) -> Unit) { Fixture().use(block) }
}
