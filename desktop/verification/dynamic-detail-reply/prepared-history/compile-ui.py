from pathlib import Path
import hashlib
import importlib.util
import json
import subprocess
import sys

sys.dont_write_bytecode = True
sys.stdout.reconfigure(encoding='utf-8', errors='replace')
HERE = Path(__file__).resolve().parent
REPO = next(p for p in HERE.parents if (p / '.git').exists())
ATTEMPT = sys.argv[1]
EDITOR = REPO / 'desktop/.local/dynamic-editor-detail-parity/final-production-safe'

def safe(path):
    value = str(Path(path).absolute())
    return Path(value if value.startswith('\\\\?\\') else '\\\\?\\' + value)

def sha(path):
    return hashlib.sha256(safe(path).read_bytes()).hexdigest()

def write(path, value):
    safe(path.parent).mkdir(parents=True, exist_ok=True)
    safe(path).write_text(value, encoding='utf-8', newline='\n')

result = subprocess.run([sys.executable, str(HERE / 'prepare.py')], capture_output=True,
    text=True, encoding='utf-8', errors='replace')
print(result.stdout+result.stderr)
result.check_returncode()
snapshot = REPO / 'desktop/.local/dynamic-editor-main-product-snapshot-01'
deps = json.loads(safe(snapshot/'ordered-runtime-cp.json').read_text(encoding='utf-8'))
editor_deps = json.loads(safe(EDITOR/'dependency-identities.json').read_text(encoding='utf-8'))
deps += editor_deps[89:]
assert len(deps) == 90
for item in deps:
    assert sha(item['path']) == item['sha256Bytes'], item['path']
write(HERE/'dependency-identities.json',json.dumps(deps,indent=2)+'\n')
spec=importlib.util.spec_from_file_location('reply_compiler',REPO/'desktop/.local/source9-appearance/compile-miuix.py')
compiler=importlib.util.module_from_spec(spec)
spec.loader.exec_module(compiler)
def sources(path):
    return [Path(str(p).removeprefix('\\\\?\\')) for p in safe(path).rglob('*.kt')]
inputs = sources(HERE/'generated/ui') + sources(HERE/'generated/detail') + sources(HERE/'protocol/generated') + sources(HERE/'protocol/detail-prepared/generated') + sources(HERE/'prepared/desktop/src')
inputs += list(HERE.glob('*Fixture.kt'))
output=HERE/('classes-ui-'+ATTEMPT)
safe(output).mkdir(exist_ok=False)
cp=[item['path'] for item in deps]
friend=next(path for path in cp if Path(path).name=='main-kotlin.jar')
args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(cp),'-Xplugin='+str(compiler.PLUGIN),
      '-Xfriend-paths='+friend,'-module-name','com_bilipai_desktop_bilipai_windows','-d',str(output)]
args += [str(path) for path in inputs]
argument_file=HERE/('compiler-ui-'+ATTEMPT+'.args')
write(argument_file,'\n'.join('"'+str(value).replace('\\','/')+'"' for value in args)+'\n')
result=subprocess.run([str(compiler.JAVA),'-Dfile.encoding=UTF-8','-Xmx1g','-cp',';'.join(map(str,compiler.COMPILER)),
       'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(argument_file)],
       capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=65)
write(HERE/('compile-ui-'+ATTEMPT+'.log'),result.stdout+result.stderr)
print(result.stdout+result.stderr)
if result.returncode == 0:
    write(HERE/'compile-ui-evidence.json',json.dumps(dict(passed=True,activeClasses=output.name,
        productJars=deps[:3],sources=[dict(path=str(path),sha256Bytes=sha(path)) for path in inputs],
        explicitPreparedProductOverride=['com.bilipai.desktop.data.DesktopDynamicCardOperations'],
        editorFoundationIsActualMain=True,MainIntegration=False,sharedGradle=False,HWND=False),indent=2)+'\n')
result.check_returncode()
