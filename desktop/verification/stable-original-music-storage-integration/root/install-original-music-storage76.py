from pathlib import Path
import hashlib,json,subprocess,sys
sys.stdout.reconfigure(encoding='utf-8');sys.dont_write_bytecode=True
H=Path(__file__).resolve().parent;MAIN=H.parents[2];REPO=MAIN.parent/'BiliPai-v023'
OUT=H/'original-music-storage-install76';assert not OUT.exists()
def wide(p):
    s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix) else prefix+s)
def read(p):return wide(p).read_bytes()
def sha(b):return hashlib.sha256(b).hexdigest()
def write(p,b):wide(p).parent.mkdir(parents=True,exist_ok=True);wide(p).write_bytes(b)
def dump(p,v):write(p,(json.dumps(v,indent=2)+'\n').encode())
head=subprocess.check_output(['git','rev-parse','HEAD'],cwd=REPO,text=True).strip()
assert head=='87462d91b94d21a60b82c771fb75af339190841e'
assert not subprocess.check_output(['git','status','--porcelain'],cwd=REPO,text=True).strip()
SETTINGS=MAIN/'desktop/.local/stable-original-settings-io-parity'
MUSIC=MAIN/'desktop/.local/stable-original-music-storage-parity'
packets=[]
for lane in [SETTINGS,MUSIC]:
    raw=read(lane/'frozen-handoff.json');packet=json.loads(raw)
    for row in packet['artifacts']:
        data=read(lane/row['path']);assert sha(data)==row['sha256Bytes'] and len(data)==row['bytes']
    packets.append(dict(path=str(lane/'frozen-handoff.json'),sha256Bytes=sha(raw),raw=len(packet['artifacts'])))
settings=json.loads(read(SETTINGS/'exact-hunks.json'))
assert settings['baseCommit']==head and settings['exactHunks']==4 and len(settings['families'])==2
families=settings['families']+[json.loads(read(MUSIC/'producer-hunk.json'))]
assert len({r['target'] for r in families})==3
changes=[]
for row in families:
    path=row['target'];previous=read(REPO/path);base=previous.replace(b'\r\n',b'\n');after=base;positions=[]
    assert sha(base)==row['baseSHA256LF']
    for hunk in row['edits']:
        before=hunk['before'].encode();desired=hunk['after'].encode();assert after.count(before)==1
        at=after.index(before);positions.append((at,before,desired));after=after[:at]+desired+after[at+len(before):]
    assert sha(after)==row['desiredSHA256LF'];inverse=after
    for at,before,desired in reversed(positions):
        assert inverse[at:at+len(desired)]==desired;inverse=inverse[:at]+before+inverse[at+len(desired):]
    assert inverse==base
    changes.append((path,previous,after))
manual='desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalMusicStorageBinding.kt'
assert not wide(REPO/manual).exists()
changes.append((manual,None,read(MUSIC/'manual/com/bilipai/desktop/ui/DesktopOriginalMusicStorageBinding.kt')))
registry=read(REPO/'desktop/upstream-sources.json');data=json.loads(registry)
assert len(data['sources'])==1170 and len(data['resources'])==213
# Exact inverse checks and every frozen raw pin pass before the first production write.
for path,previous,after in changes:
    if previous is not None:write(OUT/'before'/path,previous)
    write(OUT/'after'/path,after);write(REPO/path,after)
assert read(REPO/'desktop/upstream-sources.json')==registry
dump(OUT/'installed.json',dict(phase=76,baseCommit=head,preparedPackets=packets,newManual=1,
    existingFamilies=3,exactHunks=5,indexedInverse=True,sourceIdentityCount=1170,resourceCount=213,
    newSourceIdentities=0,registryUnchanged=True,newDependencies=0,newStoreOrActor=False,
    targets=[dict(path=p,beforeSha256Bytes=sha(b) if b is not None else None,afterSha256Bytes=sha(a)) for p,b,a in changes],
    fullOriginalHistoryAndPlaylistBodies=True,completeRootMounted=False,desktopExeReplaced=False))
print(json.dumps(dict(newManual=1,existingFamilies=3,exactHunks=5,registryUnchanged=True)))
