from pathlib import Path
import json,hashlib
LANE=Path(__file__).resolve().parent;MAIN=LANE.parents[2];REPO=MAIN.parent/'BiliPai-v023'
def sha(s):return hashlib.sha256(s.encode()).hexdigest()
def emit(path,s):
 p=LANE/path;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(s,encoding='utf-8',newline='\n')
changes=[]
def patch(s,a,b,label):
 assert s.count(a)==1,(label,s.count(a))
 changes.append({'label':label,'before':a,'after':b});return s.replace(a,b)
base=MAIN/'desktop/.local/stable-offline-task-player-parity'
assert hashlib.sha256((base/'frozen-handoff.json').read_bytes()).hexdigest()=='3146f18a8aa93dbabd88ea5bafda6192399db7ddf90d3226c4f093cb56cf4c23'
path='desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOfflineTaskPlayerBinding.kt'
raw=(base/'prepared'/path).read_text(encoding='utf-8');s=raw
s=patch(s,'    fun open(taskId: String, onOnlinePlay: (DownloadTask) -> Unit): Job? {','''    fun open(taskId: String, onOnlinePlay: (DownloadTask) -> Unit): Job? = openOwned(taskId,onOnlinePlay,null)

    /** The complete original screen owns its episode state, asset effect and two-second checkpoint effect. */
    internal fun openOriginal(taskId:String,onEpisode:(String)->Unit):Job? =
        openOwned(taskId,{throw IllegalStateException("视频文件已被删除")},onEpisode)

    private fun openOwned(taskId: String, onOnlinePlay: (DownloadTask) -> Unit, onEpisode:((String)->Unit)?): Job? {''','manual original-consumer entry without another actor')
s=patch(s,'{ open(previous.id, onOnlinePlay); Unit }','{ if(onEpisode!=null)onEpisode(previous.id) else open(previous.id, onOnlinePlay); Unit }','original previous UI state')
s=patch(s,'{ open(next.id, onOnlinePlay); Unit }','{ if(onEpisode!=null)onEpisode(next.id) else open(next.id, onOnlinePlay); Unit }','original next UI state')
a='''                    memory.assetsJob = entryScope.launch { loadDanmaku(taskId, nativeVersion, version) }
                    memory.checkpointJob = entryScope.launch {
                        while (isActive && ownsRequest(version) && ownsAcceptedSource()) {
                            delay(2_000L)
                            checkpoint()
                        }
                    }'''
s=patch(s,a,'''                    if(onEpisode==null) {
                        memory.assetsJob = entryScope.launch { loadDanmaku(taskId, nativeVersion, version) }
                        memory.checkpointJob = entryScope.launch {
                            while (isActive && ownsRequest(version) && ownsAcceptedSource()) {
                                delay(2_000L)
                                checkpoint()
                            }
                        }
                    }''','no duplicate original asset/checkpoint jobs')
s=patch(s,'    fun setDanmakuEnabled(enabled: Boolean) {','''    internal fun persistOriginal(taskId:String,positionMs:Long) {
        val version=generation.get()
        admit(version) {
            if(ownsAcceptedSource() && acceptedTaskId==taskId) {
                val durationMs=(player!!.state.value.durationSeconds*1000).toLong()
                manager.savePlaybackPosition(taskId,positionMs,durationMs)
            }
        }
    }

    internal suspend fun loadOriginalDanmaku(taskId:String,standard:List<java.nio.file.Path>,special:List<java.nio.file.Path>) {
        currentCoroutineContext().ensureActive()
        val nativeVersion=acceptedVersion ?: return
        val version=generation.get()
        if(!ownsRequest(version)||!ownsAcceptedSource()||acceptedTaskId!=taskId)return
        val task=selectedTask(taskId) ?: return
        overlay?.loadOffline(standard,special,task.item.duration.toDouble(),expectedSourceVersion=nativeVersion,
            stillOwned={ownsRequest(version)&&ownsAcceptedSource()&&acceptedTaskId==taskId&&acceptedVersion==nativeVersion})
        currentCoroutineContext().ensureActive()
    }

    internal fun releaseOriginal(taskId:String,nativeVersion:Long?) {
        if(nativeVersion==null)return
        val version=generation.get()
        admit(version) {
            if(ownsAcceptedSource() && acceptedTaskId==taskId && acceptedVersion==nativeVersion) {
                memory.stopPlayback();memory.current=null
                acceptedVersion=null;acceptedTaskId=null
            }
        }
    }

    fun setDanmakuEnabled(enabled: Boolean) {''','same Store entry-admission and exact source release')
recipe={'path':path,'baseFrozenBridge':'3146f18a8aa93dbabd88ea5bafda6192399db7ddf90d3226c4f093cb56cf4c23','baseLfSha256':sha(raw),'candidateLfSha256':sha(s),'hunks':changes}
emit('bridge-delta.json',json.dumps(recipe,ensure_ascii=False,indent=2));emit('compile-inputs/bridge/'+path,s)
rev=s
for c in changes[::-1]:assert rev.count(c['after'])==1;rev=rev.replace(c['after'],c['before'])
assert rev==raw

changes=[];path='desktop/src/main/kotlin/com/bilipai/desktop/danmaku/DanmakuOverlay.kt';raw=(REPO/path).read_text(encoding='utf-8');s=raw
s=patch(s,'    @Volatile private var eyeTint = DesktopEyeTint()','''    @Volatile private var eyeTint = DesktopEyeTint()
    @Volatile private var viewportBrightness:Pair<Long,Float>? = null
    private fun ownedViewportBrightness():Pair<Long,Float>? = viewportBrightness?.takeIf {
        !closed.get() && player.ownsSourceVersion(it.first)
    }''','one bounded source-owned viewport tint')
s=patch(s,'                eyeTint.paint(context, width, height)','''                ownedViewportBrightness()?.let { (_,brightness) -> DesktopEyeTint(1f-brightness,0f).paint(context,width,height) }
                eyeTint.paint(context, width, height)''','actual paint respects native version and retains plugin eye tint')
s=patch(s,'    fun setPluginDanmakuProcessor(processor: DanmakuPluginProcessor?) {','''    /** Video-viewport brightness, not Windows system/display brightness. Root writes on its owned UI actor. */
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

    fun setPluginDanmakuProcessor(processor: DanmakuPluginProcessor?) {''','actual same-overlay source-owned getter/setter/clear')
a='''        val visible = ((enabled && mutableCount.value > 0) || eyeTint.visible)'''
assert a in s
s=patch(s,a,'''        val visible = ((enabled && mutableCount.value > 0) || eyeTint.visible || ownedViewportBrightness()?.second?.let {it<1f}==true)''','native viewport tint visibility even when danmaku disabled')
a='''    suspend fun loadOffline(standardSegments: List<Path>, specialSegments: List<Path> = emptyList(), durationSeconds: Double = 0.0) {
        require(durationSeconds.isFinite() && durationSeconds >= 0)
        loadSource(OfflineDanmakuSource(standardSegments, specialSegments), 1L, 0L, durationSeconds, null, null)
    }'''
s=patch(s,a,'''    suspend fun loadOffline(standardSegments: List<Path>, specialSegments: List<Path> = emptyList(), durationSeconds: Double = 0.0, expectedSourceVersion:Long? = null, stillOwned:(()->Boolean)? = null) {
        require(durationSeconds.isFinite() && durationSeconds >= 0)
        require(stillOwned==null||expectedSourceVersion!=null)
        loadSource(OfflineDanmakuSource(standardSegments, specialSegments), 1L, 0L, durationSeconds, expectedSourceVersion, null, stillOwned)
    }''','original UI local asset load uses same existing source-generation publication guard')
s=patch(s,'    @Volatile private var rawDocument = DanmakuDocument()','''    @Volatile private var rawDocument = DanmakuDocument()
    // One captured native/entry-read-only owner for the existing OFFLINE window actor, not another task/cache.
    @Volatile private var offlineDocumentOwner:Pair<Long,()->Boolean>? = null
    private fun currentOfflineDocumentOwned():Boolean = offlineDocumentOwner?.let {
        !closed.get() && player.ownsSourceVersion(it.first) && it.second()
    } ?: !closed.get()''','bounded exact native/entry owner rejects retired offline documents')
s=patch(s,'                if (configuration.enabled) {','                if (configuration.enabled && currentOfflineDocumentOwned()) {','same native painter suppresses retired offline source immediately')
s=patch(s,'private suspend fun loadSource(playbackSource: DesktopDanmakuSource, cid: Long, aid: Long, durationSeconds: Double, expectedSourceVersion: Long?, maskSource:DesktopOwnedWebMaskSource?) {','private suspend fun loadSource(playbackSource: DesktopDanmakuSource, cid: Long, aid: Long, durationSeconds: Double, expectedSourceVersion: Long?, maskSource:DesktopOwnedWebMaskSource?, offlineOwner:(()->Boolean)? = null) {','same actor captures required full-UI owner at its existing load boundary')
s=patch(s,'                poolSourceVersion = expectedSourceVersion','''                poolSourceVersion = expectedSourceVersion
                offlineDocumentOwner = offlineOwner?.let {requireNotNull(expectedSourceVersion) to it}''','new online or offline load replaces rather than accumulates previous document owner')
s=patch(s,'            publish(result, version)\n            synchronized(requestLock) {\n                if (version == generation.get() && !closed.get()) {','''            publish(result, version)
            synchronized(requestLock) {
                if (version == generation.get() && !closed.get() && currentOfflineDocumentOwned()) {''','retired entry cannot start an offline window reader')
s=patch(s,'                            .distinctUntilChanged().collectLatest {\n                                try {','''                            .distinctUntilChanged().collectLatest {
                                try {
                                    if(!currentOfflineDocumentOwned())throw CancellationException("Offline document owner retired")''','old offline window stops reading after captured entry/native source retires')
s=patch(s,'    private fun publish(result: DanmakuWindowResult, version: Long) {\n        synchronized(requestLock) {\n            if (version != generation.get() || closed.get()) return','''    private fun publish(result: DanmakuWindowResult, version: Long) {
        synchronized(requestLock) {
            if (version != generation.get() || closed.get() || !currentOfflineDocumentOwned()) return''','late offline decode cannot publish into a retired document')
s=patch(s,'    private fun installDocument(document: DanmakuDocument, version: Long) {\n        val (revision, processor) = synchronized(requestLock) {\n            if (version != generation.get() || closed.get()) return','''    private fun installDocument(document: DanmakuDocument, version: Long) {
        val (revision, processor) = synchronized(requestLock) {
            if (version != generation.get() || closed.get() || !currentOfflineDocumentOwned()) return''','plugin/raw-document admission retains the same offline source owner')
s=patch(s,'            if (!closed.get() && version == generation.get() && revision == documentRevision.get()) {','            if (!closed.get() && version == generation.get() && revision == documentRevision.get() && currentOfflineDocumentOwned()) {','deferred same-EDT painter installation rejects late offline owner')
s=patch(s,'                .also { commandCid = null; mutableCommands.value = emptyList() }','                .also { offlineDocumentOwner=null; commandCid = null; mutableCommands.value = emptyList() }','existing live actor clears prior offline-owner marker')
s=patch(s,'                retireWebMaskSource()\n                commandCid = null; mutableCommands.value = emptyList()','                retireWebMaskSource()\n                offlineDocumentOwner=null\n                commandCid = null; mutableCommands.value = emptyList()','direct documents and exact release clear prior offline ownership')
recipe={'path':path,'baseLfSha256':sha(raw),'candidateLfSha256':sha(s),'hunks':changes}
emit('overlay-delta.json',json.dumps(recipe,ensure_ascii=False,indent=2));emit('compile-inputs/overlay/'+path,s)
rev=s
for c in changes[::-1]:assert rev.count(c['after'])==1;rev=rev.replace(c['after'],c['before'])
assert rev==raw
print(json.dumps({'bridgeHunks':5,'overlayHunks':len(changes),'noLiveWrites':True}))
