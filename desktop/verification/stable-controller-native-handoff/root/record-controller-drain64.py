from pathlib import Path
import hashlib,json,difflib
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023'
OUT=HERE/'controller-drain64';assert not OUT.exists();OUT.mkdir()
def wide(p):
 s=str(Path(p).absolute());prefix=chr(92)*2+'?'+chr(92);return Path(s if s.startswith(prefix) else prefix+s)
def read(p):return wide(p).read_bytes()
def sha(b):return hashlib.sha256(b).hexdigest()
rows=[]
for item in json.loads(read(HERE/'controller-drain63/source-delta.json'))['sources']:
 rel=item['sourcePath'];before=read(HERE/'controller-drain63/before'/rel);after=read(REPO/rel)
 for mode,b in [('before',before),('after',after)]:
  p=wide(OUT/mode/rel);p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(b)
 wide(OUT/(Path(rel).name+'.diff')).write_bytes(''.join(difflib.unified_diff(before.decode().splitlines(True),after.decode().splitlines(True),fromfile='actual61/'+rel,tofile='actual64/'+rel)).encode())
 rows.append(dict(sourcePath=rel,baselineBytesSha256=sha(before),candidateBytesSha256=sha(after),candidateLFsha256=sha(after.replace(b'\r\n',b'\n'))))
wide(OUT/'source-delta.json').write_bytes((json.dumps(dict(sources=rows,actualBaseline=61,reviewedDrafts=[62,63],newDependencies=0,newPlayers=0),indent=2)+'\n').encode())
fixture=read(HERE/'controller-drain63/ControllerNativeDrainFixture.kt').replace(b'Drain 62',b'Drain 64')
wide(OUT/'ControllerNativeDrainFixture.kt').write_bytes(fixture)
print('final source receipt and fixture copy recorded')
