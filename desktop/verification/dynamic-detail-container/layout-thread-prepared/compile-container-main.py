"""Compile only NEXT UI payload + pure fixtures against actual Main Reply25."""
from pathlib import Path
import hashlib, importlib.util, json, subprocess, sys
sys.dont_write_bytecode=True
sys.stdout.reconfigure(encoding='utf-8',errors='replace')
HERE=Path(__file__).resolve().parent
REPO=next(p for p in HERE.parents if (p/'.git').exists())
ATTEMPT=sys.argv[1]
def safe(path):
    value=str(Path(path).absolute())
    return Path(value if value.startswith('\\\\?\\') else '\\\\?\\'+value)
def write(path,text):
    safe(path.parent).mkdir(parents=True,exist_ok=True)
    safe(path).write_text(text,encoding='utf-8',newline='\n')
def sha(path):return hashlib.sha256(safe(path).read_bytes()).hexdigest()
snapshot=REPO/'desktop/.local/dynamic-detail-reply-main-integration/main-product-snapshot-01'
assert sha(snapshot/'manifest.json')=='8286563186e5d5be2495a598e1e32fb15091b7a459a5bbbc3d7e65243557e290'
assert sha(snapshot/'ordered-runtime-cp.json')=='06316248361f2fae9d8eb53976ba1dc6936c5bd6416f98d2b6026c207ef9a217'
dependencies=json.loads(safe(snapshot/'ordered-runtime-cp.json').read_text())
assert len(dependencies)==89
test_support=json.loads(safe(REPO/'desktop/.local/dynamic-editor-detail-parity/final-production-safe/dependency-identities.json').read_text())[89]
assert Path(test_support['path']).name.startswith('kotlin-test-')
dependencies=dependencies+[test_support]
for row in dependencies:assert sha(row['path'])==row['sha256Bytes'],row['path']
raw_manifest=HERE.parent/'raw-handoff-final/frozen-handoff.json'
assert sha(raw_manifest)=='4a7950229a7bf2eed938f9c9979b395696714634e2b690003adb062c66460852'
pure_fixture=HERE.parent/'ReplySessionFixture.kt'
pin=next(row for row in json.loads(safe(raw_manifest).read_text())['artifacts'] if row['path']=='ReplySessionFixture.kt')
assert sha(pure_fixture)==pin['sha256Bytes']
result=subprocess.run([sys.executable,str(HERE/'prepare-container.py')],capture_output=True,text=True,encoding='utf-8',errors='replace')
print(result.stdout+result.stderr);result.check_returncode()
spec=importlib.util.spec_from_file_location('container_main_compiler',REPO/'desktop/.local/source9-appearance/compile-miuix.py')
compiler=importlib.util.module_from_spec(spec);spec.loader.exec_module(compiler)
inputs=[Path(str(p).removeprefix('\\\\?\\')) for folder in [HERE/'generated',HERE/'prepared'] for p in safe(folder).rglob('*.kt')]
inputs+=list(HERE.glob('*Fixture.kt'))+[pure_fixture]
cp=[row['path'] for row in dependencies]
main=next(path for path in cp if Path(path).name=='main-kotlin.jar')
output=HERE/('classes-'+ATTEMPT);safe(output).mkdir(exist_ok=False)
args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(cp),'-Xplugin='+str(compiler.PLUGIN),
    '-Xfriend-paths='+main,'-module-name','com_bilipai_desktop_bilipai_windows','-d',str(output)]+[str(path) for path in inputs]
argfile=HERE/('compiler-'+ATTEMPT+'.args')
write(argfile,'\n'.join('"'+str(value).replace('\\','/')+'"' for value in args)+'\n')
result=subprocess.run([str(compiler.JAVA),'-Dfile.encoding=UTF-8','-Xmx1g','-cp',';'.join(map(str,compiler.COMPILER)),
    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(argfile)],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=65)
write(HERE/('compile-'+ATTEMPT+'.log'),result.stdout+result.stderr);print(result.stdout+result.stderr)
write(HERE/('compile-evidence-'+ATTEMPT+'.json'),json.dumps(dict(passed=result.returncode==0,
    snapshotSha256Bytes=sha(snapshot/'manifest.json'),orderedActual89CpSha256Bytes=sha(snapshot/'ordered-runtime-cp.json'),
    dependencies=dependencies,productionEntries=89,testSupportOnly=1,rawPreparedOverlay=None,
    pureFrozenFixtureSource=str(pure_fixture),existingMainProductOverrides=[],nextPreparedUiOnly=True,
    sourceHashes=[dict(path=str(path),sha256Bytes=sha(path)) for path in inputs],
    MainIntegration=False,sharedGradle=False,HWND=False),indent=2)+'\n')
if result.returncode:sys.exit(result.returncode)
