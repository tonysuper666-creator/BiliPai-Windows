"""Compile/run one fixture against a Root-delivered immutable actual product graph. No production sources."""
from pathlib import Path
import argparse, hashlib, importlib.util, json, subprocess, sys, zipfile
sys.dont_write_bytecode=True
sys.stdout.reconfigure(encoding='utf-8')
LANE=Path(__file__).resolve().parent
MAIN=LANE.parents[2]
def safe(p):
    s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92)
    return Path(s if s.startswith(prefix) else prefix+s)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def load(p):return json.loads(safe(p).read_text(encoding='utf-8'))
def write(p,value):
    safe(p).write_text(value if isinstance(value,str) else json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
parser=argparse.ArgumentParser()
parser.add_argument('mode',choices=['compile','run'])
parser.add_argument('--snapshot',required=True,type=int)
parser.add_argument('--manifest-sha',required=True)
parser.add_argument('--cp-sha',required=True)
parser.add_argument('--entries',required=True,type=int)
parser.add_argument('--number',default='01')
parser.add_argument('--external-input',action='store_true')
args=parser.parse_args()
assert args.snapshot>=51 and args.entries>0
snapshot=MAIN/f'desktop/.local/stable-product-snapshot-{args.snapshot}'
assert sha(snapshot/'manifest.json')==args.manifest_sha
assert sha(snapshot/'ordered-runtime-cp.json')==args.cp_sha
cp=load(snapshot/'ordered-runtime-cp.json');assert len(cp)==args.entries
def check():
    assert sha(snapshot/'manifest.json')==args.manifest_sha
    assert sha(snapshot/'ordered-runtime-cp.json')==args.cp_sha
    for row in cp:assert sha(row['path'])==row['sha256Bytes'],row['path']
check()
native=MAIN/'desktop/native/windows-x64/libmpv-2.dll'
assert sha(native)=='673e6397920ab64a9c5b3a618f7f16d38854efe72b58665f1f84e4e873b763a4'
clip=MAIN/'desktop/.local/stable-home-native-media-proof/runs/actual33-01/local-media/moving.mp4'
assert sha(clip)=='0e09ab9a6a9af00cee99508e4a3ff41e9436f7d1850e4b31c6ceb0c22d8c4656'
source=LANE/'OfflineNativeFixture.kt'
runtime_sources=['com/bilipai/desktop/ui/DesktopWindowsFullscreenControlKt.class','com/bilipai/desktop/ui/DesktopWindowsFullscreenControl.class','com/bilipai/desktop/ui/DesktopOriginalOfflineRootHostKt.class','com/android/purebilibili/feature/download/OfflineVideoPlayerScreenKt.class',
                 'com/bilipai/desktop/ui/DesktopOriginalOfflinePlayerBindings.class',
                 'com/bilipai/desktop/ui/DesktopOriginalPlayerSurfaceKt.class',
                 'com/bilipai/desktop/ui/DesktopOfflineMpvControl.class']
with zipfile.ZipFile(safe(cp[1]['path'])) as product:
    actual_names=set(product.namelist())
    for path in runtime_sources:assert path in actual_names,path
spec=importlib.util.spec_from_file_location('c',MAIN/'desktop/.local/source9-appearance/compile-miuix.py')
c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
out=LANE/('runs/actual'+str(args.snapshot)+'-'+args.number)
jar=out/'offline-native-fixture.jar'
if args.mode=='compile':
    assert not safe(out).exists()
    safe(out).mkdir(parents=True)
    safe(out/'OfflineNativeFixture.kt').write_bytes(safe(source).read_bytes())
    pins={'snapshot':args.snapshot,'manifestSha256':args.manifest_sha,'orderedCpSha256':args.cp_sha,
          'runtimeEntries':cp,'source':{'path':str(source),'sha256Bytes':sha(source)},
          'native':{'path':str(native),'sha256Bytes':sha(native)},'clip':{'path':str(clip),'sha256Bytes':sha(clip)}}
    write(out/'pins-before.json',pins)
    compiler_args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(row['path'] for row in cp),
        '-Xfriend-paths='+cp[1]['path'],'-Xplugin='+str(c.PLUGIN),'-module-name','offline_native_fixture',
        '-d',str(out/'classes'),str(source)]
    write(out/'compiler.args','\n'.join('"'+str(a).replace('\\','/')+'"' for a in compiler_args))
    compiled=subprocess.run([str(c.JAVA),'-Xmx3g','-Dfile.encoding=UTF-8','-cp',';'.join(map(str,c.COMPILER)),
        'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(out/'compiler.args')],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=150)
    write(out/'compile.log',compiled.stdout+compiled.stderr)
    if compiled.returncode:print(compiled.stdout+compiled.stderr);sys.exit(compiled.returncode)
    with zipfile.ZipFile(safe(jar),'w',zipfile.ZIP_DEFLATED) as z:
        for p in sorted(safe(out/'classes').rglob('*')):
            if p.is_file():z.writestr(p.relative_to(safe(out/'classes')).as_posix(),p.read_bytes())
    with zipfile.ZipFile(safe(jar)) as fixture:
        overlap=sorted(n for n in set(fixture.namelist()) & actual_names if n.endswith('.class'))
    assert not overlap,overlap
    check()
    write(out/'compile-result.json',{'status':'PASS_FIXTURE_ONLY','actualSnapshot':args.snapshot,'entries':args.entries,
        'productionOverrides':0,'fixtureSource':pins['source'],'fixtureJarSha256':sha(jar),
        'allOriginalRendererAndPlatformFamiliesFromActualProduct':runtime_sources,'sharedGradle':False})
    print(json.dumps({'status':'PASS_FIXTURE_ONLY','snapshot':args.snapshot,'fixtureJarSha256':sha(jar),'productionOverrides':0},indent=2))
else:
    assert args.external_input,'Normal acceptance requires Root sky external input; self-created native UI injection is disabled'
    assert sha(jar)==load(out/'compile-result.json')['fixtureJarSha256']
    pins=load(out/'pins-before.json')
    assert pins['source']['sha256Bytes']==sha(source)
    output=out/'fixture-owned-runtime';assert not safe(output).exists();safe(output).mkdir()
    command=[str(c.JAVA),'-Dfile.encoding=UTF-8','-Dsun.stdout.encoding=UTF-8','-Dsun.stderr.encoding=UTF-8',
        '-Dbilipai.mpv.path='+str(native),'-cp',';'.join([str(jar)]+[row['path'] for row in cp]),
        'com.bilipai.desktop.offlinenativefixture.OfflineNativeFixtureKt',str(output),str(clip),'external-input']
    write(out/'runtime-command.json',command)
    result=subprocess.run(command,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=4200)
    write(out/'runtime.log',result.stdout+result.stderr)
    check();assert pins['source']['sha256Bytes']==sha(source)
    write(out/'pins-after.json',pins)
    if result.returncode:print(result.stdout+result.stderr);sys.exit(result.returncode)
    proof=load(output/'native-proof.json');assert proof['status'] in ['PASS','PASS_INDEPENDENT_CONTROLS_FULLSCREEN_NOT_ACCEPTED']
    assert proof['diagnosticOnly'] is False and proof['diagnosticBaselineMounted'] is False and proof['JDWPEnabled'] is False
    for name,origin in proof['classOrigins'].items():
        assert Path(origin.removeprefix('file:/')).as_posix().lower().replace('%20',' ')==Path(cp[1]['path']).as_posix().lower(),(name,origin)
    write(out/'acceptance-result.json',{'status':proof['status'],'diagnosticOnly':False,'diagnosticBaselinesMounted':False,'JDWPEnabled':False,'fullRequestedScopeAccepted':False,'logicAndNativeControlScopeCompleted':proof['status']=='PASS','visualAcceptanceRequiresRootSkyReceipt':True,'actualSnapshot':args.snapshot,'runtimeEntries':args.entries,
        'productionOverrides':0,'nativeProofSha256':sha(output/'native-proof.json'),'assertions':proof['assertions'],
        'pointerPairs':proof['pointerPairs'],'allExpectedRuntimePinsBeforeAfter':True,'classOriginsAllActualMainKotlin':True,
        'unchangedFullProductRendererCohort':True,'actualMainShellAccepted':False,'hardwareMouseAccepted':False,
        'realAccountAccepted':False,'OSSMTCButtonEventAccepted':False,'ownedSkiaBackingOnly':True,'sharedGradle':False})
    print(json.dumps(load(out/'acceptance-result.json'),indent=2))
