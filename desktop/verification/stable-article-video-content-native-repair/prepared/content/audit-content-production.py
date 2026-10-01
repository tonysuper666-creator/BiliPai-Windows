from pathlib import Path
import hashlib,json,importlib.util,sys,difflib
sys.dont_write_bytecode=True
P=Path(__file__).resolve().parent;REPO=P.parents[2].parent/'BiliPai-v023'
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
def read(p):return wide(p).read_bytes()
def sha(b):return hashlib.sha256(b).hexdigest()
def save(p,v):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
out=P/('content-production-proof-'+(sys.argv[1]if len(sys.argv)>1 else '01'));assert not wide(out).exists();wide(out).mkdir(parents=True)
s=importlib.util.spec_from_file_location('sole_full_content',P/'prepared/desktop/tools/extract-upstream-video-content-full.py');m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
rows=[]
for label,standalone in [('default',False),('standalone',True)]:
 target=out/label;m.generate(REPO,target,standalone=standalone)
 emitted=[];skipped=[]
 for r in m.OUTPUTS:
  p=target/r['path'];expected=P/'generated'/r['path']
  if r['generated']:
   assert read(p)==read(expected),r['path'];emitted.append(r['path'])
  else:
   assert not wide(p).exists(),r['path'];skipped.append(r['path'])
 rows.append(dict(mode=label,outputs=emitted,skippedDirectOutputs=skipped,byteIdentical=True))
inv=json.loads(read(P/'content-source-selection.json'));inverse=[]
for r in inv['outputs']:
 original=read(P/'original-stable'/r['origin']);actual=read(P/'generated'/r['path'])
 if r['mode']=='direct-complete-original':assert original==actual;inverse.append(dict(path=r['path'],mode=r['mode'],exactOriginalBytes=True));continue
 if r['mode'].startswith('complete-original-related')or r['mode'].startswith('complete-original-content-groups'):
  a=original.splitlines(keepends=True);b=actual.splitlines(keepends=True);matcher=difflib.SequenceMatcher(None,a,b,autojunk=False)
  changes=[dict(tag=t,originalStart=x,originalEnd=y,generatedStart=u,generatedEnd=v,originalLines=[z.decode()for z in a[x:y]],generatedLines=[z.decode()for z in b[u:v]])for t,x,y,u,v in matcher.get_opcodes()if t!='equal']
  restored=list(b)
  for d in reversed(changes):restored[d['generatedStart']:d['generatedEnd']]=[z.encode()for z in d['originalLines']]
  assert b''.join(restored)==original
  save(out/'inverse'/(Path(r['path']).name+'.json'),dict(source=r['origin'],sourceSha256LF=sha(original),generatedSha256LF=sha(actual),inverseExactOriginal=True,lineChanges=changes))
  inverse.append(dict(path=r['path'],mode=r['mode'],inverseExactOriginal=True,changes=len(changes)))
 else:inverse.append(dict(path=r['path'],mode=r['mode'],sourceSelection='Exact original whole named declaration or remaining full renderer. Registry counts this as selected, not an entire original file.'))
save(out/'result.json',dict(passed=True,generation=rows,inverseAudits=inverse,sourcePins=len(inv['sources']),noProductAcceptance=True,declaredSharedReferences=['Original entire VideoCommentTab is supplied by existing extract-upstream-video-comment-ui.py','Original cover ratio constant is supplied by existing BGM skeleton producer','Original CollectionRow/Sheet and image preview remain sole installed consumers','Original VideoNote/AI raw DTOs and note codec already in actual47'],producerSha256LF=sha(read(P/'prepared/desktop/tools/extract-upstream-video-content-full.py'))))
print(json.dumps(dict(passed=True,productionSelectedOutputs=len(rows[0]['outputs']),directSkipped=len(rows[0]['skippedDirectOutputs']),standaloneOutputs=len(rows[1]['outputs']),inverseWholeSourceFiles=sum(bool(r.get('inverseExactOriginal')or r.get('exactOriginalBytes'))for r in inverse))))
