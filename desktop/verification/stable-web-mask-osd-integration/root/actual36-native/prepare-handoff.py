from pathlib import Path
import hashlib,json,sys
L=Path(__file__).resolve().parent;ROOT=L.parents[2];MAIN=ROOT.parent/'BiliPai';MEDIA=MAIN/'desktop/.local/stable-home-platform-media-parity'
def safe(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix) else prefix+s)
def read(p):return safe(p).read_text(encoding='utf-8')
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def save(p,v):safe(p).write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
source=read(L/'NativeOsdMaskFixture.kt');runner=read(L/'run.py')
compileResult=json.loads(read(L/'runs/compile-02/compile-result.json'));symbols=json.loads(read(L/'runs/compile-02/fixture-symbol-audit.json'))
assert compileResult['status']=='PASS' and compileResult['fixtureOnly'] and not compileResult['nativeExecuted']
assert symbols['productionClassOverrides']==0 and not symbols['intersection']
for forbidden in ['mpv_create(','mpv_initialize(','mpv_get_property','mpv_set_property','mpv_command(','Robot(','OkHttpClient(','ApiDesktopDanmakuSource(','DesktopSessionStore(','DesktopPluginStore(','setAccessible','getDeclaredField']:
 assert forbidden not in source,forbidden
assert source.count('MpvPlayer(useNullAudioOutput=true)')==1
assert 'startSoftwareTransport(' not in source and 'MpvSoftwareTarget(' not in source
assert 'no prospective production overlay allowed' not in source # Runtime admission belongs to runner, not a fake product seam.
assert 'No prospective production overlay allowed at native runtime' in runner
assert 'addNotify();validate();check(isDisplayable&&!isVisible)' in source
assert 'class OsdFixtureRect' in source and 'GetClientRect' in source and 'GetDpiForWindow' in source
assert 'player.videoOutput.collect' in source and 'player.videoOutput.value.viewport' in source
assert 'AdvancedDanmakuRenderer(authored.advanced).paint' in source and 'WebMaskParser.parseWindow' in source
assert 'applyDesktopWebMaskClip(standard,width,height,frame,v)' in source
headers=json.loads(read(MEDIA/'official-mpv-headers.json'))
client=next(r for r in headers if r['path'].endswith('client.h'));assert sha(client['path'])==client['sha256Bytes']
assert client['commit']=='69e63f425a531f814431fba12750bdb3721357f2'
header=read(client['path']);assert 'MPV_EVENT_FILE_LOADED       = 8' in header and 'MPV_EVENT_PLAYBACK_RESTART  = 21' in header
reused=[
 (ROOT/'desktop/src/test/kotlin/com/bilipai/desktop/danmaku/AdvancedDanmakuTest.kt','Existing full mode7/9 authored parser/frame/paint and no executable mode8; same Advanced renderer reused after standard mask child.'),
 (ROOT/'desktop/src/test/kotlin/com/bilipai/desktop/player/PlayerVideoOutputTest.kt','Existing exact native capability, sourceVersion/shader ownership, source-scoped reset and no setter-as-observation semantics.'),
 (ROOT/'desktop/src/main/kotlin/com/bilipai/desktop/player/DesktopOverlayNativeSmoke.kt','Existing transparent default-native HWND/surface and standard-vs-later layers, lifecycle; Robot/screen proof deliberately not invoked by this runner.'),
 (MAIN/'desktop/.local/stable-danmaku-render-main31-proof/actual-hidden-window/ActualMonitorWindowProof.kt','Existing private hidden AWT peer and actual graphicsConfiguration/device/defaultTransform/physical short-side measurement.'),
 (MAIN/'desktop/.local/stable-home-native-media-proof/NativeHomeMediaFixture.kt','Existing actual default actor state receipt/loadVersioned/seek/stop/teardown contracts, file-only fencing and actual CP origin receipts; software/Home actor not created in new runner.'),
 (L.parent/'stable-danmaku-web-mask-parity/proof/WebMaskProof.kt','Existing MASK header+gzip+base64 fixture encoding only; production full original parser reused, not rewritten.'),
]
origins=[dict(path=str(p),sha256Bytes=sha(p),reuse=reason) for p,reason in reused]
contract=dict(status='READY_FIXTURE_SOURCE_ONLY_ACTUAL_GRAPH_PENDING',fixedBiliPaiCommit='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589',fixedMpvCommit=client['commit'],nativeDLLSHA256='673e6397920ab64a9c5b3a618f7f16d38854efe72b58665f1f84e4e873b763a4',fixtureSource=dict(path=str(L/'NativeOsdMaskFixture.kt'),sha256Bytes=sha(L/'NativeOsdMaskFixture.kt')),runner=dict(path=str(L/'run.py'),sha256Bytes=sha(L/'run.py')),prospectiveCompileReceipt=sha(L/'runs/compile-02/compile-result.json'),fixtureSymbolAudit=sha(L/'runs/compile-02/fixture-symbol-audit.json'),fixtureProductionClassOverlap=0,nativeExecuted=False,strictActualRuntimeEntries=97,requiredActualAdmission=['Root-provided installed snapshot directory, manifest byteSHA and ordered97CP byteSHA; count strictly97','11 required actual class FQNs must occur uniquely in sole product main-kotlin.jar','Every actual CP/JDK/DLL/media/fixture input pinned; JVM runtime permits zero prospective production override','Native local clips fixed by existing actual33 media pins; no generation/download/account'],minimumPlatformCalls=['MpvPlayer(useNullAudioOutput=true); same existing surface Canvas.addNotify()/default Session attach','Private hidden JFrame real peer/client size, User32.GetClientRect/GetDpiForWindow, existing graphicsConfiguration.defaultTransform/device.displayMode','loadVersioned(localPath); existing state native first-frame receipt+videoOutput.viewport+ownsSourceVersion','Existing actor captureScreenshot for decoded source pixels (no Robot/desktop screenshot)','setVideoPanscan/seekToTracked/resize/current stopIfSourceVersion/close','Original full WebMaskParser→same DesktopWebMaskPath→applyDesktopWebMaskClip→existing AdvancedDanmakuRenderer'],cannotClaim=['Main Root ComposeWindow/SwingPanel bounds/layout acceptance','Real account/WBI metadata HTTP, source-controller mask actor acceptance','Physical monitor first frame or native transparent overlay final screen pixels','An asymmetric user monitor: asymmetric/fractional test values are explicit fixture inputs only','Original ByteDance compositor/AA identity, SCREEN_TOP or portrait/fullLive/special renderer closure'],reusedExistingTests=origins,officialClientHeader=client)
save(L/'platform-contract.json',contract)
evidence=[dict(relative=str(p.relative_to(safe(L))).replace('\\','/'),path=str(p),sha256Bytes=sha(p)) for p in sorted(safe(L).rglob('*')) if p.is_file() and p.name!='prepared-handoff.json']
target=L/(sys.argv[1] if len(sys.argv)>1 else 'prepared-handoff.json')
assert not target.exists(),'Do not overwrite earlier preparation receipt'
save(target,dict(status=contract['status'],nativeExecuted=False,productionClassOverrides=0,platformContract=sha(L/'platform-contract.json'),prospectiveCompile=contract['prospectiveCompileReceipt'],evidence=evidence))
print('PREPARED',sha(target),'artifacts',len(evidence))
