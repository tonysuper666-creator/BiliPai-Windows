from pathlib import Path
import hashlib, importlib.util, json, subprocess, sys, zipfile
sys.dont_write_bytecode=True; sys.stdout.reconfigure(encoding='utf-8',errors='replace')
HERE=Path(__file__).resolve().parent; REPO=HERE.parents[2]
SNAP=REPO/'desktop/.local/dynamic-detail-reply-main-integration/main-product-snapshot-01'
def sha(p):
    p=str(Path(p).resolve()); return hashlib.sha256(Path(p if p.startswith('\\\\?\\') else '\\\\?\\'+p).read_bytes()).hexdigest()
assert sha(SNAP/'manifest.json')=='8286563186e5d5be2495a598e1e32fb15091b7a459a5bbbc3d7e65243557e290'
assert sha(SNAP/'ordered-runtime-cp.json')=='06316248361f2fae9d8eb53976ba1dc6936c5bd6416f98d2b6026c207ef9a217'
cp=json.loads((SNAP/'ordered-runtime-cp.json').read_text(encoding='utf-8'))
for row in cp:assert sha(row['path'])==row['sha256Bytes']
spec=importlib.util.spec_from_file_location('livephoto_only_compiler',REPO/'desktop/.local/source9-appearance/compile-miuix.py')
c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
out=HERE/'livephoto-source-only/compile02';assert not out.exists();out.mkdir()
source=HERE/'livephoto-source-only/DesktopDynamicLivePhoto.kt';jar=out/'source-only-candidate.jar'
main=next(row['path'] for row in cp if row.get('source')=='desktop/build/classes/kotlin/main')
values=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(row['path'] for row in cp),'-Xfriend-paths='+main,
        '-Xplugin='+str(c.PLUGIN),'-module-name','livephoto_source_only_candidate','-d',jar,source]
args=out/'compiler.args';args.write_text('\n'.join('"'+str(v).replace('\\','/')+'"' for v in values)+'\n',encoding='utf-8',newline='\n')
r=subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','-Xmx2g','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(args)],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=90)
(out/'compile.log').write_text(r.stdout+r.stderr,encoding='utf-8',newline='\n');print(r.stdout+r.stderr);r.check_returncode()
with zipfile.ZipFile(jar) as z:classes=[n for n in z.namelist() if n.endswith('.class')]
assert all(n.startswith('com/bilipai/desktop/ui/DesktopDynamicLivePhotoKt') or n.startswith('com/bilipai/desktop/ui/DesktopDynamicLivePhotoPlayer') or n=='com/bilipai/desktop/ui/ComposableSingletons$DesktopDynamicLivePhotoKt.class' for n in classes)
(out/'compile-evidence.json').write_text(json.dumps({'compilePassed':True,'executed':False,'sourceOnly':True,'actualMainManifestSha256Bytes':sha(SNAP/'manifest.json'),
    'actualOrdered89CpSha256Bytes':sha(SNAP/'ordered-runtime-cp.json'),'runtimeEntries':89,'composeCompilerPluginSha256Bytes':sha(c.PLUGIN),
    'sourceSha256Bytes':sha(source),'sourceOnlyJarSha256Bytes':sha(jar),'candidateClassFamiliesOnly':['DesktopDynamicLivePhotoKt','DesktopDynamicLivePhotoPlayer','ComposableSingletons$DesktopDynamicLivePhotoKt'],
    'nativeFrameRetryWindowLifecycleAccepted':False,'actualHWND':False,'nativeDecoder':False,'RootProviderInstalled':False,'MainModified':False},indent=2)+'\n',encoding='utf-8',newline='\n')
print('PASS source-only LivePhoto candidate compiles with actual Main 89 CP / Compose plugin; no UI/native execution claim')
