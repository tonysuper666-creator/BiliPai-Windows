@file:OptIn(androidx.compose.runtime.tooling.ComposeToolingApi::class, androidx.compose.runtime.ExperimentalComposeRuntimeApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
package com.bilipai.desktop.rootidlefixture

import androidx.compose.runtime.*
import androidx.compose.runtime.tooling.*
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import com.android.purebilibili.feature.audio.player.AudioNowPlayingSession
import com.android.purebilibili.feature.video.viewmodel.VideoPlaybackUiState
import com.android.purebilibili.navigation3.BiliPaiNavKey
import com.bilipai.desktop.DesktopApp
import com.bilipai.desktop.data.*
import com.bilipai.desktop.player.*
import com.bilipai.desktop.plugins.*
import com.bilipai.desktop.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skiko.SkiaLayer
import java.awt.Component
import java.awt.Container
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities

/** This class observes actual remembered product references. It does not replace
 * any product class, write Compose slots, inject UI state, or manufacture an API. */
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
            val slots = storage.javaClass.methods.first { it.name == "getSlots" && it.parameterCount == 0 }.apply { isAccessible = true }
                .invoke(storage) as Iterable<*>
            slots.forEach { add(it) }
        }
        return values
    }
    inline fun <reified T> find(): T? = values().filterIsInstance<T>().firstOrNull()
    fun carrierFlags(): List<Boolean> {
        val mapType = Class.forName("androidx.compose.runtime.CompositionLocalMap")
        val get = mapType.getMethod("get", CompositionLocal::class.java)
        return values().filter { mapType.isInstance(it) }.mapNotNull { map ->
            // Absent keys in unrelated product groups are not the ordinary
            // carrier context. Reading its default there intentionally errors.
            runCatching { get.invoke(map, LocalDesktopOriginalVideoNativeCarrierActive) as Boolean }.getOrNull()
        }
    }
    override fun close() { job.cancel(); handles.forEach { it.dispose() }; handles.clear(); compositions.clear() }
}

private fun readField(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
private fun atomicField(owner: Any, name: String): Any? = (readField(owner, name) as AtomicReference<*>).get()
private fun findLayer(component: Component): SkiaLayer? = when (component) {
    is SkiaLayer -> component
    is Container -> component.components.firstNotNullOfOrNull(::findLayer)
    else -> null
}
private fun countCanvas(component: Component, canvas: java.awt.Canvas): Int =
    (if (component === canvas) 1 else 0) + if (component is Container) component.components.sumOf { countCanvas(it, canvas) } else 0
private fun findCanvas(component: Component): java.awt.Canvas? = when (component) {
    is java.awt.Canvas -> component
    is Container -> component.components.firstNotNullOfOrNull(::findCanvas)
    else -> null
}

private class RootIdleFixture(private val root: Path) {
    private val closing = AtomicBoolean(false)
    private val fixtureJob = SupervisorJob()
    private val fixtureScope = CoroutineScope(fixtureJob + Dispatchers.Default)
    private val refs = ProductReferences(fixtureScope)
    private val shutdown = AtomicReference<suspend () -> Unit>()
    private val windowRef = AtomicReference<ComposeWindow>()
    private val exit = AtomicReference<() -> Unit>()
    private val checks = mutableListOf<JsonObject>()
    private val sessions = DesktopSessionStore(root.resolve("isolated-session.json"), persistent = false)
    private val repository = DesktopRepository(sessions)
    private val store = DesktopPluginStore(root.resolve("isolated-plugin-store"))
    private val player = MpvPlayer(useNullAudioOutput = true)
    private val images = DesktopApplicationImageLoader(repository, root.resolve("cache")) { !closing.get() }

    private fun checkThat(name: String, result: Boolean) {
        checks += buildJsonObject { put("name", name); put("passed", result) }
        println("${if (result) "PASS" else "FAIL"} $name")
        check(result) { name }
    }
    private suspend fun <T : Any> awaitReference(name: String, read: () -> T?): T = withTimeout(45_000) {
        while (true) { withContext(Dispatchers.Main) { read() }?.let { return@withTimeout it }; delay(50) }
        @Suppress("UNREACHABLE_CODE") error(name)
    }
    private suspend fun awaitCondition(name: String, read: () -> Boolean) = withTimeout(20_000) {
        while (!withContext(Dispatchers.Main) { read() }) delay(50)
    }
    private fun capture(name: String) {
        check(SwingUtilities.isEventDispatchThread())
        val window = checkNotNull(windowRef.get())
        window.renderImmediately()
        val bitmap = checkNotNull(findLayer(window)?.screenshot())
        try { Image.makeFromBitmap(bitmap).use { image ->
            checkNotNull(image.encodeToData(EncodedImageFormat.PNG)).use { data -> Files.write(root.resolve(name + ".png"), data.bytes) }
        } } finally { bitmap.close() }
    }
    private suspend fun closeProduct() {
        if (closing.compareAndSet(false, true)) {
            withContext(NonCancellable) {
                withContext(Dispatchers.Main) {
                    checkNotNull(shutdown.get()) { "Product did not register its real shutdown" }.invoke()
                }
                withContext(Dispatchers.IO) { images.close(); player.close() }
            }
        }
    }
    fun mount() = application(exitProcessOnExit = false) {
        val state = rememberWindowState(width = 1100.dp, height = 760.dp,
            position = WindowPosition(80.dp, 80.dp))
        Window(onCloseRequest = { fixtureScope.launch { closeProduct(); withContext(Dispatchers.Main) { exit.get()?.invoke() } } },
            title = "BiliPai fixture-owned actual Root idle lifecycle", state = state) {
            val actualWindow = window
            val presentation = rememberDesktopWindowsDanmakuPresentation(actualWindow, state)
            val fullscreen = rememberDesktopWindowsFullscreenControl(actualWindow, state)
            SideEffect { windowRef.set(actualWindow); exit.set { exitApplication() } }
            CompositionLocalProvider(LocalDesktopApplicationImageLoader provides images) {
                DesktopApp(repository, player, null, null,
                    onExit = { fixtureScope.launch { closeProduct(); withContext(Dispatchers.Main) { exitApplication() } } },
                    onToggleFullscreen = fullscreen::toggle, hostWindow = actualWindow,
                    registerShutdown = shutdown::set,
                    onRestart = { error("Restart is outside this minimal isolated Root fixture") },
                    applicationPluginStore = store, isClosing = closing::get,
                    diagnosticLifecycle = null, diagnosticStartupError = null,
                    danmakuPresentation = presentation,
                    isFullscreen = { state.placement == WindowPlacement.Fullscreen },
                    setFullscreen = fullscreen::setFullscreen)
            }
            LaunchedEffect(Unit) { fixtureScope.launch { exercise() } }
        }
    }
    private suspend fun exercise() {
        var error: Throwable? = null
        try {
            val shell = awaitReference("actual product Shell") { refs.find<DesktopOriginalVideoShellOwner>() }
            awaitCondition("actual factory install") { shell.navigationReady() }
            val owner = withContext(Dispatchers.Main) { shell.slot.requireAssembly() }
            val platforms = awaitReference("actual remembered Window platforms") {
                if (shell.slot.currentAssembly() === owner) runCatching { shell.requireWindows() }.getOrNull() else null
            }
            val environment = awaitReference("actual Root Window environment") { refs.find<DesktopOriginalVideoRootWindowEnvironment>() }
            val assembler = awaitReference("actual retained assembler") { refs.find<DesktopOriginalVideoRootAssembler>() }
            val resources = awaitReference("actual resource view") { refs.find<DesktopOriginalVideoRootShellResources>() }
            val factory = shell.factoryFor(owner)
            withContext(Dispatchers.Main) {
                checkThat("Actual original idle Assembly and facade use the same retained object", shell.slot.currentAssembly() === owner && owner.owns())
                checkThat("No ordinary Success, confirmed subject or native publication was injected", owner.playback.uiState.value !is VideoPlaybackUiState.Success && owner.playback.subjectSnapshot.value == null && owner.native.current() == null)
                checkThat("Actual Section, Holder and Fullscreen share one native player and platform", owner.section.nativePlayer === player && platforms.holder.section === platforms.fullscreen.section)
                val sectionResources = readField(platforms.holder.section, "resources") as DesktopOriginalVideoSectionWindowsResources
                checkThat("Actual Section resources borrow the same Root Overlay and native owner", sectionResources.overlay === resources.overlay && sectionResources.native === owner.native)
            }
            withTimeout(20_000) { platforms.awaitNativeInitialization() }
            withContext(Dispatchers.Main) {
                val window = checkNotNull(windowRef.get())
                checkThat("Same actual MPV session initialized through the product bootstrap Canvas", player.state.value.ready && player.decoderCapabilities.value != null && SwingUtilities.getWindowAncestor(player.surface) === window && countCanvas(window, checkNotNull(findCanvas(player.surface))) == 1)
                checkThat("Idle original PiP and SMTC do not manufacture an active media source", !resources.pip.active.value && player.currentSourceSnapshot() == null)
                val flags = refs.carrierFlags()
                checkThat("Actual composition contains the required inactive Root and active bootstrap carrier contexts", flags.any { it } && flags.any { !it })
                capture("actual-idle-root")
                checkThat("Actual required route provider is mounted above the same Root", environment.currentKey() == BiliPaiNavKey.MainHost && environment.owns())
                checkThat("Actual typed Settings navigation is admitted", environment.commands.push(BiliPaiNavKey.Settings))
            }
            awaitCondition("actual Settings route") { environment.currentKey() == BiliPaiNavKey.Settings }
            withContext(Dispatchers.Main) {
                checkThat("Covering route retains exact original Assembly and Window platforms", shell.slot.currentAssembly() === owner && shell.requireWindows() === platforms && owner.owns())
                checkThat("Outgoing and incoming non-media route retain a single attached Canvas", countCanvas(checkNotNull(windowRef.get()), checkNotNull(findCanvas(player.surface))) == 1)
                capture("actual-settings-cover")
                checkThat("Actual typed back is admitted", environment.commands.back())
            }
            awaitCondition("actual Root return") { environment.currentKey() == BiliPaiNavKey.MainHost }
            val previousEpoch = repository.sessionEpoch
            withContext(Dispatchers.IO) { repository.logout() } // isolated GUEST -> fresh GUEST epoch only
            awaitCondition("retired exact owner and Window cleanup") {
                !owner.owns() && atomicField(factory, "built") == null && atomicField(assembler, "entry") == null &&
                    (readField(platforms, "closed") as AtomicBoolean).get()
            }
            withContext(Dispatchers.Main) {
                checkThat("Guest epoch invalidation retires exact construction and Window references", repository.account.value == null && repository.sessionEpoch != previousEpoch && !owner.owns() && !environment.owns())
                checkThat("Original global bar presentation is cleared on real account retirement", !AudioNowPlayingSession.active.value)
            }
            awaitCondition("fresh same Shell factory installed") { shell.navigationReady() }
            val next = withContext(Dispatchers.Main) { shell.slot.requireAssembly() }
            val nextPlatforms = awaitReference("fresh Window platforms") {
                if (shell.slot.currentAssembly() === next) runCatching { shell.requireWindows() }.getOrNull() else null
            }
            withContext(Dispatchers.Main) { checkThat("New idle Assembly exists only after old exact drain", next !== owner && next.owns() && next.section.nativePlayer === player && next.native.current() == null) }
            closeProduct()
            withContext(Dispatchers.Main) {
                checkThat("Real registered Root shutdown joins and clears retained ordinary/window refs", shell.slot.assemblies.value == null && !shell.slot.factoryReady.value && atomicField(shell, "bound") == null && (readField(nextPlatforms, "closed") as AtomicBoolean).get())
                checkThat("Same native session is closed and capability snapshot released", player.decoderCapabilities.value == null && !next.owns())
            }
        } catch (failure: Throwable) { error = failure; failure.printStackTrace() }
        finally {
            try { closeProduct() } catch (failure: Throwable) { if (error == null) error = failure else error.addSuppressed(failure) }
            withContext(Dispatchers.Main) { refs.close() }
            val origins = listOf(DesktopOriginalVideoShellOwner::class.java, DesktopOriginalVideoRootAssembler::class.java,
                DesktopOriginalVideoOwnerAssembly::class.java, DesktopOriginalVideoSectionWindowsPlatform::class.java,
                DesktopOriginalVideoRootWindowPlatformsImpl::class.java, MpvPlayer::class.java)
            val result = buildJsonObject {
                put("status", if (error == null) "PASS_IDLE_ACTUAL_ROOT" else "FAIL")
                put("assertions", checks.size); put("checks", JsonArray(checks)); put("error", error?.javaClass?.name.orEmpty())
                put("classOrigins", buildJsonObject { origins.forEach { clazz -> put(clazz.name, buildJsonObject {
                    put("origin", clazz.protectionDomain.codeSource.location.toString())
                    val bytes = checkNotNull(clazz.getResourceAsStream("/" + clazz.name.replace('.', '/') + ".class")).use { it.readBytes() }
                    put("sha256Bytes", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
                }) } })
                put("productionOverrides", 0); put("seededSuccess", false); put("realAccount", false); put("globalInput", false)
                put("unchangedMainEntry", false); put("videoDetailSuccessAccepted", false); put("physicalVideoToVideoCarrierAccepted", false)
                put("PiPTransferAccepted", false); put("SMTCButtonAccepted", false)
            }
            Files.writeString(root.resolve("root-idle-proof.json"), result.toString())
            withContext(Dispatchers.Main) { exit.get()?.invoke() }
            fixtureJob.cancel()
        }
    }
}

fun main(args: Array<String>) {
    require(args.size == 1)
    val root = Path.of(args[0]).toAbsolutePath().normalize()
    require(Files.isDirectory(root))
    RootIdleFixture(root).mount()
}
