from pathlib import Path
import hashlib,json
HERE=Path(__file__).resolve().parent;REPO=HERE.parents[2]
manifest=json.loads((HERE/'verified-artifacts.json').read_text(encoding='utf-8'))
for entry in manifest['artifacts']:
 path=Path('\\\\?\\'+str((HERE/entry['path']).absolute()))
 assert hashlib.sha256(path.read_bytes()).hexdigest()==entry['sha256Bytes'],entry['path']
for entry in json.loads((HERE/'dependency-identities.json').read_text(encoding='utf-8')):
 path=Path('\\\\?\\'+str(Path(entry['path']).absolute()))
 assert hashlib.sha256(path.read_bytes()).hexdigest()==entry['sha256Bytes'],entry['path']
for name in ['source-inventory.json','resource-inventory.json']:
 for entry in json.loads((HERE/name).read_text(encoding='utf-8')):
  text=(REPO/entry['path']).read_text(encoding='utf-8').replace('\r\n','\n')
  assert hashlib.sha256(text.encode()).hexdigest()==entry['sha256'],entry['path']
print(json.dumps(dict(verified=len(manifest['artifacts']),dependenciesVerified=True,originalSourcesAndResourcesVerified=True,passed=True)))
