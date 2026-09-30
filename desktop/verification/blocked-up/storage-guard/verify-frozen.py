from pathlib import Path
import hashlib,json
HERE=Path(__file__).resolve().parent
def safe(p):
 value=str(Path(p).absolute());return Path(value if value.startswith('\\\\?\\') else '\\\\?\\'+value)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
manifest=json.loads((HERE/'verified-artifacts.json').read_text())
for r in manifest['files']:assert sha(HERE/r['path'])==r['sha256Bytes'],r['path']
deps=json.loads((HERE/'dependency-identities.json').read_text())
for r in deps:assert sha(Path(r['path']))==r['sha256Bytes'],r['path']
print('VERIFIED',len(manifest['files']),'artifacts;',len(deps),'pinned dependencies;',sha(HERE/'verified-artifacts.json'))
