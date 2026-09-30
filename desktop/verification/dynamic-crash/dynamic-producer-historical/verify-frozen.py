from pathlib import Path
import hashlib,json,sys
sys.dont_write_bytecode=True
HERE=Path(__file__).resolve().parent;REPO=HERE.parents[2]
def safe(p):
 value=str(Path(p).absolute());return Path(value if value.startswith('\\\\?\\') else '\\\\?\\'+value)
def sha(p):return hashlib.sha256(safe(p).read_bytes()).hexdigest()
manifest=json.loads(safe(HERE/'frozen-handoff.json').read_text(encoding='utf-8'))
for row in manifest['artifacts']:
 path=HERE/row['path'];assert safe(path).stat().st_size==row['sizeBytes'],row['path']
 assert sha(path)==row['sha256Bytes'],row['path']
for row in manifest['dependencies']:assert sha(row['path'])==row['sha256Bytes'],row['path']
for row in manifest['originalSourceIdentities']:
 data=safe(REPO/row['path']).read_text(encoding='utf-8').replace('\r\n','\n')
 assert hashlib.sha256(data.encode()).hexdigest()==row['sha256'],row['path']
if '--current-targets' in sys.argv:
 for row in manifest['targets']:assert sha(REPO/row['path'])==row['baseSha256Bytes'],row['path']
print('PASS',len(manifest['artifacts']),'frozen bytes,',len(manifest['dependencies']),'external immutable dependencies,',len(manifest['originalSourceIdentities']),'original LF identities')
