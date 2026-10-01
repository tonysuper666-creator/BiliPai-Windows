from pathlib import Path
import hashlib,json
HERE=Path(__file__).resolve().parent; REPO=HERE.parents[2].parent/'BiliPai-v023'; OUT=HERE/'offline-root-install49'
assert not OUT.exists()
def wide(p):
 s=str(Path(p).absolute()); prefix=chr(92)*2+'?'+chr(92); return Path(s if s.startswith(prefix) else prefix+s)
def read(p): return wide(p).read_bytes()
def lf(p): return read(p).replace(b'\r\n',b'\n').decode()
sha=lambda b:hashlib.sha256(b).hexdigest()
pending={}; hunks=[]
def edit(path,old,new,label):
 data=pending.get(path,lf(REPO/path)); assert data.count(old)==1,label
 pending[path]=data.replace(old,new,1); hunks.append(dict(path=path,label=label,before=old,after=new))
root='desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopReadyOriginalRootMount.kt'
edit(root,'    val route = AtomicReference<DesktopOriginalRootRouteAssembly?>()', '''    private val chromeRefresh = AtomicReference<(() -> Unit)?>(null)
    fun installCurrentRootChrome(refresh: () -> Unit) { if (isActive()) chromeRefresh.set(refresh) }
    fun refreshCurrentRootChrome() { if (isActive()) chromeRefresh.get()?.invoke() }
    val route = AtomicReference<DesktopOriginalRootRouteAssembly?>()''','current Root chrome authority')
edit(root,'        if (!closed.compareAndSet(false, true)) return@withContext\n        route.getAndSet(null)?.close()', '        if (!closed.compareAndSet(false, true)) return@withContext\n        chromeRefresh.set(null)\n        route.getAndSet(null)?.close()','clear current chrome callback on shutdown')
edit(root,'    val navPreferences = remember(services.runtime.store, handle)', '    SideEffect { handle.installCurrentRootChrome(systemWallpaperChrome::refreshCurrentRootTheme) }\n    val navPreferences = remember(services.runtime.store, handle)','bind existing same Window chrome')
shell='desktop/src/main/kotlin/com/bilipai/desktop/DesktopShell.kt'
prepared=json.loads(read(HERE/'system-media49-prepared/edits.json'))
for i,row in enumerate(prepared['edits']):
 after=row['after']
 if i==1: after='    }, onPrevious = { pipPrevious.get().invoke() }, onNext = { pipNext.get().invoke() },'
 edit(shell,row['before'],after,'prepared media priority '+str(i+1))
edit(shell,'    val pipSeek = remember(player, playback)', '''    val pipPrevious = remember(playback) { java.util.concurrent.atomic.AtomicReference<() -> Unit>({ playback.previous() }) }
    val pipNext = remember(playback) { java.util.concurrent.atomic.AtomicReference<() -> Unit>({ playback.next() }) }
    val pipSeek = remember(player, playback)''','existing PiP callback lexical lifetime')
edit(shell,'        pipSeek.set { seconds -> if (retainedMedia.current != null) player?.seekTo(seconds) else playback.seekTo(seconds) }', '''        pipPrevious.set { val owner = retainedMedia.current; if (owner != null) owner.previous?.invoke() else playback.previous() }
        pipNext.set { val owner = retainedMedia.current; if (owner != null) owner.next?.invoke() else playback.next() }
        pipSeek.set { seconds ->
            val owner = retainedMedia.current
            if (owner != null) { if (owner.ownsNativeSource) player?.seekTo(seconds) } else playback.seekTo(seconds)
        }''','same retained queue and accepted source commands')
old='''                                val binding = remember(owner,entryKey.taskId) { DesktopOfflineTaskPlayerBinding(downloads,retainedMedia,danmaku,
                                    owner.scope,owner.epoch,{repository.sessionEpoch},owner::owns,owner::commit,
                                    ::desktopDownloadNetworkAvailable,{playerError}) }
                                DesktopOfflineTaskPlayerHost(entryKey.taskId,binding,{commands.back()},
                                    {task->if(task.episodeId>0) commands.push(BiliPaiNavKey.BangumiPlayer(task.seasonId,task.episodeId,
                                        task.item.lastPlaybackPositionMs.coerceAtLeast(0L),task.isCourse))
                                    else commands.video(BiliPaiNavKey.VideoDetail(task.item.bvid,task.item.cid,task.item.cover,
                                        resumePositionMs=task.item.lastPlaybackPositionMs.coerceAtLeast(0L),sourceRoute="offline_video"))},
                                    onToggleFullscreen,playerContent)'''
new='''                                val offlineEntryScope = rememberCoroutineScope()
                                val binding = remember(owner,entryKey.taskId,offlineEntryScope) { DesktopOfflineTaskPlayerBinding(downloads,retainedMedia,danmaku,
                                    offlineEntryScope,owner.epoch,{repository.sessionEpoch},{owner.owns() && offlineEntryScope.isActive},owner::commit,
                                    ::desktopDownloadNetworkAvailable,{playerError}) }
                                DesktopOriginalOfflineRootHost(entryKey.taskId,binding,offlineEntryScope,globalPluginContext,
                                    originalDanmakuPreferences,danmakuPresentation,systemMedia,pip,pipActive,hostWindow,
                                    isFullscreen,setFullscreen,{homeRootRef.get()?.refreshCurrentRootChrome()},
                                    {commands.back()},{error=it})'''
edit(shell,old,new,'mount full original Offline renderer on actual typed leaf')
edit(shell,'            val state = (if (audioTarget) audioPlayer else player)?.state?.value\n            if (state != null) systemMedia?.update(WindowsMediaSnapshot(', '''            val retainedOwner = retainedMedia.current
            val offlinePayload = if (!audioTarget && retainedOwner === retainedMedia.offline && retainedOwner.ownsNativeSource)
                downloads.tasks.value.firstOrNull { it.id == retainedMedia.offline.current }?.let {
                    com.android.purebilibili.feature.download.resolveOfflineMiniPlayerPayload(it.item)
                } else null
            val state = (if (audioTarget) audioPlayer else player)?.state?.value
            if (state != null) systemMedia?.update(WindowsMediaSnapshot(''','same original Offline metadata in existing SMTC loop')
edit(shell,'title = if (audioTarget) audio!!.current!!.title else if (retainedMedia.current != null) retainedMedia.title else current.details?.title ?: state.sourceTitle,','title = if (audioTarget) audio!!.current!!.title else offlinePayload?.title ?: if (retainedOwner != null) retainedMedia.title else current.details?.title ?: state.sourceTitle,','offline title')
edit(shell,'artist = if (audioTarget) audio!!.current!!.owner else if (retainedMedia.current != null) "" else current.details?.author.orEmpty(),','artist = if (audioTarget) audio!!.current!!.owner else offlinePayload?.owner ?: if (retainedOwner != null) "" else current.details?.author.orEmpty(),','offline creator')
edit(shell,'mediaId = if (audioTarget) audio!!.current!!.bvid else if (retainedMedia.current != null) state.sourceTitle else current.details?.bvid ?: state.sourceTitle,','mediaId = if (audioTarget) audio!!.current!!.bvid else offlinePayload?.bvid ?: if (retainedOwner != null) state.sourceTitle else current.details?.bvid ?: state.sourceTitle,','offline BVID')
OUT.mkdir()
for path,data in pending.items():
 baseline=wide(OUT/'baseline'/path); baseline.parent.mkdir(parents=True,exist_ok=True); baseline.write_bytes(read(REPO/path))
 wide(REPO/path).write_text(data,encoding='utf-8',newline='\n')
wide(OUT/'hunks.json').write_text(json.dumps(hunks,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
report=dict(applied=True,hunks=len(hunks),sourceCount=956,fullOriginalOfflineRootMounted=True,newNativeActors=0,newStores=0,newRuntimeDependencies=0,wholeCompilationPending=True,nativeAcceptancePending=True,files=[dict(path=p,sha256Lf=sha(d.encode())) for p,d in pending.items()])
wide(OUT/'installed.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8'); print(json.dumps(report))
