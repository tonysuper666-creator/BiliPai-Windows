from pathlib import Path
import hashlib,json,zipfile
L=Path(__file__).resolve().parent;ADV=L.parent/'stable-danmaku-render-config-consumers-parity';MAIN=L.parents[3]/'BiliPai'
def safe(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix) else prefix+s)
def read(p):return safe(p).read_text(encoding='utf-8').replace('\r\n','\n')
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def textsha(s):return hashlib.sha256(s.encode()).hexdigest()
def save(p,v):safe(p).write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
checks=[]
def require(label,yes):assert yes,label;checks.append(label)
require('prior213-unchanged',sha(ADV/'frozen-handoff.json')=='6d221c053309abd218b55a129634726bf5f99f28d4458a374069351468b69103')
base=json.loads(read(L/'baseline-manifest.json'));versions={}
for r in base:require('baseline:'+r['path'],textsha(read(L/'baseline'/r['path']))==r['sha256LF']);versions[r['path']]=read(L/'baseline'/r['path'])
hunks=json.loads(read(L/'local-hunks.json'))
for r in hunks:
 s=versions[r['path']];require('before:'+str(len(checks)),textsha(s)==r['beforeSha256LF']);require('exact-one:'+str(len(checks)),s.count(r['old'])==1);s=s.replace(r['old'],r['new']);versions[r['path']]=s;require('after:'+str(len(checks)),textsha(s)==r['afterSha256LF'])
for p,s in versions.items():
 folder='review-only-callers' if p.endswith('/DesktopVideoVotes.kt') else 'review-only';require('whole-review-proved:'+p,s==read(L/folder/p))
platform=versions[next(p for p in versions if p.endswith('/DesktopOriginalDanmakuRenderPlatform.kt'))];overlay=versions[next(p for p in versions if p.endswith('/DanmakuOverlay.kt'))];votes=versions[next(p for p in versions if p.endswith('/DesktopVideoVotes.kt'))]
require('no360-production-default','360f' not in platform+overlay+votes)
require('required-reference','referenceShortSidePx:Float' in platform and 'fun maximumDisplayShortSidePx():Float' in platform)
require('actual-root-monitor-mode','rootWindow().graphicsConfiguration' in platform and '.device.displayMode' in platform and 'mode.width>0 && mode.height>0' in platform)
require('no-window-video-aspect-inference',all(x not in platform for x in ['window.width','window.height','videoWidth','videoHeight']))
require('same-overlay-command-getter','danmaku.maximumDisplayShortSidePx()' in votes and 'internal fun maximumDisplayShortSidePx():Float=renderPlatform.maximumDisplayShortSidePx()' in overlay)
require('one-paint-physical-reference',overlay.count('DesktopDanmakuPaintGeometry.from(')==1)
policy=json.loads(read(L/'passive-default-source-evidence.json'));require('all5passive-calls-pinned',len(policy['allCallsites'])==5 and len({x['path'] for x in policy['allCallsites']})==4)
for r in json.loads(read(L/'source-inventory.json')):require('full-original-fixed-source:'+r['path'],sha(r['retained'])==r['sha256Bytes'] and r['commit']=='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589')
require('windows-default-stays-passive','focusableWindowState = false' in overlay and 'isAutoRequestFocus = false' in overlay and 'style or 0x00080000 or 0x00000020 or 0x08000000' in overlay)
require('no-pointer-interception-added','MouseListener' not in overlay and 'WindowProc' not in overlay)
compile=json.loads(read(L/'compile-01/compile-result.json'));proof=json.loads(read(L/'compile-01/proof-result.json'));require('compiled2+1-pass',compile['status']=='PASS' and compile['productionSources']==2 and compile['proofSources']==1);require('12proof-pass',proof['status']=='PASS' and proof['assertions']==12 and proof['actualRootMonitorRuntimeAccepted']==False)
for r in compile['sourceInputs']:require('compiled-current:'+r['path'],sha(r['path'])==r['sha256Bytes'])
jar=L/'compile-01/monitor-candidate-and-fixture.jar'
with zipfile.ZipFile(safe(jar)) as z:classes={n for n in z.namelist() if n.endswith('.class') and 'MonitorViewportProof' not in n}
with zipfile.ZipFile(safe(ADV/'compile-06/original-danmaku-render-config-consumers.jar')) as z:prior={n for n in z.namelist() if n.endswith('.class')}
require('existing-families-only',classes<=prior)
save(L/'source-audit.json',dict(status='PASS',checks=checks,checkCount=len(checks),localHunks=9,onlyExistingClasses=sorted(classes),newClassFQNCount=0,actualRootRuntimeAccepted=False))
evidence=[]
for p in sorted(safe(L).rglob('*')):
 if p.is_file() and p.name!='frozen-handoff.json':evidence.append(dict(relative=p.relative_to(safe(L)).as_posix(),path=str(p),sha256Bytes=sha(p)))
frozen=dict(status='READY_SOURCE_ONLY_LOCAL_DELTA',fixedCommit='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589',priorAdvanced213Manifest=sha(ADV/'frozen-handoff.json'),localHunkCount=9,localHunksSHA256=sha(L/'local-hunks.json'),onlyExistingThreeRootSourceFiles=True,payloadCount=0,fullOverwritesAuthorized=False,newRegistryOrDependencyCount=0,compiledProductionSources=2,compiledProofSources=1,compileResultSHA256=sha(L/'compile-01/compile-result.json'),proof=proof,passiveDefaultWholePolicyAndAllCallsites=policy,sourceAuditCheckCount=len(checks),actualFinal31BuildOwner='Root',actualRootMonitorRuntimeAccepted=False,historical213Fixture360NotRuntimeProof=True,evidence=evidence)
save(L/'frozen-handoff.json',frozen);print('FROZEN',sha(L/'frozen-handoff.json'),'artifacts',len(evidence),'checks',len(checks),'9hunks 12asserts')
