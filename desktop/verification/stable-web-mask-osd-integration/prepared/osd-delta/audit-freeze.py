from pathlib import Path
import hashlib,json,re,struct,sys,zipfile
sys.dont_write_bytecode=True
L=Path(__file__).resolve().parent;ROOT=L.parents[2];MAIN=ROOT.parent/'BiliPai';SNAP=MAIN/'desktop/.local/stable-product-snapshot-31';MASK=L.parent/'stable-danmaku-web-mask-parity';MEDIA=MAIN/'desktop/.local/stable-home-platform-media-parity'
def safe(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix) else prefix+s)
def read(p):return safe(p).read_text(encoding='utf-8').replace('\r\n','\n')
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def textsha(s):return hashlib.sha256(s.encode()).hexdigest()
def save(p,v):safe(p).write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
checks=[]
def require(label,yes):assert yes,label;checks.append(label)
require('actual31-immutable-manifest',sha(SNAP/'manifest.json')=='999bc989c995f877761b22c5c00a6a0d99819100d8cef591a25f94b5d56a3a5d')
require('mask100-independent-unchanged',sha(MASK/'frozen-handoff.json')=='5faef2a880cc1376185165edd4f71eefa09fb4711133ba7706a7bc79cfbd82c5')
require('media74-independent-unchanged',sha(MEDIA/'frozen-handoff.json')=='9b9ced6df9540109469a25da9917407126e97bdce5656688b04627f2f05fe966')
actualInputs={r['path']:r['sha256Bytes'] for r in json.loads(read(SNAP/'manifest.json'))['inputs']}
versions={};base=json.loads(read(L/'baseline-manifest.json'))
require('four-complete-baselines',len(base)==4)
for row in base:
 path=row['path'];s=read(L/'baseline'/path);require('baselineLF:'+path,textsha(s)==row['sha256LF']);versions[path]=s
 require('frozen-source-identical:'+path,read(row['source'])==s)
 if path.endswith('/PlayerVideoOutput.kt'):require('model-actual31-raw-source-pin',sha(row['source'])==actualInputs[path])
hunks=json.loads(read(L/'local-hunks.json'));require('seven-local-hunks',len(hunks)==7)
for i,r in enumerate(hunks):
 s=versions[r['path']];require('hunk-before:'+str(i),textsha(s)==r['beforeSha256LF']);require('strict-required-count:'+str(i),s.count(r['old'])==r['requiredOldCount']);s=s.replace(r['old'],r['new']);versions[r['path']]=s;require('hunk-after:'+str(i),textsha(s)==r['afterSha256LF'])
for path,s in versions.items():require('whole-review-only-equals-exact-hunks:'+path,s==read(L/'review-only'/path))
mpvPath='desktop/src/main/kotlin/com/bilipai/desktop/player/MpvPlayer.kt';mpv=versions[mpvPath];mpvBase=read(L/'baseline'/mpvPath)
require('media74-canonical-LF',textsha(mpvBase)=='8e4ac49b91d7279c10ddd5c8c6e8a045ed9d11f1f6d26b773f6322f1cfa0e6c5')
propertyPattern=r'property\(native, handle, "[^\"]+"\)'
require('all-native-property-calls-identical-no-new-poll',re.findall(propertyPattern,mpv)==re.findall(propertyPattern,mpvBase))
require('same-poll-osd-w-h-ml-mr-mt-mb',all('"osd-dimensions/'+x+'"' in mpvBase for x in ['w','h']) and '"osd-dimensions/$name"' in mpvBase and all('margin("'+x+'")' in mpvBase for x in ['ml','mr','mt','mb']))
require('same-session-sourceVersion-playbackRevision-admission','if (session === this && sourceVersion == activeSourceVersion && playbackRevision == activeRevision)' in mpv)
require('two-original-reset-sites-clear-rect',mpv.count('displayWidth = 0, displayHeight = 0, viewport = null, gamma = null')==2)
require('single-same-cycle-rect-publication',mpv.count('viewport = videoViewport')==1)
require('all-six-observations-required-no-fit-guess',all(x in mpv for x in ['osdWidth!=null&&osdWidth>0','osdHeight!=null&&osdHeight>0','marginLeft!=null','marginTop!=null','marginRight!=null','marginBottom!=null','displayWidth>0&&displayHeight>0','PlayerVideoViewport(osdWidth,osdHeight,marginLeft,marginTop,displayWidth,displayHeight) else null']))
mpvOriginal=read(MEDIA/'base/MpvPlayer.kt')
require('media74-original-base-LF',textsha(mpvOriginal)=='86863c8be2ab2731d2a0ff16219b5c237ff298a16992bac3b0aa5b76ef9bf51e')
def pollBody(s):
 a=s.index('            val osdWidth =');b=s.index('\n        private fun',a) if '\n        private fun' in s[a:] else s.index('\n        fun',a)
 return s[a:b]
require('media74-did-not-modify-original-osd-poll',pollBody(mpvBase)==pollBody(mpvOriginal))
model=versions['desktop/src/main/kotlin/com/bilipai/desktop/player/PlayerVideoOutput.kt']
require('nullable-native-not-ready-port','val viewport:PlayerVideoViewport? = null' in model)
require('one-authoritative-transform',model.count('fun sourceToPhysicalTransform(')==1 and all(x in model for x in ['width.toDouble()/osdWidth','height.toDouble()/osdHeight','translate(left.toDouble(),top.toDouble())','contentWidth.toDouble()/sourceWidth','contentHeight.toDouble()/sourceHeight']))
require('negative-margins-not-clamped','require(osdWidth>0&&osdHeight>0&&contentWidth>0&&contentHeight>0)' in model and 'left.coerce' not in model and 'top.coerce' not in model)
carrier=versions['desktop/src/main/kotlin/com/bilipai/desktop/danmaku/DesktopWebMaskPath.kt']
require('same-native-temporaries-closed','SkiaPath.makeFromSVGString(data).use' in carrier and 'native.iterator().use' in carrier)
require('same-cache-carrier-budgets','MAX_PATH_COMMANDS=20_000' in carrier and carrier.count('private val path =')==1)
require('actual-rect-required-optional-mask','if (frame == null || videoViewport == null || width <= 0 || height <= 0) return' in carrier)
require('canvas-area-subtracts-only-actual-video-mapped-path','visible.subtract(frame.path.transformedArea(videoViewport.sourceToPhysicalTransform(width,height,frame.sourceWidth,frame.sourceHeight)))' in carrier)
overlay=versions['desktop/src/main/kotlin/com/bilipai/desktop/danmaku/DanmakuOverlay.kt']
require('same-native-output-source-and-live-owner','player.videoOutput.value.takeIf {it.sourceVersion==poolSourceVersion && poolSourceVersion?.let(player::ownsSourceVersion)==true}?.viewport' in overlay)
require('same-current-mask-owner','currentWebMaskFrame((displayTime*1000).toLong())' in overlay)
require('single-physical-standard-child-not-later-ui',overlay.count('applyDesktopWebMaskClip(')==1 and overlay.index('applyDesktopWebMaskClip(')<overlay.index('advancedRenderer.paint(context'))
for token in ['CoroutineScope(','SupervisorJob(','OkHttpClient(','DesktopPluginStore(','DesktopSessionStore(','getMonitor','getGraphicsDevice']:
 require('no-additional-authority:'+token,sum(s.count(token) for s in versions.values())==sum(read(L/'baseline'/p).count(token) for p in versions))
result=json.loads(read(L/'compile-02/compile-result.json'));require('compiled-real-actual31-plus-declared-prospective-only',result['status']=='PASS' and result['actualWholeSnapshot']==sha(SNAP/'manifest.json') and result['newClassOverlapZero'] and not result['nativeMpvExecuted'])
for r in result['sourceInputs']:require('compiled-source-current:'+r['path'],sha(r['path'])==r['sha256Bytes'])
proof=read(L/'compile-02/proof-result.txt');require('actual4groups21assertions','status=PASS\ngroups=4\nassertions=21\n' in proof)
pins=json.loads(read(L/'compile-02/ordered-cp-receipt.json'));require('92actual-and-two-declared-frozen-prospective',len(pins)==94)
for r in pins:require('ordered-CP-pin:'+r.get('source',r['path']),sha(r['path'])==r['sha256Bytes'])
code=read(L.parent/'stable-danmaku-render-topmethod-audit/audit.py');namespace=dict(globals());exec(code[code.index('def static_api'):code.index('assert sha(ADV')],namespace)
own=namespace['facade_api'](L/'compile-02/native-osd-mask-candidate.jar');packages={r['package']+'/' for r in own};existing=[]
for r in pins:existing+=namespace['facade_api'](r['path'],packages)
allowed={'com/bilipai/desktop/player/PlayerVideoOutputKt.class','com/bilipai/desktop/danmaku/DesktopWebMaskPathKt.class'};unexpected=[]
for r in own:
 if r['facade'] in allowed or r['facade'].endswith('OsdMaskProofKt.class'):continue
 matches=[e for e in existing if (e['package'],e['kind'],e['member'])==(r['package'],r['kind'],r['member'])]
 if matches:unexpected.append(dict(candidate=r,existing=matches))
require('new-topmethod-name-overlap-zero',not unexpected)
save(L/'topmethod-receipt.json',dict(status='PASS',candidateJar=sha(L/'compile-02/native-osd-mask-candidate.jar'),declaredExistingFacadeOverrides=sorted(allowed),candidateApi=own,existingApiCount=len(existing),unexpected=[]))
save(L/'source-audit.json',dict(status='PASS',sourceOnly=True,checks=checks,count=len(checks),allWholeReviewFilesExactResult=True,media74OsdPollUnchanged=True,additionalNativePropertyCalls=0,newProducer=0,newDependency=0))
contract=dict(status='READY_SOURCE_ONLY',fixedTagCommit='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589',payloadCount=0,localHunkCount=7,localHunksSHA256=sha(L/'local-hunks.json'),installOrder=['Root media74 canonical exact local hunks','Root mask100 canonical payloads and exact local hunks','OSD7 local hunks only after exact before/after/count verification','Root whole compile and actual same-window native video/OSD/mask acceptance'],wholeReviewFilesAreInstallPayloads=False,registryDelta=[],dependencyDelta=[],generatedProducerDelta=[],authority='Existing Mpv Session native poll/output/sourceVersion/playbackRevision; same Overlay mask/source/account owner; one observed OSD-to-physical transform',requiredPort='PlayerVideoOutputState.viewport:PlayerVideoViewport? observed in the same native poll. Absent/not-ready/retired clears or skips mask; no guessed fit fallback.',negativeMargins='Native crop/pan ml/mt may be negative and remain exact; existing physical canvas clips mapped shape.',proof=dict(groups=4,assertions=21,actualSkiaAndJava2D=True,nativeMpv=False,actualRootWindow=False,inputsAreExplicitObservations=True),pending=['Actual Root same-window native OSD observation and final mask painting acceptance','Original engine compositor/AA pixel identity','SCREEN_TOP layout, independent portrait-fullscreen UI, original full special/Live renderer queue closure'],historicalFreezesUnmodified=[sha(MASK/'frozen-handoff.json'),sha(MEDIA/'frozen-handoff.json')])
save(L/'install-contract.json',contract)
evidence=[]
for f in sorted(safe(L).rglob('*')):
 if f.is_file() and f.name!='frozen-handoff.json':evidence.append(dict(relative=f.relative_to(safe(L)).as_posix(),path=str(f),sha256Bytes=sha(f)))
frozen=dict(status='READY_SOURCE_ONLY',fixedCommit=contract['fixedTagCommit'],localHunkCount=7,payloadCount=0,actual31Manifest=sha(SNAP/'manifest.json'),mask100Manifest=sha(MASK/'frozen-handoff.json'),media74Manifest=sha(MEDIA/'frozen-handoff.json'),compileResult=sha(L/'compile-02/compile-result.json'),sourceAudit=sha(L/'source-audit.json'),topMethods=sha(L/'topmethod-receipt.json'),installContract=sha(L/'install-contract.json'),proof=contract['proof'],actualRootRuntime=False,nativeMpvExecuted=False,evidence=evidence)
save(L/'frozen-handoff.json',frozen);print('FROZEN',sha(L/'frozen-handoff.json'),'artifacts',len(evidence),'checks',len(checks))
