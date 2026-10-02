from pathlib import Path
import ast,difflib,hashlib,importlib.util,json,os,sys
sys.dont_write_bytecode=True;sys.stdout.reconfigure(encoding='utf8')
P=Path(__file__).resolve().parent;M=P.parents[2];C=M.parent/'BiliPai-v023'
def wide(p):
 s=str(p);return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+os.path.abspath(s))
def read(p):return wide(p).read_text(encoding='utf8').replace('\r\n','\n')
def sha(t):return hashlib.sha256(t.encode()).hexdigest()
def load(name,path):
 spec=importlib.util.spec_from_file_location(name,wide(path));m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
producer=P/'baseline/desktop/tools/extract-upstream-bgm-detail.py';m=load('original_bgm_audit',producer)
shared=load('original_reply_audit',C/'desktop/tools/extract-upstream-dynamic-reply-protocol.py')
original,identities=shared.load_pinned_sources(C,m.SOURCES)
paths=[m.BASE+'feature/comment/CommentDetailViewModel.kt',m.BASE+'feature/comment/CommentDetailScreen.kt']
tracked={p:original[p] for p in paths};edits={p:[] for p in paths}
def trace(frame,event,arg):
 if frame.f_code is m.generate_comment_detail.__code__ and event=='line':
  p=frame.f_locals.get('path');t=frame.f_locals.get('body')
  if p in tracked and isinstance(t,str) and t!=tracked[p]:
   # Trace captures each actually executed canonical platform adaptation. Text
   # deltas invert back to the registry-verified full stable file, not a count.
   old=tracked[p];segments=[]
   oldLines=old.splitlines(keepends=True);newLines=t.splitlines(keepends=True)
   oldOffsets=[0];newOffsets=[0]
   for line in oldLines:oldOffsets.append(oldOffsets[-1]+len(line))
   for line in newLines:newOffsets.append(newOffsets[-1]+len(line))
   for tag,i,j,a,b in difflib.SequenceMatcher(None,oldLines,newLines,autojunk=False).get_opcodes():
    if tag!='equal':
     i,j,a,b=oldOffsets[i],oldOffsets[j],newOffsets[a],newOffsets[b]
     segments.append(dict(beforeStart=i,beforeEnd=j,afterStart=a,afterEnd=b,before=old[i:j],after=t[a:b]))
   edits[p].append(dict(line=frame.f_lineno,beforeSha256LF=sha(old),afterSha256LF=sha(t),segments=segments));tracked[p]=t
 return trace
baseline={}
sys.settrace(trace)
try:m.generate_comment_detail(original,shared,lambda rel,t:baseline.__setitem__(rel,t),[])
finally:sys.settrace(None)
records=[]
prospective=load('comment_delta_audit',P/'prepared/existing/desktop/tools/extract-upstream-bgm-detail.py')
for p in paths:
 rel=p.removeprefix(m.BASE);out='com/android/purebilibili/'+rel
 base=baseline[out];assert base==read(C/'desktop/build/generated/bgm-detail'/out)
 generated=prospective.comment_detail_root_entry_delta(out,base)
 assert generated==read(P/'reference'/out)==read(P/'replay'/out)
 # Own hook uses finite constant replace operations; extract its actual edits
 # via AST and apply them in reverse with the same exact count assertions.
 tree=ast.parse(read(P/'prepared/existing/desktop/tools/extract-upstream-bgm-detail.py'))
 fn=next(x for x in tree.body if isinstance(x,ast.FunctionDef)and x.name=='comment_detail_root_entry_delta')
 branch=next(x for x in fn.body if isinstance(x,ast.If)and ast.literal_eval(x.test.comparators[0])==out)
 before=None;own=[]
 for node in branch.body:
  if isinstance(node,ast.Assign)and isinstance(node.targets[0],ast.Name):
   if node.targets[0].id=='before':before=ast.literal_eval(node.value)
   elif node.targets[0].id=='after':own.append((before,ast.literal_eval(node.value)))
 inverse=generated
 for b,a in reversed(own):
  assert inverse.count(a)==1,(p,'own inverse');inverse=inverse.replace(a,b)
 assert inverse==base
 for edit in reversed(edits[p]):
  assert sha(inverse)==edit['afterSha256LF']
  for segment in reversed(edit['segments']):
   a,b=segment['afterStart'],segment['afterEnd'];assert inverse[a:b]==segment['after']
   inverse=inverse[:a]+segment['before']+inverse[b:]
  assert sha(inverse)==edit['beforeSha256LF']
 assert inverse==original[p]
 records.append(dict(originalPath=p,originalSha256LF=sha(original[p]),originalLines=len(original[p].splitlines()),existingCanonicalSha256LF=sha(base),prospectiveSha256LF=sha(generated),fullOriginalInverseByteEqual=True,canonicalTransforms=edits[p],newFiniteTransforms=own))
rows=json.loads(read(C/'desktop/upstream-sources.json'))
if isinstance(rows,dict):rows=rows.get('sources',rows.get('entries',[]))
selected={r.get('path',r.get('originalPath')):r for r in rows}
for p in paths:assert p in selected,(p,'existing identity required')
hunks=json.loads(read(P/'exact-hunks.json'));targets=json.loads(read(P/'targets.json'))
for target in targets:
 rel=target['path'];s=read(P/'baseline'/rel);assert sha(s)==target['beforeSha256LF']
 assert sha(read(C/rel)) in [target['beforeSha256LF'],target['afterSha256LF']],(rel,'Live source has unrelated changes; use frozen baseline audit')
 hs=[h for h in hunks if h['path']==rel]
 for h in hs:
  assert s.count(h['before'])==1;s=s.replace(h['before'],h['after'])
 assert sha(s)==target['afterSha256LF'] and s==read(P/'prepared/existing'/rel)
 for h in reversed(hs):
  assert s.count(h['after'])==1;s=s.replace(h['after'],h['before'])
 assert sha(s)==target['beforeSha256LF']
result=dict(passed=True,soleProducer=str(producer),newOriginalIdentities=0,existingScreenVMIdentities=[dict(path=p,row=selected[p])for p in paths],fullOriginalBodies=records,targetsExactForwardInverse=True,hunks=len(hunks),families=len(targets),productionWrites=0)
wide(P/'source-inverse-audit.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
print('PASS full stable VM/Screen inverse, 16 exact edits and existing identities')
