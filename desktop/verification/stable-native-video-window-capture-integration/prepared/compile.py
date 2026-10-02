from pathlib import Path
import hashlib, importlib.util, json, subprocess, sys
sys.stdout.reconfigure(encoding='utf-8', errors='replace')
sys.dont_write_bytecode = True
H = Path(__file__).resolve().parent; MAIN = H.parents[2]
S = MAIN / 'desktop/.local/stable-product-snapshot-74'

def wide(p):
    s = str(Path(p).absolute()); prefix = chr(92)*2+'?'+chr(92)
    return Path(s if s.startswith(prefix) else prefix+s)

def sha(p): return hashlib.sha256(wide(p).read_bytes()).hexdigest()

assert sha(S/'manifest.json') == '6e6f2ca97fc5e98f00f3806ad7ffeceff271466a20cc90d68479e37e38ffe210'
assert sha(S/'ordered-runtime-cp.json') == 'e5e3a3375a6d5a85fab9d51343af496bb16b46eff71aadeb98e227b766dbf2cd'
cp = json.loads((S/'ordered-runtime-cp.json').read_text()); assert len(cp) == 101
for row in cp: assert sha(row['path']) == row['sha256Bytes'], row['path']
out = H/'runs'/sys.argv[1]; out.mkdir(parents=True, exist_ok=False)
inputs = [(p, p.relative_to(H/'manual').as_posix(), 'new-manual') for p in sorted((H/'manual').rglob('*.kt'))]
inputs += [(p, p.relative_to(H/'prepared/existing').as_posix(), 'declared-existing-family')
    for p in sorted((H/'prepared/existing').rglob('*.kt'))]
sources = []
assert len({p.name for p, relative, role in inputs}) == len(inputs)
for p, relative, role in inputs:
    copy = out/'inputs'/p.name; copy.parent.mkdir(parents=True, exist_ok=True)
    copy.write_bytes(p.read_bytes()); sources.append(copy)
spec = importlib.util.spec_from_file_location('cc', MAIN/'desktop/.local/source9-appearance/compile-miuix.py')
cc = importlib.util.module_from_spec(spec); spec.loader.exec_module(cc)
args = ['-no-stdlib','-no-reflect','-jvm-target','21','-module-name','com_bilipai_desktop_bilipai_windows',
    '-Xplugin='+str(cc.PLUGIN), '-Xfriend-paths='+cp[1]['path'],'-cp',';'.join(row['path'] for row in cp),
    '-d',str(out/'classes')]+list(map(str,sources))
(out/'compiler.args').write_text('\n'.join('"'+a.replace('\\','/')+'"' for a in args),encoding='utf-8')
command = [str(cc.JAVA),'-Dfile.encoding=UTF-8','-Xmx3g','-cp',';'.join(map(str,cc.COMPILER)),
    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(out/'compiler.args')]
r = subprocess.run(command,capture_output=True,encoding='utf-8',errors='replace',timeout=180)
(out/'compile.log').write_text(r.stdout+r.stderr,encoding='utf-8')
(out/'compile-command.json').write_text(json.dumps(command,indent=2)+'\n',encoding='utf-8')
(out/'inputs.json').write_text(json.dumps({'snapshot':74,'entries':101,
    'inputs':[{'path':str(p),'sha256Bytes':sha(p),'copy':str(copy),'role':role}
        for (p, relative, role), copy in zip(inputs,sources)],
    'existingFamilyOverrides':['DesktopCommandPopupWindow.kt','DesktopOriginalVideoWindowsWindowPort.kt'],
    'newManual':1,'composeCompiler':str(cc.PLUGIN)},indent=2)+'\n',encoding='utf-8')
(out/'result.json').write_text(json.dumps({'exit':r.returncode,'sourceCount':len(sources),
    'classCount':len(list((out/'classes').rglob('*.class'))), 'nativeWindowAccepted':False,
    'wholeRootAccepted':False},indent=2)+'\n',encoding='utf-8')
print(r.stdout+r.stderr); print('exit='+str(r.returncode))
for row in cp: assert sha(row['path']) == row['sha256Bytes'], row['path']
sys.exit(r.returncode)
