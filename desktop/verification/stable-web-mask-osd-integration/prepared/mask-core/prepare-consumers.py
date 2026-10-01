from pathlib import Path
import hashlib,json
L=Path(__file__).resolve().parent;ROOT=L.parents[2]
def safe(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix) else prefix+s)
def read(p):return safe(p).read_text(encoding='utf-8').replace('\r\n','\n')
def sha(s):return hashlib.sha256(s.encode()).hexdigest()
def write(p,s):safe(p).parent.mkdir(parents=True,exist_ok=True);safe(p).write_text(s,encoding='utf-8',newline='\n')
def save(p,o):write(p,json.dumps(o,ensure_ascii=False,indent=2)+'\n')
H=json.loads(read(L/'local-hunks.json'))
def patch(path,s,old,new,reason):
 assert s.count(old)==1,(path,old[:180],s.count(old));out=s.replace(old,new);H.append(dict(path=path,old=old,new=new,beforeSha256LF=sha(s),afterSha256LF=sha(out),reason=reason));return out
path='desktop/src/main/kotlin/com/bilipai/desktop/danmaku/DanmakuSettings.kt';s=read(L/'baseline'/path)
s=patch(path,s,'    val weightFilterLevel: Int = 0,','    val weightFilterLevel: Int = 0,\n    @kotlinx.serialization.Transient\n    val smartOcclusionEnabled: Boolean = false,','Same scalar ephemeral projection receives original smart toggle; transient prevents duplicate persisted authority')
write(L/'review-only'/path,s)
path='desktop/src/main/kotlin/com/bilipai/desktop/danmaku/DesktopOriginalDanmakuRenderPlatform.kt';s=read(L/'baseline'/path)
s=patch(path,s,'        // Mask detection and portrait SCREEN_TOP are separate pending native consumers; no fabricated safe band.','        it.smartOcclusionEnabled=smartOcclusionEnabled\n        // Original smart-on default band; updateFaceOcclusion has no caller at the fixed tag.\n        it.safeBandTopRatio=0f;it.safeBandBottomRatio=if(smartOcclusionEnabled)displayAreaRatio else 1f\n        // Portrait SCREEN_TOP remains a separate actual-layout consumer.','Consume original smart flag/default display band without inventing an unused face-detection caller')
write(L/'review-only'/path,s)
path='desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopDanmakuSettingsProjection.kt';s=read(L/'baseline'/path)
s=patch(path,s,'    massiveMode=original.massiveMode,weightFilterLevel=original.weightFilterLevel,','    massiveMode=original.massiveMode,weightFilterLevel=original.weightFilterLevel,\n    smartOcclusionEnabled=original.smartOcclusion,','Original full preference setter flows through the sole projection to the actual renderer')
write(L/'review-only'/path,s)
path='desktop/src/main/kotlin/com/bilipai/desktop/danmaku/DanmakuOverlay.kt';s=read(L/'baseline'/path)
s=patch(path,s,'import com.android.purebilibili.feature.video.danmaku.DanmakuViewport','import com.android.purebilibili.feature.video.danmaku.DanmakuViewport\nimport com.android.purebilibili.feature.video.danmaku.DesktopOriginalWebMaskOwner\nimport com.android.purebilibili.feature.video.danmaku.resolveDanmakuDriftSyncIntervalMs','Original mask member owner/refresh policy, same existing native Overlay')
s=patch(path,s,') : AutoCloseable {',') : DesktopOriginalWebMaskOwner(), AutoCloseable {','One existing Overlay inherits original mask members; no second actor')
s=patch(path,s,'    private var pluginJob: Job? = null','''    private var pluginJob: Job? = null
    override val webMaskLock:Any get()=requestLock
    override val scope:CoroutineScope get()=requests
    override val cachedCid:Long get()=commandCid ?: 0L
    override val loadGeneration:Long get()=generation.get()
    override val webMaskEnabled:Boolean get()=settings.smartOcclusionEnabled && !liveMode && !closed.get()
    override val webMaskTransport:DesktopDanmakuSource get()=source
    override fun webMaskPositionMs():Long=(player.state.value.positionSeconds*1000).toLong().coerceAtLeast(0L)
    override fun invalidateWebMaskPaint(){SwingUtilities.invokeLater {if(!closed.get())panel.repaint()}}
    private var lastWebMaskRefreshNanos=Long.MIN_VALUE
    private var lastWebMaskPositionMs=Long.MIN_VALUE''','All task/lock/transport/config/time/paint ports belong to the existing Overlay')
s=patch(path,s,'val font:Font,val live:Boolean)','val font:Font,val live:Boolean,val maskReady:Boolean)','Source mask readiness participates in the sole render-config cache')
s=patch(path,s,'renderPlatform.resolveTypeface(configuration.fontWeight),true)','renderPlatform.resolveTypeface(configuration.fontWeight),true,false)','Live never enables the VOD metadata mask')
s=patch(path,s,'renderPlatform.resolveTypeface(configuration.fontWeight),false)','renderPlatform.resolveTypeface(configuration.fontWeight),false,webMaskAvailable())','Same original smart && bytes readiness drives render config')
s=patch(path,s,'configuration.originalConfig(renderPlatform).resolveRenderConfig(geometry.viewport).also','configuration.originalConfig(renderPlatform).resolveRenderConfig(geometry.viewport).copy(maskEnabled=key.maskReady).also','Original applyConfigToController maskEnabled semantics, same resolved config')
s=patch(path,s,'                    geometry.configurePhysicalPixels(physical)\n                    physical.composite=AlphaComposite.getInstance(AlphaComposite.SRC_OVER,config.alpha/255f)','                    geometry.configurePhysicalPixels(physical)\n                    applyDesktopWebMaskClip(physical,geometry.viewport.widthPx,geometry.viewport.heightPx,currentWebMaskFrame((displayTime*1000).toLong()))\n                    physical.composite=AlphaComposite.getInstance(AlphaComposite.SRC_OVER,config.alpha/255f)','Apply current owned SVG mask only to the standard physical child Graphics2D; advanced/eye tint remain later original layer consumers')
s=patch(path,s,'        val normalized = settings.normalized()\n        this.settings = normalized','        val normalized = settings.normalized()\n        val smartChanged=this.settings.smartOcclusionEnabled!=normalized.smartOcclusionEnabled\n        this.settings = normalized\n        if(smartChanged)onWebMaskSettingChanged()','Actual same-global smart toggle cancels/restarts only mask child jobs')
s=patch(path,s,'suspend fun load(cid: Long, aid: Long = 0L, durationSeconds: Double = 0.0, expectedSourceVersion: Long? = null)','suspend fun load(cid: Long, aid: Long = 0L, durationSeconds: Double = 0.0, expectedSourceVersion: Long? = null, maskSource:DesktopOwnedWebMaskSource?)','Required online fixed identity tail; null explicitly means no online metadata source')
s=patch(path,s,'        loadSource(source, cid, aid, durationSeconds, expectedSourceVersion)','        require(maskSource==null || (maskSource.cid==cid && maskSource.sourceVersion==expectedSourceVersion))\n        loadSource(source, cid, aid, durationSeconds, expectedSourceVersion, maskSource)','Do not admit mask identity from another CID/native source')
s=patch(path,s,'loadSource(OfflineDanmakuSource(standardSegments, specialSegments), 1L, 0L, durationSeconds, null)','loadSource(OfflineDanmakuSource(standardSegments, specialSegments), 1L, 0L, durationSeconds, null, null)','Offline path cannot issue online metadata/mask requests')
s=patch(path,s,'generation.incrementAndGet().also { loadJob?.cancel(); windowJob?.cancel(); liveMode = true; pendingLive.clear() }','generation.incrementAndGet().also { loadJob?.cancel(); windowJob?.cancel(); retireWebMaskSource(); liveMode = true; pendingLive.clear() }','Live source retires all VOD mask bytes/frames/tasks')
s=patch(path,s,'private suspend fun loadSource(playbackSource: DesktopDanmakuSource, cid: Long, aid: Long, durationSeconds: Double, expectedSourceVersion: Long?)','private suspend fun loadSource(playbackSource: DesktopDanmakuSource, cid: Long, aid: Long, durationSeconds: Double, expectedSourceVersion: Long?, maskSource:DesktopOwnedWebMaskSource?)','Same existing source-load actor receives the required fixed mask identity')
s=patch(path,s,'        installDocument(DanmakuDocument(), version)\n        val loading = requests.async','        synchronized(requestLock){if(version==generation.get() && !closed.get())bindWebMaskSource(maskSource)}\n        lastWebMaskRefreshNanos=Long.MIN_VALUE;lastWebMaskPositionMs=Long.MIN_VALUE\n        installDocument(DanmakuDocument(), version)\n        val loading = requests.async','Optional metadata/mask child launches concurrently with existing initial standard loading')
s=patch(path,s,'                                    val next = loader.move((player.state.value.positionSeconds * 1000).toLong())','                                    if(version==generation.get() && !closed.get())onWebMaskSegmentWindowChanged()\n                                    val next = loader.move((player.state.value.positionSeconds * 1000).toLong())','Original segment-window request retires obsolete mask decode generation')
s=patch(path,s,'                commandCid = null; mutableCommands.value = emptyList()','                retireWebMaskSource()\n                commandCid = null; mutableCommands.value = emptyList()','Locally supplied/cleared document cannot retain previous online mask source')
s=patch(path,s,'        val surface = player.surface\n        if (liveMode) {','''        validateWebMaskOwnership()
        val maskPosition=webMaskPositionMs()
        val maskNow=System.nanoTime()
        val maskInterval=resolveDanmakuDriftSyncIntervalMs(player.state.value.speed.toFloat())*1_000_000L
        val maskJump=lastWebMaskPositionMs!=Long.MIN_VALUE && kotlin.math.abs(maskPosition-lastWebMaskPositionMs)>5_000L
        if(lastWebMaskRefreshNanos==Long.MIN_VALUE || maskJump || maskNow-lastWebMaskRefreshNanos>=maskInterval){
            lastWebMaskRefreshNanos=maskNow;lastWebMaskPositionMs=maskPosition
            refreshWebMaskWindow(maskPosition)
        }
        val surface = player.surface
        if (liveMode) {''','Existing native timer reuses original speed-adjusted mask poll interval; seek jump requests immediately; retirement checked before visibility')
s=patch(path,s,'generation.incrementAndGet(); loadJob?.cancel(); windowJob?.cancel(); pluginJob?.cancel()','generation.incrementAndGet(); loadJob?.cancel(); windowJob?.cancel(); pluginJob?.cancel(); retireWebMaskSource()','Close cancels optional mask children before canceling the sole request scope')
write(L/'review-only'/path,s)
path='desktop/src/main/kotlin/com/bilipai/desktop/DesktopPlaybackController.kt';s=read(L/'baseline'/path)
old='                danmaku?.load(info.pages[index].cid, info.aid, info.pages[index].duration.toDouble(), expectedSourceVersion = version)'
new='''                danmaku?.load(info.pages[index].cid, info.aid, info.pages[index].duration.toDouble(), expectedSourceVersion = version,
                    maskSource=com.bilipai.desktop.danmaku.DesktopOwnedWebMaskSource(info.bvid,info.pages[index].cid,version,accountEpoch,
                        stillOwned={current?.let {owns(it) && it.sourceVersion==version && it.accountEpoch==accountEpoch && it.details.bvid==info.bvid && it.details.pages[it.index].cid==info.pages[index].cid}==true && native.ownsSourceVersion(version)},
                        metadata={bvid,cid->communityReports.playerMetadata(bvid,cid)}))'''
s=patch(path,s,old,new,'Same Controller current owner, epoch/native version and existing community/WBI metadata transport')
s=patch(path,s,'generation.incrementAndGet(); request?.cancel(); recovery?.cancel(); pluginLoad?.cancel()','generation.incrementAndGet(); request?.cancel(); recovery?.cancel(); pluginLoad?.cancel(); danmaku?.retireWebMaskSource()','Immediate owner invalidation retires mask network/decode independently of native window visibility')
write(L/'review-only'/path,s)
save(L/'local-hunks.json',H)
print('CONSUMER HUNKS',len(H))
