from pathlib import Path
import hashlib,json,re,sys,zipfile
L=Path(__file__).resolve().parent;ROOT=L.parents[2];MAIN=ROOT.parent/'BiliPai';SNAP=MAIN/'desktop/.local/stable-product-snapshot-36';RUN=L/'runs/actual36-01'
def safe(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix) else prefix+s)
def read(p):return safe(p).read_text(encoding='utf-8')
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def save(p,v):safe(p).write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
checks=[]
def require(label,condition):assert condition,label;checks.append(label)
require('actual36-Root-manifest',sha(SNAP/'manifest.json')=='a445ed0f4944ba6ac581b06401576aec23638ef242aa1da442a6baead60b637d')
require('actual36-Root-ordered97CP',sha(SNAP/'ordered-runtime-cp.json')=='2fa3092ec985e32bf6b1b046266e6b84994adeeb023615549a9422eddb0aa493')
require('actual36-Root-product-JAR',sha(SNAP/'main-kotlin.jar')=='4b348e570125dbc89df412b9eeb3797323258aac5a7671750064d18f4803c9f1')
cp=json.loads(read(SNAP/'ordered-runtime-cp.json'));require('strict97actualentries',len(cp)==97)
before=json.loads(read(RUN/'dependency-pins-before.json'));after=json.loads(read(RUN/'dependency-pins-after.json'))
require('actual-run-before-after-byte-receipts-identical',before==after)
require('zero-prospective-production-override',before['prospectiveCompileOnly']==[] and before['actualCP']==cp)
for row in cp+before['toolchain']+before['nativeProvenance']+before['localMedia']+[before['nativeDLL'],before['source'],before['runner']]:
 require('current-bytepin:'+row['path'],sha(row['path'])==row['sha256Bytes'])
result=json.loads(read(RUN/'proof/result.json'));accepted=json.loads(read(RUN/'accepted.json'));compiled=json.loads(read(RUN/'compile-result.json'));symbols=json.loads(read(RUN/'fixture-symbol-audit.json'));exitResult=json.loads(read(RUN/'runtime-exit.json'))
require('actual-native-runtime-terminal0',exitResult['exitCode']==0 and not exitResult['prospectiveOverlay'])
require('actual36-only-fixture-compiled',compiled['status']=='PASS' and compiled['mode']=='actual-run' and compiled['fixtureOnly'] and compiled['actualManifest']==sha(SNAP/'manifest.json') and compiled['prospectiveCompileOnly']==[])
require('only-11-test-classes-none-production',symbols['fixtureClassCount']==11 and symbols['productionClassOverrides']==0 and symbols['intersection']==[])
require('actual39native-assertions',result['status']=='PASS' and result['assertions']==39 and len(result['checks'])==39)
require('11actual-product-origins-verified',len(result['actualCodeSources'])==11 and accepted['actualClassOriginsVerified']==11)
require('no-Root-or-screen-acceptance-claimed',not result['RootComposeWindowMounted'] and not result['RootAccountMetadataOrSmartMaskActorAccepted'] and not result['nativeOverlayScreenPaintAccepted'] and not result['physicalMonitorPresentationObserved'])
require('real-default-hidden-HWND-decoded-frame',result['nativeHiddenAWTWindow'] and result['nativeDecodedFrameObserved'] and result['ordinaryNativeOutputAndActualJava2DClip'])
require('native-local-only-no-account',result['javaNetworkAttempts']==0 and result['nativeNetworkSources']==0 and not result['liveAccountRead'])
require('explicit-asymmetric-input-honest',result['asymmetricScaleIsExplicitFixtureInput'])
require('exact-runtime-result-bytepin',accepted['resultSHA256Bytes']==sha(RUN/'proof/result.json') and accepted['strictActualRuntimeEntries']==97)
with zipfile.ZipFile(safe(RUN/'native-osd-mask-fixture.jar')) as z:
 require('fixture-JAR-bytepin',sha(RUN/'native-osd-mask-fixture.jar')==symbols['fixtureJarSHA256Bytes'])
 require('only-dedicated-test-namespace',all(not n.endswith('.class') or n.startswith('com/bilipai/desktop/danmaku/nativeosdproof/') for n in z.namelist()))
with zipfile.ZipFile(safe(SNAP/'main-kotlin.jar')) as z:
 for row in result['actualCodeSources']:
  require('actual-class-bytepin:'+row['class'],hashlib.sha256(z.read(row['class'].replace('.','/')+'.class')).hexdigest()==row['classSha256Bytes'])
nativePhases=result['nativePhases'];require('3real-native-observed-geometries',len(nativePhases)==3 and [r['phase'] for r in nativePhases]==['native-fit','native-crop','native-tall-client'])
for row in nativePhases:
 require('real-positive-physical-client:'+row['phase'],row['canvas']['physicalWidth']>0 and row['canvas']['physicalHeight']>0 and row['nativeViewport']['osdWidth']==row['canvas']['physicalWidth'] and row['nativeViewport']['osdHeight']==row['canvas']['physicalHeight'])
 require('original-parser-viewBox:'+row['phase'],row['sourceViewBoxWidth']==160 and row['sourceViewBoxHeight']==90)
 require('standard-mask-and-later-original-glyphs:'+row['phase'],row['maskedPixels']>100 and row['unmaskedPixels']>100 and row['originalAdvancedPixelsInsideMask']>5)
require('real-negative-crop-preserved',nativePhases[1]['nativeViewport']['left']==-190 and nativePhases[1]['nativeViewport']['contentWidth']==1280)
require('real150percent-DPI144',all(r['canvas']['defaultScaleX']==1.5 and r['canvas']['defaultScaleY']==1.5 and r['canvas']['GetDpiForWindow']==144 for r in nativePhases))
require('real-decode-and-three-native-coordinate-images',safe(RUN/'proof/native-decoded-frame.png').is_file() and all(safe(RUN/('proof/'+r['phase']+'-java2d.png')).is_file() for r in nativePhases))
fixture=read(L/'NativeOsdMaskFixture.kt');runner=read(L/'run.py')
require('current-source-equals-compiled-frozen-copy',sha(L/'NativeOsdMaskFixture.kt')==sha(RUN/'source-inputs/NativeOsdMaskFixture.kt'))
for token in ['mpv_create(','mpv_initialize(','mpv_get_property','mpv_set_property','mpv_command(','Robot(','OkHttpClient(','ApiDesktopDanmakuSource(','DesktopSessionStore(','DesktopPluginStore(','getDeclaredField']:
 require('no-extra-client-or-fake-raw-output:'+token,token not in fixture)
require('one-existing-default-player',fixture.count('MpvPlayer(useNullAudioOutput=true)')==1 and 'MpvSoftwareTarget(' not in fixture and 'startSoftwareTransport(' not in fixture)
require('full-original-parser-and-existing-later-renderer','WebMaskParser.parseWindow' in fixture and 'AdvancedDanmakuRenderer(authored.advanced).paint' in fixture)
require('existing-production-carrier-and-clip','applyDesktopWebMaskClip(standard,width,height,frame,v)' in fixture and 'frame.path.transformedArea(v.sourceToPhysicalTransform' in fixture)
require('all-native-lifecycle-with-existing-actor',all(s in fixture for s in ['loadVersioned(','seekToTracked(','stopIfSourceVersion(','ownsSourceVersion(','player.close()','player.videoOutput.collect']))
require('hidden-private-window-required','check(isDisplayable&&!isVisible)' in fixture and 'GetClientRect' in fixture and 'GetDpiForWindow' in fixture)
require('strict-runtime97-and-no-override-admission','assert options.entries==97' in runner and 'No prospective production overlay allowed at native runtime' in runner)
for lane,pin in [(L.parent/'stable-danmaku-web-mask-parity','5faef2a880cc1376185165edd4f71eefa09fb4711133ba7706a7bc79cfbd82c5'),(L.parent/'stable-danmaku-native-osd-mask-delta','bb8d61645481ba0eba1eeb2c0069cff4f9fde1bc46bbd57d655b5e0b327029b2'),(MAIN/'desktop/.local/stable-home-platform-media-parity','9b9ced6df9540109469a25da9917407126e97bdce5656688b04627f2f05fe966')]:
 require('historical-packet-unchanged:'+lane.name,sha(lane/'frozen-handoff.json')==pin)
save(L/'actual36-source-audit.json',dict(status='PASS',checks=checks,count=len(checks),actualRuntimeEntries=97,productionClassOverrides=0,nativeSourceVersionsAndPhysicalGeometryActual=True,rootWindowAccepted=False))
contract=dict(status='PASS_ACTUAL36_NATIVE_OUTPUT_JAVA2D',actualSnapshot=str(SNAP),actualManifest=sha(SNAP/'manifest.json'),actualCP=sha(SNAP/'ordered-runtime-cp.json'),strictActualRuntimeEntries=97,nativeAssertions=39,productClassOrigins=11,productionClassOverrides=0,nativeDLL=before['nativeDLL'],nativeLocalMedia=before['localMedia'],observedNativePhases=nativePhases,observedFirstNativeReceiptElapsedNanos=result['firstNativeReceiptElapsedNanos'],observedFirstReceiptIsBenchmark=False,accepted=sha(RUN/'accepted.json'),sourceAudit=sha(L/'actual36-source-audit.json'),productionPatchCount=0,sharedGradleInvoked=False,actual= ['Installed default HWND/native Session observes genuine OSD bounds at actual150% device scaling','Non-video-aspect hidden client letterbox and real negative crop/pan margins','Native decoded local frame, original full MASK parser/SVG carrier and same Java2D standard-only clip','Existing authored Advanced renderer remains visible inside standard mask exclusion','Load/replace/seek/source retirement/stop empty output and native worker teardown'],notAccepted=['Root ComposeWindow/SwingPanel actual layout and final screen presentation','Real B station/WBI/account metadata/controller mask owner network acceptance','Native transparent overlay pixel-on-screen clipping/physical monitor first-frame timing','Original ByteDance compositor/AA identity','Asymmetric user monitor; asymmetric values only explicit fixture input','SCREEN_TOP/independent portrait fullscreen/full original special and Live renderer closure'],historicalPreparationReceiptsAreSupersededNotRuntimeAuthorities=['prepared-handoff.json','prepared-handoff-02.json','prepared-handoff-03.json'])
save(L/'actual36-contract.json',contract)
evidence=[dict(relative=p.relative_to(safe(L)).as_posix(),path=str(p),sha256Bytes=sha(p)) for p in sorted(safe(L).rglob('*')) if p.is_file() and p.name!='actual36-frozen-handoff.json']
target=L/'actual36-frozen-handoff.json';require('no-earlier-terminal-cohort-overwrite',not target.exists())
save(target,dict(status=contract['status'],actual36Manifest=contract['actualManifest'],actual97CP=contract['actualCP'],nativeAssertions=39,verifiedProductClassOrigins=11,productionClassOverrides=0,productionPatchCount=0,scope=contract['actual'],notAccepted=contract['notAccepted'],contract=sha(L/'actual36-contract.json'),sourceAudit=sha(L/'actual36-source-audit.json'),accepted=sha(RUN/'accepted.json'),evidence=evidence))
print('FROZEN_ACTUAL36',sha(target),'artifacts',len(evidence),'checks',len(checks))
