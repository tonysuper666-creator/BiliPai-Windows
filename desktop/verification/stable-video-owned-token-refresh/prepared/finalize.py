from pathlib import Path
import hashlib,json,urllib.parse,zipfile
P=Path(__file__).resolve().parent;MAIN=P.parents[2];B=MAIN/'desktop/.local/stable-video-repository-core-ports-parity';S=MAIN/'desktop/.local/stable-product-snapshot-50'
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
def read(p):return wide(p).read_bytes()
def sha(p):return hashlib.sha256(read(p)).hexdigest()
def save(p,v):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes((json.dumps(v,indent=2,ensure_ascii=False)+'\n').encode())
repo=json.loads(read(P/'repository-local-hunks.json'));login=json.loads(read(P/'login-local-hunks.json'))
result=json.loads(read(P/'fixture-runs/03/result.json'));compiled=json.loads(read(P/'compile-runs/02/result.json'))
assert result['passed']and result['assertions']==25 and compiled['passed']
origins=[]
for r in result['origins']:
 path=urllib.parse.unquote(urllib.parse.urlparse(r['codeSource']).path).lstrip('/')
 with zipfile.ZipFile(wide(path))as z:data=z.read(r['class'].replace('.','/')+'.class')
 assert hashlib.sha256(data).hexdigest()==r['sha256ClassBytes'];origins.append(dict(**r,jarClassByteVerified=True))
save(P/'fixture-runs/03/actual-origins-verified.json',dict(passed=True,identities=origins))
sourceRows=json.loads(read(B/'original-source-inventory.json'))
inventory=[]
for r in sourceRows['identities']:
 if r['path'].endswith(('TokenRefreshHelper.kt','ApiClient.kt')):
  source=B/'original-stable'/r['path'];assert sha(source)==r['sha256Bytes']
  target=P/'original-stable'/r['path'];wide(target).parent.mkdir(parents=True,exist_ok=True);wide(target).write_bytes(read(source));inventory.append(r)
save(P/'original-source-inventory.json',dict(commit=sourceRows['commit'],identities=inventory,ownership='Original policy/source evidence only. Existing canonical API/DTO/helper FQNs remain sole owners; no duplicate production declaration.'))
manual=P/'prepared/manual/com/bilipai/desktop/ui/DesktopOriginalVideoTokenRefreshBinding.kt'
recipe=dict(preparedOnly=True,productAcceptance=False,dependsOnBridge41=dict(path=str(B/'frozen-handoff.json'),sha256Bytes=sha(B/'frozen-handoff.json')),
 actual50ManifestSHA=sha(S/'manifest.json'),actual50Ordered97CPSHA=sha(S/'ordered-runtime-cp.json'),
 installation=['Install bridge41 exact Repository/cache hunks first; its raw binding source stays unchanged.',
 'Apply repository-local-hunks.json four exact local hunks to that Repository base, retaining Root unrelated code.',
 'Apply login-local-hunks.json one exact body/owned-tail hunk to sole existing DesktopLoginRepository. No extra actor/client/Store.',
 'Copy one manual DesktopOriginalVideoTokenRefreshBinding source. Never copy proof-only entire Repository/Login or candidate/fixture JAR.',
 'Root entry factory constructs DesktopOriginalVideoTokenRefreshBinding(existingLogin,entryJob,entryOwns,entryCommit). Pass available and refresh method references to each per-load RawBinding.capture.',
 'Updated installLogin adds optional requestReceipt/stillOwned/commitIfCurrent tail; existing Kotlin call sites retain legacy no-receipt behavior. Root recompiles whole product so no stale Kotlin default-argument ABI remains.',
 'No Store/entry gate wraps refresh HTTP, nav validation await, Mutex wait, shared dispatcher cancellation, native join or native close. Successful owned replacement invalidates old receipt without cancelling shared transport/native source.',
 'Root raw binding assertCurrent after refresh deliberately cancels old load on new revision; original full owner schedules a new generation and captures new authorization. No implicit old-load receipt adoption.'],
 signatures=['DesktopOriginalVideoTokenRefreshBinding(login:DesktopLoginRepository,entryJob:Job,isEntryCurrent:()->Boolean,commitIfEntryCurrent:((()->Unit)->Boolean))',
 'fun available():Boolean','suspend fun refresh(receipt:DesktopPlaybackAuthorizationReceipt,requestOwned:()->Boolean):Boolean',
 'DesktopLoginRepository.refreshTvTokenForOriginalPlayback(receipt,stillOwned,commitIfCurrent):Boolean'],
 hunks=[dict(file='repository-local-hunks.json',count=4,baseLF=repo['baseLFsha256'],candidateLF=repo['candidateLFsha256']),dict(file='login-local-hunks.json',count=1,baseLF=login['baseLFsha256'],candidateLF=login['candidateLFsha256'])],
 manual=dict(path=str(manual),target='desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalVideoTokenRefreshBinding.kt',sha256LF=sha(manual)),
 sourceIdentityMerge=[dict(**r,mode='policy-extract'if r['path'].endswith('TokenRefreshHelper.kt')else'reference',feature='video-owned-primary-token-refresh',rule='Add identity only if absent; otherwise union feature without replacing existing mode, pins or selected declarations')for r in inventory],
 scope='TV policy and existing actor/Store receipt admission only; no fullVM/Root mounted/native acceptance')
save(P/'INSTALL-RECIPE.json',recipe)
save(P/'history.json',dict(keptFailures=[dict(run='fixture-runs/01',failure='Timed out after assertion failure; initial captured stderr was not saved by old runner. Compiler/pins/args retained; no PASS claim.'),dict(run='fixture-runs/02',failure='Expected stale receipt cancellation: ordinary exception catch returned false; assertion stack and own-process thread diagnostic retained; exit124 after non-daemon transport cleanup was bypassed by the assertion.')],fix='Ordinary exception branch re-admits original receipt before returning false; stale/retired transport failures now become CancellationException. No fixture business assertion changed.',acceptedRun='fixture-runs/03',assertions=25))
note='''# Owned primary TV refresh (prepared supplement)

Depends on immutable bridge41. Root installation uses Repo four exact local hunks plus sole LoginRepository one exact hunk and one manual adapter. Original TV refresh params/signature/body fields remain selected; original non-TV/no-refresh-token returns false. There is no new credentials store/client/actor and no new canonical DTO/API declaration.

The same existing login Mutex serializes refresh operations. Caller Job is captured before IO/Mutex wait; immutable old receipt and entry lifetime tag the same Repository primary Passport service before Call creation. Existing NO_COOKIES validation Retrofit is named and reused via an owned Call.Factory wrapper; no new OkHttpClient is created for the owned API/validation views. Forced-cookie nav validation semantics remain original.

Network/validation await remains outside Store/entry/monitor. Final replace checks caller coroutine, same receipt and entry gate under Store, performs only cache invalidation + existing synchronous saveAccount, and does not call shared dispatcher.cancelAll or stop MPV. Legacy no-receipt installLogin/refreshTvToken behavior remains. Successful credential replacement is terminal: old raw load is cancelled by its existing post-refresh assertion, then a new load captures fresh authorization. Do not retag old responses.

The failure branch rechecks the old receipt: transport IOException from retirement cannot become ordinary false and execute an old fallback. Current-owner ordinary errors return false as original TokenRefreshHelper. Original helper swallowed cancellation, while Root's explicit job/epoch contract requires cancellation propagation here.

Fixture03: 25 assertions, explicit memory-only interception of existing refresh+validation transports, actual immutable50 Store/receipt. Three prospective sources/52 classes compile; zero fixture production-class overlap, five loaded class-byte origins verified, actual97CP and prepared dependencies pinned before/after. Four focused scenarios cover non-TV, TV successful commit, same-epoch revision during validation, and only caller Job cancelled at final gate while page/Store stay alive. This does not claim real account/HTTP/native or installed Root acceptance. Failure01/02 remain recorded; fixture business assertions identical across retries.
'''
wide(P/'CONTRACT.md').write_bytes(note.encode())
save(P/'source-checks.json',dict(passed=True,sourceInverse=json.loads(read(P/'source-inverse-result.json')),prospectiveSources=3,prospectiveClasses=52,fixtureAssertions=25,fixtureClassOverlap=[],classByteOriginsVerified=len(origins),newManualFiles=1,canonicalApiModelProduced=0))
rows=[];excluded=[]
for p in sorted(wide(P).rglob('*')):
 if not p.is_file():continue
 rel=p.relative_to(wide(P)).as_posix()
 if rel=='frozen-handoff.json':continue
 if p.suffix.lower()in ['.jar','.class','.pyc']or '__pycache__'in p.parts:
  excluded.append(dict(path=rel,reason='compiled binary/runtime artifact excluded from source-only handoff'));continue
 rows.append(dict(path=rel,sha256Bytes=sha(p),bytes=len(read(p))))
save(P/'frozen-handoff.json',dict(frozen=True,preparedOnly=True,rawArtifacts=rows,excludedArtifacts=excluded,scope='Owned TV refresh supplement, bridge41 unchanged; exact source packet, 3-source compile52 + memory focused25, original failed attempts retained. No installed product/fullVM/native acceptance.'))
print(json.dumps(dict(manifest=str(P/'frozen-handoff.json'),manifestSHA=sha(P/'frozen-handoff.json'),rows=len(rows),excluded=len(excluded),recipeSHA=sha(P/'INSTALL-RECIPE.json'),manualSHA=sha(manual),repoBaseLF=repo['baseLFsha256'],repoCandidateLF=repo['candidateLFsha256'],loginBaseLF=login['baseLFsha256'],loginCandidateLF=login['candidateLFsha256']),indent=2))
