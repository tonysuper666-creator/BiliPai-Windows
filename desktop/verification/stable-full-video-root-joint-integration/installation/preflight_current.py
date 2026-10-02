from pathlib import Path
import difflib,hashlib,json,subprocess,sys
sys.stdout.reconfigure(encoding='utf-8');sys.dont_write_bytecode=True
H=Path(__file__).resolve().parent;MAIN=H.parents[2];REPO=MAIN.parent/'BiliPai-v023'
import packet_inventory_current as inspection
# This file does not mutate Candidate. Only canonical source payloads/local hunks
# are composed. Generated snapshots, proof inputs and class/JAR files are excluded.
def sha(b):return hashlib.sha256(b).hexdigest()
def read(p):return inspection.read(p)
def put(p,b):
 p=inspection.wide(p);p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(b)
def normalized(v,target=None):
 if isinstance(v,list):
  for row in v:yield from normalized(row,target)
 elif isinstance(v,dict):
  candidate=next((v[k]for k in ('target','path','relativePath','file','producer','family')if isinstance(v.get(k),str)),target)
  if 'before'in v and 'after'in v:
   yield dict(target=candidate,before=v['before'],after=v['after'],count=v.get('count',1),label=v.get('label','local source hunk'))
  else:
   for key in ('families','groups','hunks','edits','patches','exactHunks'):
    if key in v:yield from normalized(v[key],candidate)
def target_path(p):
 if p is None:return None
 p=str(p).replace('\\','/')
 if Path(p).is_absolute():
  if not Path(p).resolve().is_relative_to(REPO.resolve()):return None
  p=str(Path(p).relative_to(REPO)).replace('\\','/')
 if not p.startswith(('desktop/src/','desktop/tools/'))or '..'in Path(p).parts:return None
 return p
def payload_target(relative):
 if relative.startswith('manual/'):
  p=relative.removeprefix('manual/')
 elif relative.startswith('prepared/manual/'):
  p=relative.removeprefix('prepared/manual/')
 elif relative.startswith('prepared/tools/'):
  return 'desktop/tools/'+relative.removeprefix('prepared/tools/')
 else:return None
 return p if p.startswith('desktop/')else 'desktop/src/main/kotlin/'+p
HEAD=subprocess.check_output(['git','rev-parse','HEAD'],cwd=REPO,text=True).strip()
assert HEAD=='462507f8a175a74a823fa3ea138f6b490c20a953'
assert not subprocess.check_output(['git','status','--porcelain'],cwd=REPO)
packets=inspection.inspect();texts={};base={};payloads=[];edits=[];conflicts=[];expectedDeltas=[]
def text(relative):
 if relative not in texts:
  b=read(REPO/relative).replace(b'\r\n',b'\n');base[relative]=b;texts[relative]=b.decode()
 return texts[relative]
for packet,(name,_,hunkNames)in zip(packets,inspection.PACKETS):
 lane=inspection.BASE/name
 manifest=json.loads(read(lane/'frozen-handoff.json'))
 rows=inspection.artifact_rows(manifest)
 for row in rows:
  relative=row.get('relativePath',row.get('path'));p=Path(relative);p=p if p.is_absolute()else lane/p
  relative=str(p.relative_to(lane)).replace('\\','/');target=payload_target(relative)
  if target is None:continue
  b=read(p).replace(b'\r\n',b'\n')
  if name=='stable-unified-subtitle-notification-retirement-parity':
   # This frozen delta explicitly forbids installing its complete desired copy.
   # Only its two literal deletions are composed below; compare the result.
   expectedDeltas.append(dict(target=target,sha256LF=sha(b)));continue
  if target in texts or (REPO/target).exists():
   conflicts.append(dict(packet=name,target=target,kind='new payload destination already exists'));continue
  texts[target]=b.decode();payloads.append(dict(packet=name,path=relative,target=target,sha256LF=sha(b)))
 for filename in hunkNames:
  v=json.loads(read(lane/filename))
  default='desktop/tools/extract-upstream-video-full-owner.py'if filename=='submission-producer-hunks.json'else None
  if filename=='audio-caller-transform.json':
   # Append the frozen declaration and its one original composition anchor.
   declaration=read(lane/v['transform']).decode().replace('\r\n','\n')
   anchor='def generate(repo,output,standalone=False):'
   hunks=[dict(target=v['producer'],before=anchor,after=declaration+'\n\n'+anchor,count=1,label='same sole producer frozen audio transform declaration'),
          dict(target=v['producer'],before=v['composeBefore'],after=v['composeAfter'],count=1,label='same sole producer original audio invocation composition')]
  else:hunks=list(normalized(v,default))
  if name=='stable-video-owner-download-binding-parity' and filename=='exact-hunks.json':
   bridgePath=inspection.BASE/'stable-video-full-owner-root-mount-parity/download-assets-merge-hunks.json'
   bridgeBytes=read(bridgePath)
   assert sha(bridgeBytes)=='7a27b22cfb2313b8c8e96fad10868eb812296b15268139cc6889e9620683c1ed'
   reviewPath=inspection.BASE/'stable-download-section-assets-merge-review/frozen-review.json'
   assert sha(read(reviewPath))=='fe075b3cd462ab695d6ca0732d1a81ca3f8eb5153bd472b78bcdcc569152ecb4'
   bridge=json.loads(bridgeBytes)
   asset='desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopDynamicImageAssets.kt'
   assert sha(text(asset).encode())==bridge['baseSHA256LF']
   assert sum(h['target']==asset for h in hunks)==1
   hunks=[h for h in hunks if h['target']!=asset]+list(normalized(bridge))
  assert hunks,(name,filename,'canonical local edits must be explicit')
  for i,hunk in enumerate(hunks):
   target=target_path(hunk['target'])
   if target is None:
    conflicts.append(dict(packet=name,file=filename,index=i,kind='missing/unsupported production target',target=hunk['target']));continue
   if target not in texts and not (REPO/target).exists():
    conflicts.append(dict(packet=name,file=filename,index=i,target=target,kind='source dependency not yet in canonical payload graph'));continue
   before=hunk['before'];after=hunk['after'];expected=hunk['count'];current=text(target)
   assert isinstance(before,str)and isinstance(after,str)and before and isinstance(expected,int)and expected>0
   count=current.count(before)
   if count!=expected:
    conflicts.append(dict(packet=name,file=filename,index=i,target=target,label=hunk['label'],kind='local anchor conflict',expected=expected,actual=count,beforeSHA256LF=sha(before.encode())));continue
   texts[target]=current.replace(before,after,expected)
   edits.append(dict(packet=name,file=filename,index=i,target=target,count=expected,beforeSHA256LF=sha(before.encode()),afterSHA256LF=sha(after.encode())))
run=int(sys.argv[1])if len(sys.argv)>1 else 1
OUT=H/f'preflight{run:02}';assert not OUT.exists();OUT.mkdir()
for row in expectedDeltas:assert sha(texts[row['target']].encode())==row['sha256LF']
for relative,t in texts.items():
 b=t.encode();put(OUT/'source'/relative,b)
 before=base.get(relative,b'').decode()
 diff=''.join(difflib.unified_diff(before.splitlines(True),t.splitlines(True),fromfile='Candidate/'+relative,tofile='Prospective/'+relative))
 put(OUT/'diffs'/(Path(relative).name+'.diff'),diff.encode())
assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=REPO,text=True).strip()==HEAD
assert not subprocess.check_output(['git','status','--porcelain'],cwd=REPO)
report=dict(candidateHead=HEAD,candidateModified=False,packetsVerified=len(packets),rawArtifactsVerified=sum(p['rawVerified']for p in packets),
 canonicalNewPayloads=payloads,localEditsAppliedInMemory=edits,conflicts=conflicts,
 existingHunkGraphReady=not conflicts,sourceFamilies=len(texts),rootShellSwitchFrozen=False,
 registryAndGradleRecipeApplied=False,all37RootInstalled=False,wholeMainCompiled=False,nativeWindowAccepted=False,
 sourceOutputs=[dict(path=r,sha256LF=sha(t.encode()),new=r not in base)for r,t in texts.items()])
put(OUT/'result.json',(json.dumps(report,indent=2)+'\n').encode())
print(json.dumps(dict(packetsVerified=len(packets),rawArtifactsVerified=report['rawArtifactsVerified'],canonicalPayloads=len(payloads),
 localEdits=len(edits),sourceFamilies=len(texts),conflicts=conflicts,candidateModified=False),indent=2))
