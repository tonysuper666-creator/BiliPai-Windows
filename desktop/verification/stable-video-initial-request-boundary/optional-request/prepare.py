from pathlib import Path
import hashlib,json,os
P=Path(__file__).resolve().parent
C=P.parents[3]/'BiliPai-v023'
target='desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalVideoRootTransport.kt'
def sha(s):return hashlib.sha256(s.encode('utf8')).hexdigest()
def write(p,s):
    p.parent.mkdir(parents=True,exist_ok=True);p.write_text(s,encoding='utf8',newline='\n')
def dump(p,v):write(p,json.dumps(v,ensure_ascii=False,indent=2)+'\n')
whole=(C/target).read_text(encoding='utf8').replace('\r\n','\n')
pairs=[('''        val request = checkNotNull(state.currentRequest) { "Original request is required for a media operation" }
        val token = state.currentLoadRequestToken''','''        // Original settings/background invocations may precede the first load.
        // Keep their captured Binding; require a subject only for media publication.
        val capturedRequest = state.currentRequest
        val token = state.currentLoadRequestToken'''),('''                raw.binding.assertCurrent()
                val resolved = captureDesktopOriginalResolvedMediaRequest(assembly.captureLoadState(), request, token)''','''                raw.binding.assertCurrent()
                val request = checkNotNull(capturedRequest) { "Original request is required for a media operation" }
                val resolved = captureDesktopOriginalResolvedMediaRequest(assembly.captureLoadState(), request, token)''')]
after=whole;hunks=[]
for b,a in pairs:
    assert after.count(b)==1
    baseline=after;after=after.replace(b,a,1)
    hunks.append(dict(target=target,before=b,after=a,count=1,beforeAnchorSHA256LF=sha(b),afterAnchorSHA256LF=sha(a),
        baselineWholeSHA256LF=sha(baseline),afterWholeSHA256LF=sha(after)))
reverse=after
for b,a in reversed(pairs):reverse=reverse.replace(a,b,1)
assert reverse==whole
assert after.count('checkNotNull(stateAtCapture.currentRequest)')==2
assert after.count('captureDesktopOriginalResolvedMediaRequest(assembly.captureLoadState(), request, token)')==2
assert 'isRetainedCurrent = native::isCurrent' in after
write(P/'prepared/after'/target,after)
dump(P/'exact-hunks.json',hunks)
dump(P/'source-check.json',dict(passed=True,exactInverse=True,originalRequestAndTokenRemainCaptured=True,
    preparedSourceAndTrackStillRequireCapturedSubject=True,publicationStillChecksResolvedCIDTokenTwice=True,
    sameNativeSameCacheSameCallerJob=True,actual82Failure='Real original background settings launch captured no request and eager media construction threw before the effect ran',
    boundaries='Static necessary delta only; Root builds a fresh immutable product and fixture-alone guest attempt must run again. Real VM Error network label is not independently classified.'))
dump(P/'install-contract.json',dict(copyWhitelist=[],exactHunks='exact-hunks.json',hunks=2,
    baseline='Actual82 installed RootTransport. Frozen313 remains unchanged.',beforeWholeSHA256LF=sha(whole),afterWholeSHA256LF=sha(after),
    preserves='No default subject/request/token. Optional only before media operations. Same captured API/receipt/caller. Source/track preparation and publication fail closed without actual request.'))
write(P/'README.md','''Two exact anchors in one RootTransport family. Actual82 guest media attempt01 exposed eager subject validation when the original initWithContext's settings Flow launches have no currentRequest. A readonly/background invocation may capture its actual repository Binding without preparing or publishing media. Require the immutable captured request only in publication; existing source/track preparation assertions remain unchanged. No latest request lookup, fake source, fallback subject, API proxy, native reload or Store is added.\n\nThe original media01 failure and original frozen313 remain immutable. VM subsequently showed its generic network failure; this source delta does not assert that the network path has succeeded. Root must install/build fresh product before the actual zero-override retry.\n''')
artifacts=[]
for f in sorted(P.rglob('*')):
    if f.is_file() and f.name!='frozen-handoff.json':artifacts.append(dict(path=f.relative_to(P).as_posix(),bytes=f.stat().st_size,sha256Bytes=hashlib.sha256(f.read_bytes()).hexdigest()))
dump(P/'frozen-handoff.json',dict(status='source-only necessary optional-request boundary delta',copyWhitelist=[],exactHunks='exact-hunks.json',
    beforeWholeSHA256LF=sha(whole),afterWholeSHA256LF=sha(after),artifacts=artifacts,actualProductAcceptance=False))
print(json.dumps(dict(manifestSHA256=hashlib.sha256((P/'frozen-handoff.json').read_bytes()).hexdigest(),beforeWhole=sha(whole),afterWhole=sha(after)),indent=2))
