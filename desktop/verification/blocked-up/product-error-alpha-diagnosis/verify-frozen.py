from pathlib import Path
import hashlib,json
HERE=Path(__file__).resolve().parent
def safe(p):
 value=str(Path(p).absolute());return Path(value if value.startswith('\\\\?\\') else '\\\\?\\'+value)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
manifest=json.loads((HERE/'verified-artifacts.json').read_text(encoding='utf-8'))
for row in manifest['files']:assert sha(HERE/row['path'])==row['sha256Bytes'],row['path']
for row in json.loads((HERE/'dependency-identities.json').read_text(encoding='utf-8')):assert sha(Path(row['path']))==row['sha256Bytes'],row['path']
assert sha(Path(manifest['productManifestPath']))==manifest['productManifestSha256Bytes']
old=HERE.parent/'discovery-storage-product-ui-proof'
assert sha(old/'verified-artifacts.json')==manifest['original43ManifestSha256Bytes']
for row in json.loads((old/'verified-artifacts.json').read_text(encoding='utf-8'))['files']:assert sha(old/row['path'])==row['sha256Bytes'],row['path']
print('VERIFIED',len(manifest['files']),234,'original43 unchanged',sha(HERE/'verified-artifacts.json'))
