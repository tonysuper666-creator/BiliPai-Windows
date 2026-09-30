"""Read-only byte verification; never runs prepared native or Kotlin producers."""
from pathlib import Path
import hashlib,json,sys
sys.stdout.reconfigure(encoding='utf-8')
HERE=Path(__file__).resolve().parent
def ext(p):
    s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def sha(p):return hashlib.sha256(ext(p).read_bytes()).hexdigest()
cohorts=[
 ('diagnostics-native-share-parity/terminal-leases','1ffdfd72f9445ac035f6179d3214c93e138cb4a3707263d018caa3d976d215f9',189),
 ('native-share-current-main-review','d9687e0aa7e4d2f780fd144195498b67289759102c4ed2651daa5cf0852418e1',118),
 ('native-share-community-toolchain-parity','17d368c4e7803f88aea39414ccce115e3ebfa1ae8dad703ecf7939c67fdb3cad',31)]
results=[]
for name,pin,count in cohorts:
    base=HERE.parent/name;manifest=base/'frozen-handoff.json'
    assert sha(manifest)==pin,(name,'manifest identity')
    rows=json.loads(ext(manifest).read_text(encoding='utf-8'))['artifacts'];assert len(rows)==count
    for row in rows:
        p=base/row['path'];assert sha(p)==row['sha256Bytes'],str(p)
        assert ext(p).stat().st_size==row['sizeBytes'],str(p)
    results.append({'cohort':name,'manifestSha256Bytes':pin,'artifactCount':count,'allBytesVerified':True,'preparedOnly':True})
(HERE/'predecessor-byte-verification.json').write_text(json.dumps({'passed':True,'cohorts':results,'actualMainAcceptance':False},indent=2)+'\n',encoding='utf-8',newline='\n')
print('PASS read-only verification of 338 frozen prepared artifacts; no prepared execution')
