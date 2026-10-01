from pathlib import Path
import hashlib, importlib.util, json, subprocess, sys, zipfile
sys.stdout.reconfigure(encoding='utf-8',errors='replace');sys.dont_write_bytecode=True
H=Path(__file__).resolve().parent;MAIN=H.parents[2]
def wide(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92)
 return Path(s if s.startswith(prefix) else prefix+s)
def sha(p):return hashlib.sha256(wide(p).read_bytes()).hexdigest()
def row(p):return dict(path=str(p),sha256Bytes=sha(p))
def dump(p,v):p.write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
S=MAIN/'desktop/.local/stable-product-snapshot-73'
assert sha(S/'manifest.json')=='4fe15a56b0a78bda5bf067e52f4e20ceb88244eee943a007db248e6f1bb9e829'
assert sha(S/'ordered-runtime-cp.json')=='f71557c1952176118a9eba16479da2e244cacfb6cc419a65f43dc5501f822d31'
cp=json.loads((S/'ordered-runtime-cp.json').read_text());assert len(cp)==101
def pins():
 for e in cp:assert sha(e['path'])==e['sha256Bytes']
 return [row(e['path']) for e in cp]
before=pins()
production=H/'runs'/sys.argv[1];assert json.loads((production/'result.json').read_text())['exit']==0
out=H/'runs'/sys.argv[2];out.mkdir(parents=True,exist_ok=False)
native=MAIN/'desktop/native/windows-x64/libmpv-2.dll'
assert sha(native)=='673e6397920ab64a9c5b3a618f7f16d38854efe72b58665f1f84e4e873b763a4'
native_before=row(native)
source=out/'MpvCapabilityProof.kt';source.write_bytes((H/source.name).read_bytes())
prospective=production/'classes'
prospective_before=[row(p) for p in prospective.rglob('*') if p.is_file()]
spec=importlib.util.spec_from_file_location('cc',MAIN/'desktop/.local/source9-appearance/compile-miuix.py')
cc=importlib.util.module_from_spec(spec);spec.loader.exec_module(cc)
args=['-no-stdlib','-no-reflect','-jvm-target','21','-module-name','com_bilipai_desktop_bilipai_windows',
 '-Xfriend-paths='+str(prospective)+','+cp[1]['path'],'-cp',str(prospective)+';'+';'.join(e['path'] for e in cp),'-d',str(out/'classes'),str(source)]
(out/'compiler.args').write_text('\n'.join('"'+a.replace('\\','/')+'"' for a in args),encoding='utf-8')
cmd=[str(cc.JAVA),'-Dfile.encoding=UTF-8','-Xmx2g','-cp',';'.join(map(str,cc.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(out/'compiler.args')]
r=subprocess.run(cmd,capture_output=True,encoding='utf-8',errors='replace',timeout=120)
(out/'compile.log').write_text(r.stdout+r.stderr,encoding='utf-8');dump(out/'compile-result.json',dict(exit=r.returncode))
if r.returncode:print(r.stdout+r.stderr);sys.exit(r.returncode)
classes={p.relative_to(out/'classes').as_posix() for p in (out/'classes').rglob('*.class')}
existing=set()
for e in cp:
 with zipfile.ZipFile(wide(e['path'])) as z:existing.update(z.namelist())
own={p.relative_to(prospective).as_posix() for p in prospective.rglob('*.class')}
assert not classes & (existing|own)
overrides=sorted(own&existing)
assert overrides and all(n.startswith('com/bilipai/desktop/player/MpvPlayer') for n in overrides)
dump(out/'class-overlap.json',dict(fixtureOverlap=[],existingFamilyOverrides=overrides,newManualClasses=len(own)-len(overrides)))
dump(out/'inputs.json',dict(snapshot=73,entries=101,snapshotManifest=row(S/'manifest.json'),orderedCp=row(S/'ordered-runtime-cp.json'),
 native=native_before,fixture=row(source),prospectiveInputs=json.loads((production/'inputs.json').read_text()),prospectiveClassBytes=prospective_before,
 productionSourceOverrides=1,MainShellAccepted=False,actualDisplayAccepted=False))
cmd=[str(cc.JAVA),'-Dfile.encoding=UTF-8','-Djava.awt.headless=true','-Dbilipai.mpv.path='+str(native),'-cp',str(out/'classes')+';'+str(prospective)+';'+';'.join(e['path'] for e in cp),'com.bilipai.desktop.player.MpvCapabilityProofKt']
dump(out/'runtime-command.json',cmd)
r=subprocess.run(cmd,capture_output=True,encoding='utf-8',errors='replace',timeout=45)
(out/'runtime.log').write_text(r.stdout+r.stderr,encoding='utf-8');print(r.stdout+r.stderr)
after=pins();assert before==after
assert prospective_before==[row(p) for p in prospective.rglob('*') if p.is_file()]
assert native_before==row(native)
dump(out/'runtime-result.json',dict(exit=r.returncode,pinsUnchanged=True,nativeUnchanged=True,prospectiveUnchanged=True,
 productionSourceOverrides=1,snapshot=73,entries=101,MainShellAccepted=False,actualDisplayAccepted=False))
sys.exit(r.returncode)
