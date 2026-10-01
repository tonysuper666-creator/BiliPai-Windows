from pathlib import Path
import hashlib, json

HERE = Path(__file__).resolve().parent
MAIN = HERE.parents[2]
REPO = MAIN.parent / 'BiliPai-v023'
LANE = MAIN / 'desktop/.local/stable-content-dependency-license-parity'
OUT = HERE / 'content-licenses-install53'
assert not OUT.exists()
sha = lambda data: hashlib.sha256(data).hexdigest()
raw = (LANE / 'frozen-handoff.json').read_bytes()
assert sha(raw) == '29ac9b986320a46ad593a6d07a27c05103f9d98b9ca122a78f530f3eabff2b86'
packet = json.loads(raw)
assert len(packet['rawArtifacts']) == 33
for row in packet['rawArtifacts']:
    data = Path(row['path']).read_bytes()
    assert len(data) == row['size'] and sha(data) == row['sha256Bytes'], row['path']
recipe = json.loads((LANE / 'install-recipe.json').read_bytes())
assert len(recipe['payloads']) == 5
pending = []
for row in recipe['payloads']:
    relative = row['targetRelativePath']
    assert relative.startswith('desktop/src/main/resources/licenses/rich-content/')
    target = REPO / relative
    assert target.resolve().is_relative_to(REPO.resolve()) and not target.exists()
    data = Path(row['path']).read_bytes()
    assert len(data) == row['size'] and sha(data) == row['sha256Bytes']
    pending.append((target, data, row))
OUT.mkdir()
for target, data, row in pending:
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(data)
    assert sha(target.read_bytes()) == row['sha256Bytes']
(OUT / 'installed.json').write_text(json.dumps(dict(applied=True, additiveResources=recipe['payloads'],
    sourceRegistryModified=False, runtimeDependenciesModified=False, provenancePacketSha256=sha(raw)), indent=2) + '\n', encoding='utf-8', newline='\n')
print(json.dumps(dict(applied=True, additiveResourceCount=len(pending))))
