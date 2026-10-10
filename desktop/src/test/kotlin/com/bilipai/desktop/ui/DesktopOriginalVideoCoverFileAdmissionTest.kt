package com.bilipai.desktop.ui

import com.bilipai.desktop.data.AccountSummary
import com.bilipai.desktop.data.DesktopSessionStore
import com.bilipai.desktop.download.DesktopDownloadManager
import com.bilipai.desktop.download.DownloadMuxer
import com.bilipai.desktop.player.DesktopLocalPlaybackPublication
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.settings.DesktopImageSaveLocationPreferences
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Real Binding, Assets, SessionStore and file publication; only image transport
 * and the entry/page predicates are fixtures. No chooser, GPU or external HTTP. */
class DesktopOriginalVideoCoverFileAdmissionTest {
    @TempDir lateinit var directory: Path

    private class Fixture(root: Path) : AutoCloseable {
        val bytes = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jIOMAAAAASUVORK5CYII=")
        val requests = AtomicInteger()
        val entryActive = AtomicBoolean(true)
        val pageActive = AtomicBoolean(true)
        val sourceActive = AtomicBoolean(true)
        val images: Path = Files.createDirectory(root.resolve("images"))
        val fallback: Path = Files.createDirectory(root.resolve("fallback"))
        val sessions = DesktopSessionStore(root.resolve("synthetic-session.json"), persistent = false)
        init { sessions.saveAccount(mapOf("SESSDATA" to "synthetic-only"), AccountSummary(42L, "fixture", "")) }
        val owner = checkNotNull(sessions.dynamicCacheOwner())
        val lifetime = DesktopImageSaveLifetime { !entryActive.get() }
        val store = DesktopPluginStore(root.resolve("synthetic-settings"))
        val preferences = DesktopImageSaveLocationPreferences(store, lifetime::withCommit)
        val settings = DesktopOriginalPlayerSettingsContext(
            DesktopPluginContext(store), ::entryOwned, ::entryAdmission)
        val locations = DesktopImageSaveLocations(preferences, lifetime::isActive, lifetime::withCommit,
            resolveDefaultVideoDirectory = { error("No video KnownFolder used") },
            resolveDefaultDirectory = { Result.success(fallback.resolve("BiliPai")) })
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("fixture.invalid", chain.request().url.host)
            requests.incrementAndGet()
            // Never call proceed: this is the actual Assets HTTP actor with an in-memory response.
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("Fixture").body(bytes.toResponseBody("image/png".toMediaType())).build()
        }.build()
        val assets = DesktopDynamicImageAssets(client, ::entryOwned, sessions, owner,
            selectTarget = { _, _ -> error("No chooser used") },
            selectDirectory = { error("No chooser used") }, imageSaveLocations = locations)
        val entryScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val manager = DesktopDownloadManager(client, root.resolve("download-state.json"),
            DownloadMuxer { _, _, _ -> error("Cover save must not mux media") },
            publication = DesktopLocalPlaybackPublication(::entryOwned, ::entryAdmission),
            defaultDestination = { root.resolve("downloads") })
        val binding = DesktopOriginalVideoOwnerDownloadBinding(manager, settings, assets, entryScope,
            ::entryOwned, ::entryAdmission,
            resolveCaptured = { error("Cover save must not resolve a media task") },
            captureConstructedTask = { _, _ -> error("Cover save must not construct a media task") })
        fun entryOwned() = entryActive.get() && sessions.generation == owner.epoch
        fun captured() = pageActive.get() && sourceActive.get()
        fun entryAdmission(action: () -> Unit): Boolean = sessions.withCurrentDynamicCacheOwner(owner) {
            if (!entryOwned()) throw CancellationException("Fixture entry retired")
            action()
        }
        suspend fun configure() { preferences.setImageSaveTreeUri(images.toUri().toString()) }
        override fun close() {
            assets.close(); manager.close(); entryScope.cancel(); lifetime.close(); entryActive.set(false)
            client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        }
    }

    private fun files(directory: Path): List<Path> = Files.list(directory).use { it.toList() }

    @Test fun actualBindingSavesOriginalCoverBytesToConfiguredGallery(): Unit = runBlocking {
        Fixture(directory).use { fixture ->
            fixture.configure()
            val admissions = AtomicInteger()
            val saved = fixture.binding.saveImageToGallery(fixture.settings,
                "https://fixture.invalid/cover.png", "Cover", fixture::captured) { action ->
                admissions.incrementAndGet()
                fixture.entryAdmission(action)
            }
            assertTrue(saved)
            val output = fixture.images.resolve("Cover.jpg")
            // Original cover saving keeps response bytes, including a PNG payload with the original .jpg name.
            assertArrayEquals(fixture.bytes, Files.readAllBytes(output))
            assertEquals(listOf(output.toAbsolutePath().normalize()), files(fixture.images))
            assertEquals(2, admissions.get(), "Locations selection and actual final file publication")
            assertEquals(1, fixture.requests.get())
            assertTrue(fixture.manager.tasks.value.isEmpty())
            assertFalse(Files.exists(fixture.fallback.resolve("BiliPai")))
        }
    }

    @Test fun actualBindingRejectsRetiredCaptureAtFinalMoveAndCleansGalleryStage(): Unit = runBlocking {
        Fixture(directory).use { fixture ->
            fixture.configure()
            val admissions = AtomicInteger()
            val observedWrittenStage = AtomicBoolean(false)
            val failure = runCatching {
                fixture.binding.saveImageToGallery(fixture.settings,
                    "https://fixture.invalid/cover.png", "Cover", fixture::captured) { action ->
                    if (admissions.incrementAndGet() == 2) {
                        val stages = files(fixture.images)
                        assertEquals(1, stages.size)
                        assertTrue(stages.single().fileName.toString().startsWith(".bilipai-profile-gallery-"))
                        assertArrayEquals(fixture.bytes, Files.readAllBytes(stages.single()))
                        observedWrittenStage.set(true)
                        fixture.pageActive.set(false); fixture.sourceActive.set(false)
                    }
                    // Still invoke the real action: the Binding's final current predicate must reject it.
                    fixture.entryAdmission(action)
                }
            }.exceptionOrNull()
            assertTrue(failure is CancellationException)
            assertEquals("Original cover click retired before file publication", failure?.message)
            assertTrue(observedWrittenStage.get())
            assertEquals(2, admissions.get())
            assertEquals(1, fixture.requests.get())
            assertTrue(fixture.entryOwned(), "Only page/source capture was retired")
            assertFalse(Files.exists(fixture.images.resolve("Cover.jpg")))
            assertTrue(files(fixture.images).isEmpty(), "Actual finally must remove the written gallery stage")
            assertFalse(Files.exists(fixture.fallback.resolve("BiliPai")), "Cancellation must not try a fallback destination")
            assertTrue(fixture.manager.tasks.value.isEmpty())
        }
    }
}
