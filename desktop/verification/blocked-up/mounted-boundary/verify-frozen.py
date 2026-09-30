from pathlib import Path
import hashlib,json
HERE=Path(__file__).resolve().parent;OLD=HERE.parent/'discovery-storage-error-parity'
def safe(p):
 value=str(Path(p).absolute());return Path(value if value.startswith('\\\\?\\') else '\\\\?\\'+value)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
for root in [OLD,HERE]:
 manifest=json.loads((root/'verified-artifacts.json').read_text(encoding='utf-8'))
 for r in manifest['files']:assert sha(root/r['path'])==r['sha256Bytes'],r['path']
 print('VERIFIED',len(manifest['files']),sha(root/'verified-artifacts.json'))
deps=json.loads((HERE/'dependency-identities.json').read_text(encoding='utf-8'))
for r in deps:assert sha(Path(r['path']))==r['sha256Bytes'],r['path']
print('VERIFIED',len(deps),'pinned dependencies')
