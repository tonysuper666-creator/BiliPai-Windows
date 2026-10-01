from pathlib import Path
import hashlib
import json

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
LANE = REPO / 'desktop/.local/dynamic-detail-reply-parity/detail-container-next'
PREPARED = LANE / 'prepared'


def ext(path):
    value = str(path.absolute()); prefix = chr(92) * 2 + '?' + chr(92)
    return Path(value if value.startswith(prefix) else prefix + value)


producer = 'desktop/tools/extract-upstream-dynamic-detail-container.py'
assert hashlib.sha256((PREPARED / producer).read_bytes()).hexdigest() == 'aa81541c070d3662bc1c187735d4335b5af2795de3e46107272b64069d0e8ed8'
paths = [producer] + ['desktop/src/main/kotlin/com/bilipai/desktop/ui/' + name + '.kt' for name in
    ['DesktopDetailWindow', 'DesktopDetailEffects', 'DesktopDynamicCommentLayoutAdmission', 'DesktopCommentDialogNavigation']]
for path in paths:
    assert not (REPO / path).exists(), path
    source = PREPARED / path
    assert source.is_file(), source
registry_path = REPO / 'desktop/upstream-sources.json'
registry = json.loads(registry_path.read_bytes())
assert len(registry['sources']) == 602
by_path = {row['path']: row for row in registry['sources']}
added, merged = set(), set()
rows = json.loads((LANE / 'original-source-inventory.json').read_bytes())
assert len(rows) == 24 and len({row['path'] for row in rows}) == 23
for row in rows:
    digest = hashlib.sha256(ext(REPO / row['path']).read_bytes().replace(b'\r\n', b'\n')).hexdigest()
    assert digest == row['sha256'], row['path']
    if row['path'] in by_path:
        old = by_path[row['path']]
        assert old['sha256'] == digest
        if 'dynamic-detail-container' not in old['features']:
            old['features'].append('dynamic-detail-container')
        if row['path'] not in added:
            merged.add(row['path'])
    else:
        old = dict(path=row['path'], sha256=digest, features=['dynamic-detail-container'],
            mode='direct' if row['mode'] == 'direct' else 'selected')
        registry['sources'].append(old); by_path[row['path']] = old; added.add(row['path'])
assert len(added) == 18 and len(merged) == 5 and len(registry['sources']) == 620
installed = []
for path in paths:
    data = (PREPARED / path).read_bytes()
    target = REPO / path; target.parent.mkdir(parents=True, exist_ok=True); target.write_bytes(data)
    installed.append(dict(path=path, sha256Bytes=hashlib.sha256(data).hexdigest()))
registry_path.write_text(json.dumps(registry, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
(HERE / 'installation-receipt.json').write_text(json.dumps(dict(installed=installed,
    originalIdentities=23, newIdentities=18, existingIdentitiesMerged=5,
    directSources=7, selectedGeneratedSources=17, registryCount=620,
    actualMainCompile=False, actualMainConsumer=False), indent=2) + '\n', encoding='utf-8')
print('PASS installed five source payloads, 23 unique original identities; registry620.')
