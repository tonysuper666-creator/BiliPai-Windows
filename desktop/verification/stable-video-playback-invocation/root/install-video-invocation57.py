from pathlib import Path
import hashlib,json
HERE=Path(__file__).resolve().parent
MAIN=HERE.parents[2]
REPO=MAIN.parent/'BiliPai-v023'
LANE=MAIN/'desktop/.local/stable-video-invocation-parity'
OUT=HERE/'video-invocation-install57'
assert not OUT.exists()
def wide(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92)
 return Path(s if s.startswith(prefix) else prefix+s)
def read(p):return wide(p).read_bytes()
def sha(b):return hashlib.sha256(b).hexdigest()
raw=read(LANE/'frozen-handoff.json')
assert sha(raw)=='6a3b07d40a24f7cddf3d5ced277cf6b1fa4c410877bb358294cada770c59fddf'
rows=json.loads(raw)['files'];assert len(rows)==17
for r in rows:
 b=read(LANE/r['path']);assert sha(b)==r['sha256Bytes'] and len(b)==r['bytes'],r['path']
recipe=json.loads(read(LANE/'install-whitelist.json'))
assert len(recipe['files'])==1 and not recipe['sharedPatches'] and not recipe['registryDelta'] and not recipe['gradleDelta']
registry=json.loads(read(REPO/'desktop/upstream-sources.json'))
assert len(registry['sources'])==1057 and registry['upstreamCommit']=='3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
r=recipe['files'][0];assert r['target']=='desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalVideoPlaybackInvocation.kt'
b=read(LANE/r['source']);assert sha(b)==r['sha256Bytes']=='d1bb0077e92bbd4a31acae3142146a0c82e6f3f8f1168b737c7e28f62623cbee'
target=REPO/r['target'];assert not wide(target).exists()
OUT.mkdir();a=wide(OUT/'after'/r['target']);a.parent.mkdir(parents=True,exist_ok=True);a.write_bytes(b)
wide(target).write_bytes(b)
report=dict(applied=True,targets=[dict(path=r['target'],installedSha256Bytes=sha(b))],sourceIdentityCount=1057,newSourceIdentities=0,newDependencies=0,networkInvocationCapturesActualLiveJob=True,networkChildrenShareImmutableReceipt=True,acceptedRecoveryRequiresIndependentPublicationView=True,fullOrdinaryVideoRootMountAccepted=False,currentNativeAuthorityRetired=False,wholeCompilationPending=True)
(OUT/'installed.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf8',newline='\n')
print(json.dumps(report))
