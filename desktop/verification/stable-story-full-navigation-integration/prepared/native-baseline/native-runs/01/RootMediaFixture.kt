@file:OptIn(androidx.compose.runtime.tooling.ComposeToolingApi::class, androidx.compose.runtime.ExperimentalComposeRuntimeApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
package com.bilipai.desktop.rootmediafixture

import androidx.compose.runtime.*
import androidx.compose.runtime.tooling.*
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import com.android.purebilibili.feature.audio.player.AudioNowPlayingSession
import com.android.purebilibili.feature.video.viewmodel.VideoPlaybackUiState
import com.android.purebilibili.navigation3.BiliPaiNavKey
import com.bilipai.desktop.DesktopApp
import com.android.purebilibili.feature.home.HomeCategory
import com.android.purebilibili.feature.home.resolveHomeCategoryVideoSourceRoute
import com.bilipai.desktop.data.*
import com.bilipai.desktop.diagnostics.*
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

private fun MpvPlayer.firstActualReadback(accepted:DesktopOriginalVideoAcceptedPublication):Boolean {
    val actual=currentSourceSnapshot() ?: return false
    return actual.sourceVersion==accepted.sourceVersion && actual.source.nativePublication===accepted.nativeSource.source.nativePublication &&
        state.value.firstVideoFrameReady && state.value.videoCodec!=null
}

private class RootMediaFixture(private val root: Path) {
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
    private val diagnostics = openDesktopDiagnostics(store, "actual-issued-media-fixture").getOrThrow()
    private val diagnosticLifecycle = DesktopDiagnosticLifecycle(diagnostics)
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
            title = "BiliPai fixture-owned actual guest media lifecycle", state = state) {
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
                    diagnosticLifecycle = diagnosticLifecycle, diagnosticStartupError = null,
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
            withTimeout(20_000) { platforms.awaitNativeInitialization() }
            val portrait = platforms.portrait
            val fixtureJob = checkNotNull(currentCoroutineContext()[Job])
            fun baseline(binding: DesktopOriginalVideoRepositoryBinding): Long {
                val map = readField(portrait, "captures") as java.util.IdentityHashMap<*,*>
                return readField(checkNotNull(map[binding]), "nativeBaseline") as Long
            }
            fun token(binding: DesktopOriginalVideoRepositoryBinding): Long {
                val map = readField(portrait, "captures") as java.util.IdentityHashMap<*,*>
                return readField(checkNotNull(map[binding]), "requestToken") as Long
            }
            suspend fun capture(bvid:String,cid:Long) = withContext(Dispatchers.Main) {
                portrait.capturePageRequest(bvid, 42L, cid)
            }
            // Capture and publish stay in the SAME original caller coroutine.
            // The clip is explicit local native media, never a fake detail API.
            suspend fun actualFrame(value: DesktopOriginalVideoAcceptedPublication) {
                withTimeout(20_000) { player.state.first { state ->
                    if(state.error!=null) error("Native error: "+state.error)
                    owner.native.isCurrent(value) && state.firstVideoFrameReady && state.videoCodec!=null && !state.loading
                } }
            }
            val clip=root.resolve("fixture-clip.mp4").toUri().toString()
            val firstBinding=portrait.capturePageRequest("BV1xx411c7mD",42L,101L)
            val firstBaseline=baseline(firstBinding)
            val firstToken=token(firstBinding)
            val firstSource=firstBinding.authorized(com.bilipai.desktop.player.PlaybackSource(clip,title="explicit local first"))
            val first=owner.native.publish(com.android.purebilibili.feature.video.playback.loader.PlaybackRequest.create("BV1xx411c7mD",42L,101L),
                firstSource,firstBaseline,fixtureJob) { !fixtureJob.isCancelled && owner.captureLoadState().currentLoadRequestToken==firstToken }
            actualFrame(first)
            checkThat("Initial exact captured Binding reaches native ACK and first frame",first.sourceVersion==firstBaseline+1L)
            val secondBinding=portrait.capturePageRequest("BV1yy411c7mD",42L,202L)
            val secondBaseline=baseline(secondBinding)
            val secondToken=token(secondBinding)
            checkThat("Original Session token advances before exact native stop/capture",secondToken>firstToken && owner.captureLoadState().currentBvid=="BV1yy411c7mD" && owner.captureLoadState().currentCid==202L)
            checkThat("Same-player stop synchronously advances version BEFORE capture",secondBaseline==first.sourceVersion+1L && player.currentSourceVersion==secondBaseline && player.currentSourceSnapshot()==null)
            val secondSource=secondBinding.authorized(com.bilipai.desktop.player.PlaybackSource(clip,title="explicit local replacement"))
            val second=owner.native.publish(com.android.purebilibili.feature.video.playback.loader.PlaybackRequest.create("BV1yy411c7mD",42L,202L),
                secondSource,secondBaseline,fixtureJob) { !fixtureJob.isCancelled && owner.captureLoadState().currentLoadRequestToken==secondToken }
            actualFrame(second)
            checkThat("FIFO actual Stop then replacement Load reaches ACK without self rejection",second.sourceVersion==secondBaseline+1L && player.firstActualReadback(second))
            val thirdBinding=portrait.capturePageRequest("BV1zz411c7mD",42L,303L)
            val thirdBaseline=baseline(thirdBinding)
            val thirdToken=token(thirdBinding)
            val thirdSource=thirdBinding.authorized(com.bilipai.desktop.player.PlaybackSource(clip,title="explicit local competing publication"))
            val third=owner.native.publish(com.android.purebilibili.feature.video.playback.loader.PlaybackRequest.create("BV1zz411c7mD",42L,303L),
                thirdSource,thirdBaseline,fixtureJob) { !fixtureJob.isCancelled && owner.captureLoadState().currentLoadRequestToken==thirdToken }
            actualFrame(third)
            val stale=runCatching { owner.native.publish(com.android.purebilibili.feature.video.playback.loader.PlaybackRequest.create("BV1zz411c7mD",42L,303L),
                thirdSource,thirdBaseline,fixtureJob) { true } }
            checkThat("Captured baseline does not retag over a later native publication",stale.exceptionOrNull() is CancellationException && owner.native.current()===third)
            data class CancelCapture(val binding:DesktopOriginalVideoRepositoryBinding,val baseline:Long,val source:com.bilipai.desktop.player.PlaybackSource,val caller:Job)
            val ready=CompletableDeferred<CancelCapture>()
            val cancelled=CoroutineScope(currentCoroutineContext()).launch {
                val binding=portrait.capturePageRequest("BV144411c7mD",42L,404L)
                ready.complete(CancelCapture(binding,baseline(binding),binding.authorized(com.bilipai.desktop.player.PlaybackSource(clip,title="cancelled source")),checkNotNull(currentCoroutineContext()[Job])))
                awaitCancellation()
            }
            val cancelledCapture=ready.await();cancelled.cancelAndJoin()
            val rejected=runCatching { owner.native.publish(com.android.purebilibili.feature.video.playback.loader.PlaybackRequest.create("BV144411c7mD",42L,404L),
                cancelledCapture.source,cancelledCapture.baseline,cancelledCapture.caller) { true } }
            checkThat("Cancelled original caller remains rejected after baseline repair",rejected.exceptionOrNull() is CancellationException && player.currentSourceVersion==cancelledCapture.baseline)
            withContext(Dispatchers.Main) {
                checkThat("One actual Assembly/Section/MPV/Canvas remains",shell.slot.currentAssembly()===owner && owner.section.nativePlayer===player && countCanvas(checkNotNull(windowRef.get()),checkNotNull(findCanvas(player.surface)))==1)
                checkThat("No original VideoDetail Success was seeded or asserted",owner.playback.uiState.value !is VideoPlaybackUiState.Success)
            }
            closeProduct()
            checkThat("Root shutdown drains actual assembly and native session",shell.slot.assemblies.value==null && !owner.owns() && player.decoderCapabilities.value==null)
        } catch (failure: Throwable) { error = failure; failure.printStackTrace() }
        finally {
            try { closeProduct() } catch (failure: Throwable) { if (error == null) error = failure else error.addSuppressed(failure) }
            withContext(Dispatchers.Main) { refs.close() }
            val origins = listOf(DesktopOriginalVideoShellOwner::class.java, DesktopOriginalVideoRootAssembler::class.java,
                DesktopOriginalVideoOwnerAssembly::class.java, DesktopOriginalVideoSectionWindowsPlatform::class.java,
                DesktopOriginalVideoRootWindowPlatformsImpl::class.java, MpvPlayer::class.java, DesktopOriginalPortraitPlatformBinding::class.java, com.android.purebilibili.feature.video.viewmodel.VideoPlaybackViewModel::class.java)
            val result = buildJsonObject {
                put("status", if (error == null) "PASS_PROSPECTIVE_STORY_BASELINE_NATIVE" else "FAIL")
                put("assertions", checks.size); put("checks", JsonArray(checks)); put("error", error?.javaClass?.name.orEmpty())
                put("errorMessage", sanitizeDesktopDiagnosticText(error?.message.orEmpty()))
                put("classOrigins", buildJsonObject { origins.forEach { clazz -> put(clazz.name, buildJsonObject {
                    put("origin", clazz.protectionDomain.codeSource.location.toString())
                    val bytes = checkNotNull(clazz.getResourceAsStream("/" + clazz.name.replace('.', '/') + ".class")).use { it.readBytes() }
                    put("sha256Bytes", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
                }) } })
                put("productionOverrides", "explicit frozen Story89/review plus two baseline source edits"); put("seededSuccess", false); put("realAccount", false); put("globalInput", false)
                put("unchangedMainEntry", false); put("videoDetailSuccessAccepted", checks.any { it["name"]?.jsonPrimitive?.content?.contains("public video 0 original Success")==true && it["passed"]?.jsonPrimitive?.boolean==true }); put("physicalVideoToVideoCarrierAccepted", checks.any { it["name"]?.jsonPrimitive?.content?.startsWith("Video2Video")==true && it["passed"]?.jsonPrimitive?.boolean==true })
                put("PiPTransferAccepted", checks.any { it["name"]?.jsonPrimitive?.content?.startsWith("PiP restore")==true && it["passed"]?.jsonPrimitive?.boolean==true }); put("SMTCButtonAccepted", false)
            }
            Files.writeString(root.resolve("root-media-proof.json"), result.toString())
            withContext(Dispatchers.Main) { exit.get()?.invoke() }
            fixtureJob.cancel()
        }
    }
}

fun main(args: Array<String>) {
    require(args.size == 1)
    val root = Path.of(args[0]).toAbsolutePath().normalize()
    require(Files.isDirectory(root))
    RootMediaFixture(root).mount()
}
