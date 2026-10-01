from pathlib import Path
import hashlib, json, sys
HERE=Path(__file__).resolve().parent; MAIN=HERE.parents[2]; REPO=MAIN.parent/'BiliPai-v023'
OUT=HERE/'controller-drain62'
paths=['desktop/src/main/kotlin/com/bilipai/desktop/DesktopPlaybackController.kt',
       'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalVideoNativeOwner.kt',
       'desktop/src/main/kotlin/com/bilipai/desktop/player/MpvPlayer.kt']
def wide(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix) else prefix+s)
def read(p):return wide(p).read_bytes()
def sha(b):return hashlib.sha256(b).hexdigest()
mode=sys.argv[1];assert mode in ('before','after')
if mode=='before':
 assert not OUT.exists(); OUT.mkdir()
 baseline=json.loads(read(MAIN/'desktop/.local/stable-product-snapshot-61/manifest.json'))
 pins={r['path']:r['sha256Bytes'] for r in baseline['inputs']}
for rel in paths:
 b=read(REPO/rel)
 if mode=='before':assert sha(b)==pins[rel],rel
 p=wide(OUT/mode/rel);assert not p.exists();p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(b)
if mode=='after':
 import difflib
 rows=[]
 for rel in paths:
  before=read(OUT/'before'/rel);after=read(OUT/'after'/rel)
  name=Path(rel).name+'.diff'
  diff=''.join(difflib.unified_diff(before.decode().splitlines(True),after.decode().splitlines(True),fromfile='before/'+rel,tofile='after/'+rel))
  wide(OUT/name).write_bytes(diff.encode())
  rows.append(dict(sourcePath=rel,baselineBytesSha256=sha(before),candidateBytesSha256=sha(after),candidateLFsha256=sha(after.replace(b'\r\n',b'\n'))))
 wide(OUT/'source-delta.json').write_bytes((json.dumps(dict(sources=rows,newDependencies=0,newPlayers=0),indent=2)+'\n').encode())
print(mode+' source receipt recorded')
