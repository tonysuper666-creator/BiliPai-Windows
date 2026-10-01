from pathlib import Path
import hashlib,json,os,struct,zipfile
P=Path(__file__).resolve().parent;MAIN=P.parents[2];REPO=MAIN.parent/'BiliPai-v023';PFX=chr(92)*2+'?'+chr(92)
def wide(p):
 s=os.path.abspath(p);return Path(s if s.startswith(PFX)else PFX+s)
def raw(p):return wide(p).read_bytes()
def sha(p):return hashlib.sha256(raw(p)).hexdigest()
def h(s):return hashlib.sha256(s.encode()).hexdigest()
def load(p):return raw(p).decode('utf8').replace('\r\n','\n')
def save(p,v):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_text(json.dumps(v,indent=2)+'\n',encoding='utf8',newline='\n')
assert not wide(P/'frozen-handoff.json').exists()
compiled=json.loads(load(P/'runs/06/compile-result.json'));assert compiled['passed'] and compiled['classes']==278
cacheCompiled=json.loads(load(P/'runs/07/compile-result.json'));assert cacheCompiled['passed']
proof=json.loads(load(P/'proof-runs/03/result.json'));assert proof['passed']
cp=json.loads(load(P/'runs/06/pins-before.json'))['runtime'];assert len(cp)==101
for row in cp:assert sha(row['path'])==row['sha256Bytes']
for name in ['runs/06','runs/07','proof-runs/03']:
 assert raw(P/name/'pins-before.json')==raw(P/name/'pins-after.json')
families=json.loads(load(P/'exact-hunks.json'))
for family in families:
 desired=load(P/'prepared/existing'/family['target']);assert h(desired)==family['desiredSha256LF']
 reverse=desired
 for delta in reversed(family['hunks']):
  assert reverse.count(delta['after'])==1
  reverse=reverse.replace(delta['after'],delta['before'],1)
  delta['beforeSha256LF']=h(delta['before']);delta['afterSha256LF']=h(delta['after'])
 assert h(reverse)==family['baseSha256LF']
 # Check final Root CURRENT snippets independently; do not replace its full file.
 live=load(REPO/family['target'])
 family['liveBeforeSnippetOccurrences']=[live.count(d['before'])for d in family['hunks']]
 assert all(n==1 for n in family['liveBeforeSnippetOccurrences']),family['target']
save(P/'exact-hunks.json',families)
selection=json.loads(load(P/'action-status-selection.json'));original=load(REPO/selection['originalPath'])
assert h(original)==selection['originalSha256LF']
generated=load(P/'generated'/selection['output'])
for selected in selection['functions']:
 assert original.count(selected['body'])==1 and h(selected['body'])==selected['sha256LF']
 transformed=selected['body'].replace('TokenManager.sessDataCache','primarySessData()')
 assert generated.count(transformed)==1
assert generated.count(selection['helper'])==1
assert h(generated)==selection['outputSha256LF']
registry=json.loads(load(REPO/'desktop/upstream-sources.json'))['sources']
paths=[selection['originalPath'],'app/src/main/java/com/android/purebilibili/feature/video/viewmodel/VideoPlaybackViewModel.kt']
merges=[]
for path in paths:
 rows=[r for r in registry if r['path']==path];assert len(rows)==1
 merges.append(dict(originalIdentity=rows[0],featureUnion=['stable-original-video-full-owner','desktop-original-video-owner-assembly'],preserveExistingModeAndSHA=True,newIdentity=False))
save(P/'registry-merge.json',dict(rows=merges,newOriginalIdentities=0,newResources=0,newDependencies=0,newGradleTask=False))
whitelist=[]
for source in sorted((P/'prepared/manual').rglob('*.kt')):
 if source.name=='DesktopOriginalVideoOwnerAssembly.kt':source=P/'prepared/cache-compatible/com/bilipai/desktop/ui'/source.name
 target='desktop/src/main/kotlin/com/bilipai/desktop/ui/'+source.name
 whitelist.append(dict(source=str(source.relative_to(P)).replace('\\','/'),target=target,sha256Bytes=sha(source),sha256LF=h(load(source)),newManualOnly=True))
assert len(whitelist)==4
save(P/'install-whitelist.json',dict(copyWhitelist=whitelist,exactHunks='exact-hunks.json',registryMerge='registry-merge.json',
 requiredOrder=['frozen byte-cache-consumers b0f87d87632549bf9e5b28ae0f4447f999947253e8cf0bc8d66fde0466a07628','these FOUR new manuals','these exact existing-family hunks','union existing source features','Root whole compile'],
 doNotCopy=['prepared/existing whole files','generated or generated-replay','compile-reference','runs or proof-runs binary/classes','older prepared/manual Assembly static range variant'],
 RootConstructed=False,RootMounted=False,NativePlayback=False))
def methods(data):
 pos=8
 def u2():
  nonlocal pos
  n=struct.unpack_from('>H',data,pos)[0];pos+=2;return n
 def u4():
  nonlocal pos
  n=struct.unpack_from('>I',data,pos)[0];pos+=4;return n
 cp={};count=u2();i=1
 while i<count:
  tag=data[pos];pos+=1
  if tag==1:
   size=u2();cp[i]=data[pos:pos+size].decode('utf8',errors='replace');pos+=size
  elif tag in [3,4,9,10,11,12,17,18]:pos+=4
  elif tag in [5,6]:pos+=8;i+=1
  elif tag in [7,8,16,19,20]:pos+=2
  elif tag==15:pos+=3
  else:raise ValueError(tag)
  i+=1
 pos+=6;n=u2();pos+=2*n
 def member():
  nonlocal pos
  acc=u2();name=u2();desc=u2();ac=u2()
  for _ in range(ac):u2();size=u4();pos+=size
  return acc,cp.get(name),cp.get(desc)
 for _ in range(u2()):member()
 return [member()for _ in range(u2())]
collisions=[]
for row in cp[:3]:
 with zipfile.ZipFile(wide(row['path']))as z:
  for name in z.namelist():
   if name.startswith('com/android/purebilibili/data/repository/') and name.endswith('.class'):
    data=z.read(name)
    if b'isWatchLaterAid' not in data:continue
    for access,method,descriptor in methods(data):
     if access&8 and method=='isWatchLaterAid':collisions.append(dict(owner=name,method=method,descriptor=descriptor))
assert not collisions,collisions
same=[]
with zipfile.ZipFile(wide(P/'runs/05/candidate.jar'))as old,zipfile.ZipFile(wide(P/'runs/06/candidate.jar'))as new:
 for name in ['com/bilipai/desktop/ui/DesktopOriginalVideoOwnerActionView','com/bilipai/desktop/ui/DesktopOriginalVideoEntryReadbacks','com/bilipai/desktop/ui/DesktopOriginalVideoRepositoryBinding','com/bilipai/desktop/data/DesktopRepository','com/android/purebilibili/data/repository/DesktopOriginalVideoActionStatus']:
  file=name+'.class';assert old.read(file)==new.read(file),file
  same.append(dict(className=name,sha256Bytes=hashlib.sha256(new.read(file)).hexdigest(),proof03ByteIdenticalToFinal06=True))
audit=dict(passed=True,originalCommit='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589',
 selectedOriginalMethods=5,selectedOriginalHelper=1,sourceBodyInverseExact=True,
 sourceOutput=selection['output'],sourceOutputSHA256LF=h(generated),
 reverseExistingFamilySHA256Verified=True,currentLiveAllBeforeAnchorsUnique=True,
 watchLaterInternalTopMethodCollisions=collisions,proof03LoadedFamiliesByteIdenticalToFinal06=same,
 compile06Inputs=8,compile06Classes=278,compile07CacheCompatibility=True,proof03Groups=3,proof03Assertions=33,
 sourceOnly=True,wholeOwnerConstructed=False,RootMounted=False,native=False,HTTP=False)
save(P/'source-only-audit.json',audit)
history=dict(failures=[
 dict(run='runs/01',reason='fixture/assembly source initially mistook ResumePlaybackSuggestion flow for full PlaybackSessionState'),
 dict(run='runs/02',reason='incorrect FollowStateChange package import'),
 dict(run='runs/04',reason='misnamed nonexistent Store admission; fixed to existing Repository.withPrimaryPlaybackAdmission'),
 dict(run='runs/05',reason='compiler passed; postcompile overlap audit omitted same-file DesktopSessionEpoch; separate audit correction preserves declaration'),
 dict(run='proof-runs/01',reason='test-only fake MediaPort generic signature and ambiguous PlaybackSource imports'),
 dict(run='proof-runs/02',reason='test expected CancellationException; actual existing Store rejection is BiliApiException')],
 correctedFinal=['runs/06','runs/07','proof-runs/03'],productionRuntimeAccepted=False)
save(P/'history-and-scope.json',history)
save(P/'source-inventory.json',dict(originals=merges,soleProducer='desktop/tools/extract-upstream-video-full-owner.py',
 newOutput=selection['output'],manuals=whitelist,sharedSourcesReferencedOnly=['original CreatorStatus/folder/engagement/FavoriteEnvironment','actual Holder/Section/Core','same native/Store/Repository/cache','sibling byte-cache consumer'],
 newOriginalIdentities=0,existingFamilyOverridesExplicit=True))
rows=[];exclusions=[]
for path in sorted(P.rglob('*')):
 if not path.is_file():continue
 rel=str(path.relative_to(P)).replace('\\','/')
 if rel.startswith('generated-replay/'):
  if path.name not in ['DesktopOriginalVideoActionStatus.kt','VideoPlaybackViewModel.kt']:continue
 if '/source-inputs/' in rel:continue # physical immutable snapshots kept; pins reference all bytes
 if path.suffix in ['.jar','.class','.pyc']:
  exclusions.append(dict(path=rel,sha256Bytes=sha(path),size=wide(path).stat().st_size));continue
 if '__pycache__' in rel or path.name=='frozen-handoff.json':continue
 if path.suffix in ['.kt','.py','.json','.log','.args','.md','.txt']:
  rows.append(dict(path=rel,sha256Bytes=sha(path),size=wide(path).stat().st_size))
save(P/'excluded-compiled-artifacts.json',dict(rows=exclusions,installOrCommit=False,immutableInputSourceSnapshots='runs/*/source-inputs retained on disk; exact source-input hashes and matching args/logs archived, not duplicate whole source clones'))
rows.append(dict(path='excluded-compiled-artifacts.json',sha256Bytes=sha(P/'excluded-compiled-artifacts.json'),size=wide(P/'excluded-compiled-artifacts.json').stat().st_size))
rows=sorted({r['path']:r for r in rows}.values(),key=lambda r:r['path'])
manifest=dict(schema=1,scope='prepared original ordinary-video Assembly/Action/Request/EntryReadback source, NOT actual Root owner acceptance',
 rows=rows,artifacts=len(rows),copyWhitelist=whitelist,exactHunksSHA256Bytes=sha(P/'exact-hunks.json'),
 sourceAuditSHA256Bytes=sha(P/'source-only-audit.json'),actual71ManifestSHA256='416f1ec5fdf577f13c607d52248ca3c97d39420d363bac78f500d119053e6ad1',
 actual71OrderedCPSHA256='6ec3765f3ced6095ac01d2c2908550e9487bfcd586c1008ff4a582920f84f55f',
 prospectiveCacheConsumerManifestSHA256='b0f87d87632549bf9e5b28ae0f4447f999947253e8cf0bc8d66fde0466a07628',
 compilerPassed=True,actionStatusFixturePassed=True,wholeOwnerConstructed=False,RootMounted=False,native=False,HTTP=False)
save(P/'frozen-handoff.json',manifest)
for row in rows:assert sha(P/row['path'])==row['sha256Bytes']
print('FROZEN',len(rows),'sha256',sha(P/'frozen-handoff.json'))
for row in whitelist:print(row['target'],row['sha256LF'])
