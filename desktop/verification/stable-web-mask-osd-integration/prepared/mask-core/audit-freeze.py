from pathlib import Path
import hashlib,importlib.util,json,re,struct,subprocess,sys,zipfile
sys.dont_write_bytecode=True
L=Path(__file__).resolve().parent;ROOT=L.parents[2];MAIN=ROOT.parent/'BiliPai';SNAP=MAIN/'desktop/.local/stable-product-snapshot-31';COMMIT='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
def safe(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix) else prefix+s)
def read(p):return safe(p).read_text(encoding='utf-8').replace('\r\n','\n')
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def textsha(s):return hashlib.sha256(s.encode()).hexdigest()
def save(p,v):safe(p).write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
checks=[]
def require(label,yes):assert yes,label;checks.append(label)
require('immutable-actual31',sha(SNAP/'manifest.json')=='999bc989c995f877761b22c5c00a6a0d99819100d8cef591a25f94b5d56a3a5d')
sourceInputs={r['path']:r['sha256Bytes'] for r in json.loads(read(SNAP/'manifest.json'))['inputs']}
base=json.loads(read(L/'baseline-manifest.json'));versions={}
for r in base:
 path=r['path'];s=read(L/'baseline'/path);require('baselineLF:'+path,textsha(s)==r['sha256LF']);versions[path]=s
 if path in sourceInputs:
  require('actual31-source-base:'+path,sha(ROOT/path)==sourceInputs[path] and read(ROOT/path)==s)
hunks=json.loads(read(L/'local-hunks.json'))
for i,r in enumerate(hunks):
 s=versions[r['path']];require('hunk-before:'+str(i),textsha(s)==r['beforeSha256LF']);require('strict-one:'+str(i),s.count(r['old'])==1);s=s.replace(r['old'],r['new']);versions[r['path']]=s;require('hunk-after:'+str(i),textsha(s)==r['afterSha256LF'])
for path,s in versions.items():
 folder='prepared' if path.endswith('.py') else 'review-only';require('whole-review-equals-hunk-result:'+path,s==read(L/folder/path))
inventory=json.loads(read(L/'source-inventory.json'));require('17full-pinned-origins',len(inventory['sources'])==17)
for row in inventory['sources']:
 path=row['path'];blob=subprocess.check_output(['git','show',COMMIT+':'+path],cwd=ROOT).decode('utf-8').replace('\r\n','\n')
 require('full-origin:'+path,blob==read(L/'original-stable'/path) and textsha(blob)==row['sha256LF'] and row['pinnedCommit']==COMMIT)
spec=importlib.util.spec_from_file_location('producer',L/'prepared/desktop/tools/extract-upstream-danmaku-list-menu.py');p=importlib.util.module_from_spec(spec);spec.loader.exec_module(p)
newNames={'WebMaskParser.kt','DanmakuMaskFrame.kt','DesktopOriginalWebMaskProtocol.kt','DesktopOriginalWebMaskOwner.kt','DesktopOriginalWebMaskRefreshPolicy.kt'}
newRows=[r for r in inventory['emitted'] if Path(r['path']).name in newNames];require('5new-generated',len(newRows)==5)
for row in newRows:require('generated-current:'+row['path'],textsha(read(L/'generated'/row['path']))==row['sha256LF'])
parser=next(r for r in newRows if r['path'].endswith('WebMaskParser.kt'));require('whole-parser-reversible',parser['reverseNormalizedOriginalByteEqual'] and len(parser['adaptations'])==2)
owner=read(L/'generated/com/android/purebilibili/feature/video/danmaku/DesktopOriginalWebMaskOwner.kt')
ownerRow=next(r for r in newRows if r['path'].endswith('DesktopOriginalWebMaskOwner.kt'))
manager=read(L/'original-stable'/p.PATHS[5]);selected=[]
for r in ownerRow['adaptations']:
 name=r['method']
 if name=='isCurrentSegmentWindowRequest':
  a=owner.index('    private fun '+name+'(');b=owner.index('\n\n',a);body=owner[a:b]+'\n'
  a0=manager.index('    private fun '+name+'(');b0=manager.index('\n    private fun cancelObsoleteWindowRequest',a0);original=manager[a0:b0]
 else:body=p.function(owner,name,'' if name=='shouldApplyDanmakuLoadResult' else '    ')[0];original=p.function(manager,name,'' if name=='shouldApplyDanmakuLoadResult' else '    ')[0]
 require('selected-original-hash:'+name,textsha(original)==r['originalSha256LF'])
 require('selected-adapted-hash:'+name,textsha(body)==r['adaptedSha256LF'])
 reversedBody=body
 for delta in reversed(r['adaptations']):
  require('reverse-one:'+name+':'+str(len(checks)),reversedBody.count(delta['after'])==1);reversedBody=reversedBody.replace(delta['after'],delta['before'])
 require('selected-method-reverse-original-byte-equal:'+name,reversedBody==original)
 selected.append(dict(method=name,originalSha256LF=textsha(original),adaptedSha256LF=textsha(body),reverseOriginalByteEqual=True,adaptations=r['adaptations']))
require('no-second-owner-authority',all(s not in owner for s in ['CoroutineScope(','SupervisorJob(','OkHttpClient(','Retrofit.','DesktopPluginStore','DesktopSessionStore','captureScreenshot','MLKit']))
require('same-original-guard-one-policy',owner.count('positionMs >= webMaskWindowStartMs + WEB_MASK_REFRESH_GUARD_MS')==1 and owner.count('isWithinWebMaskWindowGuard(positionMs)')==2)
require('first-window-validity-before-arithmetic','webMaskWindowStartMs==Long.MIN_VALUE || webMaskWindowEndMs==Long.MIN_VALUE || webMaskWindowEndMs<=webMaskWindowStartMs' in owner)
require('bounded-retained-native-paths',all(x in owner for x in ['MAX_CACHED_MASK_FRAMES = 2402','MAX_CACHED_PATH_COMMANDS = 2_000_000L','frames.sumOf {it.path.commandCount.toLong()}']))
carrier=read(L/'prepared/desktop/src/main/kotlin/com/bilipai/desktop/danmaku/DesktopWebMaskPath.kt')
require('existing-skia-svg-full-grammar','SkiaPath.makeFromSVGString(data).use' in carrier and 'native.iterator().use' in carrier and all('PathVerb.'+v in carrier for v in ['MOVE','LINE','QUAD','CONIC','CUBIC','CLOSE','DONE']))
require('native-temporaries-not-retained',all(x not in carrier for x in ['private val native','private var native','convertConicToQuads']))
require('carrier-budget','MAX_PATH_COMMANDS=20_000' in carrier and 'catch(_:PathBudgetExceeded){null}' in carrier)
overlay=versions['desktop/src/main/kotlin/com/bilipai/desktop/danmaku/DanmakuOverlay.kt'];controller=versions['desktop/src/main/kotlin/com/bilipai/desktop/DesktopPlaybackController.kt']
require('only-existing-overlay-scope-lock-source',all(x in overlay for x in ['get()=requestLock','get()=requests','get()=source',': DesktopOriginalWebMaskOwner(), AutoCloseable']))
require('same-controller-community-and-native-owner','metadata={bvid,cid->communityReports.playerMetadata(bvid,cid)}' in controller and 'native.ownsSourceVersion(version)' in controller)
require('offline-explicit-null','durationSeconds, null, null)' in overlay)
require('three-retire-paths-and-owner-invalidation',overlay.count('retireWebMaskSource()')==3 and 'danmaku?.retireWebMaskSource()' in controller)
require('actual-paint-ownership-before-visibility','validateWebMaskOwnership()' in overlay and overlay.index('validateWebMaskOwnership()')<overlay.index('val visible ='))
require('standard-physical-child-only',overlay.count('applyDesktopWebMaskClip(')==1 and overlay.index('applyDesktopWebMaskClip(')<overlay.index('advancedRenderer.paint(context'))
require('original-passive-preserved','focusableWindowState = false' in overlay and 'isAutoRequestFocus = false' in overlay and 'or 0x00000020 or 0x08000000' in overlay)
require('no-second-smart-preference','@kotlinx.serialization.Transient\n    val smartOcclusionEnabled' in versions['desktop/src/main/kotlin/com/bilipai/desktop/danmaku/DanmakuSettings.kt'])
face=subprocess.check_output(['git','grep','-n','updateFaceOcclusion',COMMIT,'--','app/src/main','danmaku-engine/src/main'],cwd=ROOT,text=True).splitlines();require('original-face-update-no-caller',len(face)==1 and 'internal fun updateFaceOcclusion' in face[0])
compileResult=json.loads(read(L/'compile-09/compile-result.json'));proof=json.loads(read(L/'compile-09/proof-result.json'))
require('real-actual31-compile-pass',compileResult['status']=='PASS' and compileResult['actualWholeSnapshot']==sha(SNAP/'manifest.json'))
for r in compileResult['sourceInputs']:require('exact-compiled-source:'+r['path'],sha(r['path'])==r['sha256Bytes'])
require('5groups43asserts',proof['status']=='PASS' and proof['groups']==5 and proof['assertions']==43 and not proof['externalHttp'] and not proof['rootWindowRuntime'])
# Static top-level member collision audit excludes only declared existing canonical families.
code=read(L.parent/'stable-danmaku-render-topmethod-audit/audit.py');namespace=dict(globals());exec(code[code.index('def static_api'):code.index("assert sha(ADV")],namespace)
own=namespace['facade_api'](L/'compile-09/original-web-mask-candidate.jar');packages={r['package']+'/' for r in own};existing=[]
cp=json.loads(read(SNAP/'ordered-runtime-cp.json'))
for row in cp:require('CP-pin:'+row.get('source',row['path']),sha(row['path'])==row['sha256Bytes']);existing+=namespace['facade_api'](row['path'],packages)
allowed={'com/bilipai/desktop/DesktopPlaybackControllerKt.class','com/bilipai/desktop/ui/DesktopDanmakuSettingsProjectionKt.class','com/bilipai/desktop/danmaku/DesktopOriginalDanmakuRenderPlatformKt.class'}
unexpected=[]
for r in own:
 if r['facade'] in allowed or r['facade'].endswith('WebMaskProofKt.class'):continue
 matches=[e for e in existing if (e['package'],e['kind'],e['member'])==(r['package'],r['kind'],r['member'])]
 if matches:unexpected.append(dict(candidate=r,existing=matches))
require('new-topmethod-and-overload-name-overlap0',not unexpected)
save(L/'topmethod-receipt.json',dict(status='PASS',actual31CP=sha(SNAP/'ordered-runtime-cp.json'),candidate=sha(L/'compile-09/original-web-mask-candidate.jar'),candidateApis=own,checkedExistingStaticCount=len(existing),unexpected=[]))
newRegistry=[];merge=[]
registered={r['path']:r for r in json.loads(read(ROOT/'desktop/upstream-sources.json'))['sources']}
for name in ['WebMaskParser.kt','DanmakuPlaybackSyncPolicy.kt']:
 row=next(r for r in inventory['sources'] if r['path'].endswith('/'+name));require('new-registry-source-not-previously-owned:'+name,row['path'] not in registered)
 newRegistry.append(dict(path=row['path'],sha256=row['sha256LF'],mode='extracted',features=['stable-danmaku-list-menu','stable-web-mask-parity']))
for name in ['DanmakuManager.kt','DanmakuModels.kt','DanmakuRepository.kt']:
 row=next(r for r in inventory['sources'] if r['path'].endswith('/'+name));require('existing-registry-source-reused:'+name,registered[row['path']]['sha256']==row['sha256LF']);merge.append(dict(path=row['path'],addFeatures=['stable-web-mask-parity'],keepModeAndExistingFeatures=True))
save(L/'registry-recipe.json',dict(newSourceCount=2,newRows=newRegistry,mergeOnly=merge,producerAlreadyInGradle=True,additionalDependencyCount=0))
save(L/'source-audit.json',dict(status='PASS',checks=checks,count=len(checks),selectedMethods=selected,faceNoCaller=face,sourceOnly=True,actualRootWindowFalse=True))
payloads=[]
for f in sorted(safe(L/'prepared').rglob('*')):
 if f.is_file():payloads.append(dict(path=f.relative_to(safe(L/'prepared')).as_posix(),prepared=str(f),sha256Bytes=sha(f)))
require('3payload28hunks',len(payloads)==3 and len(hunks)==28)
contract=dict(status='READY_SOURCE_ONLY',installOrder=['Root media74 exact local hunks first','Mask core3 payload +28 exact local hunks (historical packages unchanged)','Separate required actual-OSD/video-coordinate delta next','Root whole compile/runtime acceptance of final combination'],payloads=payloads,localHunksSHA256=sha(L/'local-hunks.json'),registryRecipeSHA256=sha(L/'registry-recipe.json'),wholeRootOverwritesAuthorized=False,windowClipIsIntermediate=True,pending=['Real native video OSD bounds must be stacked before claiming video-coordinate mask parity','Actual Root/native paint acceptance, no external HTTP or real B station metadata exercised here','SCREEN_TOP/full original ordinary video-viewport layout and independent portrait fullscreen layout','ByteDance compositor/AA pixel identity and original special/Live renderer queue closure'],platformDifferences=['Java2D rational-conic carrier midpoint tolerance0.01 source pixels, maxdepth16; complete SVG grammar via existing Skia','Windows current frame is half-open [start,end); no overlapping boundary-mask union','Retained2402frames/2M pathcommands, perSVG20k commands; overbudget optionalmask omitted','Original uninitialized-window guard overflow corrected, original10s/30s/5s expression otherwise retained'],singleAuthority='Existing Overlay requests/requestLock/source/rawDocument/native sourceVersion + Controller current owner/account epoch + same community WBI metadata + original full global preference',proof=proof)
save(L/'install-contract.json',contract)
evidence=[]
for f in sorted(safe(L).rglob('*')):
 if f.is_file() and f.name!='frozen-handoff.json':evidence.append(dict(relative=f.relative_to(safe(L)).as_posix(),path=str(f),sha256Bytes=sha(f)))
frozen=dict(status='READY_SOURCE_ONLY',fixedCommit=COMMIT,payloadCount=3,localHunkCount=28,newSourceIdentityCount=2,generatedAdditions=5,productionCompileSources=12,proofSources=1,actual31Manifest=sha(SNAP/'manifest.json'),compileResult=sha(L/'compile-09/compile-result.json'),proof=proof,sourceAudit=sha(L/'source-audit.json'),installContract=sha(L/'install-contract.json'),topMethods=sha(L/'topmethod-receipt.json'),firstWindowFailure=dict(originalEndValue='Long.MIN_VALUE',originalGuard='positionMs >= webMaskWindowStartMs + 5000L && positionMs <= webMaskWindowEndMs - 5000L',failedFixture='compile-06/runtime.log',failedFixtureJar=sha(L/'compile-06/original-web-mask-candidate.jar'),fix='One shared valid/initialized-window guard, then unchanged original5s expression'),actualRootRuntime=False,windowVideoCoordinatesPendingSeparateOSDDelta=True,evidence=evidence)
save(L/'frozen-handoff.json',frozen);print('FROZEN',sha(L/'frozen-handoff.json'),'artifacts',len(evidence),'checks',len(checks))
