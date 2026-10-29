@file:OptIn(androidx.compose.runtime.tooling.ComposeToolingApi::class, androidx.compose.runtime.ExperimentalComposeRuntimeApi::class)
package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import androidx.compose.runtime.tooling.*
import com.android.purebilibili.navigation3.BiliPaiNavKey
import com.bilipai.desktop.player.MpvPlayer
import com.bilipai.desktop.player.PlayerPreferences
import com.bilipai.desktop.player.PlayerPreferencesStore
import com.bilipai.desktop.settings.DESKTOP_SETTINGS_RAIL_CATEGORIES
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.awt.*
import java.awt.event.WindowEvent
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import javax.swing.JFrame

private class ProductReferences(private val scope: CoroutineScope) : AutoCloseable {
    private val compositions = CopyOnWriteArraySet<ObservableComposition>()
    private val handles = CopyOnWriteArraySet<CompositionObserverHandle>()
    private val attached = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<RecomposerInfo, Boolean>())
    private val job = scope.launch {
        Recomposer.runningRecomposers.collect { infos ->
            withContext(Dispatchers.Main) {
                infos.forEach { info -> if (attached.add(info)) {
                    handles += info.observe(object : CompositionRegistrationObserver {
                        override fun onCompositionRegistered(composition: ObservableComposition) { compositions += composition }
                        override fun onCompositionUnregistered(composition: ObservableComposition) { compositions -= composition }
                    })
                } }
            }
        }
    }
    fun values(): List<Any> {
        check(SwingUtilities.isEventDispatchThread())
        val values = mutableListOf<Any>()
        fun add(value: Any?, depth: Int = 0) {
            if (value == null || depth > 2) return
            values += value
            if (value is AtomicReference<*>) add(value.get(), depth + 1)
            // Compose wraps only RememberObserver entries; inspect the wrapper,
            // never mutate it or recursively walk unrelated actor fields.
            if (value.javaClass.name.startsWith("androidx.compose.runtime.") && value.javaClass.simpleName.endsWith("Holder")) {
                value.javaClass.declaredFields.firstOrNull { it.name in setOf("wrapped", "value", "instance") }?.let {
                    it.isAccessible = true; add(it.get(value), depth + 1)
                }
            }
        }
        compositions.forEach { composition ->
            val getter = composition.javaClass.methods.firstOrNull { it.name.startsWith("getSlotStorage") && it.parameterCount == 0 }
                ?: return@forEach
            val storage = getter.invoke(composition) ?: return@forEach
            val slotsGetter = storage.javaClass.methods.filter { it.name == "getSlots" && it.parameterCount == 0 }
                .let { methods -> methods.firstOrNull { Iterable::class.java.isAssignableFrom(it.returnType) } ?: methods.first() }
                .apply { isAccessible = true }
            val rawSlots = slotsGetter.invoke(storage)
            val slots = when (rawSlots) {
                is Iterable<*> -> rawSlots
                is Array<*> -> rawSlots.asList()
                else -> error("Unsupported readonly Compose slot carrier: " + rawSlots?.javaClass?.name)
            }
            slots.forEach { add(it) }
        }
        return values
    }
    inline fun <reified T> find(): T? = values().filterIsInstance<T>().firstOrNull()
    override fun close() { job.cancel(); handles.forEach { it.dispose() }; handles.clear(); compositions.clear() }
}
/** One actual Main, fresh guest profile, public media. Only real route commands
 * and owner retirement are used; no replacement player, state, HTTP or credentials.
 * Run only through BiliPai-MacTest on winbox. Not a headless JVM unit test. */
object WindowsVideoCrashRegressionFixture {
    private val latest = AtomicReference<DesktopOriginalRootValidationTap.Frame?>()
    private fun <T> edt(block: () -> T): T {
        if (EventQueue.isDispatchThread()) return block()
        val result = AtomicReference<Result<T>>()
        EventQueue.invokeAndWait { result.set(runCatching(block)) }
        return result.get().getOrThrow()
    }
    private fun await(label: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + 90_000_000_000L
        while (System.nanoTime() < deadline) {
            if (edt(predicate)) return
            Thread.sleep(100)
        }
        error("Timed out: $label")
    }
    private fun window() = Window.getWindows().filterIsInstance<JFrame>()
        .single { it.isShowing && it.title == "BiliPai Windows" }
    private fun components(root: Component): List<Component> = listOf(root) +
        if (root is Container) root.components.flatMap(::components) else emptyList()
    private fun player(): MpvPlayer? {
        val canvas = components(window()).filterIsInstance<Canvas>().firstOrNull {
            it.isShowing && it.javaClass.enclosingClass == MpvPlayer::class.java
        } ?: return null
        return canvas.javaClass.declaredFields.single { it.type == MpvPlayer::class.java }
            .apply { isAccessible = true }.get(canvas) as MpvPlayer
    }
    private fun shot(report: Path, name: String) = edt {
        val frame = window()
        check(frame.isActive) { "Owned window is not foreground" }
        ImageIO.write(Robot().createScreenCapture(frame.bounds), "png", report.resolve("$name.png").toFile())
    }
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 2)
        val report = Path.of(args[0]).toRealPath()
        val video = args[1]
        require(Regex("BV[0-9A-Za-z]{10}").matches(video))
        val local = Path.of(requireNotNull(System.getenv("LOCALAPPDATA")))
        require(!Files.exists(local.resolve("BiliPai/session.json")))
        require(!Files.exists(local.resolve("BiliPaiWindows/session.json")))
        PlayerPreferencesStore().save(PlayerPreferences(volume = 0.0, muted = true))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val rows = mutableListOf<JsonObject>()
        var succeeded = false
        DesktopOriginalRootValidationTap.install { latest.set(it) }.use {
            ProductReferences(scope).use { refs ->
                Thread({
                    try {
                        await("first drawn Root") { latest.get() != null }
                        val routes = edt { checkNotNull(latest.get()).routes }
                        repeat(5) { index ->
                            val started = System.nanoTime()
                            edt { routes.video(BiliPaiNavKey.VideoDetail(video)) }
                            await("public video first frame") {
                                (latest.get()?.key as? BiliPaiNavKey.VideoDetail)?.bvid == video &&
                                    player()?.state?.value?.firstVideoFrameReady == true
                            }
                            val elapsed = (System.nanoTime() - started) / 1_000_000
                            shot(report, "video-${index + 1}")
                            edt {
                                val shell = checkNotNull(refs.find<DesktopOriginalVideoShellOwner>())
                                val owner = checkNotNull(shell.slot.currentAssembly())
                                val accepted = checkNotNull(owner.native.current())
                                val factory = shell.factoryFor(owner)
                                rows += buildJsonObject {
                                    put("open", index + 1); put("firstFrameMs", elapsed)
                                    put("nativeSourceVersion", accepted.nativeSource.sourceVersion)
                                    put("processAlive", true)
                                }
                                check(routes.back())
                                shell.slot.retireAssembly(owner)
                                // Precisely the stale DisposableEffect case: a UI reference
                                // outlives its permit, and must reject rather than crash.
                                check(shell.factoryFor(owner) === factory)
                                check(!factory.isPresentationCurrent(owner, accepted))
                                check(!factory.withPresentationAdmission(owner, accepted) { error("Retired command admitted") })
                            }
                            await("fresh factory") { refs.find<DesktopOriginalVideoShellOwner>()?.navigationReady() == true }
                            Files.writeString(report.resolve("video-results.json"), JsonArray(rows).toString())
                        }
                        edt { window().extendedState = JFrame.MAXIMIZED_BOTH; check(routes.push(BiliPaiNavKey.Settings)) }
                        await("settings root") { latest.get()?.key == BiliPaiNavKey.Settings }
                        for (category in DESKTOP_SETTINGS_RAIL_CATEGORIES) {
                            val key = BiliPaiNavKey.SettingsCategory(category)
                            edt { check(routes.push(key)) }
                            await("settings category $category") { latest.get()?.key == key }
                            Thread.sleep(800)
                            shot(report, "settings-${category.name}")
                        }
                        Files.writeString(report.resolve("result.json"), buildJsonObject {
                            put("videoOpens", rows.size); put("firstFrames", rows.size)
                            put("settingsSections", DESKTOP_SETTINGS_RAIL_CATEGORIES.size)
                            put("crashes", 0); put("freshGuestProfile", true)
                            put("source", "actual Main, public video, native playback-restart")
                        }.toString())
                        succeeded = true
                        edt { window().dispatchEvent(WindowEvent(window(), WindowEvent.WINDOW_CLOSING)) }
                    } catch (failure: Throwable) {
                        failure.printStackTrace()
                        Files.writeString(report.resolve("failure.txt"), failure.stackTraceToString())
                        kotlin.system.exitProcess(91)
                    }
                }, "TON1077 actual Main regression").apply { isDaemon = true; start() }
                com.bilipai.desktop.main(emptyArray())
                scope.cancel()
                check(succeeded) { "Main exited before regression completed" }
            }
        }
    }
}
