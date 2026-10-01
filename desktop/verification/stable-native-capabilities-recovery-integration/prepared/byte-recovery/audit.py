from pathlib import Path
import hashlib,json
H=Path(__file__).resolve().parent;MAIN=H.parents[2];CAND=MAIN.with_name('BiliPai-v023')
def wide(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92)
 return Path(s if s.startswith(prefix)else prefix+s)
def read(p):return wide(p).read_bytes()
def text(p):return read(p).decode().replace('\r\n','\n')
def sha(b):return hashlib.sha256(b).hexdigest()
def row(p):b=read(p);return dict(path=str(p),sha256Bytes=sha(b),size=len(b))
def write(p,b):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes(b.encode()if isinstance(b,str)else b)
def dump(p,v):write(p,json.dumps(v,ensure_ascii=False,indent=2)+'\n')
families=json.loads(read(H/'baseline-families.json'));hunks=json.loads(read(H/'exact-hunks.json'));checks=[]
def verify(label,v):assert v,label;checks.append(dict(label=label,PASS=True))
for f in families:
 source=text(H/'prepared/existing'/f['path']);baseline=text(H/'baseline'/f['path'])
 verify(f['path']+' inverse exact before-after',sha(source.encode())==f['candidateLfSha256'])
 for h in reversed([h for h in hunks if h['path']==f['path']]):
  verify(f['path']+' unique inverse '+str(h['index']),source.count(h['after'])==1)
  source=source.replace(h['after'],h['before'],1)
 verify(f['path']+' reconstructed baseline LF',source==baseline)
 verify(f['path']+' Candidate source unmodified',read(CAND/f['path'])==read(H/'baseline'/f['path']))
cache=text(H/'prepared/existing/desktop/src/main/kotlin/com/bilipai/desktop/player/cache/DesktopMediaByteCache.kt')
owner=text(H/'prepared/existing/desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalVideoNativeOwner.kt')
oldOwner=text(H/'baseline/desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalVideoNativeOwner.kt')
bridge=text(H/'prepared/existing/desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalVideoOwnerPluginBridge.kt')
failure=text(H/'prepared/manual/desktop/src/main/kotlin/com/bilipai/desktop/player/cache/DesktopNativeByteFailure.kt')
start='    fun recoverDirectAfterCacheError(';end='    /** Called by the existing full-owner position observer'
old=oldOwner[oldOwner.index(start):oldOwner.index(end)]
new=owner[owner.index(start):owner.index('    /** The real native loopback')]
verify('NativeFailure helper byte-for-byte unchanged',old==new)
for marker in ['fun publish(','fun adopt(','fun acceptedMedia(']:
 # All edits are after those original members; preserve whole shared prefix.
 verify(marker+' original prefix preserved',owner[:owner.index('    /** Cache-error fallback')]==oldOwner[:oldOwner.index('    /** Cache-error fallback')])
verify('capacity one AtomicReference event, no queue/SharedFlow', 'AtomicReference<DesktopNativeByteFailure?>()'in cache and 'MutableSharedFlow'not in cache)
verify('same owner CallerJob active checked before event admission','!callerJob.isActive || !matchesNativeStamp(stamp)'in cache)
verify('same CallerJob/frame checked inside existing Store-entry commit','value.admission.commit {\n                if (!callerJob.isActive || !matchesNativeStamp(stamp))'in cache)
verify('exact stamp frame/version/publication/receipt','stamp.frameIdentity === value'in cache and'value.publication === stamp.publication'in cache and'value.admission.receipt == stamp.receipt'in cache)
verify('native metadata onlyIOException','if (failure is IOException) lease.reportNativeReadFailure(nativeReadStamp, job, DesktopNativeByteFailureStage.METADATA)'in cache)
verify('cancellation caught before metadata IOException branch','catch (_: CancellationException) { job.cancel(); return fail(Response.Status.GONE) }'in cache)
verify('native body onlyIOException','catch (failure: IOException)'in cache and'if (!done.get()) reportNativeReadFailure(nativeReadStamp, requestJob, DesktopNativeByteFailureStage.BODY_READ)'in cache)
origin=cache[cache.index('    private suspend fun <T> origin('):cache.index('    // Only the real loopback')]
verify('socket cancellation converted to actual caller/lease cancellation','ioJob.ensureActive(); leaseJob.ensureActive(); check(); throw failure'in origin)
prefetch=cache[cache.index('internal class DesktopBoundMediaByteCache'):cache.index('    fun prepareNativeTransport(')]
verify('prefetch and probe cannot publish native error','reportNativeReadFailure'not in prefetch and'reportNativeReadFailure'not in origin)
verify('all event publication sites are native metadata/body only',cache.count('reportNativeReadFailure(')==3)
verify('retire clears bounded event','closed.set(true);nativeFailure.set(null);cache.unregister(this)'in cache)
verify('same actor and same Loopback','private inner class Loopback : NanoHTTPD("127.0.0.1", 0)'in cache and cache.count('private inner class Loopback')==1)
verify('event contains no Throwable/URL/headers field',all(x not in failure for x in ['val url','val headers','val cookie','val cause','Throwable?']))
newRecovery=owner[owner.index('    internal fun recoverDirectAfterByteFailure'):owner.index('    /** Called by the existing full-owner')]
verify('Store-entry-native admission order',newRecovery.index('publication.admit(')<newRecovery.index('withEntryAdmission {')<newRecovery.index('player.admitSourceSnapshot(')<newRecovery.index('player.recoverSource('))
verify('actual Reader position and desired pause read inside native lock','val readback = player.state.value'in newRecovery and'val position = readback.positionSeconds'in newRecovery and'val paused = readback.paused'in newRecovery)
verify('no guessed position/autoplay from nullable nativePaused','readback.nativePaused'not in newRecovery and'position.isFinite()'in newRecovery)
verify('native failure attempt not fabricated','expectedFailureAttemptId'not in newRecovery and'PlayerFailure('not in newRecovery)
verify('direct source no cache retry', 'copy(nativeTransport = null,'in newRecovery and'.bind('not in newRecovery)
verify('real immutable snapshot and same version','player.currentSourceSnapshot()'in newRecovery and'it.sourceVersion == expected.sourceVersion'in newRecovery)
verify('existing Bridge real consumer before mute observer',bridge.index('native.observeByteCacheFailure()')<bridge.index('native.observeInheritedPluginMute()'))
vm=CAND/'desktop/build/generated/original-video-full-owner/com/android/purebilibili/feature/video/viewmodel/VideoPlaybackViewModel.kt'
plan=CAND/'desktop/build/generated/upstream/app/src/main/java/com/android/purebilibili/feature/video/playback/policy/PlaybackPostLoadPlan.kt'
v=text(vm);p=text(plan)
selected=[]
for label,a,b in [('synchronous prepare then Success',2945,3047),('postLoad scheduling',3098,3110),('deferred plan dispatch',7338,7360),('observer before plugin/play tests',7360,7390)]:
 lines=v.splitlines(keepends=True);piece=''.join(lines[a-1:b]);dest=H/'source-chain'/('VideoPlaybackViewModel-'+label.replace(' ','-')+'.kt.txt');write(dest,piece);selected.append(dict(label=label,lineStart=a,lineEnd=b,source=row(vm),retained=row(dest)))
write(H/'source-chain/PlaybackPostLoadPlan.kt.txt',p)
verify('original postLoad plugin observer starts 1200ms without first-frame wait','task = PlaybackPostLoadTask.START_PLUGIN_CHECK,\n            delayMs = 1_200L'in p and'scheduleDeferredPostLoadWork('in v[v.index('val readyState = VideoPlaybackUiState.Success'):v.index('private fun scheduleDeferredPostLoadWork')])
loop=v[v.index('private fun startPluginCheck()'):]
verify('observer precedes null dispatch, empty plugins and isPlaying checks',loop.index('environment.plugins.observeInheritedPluginMute()')<loop.index('if (expected == null)')<loop.index('isPlaying = exoPlayer?.isPlaying'))
dump(H/'source-chain.json',dict(snapshot=73,sourceSelections=selected,plan=row(plan),retainedPlan=row(H/'source-chain/PlaybackPostLoadPlan.kt.txt'),firstStartOriginalDelayMs=1200,needsFirstNativeFrame=False,loadingPausedBufferingConsumer=True,strictLatencyGuarantee=False,SponsorAwaitCanDelayLaterCycles=True,wholeVMRuntimeAccepted=False))
dump(H/'source-audit.json',dict(checks=checks,count=len(checks),nativeFailureHelperLfSha256=sha(old.encode()),originalRegistryRowsAdded=0,CandidateWritten=False))
print(json.dumps(dict(sourceChecks=len(checks),inverseFamilies=len(families),NativeFailureHelperUnchanged=True)))
