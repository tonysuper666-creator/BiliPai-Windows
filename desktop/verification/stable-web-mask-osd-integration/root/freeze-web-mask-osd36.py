from pathlib import Path
import hashlib,json,subprocess
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023'
OUT=REPO/'desktop/verification/stable-web-mask-osd-integration';assert not (OUT/'artifact-manifest.json').exists()
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def read(p):return wide(p).read_bytes()
def sha(b):return hashlib.sha256(b).hexdigest()
def pin(p,h):
 b=read(p);assert sha(b)==h,str(p);return b
rows=[];excluded=[]
def put(name,b):
 p=wide(OUT/name);p.parent.mkdir(parents=True,exist_ok=True);assert not p.exists();p.write_bytes(b)
 rows.append(dict(path=name,sha256Bytes=sha(b),sizeBytes=len(b)))
lanes=[('prepared/mask-core',REPO/'desktop/.local/stable-danmaku-web-mask-parity','frozen-handoff.json','5faef2a880cc1376185165edd4f71eefa09fb4711133ba7706a7bc79cfbd82c5'),
 ('prepared/osd-delta',REPO/'desktop/.local/stable-danmaku-native-osd-mask-delta','frozen-handoff.json','bb8d61645481ba0eba1eeb2c0069cff4f9fde1bc46bbd57d655b5e0b327029b2'),
 ('root/actual36-native',REPO/'desktop/.local/stable-danmaku-native-mask-output-proof','actual36-frozen-handoff.json','cbe90b11fa8d43fc74c8c7ddc5d8ee7cf00a805caa5eb7b51ef38eb28a5d059a')]
for label,lane,name,h in lanes:
 raw=pin(lane/name,h);put(label+'/'+name,raw);m=json.loads(raw)
 for r in m['evidence']:
  b=pin(lane/r['relative'],r['sha256Bytes'])
  if Path(r['relative']).suffix in ('.jar','.class','.kotlin_module'):
   excluded.append(dict(label=label,path=r['relative'],sha256Bytes=sha(b),sizeBytes=len(b),reason='Rebuildable fixture/isolated compiler output; not an installed binary'))
  else:put(label+'/'+r['relative'],b)
native=json.loads(read(lanes[2][1]/lanes[2][2]));assert native['nativeAssertions']==39 and native['productionClassOverrides']==0 and native['verifiedProductClassOrigins']==11
snap=MAIN/'desktop/.local/stable-product-snapshot-36'
put('root/snapshot36/manifest.json',pin(snap/'manifest.json','a445ed0f4944ba6ac581b06401576aec23638ef242aa1da442a6baead60b637d'))
cp=pin(snap/'ordered-runtime-cp.json','2fa3092ec985e32bf6b1b046266e6b84994adeeb023615549a9422eddb0aa493');put('root/snapshot36/ordered-runtime-cp.json',cp)
assert len(json.loads(cp))==97
for r in json.loads(cp):pin(r['path'],r['sha256Bytes'])
for folder in ('web-mask-osd-install',):
 for p in sorted(wide(HERE/folder).rglob('*'),key=str):
  if p.is_file():put('root/install/'+p.relative_to(wide(HERE/folder)).as_posix(),read(p))
for name in ('install-web-mask-osd.py','jvm-method-name-audit-36.json'):put('root/'+name,read(HERE/name))
for phase in (34,35,36):put(f'root/classes-{phase}.log',read(REPO/f'desktop/.local/stable-build-repair/classes-{phase}.log'))
paths=['desktop/src/main/kotlin/com/bilipai/desktop/ui/MediaScreens.kt','desktop/src/main/kotlin/com/bilipai/desktop/DesktopShell.kt']
for p in paths:
 put('root/pgc-source/base/'+p,subprocess.check_output(['git','show','HEAD:'+p],cwd=REPO))
 put('root/pgc-source/final/'+p,read(REPO/p))
put('root/pgc-source/required-community-owner.patch',subprocess.check_output(['git','diff','--no-ext-diff','--',*paths],cwd=REPO,stderr=subprocess.DEVNULL))
put('root/freeze-web-mask-osd36.py',read(__file__))
put('root/compiled-output-exclusions.json',(json.dumps(excluded,indent=2)+'\n').encode())
report=dict(targetTag='v0.2.3',wholeClassesPhase=36,wholeClassesPassed=True,sourceIdentityCount=833,resourceCount=213,
 originalFullWebMaskParserAndWindowProtocolCompiled=True,sameOverlayOwnerAndSharedMetadataConsumer=True,
 unchangedExistingNativePropertyPoll=True,realObservedViewportTransform=True,preparedMaskAssertions=43,preparedOsdAssertions=21,
 actualNativeOutputAndJava2DAssertions=39,actualProductClassOrigins=11,productionOverrides=0,actualRuntimeEntries=97,
 actualObservedScale=1.5,actualObservedDpi=144,actualFitCropResizeAndNativeRetirementAccepted=True,
 pgcMissingRequiredConsumerWired=True,pgcUsesExistingRootCommunity=True,emptyPugvBvidHasNoOptionalMask=True,
 originalUninitializedWindowOverflowNarrowGuard=True,original10s30s5sWindowExpressionsOtherwiseUnchanged=True,
 fullRootWindowScreenPaintingAccepted=False,realAccountMaskMetadataOrControllerNetworkAccepted=False,newExeDeployed=False,
 pending=['Actual Root transparent overlay final screen drawing and real smart-mask metadata','Original ByteDance compositor/AA and collision/special renderer closure','SCREEN_TOP independent portrait fullscreen and full Live queues','Complete Home embedded pages and Windows EXE delivery'])
put('integration-report.json',(json.dumps(report,indent=2)+'\n').encode())
raw=(json.dumps(dict(schema='raw-artifact-manifest-v1',artifacts=rows),indent=2)+'\n').encode();wide(OUT/'artifact-manifest.json').write_bytes(raw)
wide(OUT/'README.md').write_text('''# Original smart-mask and observed native video bounds

Install mask100 and OSD7 as one serial source combination on media74: 35 exact local hunks across10 product files and two new source identities. The original full MASK/gzip/base64/SVG parser, original getWebMask and four manager-window methods feed the existing Overlay with immutable BVID/CID/native source/account ownership. The original uninitialized Long.MIN_VALUE window subtraction overflows; a narrow initialized-window guard fixes it while retaining the10s/30s/5s window expressions. No unused local face-detector caller is fabricated.

Existing mpv property polling already observes video OSD dimensions/margins; the nullable viewport now preserves these values in the same source/revision receipt. Its single transform maps original mask coordinates into actual physical bounds, retaining real negative crop/pan margins and skipping masks while video bounds are unavailable. Load/stop clears the viewport. The original standard mask exclusion does not suppress authored advanced layers. PGC uses the existing Root community consumer with captured episode/native/epoch identity; a course without BVID has no optional metadata mask. Initial whole build found this missing required PGC consumer, then a missing coroutine import; both failed logs remain, followed by classes36 PASS.

Actual36/97 immutable runtime entries, zero product overrides and11 actual class origins pass39 real default-native HWND/MASK/Java2D/Advanced assertions. Actual150%/DPI144 observations include fit(0,107,900,506), crop(-190,0,1280,720) and tall resized(0,351,645,362), with real native decoded media, seek/load/replace/stop and worker recovery. Prepared core43 and OSD21 assertions remain separate historical source proofs. The isolated native window and Java2D image do not prove final Root transparent-overlay screen pixels, real account/WBI metadata, original ByteDance compositor identity or EXE delivery; all remain explicitly pending.
''',encoding='utf-8',newline='\n')
print(json.dumps(dict(rawArtifacts=len(rows),manifestSha256=sha(raw),actualNativeAssertions=39)))
