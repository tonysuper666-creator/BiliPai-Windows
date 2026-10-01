"""Build a sole source-derived generator and narrow install contract.
All generation recipes read pinned original files and replay explicit line edits.
No compiled classes, legacy family replacement, account or actor is installed.
"""
from pathlib import Path
import difflib,hashlib,json,os,subprocess
H=Path(__file__).resolve().parent;MAIN=H.parents[2];R=MAIN.parent/'BiliPai-v023'
PREFIX=chr(92)*2+'?'+chr(92)
COMMIT='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589';BASE='app/src/main/java/com/android/purebilibili/'
def wide(p):
 s=os.path.abspath(p);return Path(s if s.startswith(PREFIX) else PREFIX+s)
def sha(v):return hashlib.sha256(v if isinstance(v,bytes) else v.encode()).hexdigest()
def read(p):return wide(p).read_text(encoding='utf-8').replace('\r\n','\n')
def write(p,t):
 p=wide(p);p.parent.mkdir(parents=True,exist_ok=True);p.write_text(t,encoding='utf-8',newline='\n')
def save(p,v):write(p,json.dumps(v,ensure_ascii=False,indent=2)+'\n')
def original(path):return subprocess.check_output(['git','-C',str(R),'show',COMMIT+':'+path]).decode().replace('\r\n','\n')
packet=H/'install-packet-13';wide(packet).mkdir(exist_ok=False)
items=[(BASE+'feature/video/viewmodel/VideoPlaybackViewModel.kt',H/'prepared/whole/com/android/purebilibili/feature/video/viewmodel/VideoPlaybackViewModel.kt'),
 (BASE+'data/repository/VideoNoteRepository.kt',H/'prepared/notes/com/android/purebilibili/data/repository/DesktopOriginalVideoNoteProtocol.kt'),
 (BASE+'data/repository/VideoRepository.kt',H/'prepared/metadata/com/android/purebilibili/data/repository/DesktopOriginalVideoOwnerMetadataProtocol.kt'),
 (BASE+'feature/video/playback/resolver/NextPlaybackResolver.kt',H/'prepared/direct/com/android/purebilibili/feature/video/playback/resolver/NextPlaybackResolver.kt')]
for row in json.loads(read(H/'prerequisite-source-identities.json'))['sources']:items.append((row['originalPath'],H/row['output']))
items.append((BASE+'core/store/SettingsManager.kt',H/'prepared/prerequisites/com/android/purebilibili/core/store/DesktopOriginalVideoOwnerSettings.kt'))
items.append((BASE+'feature/plugin/CdnDashSegmentPrefetcher.kt',H/'prepared/prerequisites/com/android/purebilibili/feature/plugin/CdnDashSegmentPrefetcher.kt'))
recipes=[]
for path,p in items:
 raw=original(path);output=read(p)
 assert read(R/path)==raw,path+' changed from fixed stable source'
 a=raw.splitlines(keepends=True);b=output.splitlines(keepends=True);edits=[]
 for tag,i,j,k,l in difflib.SequenceMatcher(a=a,b=b,autojunk=False).get_opcodes():
  before=''.join(a[i:j]);after=''.join(b[k:l])
  edits.append(dict(kind=tag,startLine=i,endLineExclusive=j,beforeSha256LF=sha(before),after=after if tag!='equal' else None))
 rel=str(p.relative_to(H)).replace('\\','/');rel=rel[rel.index('com/'):]
 mode='direct' if raw==output else 'policy-extract'
 rec=dict(originalPath=path,originalSha256LF=sha(raw),gitBlob=subprocess.check_output(['git','-C',str(R),'rev-parse',COMMIT+':'+path],text=True).strip(),output=rel,outputSha256LF=sha(output),mode=mode,edits=edits)
 recipes.append(rec)
 write(packet/'expected-generated'/rel,output)
generator='''"""Complete pinned stable VideoPlaybackViewModel + metadata/Notes source closure.
Sole producer. Production skips exact DIRECT files, provided once by sole Sync.
Each explicit platform edit is pinned to its exact original line span/body hash.
"""
from pathlib import Path
import argparse,hashlib,json,os
COMMIT='''+repr(COMMIT)+'''
RECIPES=json.loads('''+repr(json.dumps(recipes,ensure_ascii=True))+''')
def wide(p):
 s=os.path.abspath(p);prefix=chr(92)*2+'?'+chr(92)
 return Path(s if s.startswith(prefix) else prefix+s) if os.name=='nt' else Path(s)
def sha(t):return hashlib.sha256(t.encode()).hexdigest()
def generate(repo,output,standalone=False):
 outputs=[]
 for recipe in RECIPES:
  raw=wide(Path(repo)/recipe['originalPath']).read_text(encoding='utf-8').replace('\\r\\n','\\n')
  assert sha(raw)==recipe['originalSha256LF'],recipe['originalPath']+' differs from pinned stable source'
  lines=raw.splitlines(keepends=True);parts=[];inverse=[];last=0
  for edit in recipe['edits']:
   i,j=edit['startLine'],edit['endLineExclusive'];assert i==last;last=j
   before=''.join(lines[i:j]);assert sha(before)==edit['beforeSha256LF']
   parts.append(before if edit['kind']=='equal' else edit['after']);inverse.append(before)
  assert last==len(lines) and ''.join(inverse)==raw
  body=''.join(parts);assert sha(body)==recipe['outputSha256LF'],recipe['output']
  emitted=standalone or recipe['mode']!='direct'
  if emitted:
   target=wide(Path(output)/recipe['output']);target.parent.mkdir(parents=True,exist_ok=True)
   target.write_text(body,encoding='utf-8',newline='\\n')
  outputs.append(dict(path=recipe['output'],origin=recipe['originalPath'],sha256LF=sha(body),mode=recipe['mode'],generated=emitted))
 return outputs
if __name__=='__main__':
 parser=argparse.ArgumentParser();parser.add_argument('--repo',required=True);parser.add_argument('--output',required=True);parser.add_argument('--standalone',action='store_true')
 args=parser.parse_args();rows=generate(Path(args.repo),Path(args.output),args.standalone)
 print('Full original Video owner source outputs:',len(rows),'emitted:',sum(r['generated'] for r in rows))
'''
producerPath='desktop/tools/extract-upstream-video-full-owner.py';write(packet/'prepared'/producerPath,generator)
manuals=['DesktopOriginalVideoPlaybackOwnerEnvironment.kt','DesktopOriginalVideoOwnerRepositoryView.kt','DesktopOriginalVideoNoteEnvironment.kt','DesktopOriginalVideoMetadataEnvironment.kt']
whitelist=[dict(source='prepared/'+producerPath,target=producerPath,sha256LF=sha(generator),kind='new sole producer')]
for name in manuals:
 path='desktop/src/main/kotlin/com/bilipai/desktop/ui/'+name
 text=read(H/'prepared/manual/com/bilipai/desktop/ui'/name)
 write(packet/'prepared'/path,text);whitelist.append(dict(source='prepared/'+path,target=path,sha256LF=sha(text),kind='new required same-owner manual port'))
hunks=[]
ctxPath='desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalPlayerSettingsContext.kt'
ctxBase=read(R/ctxPath);ctxDesired=read(H/'prepared/legacy/com/bilipai/desktop/ui/DesktopOriginalPlayerSettingsContext.kt')
diff=''.join(difflib.unified_diff(ctxBase.splitlines(keepends=True),ctxDesired.splitlines(keepends=True),fromfile='a/'+ctxPath,tofile='b/'+ctxPath))
write(packet/'hunks/context-string-set.patch',diff)
hunks.append(dict(path=ctxPath,baseSha256LF=sha(ctxBase),desiredSha256LF=sha(ctxDesired),patch='hunks/context-string-set.patch',scope='getStringSet/putStringSet only; merge child Holder remove/presence hunks in same sole Context, never overwrite'))
request=json.loads(read(H/'request-view-delta.json'))
base=read(R/request['path']);anchor=request['exactAnchor'];assert base.count(anchor)==1
desired=base.replace(anchor,request['insertBefore']+anchor)
hunks.append(dict(path=request['path'],baseSha256LF=sha(base),desiredSha256LF=sha(desired),anchor=anchor,before=request['insertBefore'],scope='readonly invocation view; no latest repository or completed request retained'))
completion=json.loads(read(H/'completion-settings-delta.json'));path='desktop/tools/extract-upstream-video-player-full-controls.py'
base=read(R/path);anchor="'getPlaybackCompletionBehavior','setPlaybackCompletionBehavior'";assert base.count(anchor)==1
replacement="'getPlaybackCompletionBehavior','getPlaybackCompletionBehaviorSync','setPlaybackCompletionBehavior'"
hunks.append(dict(path=path,baseSha256LF=sha(base),desiredSha256LF=sha(base.replace(anchor,replacement)),anchor=anchor,replacement=replacement,scope='reader selected into existing original memory-cache owner; preserve all other producer members'))
metadata=json.loads(read(H/'metadata-original-binding.json'));row=metadata['soleCoreHunk'];base=read(R/row['path']);anchor=row['exactAnchor'];assert base.count(anchor)==1
hunks.append(dict(path=row['path'],baseSha256LF=sha(base),desiredSha256LF=sha(base.replace(anchor,anchor+row['insertAfter'])),anchor=anchor,after=row['insertAfter'],scope='ONLY WBI visibility; same existing load protocol/cache, preserve Portrait2 members'))
save(packet/'exact-hunks.json',hunks)
registry=json.loads(read(R/'desktop/upstream-sources.json'));existing={row['path']:row for row in registry['sources']}
feature='stable-original-video-full-owner';rows=[]
for rec in recipes:
 if any(row['path']==rec['originalPath'] for row in rows):continue
 rows.append(dict(path=rec['originalPath'],sha256=rec['originalSha256LF'],features=[feature],mode=rec['mode'],existing=rec['originalPath'] in existing,preserveExistingMode=True,existingMode=existing.get(rec['originalPath'],{}).get('mode')))
save(packet/'source-registry-merge.json',dict(upstreamCommit=COMMIT,feature=feature,rows=rows,baselineCount=len(existing),newIdentities=sum(not r['existing'] for r in rows),mergeOnly=True))
save(packet/'install-whitelist.json',whitelist)
save(packet/'source-inventory.json',dict(upstreamCommit=COMMIT,outputs=[{k:v for k,v in rec.items() if k!='edits'} for rec in recipes],sourceIdentities=len(rows),productionSelected=sum(rec['mode']!='direct' for rec in recipes),directSync=sum(rec['mode']=='direct' for rec in recipes),bodyPatchReplay='Original exact line spans/hash + complete original class inverse137; no fake platform implementation',referenceExisting=['canonical Core/UI/state and actual66 NativeOwner/seek types','Fullscreen sole buildPlaybackAudioUrlCandidates','sole existing ControlSettings completion cache','sole same global Context and request Invocation','Holder337 prospective SOURCE_READY but not a compiled input to whole13']))
write(packet/'ROOT-INTEGRATION.md','''# Full original Video VM source install (not runtime mount)

Install ONLY the five install-whitelist paths. Generate into a distinct
`build/generated/original-video-full-owner` source directory with the sole producer
`--repo <pinned stable tree> --output <directory>`. Production skips exact DIRECT
files; source-registry merge keeps the existing mode for existing identities and
appends only new rows. Do not install expected-generated, legacy sources or JARs.
Apply exact-hunks in existing families, preserve the installed Core Portrait two
methods and child Holder Context remove/presence hunks. Add the generator task as
compileKotlin dependency and its own kotlin source directory using existing patterns.
The generator has no extra Python package or runtime dependency.

Complete original VM body7696 lines +48 selected top declarations (canonical
buildPlaybackAudioUrlCandidates belongs to installed Fullscreen), all retained
metadata9 methods, full original Notes schemas/String IDs/save/delete protocol,
19 original Settings methods and real required range-cache boundary. Original
class reverse137 check is in whole-vm-initial-adaptations.json. Actual66 compile13
387classes includes three explicit existing-family deltas only. Metadata compile01
42classes includes ONLY explicit Core visibility plus new metadata/environment and
Network three-policy prerequisite. Note proof46 is independent immutable actual61
evidence, not re-labelled current. No full owner constructed/mounted and no second
native/HTTP/account/queue/settings/subtitle/cache authority is installed.

Root runtime factory required constructor is copied
DesktopOriginalVideoPlaybackOwnerEnvironment(scope,settings,invocations,repository,
notes,actions,useCase,interactionUseCase,account,mini,playlist,plugins,download,
network,cache,cdnRangeCache,analytics,crash,comments,danmaku,background,isCurrent,
withEntryAdmission). See the exact typed declaration; all23 are required.
repository must be DesktopOriginalVideoOwnerRepositoryView on the SAME invocation
factory, each actual capture supplies an extended original repository over its
same binding.environment/protocol/WBI state and owned API/Call.Factory. The base
raw repository cannot simply be cast into this extended interface. No mutable
latest-binding field is permitted. Notes similarly use same captured primary API
and admitted CSRF/presence. Existing Assets retain cue/files; owned-call import
forwarding remains the next factory delta, not a second downloader.

Plugins use final frozen-plugin-contract-13, expected actual accepted object and
exact native seek submission, suspend all serialized mutations/readers; preserve
old Runtime generation and actual mute ledger. Root actual67 native completed
seek is external evidence; history/Runtime write publications remain Root work.
No invented history consent: original optional uploadViewedIfEnabled only.
Range cache is a required real Root ingress; do not fill it with a no-op writer.
Root sibling is implementing the actual captured bound cache and native carrier.

After source install, build once with Holder337. This packet does not authorize
retiring legacy Controller, switching Dashboard/Favorite/Story/Listen clients or
claiming full-page EXE acceptance. Construct the unique owner only after all true
required ports, same native handoff and legacy job drains have been assembled.
The next task supplies this actual constructor/inventory, with missing effects
plainly listed. Native/window/account/API mutation tests were not run here.
''')
print('Prepared install packet',packet,'outputs',len(recipes),'selected',sum(r['mode']!='direct' for r in recipes),'DIRECT',sum(r['mode']=='direct' for r in recipes),'identities',len(rows),'new',sum(not r['existing'] for r in rows))
