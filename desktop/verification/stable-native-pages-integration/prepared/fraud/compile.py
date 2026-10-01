from pathlib import Path
import hashlib,importlib.util,json,subprocess,sys,zipfile
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent
MAIN=next(p for p in HERE.parents if (p/'.git').exists())
SNAP=MAIN/'desktop/.local/stable-product-snapshot-11'
def safe(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def write(p,s):safe(p.parent).mkdir(parents=True,exist_ok=True);safe(p).write_text(s,encoding='utf-8',newline='\n')
def save(p,s):write(p,json.dumps(s,ensure_ascii=False,indent=2)+'\n')
def main():
 out=HERE/('compile-'+(sys.argv[1] if len(sys.argv)>1 else '01'));assert not safe(out).exists();safe(out).mkdir()
 assert sha(SNAP/'manifest.json')=='22ceb7285e89fb0ead5731a8035f98f7e3fc0790be264699671edb921aa924c7'
 assert sha(SNAP/'ordered-runtime-cp.json')=='a158698f0c336c7c28d63efc3b07f830576a70c1432a877d5ca2f92aaa3237fa'
 cp=json.loads(safe(SNAP/'ordered-runtime-cp.json').read_text());assert len(cp)==92
 def pins():
  r=[dict(path=x['path'],expected=x['sha256Bytes'],actual=sha(x['path'])) for x in cp]
  assert all(x['expected']==x['actual'] for x in r);return r
 save(out/'runtime-pins-before.json',pins())
 safe(out/'input-actual-stable11-manifest.json').write_bytes(safe(SNAP/'manifest.json').read_bytes())
 safe(out/'input-ordered-runtime-cp.json').write_bytes(safe(SNAP/'ordered-runtime-cp.json').read_bytes())
 spec=importlib.util.spec_from_file_location('existing_compiler',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
 inputs=[p for folder in ['generated','platform','proof-only','dependency-inputs'] for p in safe(HERE/folder).rglob('*.kt')]
 assert len(inputs)==5
 rows=[];sources=[]
 for p in inputs:
  copy=out/'source-inputs'/p.name;safe(copy.parent).mkdir(parents=True,exist_ok=True);safe(copy).write_bytes(safe(p).read_bytes());sources.append(copy);rows.append(dict(source=str(p),compileInput=str(copy),sha256Bytes=sha(copy)))
 target=out/'prepared-fraud-protocol.jar'
 args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(x['path'] for x in cp),'-Xfriend-paths='+str(SNAP/'main-kotlin.jar'),'-module-name','prepared_fraud_protocol','-d',str(target)]+list(map(str,sources))
 write(out/'compiler.args','\n'.join('"'+str(a).replace('\\','/')+'"' for a in args)+'\n')
 p=subprocess.run([str(c.JAVA),'-Xmx2g','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(out/'compiler.args')],capture_output=True,text=True,encoding='utf-8',timeout=180)
 write(out/'compiler.log',p.stdout+p.stderr);save(out/'runtime-pins-after.json',pins())
 r=dict(status='PASS' if p.returncode==0 else 'FAIL',sourceInputs=rows,exitCode=p.returncode,actualStableSnapshot11Sha256Bytes=sha(SNAP/'manifest.json'),actualCp92Sha256Bytes=sha(SNAP/'ordered-runtime-cp.json'),preparedOnly=True,noMainEdits=True,noGradle=True,noHTTP=True,noHWND=True)
 if p.returncode==0:
  with zipfile.ZipFile(safe(target)) as z:r.update(artifactSha256Bytes=sha(target),declaredOwnClassEntries=sorted(n for n in z.namelist() if n.endswith('.class')))
 save(out/'compile-result.json',r);print(json.dumps(dict(status=r['status'],resultSha256Bytes=sha(out/'compile-result.json'))));print((p.stdout+p.stderr)[-12000:]);return p.returncode
if __name__=='__main__':raise SystemExit(main())
