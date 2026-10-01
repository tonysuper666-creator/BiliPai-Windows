from pathlib import Path
import hashlib,json,zipfile,urllib.parse
P=Path(__file__).resolve().parent;MAIN=P.parents[2];SNAP=MAIN/'desktop/.local/stable-product-snapshot-50'
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
def read(p):return wide(p).read_bytes()
def sha(p):return hashlib.sha256(read(p)).hexdigest()
def save(p,v):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes((json.dumps(v,indent=2,ensure_ascii=False)+'\n').encode())
repo=json.loads(read(P/'repository-local-hunks.json'));cache=json.loads(read(P/'cache-local-hunks.json'))
actual=json.loads(read(SNAP/'manifest.json'));inputs={r['path']:r['sha256Bytes']for r in actual['inputs']}
sourceChecks=[]
for d in [repo,cache]:
 b=read(P/'baseline'/d['path']);sourceChecks.append(dict(path=d['path'],snapshot50RawSHA=inputs[d['path']],baselineLFSHA=d['baseLFsha256'],baselineLFMatchesSnapshotRaw=hashlib.sha256(b).hexdigest()==inputs[d['path']]))
 assert hashlib.sha256(b).hexdigest()==d['baseLFsha256']
 # Snapshot source records may pin CRLF rather than LF; explicit fixture candidate
 # baseline remains pinned here, never silently claim LF equals a different byte form.
 candidate=read(MAIN.parent/'BiliPai-v023'/d['path'])
 assert hashlib.sha256(candidate.replace(b'\r\n',b'\n')).hexdigest()==d['baseLFsha256']
 save(P/('source-current-'+Path(d['path']).stem+'.json'),dict(path=d['path'],currentRawSHA=hashlib.sha256(candidate).hexdigest(),currentLFSHA=d['baseLFsha256'],snapshot50RawSHA=inputs[d['path']],rawMatchesSnapshot=hashlib.sha256(candidate).hexdigest()==inputs[d['path']]))
compileResult=json.loads(read(P/'compile-runs/01/result.json'));result=json.loads(read(P/'fixture-runs/01/result.json'))
assert compileResult['passed']and result['passed']and result['assertions']==27
origins=[]
for r in result['origins']:
 path=urllib.parse.unquote(urllib.parse.urlparse(r['codeSource']).path).lstrip('/')
 with zipfile.ZipFile(wide(path))as z:classBytes=z.read(r['class'].replace('.','/')+'.class')
 h=hashlib.sha256(classBytes).hexdigest();assert h==r['sha256ClassBytes'];origins.append(dict(**r,verifiedAgainstJar=True))
save(P/'fixture-runs/01/actual-origins-verified.json',dict(passed=True,identities=origins))
manual=P/'prepared/manual/com/bilipai/desktop/ui/DesktopOriginalVideoRepositoryBinding.kt'
recipe=dict(preparedOnly=True,actualProductAcceptance=False,upstreamCommit='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589',
 actual50ManifestSHA=sha(SNAP/'manifest.json'),actual50Ordered97CPSHA=sha(SNAP/'ordered-runtime-cp.json'),
 canonicalCoreFrozenSHA=sha(MAIN/'desktop/.local/stable-video-state-holder-parity/frozen-handoff.json'),
 canonicalCoreJarSHA=compileResult['coreJarSha256Bytes'],
 installOrder=['Install canonical core192 source interfaces/protocol first (same sole producer; no JAR install).',
 'Apply repository-local-hunks.json five exact local hunks to current Repository, retaining unrelated live hunks.',
 'Apply cache-local-hunks.json three exact hunks to the sole existing DesktopPlaybackCache.',
 'Copy the one manual DesktopOriginalVideoRepositoryBinding.kt source; append its LF identity to the existing source provenance once.',
 'Do NOT copy proof-only whole Repository/cache, compiled candidate JAR, original-stable reference files or fixture sources to production.',
 'Root full original load owner calls capture once INSIDE each load-generation coroutine and passes that immutable rawRepository/receipt to that invocation. No mutable currentBinding and no second planner/cache/client.',
 'Root required primary token callback must use existing TV refresh actor with receipt+job admission before Call enqueue and credentials commit. Never bind current unowned refreshTvToken directly. Changed credentials retire old load; next load captures a new receipt.',
 'Final media/CDN/plugin mappings call binding.authorized(source) before the existing receipt-admitted native publication. Original UseCase media.accept cannot lose receipt; no native wait/join/close under Store/entry gate.'],
 payload=[dict(path=str(manual),target='desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalVideoRepositoryBinding.kt',sha256LF=sha(manual))],
 localHunks=[dict(file='repository-local-hunks.json',hunks=5,baselineLF=repo['baseLFsha256'],candidateLF=repo['candidateLFsha256']),dict(file='cache-local-hunks.json',hunks=3,baselineLF=cache['baseLFsha256'],candidateLF=cache['candidateLFsha256'])],
 cachePolicy=dict(originalCapacity=80,originalMinutes=10,originalExpiredComparison='now > storedAt + 10min',retainedWindowsGuards=['clock rollback removes entry','accountEpoch/authorizationRevision isolation','existing codec/audio/AV1 cache key']),
 wbiPolicy=dict(originalMinutes=30,soleFields='DesktopRepository.wbiKeys/wbiExpiresAt/wbiGeneration; canonical timestamp is an admitted view of same expiry field',originalTimestampAfterNav=True),
 lockOrder=['existing wbiMutex when Root sign is used (no inverse path takes it under Store)','same SessionStore admission','same Root entry commit gate for canonical cache/state/scalar reads','Repository protocol monitor OR existing playbackCache monitor'],
 requiredPorts=['same entry Job','same immutable load-generation isEntryCurrent and commitIfEntryCurrent','actual PlayerPreferences snapshot/codec override/blocked codecs/AV1 capability','actual global auto1080p/directedTraffic preferences and actual network/mobile getter','same owned primary TV token refresh actor'],
 deferred=['Root authority switch/full VM and Holder mount','subtitle/plugin later request factory','required TV refresh actor owned tail','same native media/source final publication factory','actual installed zero-override runtime proof'],
 scope='Focused same-cache/state bridge only, no new canonical models/cache/HTTP client/native player/static protocol state')
save(P/'INSTALL-RECIPE.json',recipe)
save(P/'source-checks.json',dict(passed=True,sourceChecks=sourceChecks,canonicalInterfacesProduced=0,newManualFiles=1,repositoryExactHunks=5,cacheExactHunks=3,sourceInverse=json.loads(read(P/'source-inverse-result.json')),fixtureAssertions=27,originsVerified=len(origins),unpermittedClassOverlap=compileResult['unpermittedOverlap']))
note='''# Real Repository/core bridge (prepared)

This packet implements the canonical core192 cache/state interfaces over the SAME DesktopRepository fields, DesktopPlaybackCache, DesktopSessionStore and owned Retrofit Call.Factory. It does not install another cache, Store, client or static WBI state. Whole Repository/cache files are proof-only; installation uses exact local hunks.

Original PlayUrlCache.kt:16–33 is capacity **80**, 10 minutes, strict `now > expiresAt`. Its old prose mentioning 50 is stale. Windows clock-rollback eviction and epoch/revision/codec/audio isolation remain explicit guards. VideoRepository's WBI cache is 30 minutes; timestamp is committed after nav completes. Root signer and canonical protocol now share the same keys/expiry/generation. Original separate key/timestamp setter ordering is preserved.

The original APP -101 branch calls TokenRefreshHelper, which supports TV only (23–33); non-TV returns false. An earlier read guessed Android refresh was missing, corrected by this exact source. Current DesktopLoginRepository.refreshTvToken is real, but its public action captures its own current epoch and has no caller receipt parameter. It cannot be injected directly into this owned callback. Root must bind its existing refresh authority with request receipt admission before enqueue and credentials commit. No parallel token/client authority is supplied here. Successful credential replacement invalidates this request receipt and cancels this load; a NEW request may retry with a fresh capture.

`DesktopOriginalVideoRepositoryBinding.capture(...)` is suspend and captures current coroutine Job plus supplied entry Job and original load generation. Its `.rawRepository` is the existing canonical DesktopOriginalVideoLoadRepository; `.protocol` exposes the sole original protocol for parent-only future methods. It does not produce a LoadPort/UseCase/VM. Capture once per load invocation before same-load async info/playurl work; share that immutable closure, never page-scoped completed Jobs or a mutable currentBinding that a replacement request can overwrite.

Transport services are constructed over the existing ownedHomeService/ownedPlaybackService and same client/Store. Tags precede Call.Factory.newCall. Primary/guest calls keep the captured playback receipt in their lifetime predicate; playback calls additionally carry the authorization tag. All scalar/cached state access uses Store -> entry gate -> short protocol/cache monitor. Root WBI fetch remains outside Store/entry/monitor. Entry predicates and commit callbacks must NOT acquire Store first from the entry lock. No Call execution, ACK, native join or close is permitted under these gates.

The fixture compiles only itself, while explicitly declaring prepared Repository/cache family overrides plus core192 interfaces. Actual immutable50 Store, receipt and DTO remain loaded from product JAR. 27 assertions passed; seven class-byte origins verified; 97 CP and prepared dependency pins match before/after. No socket, account/network operation, HWND or native decoder ran. This is not installed product/fullVM acceptance.
'''
wide(P/'CONTRACT.md').write_bytes(note.encode())
rows=[];excluded=[]
for p in sorted(wide(P).rglob('*')):
 if not p.is_file():continue
 rel=p.relative_to(wide(P)).as_posix()
 if rel=='frozen-handoff.json':continue
 if p.suffix.lower()in ['.jar','.class','.pyc']or '__pycache__'in p.parts:
  excluded.append(dict(path=rel,reason='compiled binary/runtime artifact, not source-only handoff'));continue
 rows.append(dict(path=rel,sha256Bytes=sha(p),bytes=len(read(p))))
save(P/'frozen-handoff.json',dict(frozen=True,preparedOnly=True,rawArtifacts=rows,excludedArtifacts=excluded,scope='One manual adapter, exact five Repository/three cache hunks; core192 canonical reference; 3-source 48-class prospective compile + focused27; no actual installed/fullVM/native acceptance'))
print(json.dumps(dict(manifest=str(P/'frozen-handoff.json'),sha256=sha(P/'frozen-handoff.json'),rows=len(rows),excluded=len(excluded),recipeSHA=sha(P/'INSTALL-RECIPE.json'),manualSHA=sha(manual),repositoryBaseLF=repo['baseLFsha256'],repositoryCandidateLF=repo['candidateLFsha256'],cacheBaseLF=cache['baseLFsha256'],cacheCandidateLF=cache['candidateLFsha256']),indent=2))
