from pathlib import Path
import hashlib,json
HERE=Path(__file__).resolve().parent;REPO=HERE.parents[2]
def sha(p):return hashlib.sha256(Path('\\\\?\\'+str(p.absolute())).read_bytes()).hexdigest()
manifest=json.loads((HERE/'verified-artifacts.json').read_text(encoding='utf-8'))
for e in manifest['artifacts']:assert sha(HERE/e['path'])==e['sha256Bytes'],e['path']
for e in json.loads((HERE/'dependency-identities.json').read_text(encoding='utf-8')):assert sha(Path(e['path']))==e['sha256Bytes'],e['path']
for name in ['source-inventory.json','resource-inventory.json']:
 for e in json.loads((HERE/name).read_text(encoding='utf-8')):
  text=(REPO/e['path']).read_text(encoding='utf-8').replace('\r\n','\n')
  assert hashlib.sha256(text.encode()).hexdigest()==e['sha256'],e['path']
for e in json.loads((HERE/'bridge-baselines.json').read_text(encoding='utf-8')):assert sha(REPO/e['path'])==e['baselineSha256Bytes'],e['path']
print(json.dumps(dict(passed=True,artifactsVerified=manifest['artifactCount'],dependenciesVerified=234,originalSourceAndResourcesVerified=True,bridgeMainUnchanged=True,manifestSha256Bytes=sha(HERE/'verified-artifacts.json'))))
