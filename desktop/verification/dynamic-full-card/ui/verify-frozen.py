"""Read-only byte checks for all frozen raw attempt manifests."""
from pathlib import Path
import hashlib,json
HERE=Path(__file__).resolve().parent
def ext(path):
    value=str(Path(path).absolute())
    return Path(value if value.startswith('\\\\?\\') else '\\\\?\\'+value)
def sha(path):return hashlib.sha256(ext(path).read_bytes()).hexdigest()
checks=[]
for path in sorted((HERE/'runs').glob('*/?*frozen-manifest.json')):
    value=json.loads(ext(path).read_text(encoding='utf-8'))
    for row in value['files']:
        assert sha(HERE/row['path'])==row['sha256Bytes'],row['path']
        assert ext(HERE/row['path']).stat().st_size==row['bytes'],row['path']
    checks.append({'manifest':path.relative_to(HERE).as_posix(),'sha256Bytes':sha(path),'artifacts':len(value['files'])})
assert checks
print(json.dumps({'passed':True,'checks':checks},ensure_ascii=False))
