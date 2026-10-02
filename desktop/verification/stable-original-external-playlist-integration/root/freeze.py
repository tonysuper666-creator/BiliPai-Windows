from pathlib import Path
import hashlib,json,subprocess,sys
sys.stdout.reconfigure(encoding='utf-8');sys.dont_write_bytecode=True
H=Path(__file__).resolve().parent;MAIN=H.parents[2];REPO=MAIN.parent/'BiliPai-v023'
S=MAIN/'desktop/.local/stable-product-snapshot-78'
OUT=REPO/'desktop/verification/stable-original-external-playlist-integration'
assert not OUT.exists();OUT.mkdir(parents=True)
def wide(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix)else prefix+s)
def read(p):return wide(p).read_bytes()
def sha(b):return hashlib.sha256(b).hexdigest()
def put(relative,b):
 p=wide(OUT/relative);p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(b)
def copy(relative,p):put(relative,read(p))
assert sha(read(S/'manifest.json'))=='95665c3a2211e1d7be50b40c92138db37b837f098f44e331936f841e99174e52'
assert sha(read(S/'ordered-runtime-cp.json'))=='bb20396a8d06fd8b0520142d0ae704d102c1106962fe7d2b66279455e0ccb242'
result=json.loads(read(H/'runs/01/result.json'));assert result['passed']and result['assertions']==46 and result['productionOverrides']==0
source=json.loads(read(H/'source-verification.json'));assert source['passed']and source['fullOriginalDialogInverse']
review=MAIN/'desktop/.local/stable-original-external-playlist-review'
assert sha(read(review/'frozen-review-final.json'))=='48678f3c2851cf196efd79e68115a48431cf5556dc9e53f82c6cfaf843e8e914'
receipt=json.loads(read(review/'source-review-final.json'));assert not receipt['blockingFindings'] and not receipt['compilerOrRuntimeRunByReviewer']
for r in receipt['primaryInputs']+receipt['referenceInputs']:assert sha(read(r['path']))==r['sha256Bytes']
for r in json.loads(read(review/'frozen-review-final.json'))['rawArtifacts']:assert sha(read(review/r['path']))==r['sha256Bytes']
for file in sorted(wide(review).rglob('*')):
 if file.is_file():copy('review/'+str(file.relative_to(wide(review))).replace('\\','/'),file)
for name in ('ExternalPlaylistProof.kt','run.py','verify-source.py','source-verification.json','ROOT-INTEGRATION.md','freeze.py'):copy('root/'+name,H/name)
for file in sorted(wide(H/'runs/01').rglob('*')):
 if file.is_file():copy('root/run01/'+str(file.relative_to(wide(H/'runs/01'))).replace('\\','/'),file)
for relative in [
 'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalExternalPlaylistBinding.kt',
 'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalVideoRepositoryBinding.kt',
 'desktop/src/main/kotlin/com/bilipai/desktop/audio/DesktopAudioRepository.kt',
 'desktop/src/main/kotlin/com/bilipai/desktop/data/UpstreamLogger.kt',
 'desktop/tools/extract-search-platform.py','desktop/tools/extract-upstream-music-player-full.py',
 'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalPlayerSettingsContext.kt',
 'desktop/src/main/kotlin/com/bilipai/desktop/plugins/DesktopPluginStore.kt',
 'desktop/src/main/kotlin/com/bilipai/desktop/plugins/DesktopSubscriptionWriteAdmission.kt',
 'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalMusicUiPlatform.kt',
]:
 snap=json.loads(read(S/'manifest.json'));pin=next(r for r in snap['inputs']if r['path']==relative)
 assert sha(read(REPO/relative))==pin['sha256Bytes']
 copy('product-source/'+Path(relative).name,REPO/relative)
for name in ('DesktopOriginalCapturedVideoSearch.kt','DesktopSearchDeclarations.kt'):
 copy('generated/'+name,REPO/'desktop/build/generated/search/com/android/purebilibili/data/repository'/name)
copy('generated/captured-video-search-selection.json',REPO/'desktop/build/generated/search/captured-video-search-selection.json')
music=REPO/'desktop/build/generated/music-player-full'
for name in ('DesktopOriginalExternalPlaylistDomain.kt','DesktopOriginalExternalPlaylistSchema.kt'):
 copy('generated/'+name,music/'com/android/purebilibili/data/repository'/name)
copy('generated/ExternalPlaylistImportDialog.kt',music/'com/android/purebilibili/feature/audio/screen/ExternalPlaylistImportDialog.kt')
for name in ('music-source-pins.json','music-adaptations.json','music-outputs.json'):copy('generated/'+name,music/name)
for relative in ('data/repository/SearchRepository.kt','data/repository/ExternalPlaylistRepository.kt','feature/audio/screen/ExternalPlaylistImportDialog.kt'):
 original='app/src/main/java/com/android/purebilibili/'+relative
 b=subprocess.check_output(['git','show','3d5d19a2f994daccd0e2f8b5f522b6d82f43d589:'+original],cwd=REPO)
 assert b==read(REPO/original).replace(b'\r\n',b'\n')
 put('original/'+Path(relative).name,b)
for name in ('manifest.json','ordered-runtime-cp.json'):copy('snapshot78/'+name,S/name)
copy('snapshot78/jvm-method-name-audit-78.json',MAIN/'desktop/.local/upstream-v023-audit/jvm-method-name-audit-78.json')
audit=json.loads(read(MAIN/'desktop/.local/upstream-v023-audit/jvm-method-name-audit-78.json'));assert audit['issueCount']==0
for name in ('classes-78.log','classes-78-before-ui-admission.log','classpath-78.log'):
 copy('build/'+name,REPO/'desktop/.local/stable-build-repair'/name)
rows=[]
for file in sorted(wide(OUT).rglob('*')):
 if file.is_file():
  b=read(file);rows.append(dict(path=str(file.relative_to(wide(OUT))).replace('\\','/'),sizeBytes=len(b),sha256Bytes=sha(b)))
manifest=dict(upstreamTag='v0.2.3',upstreamCommit='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589',
 sourceRegistryCount=1170,resources=213,actualSnapshot=78,runtimeEntries=101,
 productionOverrides=0,fixtureAssertions=46,fixtureClasses=20,verifiedUniqueProductClassOrigins=12,
 originalSearchAndExternalDomainInverse=True,fullOriginalImportDialogInverse=True,
 independentSourceReview=True,reviewerReran=False,realLoopbackHttp=True,fakeSearchAndNavApi=True,
 capturedBindingRuntimeAccepted=False,completeRootMounted=False,nativeWindowAccepted=False,
 desktopExeReplaced=False,artifacts=rows)
put('artifact-manifest.json',(json.dumps(manifest,indent=2)+'\n').encode())
put('README.md',b'''# Original external playlist and captured search integration

Installs the original external playlist fetch/parsing/encryption/matching and
checkpoint algorithms plus original video-search/WBI fallback/error bodies.
The existing schema, Search policies, Root request Binding, settings document,
and public audio client remain sole owners. Async import UI state uses Root's
required short music commitUi admission; network and checkpoint IO remain outside.

Actual78 complete desktop classes pass. One fixture compiles against only its
101 frozen runtime entries with zero production class overrides. Its 46 assertions
cover original search/filter/page conversion, nav fallback and retirement, public
QQ/NetEase requests and eapi ciphertext, song matching/resume, the real settings
Store checkpoint, and real loopback body-read cancellation/retirement. Search/Nav
APIs are fake; their original installed algorithms and models are used. Twelve
installed product class origins are uniquely verified, not twelve runtime actors
claimed mounted. Complete Root captured Binding/remote endpoints/native Window/
EXE acceptance remain pending. See root/ROOT-INTEGRATION.md for the required mount.

Raw source inverse receipts, fixture/compiler/classpath pins and output, full
build logs, immutable product metadata, and the independent read-only source
review are preserved byte-for-byte below. The reviewer did not rerun tests.
''')
print(json.dumps(dict(path=str(OUT),rawArtifacts=len(rows),manifestSHA256Bytes=sha(read(OUT/'artifact-manifest.json')))))
