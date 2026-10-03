@file:OptIn(androidx.compose.runtime.tooling.ComposeToolingApi::class, androidx.compose.runtime.ExperimentalComposeRuntimeApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.rootsearchfixture

import androidx.compose.runtime.*
import androidx.compose.runtime.tooling.*
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.semantics.*
import androidx.compose.ui.InternalComposeUiApi
import com.android.purebilibili.feature.search.*
import com.android.purebilibili.data.model.response.SearchType
import java.awt.event.*
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

private class RootSearchFixture(private val root: Path) {
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
    private var pointerEvents = 0
    private var keyboardEvents = 0
    private var mountedSearch = false
    private var hotKeywordAccepted = false
    private fun checkThat(name: String, result: Boolean) {
        checks += buildJsonObject { put("name",name);put("passed",result) }
        println("${if(result) "PASS" else "FAIL"} $name");check(result) { name }
    }
    private suspend fun <T:Any> awaitReference(name:String,read:()->T?):T = withTimeout(45_000) {
        while(true) { withContext(Dispatchers.Main){read()}?.let{return@withTimeout it};delay(40) }
        @Suppress("UNREACHABLE_CODE") error(name)
    }
    private suspend fun awaitCondition(name:String,read:()->Boolean) = withTimeout(35_000) {
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
        val window=checkNotNull(windowRef.get());val canvas=checkNotNull(findLayer(window)).canvas
        val point=node.boundsInRoot.center
        check(point.x>=0&&point.y>=0&&point.x<canvas.width&&point.y<canvas.height)
        val listeners=canvas.mouseListeners;check(listeners.isNotEmpty()) { "Owned Compose Canvas has no actual mouse listeners" }
        val now=System.currentTimeMillis()
        val press=MouseEvent(canvas,MouseEvent.MOUSE_PRESSED,now,InputEvent.BUTTON1_DOWN_MASK,point.x.toInt(),point.y.toInt(),1,false,MouseEvent.BUTTON1)
        val release=MouseEvent(canvas,MouseEvent.MOUSE_RELEASED,now+35,0,point.x.toInt(),point.y.toInt(),1,false,MouseEvent.BUTTON1)
        listeners.forEach{it.mousePressed(press)};listeners.forEach{it.mouseReleased(release)};pointerEvents+=2
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
    private fun textNode(label:String)=nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.Text)?.any{t->t.text==label}==true }
    private fun capture(name:String) {
        check(SwingUtilities.isEventDispatchThread());val window=checkNotNull(windowRef.get());window.renderImmediately()
        val bitmap=checkNotNull(findLayer(window)?.screenshot())
        try { Image.makeFromBitmap(bitmap).use { image -> checkNotNull(image.encodeToData(EncodedImageFormat.PNG)).use { Files.write(root.resolve(name+".png"),it.bytes) } } }
        finally { bitmap.close() }
    }
    private suspend fun closeProduct() {
        if(closing.compareAndSet(false,true))withContext(NonCancellable) {
            withContext(Dispatchers.Main){checkNotNull(shutdown.get()) { "Real product shutdown absent" }.invoke()}
            withContext(Dispatchers.IO){images.close();player.close()}
        }
    }
    fun mount()=application(exitProcessOnExit=false) {
        val state=rememberWindowState(width=1100.dp,height=900.dp,position=WindowPosition((-16000).dp,(-16000).dp))
        Window(onCloseRequest={fixtureScope.launch{closeProduct();withContext(Dispatchers.Main){exit.get()?.invoke()}}},
            title="BiliPai fixture-owned actual Root Search",state=state) {
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
            val search=awaitReference("actual remembered original Search Root"){refs.find<DesktopOriginalSearchRoot>()}
            val searchKey=BiliPaiNavKey.Search(openId=System.nanoTime())
            withContext(Dispatchers.Main) {
                checkThat("Actual Window typed Search route accepted",environment.commands.push(searchKey))
            }
            awaitCondition("actual full Search rendered") {
                environment.currentKey()==searchKey&&nodes().any{it.config.getOrNull(SemanticsActions.SetText)!=null}&&
                    (readField(search,"owners") as Map<*,*>).values.filterIsInstance<DesktopOriginalSearchEntry>().any{it.identity.key==searchKey}
            }
            val entry=withContext(Dispatchers.Main){search.entry(searchKey,false)}
            val vm=checkNotNull(entry.viewModel)
            withContext(Dispatchers.Main) {
                mountedSearch=true
                checkThat("Original VM belongs to already composed physical Root entry",entry.environment.owns()&&search.routes.root.entry.gate.owns())
                val field=nodes().first{it.config.getOrNull(SemanticsActions.SetText)!=null};click(field)
                capture("actual-root-search-landing")
            }
            delay(120)
            val keyword="RootSearchFixtureInput"
            for(ch in keyword) { withContext(Dispatchers.Main){key(KeyEvent.KEY_TYPED,KeyEvent.VK_UNDEFINED,ch)};delay(15) }
            awaitCondition("actual original key input") {vm.uiState.value.query==keyword}
            withContext(Dispatchers.Main) {
                checkThat("Owned Root Canvas keyboard enters original Search field",vm.uiState.value.query==keyword)
                key(KeyEvent.KEY_PRESSED,KeyEvent.VK_ENTER,'\n');key(KeyEvent.KEY_RELEASED,KeyEvent.VK_ENTER,'\n')
            }
            awaitCondition("actual Enter search and same history") {vm.uiState.value.showResults&&vm.uiState.value.historyList.any{it.keyword==keyword}}
            withContext(Dispatchers.Main) {
                checkThat("Original Enter writes Root's same history owner",vm.uiState.value.showResults&&vm.uiState.value.historyList.any{it.keyword==keyword})
                capture("actual-root-search-results")
            }
            awaitCondition("UP tab available"){nodes().any{it.config.getOrNull(SemanticsProperties.Text)?.any{t->t.text.startsWith("UP主")}==true}}
            withContext(Dispatchers.Main){click(nodes().first{it.config.getOrNull(SemanticsProperties.Text)?.any{t->t.text.startsWith("UP主")}==true})}
            awaitCondition("actual original UP tab") {vm.uiState.value.searchType==SearchType.UP}
            withContext(Dispatchers.Main) {
                checkThat("Actual Root pointer selects original UP result/filter body",vm.uiState.value.searchType==SearchType.UP)
                capture("actual-root-search-up-filters")
                // Actual Root route cover must preserve the exact VM, query and selected tab.
                checkThat("Actual settings cover accepted",environment.commands.push(BiliPaiNavKey.Settings))
            }
            awaitCondition("actual settings cover"){environment.currentKey()==BiliPaiNavKey.Settings}
            withContext(Dispatchers.Main){checkThat("Cover preserves same original Search owner",search.entry(searchKey,false)===entry&&entry.environment.owns());checkThat("Actual Root back accepted",environment.commands.back())}
            awaitCondition("actual same Search return"){environment.currentKey()==searchKey}
            withContext(Dispatchers.Main){checkThat("Original query/filter state retained on actual Root return",search.entry(searchKey,false)===entry&&vm.uiState.value.query==keyword&&vm.uiState.value.searchType==SearchType.UP);checkThat("Actual typed keyword route accepted",environment.commands.push(BiliPaiNavKey.Search("RootTypedKeyword",openId=System.nanoTime())))}
            val typed=awaitReference("actual typed keyword entry") {
                (readField(search,"owners") as Map<*,*>).values.filterIsInstance<DesktopOriginalSearchEntry>().firstOrNull{(it.identity.key as? BiliPaiNavKey.Search)?.keyword=="RootTypedKeyword"}
            }
            awaitCondition("original consumes typed keyword"){typed.viewModel?.uiState?.value?.query=="RootTypedKeyword"&&typed.viewModel?.uiState?.value?.showResults==true}
            withContext(Dispatchers.Main){checkThat("Typed Search keyword reaches original full VM",typed.viewModel?.uiState?.value?.query=="RootTypedKeyword");checkThat("Pop keyword entry accepted",environment.commands.back())}
            awaitCondition("popped original entry retired"){environment.currentKey()==searchKey&&!typed.environment.owns()}
            withContext(Dispatchers.Main){checkThat("Actual popped entry refuses late reducer/history admission",!typed.environment.commit{error("Retired action must not run")});checkThat("Original route returns to same owner",search.entry(searchKey,false)===entry)}
            // Clear the actual result view using its original back control, not a direct VM setter.
            awaitCondition("original clear-search action available"){nodes().any{it.config.getOrNull(SemanticsProperties.ContentDescription)?.any{d->d=="清空"||d=="Clear"}==true}}
            withContext(Dispatchers.Main){click(nodes().first{it.config.getOrNull(SemanticsProperties.ContentDescription)?.any{d->d=="清空"||d=="Clear"}==true})}
            awaitCondition("original landing restored"){textNode("完整榜单")!=null}
            withContext(Dispatchers.Main){click(checkNotNull(textNode("完整榜单")))}
            awaitCondition("real original Trending Root"){environment.currentKey()==BiliPaiNavKey.SearchTrending&&"bilibili 热搜" in labels()}
            withContext(Dispatchers.Main){checkThat("Actual landing callback pushes full original Trending Root",environment.currentKey()==BiliPaiNavKey.SearchTrending);capture("actual-root-search-trending")}
            val trending=withContext(Dispatchers.Main){search.entry(BiliPaiNavKey.SearchTrending,false)}
            // Real guest network availability is separate: no synthetic response/client is injected.
            val hot=withTimeoutOrNull(20_000){while(true){val value=withContext(Dispatchers.Main){trending.trending?.uiState?.value};if(value!=null&&!value.isLoading)return@withTimeoutOrNull value.items.firstOrNull()?.keyword;delay(100)};null}
            if(!hot.isNullOrBlank()) {
                awaitCondition("real returned hot keyword node"){textNode(hot)!=null}
                withContext(Dispatchers.Main){click(checkNotNull(textNode(hot)))}
                awaitCondition("actual hot keyword typed route"){(environment.currentKey() as? BiliPaiNavKey.Search)?.keyword==hot}
                withContext(Dispatchers.Main){hotKeywordAccepted=true;checkThat("Actual Trending keyword callback returns typed Search",(environment.currentKey() as? BiliPaiNavKey.Search)?.keyword==hot)}
            }
            withContext(Dispatchers.Main){checkThat("Input uses owned Window only",pointerEvents>=8&&keyboardEvents>=keyword.length+2);capture("actual-root-search-final")}
            closeProduct()
            withContext(Dispatchers.Main){checkThat("Actual Root shutdown retires Search VM and original route owner",!entry.environment.owns()&&!trending.environment.owns())}
        } catch(error:Throwable){failure=error;error.printStackTrace()}
        finally {
            try{closeProduct()}catch(error:Throwable){if(failure==null)failure=error else failure.addSuppressed(error)}
            withContext(Dispatchers.Main){refs.close()}
            val names=listOf("com.bilipai.desktop.DesktopShellKt","com.bilipai.desktop.ui.DesktopOriginalRootStackKt","com.bilipai.desktop.ui.DesktopOriginalSearchRoot","com.bilipai.desktop.ui.DesktopOriginalSearchEnvironment","com.android.purebilibili.feature.search.SearchViewModel","com.android.purebilibili.feature.search.SearchScreenKt","com.android.purebilibili.feature.search.SearchTrendingScreenKt","com.bilipai.desktop.data.DesktopSearchPreferences")
            val result=buildJsonObject {
                put("status",if(failure==null)"PASS_ACTUAL_ROOT_SEARCH_UI"else"FAIL");put("assertions",checks.size);put("checks",JsonArray(checks));put("error",failure?.javaClass?.name.orEmpty())
                put("RootMounted",mountedSearch);put("productionOverrides",0);put("actualOwnedCanvasPointerEvents",pointerEvents);put("actualOwnedCanvasKeyboardEvents",keyboardEvents)
                put("actualBusinessHotKeywordAccepted",hotKeywordAccepted);put("realAccount",false);put("globalInput",false);put("syntheticApi",false);put("sameRootHttpClient",true)
                put("businessNetworkAllAccepted",false);put("nativePlaybackAccepted",false);put("newEXEDeployed",false);put("unchangedMainEntry",false)
                put("classOrigins",buildJsonObject{names.forEach{name->val clazz=Class.forName(name);put(name,buildJsonObject{put("origin",clazz.protectionDomain.codeSource.location.toString());val bytes=checkNotNull(clazz.getResourceAsStream("/"+name.replace('.','/')+".class")).use{it.readBytes()};put("sha256Bytes",MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)})})}})
            }
            Files.writeString(root.resolve("root-search-proof.json"),result.toString())
            withContext(Dispatchers.Main){exit.get()?.invoke()};fixtureJob.cancel()
        }
    }
}
fun main(args:Array<String>) {
    require(args.size==1);val root=Path.of(args[0]).toAbsolutePath().normalize();require(Files.isDirectory(root));RootSearchFixture(root).mount()
}
