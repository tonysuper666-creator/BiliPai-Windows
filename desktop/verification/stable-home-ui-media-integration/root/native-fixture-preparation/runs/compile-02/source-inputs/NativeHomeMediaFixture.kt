@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
@file:Suppress("DEPRECATION")
package com.bilipai.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.bilipai.desktop.data.DesktopSessionStore
import com.bilipai.desktop.player.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.jetbrains.skia.EncodedImageFormat
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.Permission
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.math.abs

private val checks = mutableListOf<String>()
private fun verify(label: String, value: Boolean) { check(value) { label }; checks += label; println("PASS $label") }
private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
private fun mpvThreads() = Thread.getAllStackTraces().keys.filter { it.isAlive && it.name in setOf("BiliPai-native-player", "BiliPai-mpv-software-render") }.map { it.id }.toSet()
private suspend fun until(label: String, condition: () -> Boolean) {
    try { withTimeout(12_000L) { while (!condition()) delay(10L) } }
    catch (failure: TimeoutCancellationException) { error("Timeout: $label") }
}
private class LocalMediaFence : SecurityManager() {
    val networkAttempts = AtomicInteger()
    override fun checkPermission(permission: Permission) {}
    override fun checkConnect(host: String?, port: Int) { networkAttempts.incrementAndGet(); error("Native media fixture forbids Java network") }
    override fun checkConnect(host: String?, port: Int, context: Any?) = checkConnect(host, port)
    override fun checkListen(port: Int) { networkAttempts.incrementAndGet(); error("Native media fixture forbids listeners") }
    override fun checkExec(command: String) { error("Native media JVM cannot execute an external process") }
}
private class LocalMediaLifecycle : LifecycleOwner {
    private val registry = LifecycleRegistry.createUnsafe(this)
    override val lifecycle: Lifecycle get() = registry
    init { registry.currentState = Lifecycle.State.RESUMED }
}

/** Run ONLY against a subsequently installed immutable product graph. No candidate production override. */
fun main(args: Array<String>) = runBlocking {
    require(args.size == 3)
    val moving = Path.of(args[0]).toAbsolutePath().normalize()
    val blue = Path.of(args[1]).toAbsolutePath().normalize()
    val output = Path.of(args[2]).toAbsolutePath().normalize(); Files.createDirectories(output)
    require(Files.isRegularFile(moving) && Files.isRegularFile(blue))
    val fence = LocalMediaFence(); System.setSecurityManager(fence)
    val baseline = mpvThreads()
    val target = MpvSoftwareTarget().apply { resize(160, 90) }
    val player = MpvPlayer(softwareTarget = target, useNullAudioOutput = true)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    var media: DesktopHomeMediaLifetime? = null
    var scene: ImageComposeScene? = null
    val observations = mutableListOf<JsonObject>()
    var sceneFrames = 0
    var firstFrameElapsedNanos = 0L
    try {
        player.setMuted(true); player.setLoop(true); player.setSubtitlesVisible(false)
        val started = System.nanoTime()
        val v1 = player.loadVersioned(PlaybackSource(moving.toString(), referer = "", title = "Local moving raster"))
        player.startSoftwareTransport()
        val first = withTimeout(12_000L) { target.frames.first { it != null && it.sourceVersion == v1 } }!!
        firstFrameElapsedNanos = System.nanoTime() - started
        verify("first-frame uses successful copied CPU frame, not ready/playback-restart state", first.sequence > 0 && first.width == 160 && first.height == 90)
        verify("opaque BGRA real pixels", (0 until first.height).all { y -> (0 until first.width).all { x -> first.opaqueBgra[y * first.rowBytes + x * 4 + 3].toInt() and 255 == 255 } })
        val initialHash = hash(first.opaqueBgra)
        until("moving decoded video changes copied pixels") { target.frames.value?.let { it.sourceVersion == v1 && hash(it.opaqueBgra) != initialHash } == true }
        verify("real moving frames progress", target.frames.value!!.sequence > first.sequence)
        player.setPaused(true)
        until("actual native pause") { player.state.value.nativePaused == true }
        delay(200L); val pausedAt = player.state.value.positionSeconds; val pausedHash = hash(target.frames.value!!.opaqueBgra)
        delay(400L)
        verify("pause holds native position", abs(player.state.value.positionSeconds - pausedAt) < 0.10)
        verify("pause holds copied frame", hash(target.frames.value!!.opaqueBgra) == pausedHash)
        val seek = requireNotNull(player.seekToTracked(2.1))
        until("seek native receipt + real new raster") { player.state.value.seekCompletedId == seek && target.frames.value?.let { hash(it.opaqueBgra) != pausedHash } == true }
        verify("seek keeps same source token", player.ownsSourceVersion(v1) && target.frames.value!!.sourceVersion == v1)
        val v2 = player.loadVersioned(PlaybackSource(blue.toString(), referer = "", title = "Local blue replacement", startPaused = true))
        verify("replacement immediately rejects previous owner token", !player.ownsSourceVersion(v1) && player.ownsSourceVersion(v2))
        verify("replacement cannot retain old copied frame", target.frames.value?.sourceVersion?.let { it == v2 } != false)
        val blueFrame = withTimeout(12_000L) { target.frames.first { it != null && it.sourceVersion == v2 } }!!
        val index = (blueFrame.height / 2) * blueFrame.rowBytes + (blueFrame.width / 2) * 4
        verify("paused replacement renders actual new blue pixels", (blueFrame.opaqueBgra[index].toInt() and 255) > 200 && (blueFrame.opaqueBgra[index + 1].toInt() and 255) < 40 && (blueFrame.opaqueBgra[index + 2].toInt() and 255) < 40)
        player.setPaused(false)
        until("protected independent native source progresses") { player.state.value.nativePaused == false }
        val protectedVersion = v2
        val protectedSequence = target.frames.value!!.sequence

        // ACTUAL in-memory product SessionStore, guest generation. No real account/cookie path is read.
        val store = DesktopSessionStore(output.resolve("unused-session.json"), persistent = false)
        val stamp = requireNotNull(store.dynamicCacheOwner())
        val pageAlive = AtomicBoolean(true)
        val current = { pageAlive.get() && store.dynamicCacheOwner() == stamp }
        val commit: ((() -> Unit) -> Boolean) = { block ->
            var ran = false
            store.withCurrentDynamicCacheOwner(stamp) { if (pageAlive.get()) { block(); ran = true } }
            ran
        }
        media = DesktopHomeMediaLifetime(scope, current, commit) { uri ->
            val file = Path.of(java.net.URI(uri)); check(file == blue)
            PlaybackSource(file.toString(), referer = "", title = "Owned local Home texture")
        }
        val lifetime = requireNotNull(media)
        val callbackCount = AtomicInteger()
        val lifecycle = LocalMediaLifecycle()
        var ready by mutableStateOf(false)
        val ownScene = ImageComposeScene(width = 256, height = 128, coroutineContext = coroutineContext)
        scene = ownScene
        ownScene.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) {
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    DesktopHomeMpvTexture(lifetime, blue.toUri().toString(), true, true,
                        { callbackCount.incrementAndGet(); ready = true },
                        Modifier.offset(32.dp, 16.dp).size(192.dp, 96.dp).clip(RoundedCornerShape(20.dp)).graphicsLayer { alpha = if (ready) 0.5f else 0f },
                        { error("Actual texture failed: $it") })
                }
            }
        }
        suspend fun render(): java.awt.image.BufferedImage {
            val actualTime = System.nanoTime()
            sceneFrames++
            return ownScene.render(actualTime).use { image ->
                image.encodeToData(EncodedImageFormat.PNG)!!.use { data -> ImageIO.read(ByteArrayInputStream(data.bytes)) }
            }
        }
        withTimeout(12_000L) { while (callbackCount.get() == 0) { render(); delay(15L) } }
        repeat(5) { render(); delay(15L) }
        val composed = render()
        val center = java.awt.Color(composed.getRGB(128, 64), true)
        verify("actual Compose carries native blue with half alpha over white", center.blue > 230 && center.red in 100..155 && center.green in 100..155 && center.alpha == 255)
        verify("actual rounded clip preserves white corner", composed.getRGB(32, 16) == java.awt.Color.WHITE.rgb)
        verify("same source first-frame callback once", callbackCount.get() == 1)
        ImageIO.write(composed, "png", output.resolve("actual-offscreen-native-texture.png").toFile())
        store.logout() // Real guest -> new guest generation; same MID=0 epoch is retired.
        verify("actual SessionStore guest epoch changed", store.dynamicCacheOwner() != stamp && !current())
        val before = callbackCount.get()
        repeat(10) { render(); delay(15L) }
        val retired = render()
        verify("retired owner removes native pixels from Compose", retired.getRGB(128, 64) == java.awt.Color.WHITE.rgb)
        verify("retired owner cannot late callback", callbackCount.get() == before)
        lifetime.close()
        until("only private Home lease closes; protected native core stays live") { target.frames.value?.sequence?.let { it > protectedSequence } == true }
        verify("independent protected source remains owned", player.ownsSourceVersion(protectedVersion))
        verify("no Java external connection/listen", fence.networkAttempts.get() == 0)
        observations += buildJsonObject { put("phase", "nativeAndOffscreenCompose"); put("observedFirstCopiedFrameElapsedNanos", firstFrameElapsedNanos); put("firstCopiedFrameSha256Bytes", initialHash); put("replacementSourceVersion", v2); put("sceneFrames", sceneFrames); put("firstFrameCallbackCount", callbackCount.get()) }
    } finally {
        scene?.close()
        withContext(NonCancellable + Dispatchers.IO) { media?.close(); player.close() }
        scope.cancel()
    }
    until("managed client/render threads return after context/core cleanup") { mpvThreads() == baseline }
    verify("client + software render thread lifecycle returns to baseline", mpvThreads() == baseline)
    verify("closed transport clears frame mailbox", target.frames.value == null)
    val classes = listOf("com.bilipai.desktop.player.MpvPlayer", "com.bilipai.desktop.player.MpvNative", "com.bilipai.desktop.player.MpvSoftwareRenderer", "com.bilipai.desktop.player.MpvSoftwareTarget", "com.bilipai.desktop.player.MpvSoftwareFrame", "com.bilipai.desktop.ui.DesktopHomeMediaLifetime", "com.bilipai.desktop.ui.DesktopHomeMediaLease", "com.bilipai.desktop.ui.DesktopHomeOwnedMediaKt", "com.bilipai.desktop.data.DesktopSessionStore").map { name ->
        val c = Class.forName(name)
        buildJsonObject { put("class", name); put("codeSource", c.protectionDomain.codeSource.location.toString()); put("classSha256Bytes", hash(c.getResourceAsStream("/" + name.replace('.', '/') + ".class")!!.use { it.readBytes() })) }
    }
    Files.writeString(output.resolve("result.json"), buildJsonObject {
        put("status", "PASS"); put("assertions", checks.size); put("checks", JsonArray(checks.map(::JsonPrimitive))); put("observations", JsonArray(observations)); put("actualCodeSources", JsonArray(classes)); put("productionClassOverrides", 0); put("nativeWindowUsed", false); put("RootUiAccountPlaybackMounted", false); put("localNativeDecodeAndCpuFrame", true); put("offscreenComposeImageScope", true); put("screenMonitorFirstFrameObserved", false); put("javaNetworkAttempts", fence.networkAttempts.get()); put("nativeNetworkSources", 0); put("nativeSourceKind", "canonical fixed local files only"); put("liveRealAccountRead", false)
    }.toString())
    println("PASS ${checks.size} native/frame/isolated UI assertions")
}
