package com.bilipai.desktop

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.bilipai.desktop.data.DesktopRepository
import com.bilipai.desktop.data.DesktopSessionStore
import com.bilipai.desktop.player.MpvPlayer
import com.bilipai.desktop.player.MpvStartupProbe
import com.bilipai.desktop.update.DesktopUpdater
import com.bilipai.desktop.update.UpdateStorage
import com.bilipai.desktop.diagnostics.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.awt.Dimension

fun main(args: Array<String>) {
    if (args.firstOrNull() == "--backend-smoke") {
        val smokeResult = runCatching {
            runBlocking {
                val guestDirectory = java.nio.file.Files.createTempDirectory("BiliPai-network-smoke-")
                val guestSession = guestDirectory.resolve("session.json")
                try {
                    val repository = DesktopRepository(DesktopSessionStore(guestSession))
                    println("BACKEND_SMOKE_STAGE popular")
                    val popular = repository.popular()
                    require(popular.isNotEmpty()) { "Popular feed was empty" }
                    println("BACKEND_SMOKE_STAGE search")
                    val search = repository.search("哔哩哔哩")
                    require(search.isNotEmpty()) { "Search was empty" }
                    println("BACKEND_SMOKE_STAGE details")
                    val details = repository.videoDetails(popular.first().bvid)
                    println("BACKEND_SMOKE_STAGE playback")
                    val playback = repository.playback(details)
                    require(playback.videoUrl.startsWith("https://"))
                    println("BACKEND_SMOKE_OK feed=${popular.size} search=${search.size} parts=${details.pages.size} dashAudio=${playback.audioUrl != null} quality=${playback.quality}")
                } finally {
                    java.nio.file.Files.deleteIfExists(guestSession)
                    java.nio.file.Files.deleteIfExists(guestDirectory)
                }
            }
        }
        smokeResult.exceptionOrNull()?.printStackTrace()
        kotlin.system.exitProcess(if (smokeResult.isSuccess) 0 else 1)
    }
    if (args.firstOrNull() in setOf("--player-self-test", "--player-self-test-ci")) {
        require(args.size == 2) { "Usage: --player-self-test[-ci] <report-directory>" }
        val result = com.bilipai.desktop.player.PlayerSelfTest.run(java.io.File(args[1]),
            useNullAudioOutput = args.first() == "--player-self-test-ci")
        kotlin.system.exitProcess(if (result) 0 else 1)
    }
    val initialVideo = args.firstOrNull { it.startsWith("--video=") }?.substringAfter('=')
    if (DesktopUpdater.launchInstalledUpdateIfNewer(args)) return
    if (args.firstOrNull() == "--webdav-auto-backup") {
        val result = runBlocking {
            com.bilipai.desktop.backup.DesktopBackupCoordinator(
                com.bilipai.desktop.backup.DesktopBackupStore(DesktopLibrary.directoryForAccount(null))).automaticBackupIfDue()
        }
        if (result.isFailure) System.err.println("WebDAV scheduled backup failed; open backup settings for details.")
        kotlin.system.exitProcess(if (result.isSuccess) 0 else 1)
    }
    val healthIndex = args.indexOf("--update-health-file")
    val tokenIndex = args.indexOf("--update-health-token")
    val healthPath = args.getOrNull(healthIndex + 1)?.takeIf { healthIndex >= 0 }
    val healthToken = args.getOrNull(tokenIndex + 1)?.takeIf { tokenIndex >= 0 }
    application {
        val applicationPluginStore = remember {
            com.bilipai.desktop.plugins.DesktopPluginStore(DesktopLibrary.directoryForAccount(null)).also { store ->
                com.android.purebilibili.core.store.NetworkProxyStore.init(com.bilipai.desktop.plugins.DesktopPluginContext(store))
            }
        }
        val diagnosticsResult = remember(applicationPluginStore) {
            openDesktopDiagnostics(applicationPluginStore,
                runCatching { DesktopUpdater.packagedVersion() }.getOrDefault("unpackaged-development"))
        }
        val diagnostics = diagnosticsResult.getOrNull()
        val diagnosticWindow = remember { java.util.concurrent.atomic.AtomicReference<java.awt.Window?>(null) }
        val nativeCrashShare = remember(diagnostics) {
            diagnostics?.let { actor ->
                DesktopNativeCrashShare(
                    nativeDll = {
                        java.nio.file.Path.of(requireNotNull(System.getProperty("compose.application.resources.dir")),
                            "native", "windows-x64", "bilipai-diagnostic-share.dll")
                    },
                    expectedSha256 = DesktopNativeDiagnosticShareAssetHash.sha256,
                    window = diagnosticWindow::get, diagnostics = actor)
            }
        }
        val diagnosticLifecycle = remember(diagnostics, nativeCrashShare) {
            diagnostics?.let { DesktopDiagnosticLifecycle(it, nativeCrashShare) }
        }
        val diagnosticStartupError = if (diagnosticsResult.isFailure)
            "诊断配置无法读取，请检查配置并重新启动。" else null
        val diagnosticHandler = remember(diagnostics) {
            diagnostics?.let {
                val previous = Thread.getDefaultUncaughtExceptionHandler()
                val handler = DesktopDiagnosticUncaughtHandler(it, previous)
                Thread.setDefaultUncaughtExceptionHandler(handler)
                previous to handler
            }
        }
        DisposableEffect(diagnosticHandler) {
            onDispose {
                diagnosticHandler?.let { (previous, handler) ->
                    if (Thread.getDefaultUncaughtExceptionHandler() === handler)
                        Thread.setDefaultUncaughtExceptionHandler(previous)
                }
            }
        }
        val repository = remember { DesktopRepository() }
        LaunchedEffect(repository) { diagnostics?.recordStartupStage("repository_initialized") }
        val playerResult = remember { runCatching { MpvPlayer() } }
        val windowState = rememberWindowState(width = 1360.dp, height = 900.dp)
        val applicationScope = rememberCoroutineScope()
        val shutdown = remember(diagnosticLifecycle) {
            java.util.concurrent.atomic.AtomicReference<suspend () -> Unit>({ diagnosticLifecycle?.shutdownForRestore() })
        }
        val closing = remember { java.util.concurrent.atomic.AtomicBoolean() }
        // Configure the original singleton before any Window renderer asks Coil for an image.
        val applicationImages = remember(repository, closing) {
            com.bilipai.desktop.ui.DesktopApplicationImageLoader(repository,
                DesktopLibrary.directoryForAccount(null).resolve("cache"), rootAlive = { !closing.get() })
        }
        DisposableEffect(applicationImages) {
            onDispose {
                applicationImages.stopAccepting()
                applicationScope.launch(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { applicationImages.close() }
            }
        }
        var restartFailure by remember { mutableStateOf<String?>(null) }
        fun closeApp(restart: Boolean = false) {
            val restartPlan = if (restart) try { DesktopApplicationRestart.prepare() }
                catch (failure: Exception) { restartFailure = failure.message ?: "重启准备失败"; return }
                else null
            if (closing.compareAndSet(false, true)) applicationScope.launch {
                try {
                    shutdown.get().invoke()
                    withContext(Dispatchers.IO) { applicationImages.close() }
                    if (restartPlan != null) withContext(Dispatchers.IO) { restartPlan.launch() }
                    playerResult.getOrNull()?.close()
                    exitApplication()
                } catch (failure: Exception) {
                    closing.set(false)
                    if (restart) restartFailure = "重启未成功，请关闭客户端后从原文件夹重新打开。"
                    System.err.println("Application shutdown did not finish (${failure.javaClass.simpleName}).")
                }
            }
        }
        Window(
            onCloseRequest = { closeApp() },
            title = "BiliPai Windows",
            state = windowState,
            icon = painterResource("app-icon.png")
        ) {
            DisposableEffect(window, diagnosticWindow) {
                diagnosticWindow.set(window)
                onDispose { diagnosticWindow.compareAndSet(window, null) }
            }
            window.minimumSize = Dimension(960, 680)
            var rootFrameOwner by remember { mutableStateOf<(() -> Boolean)?>(null) }
            val startupHealthWritten = remember { java.util.concurrent.atomic.AtomicBoolean() }
            LaunchedEffect(Unit) {
                withFrameNanos { }
                diagnostics?.recordStartupStage("window_content_mounted")
            }
            LaunchedEffect(rootFrameOwner) {
                val ownsRoot = rootFrameOwner ?: return@LaunchedEffect
                // The real active Root page has composed and drawn; await its next frame.
                withFrameNanos { }
                if (closing.get() || !ownsRoot()) return@LaunchedEffect
                diagnostics?.recordStartupStage("original_root_content_mounted")
                if (healthPath != null && healthToken != null && playerResult.isSuccess && !startupHealthWritten.get()) {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            val root = UpdateStorage.verifiedRoot(java.nio.file.Path.of(
                                System.getenv("LOCALAPPDATA") ?: error("Windows local application data is unavailable."), "BiliPai", "updates"))
                            val requestedMarker = java.nio.file.Path.of(healthPath).toAbsolutePath().normalize()
                            val marker = UpdateStorage.existingPathWithoutLinks(requireNotNull(requestedMarker.parent)).resolve(requestedMarker.fileName)
                            require(marker.startsWith(root) && marker.fileName.toString() == "startup-health.txt")
                            val relative = root.relativize(marker)
                            require(relative.nameCount == 3 && relative.getName(0).toString().startsWith("staged-"))
                            val launchId = relative.getName(1).toString().removePrefix("launch-")
                            require(relative.getName(1).toString().startsWith("launch-") && java.util.UUID.fromString(launchId).toString() == launchId)
                            require(java.util.UUID.fromString(healthToken).toString() == healthToken)
                            var directory = root
                            for (part in relative.toList().dropLast(1)) {
                                directory = directory.resolve(part)
                                require(!java.nio.file.Files.isSymbolicLink(directory) &&
                                    java.nio.file.Files.isDirectory(directory, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                            }
                            MpvStartupProbe.verify()
                            check(!closing.get() && ownsRoot()) { "Application Root retired before startup verification" }
                            java.nio.file.Files.writeString(marker.resolveSibling("startup-version.txt"), DesktopUpdater.packagedVersion(),
                                java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE)
                            java.nio.file.Files.writeString(marker, healthToken, java.nio.file.StandardOpenOption.CREATE_NEW,
                                java.nio.file.StandardOpenOption.WRITE)
                            startupHealthWritten.set(true)
                        }
                    }.onFailure { System.err.println("Update startup verification failed: ${it.message}") }
                }
            }
            val danmakuPresentation = com.bilipai.desktop.ui.rememberDesktopWindowsDanmakuPresentation(window, windowState)
            val fullscreenControl = com.bilipai.desktop.ui.rememberDesktopWindowsFullscreenControl(window, windowState)
            androidx.compose.runtime.CompositionLocalProvider(
                com.bilipai.desktop.ui.LocalDesktopApplicationImageLoader provides applicationImages,
            ) {
            DesktopApp(repository, playerResult.getOrNull(), playerResult.exceptionOrNull()?.message, initialVideo,
                onExit = { closeApp() }, onToggleFullscreen = fullscreenControl::toggle,
                hostWindow = window, registerShutdown = shutdown::set, onRestart = { closeApp(restart = true) },
                applicationPluginStore = applicationPluginStore, isClosing = closing::get,
                diagnosticLifecycle = diagnosticLifecycle, diagnosticStartupError = diagnosticStartupError,
                danmakuPresentation = danmakuPresentation,
                isFullscreen = { windowState.placement == WindowPlacement.Fullscreen },
                setFullscreen = fullscreenControl::setFullscreen,
                onRootContentFrame = { owner ->
                    if (!closing.get() && owner()) rootFrameOwner = owner
                })
            }
            restartFailure?.let { message ->
                androidx.compose.material3.AlertDialog(onDismissRequest = { restartFailure = null },
                    title = { androidx.compose.material3.Text("客户端重启") },
                    text = { androidx.compose.material3.Text(message) },
                    confirmButton = { androidx.compose.material3.TextButton(onClick = { restartFailure = null }) { androidx.compose.material3.Text("关闭") } })
            }
        }
    }
}
