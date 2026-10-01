"""Freeze source derivation, package a single source-only producer, audit JVM symbols."""
from pathlib import Path
import ast, difflib, hashlib, importlib.util, json, re, struct, subprocess, sys, zipfile
sys.dont_write_bytecode = True
P=Path(__file__).resolve().parent; MAIN=P.parents[2]; REPO=MAIN.parent/'BiliPai-v023'
C='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'; SNAP=MAIN/'desktop/.local/stable-product-snapshot-65'
FEATURE='original-video-detail-holder-full'
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def read(p):return wide(p).read_bytes().replace(b'\r\n',b'\n').decode('utf8')
def sha(v):return hashlib.sha256(v.encode() if isinstance(v,str) else v).hexdigest()
def put(p,t):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes(t.encode() if isinstance(t,str) else t)
def save(p,v):put(p,json.dumps(v,ensure_ascii=False,indent=2)+'\n')
def load(n):return json.loads(read(P/n))
spec=importlib.util.spec_from_file_location('tokens',REPO/'desktop/tools/sync-upstream.py');parser=importlib.util.module_from_spec(spec);spec.loader.exec_module(parser)
def parts(t):
 tokens=parser.kotlin_tokens(t);depth=pa=br=0;starts=[]
 for i,(w,a,b) in enumerate(tokens):
  if depth==pa==br==0 and w in ['fun','val','var','class','object','interface']:
   n=tokens[i+1][0]
   if w=='fun':n=re.search(r'(\w+)\s*\(',t[b:])[1]
   line=t.rfind('\n',0,a)+1
   while line>0:
    prior=t.rfind('\n',0,line-1)+1
    if t[prior:line].strip().startswith('@'):line=prior
    else:break
   starts.append((n,line))
  depth+=(w=='{')-(w=='}');pa+=(w=='(')-(w==')');br+=(w=='[')-(w==']')
 return t[:starts[0][1]],[(n,t[a:starts[i+1][1] if i+1<len(starts) else len(t)]) for i,(n,a) in enumerate(starts)]
def forward(t,edits):
 history=[]
 for e in edits:
  if 'at' in e:
   a=e['at'];assert t[a:a+len(e['before'])]==e['before'],e['label']
   t=t[:a]+e['after']+t[a+len(e['before']):];history.append((a,e))
  else:
   positions=[];pos=0
   while True:
    a=t.find(e['before'],pos)
    if a<0:break
    positions.append(a);pos=a+len(e['before'])
   assert len(positions)==e.get('count',1),(e.get('label'),len(positions),e.get('count',1))
   for a in reversed(positions):
    t=t[:a]+e['after']+t[a+len(e['before']):];history.append((a,e))
 return t,history
def inverse(t,history):
 for a,e in reversed(history):
  assert t[a:a+len(e['after'])]==e['after'],e.get('label')
  t=t[:a]+e['before']+t[a+len(e['after']):]
 return t
audits={n:load(n+'-source-audit.json') for n in ['composer','pure','adapter','holder','platform-effects','missing','send-settings','holder-settings']}
follow={r['output']:r for r in load('followup-source-audit.json')['rows']}
rows=[]
for name,a in audits.items():
 for r in a.get('rows',[a]):
  origin=r.get('source',r.get('path'));raw=read(P/'original-stable'/origin)
  assert subprocess.check_output(['git','-C',str(REPO),'show',C+':'+origin]).replace(b'\r\n',b'\n').decode()==raw
  assert sha(raw)==r.get('originalSHA256LF',sha(raw))
  if name in ['pure','missing']:
   h,d=parts(raw);selected=h+''.join(b for n,b in d if n in r['retainedDeclarations'])
   if name=='missing' and origin.endswith('PlayerOrientationPolicy.kt'):
    selected=h+''.join(b for n,b in d if n in r['retainedDeclarations'] and not(n=='resolvePlayerWindowOrientationPolicy' and 'displayContext: AppDisplayContext,' not in b[:b.index('{') if '{'in b else len(b)]))
  elif name in ['send-settings','holder-settings']:
   body=raw[raw.index('{',raw.index('object SettingsManager'))+1:raw.rfind('}')]
   _,d=parts(body);selected='\n'.join(b for n,b in d if n in r['selectedMembers'])
   assert sha(selected)==r['originalSelectedBodySHA256LF']
   # The selected member algorithms are exact original chunks. Only object/import wrapper is platform source.
   output=('com/android/purebilibili/core/store/DesktopOriginalVideoHolderSendSettings.kt' if name=='send-settings' else 'com/android/purebilibili/core/store/DesktopOriginalVideoHolderSettings.kt')
   t=read(P/'prepared/generated'/output);assert sha(t)==r['outputSHA256LF'];assert selected in t
   rows.append(dict(output=output,source=origin,originalSHA256LF=sha(raw),outputSHA256LF=sha(t),bodySelectionExact=True,retainedDeclarations=r['selectedMembers'],inverseOriginalSelectedSHA256LF=sha(selected),audit=name+'-source-audit.json',wholeOriginal=False,edits=0))
   continue
  else:selected=raw
  if 'originalSelectedBeforeAdaptationSHA256LF' in r:assert sha(selected)==r['originalSelectedBeforeAdaptationSHA256LF']
  t,hist=forward(selected,r['edits']);assert sha(t)==r['outputSHA256LF'],(name,r.get('output'))
  assert inverse(t,hist)==selected
  output=r['output'];f=follow.get(output)
  fcount=0
  if f:
   assert sha(t)==f['beforeSHA256LF'];t,fh=forward(t,f['edits']);assert sha(t)==f['afterSHA256LF'];assert inverse(t,fh)==inverse(inverse(t,fh),[]) # deterministic indexed inverse
   assert inverse(t,fh)==forward(selected,r['edits'])[0];fcount=len(f['edits'])
  assert t==read(P/'prepared/generated'/output),output
  rows.append(dict(output=output,source=origin,originalSHA256LF=sha(raw),outputSHA256LF=sha(t),bodySelectionExact=True,retainedDeclarations=r.get('retainedDeclarations'),inverseOriginalSelectedSHA256LF=sha(selected),audit=name+'-source-audit.json',wholeOriginal=name not in ['pure','missing'] or selected==raw,edits=len(r['edits'])+fcount))
generated={p.relative_to(wide(P/'prepared/generated')).as_posix() for p in wide(P/'prepared/generated').rglob('*.kt')}
assert generated=={r['output']for r in rows},(generated-{r['output']for r in rows})
assert len(rows)==len(generated)
registry=json.loads(read(REPO/'desktop/upstream-sources.json'));existing={r['path']:r for r in registry['sources']}
specs=[]
for r in rows:
 raw=read(P/'original-stable'/r['source']);out=read(P/'prepared/generated'/r['output']);r['mode']='direct' if out==raw else 'selected'
 r['originalLines']=len(raw.splitlines());r['outputLines']=len(out.splitlines());r['existingRegistryMode']=existing.get(r['source'],{}).get('mode')
 # Exact guarded source-range transplants. Literals are solely explicit platform/selection edits;
 # source content is always read from its pinned original file, never embedded as a generated fallback.
 matcher=difflib.SequenceMatcher(None,raw.splitlines(True),out.splitlines(True),autojunk=False);ops=[];offsets=[0]
 for line in raw.splitlines(True):offsets.append(offsets[-1]+len(line))
 for tag,a,b,c,d in matcher.get_opcodes():
  if tag=='equal':ops.append(['copy',offsets[a],offsets[b]])
  elif tag in ['insert','replace']:ops.append(['literal',''.join(out.splitlines(True)[c:d])])
 rendered=''.join(raw[o[1]:o[2]]if o[0]=='copy'else o[1]for o in ops);assert rendered==out
 specs.append(dict(source=r['source'],sourceSHA256LF=sha(raw),output=r['output'],outputSHA256LF=sha(out),direct=r['mode']=='direct',operations=ops))
producer='''"""Sole full original Video detail Holder source extraction; no runtime code generation.
All source identities are v0.2.3 3d5d19a. Adaptations are audited source-range
transplants. Root supplies actual same-entry/window/MPV/domain bindings.
Production mode skips DIRECT originals; normal upstream sync copies them once.
"""
from pathlib import Path
import hashlib
SPECS = '''+repr(specs)+'''
def _wide(p):
    s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92)
    return Path(s if s.startswith(prefix) or __import__('os').name!='nt' else prefix+s)
def _sha(t):return hashlib.sha256(t.encode('utf8')).hexdigest()
def generate(repo, output, standalone=False):
    repo,output=Path(repo),Path(output);written=[]
    for s in SPECS:
        raw=_wide(repo/s['source']).read_bytes().replace(b'\\r\\n',b'\\n').decode('utf8')
        if _sha(raw)!=s['sourceSHA256LF']:raise ValueError('Original source identity mismatch: '+s['source'])
        if s['direct'] and not standalone:continue
        text=''.join(raw[o[1]:o[2]]if o[0]=='copy'else o[1]for o in s['operations'])
        if _sha(text)!=s['outputSHA256LF']:raise ValueError('Generated body mismatch: '+s['output'])
        target=_wide(output/s['output']);target.parent.mkdir(parents=True,exist_ok=True)
        target.write_bytes(text.encode('utf8'));written.append(target)
    return written
if __name__=='__main__':
    import argparse
    p=argparse.ArgumentParser();p.add_argument('repo');p.add_argument('output');p.add_argument('--standalone',action='store_true');a=p.parse_args()
    generate(a.repo,a.output,a.standalone)
'''
tool=P/'install-packet/tools/extract-upstream-video-detail-holder.py';put(tool,producer)
for p in wide(P/'prepared/manual').rglob('*.kt'):put(P/'install-packet/manual'/p.relative_to(wide(P/'prepared/manual')),read(p))
for p in wide(P/'prepared/generated').rglob('*.kt'):put(P/'install-packet/standalone-generated'/p.relative_to(wide(P/'prepared/generated')),read(p))
ctx=load('local-hunks/context-remove.json');base=read(ctx['basePath']);candidate=read(ctx['compileOnlyReferenceSource']);t=base
for h in ctx['hunks']:assert t.count(h['before'])==1;t=t.replace(h['before'],h['after'],1)
assert t==candidate and sha(base)==ctx['baseSHA256LF'] and sha(candidate)==ctx['candidateSHA256LF']
put(P/'install-packet/local-hunks/context-remove-base.kt',base);put(P/'install-packet/local-hunks/context-remove-candidate-reference.kt',candidate)
save(P/'install-packet/local-hunks/context-remove.json',{**ctx,'basePath':'local-hunks/context-remove-base.kt','compileOnlyReferenceSource':'local-hunks/context-remove-candidate-reference.kt'})
spec=importlib.util.spec_from_file_location('production',tool);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
for name,standalone in [('production-output',False),('standalone-output',True)]:
 output=P/'producer-audit'/name;assert not wide(output).exists();m.generate(P/'original-stable',output,standalone)
 actual={p.relative_to(wide(output)).as_posix():sha(read(p))for p in wide(output).rglob('*.kt')}
 expected={r['output']:r['outputSHA256LF']for r in rows if standalone or r['mode']!='direct'};assert actual==expected
save(P/'source-checks.json',dict(passed=True,sourceCommit=C,generatedOutputs=len(rows),directOutputs=sum(r['mode']=='direct'for r in rows),selectedOutputs=sum(r['mode']=='selected'for r in rows),originalIdentities=len({r['source']for r in rows}),allOriginalGitBytePinsVerified=True,baseAndFollowupInverseExact=True,productionDefaultSkipsAllDirect=True,productionAndStandaloneExact=True,rows=rows,productRuntimeAccepted=False))
ids=[]
for origin in sorted({r['source']for r in rows}):
 group=[r for r in rows if r['source']==origin];old=existing.get(origin)
 ids.append(dict(path=origin,sha256=group[0]['originalSHA256LF'],features=[FEATURE],mode='direct'if all(r['mode']=='direct'for r in group)else 'selected',existingIdentity=old is not None,existingMode=old.get('mode')if old else None,outputs=[r['output']for r in group],mergeRule='union features; preserve existing source hash and mode except explicit reviewed canonical DIRECT promotion; never replace full registry'))
save(P/'install-packet/registry-delta.json',dict(upstreamCommit=C,feature=FEATURE,identities=ids,installationRequiresActualSourceIdentityMatch=True))
save(P/'install-packet/source-inventory.json',dict(sourceCommit=C,rows=rows,manual=[dict(path=p.relative_to(wide(P/'install-packet')).as_posix(),sha256LF=sha(read(p)))for p in wide(P/'install-packet/manual').rglob('*.kt')],localContextHunk=ctx,compileOnlyContextWholeFileMustNotBeInstalled=True))
print('Source audit/sole producer PASS',len(rows),'outputs',len(ids),'identities')
