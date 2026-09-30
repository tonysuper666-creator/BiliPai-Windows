from pathlib import Path
import hashlib,json
HERE=Path(__file__).resolve().parent
def safe(p):
 value=str(Path(p).absolute());return Path(value if value.startswith('\\\\?\\') else '\\\\?\\'+value)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
manifest=json.loads((HERE/'verified-artifacts.json').read_text(encoding='utf-8'))
for r in manifest['files']:assert sha(HERE/r['path'])==r['sha256Bytes'],r['path']
deps=json.loads((HERE/'dependency-identities.json').read_text(encoding='utf-8'))
for r in deps:assert sha(Path(r['path']))==r['sha256Bytes'],r['path']
assert sha(HERE.parent/'blocked-up-main-product-snapshot/manifest.json')==manifest['productManifestSha256Bytes']
print('VERIFIED',len(manifest['files']),len(deps),sha(HERE/'verified-artifacts.json'))
