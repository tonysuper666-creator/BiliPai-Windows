from pathlib import Path
import hashlib,json,os,ast,importlib.util,sys
sys.dont_write_bytecode=True
P=Path(__file__).resolve().parent;M=P.parents[2];C=M.parent/'BiliPai-v023';B=P.parent/'stable-original-story-pager-root-parity'
def wide(p):
 s=str(p);return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+os.path.abspath(s))
def read(p):return wide(p).read_text(encoding='utf8').replace('\r\n','\n')
def sha(t):return hashlib.sha256(t.encode()).hexdigest()
def write(p,t):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_text(t,encoding='utf8',newline='\n')
H=[];T=[]
binding='desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalPortraitPlatformBinding.kt'
t=read(B/'prepared/existing'/binding)
b='        val expectedToken = assembly.playback.beginDesktopPortraitLoad(PlaybackRequest.create(bvid, aid, cid))\n        val captured = capturePlaybackRequest()'
a='        val expectedToken = assembly.playback.beginDesktopPortraitLoad(PlaybackRequest.create(bvid, aid, cid))\n        // Preserve original progress flush before stopping; capture only AFTER\n        // the real synchronous version retirement / same-actor Stop enqueue.\n        clearCapturedPagePlayback(expectedToken, checkNotNull(currentCoroutineContext()[Job]))\n        val captured = capturePlaybackRequest()'
assert t.count(b)==1
H.append(dict(path=binding,name='capture-baseline-after-actual-stop',before=b,after=a,beforeSha256LF=sha(b),afterSha256LF=sha(a)))
T.append(dict(path=binding,beforeSha256LF=sha(t),afterSha256LF=sha(t.replace(b,a))))
after=t.replace(b,a)
b2='    override suspend fun capturePlaybackRequest(): DesktopOriginalVideoRepositoryBinding {'
a2='''    /** Same caller/token final admission BEFORE reading/stopping the current
     * accepted source. A cancelled or replaced A request cannot stop B. No actor
     * wait, HTTP or IO occurs under this existing Store -> entry -> native gate. */
    internal fun clearCapturedPagePlayback(expectedToken: Long, callerJob: Job) {
        var applied = false
        if (!assembly.environment.commit {
            assertOwned()
            if (callerJob.isCancelled || assembly.captureLoadState().currentLoadRequestToken != expectedToken)
                throw CancellationException("Portrait stop request/caller replaced")
            clearPlaybackForReplacement(assembly.section)
            applied = true
        } || !applied) throw CancellationException("Portrait stop entry retired")
    }

    override suspend fun capturePlaybackRequest(): DesktopOriginalVideoRepositoryBinding {'''
assert after.count(b2)==1
H.append(dict(path=binding,name='guard-stop-before-source-read',before=b2,after=a2,beforeSha256LF=sha(b2),afterSha256LF=sha(a2)))
after=after.replace(b2,a2);T[-1]['afterSha256LF']=sha(after)
write(P/'prepared/existing'/binding,after)
producer='desktop/tools/extract-upstream-video-fullscreen-pager.py'
# The Story89 producer owns the exact generated Pager edits; update only its
# already added body substitution, never the original source or frozen packet.
t=read(B/'prepared/existing'/producer)
b=repr('                val playbackRequest = platform.capturePageRequest(bvid, aid, requestedCid)\n                platform.clearPlaybackForReplacement(exoPlayer)')
a=repr('                val playbackRequest = platform.capturePageRequest(bvid, aid, requestedCid)')
assert t.count(b)==1
H.append(dict(path=producer,name='no-postcapture-second-stop',before=b,after=a,beforeSha256LF=sha(b),afterSha256LF=sha(a)))
T.append(dict(path=producer,beforeSha256LF=sha(t),afterSha256LF=sha(t.replace(b,a))))
write(P/'prepared/existing'/producer,t.replace(b,a))
# Replay the sole producer with the original canonical recipe/runtime imports.
for sibling in ('extract-upstream-video-fullscreen-pager.py',):
 spec=importlib.util.spec_from_file_location('delta_pager',wide(P/'prepared/existing/desktop/tools'/sibling));mod=importlib.util.module_from_spec(spec);spec.loader.exec_module(mod)
 mod.generate(C,wide(P/'replay'))
out=wide(P/'replay');prior=wide(B/'producer-replay/pager')
changed=[]
for f in out.rglob('*.kt'):
 rel=str(f.relative_to(out)).replace('\\','/')
 if read(f)!=read(prior/rel):
  changed.append(rel)
  expected=read(prior/rel).replace('                platform.clearPlaybackForReplacement(exoPlayer)\n','')
  assert read(f)==expected,rel
assert changed==['com/android/purebilibili/feature/video/ui/pager/PortraitVideoPager.kt'],changed
write(P/'exact-hunks.json',json.dumps(H,ensure_ascii=False,indent=2)+'\n')
write(P/'targets.json',json.dumps(T,indent=2)+'\n')
write(P/'replay.json',json.dumps(dict(passed=True,changedOutputs=changed,otherOutputsByteEqual=True,generatedPagerOnlyRemovedPostcaptureStop=True),indent=2)+'\n')
print('Prepared 2 exact hunks; original progress flush -> actual synchronous stop -> captured baseline -> raw HTTP -> same actor Load')
