from pathlib import Path
import hashlib,importlib.util,json,subprocess,sys,zipfile
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent;MAIN=next(p for p in HERE.parents if (p/'.git').exists());SNAP=MAIN/'desktop/.local/stable-product-snapshot-20'
def safe(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
def save(p,v):p.write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
def pins(cp):
 rows=[dict(path=x['path'],expected=x['sha256Bytes'],actual=sha(x['path'])) for x in cp];assert all(x['expected']==x['actual'] for x in rows);return rows
def runtime():
 original=json.loads((SNAP/'ordered-runtime-cp.json').read_text(encoding='utf-8'))
 folder=MAIN/'desktop/.local/stable-miuix5157-original-runtime';p=folder/'ordered-runtime-cp-20.json'
 assert sha(p)=='9e1365eb67f97b9930570ff87cd34b514b31d858d78cf210d31783ad87af89cf'
 assert sha(folder/'relocation-ledger.json')=='68b9a804eabd84eedaf40c8b8e2487daad060616a4bac754c1f2f7d3be4fafec'
 relocated=json.loads(p.read_text(encoding='utf-8'));assert len(relocated)==len(original)==92
 changes=[]
 for index,(before,after) in enumerate(zip(original,relocated)):
  assert before['sha256Bytes']==after['sha256Bytes']
  assert {k:v for k,v in before.items() if k!='path'}=={k:v for k,v in after.items() if k!='path'}
  if before['path']!=after['path']:changes.append(dict(index=index,before=before,after=after))
 assert len(changes)==1 and changes[0]['after']['sha256Bytes']=='78e22c70dd152058f2f7570f59906607e253231f1db346494820bd7e9db7b215'
 return relocated,dict(orderedRelocatedCpSha256Bytes=sha(p),relocationLedgerSha256Bytes=sha(folder/'relocation-ledger.json'),sameByteOnlyRelocations=changes)
def main():
 assert sha(SNAP/'manifest.json')=='41439c3242e279bc0dc09a20501bdcb36e13cda7f66ab2bbbe537e7538d3ecf2'
 assert sha(SNAP/'ordered-runtime-cp.json')=='4d1499c7ddbeeddbf31427505930ab0c3d5c6a3aaaf8dc08d2a585edca720232'
 cp,relocation=runtime()
 out=HERE/('compile-'+(sys.argv[1] if len(sys.argv)>1 else '01'));assert not out.exists();out.mkdir()
 save(out/'runtime-pins-before.json',pins(cp));save(out/'same-byte-relocation.json',relocation)
 spec=importlib.util.spec_from_file_location('compiler',MAIN/'desktop/.local/source9-appearance/compile-miuix.py');c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
 serialization=list((c.CACHE/'org.jetbrains.kotlin/kotlin-serialization-compiler-plugin-embeddable/2.4.0').rglob('*.jar'));assert len(serialization)==1
 sources=[];rows=[]
 for folder in ['generated','platform','selected-files','proof-only']:
  for p in sorted((HERE/folder).rglob('*.kt')):
   copied=out/'source-inputs'/p.name;assert not copied.exists();copied.parent.mkdir(parents=True,exist_ok=True);safe(copied).write_bytes(safe(p).read_bytes());sources.append(copied);rows.append(dict(source=str(p),input=str(copied),sha256Bytes=sha(copied)))
 target=out/'original-danmaku-settings.jar'
 args=['-no-stdlib','-no-reflect','-jvm-target','21','-cp',';'.join(x['path'] for x in cp),'-Xplugin='+str(c.PLUGIN),'-Xplugin='+str(serialization[0]),'-Xfriend-paths='+str(SNAP/'main-kotlin.jar'),'-module-name','prepared_original_danmaku_settings','-d',str(target)]+list(map(str,sources))
 (out/'compiler.args').write_text('\n'.join('"'+a.replace('\\','/')+'"' for a in args)+'\n',encoding='utf-8',newline='\n')
 p=subprocess.run([str(c.JAVA),'-Xmx3g','-cp',';'.join(map(str,c.COMPILER)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','@'+str(out/'compiler.args')],capture_output=True,text=True,encoding='utf-8',timeout=180)
 (out/'compiler.log').write_text(p.stdout+p.stderr,encoding='utf-8',newline='\n');save(out/'runtime-pins-after.json',pins(cp))
 result=dict(status='PASS' if p.returncode==0 else 'FAIL',exitCode=p.returncode,sourceCount=len(sources),sourceInputs=rows,actualMain20ManifestSha256Bytes=sha(SNAP/'manifest.json'),ordered92CpSha256Bytes=sha(SNAP/'ordered-runtime-cp.json'),preparedOnly=True)
 if not p.returncode:
  with zipfile.ZipFile(target) as z:own={n for n in z.namelist() if n.endswith('.class')}
  with zipfile.ZipFile(SNAP/'main-kotlin.jar') as z:actual={n for n in z.namelist() if n.endswith('.class')}
  overlap=sorted(own&actual);unexpected=[n for n in overlap if not n.startswith(('com/bilipai/desktop/danmaku/DanmakuSettings','com/bilipai/desktop/data/DesktopDynamicCardOperations'))];assert not unexpected,unexpected
  result.update(jarSha256Bytes=sha(target),candidateClasses=sorted(own),actualClassOverlap=overlap,unexpectedActualClassOverlap=[],declaredExistingSettingsAndOperationsOverrideOnly=True)
 save(out/'compile-result.json',result);print(json.dumps(dict(status=result['status'],sources=len(sources),resultSha256Bytes=sha(out/'compile-result.json'))));print((p.stdout+p.stderr)[-10000:]);return p.returncode
if __name__=='__main__':raise SystemExit(main())
