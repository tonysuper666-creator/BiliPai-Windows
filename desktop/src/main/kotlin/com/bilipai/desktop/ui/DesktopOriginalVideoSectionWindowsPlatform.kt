package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import com.android.purebilibili.core.store.DanmakuSettings as OriginalSettings
import com.android.purebilibili.danmaku.engine.DanmakuItem
import com.android.purebilibili.feature.anime4k.*
import com.android.purebilibili.feature.video.danmaku.*
import com.android.purebilibili.feature.video.ui.components.VideoViewportLayout
import com.bilipai.desktop.data.DesktopDynamicCardOperations
import com.bilipai.desktop.danmaku.DanmakuOverlay
import com.bilipai.desktop.danmaku.DanmakuSettings
import com.bilipai.desktop.danmaku.DesktopOwnedWebMaskSource
import com.bilipai.desktop.player.*
import com.bilipai.desktop.plugins.DesktopVideoEnhancementConfiguration
import com.bilipai.desktop.settings.DesktopOriginalDanmakuPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.jetbrains.skia.Image
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Rect as SkiaRect
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.math.roundToInt

/** Every external effect belongs to the real Root owner. No default/no-op resource is supplied. */
internal class DesktopOriginalVideoSectionWindowsResources(
    val native: DesktopOriginalVideoNativeOwner,
    val isForegroundOwned: () -> Boolean,
    val isPipActive: StateFlow<Boolean>,
    /** Same Repository Store -> entry, no native lock held while action enters Overlay. */
    val withPresentationAdmission: (DesktopOriginalVideoAcceptedPublication, () -> Unit) -> Boolean,
    /** Atomic published-ref + epoch/authorization-revision + entry read only. Called under Overlay lock:
     * must never enter Store, entry monitor, MPV or another actor. Overlay verifies the native version itself. */
    val isPresentationCurrent: (DesktopOriginalVideoAcceptedPublication) -> Boolean,
    val entryScope: CoroutineScope,
    val settingsContext: DesktopOriginalPlayerSettingsContext,
    val preferences: DesktopOriginalDanmakuPreferences,
    val overlay: DanmakuOverlay,
    val enhancementConfiguration: DesktopVideoEnhancementConfiguration,
    val enhancement: DesktopVideoEnhancementSession,
    val cloudSync: () -> DesktopDanmakuCloudSyncBinding,
    val gallery: DesktopDynamicImageAssets,
    val scratchDirectory: Path,
    val hasPlaylistNext: () -> Boolean,
    val acquireScreenshotOrientationLock: (Boolean) -> AutoCloseable,
    val acquireKeepAwake: (Boolean) -> AutoCloseable,
    val onForegroundWindowAvailability: (Any, Boolean) -> Unit,
    val onNativeForegroundWindowAvailability: (java.awt.Window,Boolean) -> Unit,
    val maskSource: (DesktopOriginalVideoAcceptedPublication) -> DesktopOwnedWebMaskSource?,
    val operations: (DesktopOriginalVideoAcceptedPublication, Job) -> DesktopDynamicCardOperations,
    val recordDanmakuToggle: (Boolean) -> Unit,
    val reportUnsupported: (String) -> Unit,
)

/** Remember once with the same assembly/WindowPlatforms. The movable carrier keeps the native peer
 * between Root bootstrap and the complete original Section foreground, including its gestures. */
@Composable
internal fun rememberDesktopOriginalVideoSectionWindowsPlatform(
    resources: DesktopOriginalVideoSectionWindowsResources,
): DesktopOriginalVideoSectionWindowsPlatform {
    val latest by rememberUpdatedState(resources)
    val layout = remember(resources.native) { DesktopOriginalNativeViewportState { latest.isForegroundOwned() } }
    val carrier = remember(resources.native, layout) {
        movableContentOf<Modifier, @Composable () -> Unit> { modifier, foreground ->
            val current = latest
            val native by current.native.player.state.collectAsState()
            val pip by current.isPipActive.collectAsState()
            // Reading StateFlow invalidates the current version while the same carrier/peer survives.
            @Suppress("UNUSED_VARIABLE") val invalidator = native.ready to native.loading
            layout.setNativeAttached(!pip)
            DesktopOriginalPlayerSurface(current.native.player, current.native.current()?.sourceVersion,
                current.isForegroundOwned, modifier, foreground, current.onForegroundWindowAvailability, layout,
                current.onNativeForegroundWindowAvailability,!pip)
        }
    }
    return remember(resources, layout, carrier) { DesktopOriginalVideoSectionWindowsPlatform(resources, layout, carrier) }
}

// The outer fullscreen foreground and its inner original Section share this exact
// adapter/Popup. An inner call renders inside the existing portal, never attaches
// the same movable native carrier a second time in one composition frame.
private val LocalDesktopOriginalSectionForegroundOwner = staticCompositionLocalOf<Any?> { null }

internal class DesktopOriginalVideoSectionWindowsPlatform(
    private val resources: DesktopOriginalVideoSectionWindowsResources,
    private val layout: DesktopOriginalNativeViewportState,
    private val carrier: @Composable (Modifier, @Composable () -> Unit) -> Unit,
) : DesktopOriginalVideoSectionPlatform {
    private var submittedViewport:Pair<DesktopOriginalVideoAcceptedPublication,DesktopNativeVideoViewportTransform>? = null
    private var viewportPublication:DesktopOriginalVideoAcceptedPublication? = null
    private var borrowedViewportToken: Any? = null // Same presentation admission; not a source authority.
    private var reportedNativeReveal=false
    private val player get() = resources.native.player
    private fun expected(): DesktopOriginalVideoAcceptedPublication = resources.native.current()
        ?: throw CancellationException("Original Section has no accepted source")
    private fun owns(value: DesktopOriginalVideoAcceptedPublication): Boolean = resources.isForegroundOwned() && resources.native.isCurrent(value)
    private fun write(action: (DesktopOriginalVideoAcceptedPublication) -> Unit): Boolean {
        val value = resources.native.current() ?: return false
        return resources.isForegroundOwned() && resources.native.admitPlaybackDispatch(value) { action(value) }
    }
    private fun presentationWrite(action:(DesktopOriginalVideoAcceptedPublication)->Unit):Boolean {
        val value=resources.native.current() ?: return false
        return resources.isForegroundOwned() && resources.withPresentationAdmission(value) {action(value)}
    }
    private suspend fun checkpoint(value: DesktopOriginalVideoAcceptedPublication) {
        currentCoroutineContext().ensureActive()
        if (!owns(value)) throw CancellationException("Original Section source retired")
    }
    @Composable fun InitialNativeSurface(modifier: Modifier) {
        if (LocalDesktopOriginalVideoNativeCarrierActive.current) carrier(modifier) {}
    }
    suspend fun awaitNativeInitialization() {
        player.state.first { state ->
            currentCoroutineContext().ensureActive()
            if (!resources.isForegroundOwned()) throw CancellationException("Original Section bootstrap retired")
            if (!state.ready && state.error != null) throw IllegalStateException(state.error)
            state.ready
        }
    }
    @Composable override fun RenderPlayerForeground(content: @Composable () -> Unit) {
        if (!LocalDesktopOriginalVideoNativeCarrierActive.current ||
            LocalDesktopOriginalSectionForegroundOwner.current === this) {
            content()
        } else {
            carrier(Modifier.fillMaxSize()) {
                CompositionLocalProvider(LocalDesktopOriginalSectionForegroundOwner provides this) {
                    content()
                }
            }
        }
    }
    override val settingsContext get() = resources.settingsContext
    override val viewportAttached get() = layout.attached && resources.isForegroundOwned() && !resources.isPipActive.value
    override val viewportHeightPixels get() = layout.heightPixels
    override val statusBarInsetPixels get() = 0 // Windows client area has no Android system status bar.
    override val danmakuReferencePixels get() = resources.overlay.maximumDisplayShortSidePx()
    override val danmakuPreferences get() = resources.preferences
    override val cloudSync get() = resources.cloudSync()
    override val enhancementConfig get() = resources.enhancementConfiguration.configState
    override val enhancementState get() = resources.enhancement.state
    override val enhancementActions = object : DesktopOriginalSectionEnhancementActions {
        override fun setAlgorithm(value: VideoEnhancementAlgorithm) { write { resources.enhancementConfiguration.setAlgorithm(value) } }
        override fun setPreset(value: Anime4KPreset) { write { resources.enhancementConfiguration.setPreset(value) } }
        override fun setFsrSharpness(value: Float) { write { resources.enhancementConfiguration.setFsrSharpness(value) } }
    }
    override val volume = object : DesktopOriginalSectionVolumePort {
        override fun currentStep(): Int { expected(); return player.state.value.volume.roundToInt().coerceIn(0, 100) }
        override fun maximumStep() = 100 // Existing MPV application volume, not Windows system-master volume.
        override fun setStep(step: Int) { write { player.setVolume(step.coerceIn(0,100).toDouble()) } }
        override fun toggleMute() { write { player.toggleMuted() } }
    }
    override val viewport = object : DesktopOriginalPlayerViewportPort {
        override var resizeMode: Int = 0
        override val width get() = player.surface.width
        override val height get() = player.surface.height
        override fun requestLayout() = post { player.surface.revalidate(); player.surface.doLayout() }
        override fun requestContentLayout() = requestLayout()
        override fun invalidate() = post { player.surface.repaint() }
        override fun post(block: () -> Unit) { SwingUtilities.invokeLater { if(resources.isForegroundOwned() && !resources.isPipActive.value) block() } }
        override fun postOnFrame(block: () -> Unit) {
            post { Timer(16) { event -> (event.source as Timer).stop(); if(resources.isForegroundOwned()) block() }.apply { isRepeats=false; start() } }
        }
    }
    override fun hasPlaylistNext() = resources.isForegroundOwned() && resources.hasPlaylistNext()
    override fun setScreenshotAndOrientationLock(locked: Boolean) = resources.acquireScreenshotOrientationLock(locked)
    override fun setViewportActive(active: Boolean) {
        presentationWrite {
            if (resources.overlay.setOriginalSectionViewport(it.sourceVersion, active)) {
                viewportPublication = it; borrowedViewportToken = null
            }
        }
    }
    override fun releaseViewportForThisEntry() {
        // A normal Section disposer must not release a newer borrowed Pager view.
        if (borrowedViewportToken == null) {
            viewportPublication?.let { resources.overlay.releaseOriginalSectionViewport(it.sourceVersion) }
            viewportPublication = null; layout.reset()
        }
    }
    override fun acquireViewportLease(): AutoCloseable {
        val token = Any()
        var fixed: DesktopOriginalVideoAcceptedPublication? = null
        presentationWrite {
            if (resources.overlay.setOriginalSectionViewport(it.sourceVersion, true)) {
                viewportPublication = it; borrowedViewportToken = token; fixed = it
            }
        }
        val released = AtomicBoolean()
        return AutoCloseable {
            if (released.compareAndSet(false, true) && fixed != null) {
                // Existing short Store -> entry admission; native lock is released
                // before the Overlay call. Old source/handle cannot reset new layout.
                presentationWrite { current ->
                    if (current === fixed && viewportPublication === fixed && borrowedViewportToken === token) {
                        resources.overlay.releaseOriginalSectionViewport(current.sourceVersion)
                        viewportPublication = null; borrowedViewportToken = null; layout.reset()
                    }
                }
            }
        }
    }
    override fun recoverViewport(identity: String, fullscreen: Boolean, pip: Boolean, predictiveBackGeneration: Int) { viewport.requestLayout(); viewport.invalidate() }
    override fun readViewportBrightness(): Float { val value=expected(); return resources.overlay.viewportBrightnessFor(value.sourceVersion) ?: 1f }
    override fun setViewportBrightness(value: Float, requestSystemBrightness: Boolean) {
        require(value.isFinite())
        presentationWrite { resources.overlay.setViewportBrightness(it.sourceVersion, value.coerceIn(0.05f,1f)) }
        // This is the actual source-owned viewport dimmer. No claim of physical monitor brightness.
    }
    override fun setCurrentVideoEnhancementEnabled(enabled: Boolean) { write { resources.enhancement.bindVideoIdentity(it.request.bvid,it.sourceVersion); resources.enhancement.setCurrentVideoEnabled(enabled) } }
    override fun recordDanmakuToggle(enabled: Boolean) { write { resources.recordDanmakuToggle(enabled) } }

    override val danmaku = object : DesktopOriginalSectionDanmakuPort {
        private fun settings() = resources.overlay.currentSettings
        private fun update(change: (DanmakuSettings)->DanmakuSettings) { presentationWrite { resources.overlay.applySettings(change(settings())) } }
        override var isEnabled: Boolean get()=settings().enabled; set(value) { update { it.copy(enabled=value) } }
        override var opacity:Float get()=settings().opacity;set(value){update{it.copy(opacity=value)}}
        override var fontScale:Float get()=settings().fontScale;set(value){update{it.copy(fontScale=value)}}
        override var fontWeight:Int get()=settings().fontWeight;set(value){update{it.copy(fontWeight=value)}}
        override var speedFactor:Float get()=settings().speedFactor;set(value){update{it.copy(speedFactor=value)}}
        override var displayArea:Float get()=settings().displayAreaRatio;set(value){update{it.copy(displayAreaRatio=value)}}
        override var strokeWidth:Float get()=settings().strokeWidth;set(value){update{it.copy(strokeWidth=value,strokeEnabled=value>0)}}
        override var lineHeight:Float get()=settings().lineHeight;set(value){update{it.copy(lineHeight=value)}}
        override var scrollDurationSeconds:Float get()=settings().scrollDurationSeconds;set(value){update{it.copy(scrollDurationSeconds=value)}}
        override var staticDurationSeconds:Float get()=settings().staticDurationSeconds;set(value){update{it.copy(staticDurationSeconds=value)}}
        override var scrollFixedVelocity:Boolean get()=settings().scrollFixedVelocity;set(value){update{it.copy(scrollFixedVelocity=value)}}
        override var staticDanmakuToScroll:Boolean get()=settings().staticDanmakuToScroll;set(value){update{it.copy(staticDanmakuToScroll=value)}}
        override var massiveMode:Boolean get()=settings().massiveMode;set(value){update{it.copy(massiveMode=value)}}
        override val advancedDanmakuFlow get()=resources.overlay.advancedItems
        override val commandDanmakuFlow get()=resources.overlay.commandItems
        override fun updateSettings(settings:OriginalSettings) {update{projectOriginalDanmakuRendererSettings(it,settings)}}
        override fun clear(){presentationWrite{resources.overlay.seekOriginalSection(it.sourceVersion,(player.state.value.positionSeconds*1000).toLong(),false)}}
        override fun seekTo(positionMs:Long){presentationWrite{resources.overlay.seekOriginalSection(it.sourceVersion,positionMs,false)}}
        override fun prepareForSeekScrub(){presentationWrite{resources.overlay.seekOriginalSection(it.sourceVersion,null,true)}}
        override fun cancelSeekScrub(){presentationWrite{resources.overlay.seekOriginalSection(it.sourceVersion,(player.state.value.positionSeconds*1000).toLong(),false)}}
        override fun getLoadedDanmakuList():List<DanmakuItem>{val value=expected();return resources.overlay.poolSourceFor(value.request.cid,value.sourceVersion)?.let(::originalDanmakuPoolItems).orEmpty()}
        override suspend fun loadDanmaku(cid:Long,aid:Long,durationHintMs:Long,bvid:String){
            val value=expected();checkpoint(value);require(value.request.cid==cid && value.request.bvid==bvid)
            val mask=resources.maskSource(value)?.let {
                require(it.bvid==bvid && it.cid==cid && it.sourceVersion==value.sourceVersion && it.accountEpoch==value.accountEpoch)
                DesktopOwnedWebMaskSource(it.bvid,it.cid,it.sourceVersion,it.accountEpoch,{resources.isPresentationCurrent(value)},it.metadata)
            }
            resources.overlay.load(cid,aid,durationHintMs.coerceAtLeast(0)/1000.0,value.sourceVersion,mask){resources.isPresentationCurrent(value)}
            checkpoint(value)
        }
    }
    private suspend fun capture(value:DesktopOriginalVideoAcceptedPublication):ByteArray = withContext(Dispatchers.IO) {
        checkpoint(value)
        val root=resources.scratchDirectory.toRealPath();require(Files.isDirectory(root))
        val target=root.resolve("bilipai-section-${UUID.randomUUID()}.png")
        try {
            player.captureScreenshotForSource(value.nativeSource,target,true)
            checkpoint(value);val size=Files.size(target);require(size in 1..32L*1024*1024)
            Files.newInputStream(target).use { input ->
                val output=java.io.ByteArrayOutputStream();val chunk=ByteArray(64*1024)
                while(true){checkpoint(value);val count=input.read(chunk);if(count<0)break;require(output.size().toLong()+count<=32L*1024*1024);output.write(chunk,0,count)}
                output.toByteArray()
            }
        } finally {Files.deleteIfExists(target)}
    }
    override suspend fun captureAmbientFrame(targetWidth:Int,targetHeight:Int):ImageBitmap? {
        require(targetWidth in 1..2048 && targetHeight in 1..2048)
        val value=expected();val bytes=capture(value);checkpoint(value)
        return desktopOriginalSectionAmbientFrame(bytes,targetWidth,targetHeight){checkpoint(value)}
    }
    override suspend fun captureAndSaveScreenshot(videoWidth:Int,videoHeight:Int,title:String):Boolean {
        val value=expected();val bytes=capture(value);checkpoint(value)
        val safeTitle=title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"),"_").take(80).ifBlank{"BiliPai"}
        val name="$safeTitle-${System.currentTimeMillis()}-${UUID.randomUUID()}.png"
        val caller=currentCoroutineContext()
        return resources.gallery.saveNativeFrameBytes(bytes,name,{caller.isActive && owns(value)}) { block -> resources.withPresentationAdmission(value) {caller.ensureActive();block()} }
    }
    private suspend fun <T> request(action:suspend(DesktopDynamicCardOperations)->Result<T>):Result<T> {
        val value=expected();checkpoint(value);val job=currentCoroutineContext().job
        val result=action(resources.operations(value,job));checkpoint(value);return result
    }
    override suspend fun submitGradeDanmaku(aid:Long,cid:Long,progress:Long,gradeId:String,gradeScore:Int):Result<Unit>{
        require(cid==expected().request.cid);return request{it.submitGradeDanmaku(aid,cid,progress,gradeId,gradeScore)}
    }
    override suspend fun submitVote(voteId:Long,optionIndexes:List<Int>):Result<Unit> = request { it.submitVote(voteId,optionIndexes,"").map { Unit } }

    @Composable override fun NativeViewport(modifier:Modifier,layout:VideoViewportLayout,resizeMode:Int,revealAlpha:Float,
        revealScale:Float,freeScale:Float,panX:Float,panY:Float,flipHorizontal:Boolean,flipVertical:Boolean,visible:Boolean,keepAwake:Boolean) {
        val value=resources.native.current()
        DisposableEffect(value,keepAwake) { val lease=resources.acquireKeepAwake(keepAwake);onDispose{lease.close()} }
        SideEffect {
            val transform=DesktopNativeVideoViewportTransform(resizeMode,freeScale,panX/layout.width.coerceAtLeast(1),panY/layout.height.coerceAtLeast(1),flipHorizontal,flipVertical)
            if(value!=null && owns(value) && !resources.isPipActive.value &&
                (submittedViewport?.first!==value || submittedViewport?.second!=transform))write {
                    if(player.setOriginalVideoViewport(it.nativeSource,transform))submittedViewport=it to transform
                }
            if((revealAlpha!=1f || revealScale!=1f) && !reportedNativeReveal){reportedNativeReveal=true;resources.reportUnsupported("HWND video does not support texture alpha/reveal-scale; original cover is retained")}
        }
        Box(modifier.onGloballyPositioned { coordinates -> if(resources.isForegroundOwned() && !resources.isPipActive.value)this.layout.update(coordinates.boundsInRoot(),visible) })
    }
    @Composable override fun NativeDanmakuSurface(viewport:DanmakuViewport,modifier:Modifier) {
        // The same native overlay reads actual Canvas/DPI/OSD geometry in its sole timer.
        SideEffect {presentationWrite{resources.overlay.bindOriginalSectionDanmakuViewport(it.sourceVersion,viewport)}}
        var coordinates by remember {mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null)}
        Box(modifier.onGloballyPositioned {coordinates=it}.pointerInput(resources.overlay,resources.native) {
            awaitEachGesture {
                val down=awaitFirstDown(requireUnconsumed=true)
                val publication=resources.native.current() ?: return@awaitEachGesture
                val origin=coordinates?.takeIf {it.isAttached}?.localToWindow(androidx.compose.ui.geometry.Offset.Zero)
                    ?: return@awaitEachGesture
                if(!owns(publication) || resources.isPipActive.value)return@awaitEachGesture
                val hit=resources.overlay.beginOriginalDanmakuPointer(publication.sourceVersion,
                    (origin.x+down.position.x).toDouble(),(origin.y+down.position.y).toDouble()) ?: return@awaitEachGesture
                down.consume()
                try {
                    var released:androidx.compose.ui.input.pointer.PointerInputChange?=null
                    var cancelled=false
                    while(released==null && !cancelled) {
                        val event=awaitPointerEvent()
                        val change=event.changes.firstOrNull {it.id==down.id}
                        if(change==null || change.isConsumed || event.changes.size!=1 ||
                            (change.position-down.position).getDistance()>viewConfiguration.touchSlop ||
                            !owns(publication) || resources.isPipActive.value)cancelled=true
                        else {change.consume();if(!change.pressed)released=change}
                    }
                    val up=released
                    val latestOrigin=coordinates?.takeIf {it.isAttached}?.localToWindow(androidx.compose.ui.geometry.Offset.Zero)
                    if(!cancelled && up!=null && latestOrigin==origin && owns(publication))
                        resources.overlay.finishOriginalDanmakuPointer(hit,(origin.x+up.position.x).toDouble(),(origin.y+up.position.y).toDouble())
                } finally {resources.overlay.cancelOriginalDanmakuPointer(hit)}
            }
        })
    }
}

/** Creates an owned Bitmap copy; source Skia Image/Bitmap/Canvas temporaries close before returning. */
internal suspend fun desktopOriginalSectionAmbientFrame(bytes:ByteArray,width:Int,height:Int,
    checkpoint:suspend()->Unit):ImageBitmap = withContext(Dispatchers.Default) {
    require(width in 1..2048 && height in 1..2048 && bytes.size.toLong() in 1..32L*1024*1024)
    checkpoint()
    Image.makeFromEncoded(bytes).use { input ->
        require(input.width.toLong()*input.height<=32L*1024*1024)
        Bitmap().use { output ->
            check(output.allocN32Pixels(width,height))
            Canvas(output).use { canvas -> canvas.drawImageRect(input,SkiaRect.makeWH(width.toFloat(),height.toFloat())) }
            checkpoint()
            Image.makeFromBitmap(output).use { resized -> resized.toComposeImageBitmap() }
        }
    }
}
