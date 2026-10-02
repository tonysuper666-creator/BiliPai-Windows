from pathlib import Path
import hashlib,json,os,subprocess,sys
sys.dont_write_bytecode=True;sys.stdout.reconfigure(encoding='utf8')
P=Path(__file__).resolve().parent;C=P.parents[2].parent/'BiliPai-v023';BASE='cd8317d54fff02c2d920e295397aef502333f69b';UP='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
def wide(p):
 s=os.path.abspath(str(p));return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
def sha(s):return hashlib.sha256(s.encode()).hexdigest()
def write(p,t):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_text(t,encoding='utf8',newline='\n')
def get(ref,path):return subprocess.check_output(['git','-C',str(C),'show',ref+':'+path]).decode('utf8').replace('\r\n','\n')
path='desktop/tools/extract-upstream-bangumi-player.py';old=get(BASE,path)
anchor="  body=body.replace(before,before+'                    cachedPlayData = playData,\\n')\n"
after=anchor+'''  before='    override fun close() {\\n        flushBangumiPlaybackHeartbeat()\\n'
  assert body.count(before)==1
  body=body.replace(before,'    override fun close() {\\n        if (environment.ownsPlaybackSource()) flushBangumiPlaybackHeartbeat()\\n')
  before='        exoPlayer?.removeListener(playbackEndListener)\\n'
  assert body.count(before)==1
  body=body.replace(before,'        environment.player.removeListener(playbackEndListener)\\n')
  before='        val added = environment.addDownloadTask(task)\\n'
  assert body.count(before)==1
  body=body.replace(before,'        val added = try { environment.addDownloadTask(task) } catch (cancelled: kotlinx.coroutines.CancellationException) {\\n            throw cancelled\\n        } catch (failure: Exception) {\\n            environment.launch { _toastEvent.send(failure.message ?: "当前集暂时无法下载") }\\n            return\\n        }\\n')
'''
assert old.count(anchor)==1;new=old.replace(anchor,after);write(P/'prepared'/path,new)
write(P/'lifecycle-exact-hunk.json',json.dumps(dict(base=BASE,path=path,before=anchor,after=after,beforeCount=1,baseSha256LF=sha(old),preparedSha256LF=sha(new),onlyAddedBoundary='Retirement closes the borrowed PGC callback registration even when native/source admission is gone; no MPV release. Periodic/paused/ended heartbeat business remains original; final retirement heartbeat still pending.'),ensure_ascii=False,indent=2)+'\n')
# Stateless original raw XML/deflate protocol. Screen already consumes the sole
# existing Overlay document, so the original global CID cache is not duplicated.
rel='app/src/main/java/com/android/purebilibili/data/repository/DanmakuRepository.kt';raw=get(UP,rel)
start=raw.index('            val result: ByteArray?\n',raw.index('suspend fun getDanmakuRawData'));end=raw.index('            // 存入缓存（限制条目数与字节数）',start)
decode=raw[start:end]
decode=decode.replace('                    while (!inflater.finished()) {','                    while (!inflater.finished()) {\n                        kotlinx.coroutines.currentCoroutineContext().ensureActive()')
decode=decode.replace('                    outputStream.write(tempBuffer, 0, count)','                    require(outputStream.size() + count <= com.bilipai.desktop.danmaku.DanmakuParser.MAX_DOCUMENT_BYTES) { "Danmaku document too large" }\n                    outputStream.write(tempBuffer, 0, count)')
text='''package com.bilipai.desktop.ui
import kotlinx.coroutines.*
/** Original raw XML/deflate transform over the SAME actual invocation API.
 * The raw Overlay/cache authority remains existing; no new client/CID cache. */
internal suspend fun desktopOriginalBangumiRawDanmaku(binding: DesktopOriginalVideoRepositoryBinding,
    cid: Long, assertPresenter: () -> Unit): ByteArray? = withContext(Dispatchers.IO) {
    ensureActive(); binding.assertCurrent(); assertPresenter()
    try {
        val bytes = binding.primaryApi.getDanmakuXml(cid).use { body ->
            require(body.contentLength() <= com.bilipai.desktop.danmaku.DanmakuParser.MAX_DOCUMENT_BYTES)
            body.byteStream().use { it.readNBytes(com.bilipai.desktop.danmaku.DanmakuParser.MAX_DOCUMENT_BYTES + 1) }
                .also { require(it.size <= com.bilipai.desktop.danmaku.DanmakuParser.MAX_DOCUMENT_BYTES) }
        }
        ensureActive(); binding.assertCurrent(); assertPresenter()
        if (bytes.isEmpty()) return@withContext null
'''+decode+'''
        ensureActive(); binding.assertCurrent(); assertPresenter()
        result
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) {
        ensureActive(); binding.assertCurrent(); assertPresenter()
        android.util.Log.e("DanmakuRepo", "getDanmakuRawData failed: ${failure.message}")
        null
    }
}
'''
write(P/'prepared/desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalBangumiRawDanmakuView.kt',text)
write(P/'raw-danmaku-boundary.json',json.dumps(dict(upstreamCommit=UP,originalPath=rel,originalSha256LF=sha(raw),originalDecodeBody=raw[start:end],adaptedDecodeBody=decode,platformBoundaries=['Same captured invocation primary API and real caller guards before/after IO','No duplicate original global CID cache: Screen/Overlay own sole document cache','Actual transport/decompression bound and cancellation added; original plainXML/deflate/failure data behavior retained'],rootConsumer='BasePlayerRequests.getDanmakuRawData only, normal Screen uses sole Section Overlay'),ensure_ascii=False,indent=2)+'\n')
print('Prepared lifecycle exact hunk and original raw-danmaku protocol view')
nativePath='desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalBangumiNativePresenter.kt';native=get(BASE,nativePath)
nativeAnchor='    override fun onSharedPlaybackReplaced() {\n        if (retired.compareAndSet(false, true)) scope.cancel("Original shared playback taken over")\n    }\n'
nativeAfter='''    private var retirementCapture: (() -> Unit)? = null
    private var episodeRetirementCapture: ((BangumiDetail, BangumiEpisode) -> Unit)? = null
    fun installRetirementCapture(onRetirement: () -> Unit, beforeEpisode: (BangumiDetail, BangumiEpisode) -> Unit) {
        assertCurrent()
        check(retirementCapture == null && episodeRetirementCapture == null)
        retirementCapture = onRetirement
        episodeRetirementCapture = beforeEpisode
    }
    override fun onSharedPlaybackReplaced() {
        if (retired.compareAndSet(false, true)) {
            try { retirementCapture?.invoke() }
            finally { scope.cancel("Original shared playback taken over") }
        }
    }
'''
assert native.count(nativeAnchor)==1;preparedNative=native.replace(nativeAnchor,nativeAfter)
episodeAnchor='        portrait.captureBangumiPageRequest(this, detail, episode)\n'
episodeAfter='        episodeRetirementCapture?.invoke(detail, episode)\n'+episodeAnchor
assert preparedNative.count(episodeAnchor)==1;preparedNative=preparedNative.replace(episodeAnchor,episodeAfter)
write(P/'prepared'/nativePath,preparedNative)
write(P/'native-retirement-exact-hunks.json',json.dumps(dict(base=BASE,path=nativePath,baseSha256LF=sha(native),preparedSha256LF=sha(preparedNative),hunks=[dict(before=nativeAnchor,after=nativeAfter,count=1),dict(before=episodeAnchor,after=episodeAfter,count=1)],reason='Capture actual old accepted/native position before source replacement outside gates; queue final original type4 primary operation on existing Root scope, never old canceled PGC scope/latest-cookie retag'),ensure_ascii=False,indent=2)+'\n')
