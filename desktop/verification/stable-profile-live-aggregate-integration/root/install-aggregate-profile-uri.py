from pathlib import Path
import hashlib,json
HERE=Path(__file__).resolve().parent;MAIN=HERE.parents[2];REPO=MAIN.parent/'BiliPai-v023';OUT=HERE/'aggregate-profile-uri-install'
assert not OUT.exists();OUT.mkdir()
sha=lambda b:hashlib.sha256(b).hexdigest()
def wide(p):
 s=str(Path(p).absolute());return Path(s if s.startswith('\\\\?\\')else'\\\\?\\'+s)
lanes={
 'aggregate':(MAIN/'desktop/.local/stable-home-four-page-aggregate-parity','7398c0f9ae3e6b3b0f519616247bd5c4ccff662786ab90cd792d3d324fd17a48'),
 'profileUri':(REPO/'desktop/.local/stable-profile-file-uri-native-delta','dff4a29b9bd149c0c49574d023a98de1bbad98534af94044fe01b977d4ca5eb8'),
}
for name,(lane,pin)in lanes.items():
 raw=wide(lane/'frozen-handoff.json').read_bytes();assert sha(raw)==pin
 d=json.loads(raw)
 for r in d.get('artifacts',d.get('rawArtifacts',[])):assert sha(wide(lane/r['path']).read_bytes())==r['sha256Bytes'],(name,r['path'])
lane=lanes['aggregate'][0];installed=[]
for r in json.loads((lane/'install-whitelist.json').read_text(encoding='utf-8'))['files']:
 b=wide(lane/r['source']).read_bytes();assert sha(b.replace(b'\r\n',b'\n'))==r['sha256LF']
 p=REPO/r['destination'];assert not p.exists();p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(b);installed.append(r)
hunk=json.loads((lanes['profileUri'][0]/'local-hunks.json').read_text(encoding='utf-8'));p=REPO/hunk['target'];raw=p.read_bytes();assert sha(raw)==hunk['baseProducerSha256Bytes'];text=raw.decode('utf-8')
(OUT/'extract-upstream-profile-main.py.before').write_bytes(raw)
for r in hunk['hunks']:
 assert text.count(r['before'])==r['expectedCount'];text=text.replace(r['before'],r['after'])
assert sha(text.encode())==hunk['candidateProducerSha256Bytes'];p.write_text(text,encoding='utf-8',newline='\n')
report=dict(payloads=installed,profileUriHunks=hunk,preparedManifests={k:v[1]for k,v in lanes.items()},rootFactoryMounted=False,profileMounted=False)
(OUT/'installed.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8');print(json.dumps(dict(payloads=len(installed),profileUriHunks=len(hunk['hunks']),rootMounted=False)))
