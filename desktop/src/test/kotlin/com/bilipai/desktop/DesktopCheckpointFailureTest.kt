package com.bilipai.desktop

import com.bilipai.desktop.data.*
import com.bilipai.desktop.data.PlaybackSource as ResolvedSource
import com.bilipai.desktop.player.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.swing.Swing
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.SwingUtilities
import kotlin.test.*

/** Real controller and MpvPlayer ownership; its Canvas is never attached to a window. */
class DesktopCheckpointFailureTest {
    @Test fun `malformed privacy fails safely on repeated checkpoints and still pauses and closes`(): Unit = runBlocking {
        Fixture().use { f ->
            f.open()
            val before = Files.readAllBytes(f.root.resolve("library.json"))
            Files.createDirectories(f.root.resolve("search"))
            Files.writeString(f.root.resolve("search/plugin-settings.json"), "{secret-private-content")
            f.onMain {
                repeat(3) { assertFalse(f.controller.checkpoint()) }
                assertEquals("播放记录保存失败，请检查本地隐私和存储设置", f.controller.state.value.error)
                assertFalse(f.controller.state.value.error.orEmpty().contains("secret"))
                assertFalse(f.controller.state.value.error.orEmpty().contains(f.root.toString()))
                f.controller.pause()
                assertTrue(f.player.state.value.paused)
                f.controller.close(); f.controller.close()
                assertNull(f.player.currentSourceSnapshot())
            }
            assertContentEquals(before, Files.readAllBytes(f.root.resolve("library.json")))
        }
    }

    @Test fun `history storage failure is reported but stopping still releases the owned source`(): Unit = runBlocking {
        Fixture().use { f ->
            f.open()
            val file = f.root.resolve("library.json")
            Files.delete(file); Files.createDirectory(file); Files.writeString(file.resolve("keep"), "original marker")
            f.onMain {
                assertFalse(f.controller.checkpoint())
                assertNotNull(f.controller.state.value.error)
                f.controller.stop()
                assertNull(f.player.currentSourceSnapshot())
                assertNull(f.controller.state.value.details)
            }
            assertEquals("original marker", Files.readString(file.resolve("keep")))
        }
    }

    @Test fun `no eligible source and deliberate incognito suppression remain successful checkpoints`(): Unit = runBlocking {
        Fixture().use { f ->
            f.onMain { assertTrue(f.controller.checkpoint()) }
            f.open()
            val before = Files.readAllBytes(f.root.resolve("library.json"))
            f.preferences.setPrivacyMode(true)
            f.onMain { assertTrue(f.controller.checkpoint()); assertNull(f.controller.state.value.error) }
            assertContentEquals(before, Files.readAllBytes(f.root.resolve("library.json")))
        }
    }

    @Test fun `a foreign source is neither checkpointed nor stopped during controller close`(): Unit = runBlocking {
        Fixture().use { f ->
            f.open()
            f.onMain {
                val foreign = f.player.loadVersioned(PlaybackSource("https://fixture.invalid/foreign", title = "foreign"))
                Files.createDirectories(f.root.resolve("search")); Files.writeString(f.root.resolve("search/plugin-settings.json"), "malformed")
                assertTrue(f.controller.checkpoint())
                f.controller.close()
                assertEquals(foreign, f.player.currentSourceVersion)
                assertEquals("foreign", f.player.currentSourceSnapshot()?.source?.title)
            }
        }
    }

    private class Fixture : AutoCloseable {
        val root = Files.createTempDirectory("bilipai-checkpoint-lifecycle-").toAbsolutePath().normalize()
        val preferences = DesktopSearchPreferences(root)
        val player = MpvPlayer()
        private val failures = CopyOnWriteArrayList<Throwable>()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing + CoroutineExceptionHandler { _, error -> failures.add(error) })
        private val repository = DesktopRepository(DesktopSessionStore(root.resolve("account.json"), persistent = false))
        private val source = object : DesktopPlaybackDataSource {
            override suspend fun videoDetails(bvid: String) = VideoDetails(bvid, 1, "fixture", "", "", "", 0, 0,
                listOf(VideoPart(7, "fixture part", 100)))
            override suspend fun related(bvid: String) = emptyList<VideoCard>()
            override suspend fun playback(details: VideoDetails, index: Int, quality: Int, codecOverride: String?, forceRefresh: Boolean) =
                ResolvedSource("https://fixture.invalid/video", null, "fixture", "", quality = quality)
        }
        val controller = DesktopPlaybackController(repository, player, null, null,
            DesktopLibrary(root, preferences::isPrivacyModeEnabledSync), { PlayerPreferences() }, scope, dataSource = source)
        suspend fun open() {
            onMain { controller.open(VideoCard("BV-checkpoint", "fixture", "", "", 0, 100)) }
            withTimeout(3_000) { controller.state.first { !it.opening && it.details != null } }
            onMain {
                @Suppress("UNCHECKED_CAST")
                val mutable = MpvPlayer::class.java.getDeclaredField("mutableState").apply { isAccessible = true }
                    .get(player) as MutableStateFlow<PlayerState>
                mutable.value = mutable.value.copy(loading = false, durationSeconds = 100.0, positionSeconds = 12.0, paused = false)
            }
        }
        fun onMain(block: () -> Unit) {
            var failure: Throwable? = null
            SwingUtilities.invokeAndWait { try { block() } catch (error: Throwable) { failure = error } }
            failure?.let { throw it }
        }
        override fun close() {
            onMain { controller.close() }; scope.cancel(); player.close()
            check(root.startsWith(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()))
            check(root.fileName.toString().startsWith("bilipai-checkpoint-lifecycle-"))
            Files.walk(root).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach { path ->
                check(path.toAbsolutePath().normalize().startsWith(root)); Files.deleteIfExists(path)
            } }
            check(failures.isEmpty()) { "Checkpoint failure escaped to the controller scope" }
        }
    }
}
