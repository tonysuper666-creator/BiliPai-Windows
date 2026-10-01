from pathlib import Path
import hashlib,importlib.util,json,subprocess,sys,zipfile
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent;MAIN=next(p for p in HERE.parents if (p/'.git').exists());SNAP=MAIN/'desktop/.local/stable-product-snapshot-15'
def safe(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def write(p,s):safe(p.parent).mkdir(parents=True,exist_ok=True);safe(p).write_text(s,encoding='utf-8',newline='\n')
def save(p,s):write(p,json.dumps(s,ensure_ascii=False,indent=2)+'\n')
def runtime():
 assert sha(SNAP/'manifest.json')=='c665e8cc40604866341c9ac5f6ef992ed84b52fe709a1f934337f754012cab87'
 assert sha(SNAP/'ordered-runtime-cp.json')=='bcbc863d545922aaa4308076a4d4651c219aafacf339e8cb3000e59696f9e335'
 cp=json.loads(safe(SNAP/'ordered-runtime-cp.json').read_text(encoding='utf-8'));assert len(cp)==92;return cp
def pins(cp):
 rows=[dict(path=x['path'],expected=x['sha256Bytes'],actual=sha(x['path'])) for x in cp];assert all(x['actual']==x['expected'] for x in rows);return rows
def compiler():
 spec=importlib.util.spec_from_file_location('compiler',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c);return c
def compile_sources(out,sources,target,cp,friend):
 c=compiler();args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(cp),'-Xplugin='+str(c.PLUGIN),'-Xfriend-paths='+','.join(friend),'-module-name','prepared_original_favorite_drawer','-d',str(target)]+list(map(str,sources))
 write(out/'compiler.args','\n'.join('"'+str(a).replace('\\','/')+'"' for a in args)+'\n')
 p=subprocess.run([str(c.JAVA),'-Xmx3g','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(out/'compiler.args')],capture_output=True,text=True,encoding='utf-8',timeout=150);write(out/'compiler.log',p.stdout+p.stderr);return p
def main():
 out=HERE/('compile-'+(sys.argv[1] if len(sys.argv)>1 else '01'));assert not safe(out).exists();safe(out).mkdir()
 cp=runtime();save(out/'runtime-pins-before.json',pins(cp));sources=[];rows=[]
 for root in ('generated','platform'):
  for p in sorted(safe(HERE/root).rglob('*.kt')):
   dest=out/'source-inputs'/root/p.relative_to(safe(HERE/root));safe(dest.parent).mkdir(parents=True,exist_ok=True);safe(dest).write_bytes(p.read_bytes());sources.append(dest);rows.append(dict(source=str(p),input=str(dest),sha256LF=sha(dest)))
 target=out/'original-favorite-folder.jar';p=compile_sources(out,sources,target,[x['path'] for x in cp],[str(SNAP/'main-kotlin.jar')]);save(out/'runtime-pins-after.json',pins(cp))
 result=dict(status='PASS' if p.returncode==0 else 'FAIL',exitCode=p.returncode,sourceCount=len(rows),sourceInputs=rows,actualMain15ManifestSha256Bytes=sha(SNAP/'manifest.json'),actualCp92Sha256Bytes=sha(SNAP/'ordered-runtime-cp.json'),preparedOnly=True,noMainEdits=True,noGradle=True)
 if not p.returncode:
  with zipfile.ZipFile(safe(target)) as z:own={x for x in z.namelist() if x.endswith('.class')}
  with zipfile.ZipFile(safe(SNAP/'main-kotlin.jar')) as z:actual={x for x in z.namelist() if x.endswith('.class')}
  overlaps=sorted(own&actual);assert not overlaps,overlaps
  result.update(jarSha256Bytes=sha(target),allCandidateClasses=sorted(own),actualClassOverlap=[],noOperationsStoreOrModelsOverrides=True)
 save(out/'compile-result.json',result);print(json.dumps(dict(status=result['status'],sources=len(rows),sha256Bytes=sha(out/'compile-result.json'))));print((p.stdout+p.stderr)[-12000:]);return p.returncode
if __name__=='__main__':raise SystemExit(main())
