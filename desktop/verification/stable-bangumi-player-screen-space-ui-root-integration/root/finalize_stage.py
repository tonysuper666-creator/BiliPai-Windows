from pathlib import Path
import difflib,hashlib,json,subprocess,sys
root=Path(__file__).resolve().parent;main=root.parents[2];candidate=main.parent/'BiliPai-v023'
relative='desktop/verification/stable-bangumi-player-screen-space-ui-root-integration';target=candidate/relative
def wide(p):
 s=str(p.absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def read(p):return wide(p).read_bytes()
def sha(b):return hashlib.sha256(b).hexdigest()
def write(p,b):wide(p.parent).mkdir(parents=True,exist_ok=True);wide(p).write_bytes(b)
def git(*a):return subprocess.check_output(['git','-c','core.longpaths=true',*a],cwd=candidate)
def encoded(d):return (json.dumps(d,indent=2)+'\n').encode()
installation=json.loads(read(root/'installation.json'));patch=[]
changes=[]
for r in installation['sourceTargets']:
 b=read(candidate/r['path']);old=read(root/'before'/r['path'])
 if sha(b)!=r['afterSha256Bytes']:
  assert r['path'] in ['desktop/upstream-sources.json','desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalBangumiRawDanmakuView.kt']
  changes.append(dict(path=r['path'],normalBuildSha256Bytes=r['afterSha256Bytes'],finalSourceSha256Bytes=sha(b)))
  r['afterSha256Bytes']=sha(b)
 patch.extend(difflib.unified_diff(old.decode().replace('\r\n','\n').splitlines(keepends=True),b.decode().splitlines(keepends=True),fromfile='a/'+r['path'],tofile='b/'+r['path']))
assert len(changes)==2
write(root/'installation.json',encoded(installation));write(root/'source-diff.patch',''.join(patch).encode())
note=dict(priorStageRejected=True,reason='Two whitespace-only original API blank lines; full registry sorting caused unnecessary diff.',
 changes=changes,registryIdentitiesFeaturesModesUnchanged=True,rawDanmakuOnlyTwoBlankLinesTrimmed=True,
 normalGeneratedOutputsUnchanged=True,normalCompiledKotlinBehaviorUnchanged=True)
write(root/'stage-cleanup.json',encoded(note))
for name in ['generated-verification.json','integration-summary.json']:
 d=json.loads(read(root/name));d['sourceTargets']=installation['sourceTargets'];d['postCompileNonBehavioralCleanup']=note;write(root/name,encoded(d))
index=json.loads(read(target/'artifact-index.json'));files=index['files']
for dest,source in [('root/installation.json','installation.json'),('root/source-diff.patch','source-diff.patch'),
 ('root/generated-verification.json','generated-verification.json'),('integration-summary.json','integration-summary.json'),
 ('root/stage-cleanup.json','stage-cleanup.json'),('root/finalize_stage.py','finalize_stage.py')]:
 b=read(root/source);write(target/dest,b)
 r=next((r for r in files if r['path']==dest),None)
 if r is None:r={'path':dest};files.append(r)
 r.update(bytes=len(b),sha256Bytes=sha(b))
write(target/'artifact-index.json',encoded(index))
expected={relative+'/'+r['path']:r['sha256Bytes'] for r in files}
for p,h in list(expected.items()):assert sha(read(candidate/p))==h,p
expected[relative+'/artifact-index.json']=sha(read(target/'artifact-index.json'))
source_paths=['.gitattributes']+[r['path'] for r in installation['sourceTargets']]
for p in source_paths:expected[p]=sha(read(candidate/p).replace(b'\r\n',b'\n'))
paths=list(expected)
for start in range(0,len(paths),20):git('add','-f','--',*paths[start:start+20])
actual=[p.decode() for p in git('diff','--cached','--name-only','-z').split(b'\0') if p]
assert set(actual)==set(paths)
for p,h in expected.items():assert sha(git('show',':'+p))==h,p
git('diff','--cached','--check','--',*source_paths)
result=dict(stagedExactBlobs=len(paths),productTargets=len(installation['sourceTargets']),rawProofFiles=len(files),
 everyStagedBlobVerified=True,sourceWhitespacePassed=True,priorStageFailurePreserved=True)
write(root/'stage-verification.json',encoded(result));print(json.dumps(result))
