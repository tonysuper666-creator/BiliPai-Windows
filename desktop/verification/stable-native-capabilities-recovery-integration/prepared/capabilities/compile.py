from pathlib import Path
import hashlib, importlib.util, json, subprocess, sys
sys.stdout.reconfigure(encoding='utf-8', errors='replace')
sys.dont_write_bytecode = True
H = Path(__file__).resolve().parent; MAIN = H.parents[2]
S = MAIN / 'desktop/.local/stable-product-snapshot-73'
def wide(p):
    s=str(Path(p).absolute()); prefix=chr(92)*2+'?'+chr(92)
    return Path(s if s.startswith(prefix) else prefix+s)
sha = lambda p: hashlib.sha256(wide(p).read_bytes()).hexdigest()
assert sha(S/'manifest.json') == '4fe15a56b0a78bda5bf067e52f4e20ceb88244eee943a007db248e6f1bb9e829'
assert sha(S/'ordered-runtime-cp.json') == 'f71557c1952176118a9eba16479da2e244cacfb6cc419a65f43dc5501f822d31'
cp = json.loads((S/'ordered-runtime-cp.json').read_text()); assert len(cp) == 101
for row in cp: assert sha(row['path']) == row['sha256Bytes'], row['path']
name = sys.argv[1]
out = H/'runs'/name; out.mkdir(parents=True, exist_ok=False)
sources = sorted((H/'manual').rglob('*.kt')) + [H/'after/desktop/src/main/kotlin/com/bilipai/desktop/player/MpvPlayer.kt']
spec=importlib.util.spec_from_file_location('cc',MAIN/'desktop/.local/source9-appearance/compile-miuix.py')
cc=importlib.util.module_from_spec(spec);spec.loader.exec_module(cc)
args=['-no-stdlib','-no-reflect','-jvm-target','21','-module-name','com_bilipai_desktop_bilipai_windows',
    '-Xfriend-paths='+cp[1]['path'],'-cp',';'.join(row['path'] for row in cp),'-d',str(out/'classes')]+list(map(str,sources))
(out/'compiler.args').write_text('\n'.join('"'+a.replace('\\','/')+'"' for a in args),encoding='utf-8')
cmd=[str(cc.JAVA),'-Dfile.encoding=UTF-8','-Xmx3g','-cp',';'.join(map(str,cc.COMPILER)),
    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(out/'compiler.args')]
r=subprocess.run(cmd,capture_output=True,encoding='utf-8',errors='replace',timeout=180)
(out/'compile.log').write_text(r.stdout+r.stderr,encoding='utf-8')
(out/'inputs.json').write_text(json.dumps({'snapshot':73,'entries':101,
    'inputs':[{'path':str(p),'sha256Bytes':sha(p)} for p in sources],
    'existingFamilyOverrides':['MpvPlayer.kt'],'newManual':len(sources)-1},indent=2)+'\n',encoding='utf-8')
(out/'result.json').write_text(json.dumps({'exit':r.returncode,'sourceCount':len(sources)},indent=2)+'\n',encoding='utf-8')
print(r.stdout+r.stderr);print('exit='+str(r.returncode))
for row in cp: assert sha(row['path']) == row['sha256Bytes'], row['path']
sys.exit(r.returncode)
