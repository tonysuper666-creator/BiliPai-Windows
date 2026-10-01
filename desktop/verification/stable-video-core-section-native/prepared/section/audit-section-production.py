from pathlib import Path
import hashlib,json,importlib.util,sys,difflib
sys.dont_write_bytecode=True
P=Path(__file__).resolve().parent;REPO=P.parents[2].parent/'BiliPai-v023'
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
def read(p):return wide(p).read_bytes().replace(b'\r\n',b'\n')
def sha(b):return hashlib.sha256(b).hexdigest()
def save(p,v):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes((json.dumps(v,ensure_ascii=False,indent=2)+'\n').encode())
out=P/('section-production-proof-'+sys.argv[1]);assert not wide(out).exists();wide(out).mkdir(parents=True)
s=importlib.util.spec_from_file_location('sole_section',P/'prepared/desktop/tools/extract-upstream-video-player-section-full.py');m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
rows=[]
for label,standalone in [('default',False),('standalone',True)]:
 target=out/label;m.generate(REPO,target,standalone=standalone);emitted=[];skipped=[]
 for r in m.OUTPUTS:
  p=target/r['path'];expected=next((q/r['path']for q in [P/'generated',P/'section-generated']if wide(q/r['path']).exists()),None);assert expected,r['path']
  if r['generated']:assert read(p)==read(expected),r['path'];emitted.append(r['path'])
  else:assert not wide(p).exists(),r['path'];skipped.append(r['path'])
 rows.append(dict(mode=label,outputs=emitted,skippedDirectOutputs=skipped,byteIdentical=True))
inverse=[]
for r in m.OUTPUTS:
 generated=next(q/r['path']for q in [P/'generated',P/'section-generated']if wide(q/r['path']).exists())
 original=m.BASE+r['path'].removeprefix('com/android/purebilibili/')
 if original not in m.SOURCE_PINS:continue
 a=read(REPO/original);b=read(generated);changes=[]
 for tag,x,y,u,v in difflib.SequenceMatcher(None,a.splitlines(keepends=True),b.splitlines(keepends=True),autojunk=False).get_opcodes():
  if tag!='equal':changes.append(dict(tag=tag,originalStart=x,originalEnd=y,generatedStart=u,generatedEnd=v,originalLines=[z.decode()for z in a.splitlines(keepends=True)[x:y]],generatedLines=[z.decode()for z in b.splitlines(keepends=True)[u:v]]))
 restored=b.splitlines(keepends=True)
 for d in reversed(changes):restored[d['generatedStart']:d['generatedEnd']]=[z.encode()for z in d['originalLines']]
 assert b''.join(restored)==a
 save(out/'inverse'/(Path(r['path']).name+'.json'),dict(source=original,sourceSha256LF=sha(a),generatedSha256LF=sha(b),inverseExactOriginal=True,lineChanges=changes,scope='Full original source with explicit platform adaptations. This diff is an auditable reversal, not a claim every line is original.'))
 inverse.append(dict(path=r['path'],exactOriginalBytes=not changes,inverseExactOriginal=True,changes=len(changes)))
save(out/'result.json',dict(passed=True,generation=rows,inverseAudits=inverse,sourcePins=len(m.SOURCE_PINS),producerSha256LF=sha(read(P/'prepared/desktop/tools/extract-upstream-video-player-section-full.py')),noProductAcceptance=True,wholeRendererExplicitPlatformBlockEdits=len(m.EDITS),manualContractsRequired=True))
print(json.dumps(dict(passed=True,productionSelected=len(rows[0]['outputs']),directSkipped=len(rows[0]['skippedDirectOutputs']),standalone=len(rows[1]['outputs']),wholeOriginalInverseFiles=len(inverse))))
