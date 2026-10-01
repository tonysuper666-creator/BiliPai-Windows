from pathlib import Path
import hashlib,importlib.util,json,subprocess,sys,zipfile
sys.dont_write_bytecode=True
LANE=Path(__file__).resolve().parent;MAIN=LANE.parents[2];SNAP=MAIN/'desktop/.local/stable-product-snapshot-43';number=sys.argv[1]if len(sys.argv)>1 else'01';OUT=LANE/('compile-'+number);assert not OUT.exists();OUT.mkdir()
sha=lambda b:hashlib.sha256(b).hexdigest()
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
cp=json.loads((SNAP/'ordered-runtime-cp.json').read_bytes());assert len(cp)==97
for r in cp:assert sha(wide(r['path']).read_bytes())==r['sha256Bytes']
sources=list(wide(LANE/'prepared').rglob('*.kt'));assert len(sources)==7
spec=importlib.util.spec_from_file_location('compiler',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
jar=OUT/'candidate.jar';args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(r['path']for r in cp),'-Xplugin='+str(c.PLUGIN),'-Xfriend-paths='+str(SNAP/'main-kotlin.jar'),'-module-name','com_bilipai_desktop_bilipai_windows','-d',str(jar)]+list(map(str,sources))
argfile=OUT/'compiler.args';argfile.write_text('\n'.join('"'+a.replace('\\','/')+'"'for a in args)+'\n',encoding='utf-8')
r=subprocess.run([str(c.JAVA),'-Dfile.encoding=UTF-8','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(argfile)],capture_output=True,text=True,encoding='utf-8',timeout=180)
(OUT/'compiler.log').write_text(r.stdout+r.stderr,encoding='utf-8');assert r.returncode==0,'inspect compiler.log'
with zipfile.ZipFile(jar)as z:
 classes=[n for n in z.namelist()if n.endswith('.class')]
 for r in cp:
  with zipfile.ZipFile(wide(r['path']))as base:assert not set(classes).intersection(base.namelist()),r['path']
(OUT/'result.json').write_text(json.dumps(dict(passed=True,sourceCount=len(sources),classCount=len(classes),newFqnCollisions=0,actual43RuntimeEntries=97,productionOverrides=0,sourceInputs=[dict(path=str(p.relative_to(wide(LANE))),sha256Bytes=sha(p.read_bytes()))for p in sources],candidateJarSha256Bytes=sha(jar.read_bytes())),indent=2)+'\n',encoding='utf-8');print(json.dumps(dict(passed=True,sources=len(sources),classes=len(classes),newFqnCollisions=0)))
