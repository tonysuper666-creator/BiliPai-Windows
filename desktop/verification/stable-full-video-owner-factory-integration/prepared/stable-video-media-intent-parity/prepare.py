from pathlib import Path
import hashlib, importlib.util, json, os, subprocess
P=Path(__file__).resolve().parent; MAIN=P.parents[2]; REPO=MAIN.parent/'BiliPai-v023'
PFX=chr(92)*2+'?'+chr(92)
def wide(p):
 s=os.path.abspath(p);return Path(s if s.startswith(PFX)else PFX+s)
def text(p):return wide(p).read_text(encoding='utf8').replace('\r\n','\n')
def sha(s):return hashlib.sha256(s.encode()).hexdigest()
def save(p,s):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_text(s,encoding='utf8',newline='\n')
def dump(p,v):save(p,json.dumps(v,ensure_ascii=False,indent=2)+'\n')
def one(s,a,b):assert s.count(a)==1,a[:60];return s.replace(a,b,1)
rows=[]
def patch(target,hunks,compile69base=None):
 base=text(REPO/target);s=base
 for a,b in hunks:s=one(s,a,b)
 save(P/'prepared/existing'/target,s)
 rows.append(dict(target=target,baseSHA256LF=sha(base),desiredSHA256LF=sha(s),hunks=[dict(before=a,after=b)for a,b in hunks]))
 if compile69base:
  b=subprocess.run(['git','-C',str(REPO),'show','9fbfe50973de4dc2b04984ef6ef6870bee617f2b:'+target],capture_output=True,check=True).stdout.decode('utf8')
  old=b
  for a,c in hunks:b=one(b,a,c)
  save(P/'compile-reference-actual69'/target,b)
  return b
 return s

t='desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalVideoPlaybackUseCaseEnvironment.kt'
a='internal interface DesktopOriginalVideoMediaPort {\n'
b=a+'''    /** Required synchronous lexical span. Native Load needs these exact original
     * arguments before enqueue; no Store/native gate or waiting is implied. */
    fun withPlaybackIntent(startPositionMs:Long,playWhenReady:Boolean,action:()->Unit)
'''
patch(t,[(a,b)])

t='desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalVideoPlaybackInvocation.kt'
a='    private fun activeMedia(): DesktopOriginalVideoMediaPort =\n        activeThreadInvocation()?.media ?: acceptedMedia().also { assertCurrent() }\n'
b='''    // Only a synchronous call span. Fixed accepted media must not be rebuilt
    // between withPlaybackIntent/prepare/accept, especially outside an invocation.
    private val lexicalMedia = ThreadLocal<DesktopOriginalVideoMediaPort?>()
    private fun activeMedia(): DesktopOriginalVideoMediaPort = lexicalMedia.get()
        ?: activeThreadInvocation()?.media ?: acceptedMedia().also { assertCurrent() }
'''
a2='    val media: DesktopOriginalVideoMediaPort = object : DesktopOriginalVideoMediaPort {\n'
b2=a2+'''        override fun withPlaybackIntent(startPositionMs:Long,playWhenReady:Boolean,action:()->Unit) {
            val captured = activeMedia()
            val previous = lexicalMedia.get()
            lexicalMedia.set(captured)
            try { captured.withPlaybackIntent(startPositionMs,playWhenReady,action) }
            finally { if (previous == null) lexicalMedia.remove() else lexicalMedia.set(previous) }
        }
'''
patch(t,[(a,b),(a2,b2)])

t='desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalVideoNativeOwner.kt'
a='''        return object : DesktopOriginalVideoMediaPort {
            override fun prepareLegacyDash'''
b='''        return object : DesktopOriginalVideoMediaPort {
            override fun withPlaybackIntent(startPositionMs:Long,playWhenReady:Boolean,action:()->Unit) {
                checkCurrent()
                delegate.withPlaybackIntent(startPositionMs,playWhenReady) {
                    checkCurrent(); action()
                }
            }
            override fun prepareLegacyDash'''
patch(t,[(a,b)],True)

t='desktop/tools/extract-upstream-video-state-core.py'
a="s=s.replace('val mediaItem = MediaItem.fromUri(url)\\n        player.setMediaItem(mediaItem)','val mediaItem = environment.media.prepareProgressive(url)\\n        environment.media.accept(mediaItem)')\n"
b=a+'''# Original Exo assigns a source before prepare. MPV enqueues Load at accept:
# capture the EXACT original play call arguments first, preserving source policy
# and every subsequent original property/seek/prepare expression and order.
swap('        val dashSegmentRequestsEnabled = resolveDashSegmentRequestsEnabled()\\n',
     '        environment.media.withPlaybackIntent(seekTo, playWhenReady) {\\n        val dashSegmentRequestsEnabled = resolveDashSegmentRequestsEnabled()\\n',
     'Windows native initial Load original lexical intent begin DASH')
swap('        environment.media.accept(finalSource)\\n        player.playWhenReady = playWhenReady',
     '        environment.media.accept(finalSource)\\n        }\\n        player.playWhenReady = playWhenReady',
     'Windows native original lexical intent end DASH')
swap('        val mediaItem = environment.media.prepareProgressive(url)\\n        environment.media.accept(mediaItem)',
     '        environment.media.withPlaybackIntent(seekTo, playWhenReady) {\\n        val mediaItem = environment.media.prepareProgressive(url)\\n        environment.media.accept(mediaItem)\\n        }',
     'Windows native initial Load original lexical intent progressive')
'''
s=patch(t,[(a,b)])
dump(P/'exact-hunks.json',rows)
spec=importlib.util.spec_from_file_location('p',P/'prepared/existing'/t);p=importlib.util.module_from_spec(spec);spec.loader.exec_module(p)
out=P/'generated-replay';outputs=p.generate(REPO,out,False)
before=text(REPO/'desktop/build/generated/original-video-state-core/com/android/purebilibili/feature/video/usecase/DesktopOriginalVideoPlaybackUseCase.kt')
after=text(out/'com/android/purebilibili/feature/video/usecase/DesktopOriginalVideoPlaybackUseCase.kt')
reverse=after
for a,b in [("        environment.media.withPlaybackIntent(seekTo, playWhenReady) {\n        val dashSegmentRequestsEnabled = resolveDashSegmentRequestsEnabled()\n","        val dashSegmentRequestsEnabled = resolveDashSegmentRequestsEnabled()\n"),
 ("        environment.media.accept(finalSource)\n        }\n        player.playWhenReady = playWhenReady","        environment.media.accept(finalSource)\n        player.playWhenReady = playWhenReady"),
 ("        environment.media.withPlaybackIntent(seekTo, playWhenReady) {\n        val mediaItem = environment.media.prepareProgressive(url)\n        environment.media.accept(mediaItem)\n        }","        val mediaItem = environment.media.prepareProgressive(url)\n        environment.media.accept(mediaItem)")]:reverse=one(reverse,a,b)
assert reverse==before,'unrelated original Core business changed'
dump(P/'source-replay-audit.json',dict(originalStableCommit=p.COMMIT,originalCoreSHA256LF=sha(before),candidateCoreSHA256LF=sha(after),
 inverseThreeLexicalWrappersExactlyMatchesActual69Core=True,allOutputs=outputs,
 directEmission='production skips DIRECT; sole Sync unchanged',nativeRuntimeAccepted=False))
print('Prepared lexical intent exact4families + one manual; reverse original Core exact.')
