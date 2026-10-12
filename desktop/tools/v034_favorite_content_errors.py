"""Complete fixed upstream FavoriteRepository; the existing producer remains sole owner."""
from pathlib import Path
import hashlib, json, os

ROOT = Path(__file__).resolve().parents[1] / 'upstream-slices/v034-favorite-content-errors'
COMMIT = 'b2091b1de636e499df80af7e700796b4f52b7f92'
MANIFEST_SHA256 = 'e379f83cc71d40d7d3f8619e0ed7fdcd2d5495007eb0d2325da337375b2614f4'
PIN = {'path': 'core-data/src/main/java/com/android/purebilibili/data/repository/FavoriteRepository.kt', 'upstreamCommit': 'b2091b1de636e499df80af7e700796b4f52b7f92', 'gitBlob': '798b741f1111bc8a2b401899572bc32513c91542', 'bytes': 14069, 'sha256Bytes': 'e08693a03a8b1579a719d0f2b168771710fd5596351c358c52e1701621c3e147', 'sha256LfUtf8': 'e08693a03a8b1579a719d0f2b168771710fd5596351c358c52e1701621c3e147', 'sourceUrl': 'https://raw.githubusercontent.com/jay3-yy/BiliPai/b2091b1de636e499df80af7e700796b4f52b7f92/core-data/src/main/java/com/android/purebilibili/data/repository/FavoriteRepository.kt'}
REL = 'data/repository/FavoriteRepository'

def wide(path):
    value = str(path.absolute())
    prefix = chr(92) * 2 + '?' + chr(92)
    return Path(value if value.startswith(prefix) else prefix + value) if os.name == 'nt' else path

def digest(raw):
    return hashlib.sha256(raw).hexdigest()

def strict_json(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('duplicate favorite-content source manifest key')
        result[key] = value
    return result

def selected_source(rel):
    if rel != REL:
        raise ValueError('unsupported favorite-content source path')
    root = wide(ROOT)
    manifest_raw = (root / 'manifest.json').read_bytes()
    if digest(manifest_raw) != MANIFEST_SHA256:
        raise ValueError('favorite-content source manifest pin mismatch')
    manifest = json.loads(manifest_raw, object_pairs_hook=strict_json)
    if manifest.get('schemaVersion') != 1 or manifest.get('upstreamCommit') != COMMIT or manifest.get('files') != [PIN]:
        raise ValueError('favorite-content source manifest rows mismatch')
    files = set()
    for here, dirs, names in os.walk(root):
        for name in dirs + names:
            entry = Path(here, name)
            attrs = entry.lstat()
            if entry.is_symlink() or getattr(attrs, 'st_file_attributes', 0) & 0x400:
                raise ValueError('favorite-content source cannot contain redirected entries')
        files.update(Path(here, name).relative_to(root).as_posix() for name in names)
    if files != {PIN['path'], 'manifest.json'}:
        raise ValueError('favorite-content source file-set mismatch')
    raw = (root / PIN['path']).read_bytes()
    if len(raw) != PIN['bytes'] or digest(raw) != PIN['sha256Bytes']:
        raise ValueError('favorite-content source raw pin mismatch')
    blob = hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest()
    if blob != PIN['gitBlob']:
        raise ValueError('favorite-content source Git blob mismatch')
    body = raw.decode('utf-8').replace('\r\n', '\n').replace('\r', '\n')
    if digest(body.encode()) != PIN['sha256LfUtf8']:
        raise ValueError('favorite-content source LF pin mismatch')
    return body, dict(path='desktop/upstream-slices/v034-favorite-content-errors/' + PIN['path'],
        upstreamCommit=COMMIT, sha256LfUtf8=PIN['sha256LfUtf8'],
        rawSha256Bytes=PIN['sha256Bytes'], gitBlob=PIN['gitBlob'])
