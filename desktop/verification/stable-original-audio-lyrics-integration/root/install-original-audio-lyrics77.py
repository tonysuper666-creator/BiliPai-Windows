from pathlib import Path
import hashlib,json,subprocess,sys
sys.stdout.reconfigure(encoding='utf-8');sys.dont_write_bytecode=True
H=Path(__file__).resolve().parent;MAIN=H.parents[2];REPO=MAIN.parent/'BiliPai-v023'
LANE=MAIN/'desktop/.local/stable-original-audio-lyrics-parity';OUT=H/'original-audio-lyrics-install77'
assert not OUT.exists()
def wide(p):
    s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix) else prefix+s)
def read(p):return wide(p).read_bytes()
def sha(b):return hashlib.sha256(b).hexdigest()
def write(p,b):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes(b)
head=subprocess.check_output(['git','rev-parse','HEAD'],cwd=REPO,text=True).strip();assert head.startswith('84b47296')
assert not subprocess.check_output(['git','status','--porcelain'],cwd=REPO,text=True).strip()
raw=read(LANE/'frozen-handoff.json');packet=json.loads(raw)
for row in packet['artifacts']:
    b=read(LANE/row['path']);assert sha(b)==row['sha256Bytes'] and len(b)==row['bytes']
contract=json.loads(read(LANE/'install-contract.json'));assert len(contract['copyWhitelist'])==1 and contract['exactHunks']==1
hunks=json.loads(read(LANE/'producer-hunk.json'));target=hunks['target'];previous=read(REPO/target);base=previous.replace(b'\r\n',b'\n')
assert sha(base)==hunks['baseSHA256LF'];after=base;positions=[]
for row in hunks['edits']:
    old=row['before'].encode();new=row['after'].encode();assert after.count(old)==1
    at=after.index(old);positions.append((at,old,new));after=after[:at]+new+after[at+len(old):]
assert sha(after)==hunks['desiredSHA256LF'];inverse=after
for at,old,new in reversed(positions):
    assert inverse[at:at+len(new)]==new;inverse=inverse[:at]+old+inverse[at+len(new):]
assert inverse==base
manual=contract['copyWhitelist'][0];manualBytes=read(LANE/manual['source']);assert sha(manualBytes)==manual['sha256Bytes']
assert not wide(REPO/manual['destination']).exists()
registry=read(REPO/'desktop/upstream-sources.json');r=json.loads(registry)
assert len(r['sources'])==1170 and len(r['resources'])==213
original=[v for v in r['sources'] if v['path']==contract['originalSelectedSource']]
assert len(original)==1 and original[0]['sha256']==contract['originalSha256LF']
changes=[(target,previous,after),(manual['destination'],None,manualBytes)]
# All packet, original pin, exact hunk and inverse checks precede production writes.
for path,before,desired in changes:
    if before is not None:write(OUT/'before'/path,before)
    write(OUT/'after'/path,desired);write(REPO/path,desired)
assert read(REPO/'desktop/upstream-sources.json')==registry
write(OUT/'installed.json',(json.dumps(dict(phase=77,baseCommit=head,preparedPacketSHA256=sha(raw),preparedRaw=len(packet['artifacts']),
    newManual=1,existingProducerFamilies=1,exactProducerHunks=1,indexedInverse=True,
    sourceIdentityCount=1170,resourceCount=213,newOriginalIdentities=0,registryUnchanged=True,newDependencies=0,
    newPlayerCacheOrClient=False,portMethods=6,originalDomainMethodsSelected=5,originalPrivateLoadSelected=True,
    canonicalMusicUiStateAndRepositoryOwnersPreserved=True,underlyingBlockingHttpCancellationClaimed=False,
    targets=[dict(path=p,beforeSHA256Bytes=sha(b) if b is not None else None,afterSHA256Bytes=sha(a)) for p,b,a in changes],
    completeRootMounted=False,nativeOrWindowAccepted=False,desktopExeReplaced=False),indent=2)+'\n').encode())
print(json.dumps(dict(preparedRaw=len(packet['artifacts']),newManual=1,exactProducerHunks=1,registryUnchanged=True)))
