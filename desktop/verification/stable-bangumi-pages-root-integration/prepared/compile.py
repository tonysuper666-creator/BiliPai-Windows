from pathlib import Path
import hashlib,importlib.util,json,os,subprocess,sys,zipfile
sys.dont_write_bytecode=True;sys.stdout.reconfigure(encoding='utf8')
P=Path(__file__).resolve().parent;M=P.parents[2];C=M.parent/'BiliPai-v023'
def wide(p):
 s=str(p);return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+os.path.abspath(s))
def sha(p):return hashlib.sha256(wide(p).read_bytes()).hexdigest()
S=M/'desktop/.local/stable-product-snapshot-84';cp=json.loads(wide(S/'ordered-runtime-cp.json').read_text())
def pins():
 assert sha(S/'manifest.json')=='f70a306e3dc42df93c00d536163ff4a1f1953cbc94d36586c9500281983351b2'
 assert sha(S/'ordered-runtime-cp.json')=='4b1de9cce18526162113e1336c0252b37b3113d1f0b8832b68953fc3ec5df8d9'
 assert len(cp)==101
 for r in cp:assert sha(r['path'])==r['sha256Bytes'],r['path']
pins()
spec=importlib.util.spec_from_file_location('compiler',M/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
sources={}
for root in (P/'prepared',P/'generated'):
 for f in wide(root).rglob('*.kt'):sources[f.name]=f
number=sys.argv[1];out=P/'runs'/number;wide(out).mkdir(parents=True,exist_ok=False)
main=next(r['path']for r in cp if r.get('source')=='desktop/build/classes/kotlin/main')
args=['-no-stdlib','-no-reflect','-jvm-target','21','-module-name','com_bilipai_desktop_bilipai_windows','-Xplugin='+str(c.PLUGIN),'-Xfriend-paths='+main,'-cp',';'.join(r['path']for r in cp),'-d',str(out/'classes')]+[str(f)for f in sources.values()]
wide(out/'compiler.args').write_text('\n'.join('"'+a.replace('\\','/')+'"'for a in args),encoding='utf8')
r=subprocess.run([str(c.JAVA),'-Xmx5g','-Dfile.encoding=UTF-8','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(out/'compiler.args')],capture_output=True,timeout=360)
wide(out/'compile.log').write_bytes(r.stdout+r.stderr);pins()
wide(out/'result.json').write_text(json.dumps(dict(passed=r.returncode==0,entries=101,snapshot=84,sourceCount=len(sources),explicitProspectiveSources=[dict(path=str(f),sha256Bytes=sha(f))for f in sources.values()],productionWrites=0,rootRuntimeAccepted=False),ensure_ascii=False,indent=2),encoding='utf8')
if r.returncode==0:
 with zipfile.ZipFile(wide(out/'prospective.jar'),'w',zipfile.ZIP_DEFLATED)as z:
  for f in wide(out/'classes').rglob('*'):
   if f.is_file():z.writestr(f.relative_to(wide(out/'classes')).as_posix(),f.read_bytes())
print((r.stdout+r.stderr).decode('utf8',errors='replace')[-12000:]);print('PASS'if r.returncode==0 else'FAIL',len(sources),'inputs');sys.exit(r.returncode)

