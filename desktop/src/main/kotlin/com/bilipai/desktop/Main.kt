package com.bilipai.desktop

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.awt.Dimension

fun main(args: Array<String>) {
    if (args.firstOrNull() == "--backend-smoke") {
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
        return
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
        val repository = remember { DesktopRepository() }
        val playerResult = remember { runCatching { MpvPlayer() } }
        val windowState = rememberWindowState(width = 1360.dp, height = 900.dp)
        val applicationScope = rememberCoroutineScope()
        val shutdown = remember { java.util.concurrent.atomic.AtomicReference<suspend () -> Unit>({}) }
        val closing = remember { java.util.concurrent.atomic.AtomicBoolean() }
        fun closeApp() {
            if (closing.compareAndSet(false, true)) applicationScope.launch {
                try { shutdown.get().invoke(); playerResult.getOrNull()?.close(); exitApplication() }
                catch (failure: Exception) { closing.set(false); System.err.println("Application shutdown did not finish (${failure.javaClass.simpleName}).") }
            }
        }
        Window(
            onCloseRequest = { closeApp() },
            title = "BiliPai Windows",
            state = windowState,
            icon = painterResource("app-icon.png")
        ) {
            window.minimumSize = Dimension(960, 680)
            LaunchedEffect(Unit) {
                withFrameNanos { }
                if (healthPath != null && healthToken != null && playerResult.isSuccess) {
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
                            java.nio.file.Files.writeString(marker.resolveSibling("startup-version.txt"), DesktopUpdater.packagedVersion(),
                                java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE)
                            java.nio.file.Files.writeString(marker, healthToken, java.nio.file.StandardOpenOption.CREATE_NEW,
                                java.nio.file.StandardOpenOption.WRITE)
                        }
                    }.onFailure { System.err.println("Update startup verification failed: ${it.message}") }
                }
            }
            DesktopApp(repository, playerResult.getOrNull(), playerResult.exceptionOrNull()?.message, initialVideo,
                onExit = { closeApp() }, onToggleFullscreen = {
                    windowState.placement = if (windowState.placement == WindowPlacement.Fullscreen) WindowPlacement.Floating else WindowPlacement.Fullscreen
                }, hostWindow = window, registerShutdown = shutdown::set)
        }
    }
}
