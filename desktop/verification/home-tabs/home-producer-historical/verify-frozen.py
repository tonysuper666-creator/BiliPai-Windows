from pathlib import Path
import hashlib,json
HERE=Path(__file__).resolve().parent
def ext(p):
 p=str(Path(p).absolute());return Path(p if p.startswith('\\\\?\\') else '\\\\?\\'+p)
def sha(p):return hashlib.sha256(ext(p).read_bytes()).hexdigest()
contract=json.loads((HERE/'frozen-handoff.json').read_text(encoding='utf-8'))
assert sha(HERE/contract['artifactManifest'])==contract['artifactManifestSha256Bytes']
manifest=json.loads((HERE/contract['artifactManifest']).read_text(encoding='utf-8'))
for row in manifest['files']:assert sha(HERE/row['path'])==row['sha256Bytes'],row['path']
for row in manifest['externalTaskOwnedFiles']:assert sha(Path(row['path']))==row['sha256Bytes'],row['path']
print('PASS frozen',len(manifest['files']),'local artifacts',len(manifest['externalTaskOwnedFiles']),'external owned artifacts')
