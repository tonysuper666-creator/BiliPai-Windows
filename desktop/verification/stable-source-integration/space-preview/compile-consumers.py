"""Compile candidate consumers plus sole selected Space source, no shared build."""
from pathlib import Path
import hashlib,importlib.util,json,subprocess,sys,zipfile
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent
PREFIX=chr(92)*2+'?'+chr(92)
def safe(p):
 s=str(Path(p).absolute());return Path(s if s.startswith(PREFIX) else PREFIX+s)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
snap=HERE.parent/'image-save-main-integration/main-product-snapshot-04'
assert sha(snap/'manifest.json')=='7bc5ac8875dadf38510120db6619bd6e748e7f790a47afc11374e14114f25865'
assert sha(snap/'ordered-runtime-cp.json')=='7a3141310fa355d8618c5bc2be90f36a2c13c8ed36f2d981bf4a14c2232165e2'
items=json.loads(safe(snap/'ordered-runtime-cp.json').read_text(encoding='utf-8'))
child=HERE.parent/'stable-image-preview-producer-parity/compile-01'
renderer=child/'prepared-stable-preview.jar'
assert sha(renderer)=='31a9d946f9d64a441e9c5f33c5647bccb63df94dabb37080a4e763d8d2adc37f'
childresult=json.loads(safe(child/'compile-result.json').read_text(encoding='utf-8'))
assert childresult['status']=='PASS' and childresult['preparedOnly']
assert sha(HERE.parent/'stable-image-preview-producer-parity/evidence-manifest.json')=='aa2f0fded76142156e7470d40d999939e78fe9ea3a86300353ab8e1e805a92d9'
for i in items:assert sha(i['path'])==i['sha256Bytes']
spec=importlib.util.spec_from_file_location('space_stable_consumer_compiler',HERE.parent/'source9-appearance/compile-miuix.py')
compiler=importlib.util.module_from_spec(spec);spec.loader.exec_module(compiler)
main=next(i['path'] for i in items if i.get('source')=='desktop/build/classes/kotlin/main')
sourceRoot=HERE/'prepared/desktop/src/main/kotlin/com/bilipai/desktop/ui'
sources=[sourceRoot/n for n in ['DesktopSpaceImagePreviews.kt','DesktopSpaceOverviewScreens.kt','DesktopCompleteSpaceScreen.kt','DesktopSpaceScreens.kt']]
sources.append(HERE/'generated/com/android/purebilibili/feature/space/DesktopOriginalSpaceImagePreviewCallers.kt')
attempt=HERE/('compile-'+sys.argv[1] if len(sys.argv)>1 else 'compile-01');attempt.mkdir(exist_ok=False)
args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',str(renderer)+';'+';'.join(i['path'] for i in items),
 '-Xplugin='+str(compiler.PLUGIN),'-Xfriend-paths='+main+','+str(renderer),'-module-name','prepared_stable_space_image_consumers',
 '-d',str(attempt/'candidate.jar'),*map(str,sources)]
safe(attempt/'compiler.args').write_text('\n'.join('"'+v.replace('\\','/')+'"' for v in args),encoding='utf-8',newline='\n')
safe(attempt/'input-ordered-runtime-cp.json').write_text(json.dumps(items,indent=2),encoding='utf-8',newline='\n')
ran=subprocess.run([str(compiler.JAVA),'-Dfile.encoding=UTF-8','-Xmx1g','-cp',';'.join(map(str,compiler.COMPILER)),
 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(attempt/'compiler.args')],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=120)
safe(attempt/'compiler.log').write_text(ran.stdout+ran.stderr,encoding='utf-8',newline='\n')
print((ran.stdout+ran.stderr)[-16000:]);ran.check_returncode()
with zipfile.ZipFile(safe(attempt/'candidate.jar')) as jar:classes={n for n in jar.namelist() if n.endswith('.class')}
with zipfile.ZipFile(safe(main)) as jar:overlap=sorted(classes.intersection(jar.namelist()))
expected=['com/bilipai/desktop/ui/DesktopSpaceOverviewScreensKt','com/bilipai/desktop/ui/DesktopCompleteSpaceScreenKt',
 'com/bilipai/desktop/ui/DesktopCompleteSpaceState','com/bilipai/desktop/ui/DesktopSpaceScreensKt',
 'com/bilipai/desktop/ui/DesktopSpaceBrowseState','com/bilipai/desktop/ui/DesktopSpaceTab',
 'com/bilipai/desktop/ui/DesktopSpaceGuardRow','com/bilipai/desktop/ui/DesktopSpaceHomeSection',
 'com/bilipai/desktop/ui/ComposableSingletons$DesktopSpaceOverviewScreensKt',
 'com/bilipai/desktop/ui/ComposableSingletons$DesktopCompleteSpaceScreenKt',
 'com/bilipai/desktop/ui/ComposableSingletons$DesktopSpaceScreensKt']
assert overlap
assert all(any(c==p+'.class' or c.startswith(p+'$') for p in expected) for c in overlap),overlap
for i in items:assert sha(i['path'])==i['sha256Bytes']
assert sha(renderer)==childresult['artifactSha256Bytes']
result=dict(preparedOnly=True,mainAcceptance=False,status='PASS',exitCode=0,sourceCount=len(sources),
 originalTag='v0.2.3',originalCommit='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589',
 originalSpaceSourceSha256Lf='2c7063c8c9b112b10f7b5394b34ddfc4362fcf2984d3eae3a3364469cc3557ca',
 sourceInputs=[dict(path=str(p.relative_to(HERE)),sha256Bytes=sha(p)) for p in sources],
 actualMain04Cp=items,mainCpCount=len(items),candidateClassCount=len(classes),
 declaredConsumerProductClassOverrides=overlap,candidateProductClassOverrideCount=len(overlap),
 rendererCandidateDependency=dict(path=str(renderer),sha256Bytes=sha(renderer),sourceCount=6,
  declaredProductClassOverrides=childresult['declaredSameOwnerClassOverrides']),
 artifactSha256Bytes=sha(attempt/'candidate.jar'),noRuntime=True,noMainEdits=True,noGradle=True,noHWND=True,
 stableHeaderConsumerMounted=False,stableSystemChooserAccepted=False)
safe(attempt/'compile-result.json').write_text(json.dumps(result,indent=2),encoding='utf-8',newline='\n')
print('STABLE ORIGINAL BANNER AND THREE CONSUMERS COMPILE PASS (prepared overrides declared; no runtime).')
