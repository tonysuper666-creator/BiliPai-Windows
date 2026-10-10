package com.bilipai.desktop.ui

import com.bilipai.desktop.data.AccountSummary
import com.bilipai.desktop.data.DesktopSessionStore
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.settings.DesktopImageSaveLocationPreferences
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Runs the actual Section-used pipeline and real Assets/Locations file actions.
 * Fixture PNG replaces GPU frame acquisition only: no native/HDR success claim. */
class DesktopWindowsVideoScreenshotAdmissionTest {
    @TempDir lateinit var directory: Path
    private val png = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jIOMAAAAASUVORK5CYII=")

    private class Fixture(root: Path) : AutoCloseable {
        val active = AtomicBoolean(true)
        val page = AtomicBoolean(true)
        val source = AtomicBoolean(true)
        val images: Path = Files.createDirectory(root.resolve("images"))
        val fallback: Path = Files.createDirectory(root.resolve("fallback"))
        val sessions = DesktopSessionStore(root.resolve("synthetic-session.json"), persistent = false)
        init { sessions.saveAccount(mapOf("SESSDATA" to "synthetic-only"), AccountSummary(42L,"fixture","")) }
        val owner = checkNotNull(sessions.dynamicCacheOwner())
        val lifetime = DesktopImageSaveLifetime { !active.get() }
        val preferences = DesktopImageSaveLocationPreferences(
            DesktopPluginStore(root.resolve("settings")),lifetime::withCommit)
        val locations = DesktopImageSaveLocations(preferences,lifetime::isActive,lifetime::withCommit,
            resolveDefaultVideoDirectory = { error("No video KnownFolder") },
            resolveDefaultDirectory = { Result.success(fallback.resolve("BiliPai")) })
        val assets = DesktopDynamicImageAssets(OkHttpClient.Builder().addInterceptor {
            error("Native screenshot must not request HTTP")
        }.build(), { active.get() },sessions,owner,
            selectTarget = { _, _ -> error("No chooser") }, selectDirectory = { error("No chooser") },
            imageSaveLocations = locations)
        fun captured() = active.get() && page.get() && source.get() && sessions.generation == owner.epoch
        fun admission(action: () -> Unit): Boolean = sessions.withCurrentDynamicCacheOwner(owner, action)
        suspend fun configure() { preferences.setImageSaveTreeUri(images.toUri().toString()) }
        suspend fun save(bytes: ByteArray, owned: () -> Boolean, commit: ((() -> Unit) -> Boolean)) =
            assets.saveNativeFrameBytes(bytes,"Screenshot.png",owned,commit)
        override fun close() { assets.close(); lifetime.close(); active.set(false) }
    }
    private fun files(path: Path) = Files.list(path).use { it.toList() }

    @Test fun actualPipelineSavesTheCapturedPngAndSavedReceiptRejectsRetiredPage(): Unit = runBlocking {
        Fixture(directory).use { fixture ->
            fixture.configure()
            val captures = AtomicInteger()
            val saved = captureDesktopWindowsVideoScreenshot(fixture::captured,
                capture = { captures.incrementAndGet(); png },save = fixture::save,admission = fixture::admission)
            assertArrayEquals(png,saved)
            assertArrayEquals(png,Files.readAllBytes(fixture.images.resolve("Screenshot.png")))
            assertEquals(1,captures.get())
            assertEquals(listOf("Screenshot.png"),files(fixture.images).map { it.fileName.toString() })
            val receipt = DesktopOriginalSavedVideoScreenshot(checkNotNull(saved),fixture::captured)
            assertArrayEquals(png,receipt.copyPngBytes())
            fixture.page.set(false)
            assertFalse(receipt.isCurrent())
            assertThrows(CancellationException::class.java) { receipt.copyPngBytes() }
            assertFalse(Files.exists(fixture.fallback.resolve("BiliPai")))
        }
    }

    @Test fun sourceRetiredDuringNativeCaptureCannotSaveOrRecaptureAnotherSource(): Unit = runBlocking {
        Fixture(directory).use { fixture ->
            fixture.configure()
            val captures = AtomicInteger()
            val saves = AtomicInteger()
            val failure = runCatching {
                captureDesktopWindowsVideoScreenshot(fixture::captured,
                    capture = { captures.incrementAndGet(); fixture.source.set(false); png },
                    save = { bytes, owned, commit -> saves.incrementAndGet(); fixture.save(bytes,owned,commit) },
                    admission = fixture::admission)
            }.exceptionOrNull()
            assertTrue(failure is CancellationException)
            assertEquals(1,captures.get(),"No latest-source fallback/recapture")
            assertEquals(0,saves.get())
            assertTrue(files(fixture.images).isEmpty())
            assertFalse(Files.exists(fixture.fallback.resolve("BiliPai")))
        }
    }

    @Test fun pageRetiredAtActualFinalGalleryAdmissionLeavesNoFileOrWrittenStage(): Unit = runBlocking {
        Fixture(directory).use { fixture ->
            fixture.configure()
            val admissions = AtomicInteger()
            val stageObserved = AtomicBoolean(false)
            val failure = runCatching {
                captureDesktopWindowsVideoScreenshot(fixture::captured,capture = { png },save = fixture::save,
                    admission = { action ->
                        if (admissions.incrementAndGet() == 2) {
                            val stage = files(fixture.images).single()
                            assertTrue(stage.fileName.toString().startsWith(".bilipai-profile-gallery-"))
                            assertArrayEquals(png,Files.readAllBytes(stage))
                            stageObserved.set(true)
                            fixture.page.set(false)
                        }
                        // Invoke the actual pipeline action; its final current check must reject it.
                        fixture.admission(action)
                    })
            }.exceptionOrNull()
            assertTrue(failure is CancellationException)
            assertEquals("Screenshot page/source retired",failure?.message)
            assertTrue(stageObserved.get()); assertEquals(2,admissions.get())
            assertTrue(fixture.source.get(),"Only this page lifetime retired")
            assertTrue(files(fixture.images).isEmpty(),"Real gallery finally cleans the written stage")
            assertFalse(Files.exists(fixture.fallback.resolve("BiliPai")))
        }
    }

    @Test fun oldPageUnregisterCannotRemoveNewShortcutAndRetiredCallbackDoesNotConsumeKey() {
        val shortcuts = DesktopWindowsVideoScreenshotShortcut()
        val oldCalls = AtomicInteger(); val newCalls = AtomicInteger(); val current = AtomicBoolean(true)
        val oldPage = shortcuts.register { oldCalls.incrementAndGet(); true }
        val newPage = shortcuts.register { newCalls.incrementAndGet(); current.get() }
        oldPage.close()
        assertTrue(shortcuts.dispatch())
        assertEquals(0,oldCalls.get()); assertEquals(1,newCalls.get())
        current.set(false)
        assertFalse(shortcuts.dispatch())
        newPage.close()
        assertFalse(shortcuts.dispatch())
        assertEquals(2,newCalls.get())
    }
}
