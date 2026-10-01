from pathlib import Path
import hashlib,json,subprocess
HERE=Path(__file__).resolve().parent
REPO=HERE.parents[2].parent/'BiliPai-v023'
def sha(data): return hashlib.sha256(data).hexdigest()
def load(path): return (REPO/path).read_bytes().replace(b'\r\n',b'\n').decode('utf-8')
rows=[]
def save(path,original,candidate):
    target=HERE/'prepared'/path; target.parent.mkdir(parents=True,exist_ok=True)
    target.write_bytes(candidate.encode('utf-8'))
    rows.append(dict(path=path,baseSha256LF=sha(original.encode()),candidateSha256LF=sha(candidate.encode())))
def replace(text,old,new):
    assert text.count(old)==1,old
    return text.replace(old,new,1)
path='desktop/upstream-sources.json'; original=load(path); manifest=json.loads(original)
quality='app/src/main/java/com/android/purebilibili/feature/download/DownloadQualityDialog.kt'
assert not any(r['path']==quality for r in manifest['sources'])
source=load(quality).encode()
git=subprocess.check_output(['git','show',manifest['upstreamCommit']+':'+quality],cwd=REPO).replace(b'\r\n',b'\n')
assert source==git
manifest['sources'].append(dict(path=quality,sha256=sha(source),features=['downloads'],mode='direct'))
save(path,original,json.dumps(manifest,indent=2)+'\n')
path='desktop/src/main/kotlin/com/bilipai/desktop/ui/DownloadScreens.kt'; original=load(path); text=original
text=replace(text,'import com.android.purebilibili.feature.download.shouldContinueAllInclude\n',
    'import com.android.purebilibili.feature.download.shouldContinueAllInclude\nimport com.android.purebilibili.feature.download.resolveDownloadTaskClickTarget\nimport com.android.purebilibili.feature.download.DownloadTaskClickTarget\n')
text=replace(text,'    sharedDanmaku: DanmakuOverlay? = null, retained: DesktopRetainedMedia? = null,\n',
    '    sharedDanmaku: DanmakuOverlay? = null, retained: DesktopRetainedMedia? = null,\n    onOnlinePlay: ((DownloadTask) -> Unit)? = null,\n')
text=replace(text,'    fun play(task: DownloadTask) {\n        retained?.acquire(memory)',
'''    fun play(task: DownloadTask) {
        // Original routing policy; Windows interface state needs no HTTP probe.
        when (resolveDownloadTaskClickTarget(task.item, desktopDownloadNetworkAvailable())) {
            DownloadTaskClickTarget.OnlinePlayer -> {
                if (onOnlinePlay != null) onOnlinePlay(task)
                else error = "缓存文件已失效，请从视频页重新播放"
                return
            }
            null -> { error = "缓存文件不可用，连接网络后可回退在线播放"; return }
            DownloadTaskClickTarget.OfflinePlayer -> Unit
        }
        retained?.acquire(memory)''')
text=replace(text,'{ Text("播放本地文件") }','{ Text("播放") }')
text+='''
/** Windows link availability only; the existing online player reports server failures. */
private fun desktopDownloadNetworkAvailable(): Boolean = runCatching {
    java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces()).any { network ->
        network.isUp && !network.isLoopback && java.util.Collections.list(network.inetAddresses).any { !it.isLoopbackAddress }
    }
}.getOrDefault(false)
'''
save(path,original,text)
path='desktop/src/main/kotlin/com/bilipai/desktop/DesktopShell.kt'; original=load(path); text=original
text=replace(text,'                                onDownload = { scope.launch {\n',
'''                                onDownload = { requestedQuality, options ->
                                    val expectedEpoch = repository.sessionEpoch
                                    scope.launch {
''')
text=replace(text,'val source = repository.playback(info, playing.currentPart, playing.quality)\n                                        downloads.enqueue',
'''val source = repository.playback(info, playing.currentPart, requestedQuality)
                                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                        if (repository.sessionEpoch != expectedEpoch) throw CancellationException("下载账号已切换")
                                        downloads.enqueue''')
text=replace(text,'durationSeconds = part.duration.toInt(), quality = source.quality, episodeLabel = part.title))',
'''durationSeconds = part.duration.toInt(), quality = source.quality,
                                            qualityLabel = source.availableQualities.firstOrNull { it.id == source.quality }?.label.orEmpty(),
                                            includeDanmaku = options.includeDanmaku, episodeLabel = part.title))''')
text=replace(text,'section == DesktopSection.DOWNLOADS -> DownloadBrowserScreen(downloads, player, playerError, { mediaActive = it; if (it) listen?.pause() }, onToggleFullscreen, playerContent, danmaku, retainedMedia)',
'''section == DesktopSection.DOWNLOADS -> DownloadBrowserScreen(downloads, player, playerError,
                                { mediaActive = it; if (it) listen?.pause() }, onToggleFullscreen, playerContent, danmaku, retainedMedia,
                                onOnlinePlay = { task ->
                                    if (task.episodeId > 0) showSeason(task.seasonId, task.episodeId, task.isCourse,
                                        task.item.lastPlaybackPositionMs.coerceAtLeast(0L) / 1000.0)
                                    else if (task.item.bvid.startsWith("BV")) openVideo(VideoCard(task.item.bvid, task.title,
                                        task.item.cover, task.item.ownerName, 0L, task.item.duration,
                                        progressSeconds = (task.item.lastPlaybackPositionMs.coerceAtLeast(0L) / 1000L).toInt(),
                                        preferredCid = task.item.cid))
                                    else error = "缓存文件已失效，此任务没有可用的在线入口"
                                })''')
text=replace(text,'    engagement: @Composable () -> Unit, onDownload: () -> Unit, onCast: () -> Unit, onStory: () -> Unit,',
    '    engagement: @Composable () -> Unit, onDownload: (Int, com.android.purebilibili.feature.download.DownloadOptions) -> Unit, onCast: () -> Unit, onStory: () -> Unit,')
text=replace(text,'    val info = playing.details ?: return\n    Row(Modifier.fillMaxSize()',
'''    val info = playing.details ?: return
    var showDownloadQuality by remember(info.bvid, playing.currentPart) { mutableStateOf(false) }
    if (showDownloadQuality) com.android.purebilibili.feature.download.DownloadQualityDialog(
        title = info.title, qualityOptions = playing.availableQualities.map { it.id to it.label },
        currentQuality = playing.effectiveQuality.takeIf { it > 0 } ?: playing.quality,
        onQualitySelected = { quality, options -> showDownloadQuality = false; onDownload(quality, options) },
        onDismiss = { showDownloadQuality = false })
    Row(Modifier.fillMaxSize()''')
text=replace(text,'OutlinedButton(onClick = onDownload) { Text("下载本集") }',
    'OutlinedButton(onClick = { showDownloadQuality = true }, enabled = playing.availableQualities.isNotEmpty()) { Text("下载本集") }')
save(path,original,text)
(HERE/'installation.json').write_text(json.dumps(dict(originalCommit=manifest['upstreamCommit'],sources=rows,compiled=False,runtimeAccepted=False),indent=2)+'\n',encoding='utf-8')
print(json.dumps(dict(prepared=len(rows),directOriginalQualityDialog=True)))
