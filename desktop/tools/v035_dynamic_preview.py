"""Whole original dynamic preview/card/rich-text closure; existing Root read/write authorities."""
from pathlib import Path
import hashlib, json, os
from v035_dynamic_link import fixed as fixed_link_sources

ROOT = Path(__file__).resolve().parent.parent / 'upstream-slices/v035-dynamic-preview'
MANIFEST_SHA256 = '338944574b1b6cc29b7c1c681cbb28b272402cb69036eacb7d8c15f2a9adfcdd'

def wide(path):
    value = str(path.resolve())
    return Path(value if value.startswith('\\\\?\\') else '\\\\?\\' + value) if os.name == 'nt' else path

def original_entries():
    raw = wide(ROOT / 'manifest.json').read_bytes()
    assert hashlib.sha256(raw).hexdigest() == MANIFEST_SHA256
    manifest = json.loads(raw)
    result = {}
    for row in manifest['sources']:
        body = wide(ROOT / row['archiveFile']).read_bytes()
        assert hashlib.sha256(body).hexdigest() == row['sha256']
        result[row['path']] = (row, body)
    return manifest, result

def updated_original(path, original):
    manifest, entries = original_entries()
    if path.endswith(('/dynamic/components/DynamicCard.kt', '/dynamic/components/ForwardedContent.kt')):
        _, sources = fixed_link_sources()
        filename = path.rsplit('/', 1)[1]
        raw = sources[filename]
        if filename == 'DynamicCard.kt':
            assert hashlib.sha256(raw).hexdigest() == manifest['existingWholeCardSha256']
        return raw.replace(b'\r\n', b'\n').decode()
    if path.endswith('/dynamic/components/DynamicRichTextPolicy.kt'):
        return entries[path][1].replace(b'\r\n', b'\n').decode()
    return original

def prepare_latest_card(original):
    manifest, _ = fixed_link_sources()
    slot = next(row for row in manifest['card']['consumers']
        if row['name'] == 'complete-original-reserve-override-and-link-card-slot-closure')
    assert original.count(slot['original']) == 1
    # The exact existing two feedback/Context seams retain the whole original slot.
    return original.replace(slot['original'], slot['adapted'], 1)

def emit_preview_core(output):
    _, entries = original_entries()
    rows = []
    for path, (row, raw) in entries.items():
        if 'adaptation' not in row: continue
        body = raw.decode()
        for edit in row['adaptation']['edits']:
            assert body.count(edit['before']) == 1
            body = body.replace(edit['before'], edit['after'], 1)
        assert hashlib.sha256(body.encode()).hexdigest() == row['adaptation']['adaptedSha256']
        relative = path.split('/src/main/java/', 1)[1]
        target = wide(output / relative)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(body.encode())
        rows.append(target)
    assert len(rows) == 3
    return rows
