package com.bilipai.desktop.danmaku

import com.android.purebilibili.feature.video.danmaku.resolveDanmakuViewport
import com.android.purebilibili.feature.video.danmaku.DanmakuViewport
import com.android.purebilibili.feature.video.danmaku.DesktopOriginalWebMaskOwner
import com.android.purebilibili.feature.video.danmaku.resolveDanmakuDriftSyncIntervalMs
import com.android.purebilibili.danmaku.engine.DanmakuRenderConfig
import com.android.purebilibili.feature.video.danmaku.CommandDanmakuItem
import com.android.purebilibili.feature.live.LiveDanmakuItem
import com.android.purebilibili.core.plugin.DanmakuStyle
import com.android.purebilibili.danmaku.parser.bas.BasDanmaku
import com.android.purebilibili.danmaku.parser.bas.BasTarget
import com.bilipai.desktop.player.MpvPlayer
import com.bilipai.desktop.player.OwnedPlaybackSourceSnapshot
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import okhttp3.OkHttpClient
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Frame
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsDevice
import java.awt.GraphicsEnvironment
import java.awt.RenderingHints
import java.awt.LinearGradientPaint
import java.awt.Window
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ArrayBlockingQueue
import javax.swing.JComponent
import javax.swing.JWindow
import javax.swing.SwingUtilities
import javax.swing.Timer

/** A transparent mouse-through native window above mpv's child HWND. */
class DanmakuOverlay internal constructor(
    private val player: MpvPlayer,
    private val renderPlatform:DesktopOriginalDanmakuRenderPlatform,
    httpClient: OkHttpClient = ApiDesktopDanmakuSource.publicClient(),
    private val source: DesktopDanmakuSource = ApiDesktopDanmakuSource(httpClient),
    // Explicit independent embedding/fixture permit. Main leaves this absent:
    // its BAS publication requires the exact Root source/account/entry binding.
    private val basStandaloneAdmission: ((OwnedPlaybackSourceSnapshot, () -> Unit) -> Boolean)? = null,
) : DesktopOriginalWebMaskOwner(), AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val generation = AtomicLong()
    private val documentRevision = AtomicLong()
    private val requestLock = Any()
    // The existing document-processing ticket, distinct from local raw-list
    // revisions. Appending a self item must not cancel/re-run an in-flight plugin.
    private var documentInstallRevision=0L
    private var cacheMaintenance=false
    @Volatile private var windowLoader:DanmakuWindowLoader?=null
    private val requests = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loadJob: Job? = null
    private var windowJob: Job? = null
    private var pluginJob: Job? = null
    override val webMaskLock:Any get()=requestLock
    override val scope:CoroutineScope get()=requests
    override val cachedCid:Long get()=commandCid ?: 0L
    override val loadGeneration:Long get()=generation.get()
    override val webMaskEnabled:Boolean get()=settings.smartOcclusionEnabled && !cacheMaintenance && !liveMode && !closed.get()
    override val webMaskTransport:DesktopDanmakuSource get()=source
    override fun webMaskPositionMs():Long=(player.state.value.positionSeconds*1000).toLong().coerceAtLeast(0L)
    override fun invalidateWebMaskPaint(){SwingUtilities.invokeLater {if(!closed.get())panel.repaint()}}
    private var lastWebMaskRefreshNanos=Long.MIN_VALUE
    private var lastWebMaskPositionMs=Long.MIN_VALUE
    private val mutableError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = mutableError.asStateFlow()
    private val mutableCount = MutableStateFlow(0)
    val commentCount: StateFlow<Int> = mutableCount.asStateFlow()
    private val mutableCommands = MutableStateFlow(emptyList<CommandDanmakuItem>())
    val commandItems: StateFlow<List<CommandDanmakuItem>> = mutableCommands.asStateFlow()
    private val mutableAdvanced = MutableStateFlow(emptyList<com.android.purebilibili.danmaku.parser.AdvancedDanmakuData>())
    val advancedItems: StateFlow<List<com.android.purebilibili.danmaku.parser.AdvancedDanmakuData>> = mutableAdvanced.asStateFlow()
    private val sourcePresentation=DesktopDanmakuSourcePresentation(player)
    private val authorPresentation=DesktopDanmakuAuthorPresentation(player)
    internal fun bindOriginalAuthor(source:OwnedPlaybackSourceSnapshot,token:Any,mid:Long,stillOwned:()->Boolean):Boolean =
        authorPresentation.bind(source,token,mid,stillOwned)
    internal fun releaseOriginalAuthor(token:Any):Boolean = authorPresentation.release(token)
    /** Same full source/recovery identity; this short presentation write takes no Overlay monitor. */
    internal fun bindOriginalPresentation(expected:OwnedPlaybackSourceSnapshot,fullscreen:Boolean):Boolean =
        sourcePresentation.update(expected,fullscreen)
    @Volatile private var originalSectionViewport: Pair<Long,Boolean>? = null
    @Volatile private var originalSectionDanmakuViewport: Pair<Long,DanmakuViewport>? = null
    @Volatile private var originalSeekScrub: Pair<Long,Boolean>? = null
    fun bindOriginalSectionDanmakuViewport(version:Long,viewport:DanmakuViewport):Boolean = synchronized(requestLock) {
        if(closed.get() || !player.ownsSourceVersion(version)) false
        else {originalSectionDanmakuViewport=version to viewport;true}
    }
    fun setOriginalSectionViewport(version:Long,active:Boolean):Boolean = synchronized(requestLock) {
        if(closed.get() || !player.ownsSourceVersion(version)) false
        else {originalSectionViewport=version to active;true}
    }
    fun releaseOriginalSectionViewport(version:Long):Boolean = synchronized(requestLock) {
        if(originalSectionViewport?.first!=version) false
        else {originalSectionViewport=null;originalSectionDanmakuViewport=null;originalSeekScrub=null;true}
    }
    /** Seek scrubbing suppresses displayed items only, retaining the raw CID document and loader. */
    fun seekOriginalSection(version:Long,positionMs:Long?,scrubbing:Boolean):Boolean = synchronized(requestLock) {
        if(closed.get() || !player.ownsSourceVersion(version) || poolSourceVersion!=version) false
        else {
            originalSeekScrub=version to scrubbing
            val currentGeneration=generation.get()
            SwingUtilities.invokeLater {
                if(!closed.get() && currentGeneration==generation.get() && player.ownsSourceVersion(version)) {
                    scheduler.resetTimeline()
                    lastPosition=Double.NaN
                    if(positionMs!=null) {anchoredPosition=positionMs.coerceAtLeast(0L)/1000.0;displayTime=anchoredPosition;sampleTimeNanos=System.nanoTime()}
                    panel.repaint()
                }
            }
            true
        }
    }
    // This is transport ownership only; the command list remains the sole list.
    private var commandCid: Long? = null
    private var poolSourceVersion: Long? = null
    private val mutablePoolSourceRevision = MutableStateFlow(0L)
    val poolSourceRevision: StateFlow<Long> = mutablePoolSourceRevision.asStateFlow()
    private data class HotDocument(val generation:Long,val revision:Long,val comments:List<DanmakuComment>,
        // Processing provenance only: includes raw locals dropped by the plugin.
        // Their absence from a visible scheduler is not permission to inject them again.
        val processedLocalIds:Set<Int>)
    private var hotDocument:HotDocument? = null
    private data class HotReservation(val sourceVersion:Long,val token:Any,val heightPx:Float)
    @Volatile private var hotReservation:HotReservation? = null

    /** A projection of the actual installed plugin output, before duplicate merging.
     * The original raw pool remains untouched. Settings are applied at read time. */
    internal fun hotItemsFor(cid:Long,sourceVersion:Long):List<com.android.purebilibili.danmaku.engine.DanmakuItem> {
        val captured=synchronized(requestLock) {
            val document=hotDocument
            if(!originalDocumentOwned(sourceVersion) || commandCid!=cid || document==null ||
                document.generation!=generation.get() || document.revision!=documentRevision.get())null
            else document to settings
        } ?: return emptyList()
        val items=desktopOriginalHotDanmakuItems(captured.first.comments,captured.second)
        return synchronized(requestLock) {
            if(!originalDocumentOwned(sourceVersion) || commandCid!=cid || hotDocument!==captured.first ||
                captured.first.generation!=generation.get() || captured.first.revision!=documentRevision.get() ||
                settings!=captured.second)emptyList() else items
        }
    }

    /** Identity registration prevents a retiring popup from clearing its successor's track reservation. */
    internal fun reserveHotBar(sourceVersion:Long,token:Any,heightPx:Float):Boolean = synchronized(requestLock) {
        if(!originalDocumentOwned(sourceVersion))false else {
            hotReservation=HotReservation(sourceVersion,token,heightPx)
            SwingUtilities.invokeLater {if(!closed.get())panel.repaint()};true
        }
    }
    internal fun updateHotBarReservation(sourceVersion:Long,token:Any,heightPx:Float):Boolean = synchronized(requestLock) {
        if(!originalDocumentOwned(sourceVersion) || hotReservation?.token!==token || hotReservation?.sourceVersion!=sourceVersion)false
        else {hotReservation=HotReservation(sourceVersion,token,heightPx);SwingUtilities.invokeLater {if(!closed.get())panel.repaint()};true}
    }
    internal fun releaseHotBarReservation(token:Any) = synchronized(requestLock) {
        if(hotReservation?.token===token) {hotReservation=null;SwingUtilities.invokeLater {if(!closed.get())panel.repaint()}}
    }
    /** Derived view of the sole raw document, with no second item/cache authority. */
    fun poolSourceFor(cid: Long, sourceVersion: Long): DanmakuPoolSourceSnapshot? = synchronized(requestLock) {
        if (closed.get() || liveMode || cid <= 0L || commandCid != cid || poolSourceVersion != sourceVersion ||
            !player.ownsSourceVersion(sourceVersion)) null
        else DanmakuPoolSourceSnapshot(cid, sourceVersion, documentRevision.get(), rawDocument.comments)
    }
    /** Original server-disable metadata belongs to the sole raw document.
     * Require the same actual native source; no per-CID set/cache is created. */
    fun serverDisabledFor(cid: Long, sourceVersion: Long): Boolean = synchronized(requestLock) {
        check(!closed.get() && !liveMode && cid > 0L && player.ownsSourceVersion(sourceVersion)) {
            "Danmaku server-state source is not current"
        }
        // Original mark-set absence is false until a reply marks this CID.
        // Never use metadata from another CID/version or create a second set.
        commandCid == cid && poolSourceVersion == sourceVersion && rawDocument.serverDisabled
    }
    fun commandItemsFor(cid: Long): List<CommandDanmakuItem> = synchronized(requestLock) {
        if (!closed.get() && cid > 0 && commandCid == cid) mutableCommands.value else emptyList()
    }
    private val mutableFormat = MutableStateFlow<DanmakuFormat?>(null)
    val format: StateFlow<DanmakuFormat?> = mutableFormat.asStateFlow()
    @Volatile private var settings = DanmakuSettings()
    @Volatile private var eyeTint = DesktopEyeTint()
    @Volatile private var viewportBrightness:Pair<Long,Float>? = null
    private fun ownedViewportBrightness():Pair<Long,Float>? = viewportBrightness?.takeIf {
        !closed.get() && player.ownsSourceVersion(it.first)
    }
    @Volatile private var pluginProcessor: DanmakuPluginProcessor? = null
    @Volatile private var rawDocument = DanmakuDocument()
    // One captured native/entry-read-only owner for the existing OFFLINE window actor, not another task/cache.
    @Volatile private var offlineDocumentOwner:Pair<Long,()->Boolean>? = null
    private fun currentOfflineDocumentOwned():Boolean = offlineDocumentOwner?.let {
        !closed.get() && player.ownsSourceVersion(it.first) && it.second()
    } ?: !closed.get()
    private data class OriginalClickBinding(
        val token:Long,val owns:()->Boolean,val onClick:(String,Long,String,Boolean)->Unit,
        val foregroundWindow:()->Window?,val currentMid:()->Long,
        val admit:(Long,()->Unit)->Boolean,
    )
    private data class OriginalPaintHit(val comment:DanmakuComment,val area:java.awt.geom.Area)
    private data class OriginalPaintFrame(
        val cid:Long,val sourceVersion:Long,val generation:Long,val revision:Long,
        val origin:java.awt.Point,val width:Int,val height:Int,
        val scaleX:Double,val scaleY:Double,val mediaTime:Double,val configuration:DanmakuSettings,val hits:List<OriginalPaintHit>,
    )
    internal class OriginalDanmakuPointerHit internal constructor(
        internal val identity:Any,internal val listener:Long,internal val sourceVersion:Long,
        internal val cid:Long,internal val generation:Long,internal val revision:Long,
        internal val internalId:Int,internal val foreground:Window,internal val foregroundHwnd:Long,
        internal val origin:java.awt.Point,internal val width:Int,internal val height:Int,
        internal val scaleX:Double,internal val scaleY:Double,internal val mediaTime:Double,
        internal val configuration:DanmakuSettings,
    )
    private val originalClickSequence=AtomicLong()
    private var originalLocalInjectionPhase:Any=Any() // Same-document preprocessing phase; not an item list.
    private val originalClickBindings=linkedMapOf<Long,OriginalClickBinding>()
    private var originalPaintFrame:OriginalPaintFrame?=null // EDT; derived ONLY from successful paint.
    private var originalPointerHit:OriginalDanmakuPointerHit?=null // EDT; one accepted DOWN/UP, no item queue.
    private var originalInstalledGeneration=Long.MIN_VALUE // EDT; scheduler's actual installed identity.
    private var originalInstalledRevision=Long.MIN_VALUE

    /** Predicates/getters are actual Root's read-only entry/account/foreground views.
     * The sole original Section forwards real pointer input; this passive HWND never takes input. */
    internal fun acquireOriginalDanmakuClickListener(
        stillOwned:()->Boolean,onClick:(String,Long,String,Boolean)->Unit,
        foregroundWindow:()->Window?,currentMid:()->Long,
        admit:(expectedSourceVersion:Long,action:()->Unit)->Boolean,
    ):AutoCloseable {
        val token=originalClickSequence.incrementAndGet()
        synchronized(requestLock) {
            if(!closed.get() && stillOwned())
                originalClickBindings[token]=OriginalClickBinding(token,stillOwned,onClick,foregroundWindow,currentMid,admit)
        }
        val released=AtomicBoolean()
        return AutoCloseable {
            if(released.compareAndSet(false,true)) synchronized(requestLock) {originalClickBindings.remove(token)}
        }
    }
    private fun originalDocumentOwned(version:Long):Boolean = !closed.get() && !liveMode && version>0L &&
        poolSourceVersion==version && (commandCid ?: 0L)>0L && player.ownsSourceVersion(version) && currentOfflineDocumentOwned()

    fun clearOriginalPortraitDanmaku(expectedSourceVersion:Long):Boolean = synchronized(requestLock) {
        if(!originalDocumentOwned(expectedSourceVersion)) false
        else seekOriginalSection(expectedSourceVersion,null,true)
    }
    fun recoverOriginalPortraitDanmaku(expectedSourceVersion:Long,positionMs:Long,
        playWhenReady:Boolean,playbackState:Int):Boolean = synchronized(requestLock) {
        if(!originalDocumentOwned(expectedSourceVersion))return@synchronized false
        val state=player.state.value
        when(com.android.purebilibili.feature.video.danmaku.resolveDanmakuActionForForegroundRecovery(
            playWhenReady,!state.paused && !state.loading && !state.ended,playbackState,settings.enabled,
            mutableFormat.value != null || rawDocument.size > 0)) {
            com.android.purebilibili.feature.video.danmaku.DanmakuSyncAction.HardResync ->
                seekOriginalSection(expectedSourceVersion,positionMs,false)
            com.android.purebilibili.feature.video.danmaku.DanmakuSyncAction.PauseOnly ->
                seekOriginalSection(expectedSourceVersion,null,true)
            else -> true
        }
    }
    fun addOriginalPortraitDanmaku(expectedSourceVersion:Long,text:String,color:Int,mode:Int,fontSize:Int):Boolean =
        synchronized(requestLock) {
            if(!originalDocumentOwned(expectedSourceVersion))return@synchronized false
            val position=(player.state.value.positionSeconds*1000).toLong().coerceAtLeast(0L)
            val item=com.android.purebilibili.feature.video.danmaku.desktopOriginalLocalDanmakuItem(
                text,color,mode,fontSize,position,settings.staticDanmakuToScroll)
            val previous=rawDocument.comments.filter {it.originalLocalItem!=null}.maxOfOrNull {it.id} ?: Int.MIN_VALUE
            check(previous< -1) {"Local danmaku measurement identity exhausted"}
            val comment=DanmakuComment(previous+1,item.showAtTime/1000.0,mode,fontSize,
                (item.textColor ?: color) and 0xffffff,item.text.orEmpty(),originalLocalItem=item,
                originalLocalInjectionPhase=originalLocalInjectionPhase)
            rawDocument=rawDocument.copy(comments=(rawDocument.comments+comment)
                .sortedBy {it.timeSeconds})
            documentRevision.incrementAndGet().also {mutablePoolSourceRevision.value=it}
            publishOriginalLocalAppend(generation.get(),documentInstallRevision)
            true
        }

    /** Mutate only the installed timeline; a pending full pipeline picks up all
     * late local additions and applies their current/retired phase at publication. */
    private fun publishOriginalLocalAppend(version:Long,installRevision:Long) {
        SwingUtilities.invokeLater {installOriginalLocalAppend(version,installRevision)}
    }

    private fun installOriginalLocalAppend(version:Long,installRevision:Long) {
        check(SwingUtilities.isEventDispatchThread())
        var count:Int?=null
        var appliedRevision=0L
        synchronized(requestLock) {
            if(closed.get() || cacheMaintenance || generation.get()!=version ||
                documentInstallRevision!=installRevision || !currentOfflineDocumentOwned())return
            if(originalInstalledGeneration!=version || originalInstalledRevision<installRevision)return
            val installed=hotDocument?.takeIf {it.generation==version && it.revision==originalInstalledRevision}
                ?: return
            // Reconcile against the actual current settings phase on EDT. A
            // settings publication may precede its queued scheduler update.
            val known=installed.processedLocalIds
            val local=rawDocument.comments.filter {it.originalLocalItem!=null && it.id !in known}
            val added=scheduler.appendOriginalLocalComments(local,originalLocalInjectionPhase,settings,
                allowPhaseRetirement=true) ?: return
            val processed=(installed.comments+added).sortedBy {it.timeSeconds}
            val revision=documentRevision.get()
            hotDocument=HotDocument(version,revision,processed,known+added.map {it.id})
            originalInstalledRevision=revision
            appliedRevision=revision
            count=processed.size+rawDocument.advanced.size
            // Existing styles, advanced renderer, text measurements, active
            // occupants and clock remain untouched by a local append.
        }
        count?.let {
            if(synchronized(requestLock) {!closed.get() && generation.get()==version &&
                documentInstallRevision==installRevision && documentRevision.get()==appliedRevision &&
                currentOfflineDocumentOwned()}) {
                vodBaseGeneration=version; vodBaseCount=it; updateVodCount()
            }
            panel.repaint()
        }
    }

    private fun originalClickBinding():OriginalClickBinding? {
        originalClickBindings.entries.removeIf {!it.value.owns()}
        return originalClickBindings.values.lastOrNull()
    }
    private fun originalFrameCurrent(frame:OriginalPaintFrame):Boolean =
        originalDocumentOwned(frame.sourceVersion) && commandCid==frame.cid &&
            generation.get()==frame.generation && documentRevision.get()==frame.revision &&
            originalInstalledGeneration==frame.generation && originalInstalledRevision==frame.revision &&
            settings==frame.configuration && settings.enabled && originalSeekScrub?.let {it.first==frame.sourceVersion && it.second}!=true &&
            overlay?.isShowing==true && panel.isShowing && panel.width==frame.width && panel.height==frame.height &&
            panel.locationOnScreen==frame.origin &&
            panel.graphicsConfiguration?.defaultTransform?.let {
                kotlin.math.hypot(it.scaleX,it.shearY)==frame.scaleX &&
                    kotlin.math.hypot(it.scaleY,it.shearX)==frame.scaleY
            }==true && player.state.value.let {
                it.positionSeconds==frame.mediaTime && it.paused && it.ready && it.firstVideoFrameReady && !it.loading && !it.ended && !it.audioOnly && it.error==null
            }
    private fun originalWindowPoint(window:Window,x:Double,y:Double,frame:OriginalPaintFrame):java.awt.geom.Point2D.Double? {
        if(!window.isShowing || !window.isDisplayable || !x.isFinite() || !y.isFinite())return null
        val transform=window.graphicsConfiguration?.defaultTransform ?: return null
        val sx=kotlin.math.hypot(transform.scaleX,transform.shearY)
        val sy=kotlin.math.hypot(transform.scaleY,transform.shearX)
        if(!sx.isFinite() || !sy.isFinite() || sx<=0.0 || sy<=0.0)return null
        val screen=window.locationOnScreen
        return java.awt.geom.Point2D.Double(screen.x+x/sx-frame.origin.x,screen.y+y/sy-frame.origin.y)
    }
    /** Actual Compose positionInWindow physical pixels, converted with that same foreground Window's DPI/origin. */
    internal fun beginOriginalDanmakuPointer(expectedSourceVersion:Long,windowPixelX:Double,windowPixelY:Double):OriginalDanmakuPointerHit? {
        check(SwingUtilities.isEventDispatchThread())
        return synchronized(requestLock) {
            originalPointerHit=null
            val frame=originalPaintFrame ?: return@synchronized null
            if(frame.sourceVersion!=expectedSourceVersion || !originalFrameCurrent(frame))return@synchronized null
            val binding=originalClickBinding() ?: return@synchronized null
            val foreground=binding.foregroundWindow() ?: return@synchronized null
            val point=originalWindowPoint(foreground,windowPixelX,windowPixelY,frame) ?: return@synchronized null
            val hit=frame.hits.lastOrNull {it.area.contains(point)} ?: return@synchronized null
            OriginalDanmakuPointerHit(Any(),binding.token,frame.sourceVersion,frame.cid,frame.generation,frame.revision,
                hit.comment.id,foreground,Pointer.nativeValue(Native.getWindowPointer(foreground)),
                frame.origin,frame.width,frame.height,frame.scaleX,frame.scaleY,frame.mediaTime,frame.configuration).also {originalPointerHit=it}
        }
    }
    internal fun cancelOriginalDanmakuPointer(hit:OriginalDanmakuPointerHit) {
        check(SwingUtilities.isEventDispatchThread())
        if(originalPointerHit===hit)originalPointerHit=null
    }
    internal fun finishOriginalDanmakuPointer(hit:OriginalDanmakuPointerHit,windowPixelX:Double,windowPixelY:Double):Boolean {
        check(SwingUtilities.isEventDispatchThread())
        val selected=synchronized(requestLock) {
            if(originalPointerHit!==hit)return@synchronized null
            originalPointerHit=null
            val frame=originalPaintFrame ?: return@synchronized null
            if(!originalFrameCurrent(frame) || frame.cid!=hit.cid || frame.sourceVersion!=hit.sourceVersion ||
                frame.generation!=hit.generation || frame.revision!=hit.revision || frame.origin!=hit.origin ||
                frame.width!=hit.width || frame.height!=hit.height || frame.scaleX!=hit.scaleX || frame.scaleY!=hit.scaleY ||
                frame.mediaTime!=hit.mediaTime || frame.configuration!=hit.configuration)return@synchronized null
            val binding=originalClickBinding()?.takeIf {it.token==hit.listener} ?: return@synchronized null
            if(binding.foregroundWindow()!==hit.foreground || !hit.foreground.isDisplayable ||
                Pointer.nativeValue(Native.getWindowPointer(hit.foreground))!=hit.foregroundHwnd)return@synchronized null
            val point=originalWindowPoint(hit.foreground,windowPixelX,windowPixelY,frame) ?: return@synchronized null
            val item=frame.hits.lastOrNull {it.comment.id==hit.internalId && it.area.contains(point)}?.comment ?: return@synchronized null
            binding to item
        } ?: return false
        // Original callbacks/UI/API work happen AFTER Overlay's request lock has been released.
        val (binding,comment)=selected
        if(!binding.owns() || synchronized(requestLock) {originalClickBindings.values.lastOrNull()?.token}!=binding.token ||
            !player.ownsSourceVersion(hit.sourceVersion))return false
        val original=comment.originalLocalItem ?: comment.originalElement?.let {
            com.android.purebilibili.feature.video.danmaku.DesktopOriginalDanmakuItemParser.createTextDataFromProto(it)
        } ?: if(comment.originalXmlAttributes!=null && comment.originalXmlContent!=null)
            com.android.purebilibili.feature.video.danmaku.DesktopOriginalDanmakuItemParser.createTextData(comment.originalXmlAttributes,comment.originalXmlContent)
        else null
        var dispatched=false
        val admitted=binding.admit(hit.sourceVersion) {
            // Root first admits this exact native publication under Store -> entry,
            // releasing NativeOwner's validation lock before this short Overlay read.
            val current=synchronized(requestLock) {
                !closed.get() && !liveMode && poolSourceVersion==hit.sourceVersion && commandCid==hit.cid &&
                    generation.get()==hit.generation && documentRevision.get()==hit.revision &&
                    originalClickBindings.values.lastOrNull()?.token==binding.token
            }
            if(current && binding.owns()) {
                val user=com.android.purebilibili.feature.video.danmaku.resolveDanmakuClickUserHash(original?.userHash ?: comment.userHash)
                val self=(original?.isSelf==true) || com.android.purebilibili.feature.video.danmaku.resolveDanmakuClickIsSelf(user,binding.currentMid())
                binding.onClick(comment.text,original?.danmakuId ?: comment.serverId,user,self)
                dispatched=true
            }
        }
        return admitted && dispatched
    }


    private var styles = emptyMap<Int, DanmakuStyle>()
    var enabled: Boolean
        get() = settings.enabled
        set(value) { applySettings(settings.copy(enabled = value)) }
    val currentSettings: DanmakuSettings get() = settings
    internal fun maximumDisplayShortSidePx():Float=renderPlatform.maximumDisplayShortSidePx()
    private var overlay: JWindow? = null
    private var owner: Window? = null
    private var ownerWasActive = false
    private var scheduler = DanmakuScheduler(emptyList(), settings,liveAdmission=false)
    private var advancedRenderer = AdvancedDanmakuRenderer(emptyList())
    private val basRenderer = DesktopBasRenderer()
    private var basFilterJob: Job? = null
    private val basFilterTicket = AtomicLong()
    private var basPluginProcessor: DesktopBasPluginProcessor? = null
    private data class BasInstallation(
        val generation: Long, val installRevision: Long, val raw: List<BasDanmaku>,
        val settings: DanmakuSettings, val processor: DesktopBasPluginProcessor?,
        val source: OwnedPlaybackSourceSnapshot?, val items: List<BasDanmaku>, val ticket: Long,
        val rejections: Map<DesktopBasRejection, Int> = emptyMap(),
    )
    private val mutableBasRejections = MutableStateFlow<Map<DesktopBasRejection, Int>>(emptyMap())
    internal val basRejections: StateFlow<Map<DesktopBasRejection, Int>> = mutableBasRejections.asStateFlow()
    private var basInstallation: BasInstallation? = null
    private var vodBaseCount = 0
    private var vodBaseGeneration = Long.MIN_VALUE
    private class BasActionBinding(val source: OwnedPlaybackSourceSnapshot,
        val owned: () -> Boolean, val admit: ((() -> Unit) -> Boolean), val activate: (BasTarget) -> Boolean)
    @Volatile private var basActionBinding: BasActionBinding? = null
    private var basPaintFrame: DesktopBasInputFrame? = null // EDT, only a completed actual panel paint.
    private val basInput = DesktopBasOverlayInput(basRenderer) { basPaintFrame?.takeIf { it.isCurrent() } }

    /** Root supplies a full accepted source and a lock-order-safe presentation predicate. */
    internal fun acquireBasActions(source: OwnedPlaybackSourceSnapshot,
        cid: Long?, owned: () -> Boolean, admit: ((() -> Unit) -> Boolean), activate: (BasTarget) -> Boolean): AutoCloseable {
        check(SwingUtilities.isEventDispatchThread())
        val binding = BasActionBinding(source, owned, admit, activate)
        basActionBinding = binding
        basPaintFrame = null; basInput.cancel()
        // An admitted same-part recovery can replace URLs without changing the
        // numeric native version. Reuse the sole raw pool under the new Root lease.
        val needsRefresh = synchronized(requestLock) {
            !liveMode && !cacheMaintenance && !closed.get() && rawDocument.bas.isNotEmpty() &&
                (cid == null || commandCid == cid) && (poolSourceVersion == null || poolSourceVersion == source.sourceVersion) &&
                owned() && currentOfflineDocumentOwned() && player.ownsSourceSnapshot(source) &&
                basInstallation?.let { basCurrent(it) && sameBasSource(it.source, source) } != true
        }
        if (needsRefresh) refreshBasDocument()
        return AutoCloseable {
            check(SwingUtilities.isEventDispatchThread())
            if (basActionBinding === binding) {
                basActionBinding = null; basPaintFrame = null; basInput.cancel()
            }
        }
    }

    private fun sameBasSource(left: OwnedPlaybackSourceSnapshot?, right: OwnedPlaybackSourceSnapshot): Boolean =
        left?.sourceVersion == right.sourceVersion && left?.source == right.source

    private fun basCurrent(value: BasInstallation): Boolean = !closed.get() && !cacheMaintenance && !liveMode &&
        generation.get() == value.generation && documentInstallRevision == value.installRevision &&
        basFilterTicket.get() == value.ticket && rawDocument.bas === value.raw && settings.hasSameBasFilterPolicy(value.settings) &&
        basPluginProcessor === value.processor && currentOfflineDocumentOwned() &&
        value.source?.let { player.ownsSourceSnapshot(it) && (poolSourceVersion == null || poolSourceVersion == it.sourceVersion) } == true

    private fun updateVodCount() {
        if (!liveMode) mutableCount.value = (if(vodBaseGeneration==generation.get())vodBaseCount else 0) +
            (basInstallation?.takeIf(::basCurrent)?.items?.size ?: 0)
    }

    private fun clearBasPresentation() {
        basInstallation=null;basPaintFrame=null;basInput.cancel();basRenderer.clear();mutableBasRejections.value=emptyMap()
    }

    /** Refilter raw BAS separately: changing its settings must not replay ordinary plugins or lanes. */
    private fun refreshBasDocument() {
        val captured = synchronized(requestLock) {
            basFilterJob?.cancel()
            val ticket = basFilterTicket.incrementAndGet()
            if (closed.get() || cacheMaintenance || liveMode) return
            BasInstallation(generation.get(), documentInstallRevision, rawDocument.bas, settings, basPluginProcessor,
                player.currentSourceSnapshot(), emptyList(), ticket)
        }
        val job = requests.launch(Dispatchers.Default) {
            ensureActive()
            val context = coroutineContext
            val processor = captured.processor?.let { delegate -> DesktopBasPluginProcessor(
                filter = { context.ensureActive(); delegate.filter(it) },
                style = { context.ensureActive(); delegate.style(it) }) }
            val rejections = linkedMapOf<DesktopBasRejection, Int>()
            val processed = filterDesktopBasDanmaku(captured.raw, captured.settings, processor,
                DesktopBasDocumentBudget()) { _, reason ->
                context.ensureActive(); rejections[reason] = (rejections[reason] ?: 0) + 1
            }
            ensureActive()
            SwingUtilities.invokeLater {
                val binding = basActionBinding?.takeIf { sameBasSource(captured.source, it.source) }
                var installed = false
                val publish = {
                    synchronized(requestLock) {
                        if (basCurrent(captured) && (binding == null || (basActionBinding === binding && binding.owned()))) {
                            basInstallation = captured.copy(items = processed, rejections = rejections.toMap())
                            mutableBasRejections.value = rejections.toMap()
                            installed = true
                        }
                    }
                }
                if (binding != null) {
                    // Root: Store -> entry -> Overlay. Its short native check is
                    // released before this monitor; never native.admit -> Overlay.
                    if (!binding.admit(publish)) installed = false
                } else if (captured.source != null && basStandaloneAdmission != null) {
                    if (!basStandaloneAdmission.invoke(captured.source, publish)) installed = false
                }
                if (installed) {
                    basPaintFrame = null; basInput.cancel(); basRenderer.clear()
                    updateVodCount(); panel.repaint()
                }
            }
        }
        synchronized(requestLock) {
            if (basFilterTicket.get() == captured.ticket && !closed.get()) basFilterJob = job else job.cancel()
        }
    }

    private fun paintBas(context: Graphics2D, geometry: DesktopDanmakuPaintGeometry) {
        val installation = basInstallation?.takeIf(::basCurrent) ?: return
        if (installation.items.isEmpty()) return
        val config = settings
        basRenderer.configure(installation.items, geometry.viewport.widthPx, geometry.viewport.heightPx,
            config.opacity, config.fontScale, config.fontWeight)
        basRenderer.frame((displayTime * 1_000).toLong().coerceAtLeast(0L))
        val physical = context.create() as Graphics2D
        val painted = try {
            geometry.configurePhysicalPixels(physical)
            physical.composite = AlphaComposite.SrcOver // Original BAS painter applies opacity once.
            basRenderer.paint(physical)
        } finally { physical.dispose() }
        val binding = basActionBinding ?: return
        if (!painted || !panel.isShowing || overlay?.isShowing != true || !binding.owned() ||
            installation.source?.sourceVersion != binding.source.sourceVersion ||
            installation.source?.source != binding.source.source || !player.ownsSourceSnapshot(binding.source)) return
        val origin = panel.locationOnScreen
        val inputGeometry = DesktopBasInputGeometry(origin.x, origin.y, panel.width, panel.height, geometry.scaleX, geometry.scaleY)
        val revision = documentRevision.get()
        basPaintFrame = DesktopBasInputFrame(binding, installation, inputGeometry, isCurrent = {
            val native = player.state.value
            val transform = panel.graphicsConfiguration?.defaultTransform
            basActionBinding === binding && basInstallation === installation && basCurrent(installation) &&
                documentRevision.get() == revision && binding.owned() && settings == config && settings.enabled && settings.allowSpecial &&
                player.ownsSourceSnapshot(binding.source) && native.ready && native.firstVideoFrameReady &&
                !native.loading && !native.ended && !native.audioOnly && native.error == null && native.failure == null &&
                originalSeekScrub?.let { it.first == binding.source.sourceVersion && it.second } != true &&
                panel.isShowing && overlay?.isShowing == true && panel.locationOnScreen == origin &&
                panel.width == inputGeometry.width && panel.height == inputGeometry.height &&
                transform?.scaleX == geometry.scaleX && transform?.scaleY == geometry.scaleY
        }, activate = binding.activate)
    }
    private var lastPosition = Double.NaN
    private var sampleTimeNanos = 0L
    private var anchoredPosition = 0.0
    private var displayTime = 0.0
    @Volatile private var liveMode = false
    private val liveRenderer = LiveDanmakuRenderer(requests)
    private data class PendingLive(val generation: Long, val item: LiveDanmakuItem)
    private val pendingLive = ArrayBlockingQueue<PendingLive>(600)
    private val measuredWidths = mutableMapOf<Triple<Int,Font,Boolean>, DesktopDanmakuAuthorTextMetrics>()
    private data class ConfigKey(val settings:DanmakuSettings,val viewport:DanmakuViewport,val font:Font,val live:Boolean,val maskReady:Boolean,val hotReservedHeightPx:Float=0f,val fullscreen:Boolean=false)
    private var resolvedConfig:Pair<ConfigKey,DanmakuRenderConfig>?=null
    private val panel:JComponent = object : JComponent() {
        override fun paintComponent(graphics: Graphics) {
            originalPaintFrame=null
            basPaintFrame=null
            val context = graphics.create() as Graphics2D
            try {
                context.composite = AlphaComposite.Src
                context.color = Color(0, 0, 0, 0)
                context.fillRect(0, 0, width, height)
                context.composite = AlphaComposite.SrcOver
                context.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                context.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val geometry=DesktopDanmakuPaintGeometry.from(width,height,context.transform,renderPlatform.maximumDisplayShortSidePx()) ?: return
                // The original Section supplies density/scale only for this same physical viewport.
                // A SCREEN_TOP region outside the actual Canvas remains a separate platform gap.
                val viewport=geometry.sectionViewport(if(liveMode)null else originalSectionDanmakuViewport?.takeIf {
                    it.first==poolSourceVersion && player.ownsSourceVersion(it.first)
                }?.second)
                val fullscreen=sourcePresentation.isFullscreen()
                val configuration = settings
                if (configuration.enabled && currentOfflineDocumentOwned() &&
                    originalSectionViewport?.let { !player.ownsSourceVersion(it.first) || it.second } != false &&
                    originalSeekScrub?.let { player.ownsSourceVersion(it.first) && it.second } != true) {
                  context.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, configuration.opacity)
                  if (liveMode) {
                    // Uses the actual Root-monitor geometry resolved once above.
                    val key=ConfigKey(configuration,geometry.viewport,renderPlatform.resolveTypeface(configuration.fontWeight),true,false,fullscreen=fullscreen)
                    val bandHeight=(geometry.viewport.heightPx*configuration.displayAreaRatio).toInt().coerceAtLeast(1)
                    val config=resolvedConfig?.takeIf { it.first==key }?.second ?: com.android.purebilibili.feature.video.danmaku.resolveDesktopOriginalLiveDanmakuRenderConfig(configuration,geometry.viewport.widthPx,bandHeight,configuration.displayAreaRatio,geometry.viewport.density,renderPlatform,isFullscreen=fullscreen).also { resolvedConfig=key to it }
                    val physical=context.create() as Graphics2D
                    try {geometry.configurePhysicalPixels(physical);physical.composite=AlphaComposite.getInstance(AlphaComposite.SRC_OVER,config.alpha/255f);liveRenderer.paint(physical,geometry.viewport.widthPx,geometry.viewport.heightPx,bandHeight,config,configuration)}
                    finally {physical.dispose()}
                  } else {
                // Uses the actual Root-monitor geometry resolved once above.
                val reserved=hotReservation?.takeIf {it.sourceVersion==poolSourceVersion && player.ownsSourceVersion(it.sourceVersion)}?.heightPx ?: 0f
                val key=ConfigKey(configuration,viewport,renderPlatform.resolveTypeface(configuration.fontWeight),false,webMaskAvailable(),reserved,fullscreen)
                val config=resolvedConfig?.takeIf { it.first==key }?.second ?: configuration.originalConfig(renderPlatform,reserved).resolveRenderConfig(viewport,fullscreen).copy(maskEnabled=key.maskReady).also { resolvedConfig=key to it }
                val physical=context.create() as Graphics2D
                try {
                    geometry.configurePhysicalPixels(physical)
                    applyDesktopWebMaskClip(physical,geometry.viewport.widthPx,geometry.viewport.heightPx,currentWebMaskFrame((displayTime*1000).toLong()),player.videoOutput.value.takeIf {it.sourceVersion==poolSourceVersion && poolSourceVersion?.let(player::ownsSourceVersion)==true}?.viewport)
                    physical.composite=AlphaComposite.getInstance(AlphaComposite.SRC_OVER,config.alpha/255f)
                    if (measuredWidths.size > 5_000) measuredWidths.clear()
                    fun font(comment:DanmakuComment):Font {
                        val style=styles[comment.id]
                        return desktopDanmakuFont(config,comment,style?.scale ?: 1f,style?.bold==true)
                    }
                    val author=authorPresentation.capture()
                    val badge=DesktopOriginalUpDanmakuBadge(physical)
                    fun textMetrics(comment:DanmakuComment):DesktopDanmakuAuthorTextMetrics {
                        val font=font(comment)
                        val tagged=author.matches(comment.userHash)
                        return measuredWidths.getOrPut(Triple(comment.id,font,tagged)) {badge.measure(comment.text,font,tagged)}
                    }
                    scheduler.observeOriginalLocalInjectionPhase(synchronized(requestLock){originalLocalInjectionPhase})
                    val positioned = scheduler.frame(displayTime, geometry.viewport.widthPx, geometry.viewport.heightPx, config, author.measurementRevision) { comment ->
                        val metrics=textMetrics(comment)
                        DesktopDanmakuTextMetrics(metrics.width,metrics.ascent)
                    }
                    val originalHits=mutableListOf<OriginalPaintHit>()
                    positioned.forEach { item ->
                        val style = styles[item.comment.id]
                        val font = font(item.comment)
                        val authorMetrics=textMetrics(item.comment)
                        pluginAwtColor(style?.backgroundColor)?.let { color ->
                            val metrics = physical.getFontMetrics(font)
                            physical.color = color
                            physical.fillRoundRect(item.x.toInt() - 4, item.baseline.toInt() - metrics.ascent - 2,
                                item.textWidth + 8, metrics.height + 4, 6, 6)
                        }
                        badge.paint(item.x,item.baseline,authorMetrics)
                        val shape = font.createGlyphVector(physical.fontRenderContext, item.comment.text)
                            .getOutline((item.x+authorMetrics.badgeAdvance).toFloat(), item.baseline.toFloat())
                        if (config.strokeWidthPx > 0f) {
                            physical.color = pluginAwtColor(style?.borderColor) ?: Color(config.strokeColor,true)
                            physical.stroke = BasicStroke(config.strokeWidthPx, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                            physical.draw(shape)
                        }
                        if (item.comment.isVipGradualColor && style?.textColor == null) {
                            val x = item.x.toFloat()
                            physical.paint = LinearGradientPaint(x, 0f, x + authorMetrics.pureWidth.coerceAtLeast(1), 0f,
                                floatArrayOf(0f, 0.5f, 1f), arrayOf(Color(0xff7cba), Color(0xa798ff), Color(0x70d6ff)))
                        } else physical.color = pluginAwtColor(style?.textColor) ?: Color(item.comment.color)
                        physical.fill(shape)
                        if(config.alpha>0) {
                            val metrics=physical.getFontMetrics(font)
                            val area=java.awt.geom.Area(java.awt.geom.Rectangle2D.Double(item.x,
                                item.baseline-metrics.ascent,item.textWidth.toDouble(),(metrics.ascent+metrics.descent).toDouble()))
                            physical.clip?.let {area.intersect(java.awt.geom.Area(it))}
                            area.transform(java.awt.geom.AffineTransform.getScaleInstance(1.0/geometry.scaleX,1.0/geometry.scaleY))
                            if(!area.isEmpty)originalHits+=OriginalPaintHit(item.comment,area)
                        }
                    }
                    synchronized(requestLock) {
                        val cid=commandCid
                        val sourceVersion=poolSourceVersion
                        if(cid!=null && sourceVersion!=null && originalDocumentOwned(sourceVersion) &&
                            originalInstalledGeneration==generation.get() && originalInstalledRevision==documentRevision.get() &&
                            player.state.value.paused && panel.isShowing && overlay?.isShowing==true) {
                            originalPaintFrame=OriginalPaintFrame(cid,sourceVersion,generation.get(),documentRevision.get(),
                                panel.locationOnScreen,panel.width,panel.height,geometry.scaleX,geometry.scaleY,displayTime,configuration,originalHits.toList())
                        }
                    }
                } finally {physical.dispose()}
                advancedRenderer.paint(context, (displayTime * 1000).toLong(), width, height, configuration)
                paintBas(context, geometry)
                  }
                }
                ownedViewportBrightness()?.let { (_,brightness) -> DesktopEyeTint(1f-brightness,0f).paint(context,width,height) }
                eyeTint.paint(context, width, height)
            } finally { context.dispose() }
        }
    }.apply { isOpaque = false }
    private val timer = Timer(16) { tick() }

    init { SwingUtilities.invokeLater { if (!closed.get()) timer.start() } }

    fun applySettings(settings: DanmakuSettings) {
        val normalized = settings.normalized()
        val smartChanged=this.settings.smartOcclusionEnabled!=normalized.smartOcclusionEnabled
        val basFilterChanged=!this.settings.hasSameBasFilterPolicy(normalized)
        synchronized(requestLock) {
            if(!this.settings.hasSameTimelinePolicy(normalized))originalLocalInjectionPhase=Any()
            this.settings=normalized
        }
        if(smartChanged)onWebMaskSettingChanged()
        if(basFilterChanged)refreshBasDocument()
        SwingUtilities.invokeLater {
            if (!closed.get()) {
                scheduler.applySettings(this.settings)
                measuredWidths.clear()
                panel.repaint()
            }
        }
    }

    fun setEyeProtection(dimAlpha: Float, warmAlpha: Float, warmArgb: Int = 0xffffc07a.toInt()) {
        eyeTint = DesktopEyeTint(dimAlpha.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f,
            warmAlpha.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f, warmArgb)
        SwingUtilities.invokeLater { if (!closed.get()) panel.repaint() }
    }

    /** Video-viewport brightness, not Windows system/display brightness. Root writes on its owned UI actor. */
    fun setViewportBrightness(expectedSourceVersion:Long,brightness:Float):Boolean = synchronized(requestLock) {
        if(closed.get()||!brightness.isFinite()||!player.ownsSourceVersion(expectedSourceVersion))return@synchronized false
        viewportBrightness=expectedSourceVersion to brightness.coerceIn(0f,1f)
        SwingUtilities.invokeLater {if(!closed.get())panel.repaint()}
        true
    }
    fun viewportBrightnessFor(expectedSourceVersion:Long):Float? = synchronized(requestLock) {
        if(closed.get()||!player.ownsSourceVersion(expectedSourceVersion))null
        else viewportBrightness?.takeIf {it.first==expectedSourceVersion}?.second ?: 1f
    }
    fun clearViewportBrightness(expectedSourceVersion:Long):Boolean = synchronized(requestLock) {
        if(closed.get()||!player.ownsSourceVersion(expectedSourceVersion)||viewportBrightness?.first!=expectedSourceVersion)return@synchronized false
        viewportBrightness=null
        SwingUtilities.invokeLater {if(!closed.get())panel.repaint()}
        true
    }

    fun setPluginDanmakuProcessor(processor: DanmakuPluginProcessor?) = setPluginDanmakuProcessors(processor, null)

    internal fun setPluginDanmakuProcessors(processor: DanmakuPluginProcessor?, basProcessor: DesktopBasPluginProcessor?) {
        val vod = synchronized(requestLock) {
            pluginProcessor = processor
            basPluginProcessor = basProcessor
            if (!liveMode && !cacheMaintenance) rawDocument to generation.get() else null
        }
        vod?.let { (document, version) -> installDocument(document, version) }
        SwingUtilities.invokeLater { if (!closed.get()) { liveRenderer.setProcessor(processor); panel.repaint() } }
    }

    suspend fun load(cid:Long,aid:Long=0L,durationSeconds:Double=0.0,expectedSourceVersion:Long?=null,maskSource:DesktopOwnedWebMaskSource?) =
        load(cid,aid,durationSeconds,expectedSourceVersion,maskSource,null)

    suspend fun load(cid:Long,aid:Long,durationSeconds:Double,expectedSourceVersion:Long?,maskSource:DesktopOwnedWebMaskSource?,stillOwned:(()->Boolean)?) {
        require(stillOwned==null || expectedSourceVersion!=null)
        require(cid > 0) { "Invalid danmaku content ID." }
        require(aid >= 0 && durationSeconds.isFinite() && durationSeconds >= 0)
        require(maskSource==null || (maskSource.cid==cid && maskSource.sourceVersion==expectedSourceVersion))
        loadSource(source, cid, aid, durationSeconds, expectedSourceVersion, maskSource, stillOwned)
    }

    /** Decodes the downloaded upstream protobuf assets through the same window policy, without HTTP. */
    suspend fun loadOffline(standardSegments: List<Path>, specialSegments: List<Path> = emptyList(), durationSeconds: Double = 0.0, expectedSourceVersion:Long? = null, stillOwned:(()->Boolean)? = null) {
        require(durationSeconds.isFinite() && durationSeconds >= 0)
        require(stillOwned==null||expectedSourceVersion!=null)
        loadSource(OfflineDanmakuSource(standardSegments, specialSegments), 1L, 0L, durationSeconds, expectedSourceVersion, null, stillOwned)
    }

    /** Switches from VOD segments to genuine realtime events received by the upstream live client. */
    fun enterLive(): Long {
        val version = synchronized(requestLock) {
            check(!cacheMaintenance) { "弹幕缓存正在维护" }
            generation.incrementAndGet().also { loadJob?.cancel(); windowJob?.cancel(); windowLoader?.close();windowLoader=null;retireWebMaskSource(); liveMode = true; pendingLive.clear() }
                .also { offlineDocumentOwner=null; commandCid = null; mutableCommands.value = emptyList(); mutableAdvanced.value=emptyList(); originalSectionDanmakuViewport=null }
        }
        mutableError.value = null; mutableFormat.value = null
        SwingUtilities.invokeLater {
            if (!closed.get() && version == generation.get() && liveMode) {
                clearBasPresentation();liveRenderer.reset(); mutableCount.value = 0; panel.repaint()
            }
        }
        return version
    }

    fun emitLive(item: LiveDanmakuItem, liveGeneration: Long = generation.get()) {
        if (!closed.get() && liveMode && liveGeneration == generation.get()) pendingLive.offer(PendingLive(liveGeneration, item))
    }

    fun removeLiveSuperChats(ids: List<Long>, liveGeneration: Long = generation.get()) {
        SwingUtilities.invokeLater {
            if (!closed.get() && liveMode && liveGeneration == generation.get()) {
                liveRenderer.removeSuperChats(ids); mutableCount.value = liveRenderer.size
            }
        }
    }

    fun clearLive(liveGeneration: Long? = null) {
        synchronized(requestLock) {
            if (liveMode && (liveGeneration == null || liveGeneration == generation.get())) setDocument(DanmakuDocument())
        }
    }

    private suspend fun loadSource(playbackSource: DesktopDanmakuSource, cid: Long, aid: Long, durationSeconds: Double, expectedSourceVersion: Long?, maskSource:DesktopOwnedWebMaskSource?, offlineOwner:(()->Boolean)? = null) {
        val sourceSnapshot=player.currentSourceSnapshot()?.takeIf {expectedSourceVersion==null || it.sourceVersion==expectedSourceVersion}
        val windowAdmission=WindowAdmission(sourceSnapshot,offlineOwner,
            basActionBinding?.let {sourceSnapshot!=null && sameBasSource(sourceSnapshot,it.source)}==true ||
                synchronized(requestLock) {originalClickBindings.isNotEmpty()})
        val version = synchronized(requestLock) {
            check(!cacheMaintenance && !closed.get()) { "弹幕缓存正在维护" }
            generation.incrementAndGet().also {
                loadJob?.cancel(); windowJob?.cancel(); windowLoader?.close();windowLoader=null
                liveMode = false; pendingLive.clear()
                // Clear the old document before publishing a new CID/version identity.
                rawDocument = DanmakuDocument()
                poolSourceVersion = expectedSourceVersion
                offlineDocumentOwner = offlineOwner?.let {requireNotNull(expectedSourceVersion) to it}
                commandCid = cid; mutableCommands.value = emptyList()
                mutableError.value = null; mutableFormat.value = null
            }
        }
        synchronized(requestLock){if(version==generation.get() && !closed.get())bindWebMaskSource(maskSource)}
        lastWebMaskRefreshNanos=Long.MIN_VALUE;lastWebMaskPositionMs=Long.MIN_VALUE
        installDocument(DanmakuDocument(), version)
        val loadingLoader=java.util.concurrent.atomic.AtomicReference<DanmakuWindowLoader?>()
        val loading = synchronized(requestLock) {
          if(cacheMaintenance || closed.get() || version!=generation.get())throw CancellationException("弹幕缓存维护或请求已退役")
          requests.async {
            val loader = DanmakuWindowLoader(playbackSource, cid, aid, (durationSeconds * 1000).toLong(),requests) {
                resolvedConfig?.second?.let {maxOf(it.scrollDurationMs,it.pinnedDurationMs)} ?:
                    (maxOf(settings.scrollDurationSeconds,settings.staticDurationSeconds)*settings.speedFactor*1_000).toLong()
            }
            loadingLoader.set(loader)
            val admitted=synchronized(requestLock) {
                if(!cacheMaintenance && version==generation.get() && !closed.get()) {windowLoader=loader;true} else false
            }
            if(!admitted) {loader.close();throw CancellationException("Special loader owner retired")}
            val result = loader.initial((player.state.value.positionSeconds * 1000).toLong())
            publish(result, version, windowAdmission)
            synchronized(requestLock) {
                if (version == generation.get() && !closed.get() && currentOfflineDocumentOwned()) {
                    windowJob = requests.launch {
                      try {
                        val windowContext=currentCoroutineContext()
                        combine(player.state,loader.specialRevision) {state,_->
                            loader.refreshKey((state.positionSeconds*1_000).toLong(),state.seekCompletedId)
                        }
                            .distinctUntilChanged().collectLatest {
                                try {
                                    if(!currentOfflineDocumentOwned() || sourceSnapshot?.let { !player.ownsSourceSnapshot(it) }==true) {
                                        // collectLatest runs its action in a child. Throwing CE there
                                        // alone leaves the collector and its sibling index IO alive.
                                        windowContext.cancel(CancellationException("Document source owner retired"))
                                        return@collectLatest
                                    }
                                    if(version==generation.get() && !closed.get())onWebMaskSegmentWindowChanged()
                                    val next = loader.move((player.state.value.positionSeconds * 1000).toLong(),player.state.value.seekCompletedId)
                                    if (next != null) publish(next, version, windowAdmission)
                                } catch (failure: Exception) {
                                    if (failure is CancellationException) throw failure
                                    if (version == generation.get() && !closed.get())
                                        mutableError.value = "弹幕分段加载失败：${failure.message ?: "network error"}"
                                }
                            }
                      } finally {loader.close()}
                    }
                } else {
                    loader.close()
                }
            }
        }
        }
        synchronized(requestLock) {
            if (version == generation.get() && !closed.get()) loadJob = loading else loading.cancel()
        }
        try {
            loading.await()
        } catch (failure: Exception) {
            if (failure is CancellationException) { loadingLoader.get()?.close();loading.cancel();throw failure }
            loadingLoader.get()?.close()
            if (version == generation.get() && !closed.get()) mutableError.value = "弹幕加载失败：${failure.message ?: "network error"}"
        }
    }

    private class WindowAdmission(val source:OwnedPlaybackSourceSnapshot?,val legacyOwned:(()->Boolean)?,formal:Boolean) {
        val formalOwnerRequired=AtomicBoolean(formal)
    }
    /** A request which acquired a formal Root owner can never fall back after
     * that owner retires. Independent embeddings require their explicit port. */
    private fun admitWindow(receipt:WindowAdmission,action:()->Unit):Boolean {
        val source=receipt.source
        if(source==null) {action();return true}
        val bas=basActionBinding?.takeIf {sameBasSource(source,it.source)}
        if(bas!=null) {
            receipt.formalOwnerRequired.set(true)
            return bas.admit {if(basActionBinding===bas && bas.owned() && player.ownsSourceSnapshot(source))action()}
        }
        val original=synchronized(requestLock) {originalClickBindings.values.lastOrNull {it.owns()}}
        if(original!=null) {
            receipt.formalOwnerRequired.set(true)
            return original.admit(source.sourceVersion) {
                synchronized(requestLock) {if(originalClickBindings[original.token]===original && original.owns() && player.ownsSourceSnapshot(source))action()}
            }
        }
        if(receipt.formalOwnerRequired.get())return false
        basStandaloneAdmission?.let {return it(source,action)}
        if(receipt.legacyOwned?.invoke()==true && currentOfflineDocumentOwned() && player.ownsSourceSnapshot(source)) {action();return true}
        return false
    }

    private fun publish(result: DanmakuWindowResult, version: Long, receipt:WindowAdmission) {
        val source=receipt.source
        admitWindow(receipt) {
            synchronized(requestLock) {
                if(version!=generation.get() || closed.get() || !currentOfflineDocumentOwned() ||
                    source?.let {!player.ownsSourceSnapshot(it)}==true)return@synchronized
                val position=(player.state.value.positionSeconds*1_000).toLong()
                if(result.specialStartMs!=null && result.specialEndMs!=null &&
                    position !in result.specialStartMs until result.specialEndMs)return@synchronized
                mutableError.value=result.warning;mutableCommands.value=result.commands;mutableFormat.value=result.format
                if(result.specialOnly)publishSpecialWindow(result.document,version,receipt)
                else {
                    val local=rawDocument.comments.filter {it.originalLocalItem!=null}
                    val comments=(result.document.comments+local).distinctBy {it.id}.sortedBy {it.timeSeconds}
                    installDocument(result.document.copy(comments=comments),version)
                }
            }
        }
    }

    /** Ordinary lanes/styles, consumed cursor, plugin job and local phase remain
     * intact when only the short special window changes. Pending ordinary work
     * merges this latest advanced list at its existing final install boundary. */
    private fun publishSpecialWindow(document:DanmakuDocument,version:Long,receipt:WindowAdmission) {
        val source=receipt.source
        if(rawDocument.advanced==document.advanced && rawDocument.bas==document.bas)return
        rawDocument=rawDocument.copy(advanced=document.advanced,bas=document.bas,
            serverDisabled=rawDocument.serverDisabled || document.serverDisabled)
        mutableAdvanced.value=document.advanced
        val revision=documentRevision.incrementAndGet();mutablePoolSourceRevision.value=revision
        val advanced=document.advanced
        SwingUtilities.invokeLater {
            admitWindow(receipt) {
                synchronized(requestLock) {
                    if(version==generation.get() && !closed.get() && currentOfflineDocumentOwned() &&
                        rawDocument.advanced===advanced && source?.let {!player.ownsSourceSnapshot(it)}!=true) {
                        advancedRenderer=AdvancedDanmakuRenderer(advanced)
                        hotDocument?.takeIf {it.generation==version}?.let {hotDocument=it.copy(revision=documentRevision.get())}
                        if(originalInstalledGeneration==version)originalInstalledRevision=documentRevision.get()
                        if(vodBaseGeneration==version)vodBaseCount=(hotDocument?.comments?.size ?: rawDocument.comments.size)+advanced.size
                        updateVodCount();panel.repaint()
                    }
                }
            }
        }
        refreshBasDocument()
    }

    internal fun cacheMemoryEstimate(): Long = synchronized(requestLock) {
        (windowLoader?.cachedBytes() ?: 0L)+rawDocument.comments.sumOf { it.text.length*2L+64L }
    }
    /** VOD memory only. Existing jobs (including inherited masks) drain outside requestLock;
     * the same generation excludes stale plugin/EDT publication. Live image cache is not claimed. */
    internal suspend fun clearIdleCache(checkRequest: () -> Unit) {
        checkRequest()
        val (version,jobs)=synchronized(requestLock) {
            check(!closed.get() && !cacheMaintenance);require(!liveMode) { "请退出直播后再清理点播弹幕缓存" }
            cacheMaintenance=true
            val ownedJobs=requests.coroutineContext[Job]!!.children.toList()
            val next=generation.incrementAndGet();retireWebMaskSource()
            ownedJobs.forEach {it.cancel()}
            next to ownedJobs
        }
        try {
            jobs.forEach {it.join()};checkRequest()
            synchronized(requestLock) {
                check(!closed.get());loadJob=null;windowJob=null;pluginJob=null;windowLoader?.close();windowLoader=null
                rawDocument=DanmakuDocument();offlineDocumentOwner=null;poolSourceVersion=null
                commandCid=null;pendingLive.clear();mutableCommands.value=emptyList();mutableAdvanced.value=emptyList()
                mutableError.value=null;mutableFormat.value=null;originalLocalInjectionPhase=Any();documentRevision.incrementAndGet()
            }
            val painted=kotlinx.coroutines.CompletableDeferred<Unit>()
            SwingUtilities.invokeLater {
                try {
                    check(!closed.get() && cacheMaintenance && version==generation.get())
                    scheduler=DanmakuScheduler(emptyList(),settings,liveAdmission=false)
                    advancedRenderer=AdvancedDanmakuRenderer(emptyList());styles=emptyMap();measuredWidths.clear()
                    clearBasPresentation()
                    mutableCount.value=0;panel.repaint();painted.complete(Unit)
                } catch(failure:Throwable) { painted.completeExceptionally(failure) }
            }
            kotlinx.coroutines.withTimeout(3_000L) {painted.await()};checkRequest()
        } finally { synchronized(requestLock) {cacheMaintenance=false} }
    }

    /** One supplied document admitted by the already retained native source/account/plugin owner. */
    internal fun setOwnedDocument(document: DanmakuDocument, expectedSourceVersion: Long, stillOwned: () -> Boolean): Boolean {
        val version = synchronized(requestLock) {
            if (cacheMaintenance || closed.get() || !player.ownsSourceVersion(expectedSourceVersion) || !stillOwned()) return false
            generation.incrementAndGet().also {
                loadJob?.cancel(); windowJob?.cancel();windowLoader?.close();windowLoader=null;pluginJob?.cancel(); liveMode = false; pendingLive.clear()
                retireWebMaskSource(); rawDocument = DanmakuDocument()
                poolSourceVersion = expectedSourceVersion; offlineDocumentOwner = expectedSourceVersion to stillOwned
                commandCid = null; mutableCommands.value = emptyList(); mutableAdvanced.value = emptyList()
                mutableError.value = null; mutableFormat.value = null
            }
        }
        installDocument(document, version)
        return true
    }
    internal fun clearOwnedDocument(expectedSourceVersion: Long) = synchronized(requestLock) {
        if (poolSourceVersion == expectedSourceVersion && offlineDocumentOwner?.first == expectedSourceVersion) {
            generation.incrementAndGet(); loadJob?.cancel(); windowJob?.cancel(); windowLoader?.close();windowLoader=null;pluginJob?.cancel()
            offlineDocumentOwner = null; poolSourceVersion = null; rawDocument = DanmakuDocument()
            mutableCommands.value = emptyList(); mutableAdvanced.value = emptyList(); mutableCount.value = 0
            val clearedGeneration = generation.get()
            SwingUtilities.invokeLater {
                if (!closed.get() && clearedGeneration == generation.get()) {
                    scheduler = DanmakuScheduler(emptyList(), settings, liveAdmission = false)
                    advancedRenderer = AdvancedDanmakuRenderer(emptyList()); clearBasPresentation(); panel.repaint()
                }
            }
        }
    }

    /** Also supports locally supplied documents and deterministic offline verification. */
    fun setComments(comments: List<DanmakuComment>) {
        setDocument(DanmakuDocument(comments))
    }

    fun setDocument(document: DanmakuDocument) {
        val version = synchronized(requestLock) {
            check(!cacheMaintenance) { "弹幕缓存正在维护" }
            check(!cacheMaintenance && !closed.get()) { "弹幕缓存正在维护" }
            generation.incrementAndGet().also {
                loadJob?.cancel(); windowJob?.cancel(); windowLoader?.close();windowLoader=null;liveMode = false; pendingLive.clear()
                retireWebMaskSource()
                offlineDocumentOwner=null
                commandCid = null; mutableCommands.value = emptyList()
            }
        }
        mutableError.value = null
        mutableFormat.value = null
        installDocument(document, version)
    }

    private data class DocumentInstallSnapshot(val settings:DanmakuSettings,val phase:Any,
        val raw:DanmakuDocument,val revision:Long)

    private fun installDocument(document: DanmakuDocument, version: Long) {
        val (revision, processor) = synchronized(requestLock) {
            if (cacheMaintenance || version != generation.get() || closed.get() || !currentOfflineDocumentOwned()) return
            originalLocalInjectionPhase=Any()
            pluginJob?.cancel()
            rawDocument = document.copy(serverDisabled = rawDocument.serverDisabled || document.serverDisabled)
            mutableAdvanced.value = document.advanced
            documentRevision.incrementAndGet().also {
                documentInstallRevision=it;mutablePoolSourceRevision.value = it
            } to pluginProcessor
        }
        val inputIds=document.comments.mapTo(hashSetOf()) {it.id}
        val inputLocalIds=document.comments.asSequence().filter {it.originalLocalItem!=null}.map {it.id}.toSet()
        fun install(processed: DanmakuDocument, nextStyles: Map<Int, DanmakuStyle>) {
            SwingUtilities.invokeLater {
                fun current() = !closed.get() && version==generation.get() && revision==documentInstallRevision && currentOfflineDocumentOwned()
                val captured=synchronized(requestLock) {
                    if(current())DocumentInstallSnapshot(settings,originalLocalInjectionPhase,rawDocument,documentRevision.get()) else null
                } ?: return@invokeLater
                // Local sends accepted while this pipeline was busy are already
                // downstream of preprocessing. Merge them once into this result.
                val local=captured.raw.comments.filter {it.originalLocalItem!=null &&
                    it.id !in inputIds}
                val installedComments=(processed.comments+local).sortedBy {it.timeSeconds}
                // Parsing/filter preparation is outside the publication monitor. The renderer and hot view
                // are swapped together only if this exact document/settings/phase still owns the result.
                val nextScheduler=DanmakuScheduler(installedComments,captured.settings,liveAdmission=false,
                    immediateLocalPhase=captured.phase)
                val nextAdvanced=AdvancedDanmakuRenderer(captured.raw.advanced)
                val applied=synchronized(requestLock) {
                    if(!current() || settings!=captured.settings || originalLocalInjectionPhase!==captured.phase ||
                        rawDocument!==captured.raw || documentRevision.get()!=captured.revision)false
                    else {
                        styles=nextStyles;scheduler=nextScheduler;advancedRenderer=nextAdvanced
                        hotDocument=HotDocument(version,captured.revision,installedComments,inputLocalIds+local.map {it.id})
                        originalInstalledGeneration=version;originalInstalledRevision=captured.revision
                        measuredWidths.clear();true
                    }
                }
                if(applied) {
                    if(synchronized(requestLock){current()}) {
                        vodBaseGeneration=version; vodBaseCount=installedComments.size+captured.raw.advanced.size; updateVodCount()
                    }
                    panel.repaint()
                } else if(synchronized(requestLock){current()})install(processed,nextStyles)
            }
        }
        refreshBasDocument()
        if (processor == null) install(document, emptyMap())
        else {
          val processing = requests.launch(Dispatchers.Default) {
            val next = mutableListOf<DanmakuComment>()
            val nextStyles = mutableMapOf<Int, DanmakuStyle>()
            document.comments.forEachIndexed { index, comment ->
                if (index % 128 == 0) ensureActive()
                applyDesktopDanmakuPlugin(comment, processor)?.let { transformed ->
                    next += transformed.comment
                    transformed.style?.let { nextStyles[transformed.comment.id] = it }
                }
            }
            install(document.copy(comments = next), nextStyles)
          }
          synchronized(requestLock) {
              if (version == generation.get() && revision == documentInstallRevision && !closed.get()) pluginJob = processing
              else processing.cancel()
          }
        }
    }

    private fun tick() {
        if (closed.get()) return
        validateWebMaskOwnership()
        val maskPosition=webMaskPositionMs()
        val maskNow=System.nanoTime()
        val maskInterval=resolveDanmakuDriftSyncIntervalMs(player.state.value.speed.toFloat())*1_000_000L
        val maskJump=lastWebMaskPositionMs!=Long.MIN_VALUE && kotlin.math.abs(maskPosition-lastWebMaskPositionMs)>5_000L
        if(lastWebMaskRefreshNanos==Long.MIN_VALUE || maskJump || maskNow-lastWebMaskRefreshNanos>=maskInterval){
            lastWebMaskRefreshNanos=maskNow;lastWebMaskPositionMs=maskPosition
            refreshWebMaskWindow(maskPosition)
        }
        val surface = player.surface
        basInput.attach(surface.components.filterIsInstance<java.awt.Canvas>().singleOrNull())
        if (basInstallation?.let(::basCurrent) == false) {
            clearBasPresentation(); updateVodCount()
        }
        if (liveMode) {
            repeat(200) {
                val pending = pendingLive.poll()
                if (pending != null && pending.generation == generation.get()) liveRenderer.add(pending.item)
            }
            liveRenderer.expire(); mutableCount.value = liveRenderer.size
        }
        val currentOwner = SwingUtilities.getWindowAncestor(surface)
        val playerState = player.state.value
        val visible = ((enabled && mutableCount.value > 0) || eyeTint.visible || ownedViewportBrightness()?.second?.let {it<1f}==true) && surface.isShowing && surface.width > 0 && surface.height > 0 &&
            currentOwner != null && currentOwner.isVisible &&
            (currentOwner !is Frame || currentOwner.extendedState and Frame.ICONIFIED == 0) &&
            playerState.ready && playerState.firstVideoFrameReady && playerState.videoCodec != null && playerState.error == null && !playerState.ended && !playerState.audioOnly
        if (!visible) { originalPaintFrame=null;originalPointerHit=null;basPaintFrame=null;basInput.cancel();overlay?.isVisible = false; ownerWasActive = false; return }
        if (currentOwner != owner) {
            overlay?.dispose()
            owner = currentOwner
            if (!GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
                    .isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT)) {
                mutableError.value = "当前显示环境不支持透明弹幕窗口。"
                enabled = false
                return
            }
            overlay = JWindow(currentOwner).apply {
                focusableWindowState = false
                isAutoRequestFocus = false
                background = Color(0, 0, 0, 0)
                rootPane.isOpaque = false
                layeredPane.isOpaque = false
                (contentPane as JComponent).isOpaque = false
                contentPane.add(panel)
            }
        }
        val window = overlay ?: return
        val location = surface.locationOnScreen
        if (window.x != location.x || window.y != location.y || window.width != surface.width || window.height != surface.height)
            window.setBounds(location.x, location.y, surface.width, surface.height)
        val showing = !window.isVisible
        if (window.isAlwaysOnTop != currentOwner.isAlwaysOnTop) window.isAlwaysOnTop = currentOwner.isAlwaysOnTop
        if (showing) window.isVisible = true
        val hwnd = Native.getWindowPointer(window)
        val style = user32.GetWindowLongW(hwnd, -20)
        if (showing) user32.SetWindowLongW(hwnd, -20, style or 0x00080000 or 0x00000020 or 0x08000000)
        val nativeTopmost = style and 0x00000008 != 0
        if (showing || (currentOwner.isActive && !ownerWasActive) || nativeTopmost != currentOwner.isAlwaysOnTop) {
            // Restore only this owned popup after a host activation/reattachment. Do not keep
            // raising a background application or cover newer dialogs/menus on every repaint.
            val band = if (currentOwner.isAlwaysOnTop) Pointer.createConstant(-1L) else Pointer.createConstant(if (nativeTopmost) -2L else 0L)
            user32.SetWindowPos(hwnd, band, 0, 0, 0, 0, 0x0001 or 0x0002 or 0x0010 or 0x0200)
        }
        ownerWasActive = currentOwner.isActive
        val now = System.nanoTime()
        if (playerState.positionSeconds != lastPosition) {
            lastPosition = playerState.positionSeconds
            anchoredPosition = playerState.positionSeconds
            sampleTimeNanos = now
        }
        val offset = if (playerState.paused || playerState.loading) 0.0 else
            ((now - sampleTimeNanos) / 1_000_000_000.0).coerceIn(0.0, 0.3) * playerState.speed.coerceIn(0.1, 8.0)
        displayTime = anchoredPosition + offset
        panel.repaint()
    }

    /** Fixture diagnostics describe geometry and native state, never user comments or source credentials. */
    internal fun nativeRenderSnapshot(): String {
        check(SwingUtilities.isEventDispatchThread())
        val surface = player.surface
        val window = overlay
        val ancestor = SwingUtilities.getWindowAncestor(surface)
        val playerState = player.state.value
        val native = window?.takeIf { it.isDisplayable }?.let { nativeWindow ->
            runCatching { "nativeExtendedStyle=0x${user32.GetWindowLongW(Native.getWindowPointer(nativeWindow), -20).toString(16)}" }.getOrElse { "nativeExtendedStyle=unavailable" }
        } ?: "nativeWindow=absent"
        return "surface=${surface.bounds}, showing=${surface.isShowing}, ancestor=${ancestor?.javaClass?.simpleName}, ancestorVisible=${ancestor?.isVisible}, ancestorFrameState=${(ancestor as? Frame)?.extendedState}, " +
            "owner=${owner?.javaClass?.simpleName}, ownerActive=${owner?.isActive}, ownerTopmost=${owner?.isAlwaysOnTop}, timerRunning=${timer.isRunning}, enabled=$enabled, " +
            "overlay=${window?.bounds}, showing=${window?.isShowing}, overlayTopmost=${window?.isAlwaysOnTop}, panel=${panel.bounds}, comments=${mutableCount.value}, " +
            "mediaTime=$displayTime, ready=${playerState.ready}, firstFrame=${playerState.firstVideoFrameReady}, nativePaused=${playerState.nativePaused}, " +
            "videoCodecPresent=${playerState.videoCodec != null}, ended=${playerState.ended}, audioOnly=${playerState.audioOnly}, errorPresent=${playerState.error != null}, $native"
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            synchronized(requestLock) { generation.incrementAndGet();originalClickBindings.clear();loadJob?.cancel(); windowJob?.cancel(); windowLoader?.close();windowLoader=null;pluginJob?.cancel(); retireWebMaskSource() }
            requests.cancel()
            SwingUtilities.invokeLater {
                originalPaintFrame=null;originalPointerHit=null;basPaintFrame=null;basActionBinding=null
                basInput.close();basRenderer.close();basInstallation=null
                timer.stop(); overlay?.dispose(); overlay = null
            }
        }
    }

    private interface User32 : StdCallLibrary {
        fun GetWindowLongW(window: Pointer, index: Int): Int
        fun SetWindowLongW(window: Pointer, index: Int, value: Int): Int
        fun SetWindowPos(window: Pointer, insertAfter: Pointer?, x: Int, y: Int, width: Int, height: Int, flags: Int): Boolean
    }

    companion object {
        private val user32 by lazy { Native.load("user32", User32::class.java) }
    }
}

/** Immutable projection metadata; comments is the sole raw-document list reference. */
data class DanmakuPoolSourceSnapshot(val cid:Long,val sourceVersion:Long,val revision:Long,val comments:List<DanmakuComment>)
