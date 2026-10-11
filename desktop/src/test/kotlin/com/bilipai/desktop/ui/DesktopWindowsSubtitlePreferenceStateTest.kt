@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package com.bilipai.desktop.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.android.purebilibili.core.plugin.PlayerPlugin
import com.android.purebilibili.data.model.response.PlayUrlData
import com.android.purebilibili.feature.video.subtitle.*
import com.android.purebilibili.feature.video.viewmodel.DesktopVideoCommentRequests
import com.bilipai.desktop.data.*
import com.bilipai.desktop.player.*
import com.bilipai.desktop.plugins.*
import java.awt.EventQueue
import java.awt.GraphicsEnvironment
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.FileVisitResult
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.swing.Swing
import okhttp3.OkHttpClient
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import kotlin.test.*

/** Real production subtitle-state component, original full resolvers, actual
 * Assembly.create/VM and EDT-owned SubtitleModeBinding. The real retained
 * SaveableStateHolder disposes/remounts the same Nav entry. This CPU scene does
 * not construct an AWT Window, initialize MPV, publish media or render subtitle
 * pixels; physical Leaf/window/manual-chip acceptance remains separate.
 */
@Execution(ExecutionMode.SAME_THREAD)
class DesktopWindowsSubtitlePreferenceStateTest {
    @Test fun sameOriginalSessionKeyRefreshKeepsTheRegisteredManualSelection(): Unit = runBlocking {
        withOwner { owner ->
            val initial = originalSession()
            val scene = Scene(owner, initial)
            try {
                scene.awaitMode(owner, SubtitleDisplayMode.BILINGUAL)
                SubtitleDisplayMode.entries.forEach { mode ->
                    assertTrue(scene.set(owner, mode))
                    scene.awaitMode(owner, mode)
                }
                assertTrue(scene.set(owner, SubtitleDisplayMode.SECONDARY_ONLY))
                scene.awaitMode(owner, SubtitleDisplayMode.SECONDARY_ONLY)
                // Track availability/mute can change on a metadata/cue refresh,
                // but the original policy intentionally keeps this business key.
                val refreshed = originalSession(hasSecondaryTrack = false, muted = true)
                assertEquals(initial.key, refreshed.key)
                assertEquals(SubtitleDisplayMode.PRIMARY_ONLY, refreshed.initialMode)
                scene.changeSession(refreshed)
                scene.pump()
                assertEquals(SubtitleDisplayMode.SECONDARY_ONLY, scene.read(owner))
                assertTrue(owner.owner.owns())
                assertNull(owner.player.currentSourceSnapshot())
            } finally { scene.close() }
        }
    }

    @Test fun partAndPreferenceChangesUseTheOriginalSessionResetPolicy(): Unit = runBlocking {
        withOwner { owner ->
            val initial = originalSession()
            val scene = Scene(owner, initial)
            try {
                scene.awaitMode(owner, initial.initialMode)
                assertTrue(scene.set(owner, SubtitleDisplayMode.OFF))
                scene.awaitMode(owner, SubtitleDisplayMode.OFF)
                val nextPart = originalSession(cid = 102L)
                assertNotEquals(initial.key, nextPart.key)
                scene.changeSession(nextPart)
                scene.awaitMode(owner, SubtitleDisplayMode.BILINGUAL)
                assertTrue(scene.set(owner, SubtitleDisplayMode.PRIMARY_ONLY))
                scene.awaitMode(owner, SubtitleDisplayMode.PRIMARY_ONLY)
                val offPreference = originalSession(cid = 102L, preference = SubtitleAutoPreference.OFF)
                assertNotEquals(nextPart.key, offPreference.key)
                scene.changeSession(offPreference)
                scene.awaitMode(owner, SubtitleDisplayMode.OFF)
                assertTrue(scene.set(owner, SubtitleDisplayMode.BILINGUAL))
                scene.awaitMode(owner, SubtitleDisplayMode.BILINGUAL)
                val nextLanguage = originalSession(cid = 102L, primaryLanguage = "ja-JP",
                    preference = SubtitleAutoPreference.OFF)
                assertNotEquals(offPreference.key, nextLanguage.key)
                scene.changeSession(nextLanguage)
                scene.awaitMode(owner, SubtitleDisplayMode.OFF)
            } finally { scene.close() }
        }
    }

    @Test fun sameRetainedAssemblyRestoresItsManualChoiceAfterRealEntryDisposal(): Unit = runBlocking {
        withOwner { owner ->
            val session = originalSession()
            val token = owner.owner.subtitleSaveableOwnerKey
            val scene = Scene(owner, session)
            try {
                scene.awaitMode(owner, SubtitleDisplayMode.BILINGUAL)
                assertTrue(scene.set(owner, SubtitleDisplayMode.OFF))
                scene.awaitMode(owner, SubtitleDisplayMode.OFF)
                scene.hide()
                scene.await { scene.read(owner) == null }
                assertFalse(scene.set(owner, SubtitleDisplayMode.BILINGUAL))
                assertTrue(owner.owner.owns())
                scene.show(owner, session.copy())
                scene.awaitMode(owner, SubtitleDisplayMode.OFF)
                assertEquals(token, owner.owner.subtitleSaveableOwnerKey)
                // A registration is live again, rather than an assertion only
                // against an unmounted saved payload or copied resolver state.
                assertTrue(scene.set(owner, SubtitleDisplayMode.PRIMARY_ONLY))
                scene.awaitMode(owner, SubtitleDisplayMode.PRIMARY_ONLY)
            } finally { scene.close() }
        }
    }

    @Test fun differentRealAssemblyCannotRestoreThePreviousOwnersManualChoice(): Unit = runBlocking {
        withOwner { previous ->
            val session = originalSession()
            val scene = Scene(previous, session)
            try {
                scene.awaitMode(previous, SubtitleDisplayMode.BILINGUAL)
                assertTrue(scene.set(previous, SubtitleDisplayMode.SECONDARY_ONLY))
                scene.awaitMode(previous, SubtitleDisplayMode.SECONDARY_ONLY)
                scene.hide()
                scene.await { scene.read(previous) == null }
                previous.retire()
                assertFalse(previous.owner.owns())
                withOwner { replacement ->
                    assertNotSame(previous.owner, replacement.owner)
                    assertNotSame(previous.owner.playback, replacement.owner.playback)
                    assertEquals(previous.epoch, replacement.epoch)
                    assertNotEquals(previous.owner.subtitleSaveableOwnerKey, replacement.owner.subtitleSaveableOwnerKey)
                    // The SAME retained Nav provider still contains the old
                    // owner's two saved values. The real Saver must reject them.
                    scene.show(replacement, session.copy())
                    scene.awaitMode(replacement, SubtitleDisplayMode.BILINGUAL)
                    assertNull(scene.read(previous))
                    assertFalse(scene.set(previous, SubtitleDisplayMode.OFF))
                    assertTrue(scene.set(replacement, SubtitleDisplayMode.PRIMARY_ONLY))
                    scene.awaitMode(replacement, SubtitleDisplayMode.PRIMARY_ONLY)
                    scene.hide()
                    scene.await { scene.read(replacement) == null }
                }
            } finally { scene.close() }
        }
    }

    private fun originalSession(cid: Long = 101L, primaryLanguage: String = "zh-CN",
        hasSecondaryTrack: Boolean = true, preference: SubtitleAutoPreference = SubtitleAutoPreference.ON,
        muted: Boolean = false) = resolveSubtitlePreferenceSession(
        bvid = "BV1GJ411x7h7", cid = cid, primaryLanguage = primaryLanguage, secondaryLanguage = "en-US",
        primaryTrackLikelyAi = false, secondaryTrackLikelyAi = false,
        hasPrimaryTrack = true, hasSecondaryTrack = hasSecondaryTrack,
        preference = preference, isMuted = muted,
    )

    private suspend fun withOwner(block: suspend (ActualOwner) -> Unit) {
        val owner = ActualOwner()
        try { owner.initialize(); withTimeout(20_000L) { block(owner) } }
        finally { owner.close() }
    }

    private class Scene(initialOwner: ActualOwner, initialSession: SubtitlePreferenceSession) : AutoCloseable {
        private var activeOwner by mutableStateOf<ActualOwner?>(initialOwner)
        private var session by mutableStateOf(initialSession)
        private var nanos = 0L
        private var closed = false
        private val bridge = edt {
            DesktopOriginalSubtitleModeBinding(
                isCurrent = { vm -> activeOwner?.let { it.owner.playback === vm && it.owner.owns() } == true },
                admit = { vm, action -> activeOwner?.takeIf { it.owner.playback === vm }?.admit(action) == true },
            )
        }
        private val scene = edt {
            check(GraphicsEnvironment.isHeadless())
            ImageComposeScene(400, 200, Density(1f)) {
                val holder = rememberSaveableStateHolder()
                val owner = activeOwner
                if (owner != null) holder.SaveableStateProvider("same-retained-video-nav-entry") {
                    val mode = rememberDesktopWindowsSubtitleMode(owner.owner.subtitleSaveableOwnerKey, session)
                    val latestMode by rememberUpdatedState(mode.value)
                    DisposableEffect(owner.owner, bridge) {
                        val registration = bridge.register(owner.owner.playback, { latestMode }, { mode.value = it })
                        onDispose { registration.close() }
                    }
                    Text(mode.value.name)
                }
            }
        }
        fun set(owner: ActualOwner, mode: SubtitleDisplayMode) = edt { bridge.set(owner.owner.playback, mode) }
        fun read(owner: ActualOwner) = edt { bridge.read(owner.owner.playback) }
        fun changeSession(value: SubtitlePreferenceSession) = edt { session = value }
        fun hide() = edt { activeOwner = null }
        fun show(owner: ActualOwner, value: SubtitlePreferenceSession) = edt { session = value; activeOwner = owner }
        private fun render() = edt {
            nanos = maxOf(System.nanoTime(), nanos + 1L)
            scene.render(nanos).close()
        }
        suspend fun pump() { repeat(5) { render(); delay(12L) } }
        suspend fun await(condition: () -> Boolean) = withTimeout(5_000L) {
            while (true) { render(); if (condition()) return@withTimeout; delay(12L) }
        }
        suspend fun awaitMode(owner: ActualOwner, expected: SubtitleDisplayMode) = await { read(owner) == expected }
        override fun close() = edt {
            if (!closed) {
                closed = true
                try { scene.close() } finally { bridge.close() }
            }
        }
    }

    /** Constructor and ports reuse the existing actual PGC Assembly fixture.
     * No playback load, metadata request, fake Success/private VM write, native
     * handle or global NetworkModule owner is introduced for the presentation test.
     */
    private class ActualOwner {
        private val directory = Files.createTempDirectory("subtitle-actual-saveable-")
        private val createdAbsoluteDirectory = directory.toAbsolutePath().normalize()
        private val createdRealDirectory = directory.toRealPath()
        private val systemTempDirectory = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()
        private val systemRealTempDirectory = systemTempDirectory.toRealPath()
        private val sessions = DesktopSessionStore.temporary()
        private val repository = DesktopRepository(sessions)
        private val failures = CopyOnWriteArrayList<Throwable>()
        private val job = SupervisorJob()
        private val scope = CoroutineScope(job + Dispatchers.Swing + CoroutineExceptionHandler { _, failure -> failures += failure })
        private val context = DesktopPluginContext(DesktopPluginStore(directory.resolve("settings")))
        val player = MpvPlayer()
        private val transport: OkHttpClient
        val epoch: Long
        private var rootCurrent = true
        private var retired = false
        lateinit var owner: DesktopOriginalVideoOwnerAssembly
            private set
        init {
            sessions.saveAccount(mapOf("SESSDATA" to "synthetic-subtitle", "bili_jct" to "synthetic-subtitle-csrf"),
                AccountSummary(41L, "Synthetic subtitle owner", ""))
            epoch = repository.sessionEpoch
            transport = repository.httpClient.newBuilder().retryOnConnectionFailure(false).addInterceptor {
                error("Subtitle presentation fixture cannot access an HTTP service")
            }.build()
            DesktopRepository::class.java.getDeclaredField("client").apply { isAccessible = true }.set(repository, transport)
        }
        private fun owns() = rootCurrent && job.isActive && repository.sessionEpoch == epoch
        private fun commit(action: () -> Unit): Boolean = if (!owns()) false else { action(); true }
        fun admit(action: () -> Unit): Boolean {
            check(EventQueue.isDispatchThread())
            return repository.withPrimaryPlaybackAdmission(epoch, owner::owns) { commit(action) }
        }
        fun initialize() = edt {
            val settings = DesktopOriginalPlayerSettingsContext(context, ::owns, ::commit,
                largeScreenOrFoldableConfiguration = { false }, isDebugBuild = { false })
            val readbacks = DesktopOriginalVideoEntryReadbacks(repository, epoch, scope, ::owns, ::commit)
            val operations = DesktopDynamicCardOperations(repository, epoch, ::owns)
            val comments = Proxy.newProxyInstance(DesktopVideoCommentRequests::class.java.classLoader,
                arrayOf(DesktopVideoCommentRequests::class.java)) { _, method, _ ->
                when (method.name) { "isOwned" -> owns(); "currentMid" -> 41L; else -> error("No comment operation ${method.name}") }
            } as DesktopVideoCommentRequests
            val root = DesktopOriginalCommentRootOwner(operations, comments, scope, operations.emotes, ::owns,
                { error("No subtitle fixture feedback") })
            val plugins = Proxy.newProxyInstance(DesktopOriginalVideoOwnerPlugins::class.java.classLoader,
                arrayOf(DesktopOriginalVideoOwnerPlugins::class.java)) { _, method, _ ->
                when (method.name) { "capturePlaybackDispatch" -> null; "enabledPlayerPlugins" -> emptyList<PlayerPlugin>()
                    else -> error("No pre-media plugin operation ${method.name}") }
            } as DesktopOriginalVideoOwnerPlugins
            val network = object : DesktopOriginalVideoOwnerNetwork {
                override fun isMobileData() = false
                override fun isWifi() = true
                override fun cdnNetwork() = error("No subtitle fixture media transport")
            }
            val cache = object : DesktopOriginalVideoOwnerCache {
                override fun get(bvid: String, cid: Long): PlayUrlData? = null
                override fun invalidate(bvid: String, cid: Long) = error("No subtitle fixture cache mutation")
            }
            val effects = DesktopOriginalVideoOwnerEffects(unused(), unused(), unused(), network, cache,
                unused(), unused(), unused(), MutableStateFlow(false))
            val progress = object : DesktopOriginalVideoProgressPort {
                override fun getCachedPosition(bvid: String, cid: Long) = 0L
                override fun savePosition(bvid: String, cid: Long, positionMs: Long) = error("No accepted source progress")
            }
            val capabilities = object : DesktopOriginalVideoPlaybackCapabilities {
                override fun isHevcSupported() = false
                override fun isAv1Supported() = false
                override fun isHdrSupported() = false
                override fun isDolbyVisionSupported() = false
                override fun isDolbyAtmosAudioSupported() = false
                override fun isDolbySoftwareAudioDecoderRequired() = false
            }
            owner = DesktopOriginalVideoOwnerAssembly.create(context, settings, repository, epoch, scope,
                ::owns, ::commit, player, DesktopRepositoryPlaybackPublication(repository), { false }, MutableStateFlow(null),
                { error("No accepted native publication") }, { _, _ -> error("No accepted media byte admission") },
                captureInvocation = { _, _ -> error("No subtitle presentation playback request") }, status = readbacks,
                prepareAcceptedMedia = { error("No accepted media") }, createPlugins = { _, _ -> plugins },
                confirmFollow = { error("No follow mutation") }, feedback = { error("No UI feedback") },
                readHasPrimarySession = readbacks::hasPrimarySession, readHasPrimaryAccessToken = readbacks::hasPrimaryAccessToken,
                readPrimaryMid = readbacks::primaryMid, readOwnedCooldown = readbacks::cooldownRemainingMs,
                progress = progress, capabilities = capabilities,
                applyPreferredVolume = { check(it.currentNativeSourceVersion() == null) },
                dashSegmentRequestsEnabled = { false }, logSeek = { _, _, _, _ -> error("No native seek") },
                diagnosticLoggingEnabled = { false }, resumeEndedSource = { error("No native restart") },
                root = root, interactionAnalytics = unused(), effects = effects,
                todayWatchFeedback = DesktopTodayWatchFeedbackWriteBinding(context, ::owns, ::commit))
        }
        suspend fun retire() = withContext(NonCancellable) {
            if (!retired) {
                retired = true
                try { edt { rootCurrent = false; if (::owner.isInitialized) owner.close() } }
                finally { job.cancelAndJoin() }
            } else {
                // A timed-out case cannot let its already-cancelled child skip
                // drainage before the real temporary store is deleted.
                job.cancelAndJoin()
            }
        }
        suspend fun close() = withContext(NonCancellable) {
            try { retire() }
            finally {
                try { edt { player.close() } }
                finally {
                    try { transport.dispatcher.executorService.shutdownNow() }
                    finally { transport.connectionPool.evictAll(); deleteOnlyCreatedFixtureDirectory() }
                }
            }
            assertTrue(failures.isEmpty(), failures.toString())
        }
        private fun deleteOnlyCreatedFixtureDirectory() {
            check(directory.toAbsolutePath().normalize() == createdAbsoluteDirectory)
            check(createdAbsoluteDirectory.parent == systemTempDirectory && createdRealDirectory.parent == systemRealTempDirectory)
            check(createdAbsoluteDirectory != systemTempDirectory)
            check(createdAbsoluteDirectory.fileName.toString().startsWith("subtitle-actual-saveable-"))
            fun validate(path: Path, attrs: BasicFileAttributes) {
                val absolute = path.toAbsolutePath().normalize()
                check(absolute.startsWith(createdAbsoluteDirectory) && !attrs.isSymbolicLink && !attrs.isOther)
                check(path.toRealPath() == absolute)
                if (System.getProperty("os.name").startsWith("Windows")) {
                    val flags = Files.getAttribute(path, "dos:attributes", LinkOption.NOFOLLOW_LINKS) as Int
                    check(flags and 0x400 == 0) { "Refusing fixture cleanup through a reparse point: $path" }
                }
            }
            check(directory.toRealPath() == createdRealDirectory)
            val paths = mutableListOf<Path>()
            Files.walkFileTree(directory, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    validate(dir, attrs); paths.add(dir); return FileVisitResult.CONTINUE
                }
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    validate(file, attrs); paths.add(file); return FileVisitResult.CONTINUE
                }
            })
            paths.sortedByDescending { it.nameCount }.forEach { path ->
                check(directory.toRealPath() == createdRealDirectory)
                validate(path, Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS))
                Files.delete(path)
            }
        }
    }

    companion object {
        private inline fun <reified T> unused(): T = Proxy.newProxyInstance(T::class.java.classLoader,
            arrayOf(T::class.java)) { _, method, _ -> error("Unexpected ${T::class.java.simpleName}.${method.name}") } as T
        private fun <T> edt(action: () -> T): T {
            if (EventQueue.isDispatchThread()) return action()
            var outcome: Result<T>? = null
            EventQueue.invokeAndWait { outcome = runCatching(action) }
            return checkNotNull(outcome).getOrThrow()
        }
    }
}
