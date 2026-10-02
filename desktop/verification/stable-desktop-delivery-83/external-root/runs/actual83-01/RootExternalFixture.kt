@file:OptIn(androidx.compose.runtime.tooling.ComposeToolingApi::class, androidx.compose.runtime.ExperimentalComposeRuntimeApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
package com.bilipai.desktop.rootexternalfixture

import androidx.compose.runtime.*
import androidx.compose.runtime.tooling.*
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.*
import com.android.purebilibili.core.plugin.PluginCapability
import com.bilipai.desktop.plugins.js.DesktopJsPluginHost
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
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


private class FixtureMediaOrigin(clip: Path):AutoCloseable {
    private val bytes=Files.readAllBytes(clip)
    private val executor=Executors.newCachedThreadPool()
    private val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
    val requests=AtomicInteger()
    val url get()="http://127.0.0.1:${server.address.port}/local.mp4"
    init {
        server.executor=executor
        server.createContext("/local.mp4") { e ->
            requests.incrementAndGet()
            try {
                val range=e.requestHeaders.getFirst("Range")?.let { Regex("bytes=(\\d+)-(\\d*)").matchEntire(it) }
                val start=range?.groupValues?.get(1)?.toInt() ?: 0
                val end=(range?.groupValues?.get(2)?.toIntOrNull() ?: bytes.lastIndex).coerceAtMost(bytes.lastIndex)
                if(start<0||start>bytes.lastIndex||end<start) { e.sendResponseHeaders(416,-1); return@createContext }
                e.responseHeaders.add("Content-Type","video/mp4")
                e.responseHeaders.add("Accept-Ranges","bytes")
                if(range!=null)e.responseHeaders.add("Content-Range","bytes $start-$end/${bytes.size}")
                if(e.requestMethod=="HEAD") { e.responseHeaders.add("Content-Length",(end-start+1).toString()); e.sendResponseHeaders(if(range!=null)206 else 200,-1) }
                else { e.sendResponseHeaders(if(range!=null)206 else 200,(end-start+1).toLong()); e.responseBody.use { it.write(bytes,start,end-start+1) } }
            } finally { e.close() }
        }
        server.start()
    }
    override fun close() { server.stop(0); executor.shutdownNow() }
}
private fun semanticTree(n:SemanticsNode):List<SemanticsNode> = listOf(n)+n.children.flatMap(::semanticTree)

private class RootExternalFixture(private val root: Path, clip: Path) {
    private val origin = FixtureMediaOrigin(clip)
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
            title = "BiliPai fixture-owned actual authorized external media lifecycle", state = state) {
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

    private suspend fun invokeOriginalButton(label:String) = withTimeout(20_000) {
        while(true) {
            val invoked=withContext(Dispatchers.Main) {
                val w=checkNotNull(windowRef.get())
                val nodes=w.semanticsOwners.flatMap { semanticTree(it.unmergedRootSemanticsNode) }
                val node=nodes.firstOrNull { n ->
                    semanticTree(n).any { child -> child.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text==label } } &&
                        n.config.getOrNull(SemanticsActions.OnClick)?.action!=null &&
                        !n.config.contains(SemanticsProperties.Disabled)
                }
                node?.config?.getOrNull(SemanticsActions.OnClick)?.action?.invoke() == true
            }
            if(invoked)return@withTimeout
            delay(50)
        }
    }
    private suspend fun exercise() {
        var error:Throwable?=null
        try {
            val shell=awaitReference("actual Shell") { refs.find<DesktopOriginalVideoShellOwner>() }
            awaitCondition("actual factory") { shell.navigationReady() }
            val owner=withContext(Dispatchers.Main) { shell.slot.requireAssembly() }
            val platforms=awaitReference("actual Windows platforms") { runCatching { shell.requireWindows() }.getOrNull() }
            val environment=awaitReference("actual Root environment") { refs.find<DesktopOriginalVideoRootWindowEnvironment>() }
            val resources=awaitReference("actual resources") { refs.find<DesktopOriginalVideoRootShellResources>() }
            val media=awaitReference("same actual retained media") { refs.find<DesktopRetainedMedia>() }
            withTimeout(20_000) { platforms.awaitNativeInitialization() }
            withContext(Dispatchers.Main) { checkThat("Actual idle ordinary Assembly, Section, MPV and Overlay agree",owner.owns()&&owner.section.nativePlayer===player&&
                (readField(platforms.holder.section,"resources") as DesktopOriginalVideoSectionWindowsResources).overlay===resources.overlay) }
            val plugins=environment.runtime.jsPlugins
            plugins.accountChanged(repository.sessionEpoch)
            plugins.load()
            val id="fixture.loopback.media"
            val streamUrl=JsonPrimitive(origin.url).toString()
            val script="""window.BiliPaiPlugin={id:'fixture.loopback.media',title:'Loopback Fixture',permissions:['EXTERNAL_MEDIA_PLAYBACK'],modules:[{id:'items',title:'Items',functionName:'load'}],load:function(params){return [{id:'a',title:'Media A',streams:[{id:'a',title:'fixture-loopback-mp4-a',url:$streamUrl,contentType:'video/mp4',headers:{}}]},{id:'b',title:'Media B',streams:[{id:'b',title:'fixture-loopback-mp4-b',url:$streamUrl,contentType:'video/mp4',headers:{}}]}];}};"""
            val preview=plugins.previewScript(script)
            val installed=plugins.install(preview,setOf(PluginCapability.EXTERNAL_MEDIA_PLAYBACK))
            plugins.setEnabled(id,true)
            checkThat("Isolated plugin traverses actual preview, install approval and enable",installed.manifest.id==id&&
                plugins.state.value.plugins.single { it.installed.manifest.id==id }.let { it.authorizationMatches&&it.installed.enabled }&&repository.account.value==null)
            withContext(Dispatchers.Main) { checkThat("Actual typed plugin content route admitted",environment.commands.push(BiliPaiNavKey.JsPluginContent(id))) }
            awaitCondition("actual plugin content") { environment.currentKey()==BiliPaiNavKey.JsPluginContent(id) }
            suspend fun open(index:Int):Long {
                invokeOriginalButton("fixture-loopback-mp4-${if(index==0)"a" else "b"}")
                awaitCondition("actual original stream callback enters ExternalMedia") { environment.currentKey() is BiliPaiNavKey.ExternalMedia&&media.external.request?.title=="Media ${if(index==0)"A" else "B"}" }
                withTimeout(25_000) { player.state.first { st ->
                    if(st.error!=null)error("Actual external native error: "+st.error)
                    st.firstVideoFrameReady&&st.videoCodec!=null&&!st.loading&&st.durationSeconds>0
                } }
                invokeOriginalButton("暂停")
                withTimeout(10_000) { player.state.first { it.paused&&!it.loading } }
                return withContext(Dispatchers.Main) {
                    val version=checkNotNull(media.external.sourceVersion)
                    checkThat("External $index original menu callback, authority and real first frame agree",media.external.authorizationCurrent&&media.external.ownsNativeSource&&media.external.loaded&&player.ownsSourceVersion(version)&&origin.requests.get()>0)
                    checkThat("External $index retains same ordinary Assembly and one native Canvas",shell.slot.currentAssembly()===owner&&shell.requireWindows()===platforms&&countCanvas(checkNotNull(windowRef.get()),checkNotNull(findCanvas(player.surface)))==1)
                    capture("actual-external-$index")
                    version
                }
            }
            val first=open(0)
            withContext(Dispatchers.Main) { checkThat("Actual Settings cover admitted",environment.commands.push(BiliPaiNavKey.Settings)) }
            awaitCondition("actual Settings") { environment.currentKey()==BiliPaiNavKey.Settings }
            delay(500)
            withContext(Dispatchers.Main) {
                checkThat("Covered external source retains exact native version and same Assembly",media.external.sourceVersion==first&&player.ownsSourceVersion(first)&&shell.slot.currentAssembly()===owner)
                checkThat("Actual back to external admitted",environment.commands.back())
            }
            awaitCondition("actual external return") { environment.currentKey() is BiliPaiNavKey.ExternalMedia }
            withContext(Dispatchers.Main) { checkThat("Actual back to plugin content admitted",environment.commands.back()) }
            awaitCondition("actual content return") { environment.currentKey()==BiliPaiNavKey.JsPluginContent(id) }
            val second=open(1)
            checkThat("Second original external menu launch advances same native actor",second>first&&media.external.sourceVersion==second&&player.ownsSourceVersion(second))
            invokeOriginalButton("浮窗")
            awaitCondition("actual PiP attached") { resources.pip.active.value&&SwingUtilities.getWindowAncestor(player.surface)!==windowRef.get() }
            withContext(Dispatchers.Main) {
                val pipWindow=checkNotNull(SwingUtilities.getWindowAncestor(player.surface))
                checkThat("External PiP owns same Canvas, source and Assembly",countCanvas(pipWindow,checkNotNull(findCanvas(player.surface)))==1&&player.ownsSourceVersion(second)&&shell.slot.currentAssembly()===owner)
                resources.pip.restore()
            }
            awaitCondition("actual PiP restore") { !resources.pip.active.value&&SwingUtilities.getWindowAncestor(player.surface)===windowRef.get() }
            withContext(Dispatchers.Main) { checkThat("External PiP restore retains same version and one Canvas",player.ownsSourceVersion(second)&&countCanvas(checkNotNull(windowRef.get()),checkNotNull(findCanvas(player.surface)))==1) }
            closeProduct()
            withContext(Dispatchers.Main) { checkThat("Actual external Root shutdown joins owner and closes native session",shell.slot.assemblies.value==null&&!owner.owns()&&player.decoderCapabilities.value==null) }
        } catch(failure:Throwable) { error=failure;failure.printStackTrace() }
        finally {
            try { closeProduct() } catch(failure:Throwable) { if(error==null)error=failure else error.addSuppressed(failure) }
            origin.close()
            withContext(Dispatchers.Main) { refs.close() }
            val origins=listOf(DesktopOriginalVideoShellOwner::class.java,DesktopOriginalVideoRootAssembler::class.java,
                DesktopOriginalVideoOwnerAssembly::class.java,DesktopOriginalVideoSectionWindowsPlatform::class.java,
                DesktopOriginalVideoRootWindowPlatformsImpl::class.java,MpvPlayer::class.java,
                DesktopExternalPageMemory::class.java,DesktopJsPluginHost::class.java)
            val result=buildJsonObject {
                put("status",if(error==null)"PASS_ACTUAL_AUTHORIZED_EXTERNAL_ROOT" else "FAIL")
                put("assertions",checks.size);put("checks",JsonArray(checks));put("error",error?.javaClass?.name.orEmpty());put("errorMessage",sanitizeDesktopDiagnosticText(error?.message.orEmpty()))
                put("classOrigins",buildJsonObject { origins.forEach { clazz -> put(clazz.name,buildJsonObject {
                    put("origin",clazz.protectionDomain.codeSource.location.toString())
                    val bytes=checkNotNull(clazz.getResourceAsStream("/"+clazz.name.replace('.','/')+".class")).use { it.readBytes() }
                    put("sha256Bytes",MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
                }) } })
                put("productionOverrides",0);put("seededSuccess",false);put("realAccount",false);put("globalInput",false);put("semanticCallbackOnly",true)
                put("unchangedMainEntry",false);put("videoDetailSuccessAccepted",false);put("physicalVideoToVideoCarrierAccepted",false)
                put("externalFirstFrameAccepted",checks.any { it["name"]?.jsonPrimitive?.content=="External 0 original menu callback, authority and real first frame agree"&&it["passed"]?.jsonPrimitive?.boolean==true })
                put("externalTwoSourceAccepted",checks.any { it["name"]?.jsonPrimitive?.content=="Second original external menu launch advances same native actor"&&it["passed"]?.jsonPrimitive?.boolean==true })
                put("PiPTransferAccepted",checks.any { it["name"]?.jsonPrimitive?.content=="External PiP restore retains same version and one Canvas"&&it["passed"]?.jsonPrimitive?.boolean==true });put("SMTCButtonAccepted",false)
            }
            Files.writeString(root.resolve("root-external-proof.json"),result.toString())
            withContext(Dispatchers.Main) { exit.get()?.invoke() }
            fixtureJob.cancel()
        }
    }
}
fun main(args:Array<String>) {
    require(args.size==2)
    val root=Path.of(args[0]).toAbsolutePath().normalize()
    val clip=Path.of(args[1]).toAbsolutePath().normalize()
    require(Files.isDirectory(root)&&Files.isRegularFile(clip))
    RootExternalFixture(root,clip).mount()
}
