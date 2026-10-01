from pathlib import Path
import hashlib,importlib.util,json,subprocess,sys,zipfile
sys.stdout.reconfigure(encoding='utf-8',errors='replace');sys.dont_write_bytecode=True
H=Path(__file__).resolve().parent;MAIN=H.parents[2];S=MAIN/'desktop/.local/stable-product-snapshot-70'
def wide(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix)else prefix+s)
def data(p):return wide(p).read_bytes()
def sha(p):return hashlib.sha256(data(p)).hexdigest()
def row(p):return dict(path=str(p),size=len(data(p)),sha256Bytes=sha(p))
def write(p,t):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes(t.encode()if isinstance(t,str)else t)
def dump(p,v):write(p,json.dumps(v,ensure_ascii=False,indent=2)+'\n')
assert sha(S/'manifest.json')=='f1e410523c6991a1f588ffc4e1d571f1251c81f6ab2b2d25ed9c947f9be03621'
assert sha(S/'ordered-runtime-cp.json')=='3e2c79ad73fd69d07025ab12d617d8aa9932cef580ef1046e3550458b8482aba'
cp=json.loads(data(S/'ordered-runtime-cp.json'));assert len(cp)==101
out=H/'runs'/sys.argv[1];wide(out).mkdir(parents=True,exist_ok=False)
def pins():
 for r in cp:assert sha(r['path'])==r['sha256Bytes']
 return [row(r['path'])for r in cp]
before=pins();dump(out/'pins-before.json',before)
sources=list((H/'prepared/manual').rglob('*.kt'))+list((H/'prepared/existing/desktop/src').rglob('*.kt'))
sources += [H/'generated/full-owner/com/android/purebilibili/feature/video/viewmodel/VideoPlaybackViewModel.kt',H/'generated/full-owner/com/android/purebilibili/feature/plugin/CdnDashSegmentPrefetcher.kt']
sources += [H/'generated/portrait/com/android/purebilibili/feature/video/ui/pager/PortraitVideoPager.kt',H/'generated/portrait/com/android/purebilibili/feature/video/ui/pager/PortraitVideoLoadPolicy.kt']
intent=MAIN/'desktop/.local/stable-video-media-intent-parity'
assert sha(intent/'frozen-handoff.json')=='8357e7cdf392d34811330c10a722ef5133abd109a3b6e5767bad5a5c92806a82'
sources += [intent/'prepared/manual/com/bilipai/desktop/ui/DesktopOriginalVideoMediaIntent.kt',intent/'prepared/existing/desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalVideoPlaybackUseCaseEnvironment.kt']
fixture=H/'fixture/CapturedCacheConsumersProof.kt'
if fixture.exists():sources.append(fixture)
copied=[]
for i,p in enumerate(sources):
 dest=out/'inputs'/str(i)/p.name;write(dest,data(p));copied.append(dest)
dump(out/'inputs.json',[dict(source=row(p),copied=row(c))for p,c in zip(sources,copied)])
spec=importlib.util.spec_from_file_location('cc',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');cc=importlib.util.module_from_spec(spec);spec.loader.exec_module(cc)
args=['-no-stdlib','-no-reflect','-jvm-target','21','-module-name','com_bilipai_desktop_bilipai_windows','-Xplugin='+str(cc.PLUGIN),'-Xfriend-paths='+cp[1]['path'],'-cp',';'.join(r['path']for r in cp),'-d',str(out/'classes')]+list(map(str,copied))
write(out/'compiler.args','\n'.join('"'+a.replace('\\','/')+'"'for a in args))
command=[str(cc.JAVA),'-Dfile.encoding=UTF-8','-Xmx3g','-cp',';'.join(map(str,cc.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(out/'compiler.args')]
dump(out/'compiler-command.json',command)
r=subprocess.run(command,capture_output=True,encoding='utf-8',errors='replace',timeout=180);write(out/'compile.log',r.stdout+r.stderr)
dump(out/'compile-result.json',dict(exit=r.returncode,snapshot=70,entries=101,explicitPreparedSourceInputs=len(sources),RootInstalledAcceptance=False))
if r.returncode:print(r.stdout+r.stderr);dump(out/'pins-after.json',pins());sys.exit(r.returncode)
own={p.relative_to(wide(out/'classes')).as_posix()for p in wide(out/'classes').rglob('*.class')};actual=set()
for entry in cp:
 with zipfile.ZipFile(wide(entry['path']))as z:actual.update(z.namelist())
overlap=sorted(own&actual)
allowed=['com/bilipai/desktop/player/cache/DesktopMediaByte','com/bilipai/desktop/player/cache/DesktopMediaResource','com/bilipai/desktop/player/cache/DesktopBoundMediaByteCache','com/bilipai/desktop/player/cache/DesktopNativeMediaTransport','com/bilipai/desktop/player/cache/DesktopMediaOriginHeaders','com/bilipai/desktop/ui/DesktopOriginalVideoNativeOwner','com/bilipai/desktop/ui/DesktopOriginalVideoInitialPublication','com/bilipai/desktop/ui/DesktopOrdinary','com/bilipai/desktop/ui/DesktopOriginalVideoAcceptedPublication','com/bilipai/desktop/ui/DesktopOriginalVideoRepositoryBinding','com/bilipai/desktop/ui/DesktopOriginalVideoPlaybackOwnerEnvironment','com/bilipai/desktop/ui/DesktopOriginalCdnRangeCache','com/bilipai/desktop/ui/DesktopOriginalVideoOwner','com/bilipai/desktop/ui/DesktopOriginalPortrait','com/bilipai/desktop/ui/DesktopOriginalVideoMediaPort','com/bilipai/desktop/ui/DesktopOriginalVideoPlaybackUseCaseEnvironment','com/bilipai/desktop/ui/DesktopOriginalVideoPlaybackCapabilities','com/bilipai/desktop/ui/DesktopOriginalVideoLoadRepository','com/bilipai/desktop/ui/DesktopOriginalVideoInitialActions','com/bilipai/desktop/ui/DesktopOriginalVideoProgressPort','com/android/purebilibili/feature/video/viewmodel/VideoPlaybackViewModel','com/android/purebilibili/feature/plugin/CdnDash','com/android/purebilibili/feature/video/ui/pager/PortraitVideoLoadPolicy','com/android/purebilibili/feature/video/ui/pager/PortraitPlayback','com/android/purebilibili/feature/video/ui/pager/PortraitPage','com/android/purebilibili/feature/video/ui/pager/PortraitResolved','com/android/purebilibili/feature/video/ui/pager/PortraitParsed','com/android/purebilibili/feature/video/ui/pager/PortraitVideoPager','com/android/purebilibili/feature/video/ui/pager/PortraitQuality','com/android/purebilibili/feature/video/ui/pager/PortraitPrefetch','com/android/purebilibili/feature/video/ui/pager/PortraitPage','com/bilipai/desktop/ui/DesktopOriginalPortrait']
allowed += ['com/android/purebilibili/feature/video/ui/pager/'+n for n in ['ComposableSingletons$PortraitVideoPagerKt','PortraitDanmakuSurfaceMode','PortraitFavoriteAction','PortraitVideoInteractionOverride','PortraitVideoInteractionUiState']]
allowed += ['com/android/purebilibili/feature/video/viewmodel/'+n for n in ['AudioModePlaylist','ExternalPlaylistSyncDecision','InitialQualityUnavailableReason','QualityChangeReason','SponsorContributionRequest']]
unexpected=[c for c in overlap if not any(c.startswith(p)for p in allowed)]
dump(out/'overlap.json',dict(classes=len(own),declaredProspectiveFamilyOverrides=overlap,unexpected=unexpected,RootInstalledAcceptance=False));assert not unexpected,unexpected
if fixture.exists():
 command=[str(cc.JAVA),'--add-modules','jdk.httpserver','-Dfile.encoding=UTF-8','-Djava.awt.headless=true','-cp',str(out/'classes')+';'+';'.join(r['path']for r in cp),'com.bilipai.desktop.ui.CapturedCacheConsumersProofKt',str(out/'owned-data'),str(MAIN/'desktop/.local/stable-native-media-byte-cache-parity/mpd-media')]
 dump(out/'runtime-command.json',command);r=subprocess.run(command,capture_output=True,encoding='utf-8',errors='replace',timeout=60);write(out/'runtime.log',r.stdout+r.stderr);print(r.stdout+r.stderr)
 dump(out/'runtime-result.json',dict(exit=r.returncode,snapshot=70,entries=101,RootInstalledAcceptance=False))
after=pins();dump(out/'pins-after.json',after);assert before==after
dump(out/'receipt.json',dict(snapshot=70,entries=101,compilePASS=True,runtimeExit=r.returncode if fixture.exists()else None,pinsUnchanged=True,explicitPreparedSources=len(sources),unexpectedClassOverrides=[],RootInstalledAcceptance=False))
print('Candidate adapter compile PASS',len(sources),'sources',len(own),'classes; immutable70 pins unchanged; declared prospective families only.');sys.exit(r.returncode)
