from pathlib import Path
import hashlib,json,subprocess
H=Path(__file__).resolve().parent;MAIN=H.parents[2];REPO=MAIN.parent/'BiliPai-v023'
def wide(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix)else prefix+s)
def read(p):return wide(p).read_bytes()
def sha(b):return hashlib.sha256(b).hexdigest()
def put(p,b):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes(b)
def dump(p,v):put(p,(json.dumps(v,ensure_ascii=False,indent=2)+'\n').encode())
assert not(H/'source-review.json').exists()
primary=['desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalExternalPlaylistBinding.kt',
 'desktop/tools/extract-search-platform.py','desktop/tools/extract-upstream-music-player-full.py']
refs=['desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalVideoRepositoryBinding.kt',
 'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalPlayerSettingsContext.kt',
 'desktop/src/main/kotlin/com/bilipai/desktop/plugins/DesktopSubscriptionWriteAdmission.kt',
 'desktop/src/main/kotlin/com/bilipai/desktop/plugins/DesktopPluginStore.kt',
 'desktop/src/main/kotlin/com/bilipai/desktop/audio/DesktopAudioRepository.kt']
inputs=[];texts={}
for rel in primary+refs:
 b=read(REPO/rel);t=b.decode().replace('\r\n','\n');texts[rel]=t
 inputs.append(dict(path=str(REPO/rel),sha256Bytes=sha(b),size=len(b),sha256LF=sha(t.encode()),role='primary'if rel in primary else'reference'))
 if rel in primary:put(H/'retained-current'/rel,b)
checks=[]
def test(rel,needle,claim):
 t=texts[rel];assert needle in t
 checks.append(dict(file=rel,line=t[:t.index(needle)].count('\n')+1,claim=claim,sourceExpression=needle))
b=primary[0];s=primary[1];m=primary[2];store=refs[3]
test(b,'val binding = retained ?: captureRequest()','New operation captures in actual caller Job; nested operation retains same request binding.')
test(b,'return withContext(retainedRequest.asContextElement(binding))','Same retained binding survives coroutine dispatcher changes for nested progress/checkpoint calls.')
test(b,'commitIfCurrent = binding::admitCurrentMutation','Same captured request receipt supplies short mutation gate; no new account/store authority.')
test(b,'if (retained != null) return block(domain).also { checkRequest() }','Nested operations preserve surrounding admission and check completion without recapture.')
test(b,'caller.ensureActive()\n                DesktopSubscriptionWriteAdmission.checkCurrentRequestOrOriginal()\n                onProgress','Original match progress callback is checked before and after nested checkpoint writes.')
test(b,'invokeOnCompletion(onCancelling = true, invokeImmediately = true)','Call.cancel is attached immediately at request cancellation, including already cancelled Job.')
test(b,'return call.execute().use { response ->','Response remains open only through original parser; actual response closure executes before cancellation hook is removed.')
test(b,'body(CheckedPlaylistBody(response.body, ::checkpoint))','Body carrier shares borrowed actual response; no copy/client/service is created.')
test(b,'check()\n            val count = super.read(sink, byteCount)\n            check()','Each underlying body read checks caller and captured account/entry before and after IO.')
test(b,'block(owned).also { checkpoint() }','Final parser result rechecks admission, including data satisfied by an already buffered source.')
test(b,'require(supplied === context)','Checkpoint operation requires the same Root settings context, not a new persisted document.')
test(s,'assert restored == original_selected','Producer explicitly reverses all guard adaptations to the original selected search/sign/error bodies.')
test(s,'private val api: SearchApi,\n    private val navApi: BilibiliApi,','Search and Nav APIs are constructor-required captured request facets, not latest credential globals.')
test(s,'checkCurrent()\\n        val navResp = navApi.getNavInfo()\\n        checkCurrent()','Real nav result is checked before and after original WBI selection.')
test(s,'checkCurrent()\\n        val response = api.search(signedParams)\\n        checkCurrent()','Real search result is checked before and after original conversion/pagination.')
test(m,'assert restored==originalDomain','Producer reverses original domain body, encryption/parsing/matching/checkpoint selection exactly.')
test(m,"'responsePort.withResponse(request) { response ->'",'Only original response execution boundary changes; original request parameters, encryption and parser bodies remain selected.')
test(m,'Only async UI publication enters same caller/entry/account gate; checkpoint IO remains outside','Original ImportDialog async state publication uses required platform.commitUi while IO remains outside.')
test(store,'val temporary = Files.createTempFile(backing.root, "plugin-settings-", ".tmp")','Existing global settings actor stages owned file outside Root account/entry gates.')
test(store,'val permit = acquirePermit()','Only permit acquisition enters Root admission; document publication consumes permit under backing monitor after leaving Root gate.')
originals=[]
for rel in ['app/src/main/java/com/android/purebilibili/data/repository/SearchRepository.kt',
 'app/src/main/java/com/android/purebilibili/data/repository/ExternalPlaylistRepository.kt',
 'app/src/main/java/com/android/purebilibili/feature/audio/screen/ExternalPlaylistImportDialog.kt']:
 git=subprocess.run(['git','-C',str(REPO),'show','3d5d19a2f994daccd0e2f8b5f522b6d82f43d589:'+rel],capture_output=True,check=True).stdout.decode().replace('\r\n','\n')
 current=read(REPO/rel).decode().replace('\r\n','\n');assert git==current
 originals.append(dict(path=rel,commit='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589',sha256LF=sha(current.encode()),gitBlobMatches=True))
fixture=MAIN/'desktop/.local/stable-original-external-playlist-integration/runs/01/result.json'
fixtureResult=json.loads(read(fixture));assert fixtureResult['passed']and fixtureResult['assertions']==46 and fixtureResult['productionOverrides']==0
for row in inputs:assert sha(read(row['path']))==row['sha256Bytes']
receipt=dict(version=1,readOnly=True,productionModified=False,compilerOrRuntimeRunByReviewer=False,
 primaryInputs=inputs[:3],referenceInputs=inputs[3:],fixedOriginals=originals,sourceChecks=checks,blockingFindings=[],
 actual78=dict(manifestSHA256Bytes='95665c3a2211e1d7be50b40c92138db37b837f098f44e331936f841e99174e52',
  orderedCPSHA256Bytes='bb20396a8d06fd8b0520142d0ae704d102c1106962fe7d2b66279455e0ccb242',runtimeEntries=101,
  mainKotlinSHA256Bytes='84eb914ae6e7da03c90dc5a58d4f0c21b906e19baac8e7b785cbe29150285d2d'),
 rootOwnedFixture=dict(path=str(fixture),sha256Bytes=sha(read(fixture)),size=len(read(fixture)),result=fixtureResult,reviewerReran=False),
 conclusions=['No specific blocking defect found in the three requested final source families.',
  'Actual caller cancellation and captured receipt reject results and body reads; caller hook lasts through response closure.',
  'Nested batch checkpoint uses retained binding and same outer SubscriptionWriteAdmission; it does not capture latest account.',
  'Original matching/search/encryption/parser/checkpoint semantics remain source selected; added guards target transport/publication boundaries.',
  'Transport borrows the existing bounded public audio client and captured search facets; no new client/cache/Store/MPV/thread created by binding.',
  'Original checkpoint uses global import_checkpoint_v1 key; receipt guards writes but this is not new per-account checkpoint partitioning.',
  'Original domain runCatching can contain CancellationException temporarily, but adapter final check and child completion reject canceled/retired request before publication.',
  'Complete Root captured-binding runtime and concrete Music commitUi wiring are still a separate required mount acceptance.'],
 requiredMount=['Same Root context identity and captureRequest from actual current entry/caller Job.',
  'Concrete platform.commitUi must join same SubscriptionWriteAdmission and live entry admission; do not invoke merely an unguarded UI closure.',
  'Do not put network/parse/temp/fsync IO inside Store/entry gates. Existing settings permit minting remains short.'])
dump(H/'source-review.json',receipt)
raw=[]
for p in sorted(H.rglob('*')):
 if p.is_file()and p.name!='frozen-review.json':raw.append(dict(path=p.relative_to(H).as_posix(),sha256Bytes=sha(read(p)),size=len(read(p))))
dump(H/'frozen-review.json',dict(rawArtifacts=raw,rawCount=len(raw),reviewOnly=True,sourceReviewSHA256Bytes=sha(read(H/'source-review.json'))))
print(json.dumps(dict(sourceChecks=len(checks),sourceReviewSHA256Bytes=sha(read(H/'source-review.json')),manifestSHA256Bytes=sha(read(H/'frozen-review.json')),rawCount=len(raw))))
