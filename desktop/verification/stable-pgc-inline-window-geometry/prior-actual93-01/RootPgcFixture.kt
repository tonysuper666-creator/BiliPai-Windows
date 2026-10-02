@file:OptIn(androidx.compose.runtime.tooling.ComposeToolingApi::class, androidx.compose.runtime.ExperimentalComposeRuntimeApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.rootpgcfixture

import androidx.compose.runtime.*
import androidx.compose.runtime.tooling.*
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.semantics.*
import androidx.compose.ui.InternalComposeUiApi
import com.android.purebilibili.feature.search.*
import com.android.purebilibili.data.model.response.SearchType
import com.android.purebilibili.data.repository.SearchUpOrder
import com.android.purebilibili.core.network.policy.AppHttpProxySettings
import com.android.purebilibili.core.store.NetworkProxyStore
import java.awt.event.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import com.android.purebilibili.feature.audio.player.AudioNowPlayingSession
import com.android.purebilibili.feature.video.viewmodel.VideoPlaybackUiState
import com.android.purebilibili.navigation3.BiliPaiNavKey
import com.bilipai.desktop.DesktopApp
import com.bilipai.desktop.DesktopLibrary
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
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities
import com.android.purebilibili.feature.bangumi.BangumiPlayerState

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

/** Fixture-owned deny proxy used through the ORIGINAL app proxy settings/selector.
 * It never forwards traffic, returns business data, records URLs, reads account headers,
 * changes the repository or creates another HTTP client. All accepted sockets close. */
private class FixtureDenyProxy : AutoCloseable {
    private val server=ServerSocket(0,32,InetAddress.getByName("127.0.0.1"))
    private val stopped=AtomicBoolean(false)
    val denied=AtomicInteger()
    val port:Int get()=server.localPort
    private val worker=Thread({
        while(!stopped.get()) try {
            server.accept().use { socket ->
                denied.incrementAndGet()
                socket.getOutputStream().write("HTTP/1.1 503 Fixture network disabled\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
            }
        } catch(error:java.io.IOException) { if(!stopped.get())throw error }
    },"RootPgcFixture-DenyProxy").apply{isDaemon=true;start()}
    override fun close(){if(stopped.compareAndSet(false,true)){server.close();worker.join(3000);check(!worker.isAlive)}}
}

private class RootPgcFixture(private val root: Path) {
    private val closing = AtomicBoolean(false)
    private val fixtureJob = SupervisorJob()
    private val fixtureScope = CoroutineScope(fixtureJob + Dispatchers.Default)
    private val refs = ProductReferences(fixtureScope)
    private val shutdown = AtomicReference<suspend () -> Unit>()
    private val windowRef = AtomicReference<ComposeWindow>()
    private val exit = AtomicReference<() -> Unit>()
    private val checks = mutableListOf<JsonObject>()
    private val pointerTrace = mutableListOf<JsonObject>()
    private val sessions = DesktopSessionStore(root.resolve("isolated-session.json"), persistent = false)
    private val store = DesktopPluginStore(root.resolve("isolated-plugin-store"))
    private val denyProxy = FixtureDenyProxy()
    private val fixtureProxySettings = run {
        val context=DesktopPluginContext(store)
        NetworkProxyStore.init(context)
        NetworkProxyStore.save(context,AppHttpProxySettings(enabled=true,host="127.0.0.1",portText=denyProxy.port.toString()))
        check(NetworkProxyStore.getSync().enabled)
    }
    private val repository = DesktopRepository(sessions)
    private val player = MpvPlayer(useNullAudioOutput = true)
    private val images = DesktopApplicationImageLoader(repository, root.resolve("cache")) { !closing.get() }
    private var pointerEvents = 0
    private var keyboardEvents = 0
    private var mountedSearch = false
    private var hotKeywordAccepted = false
    private fun checkThat(name: String, result: Boolean) {
        checks += buildJsonObject { put("name",name);put("passed",result) }
        println("${if(result) "PASS" else "FAIL"} $name");check(result) { name }
    }
    private suspend fun <T:Any> awaitReference(name:String,read:()->T?):T = withTimeout(45_000) {
        println("STAGE await reference: $name")
        while(true) { withContext(Dispatchers.Main){read()}?.let{return@withTimeout it};delay(40) }
        @Suppress("UNREACHABLE_CODE") error(name)
    }
    private suspend fun awaitCondition(name:String,read:()->Boolean) = withTimeout(35_000) {
        println("STAGE await condition: $name")
        while(!withContext(Dispatchers.Main){read()})delay(40)
    }
    private fun nodes():List<SemanticsNode> {
        check(SwingUtilities.isEventDispatchThread())
        fun walk(n:SemanticsNode):List<SemanticsNode> = listOf(n)+n.children.flatMap(::walk)
        return checkNotNull(windowRef.get()).semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
    }
    private fun labels() = nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
    /** The exact owned Compose Skia Canvas listeners receive these AWT events. No Robot,
     * OS key injection, user process, semantic SetText/OnClick or state setter is used. */
    private fun click(node:SemanticsNode) {
        check(SwingUtilities.isEventDispatchThread())
        val window=checkNotNull(windowRef.get());val layer=checkNotNull(findLayer(window));val canvas=layer.canvas
        val point=node.boundsInRoot.center
        val scale=layer.contentScale
        check(scale.isFinite()&&scale>0)
        // Record the framework's actual coordinate carriers; the visible product owns its transform.
        val x=point.x/scale;val y=point.y/scale
        check(x>=0&&y>=0&&x<canvas.width&&y<canvas.height)
        pointerTrace+=buildJsonObject { put("nodeId",node.id);put("text",node.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString("|"){it.text});put("scale",scale);put("x",x);put("y",y);put("canvasWidth",canvas.width);put("canvasHeight",canvas.height);put("boundsInRoot",node.boundsInRoot.toString());put("boundsInWindow",node.boundsInWindow.toString());put("ownedCanvasIdentity",System.identityHashCode(canvas));put("ownedChildWindows",buildJsonArray{window.ownedWindows.forEach{child->add(buildJsonObject{put("type",child.javaClass.name);put("visible",child.isVisible);put("sameCanvas",findCanvas(child)===canvas);put("hasCanvas",findCanvas(child)!=null)})}}) }
        val listeners=canvas.mouseListeners;check(listeners.isNotEmpty()) { "Owned Compose Canvas has no actual mouse listeners" }
        val now=System.currentTimeMillis()
        val entered=MouseEvent(canvas,MouseEvent.MOUSE_ENTERED,now,0,x.toInt(),y.toInt(),0,false,MouseEvent.NOBUTTON)
        val moved=MouseEvent(canvas,MouseEvent.MOUSE_MOVED,now+1,0,x.toInt(),y.toInt(),0,false,MouseEvent.NOBUTTON)
        val press=MouseEvent(canvas,MouseEvent.MOUSE_PRESSED,now,InputEvent.BUTTON1_DOWN_MASK,x.toInt(),y.toInt(),1,false,MouseEvent.BUTTON1)
        val release=MouseEvent(canvas,MouseEvent.MOUSE_RELEASED,now+35,0,x.toInt(),y.toInt(),1,false,MouseEvent.BUTTON1)
        val clicked=MouseEvent(canvas,MouseEvent.MOUSE_CLICKED,now+36,0,x.toInt(),y.toInt(),1,false,MouseEvent.BUTTON1)
        listeners.forEach{it.mouseEntered(entered)};canvas.mouseMotionListeners.forEach{it.mouseMoved(moved)}
        listeners.forEach{it.mousePressed(press)};listeners.forEach{it.mouseReleased(release)};listeners.forEach{it.mouseClicked(clicked)};pointerEvents+=5
        window.renderImmediately()
    }
    private fun key(id:Int,code:Int,char:Char) {
        check(SwingUtilities.isEventDispatchThread())
        val canvas=checkNotNull(findLayer(checkNotNull(windowRef.get()))).canvas
        val listeners=canvas.keyListeners;check(listeners.isNotEmpty()) { "Owned Compose Canvas has no actual key listeners" }
        val event=KeyEvent(canvas,id,System.currentTimeMillis(),0,code,char)
        listeners.forEach { when(id) { KeyEvent.KEY_TYPED->it.keyTyped(event);KeyEvent.KEY_PRESSED->it.keyPressed(event);KeyEvent.KEY_RELEASED->it.keyReleased(event) } };keyboardEvents++
        checkNotNull(windowRef.get()).renderImmediately()
    }
    private fun textNode(label:String)=nodes().lastOrNull { it.config.getOrNull(SemanticsProperties.Text)?.any{t->t.text==label}==true }
    private fun capture(name:String) {
        check(SwingUtilities.isEventDispatchThread());val window=checkNotNull(windowRef.get());window.renderImmediately()
        val bitmap=checkNotNull(findLayer(window)?.screenshot())
        try { Image.makeFromBitmap(bitmap).use { image -> checkNotNull(image.encodeToData(EncodedImageFormat.PNG)).use { Files.write(root.resolve(name+".png"),it.bytes) } } }
        finally { bitmap.close() }
    }
    private suspend fun closeProduct() {
        if(closing.compareAndSet(false,true))withContext(NonCancellable) {
            var shutdownFailure:Throwable?=null
            try { withContext(Dispatchers.Main){checkNotNull(shutdown.get()) { "Real product shutdown absent" }.invoke()} }
            catch(error:Throwable){shutdownFailure=error}
            try { withContext(Dispatchers.IO){try{images.close()}finally{player.close()}} }
            catch(error:Throwable){if(shutdownFailure==null)shutdownFailure=error else shutdownFailure.addSuppressed(error)}
            shutdownFailure?.let{throw it}
        }
    }
    fun mount()=application(exitProcessOnExit=false) {
        val state=rememberWindowState(width=1100.dp,height=900.dp,position=WindowPosition((-16000).dp,(-16000).dp))
        Window(onCloseRequest={fixtureScope.launch{closeProduct();withContext(Dispatchers.Main){exit.get()?.invoke()}}},
            title="BiliPai fixture-owned actual Root PGC",state=state) {
            val actualWindow=window
            val presentation=rememberDesktopWindowsDanmakuPresentation(actualWindow,state)
            val fullscreen=rememberDesktopWindowsFullscreenControl(actualWindow,state)
            SideEffect { actualWindow.focusableWindowState=false;windowRef.set(actualWindow);exit.set{exitApplication()} }
            CompositionLocalProvider(LocalDesktopApplicationImageLoader provides images) {
                DesktopApp(repository,player,null,null,
                    onExit={fixtureScope.launch{closeProduct();withContext(Dispatchers.Main){exitApplication()}}},
                    onToggleFullscreen=fullscreen::toggle,hostWindow=actualWindow,registerShutdown=shutdown::set,
                    onRestart={error("Restart is outside this fixture")},applicationPluginStore=store,isClosing=closing::get,
                    diagnosticLifecycle=null,diagnosticStartupError=null,danmakuPresentation=presentation,
                    isFullscreen={state.placement==WindowPlacement.Fullscreen},setFullscreen=fullscreen::setFullscreen)
            }
            LaunchedEffect(Unit){fixtureScope.launch{exercise()}}
        }
    }
    private suspend fun exercise() {
        var failure:Throwable?=null
        try {
            val shell=awaitReference("actual Shell"){refs.find<DesktopOriginalVideoShellOwner>()}
            awaitCondition("published actual Root"){shell.navigationReady()}
            val window=awaitReference("actual Window route provider"){refs.find<DesktopOriginalVideoRootWindowEnvironment>()}
            val key=BiliPaiNavKey.BangumiPlayer(77L,88L,resumePositionMs=1250L)
            withContext(Dispatchers.Main){checkThat("Actual Root accepts typed PGC",window.commands.push(key))}
            val pgc=awaitReference("actual original PGC owner"){refs.find<DesktopOriginalBangumiPlayerRootOwner>()}
            val platforms=awaitReference("actual native Window platforms"){refs.find<DesktopOriginalVideoRootWindowPlatformsImpl>()}
            awaitCondition("full original PGC has real error and retry") {
                window.currentKey()==key && pgc.entries.value?.key==key &&
                    pgc.entries.value?.viewModel?.uiState?.value is BangumiPlayerState.Error && textNode("重试")!=null
            }
            val entry=withContext(Dispatchers.Main){checkNotNull(pgc.entries.value)}
            withContext(Dispatchers.Main) {
                mountedSearch=true
                checkThat("Actual physical leaf uses original PGC and native platforms",entry.owns()&&platforms.bangumiPlayer===pgc)
                checkThat("Complete original PGC VM reports actual denied transport",entry.viewModel.uiState.value is BangumiPlayerState.Error)
                checkThat("Same existing native Section and typed comment VM",entry.section===platforms.holder.section&&entry.comments===checkNotNull(shell.slot.currentAssembly()).domains.comments)
                capture("actual-root-pgc-original-error")
            }
            delay(750)
            val requests=denyProxy.denied.get()
            withContext(Dispatchers.Main){click(checkNotNull(textNode("重试")))}
            awaitCondition("original PGC pointer retry calls guarded transport") {
                denyProxy.denied.get()>requests && entry.viewModel.uiState.value is BangumiPlayerState.Error
            }
            withContext(Dispatchers.Main) {
                checkThat("Actual pointer invokes complete original PGC retry",denyProxy.denied.get()>requests)
                checkThat("Retry retains one original PGC VM",pgc.entries.value===entry)
                checkThat("Actual Settings cover accepted",window.commands.push(BiliPaiNavKey.Settings))
            }
            awaitCondition("covered PGC physical key"){window.currentKey()==BiliPaiNavKey.Settings}
            withContext(Dispatchers.Main) {
                checkThat("Covered PGC retains original state owner",pgc.entries.value===entry&&entry.owns())
                checkThat("Actual Root returns to PGC",window.commands.back())
            }
            awaitCondition("same original PGC restored"){window.currentKey()==key&&textNode("重试")!=null}
            withContext(Dispatchers.Main) {
                checkThat("Return reuses same original PGC entry",pgc.entries.value===entry&&entry.owns())
                checkThat("Pop actual PGC accepted",window.commands.back())
            }
            awaitCondition("popped unloaded PGC scope retired"){window.currentKey()!=key&&!entry.owns()&&pgc.entries.value==null}
            withContext(Dispatchers.Main){checkThat("Popped PGC rejects original late retry",runCatching{entry.viewModel.retry()}.isFailure)}
            val courseKey=BiliPaiNavKey.BangumiPlayer(99L,0L,isCourse=true,preferredAid=101L)
            withContext(Dispatchers.Main){checkThat("Actual Root accepts typed PUGV",window.commands.push(courseKey))}
            awaitCondition("complete original PUGV error is rendered") {
                window.currentKey()==courseKey&&pgc.entries.value?.key==courseKey&&
                    pgc.entries.value?.viewModel?.uiState?.value is BangumiPlayerState.Error&&textNode("重试")!=null
            }
            val course=withContext(Dispatchers.Main){checkNotNull(pgc.entries.value)}
            withContext(Dispatchers.Main) {
                checkThat("PUGV uses new original VM and same native owner",course!==entry&&course.owns()&&course.section===entry.section)
                capture("actual-root-pgc-pugv-original-error")
            }
            val courseRequests=denyProxy.denied.get()
            withContext(Dispatchers.Main){click(checkNotNull(textNode("重试")))}
            awaitCondition("PUGV actual pointer retry completes"){denyProxy.denied.get()>courseRequests&&course.viewModel.uiState.value is BangumiPlayerState.Error}
            withContext(Dispatchers.Main){checkThat("PUGV retry retains original course VM",pgc.entries.value===course&&denyProxy.denied.get()>courseRequests)}
            val epoch=repository.sessionEpoch
            withContext(Dispatchers.IO){repository.logout()}
            awaitCondition("actual guest epoch retires PGC Root"){repository.sessionEpoch!=epoch&&!course.owns()&&!window.owns()}
            withContext(Dispatchers.Main){checkThat("Actual guest account change retires PGC and PUGV children",repository.account.value==null&&!entry.owns()&&!course.owns())}
            closeProduct()
            withContext(Dispatchers.Main){checkThat("Actual Root close keeps all PGC entries retired",!entry.owns()&&!course.owns())}
        } catch(error:Throwable) {
            failure=error;error.printStackTrace()
            withContext(Dispatchers.Main){runCatching{capture("actual-root-pgc-failure");Files.writeString(root.resolve("failure-labels.json"),buildJsonObject{put("labels",buildJsonArray{labels().forEach{add(it)}});put("pointerTrace",JsonArray(pointerTrace))}.toString())}.onFailure{it.printStackTrace()}}
        } finally {
            try{closeProduct()}catch(error:Throwable){if(failure==null)failure=error else failure.addSuppressed(error)}
            withContext(Dispatchers.Main){refs.close()}
            withContext(Dispatchers.IO){denyProxy.close()}
            val names=listOf("com.bilipai.desktop.DesktopShellKt","com.bilipai.desktop.ui.DesktopOriginalRootStackKt","com.bilipai.desktop.ui.DesktopOriginalBangumiPlayerRootOwner","com.bilipai.desktop.ui.DesktopOriginalBangumiPlayerPhysicalLeafKt","com.bilipai.desktop.ui.DesktopOriginalBangumiNativePresenter","com.android.purebilibili.feature.bangumi.BangumiPlayerViewModel","com.android.purebilibili.feature.bangumi.DesktopOriginalBangumiPlayerScreenKt","com.android.purebilibili.feature.bangumi.ui.player.DesktopOriginalBangumiPlayerContentKt","com.android.purebilibili.feature.bangumi.ui.player.DesktopOriginalBangumiPlayerOverlayHostKt")
            val result=buildJsonObject {
                put("status",if(failure==null)"PASS_ACTUAL_ROOT_PGC_UI"else"FAIL");put("assertions",checks.size);put("checks",JsonArray(checks));put("error",failure?.javaClass?.name.orEmpty())
                put("RootMounted",mountedSearch);put("productionOverrides",0);put("actualOwnedCanvasPointerEvents",pointerEvents);put("actualOwnedCanvasKeyboardEvents",keyboardEvents)
                put("pointerTrace",JsonArray(pointerTrace));put("actualBusinessHotKeywordAccepted",false);put("realAccount",false);put("globalInput",false);put("syntheticApi",false);put("sameRootHttpClient",true)
                put("appAndImageNetworkFailClosed",true);put("fixtureDenyProxyConnections",denyProxy.denied.get())
                put("businessNetworkAllAccepted",false);put("nativePlaybackAccepted",false);put("newEXEDeployed",false);put("unchangedMainEntry",false)
                put("classOrigins",buildJsonObject{names.forEach{name->val clazz=Class.forName(name);put(name,buildJsonObject{put("origin",clazz.protectionDomain.codeSource.location.toString());val bytes=checkNotNull(clazz.getResourceAsStream("/"+name.replace('.','/')+".class")).use{it.readBytes()};put("sha256Bytes",MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)})})}})
            }
            Files.writeString(root.resolve("root-pgc-proof.json"),result.toString())
            withContext(Dispatchers.Main){exit.get()?.invoke()};fixtureJob.cancel()
        }
    }
}
fun main(args:Array<String>) {
    require(args.size==1);val root=Path.of(args[0]).toAbsolutePath().normalize();require(Files.isDirectory(root))
    // Set only the existing preference in the runner-owned LOCALAPPDATA before actual Root reads it.
    DesktopLibrary().setAutomaticUpdates(false)
    RootPgcFixture(root).mount()
}
