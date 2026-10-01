from pathlib import Path
import hashlib,json,subprocess,sys,difflib,importlib.util
sys.stdout.reconfigure(encoding='utf-8',errors='replace');sys.dont_write_bytecode=True
H=Path(__file__).resolve().parent;MAIN=H.parents[2];ROOT=MAIN.parent/'BiliPai-v023'
def wide(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix)else prefix+s)
def data(p):return wide(p).read_bytes()
def text(p):return data(p).decode().replace('\r\n','\n')
def sha(t):return hashlib.sha256(t.encode()if isinstance(t,str)else t).hexdigest()
def write(p,t):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes(t.encode()if isinstance(t,str)else t)
def dump(p,v):write(p,json.dumps(v,ensure_ascii=False,indent=2)+'\n')
rows=[];changed={}
def change(path,before,after,label):
 if path not in changed:
  original=text(ROOT/path);changed[path]=original;write(H/'baseline'/path,original)
 current=changed[path];assert current.count(before)==1,(path,label,current.count(before))
 changed[path]=current.replace(before,after,1)
 rows.append(dict(path=path,label=label,before=before,after=after,beforeSha256LF=sha(before),afterSha256LF=sha(after)))

base='desktop/src/main/kotlin/com/bilipai/desktop/'
change(base+'player/cache/DesktopMediaByteProtocol.kt','    private val fingerprint: String,\n','    private val fingerprint: String,\n    /** Same captured Bound survives native publication adoption; no re-bind. */\n    internal val bound: DesktopBoundMediaByteCache,\n','carrier exact bound reference')
change(base+'player/cache/DesktopMediaByteCache.kt','    suspend fun metadata(track: DesktopMediaByteTrack): DesktopMediaResource {\n','    internal fun probeAdmission(): DesktopMediaByteAdmission { check(); return frame.get().admission }\n    @OptIn(InternalCoroutinesApi::class)\n    internal fun watchProbeCancellation(handler: (Throwable?) -> Unit): DisposableHandle =\n        leaseJob.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { if (it != null) handler(it) }\n\n    suspend fun metadata(track: DesktopMediaByteTrack): DesktopMediaResource {\n','same lease captured probe admission and immediate cancellation')
bound_methods='''    private val preparedManifestFingerprint = AtomicReference<String?>()
    private fun manifestFingerprint(value: String?): String =
        mediaDigest(if (value == null) "separate/edl" else "adaptive-mpd\\n$value")
    internal fun matchesCapturedPlan(values: List<DesktopMediaByteTrack>, fullAdaptiveManifest: String?): Boolean {
        lease.check()
        return preparedManifestFingerprint.get() == manifestFingerprint(fullAdaptiveManifest) &&
            tracks.size == values.size && tracks.zip(values).all { (a, b) ->
                a.url == b.url && a.urls == b.urls && a.cacheKey == b.cacheKey &&
                    a.representation == b.representation && a.headers == b.headers
            }
    }
    override fun close() = lease.close()
    internal fun probeCalls(callerJob: Job, stillCurrent: () -> Boolean): okhttp3.Call.Factory =
        DesktopMediaByteProbeCalls(lease, tracks, callerJob, stillCurrent)
'''
change(base+'player/cache/DesktopMediaByteCache.kt','    override fun close() = lease.close()\n',bound_methods,'same bound actual probe and exact captured plan identity')
change(base+'player/cache/DesktopMediaByteCache.kt','return DesktopNativeMediaTransport(lease,mode,video,audio,progressive,mediaSourceFingerprint(source))','check(preparedManifestFingerprint.compareAndSet(null, manifestFingerprint(fullAdaptiveManifest)))\n        return DesktopNativeMediaTransport(lease,mode,video,audio,progressive,mediaSourceFingerprint(source),this)','same bound and full manifest carried once')

method='''    /** Same raw request's exact account/Job/entry admission for native byte preparation. */
    fun captureMediaBytes(cache: com.bilipai.desktop.player.cache.DesktopMediaByteCache): DesktopOriginalVideoByteCacheRequest = read {
        val namespace = repository.capturePlaybackCachePartition(receipt, ::entryCurrent)
        DesktopOriginalVideoByteCacheRequest(cache,
            com.bilipai.desktop.player.cache.DesktopMediaByteRepositoryAdmission(repository, authorization,
                namespace, requestJob, ::entryCurrent, commitIfEntryCurrent), ::assertCurrent)
    }

'''
change(base+'ui/DesktopOriginalVideoRepositoryBinding.kt','    companion object {\n',method+'    companion object {\n','same request captured byte view')
change(base+'ui/DesktopOriginalVideoPlaybackOwnerEnvironment.kt','    val cdnRangeCache:DesktopOriginalCdnRangeCache,\n','    val cdnRangeCapture:(DesktopOriginalVideoAcceptedPublication)->DesktopOriginalCdnRangeCapture?,\n','required accepted-source capture factory; null only actual direct source')
change(base+'ui/DesktopOriginalPortraitPlatform.kt','    suspend fun prefetchRange(url: String, headers: Map<String,String>, length: Long)\n','    fun captureMediaCache(request: DesktopOriginalVideoRepositoryBinding, playData: PlayUrlData,\n        streamUrls: com.android.purebilibili.feature.video.ui.pager.PortraitPlaybackStreamUrls): DesktopOriginalPortraitByteCache\n','retain exact raw request for head consumer')

recover='''    /** Cache-error fallback is the original remote semantic source. Required failed
     * attempt and accepted identity prevent retrying a replaced/recovered native load. */
    fun recoverDirectAfterCacheError(expected: DesktopOriginalVideoAcceptedPublication,
        positionSeconds: Double, paused: Boolean, expectedFailureAttemptId: Long): Boolean {
        if (!owns(expected) || expected.nativeSource.source.nativeTransport == null ||
            expectedFailureAttemptId <= 0L || !positionSeconds.isFinite() || positionSeconds < 0.0) return false
        var recovered = false
        try {
            publication.admit(expected.nativeSource.source, { owns(expected) }) {
                withEntryAdmission {
                    if (!owns(expected) || player.state.value.failure?.attemptId != expectedFailureAttemptId) return@withEntryAdmission
                    lateinit var next: DesktopOriginalVideoAcceptedPublication
                    val direct = retainedSource(expected.nativeSource.source.copy(nativeTransport = null,
                        startPositionSeconds = positionSeconds, startPaused = paused)) { owns(next) }
                    if (!player.recoverSource(expected.sourceVersion, direct, positionSeconds, paused,
                            expectedFailureAttemptId = expectedFailureAttemptId)) return@withEntryAdmission
                    next = DesktopOriginalVideoAcceptedPublication(expected.request,
                        checkNotNull(player.currentSourceSnapshot()).also { check(it.sourceVersion == expected.sourceVersion) })
                    accepted.set(next)
                    inheritedMute.get()?.takeIf { it.lease === expected }?.let { previous ->
                        inheritedMute.compareAndSet(previous, InheritedMute(next, previous.interval))
                    }
                    onAccepted(next); recovered = true
                }
            }
        } catch (_: CancellationException) { return false }
        return recovered
    }

'''
change(base+'ui/DesktopOriginalVideoNativeOwner.kt','    /** Called by the existing full-owner position observer, with no new poller.\n',recover+'    /** Called by the existing full-owner position observer, with no new poller.\n','real one-shot guarded direct native recovery')

# Producer deltas run AFTER each unchanged original full-body/source-inverse verification.
# The exact emitted before/after snippets remain explicit, reverse-checkable data.
vm='com/android/purebilibili/feature/video/viewmodel/VideoPlaybackViewModel.kt'
cdn='com/android/purebilibili/feature/plugin/CdnDashSegmentPrefetcher.kt'
pg='com/android/purebilibili/feature/video/ui/pager/PortraitVideoPager.kt'
pol='com/android/purebilibili/feature/video/ui/pager/PortraitVideoLoadPolicy.kt'
full_edits=[
 dict(path=vm,before='        playbackCdnPrefetchJob = environment.invocations.launch {\n',after='        val capturedCache = environment.cdnRangeCapture(expected) ?: return\n        playbackCdnPrefetchJob = environment.invocations.launch {\n'),
 dict(path=vm,before='                cache = environment.cdnRangeCache,\n                client = environment.repository.playbackCalls\n',after='                cache = capturedCache,\n                client = capturedCache.calls(checkNotNull(kotlinx.coroutines.currentCoroutineContext()[Job])),\n                headers = capturedCache.headers\n'),
 dict(path=cdn,before='    private val client: okhttp3.Call.Factory\n',after='    private val client: okhttp3.Call.Factory,\n    private val headers: Map<String, String>\n'),
 dict(path=cdn,before='headers = PLAYBACK_HEADERS',after='headers = headers'),
 dict(path=cdn,before='            .header("Referer", "https://www.bilibili.com")\n',after='            .apply { headers.forEach { (name, value) -> header(name, value) } }\n',count=2),
]
old_companion='''    private companion object {
        val PLAYBACK_HEADERS = mapOf(
            "Referer" to "https://www.bilibili.com",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        )
    }
'''
full_edits.append(dict(path=cdn,before=old_companion,after=''))
portrait_edits=[
 dict(path=pg,before='val playData = platform.capturePlaybackRequest().protocol.preloadPortraitPlayUrl(',after='val byteRequest = platform.capturePlaybackRequest()\n                    val playData = byteRequest.protocol.preloadPortraitPlayUrl(',count=2),
 dict(path=pg,before='prefetchPortraitPlaybackHead(platform, streamUrls)',after='prefetchPortraitPlaybackHead(platform, streamUrls, platform.captureMediaCache(byteRequest, playData, streamUrls))',count=2),
 dict(path=pol,before='    streamUrls: PortraitPlaybackStreamUrls\n) {\n    // 头部预取',after='    streamUrls: PortraitPlaybackStreamUrls,\n    capturedCache: com.bilipai.desktop.ui.DesktopOriginalPortraitByteCache\n) {\n    // 头部预取'),
 dict(path=pol,before='    if (!context.isWifi()) return\n    coroutineScope {\n',after='    if (!context.isWifi()) { capturedCache.close(); return }\n    try { coroutineScope {\n'),
 dict(path=pol,before='context.prefetchRange(url, buildPortraitPlaybackHttpHeaders(), length)',after='capturedCache.prefetchHead(url, length)'),
 dict(path=pol,before='        }.awaitAll()\n    }\n}\n\ninternal fun buildPortraitCachedMediaSourceFactory',after='        }.awaitAll()\n    } } finally { capturedCache.close() }\n}\n\ninternal fun buildPortraitCachedMediaSourceFactory'),
]
def producer_delta(name,edits,anchor,call):
 fn='captured_media_byte_consumer_delta'
 function='\ndef '+fn+'(path, text):\n edits='+repr(edits)+'\n for edit in edits:\n  if edit["path"]!=path:continue\n  count=edit.get("count",1);assert text.count(edit["before"])==count,(path,"byte-cache consumer anchor")\n  text=text.replace(edit["before"],edit["after"],count)\n return text\n\n'
 path='desktop/tools/'+name
 change(path,'def generate(repo,output,standalone=False):\n',function+'def generate(repo,output,standalone=False):\n','strict post-original delta body')
 change(path,anchor,call,'apply source delta after original inverse proof')
producer_delta('extract-upstream-video-full-owner.py',full_edits,
 '  body=retained_handoff_platform_delta(recipe[\'output\'],body)\n',
 '  body=retained_handoff_platform_delta(recipe[\'output\'],body)\n  body=captured_media_byte_consumer_delta(recipe[\'output\'],body)\n')
producer_delta('extract-upstream-video-fullscreen-pager.py',portrait_edits,
 "  text=spec.get('prefix','')+text+spec.get('suffix','');assert digest(text)==spec['outputSHA'],spec['output']\n",
 "  text=spec.get('prefix','')+text+spec.get('suffix','');assert digest(text)==spec['outputSHA'],spec['output']\n  text=captured_media_byte_consumer_delta(spec['output'],text)\n")

for path,t in changed.items():
 write(H/'prepared/existing'/path,t)
 write(H/'diffs'/(Path(path).name+'.diff'),''.join(difflib.unified_diff(text(ROOT/path).splitlines(True),t.splitlines(True),fromfile=path,tofile=path)))
 reverse=t
 for r in reversed([r for r in rows if r['path']==path]):
  assert reverse.count(r['after'])==1,(path,r['label']);reverse=reverse.replace(r['after'],r['before'],1)
 assert reverse==text(H/'baseline'/path)
dump(H/'exact-hunks.json',dict(schema=1,baselineCommit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),hunks=rows))
dump(H/'consumer-source-deltas.json',dict(fullOwner=full_edits,portrait=portrait_edits))
def module(name,path):
 spec=importlib.util.spec_from_file_location(name,path);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
full=module('cache_owner_producer',H/'prepared/existing/desktop/tools/extract-upstream-video-full-owner.py')
full_rows=full.generate(ROOT,H/'generated/full-owner')
portrait=module('cache_portrait_producer',H/'prepared/existing/desktop/tools/extract-upstream-video-fullscreen-pager.py')
portrait_rows=portrait.generate(ROOT,H/'generated/portrait')
dump(H/'generated-source-inventory.json',dict(fullOwner=full_rows,portrait=portrait_rows))
print('Prepared',len(rows),'hunks',len(changed),'existing files; all exact reverse checks PASS.')
