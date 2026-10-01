from pathlib import Path
import hashlib,importlib.util,json,os,subprocess,sys,zipfile
P=Path(__file__).resolve().parent;MAIN=P.parents[2];S=MAIN/'desktop/.local/stable-original-video-byte-cache-consumers-parity';PFX=chr(92)*2+'?'+chr(92)
def wide(p):
 s=os.path.abspath(p);return Path(s if s.startswith(PFX)else PFX+s)
def sha(p):return hashlib.sha256(wide(p).read_bytes()).hexdigest()
def h(s):return hashlib.sha256(s.encode()).hexdigest()
def load(p):return wide(p).read_text(encoding='utf8').replace('\r\n','\n')
def write(p,s):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_text(s,encoding='utf8',newline='\n')
def save(p,v):write(p,json.dumps(v,indent=2)+'\n')
assert sha(S/'frozen-handoff.json')=='b0f87d87632549bf9e5b28ae0f4447f999947253e8cf0bc8d66fde0466a07628'
runtime=json.loads(load(S/'runs/proof-06/runtime-command.json'));prospective=Path(runtime[runtime.index('-cp')+1].split(';')[0])
assert wide(prospective).is_dir()
prospectivePins=sorted([dict(path=str(p.relative_to(prospective)),sha256Bytes=sha(p))for p in prospective.rglob('*')if p.is_file()],key=lambda r:r['path'])
assembly=P/'prepared/manual/com/bilipai/desktop/ui/DesktopOriginalVideoOwnerAssembly.kt';base=load(assembly)
field='    val cdnRangeCache: DesktopOriginalCdnRangeCache,\n'
before='                    effects.cdnRangeCache, effects.analytics, effects.crash, comments, effects.danmaku,\n'
after='''                    { expected ->
                        if (owner.native.isCurrent(expected) && expected.nativeSource.source.nativeTransport != null)
                            DesktopOriginalCdnRangeCapture(expected, owner.native::isCurrent)
                        else null // actual direct source or retired lease; no fake cache success
                    }, effects.analytics, effects.crash, comments, effects.danmaku,
'''
assert base.count(field)==1 and base.count(before)==1
body=base.replace(field,'',1).replace(before,after,1)
derived=P/'prepared/cache-compatible/com/bilipai/desktop/ui/DesktopOriginalVideoOwnerAssembly.kt';write(derived,body)
save(P/'cache-consumer-compatibility-hunk.json',dict(target='desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalVideoOwnerAssembly.kt',
 baseSha256LF=h(base),desiredSha256LF=h(body),requiredSiblingManifestSHA256=sha(S/'frozen-handoff.json'),
 hunks=[dict(before=field,after=''),dict(before=before,after=after)],
 oldStaticRangePortRemoved=True,sameNativeOwnerCapturedCacheOnly=True,newAuthority=False))
bindingPath='desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalVideoRepositoryBinding.kt'
binding=load(S/'prepared/existing'/bindingPath)
ours=json.loads(load(P/'exact-hunks.json'))
for family in ours:
 if family['target']==bindingPath:
  for delta in family['hunks']:
   assert binding.count(delta['before'])==1;binding=binding.replace(delta['before'],delta['after'],1)
combinedBinding=P/'compile-reference/cache-compatible/DesktopOriginalVideoRepositoryBinding.kt';write(combinedBinding,binding)
vm=load(S/'generated/full-owner/com/android/purebilibili/feature/video/viewmodel/VideoPlaybackViewModel.kt')
anchor='    // Internal state\n';assert vm.count(anchor)==1
projection='    internal fun captureDesktopLoadState(): com.android.purebilibili.feature.video.playback.session.PlaybackSessionState = playbackSessionState\n\n'
vm=vm.replace(anchor,projection+anchor,1)
combinedVM=P/'compile-reference/cache-compatible/VideoPlaybackViewModel.kt';write(combinedVM,vm)
pins=json.loads(load(P/'runs/06/pins-before.json'));cp=pins['runtime'];assert len(cp)==101
def check():
 for r in cp:assert sha(r['path'])==r['sha256Bytes'],r['path']
 for r in prospectivePins:assert sha(prospective/r['path'])==r['sha256Bytes'],r['path']
check()
out=P/'runs'/sys.argv[1];assert not wide(out).exists();wide(out).mkdir(parents=True)
sources=[p for p in sorted((P/'prepared/manual').rglob('*.kt'))if p!=assembly]+[derived,combinedBinding,combinedVM,
 P/'generated/com/android/purebilibili/data/repository/DesktopOriginalVideoActionStatus.kt',
 P/'prepared/existing/desktop/src/main/kotlin/com/bilipai/desktop/data/DesktopRepository.kt']
for source in sources:write(out/'source-inputs'/source.relative_to(P),load(source))
allPins=dict(actual71ManifestSHA256=pins['actual71ManifestSHA256'],actual71OrderedCPSHA256=pins['actual71OrderedCPSHA256'],runtime=cp,
 prospectiveSiblingManifestSHA256=sha(S/'frozen-handoff.json'),prospectiveClasses=prospectivePins,
 inputs=[dict(path=str(p),sha256Bytes=sha(p))for p in sources])
save(out/'pins-before.json',allPins)
spec=importlib.util.spec_from_file_location('c',MAIN/'desktop/.local/source9-appearance/compile-miuix.py')
c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
jar=out/'candidate.jar';classpath=[str(prospective)]+[r['path']for r in cp]
args=['-no-stdlib','-no-reflect','-jvm-target','21','-module-name','com_bilipai_desktop_bilipai_windows',
 '-Xplugin='+str(c.PLUGIN),'-Xfriend-paths='+str(prospective)+','+cp[1]['path'],'-cp',';'.join(classpath),'-d',str(jar)]+list(map(str,sources))
arg=out/'compile.args';write(arg,'\n'.join('"'+a.replace('\\','/')+'"'for a in args))
r=subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','-Xmx2g','-cp',';'.join(map(str,c.COMPILER)),
 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(arg)],capture_output=True,timeout=180)
wide(out/'compile.log').write_bytes(r.stdout+r.stderr);check();save(out/'pins-after.json',allPins)
save(out/'compile-result.json',dict(passed=r.returncode==0,exitCode=r.returncode,inputs=len(sources),
 actual71RuntimeEntries=101,prospectiveSiblingConsumerReference=True,RootInstalledAcceptance=False,
 fullOwnerConstructed=False,native=False,HTTP=False,runtimeNotRepeated=True,jarSHA256Bytes=sha(jar)if r.returncode==0 else None))
print((r.stdout+r.stderr).decode('utf8',errors='replace'));raise SystemExit(r.returncode)
