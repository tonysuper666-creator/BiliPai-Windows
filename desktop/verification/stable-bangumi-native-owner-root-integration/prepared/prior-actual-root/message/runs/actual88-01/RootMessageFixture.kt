@file:OptIn(androidx.compose.runtime.tooling.ComposeToolingApi::class, androidx.compose.runtime.ExperimentalComposeRuntimeApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.rootmessagefixture

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
    },"RootMessageFixture-DenyProxy").apply{isDaemon=true;start()}
    override fun close(){if(stopped.compareAndSet(false,true)){server.close();worker.join(3000);check(!worker.isAlive)}}
}

private class RootMessageFixture(private val root: Path) {
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
    private var mountedMessages = false
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
            title="BiliPai fixture-owned actual Root guest messages",state=state) {
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
            val environment=awaitReference("actual Window route provider"){refs.find<DesktopOriginalVideoRootWindowEnvironment>()}
            val messages=awaitReference("actual remembered full message Root"){refs.find<DesktopOriginalMessagePagesRoot>()}
            val routes=withContext(Dispatchers.Main){readField(messages,"routes") as DesktopOriginalRootRouteAssembly}
            withContext(Dispatchers.Main) {
                checkThat("Actual message Root borrows same repository and guest gate",messages.isOwned()&&messages.repository===repository&&repository.account.value==null&&routes.root.entry.gate.mid==null)
            }
            val keys=listOf(BiliPaiNavKey.Inbox,BiliPaiNavKey.ReplyMe,BiliPaiNavKey.AtMe,BiliPaiNavKey.LikeMe,BiliPaiNavKey.SystemNotice,BiliPaiNavKey.Chat(123456L,1,"Fixture peer"))
            for((index,messageKey) in keys.withIndex()) {
                withContext(Dispatchers.Main){checkThat("Actual typed message route accepted: $messageKey",environment.commands.push(messageKey))}
                awaitCondition("actual guest message leaf: $messageKey"){environment.currentKey()==messageKey&&"登录后查看账号内容" in labels()&&textNode("扫码登录")!=null}
                delay(750) // Navigation3 exit/enter semantics are both retained during their real transition.
                withContext(Dispatchers.Main){
                    mountedMessages=true
                    checkThat("Guest $messageKey renders same actual Root login gate",messages.isOwned()&&routes.containsEntry(messageKey)&&"登录后查看账号内容" in labels())
                    checkThat("Guest $messageKey creates no authenticated message owner",(readField(messages,"entries") as Map<*,*>).isEmpty())
                    capture("actual-root-message-guest-$index")
                    click(checkNotNull(textNode("扫码登录")))
                }
                awaitCondition("original guest Login button callback"){environment.currentKey()==BiliPaiNavKey.Login}
                withContext(Dispatchers.Main){
                    checkThat("Guest $messageKey Canvas button pushes actual typed Login",environment.currentKey()==BiliPaiNavKey.Login&&routes.containsEntry(messageKey))
                    checkThat("Actual Login back accepted: $messageKey",environment.commands.back())
                }
                awaitCondition("back to actual retained guest leaf"){environment.currentKey()==messageKey&&"登录后查看账号内容" in labels()}
                withContext(Dispatchers.Main){checkThat("Back retains exact message Root with no guest VM",messages.isOwned()&&(readField(messages,"entries") as Map<*,*>).isEmpty())}
            }
            val selected=keys.last()
            withContext(Dispatchers.Main){checkThat("Actual settings cover accepted",environment.commands.push(BiliPaiNavKey.Settings))}
            awaitCondition("actual settings cover"){environment.currentKey()==BiliPaiNavKey.Settings}
            withContext(Dispatchers.Main){
                checkThat("Cover retains all six typed guest entries and unique message Root",messages.isOwned()&&keys.all(routes::containsEntry)&&(readField(messages,"entries") as Map<*,*>).isEmpty())
                checkThat("Actual settings back accepted",environment.commands.back())
            }
            awaitCondition("actual retained message return"){environment.currentKey()==selected&&"登录后查看账号内容" in labels()}
            for(messageKey in keys.asReversed()) {
                withContext(Dispatchers.Main){
                    checkThat("Pop actual message entry: $messageKey",environment.currentKey()==messageKey&&environment.commands.back())
                }
                awaitCondition("actual removed guest route pruned"){!routes.containsEntry(messageKey)&&(readField(messages,"entries") as Map<*,*>).isEmpty()}
                withContext(Dispatchers.Main){checkThat("Popped guest key has no retained or authenticated owner",!routes.containsEntry(messageKey)&&messages.isOwned()&&(readField(messages,"entries") as Map<*,*>).isEmpty())}
            }
            val oldEpoch=repository.sessionEpoch
            withContext(Dispatchers.IO){repository.logout()}
            awaitCondition("actual guest epoch retires old message/window owner"){repository.sessionEpoch!=oldEpoch&&!messages.isOwned()&&!environment.owns()&&!routes.owns()}
            withContext(Dispatchers.Main){
                checkThat("Real isolated guest epoch invalidates same actual message Root",repository.account.value==null&&!messages.isOwned()&&!environment.owns()&&!routes.owns())
                checkThat("Prior guest Root refuses late route and Store admission",!environment.commands.push(BiliPaiNavKey.ReplyMe)&&!routes.root.entry.gate.commit{error("Retired guest callback must not run")})
            }
            val fresh=awaitReference("actual fresh message Root"){refs.values().filterIsInstance<DesktopOriginalMessagePagesRoot>().firstOrNull{it!==messages&&it.isOwned()}}
            val freshWindow=awaitReference("actual fresh Window owner"){refs.values().filterIsInstance<DesktopOriginalVideoRootWindowEnvironment>().firstOrNull{it!==environment&&it.owns()}}
            withContext(Dispatchers.Main){checkThat("Fresh actual Root accepts guest typed message",freshWindow.commands.push(BiliPaiNavKey.ReplyMe))}
            awaitCondition("fresh guest message UI"){freshWindow.currentKey()==BiliPaiNavKey.ReplyMe&&"登录后查看账号内容" in labels()}
            withContext(Dispatchers.Main){
                checkThat("Fresh message owner is same repository and guest gated",fresh.repository===repository&&fresh.isOwned()&&(readField(fresh,"entries") as Map<*,*>).isEmpty())
                capture("actual-root-message-final")
            }
            closeProduct()
            withContext(Dispatchers.Main){checkThat("Actual Root close retires prior and fresh message/window owners",!fresh.isOwned()&&!freshWindow.owns()&&!messages.isOwned()&&!environment.owns())}
        } catch(error:Throwable) {
            failure=error;error.printStackTrace()
            withContext(Dispatchers.Main){runCatching{capture("actual-root-message-failure")}.onFailure{it.printStackTrace()}}
        } finally {
            try{closeProduct()}catch(error:Throwable){if(failure==null)failure=error else failure.addSuppressed(error)}
            withContext(Dispatchers.Main){refs.close()}
            withContext(Dispatchers.IO){denyProxy.close()}
            val names=listOf("com.bilipai.desktop.DesktopShellKt","com.bilipai.desktop.ui.DesktopOriginalRootStackKt","com.bilipai.desktop.ui.DesktopOriginalMessagePagesRoot","com.bilipai.desktop.ui.DesktopOriginalMessagePagesRootKt","com.bilipai.desktop.ui.CommunityUiSupportKt","com.android.purebilibili.feature.message.MessageCenterScreenKt","com.android.purebilibili.feature.message.ChatScreenKt","com.android.purebilibili.feature.message.feed.ReplyMeScreenKt","com.android.purebilibili.feature.message.feed.AtMeScreenKt","com.android.purebilibili.feature.message.feed.LikeMeScreenKt","com.android.purebilibili.feature.message.feed.SystemNoticeScreenKt")
            val result=buildJsonObject {
                put("status",if(failure==null)"PASS_ACTUAL_ROOT_MESSAGE_GUEST_UI"else"FAIL");put("assertions",checks.size);put("checks",JsonArray(checks));put("error",failure?.javaClass?.name.orEmpty())
                put("RootMounted",mountedMessages);put("productionOverrides",0);put("actualOwnedCanvasPointerEvents",pointerEvents);put("actualOwnedCanvasKeyboardEvents",keyboardEvents);put("pointerTrace",JsonArray(pointerTrace))
                put("authenticatedMessagePagesAccepted",false);put("realAccount",false);put("globalInput",false);put("syntheticApi",false);put("sameRootHttpClient",true)
                put("appAndImageNetworkFailClosed",true);put("fixtureDenyProxyConnections",denyProxy.denied.get());put("businessNetworkAllAccepted",false);put("nativePlaybackAccepted",false);put("newEXEDeployed",false);put("unchangedMainEntry",false)
                put("classOrigins",buildJsonObject{names.forEach{name->val clazz=Class.forName(name);put(name,buildJsonObject{put("origin",clazz.protectionDomain.codeSource.location.toString());val bytes=checkNotNull(clazz.getResourceAsStream("/"+name.replace('.','/')+".class")).use{it.readBytes()};put("sha256Bytes",MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)})})}})
            }
            Files.writeString(root.resolve("root-message-proof.json"),result.toString())
            withContext(Dispatchers.Main){exit.get()?.invoke()};fixtureJob.cancel()
        }
    }
}
fun main(args:Array<String>) {
    require(args.size==1);val root=Path.of(args[0]).toAbsolutePath().normalize();require(Files.isDirectory(root))
    DesktopLibrary().setAutomaticUpdates(false)
    RootMessageFixture(root).mount()
}
