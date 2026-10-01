from pathlib import Path
import hashlib, importlib.util, json, subprocess, sys, zipfile
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent
MAIN=next(p for p in HERE.parents if (p/'.git').exists())
SNAP=MAIN/'desktop/.local/stable-product-snapshot-11'
def safe(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def write(p,s):safe(p.parent).mkdir(parents=True,exist_ok=True);safe(p).write_text(s,encoding='utf-8',newline='\n')
def save(p,s):write(p,json.dumps(s,ensure_ascii=False,indent=2)+'\n')
def compiler():
 spec=importlib.util.spec_from_file_location('existing_compiler',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c);return c
def runtime():
 assert sha(SNAP/'manifest.json')=='22ceb7285e89fb0ead5731a8035f98f7e3fc0790be264699671edb921aa924c7'
 assert sha(SNAP/'ordered-runtime-cp.json')=='a158698f0c336c7c28d63efc3b07f830576a70c1432a877d5ca2f92aaa3237fa'
 cp=json.loads(safe(SNAP/'ordered-runtime-cp.json').read_text());assert len(cp)==92;return cp
def pins(cp):
 rows=[dict(path=x['path'],expected=x['sha256Bytes'],actual=sha(x['path'])) for x in cp];assert all(x['actual']==x['expected'] for x in rows);return rows
def compile_sources(out,sources,target,cp,friend):
 c=compiler();args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(cp),'-Xfriend-paths='+','.join(friend),'-module-name','prepared_download_notification','-d',str(target)]+list(map(str,sources))
 write(out/'compiler.args','\n'.join('"'+str(a).replace('\\','/')+'"' for a in args)+'\n')
 p=subprocess.run([str(c.JAVA),'-Xmx2g','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(out/'compiler.args')],capture_output=True,text=True,encoding='utf-8',timeout=120)
 write(out/'compiler.log',p.stdout+p.stderr);return p
def main():
 out=HERE/('compile-'+(sys.argv[1] if len(sys.argv)>1 else '01'));assert not safe(out).exists();safe(out).mkdir()
 cp=runtime();save(out/'runtime-pins-before.json',pins(cp))
 sources=[];rows=[]
 for p in safe(HERE/'prepared/desktop/src/main/kotlin').rglob('*.kt'):
  dest=out/'source-inputs'/p.relative_to(safe(HERE/'prepared'));safe(dest.parent).mkdir(parents=True,exist_ok=True);safe(dest).write_bytes(p.read_bytes());sources.append(dest);rows.append(dict(source=str(p),compileInput=str(dest),sha256Bytes=sha(dest)))
 target=out/'prepared-download-notification.jar';p=compile_sources(out,sources,target,[x['path'] for x in cp],[str(SNAP/'main-kotlin.jar')]);save(out/'runtime-pins-after.json',pins(cp))
 result=dict(status='PASS' if p.returncode==0 else 'FAIL',sourceInputs=rows,exitCode=p.returncode,actualStable11ManifestSha256Bytes=sha(SNAP/'manifest.json'),actualCp92Sha256Bytes=sha(SNAP/'ordered-runtime-cp.json'),noMainEdits=True,noGradle=True,preparedOnly=True)
 if not p.returncode:
  with zipfile.ZipFile(safe(target)) as z:own={x for x in z.namelist() if x.endswith('.class')}
  with zipfile.ZipFile(safe(SNAP/'main-kotlin.jar')) as z:actual={x for x in z.namelist() if x.endswith('.class')}
  result.update(artifactSha256Bytes=sha(target),classOverlapWithActual=sorted(own&actual));assert not result['classOverlapWithActual']
 save(out/'compile-result.json',result);print(json.dumps(dict(status=result['status'],sourceCount=len(rows),sha256Bytes=sha(out/'compile-result.json'))));print((p.stdout+p.stderr)[-12000:]);return p.returncode
if __name__=='__main__':raise SystemExit(main())
