from pathlib import Path
import hashlib
import json
import subprocess
import sys

main = Path(__file__).resolve().parents[3]
candidate = main.parent / 'BiliPai-v023'
root = (main / sys.argv[1]).resolve()
packet = (main / sys.argv[2]).resolve()
relative = sys.argv[3]
expected_frozen_sha = sys.argv[4]
target = candidate / relative
assert root.is_relative_to((main / 'desktop/.local').resolve())
assert packet.is_relative_to((main / 'desktop/.local').resolve())
assert Path(relative).parts[:2] == ('desktop', 'verification')

def wide(path):
    return Path('\\\\?\\' + str(path.absolute()))

def read(path):
    return wide(path).read_bytes()

def sha(raw):
    return hashlib.sha256(raw).hexdigest()

def lf(raw):
    return raw.replace(b'\r\n', b'\n')

def git(*args):
    return subprocess.check_output(['git', '-c', 'core.longpaths=true', *args], cwd=candidate)

assert not wide(target).exists()
assert not git('diff', '--cached', '--name-only', '-z').strip()
installation = json.loads(read(root / 'installation.json'))
assert git('rev-parse', 'HEAD').decode().strip() == installation['baseCommit']
build = json.loads(read(root / 'actual-build01.json'))
assert build['exitCode'] == 0 and build['actualProductOverrides'] == 0
assert build['mainClassesCompiled'] and build['testClassesCompiled']
assert sha(read(root / 'actual-build01.log')) == build['logSha256Bytes']
for row in installation['sourceTargets']:
    assert sha(read(candidate / row['path'])) == row['afterSha256Bytes'], row['path']
frozen_raw = read(packet / 'frozen-handoff.json')
assert sha(frozen_raw) == expected_frozen_sha
frozen = json.loads(frozen_raw)
rows = frozen.get('rawArtifacts', frozen.get('rawFiles', frozen.get('raw')))
assert rows and len({row['path'] for row in rows}) == len(rows)
files, excluded = [], []
def copy(path, raw):
    assert path not in {row['path'] for row in files}, path
    destination = target / path
    assert destination.resolve().is_relative_to(target.resolve())
    wide(destination.parent).mkdir(parents=True, exist_ok=True)
    wide(destination).write_bytes(raw)
    files.append(dict(path=path, sha256Bytes=sha(raw), bytes=len(raw)))

copy('prepared/frozen-handoff.json', frozen_raw)
for row in rows:
    raw = read(packet / row['path'])
    size = row.get('sizeBytes', row.get('size', row.get('bytes')))
    assert len(raw) == size and sha(raw) == row['sha256Bytes'], row['path']
    if Path(row['path']).suffix.lower() in ('.jar', '.dll', '.exe', '.class', '.kotlin_module'):
        excluded.append(row)
    else:
        copy('prepared/' + row['path'], raw)
for name in ('install.py', 'installation.json', 'source-diff.patch', 'build_actual.py',
    'actual-build01.log', 'actual-build01.json', 'actual-native-producer-receipt.json',
    'actual-native-producer-input-graph.json'):
    copy('root/' + name, read(root / name))
copy('root/save_and_stage.py', read(Path(__file__).resolve()))
for row in installation['sourceTargets']:
    if row['beforeSha256Bytes'] is not None:
        raw = read(root / 'before' / row['path'])
        assert sha(raw) == row['beforeSha256Bytes']
        copy('root/before/' + row['path'], raw)
summary = json.loads(read(root / 'integration-summary.json'))
assert summary['baseCommit'] == installation['baseCommit']
assert summary['sourceTargets'] == installation['sourceTargets']
assert summary['normalMainAndTestCompilePassed']
summary['excludedCompiledProofFiles'] = excluded
copy('integration-summary.json', (json.dumps(summary, indent=2) + '\n').encode())
copy('README.md', read(root / 'README.md'))
index = (json.dumps(dict(files=files), indent=2) + '\n').encode()
wide(target / 'artifact-index.json').write_bytes(index)
attributes = candidate / '.gitattributes'
raw = read(attributes)
line = (relative + '/** -text\n').encode()
assert line not in raw
wide(attributes).write_bytes(raw + (b'' if raw.endswith(b'\n') else b'\n') + line)
expected = {relative + '/' + row['path']: row['sha256Bytes'] for row in files}
expected[relative + '/artifact-index.json'] = sha(index)
source_paths = ['.gitattributes'] + [row['path'] for row in installation['sourceTargets']]
for path in source_paths:
    expected[path] = sha(lf(read(candidate / path)))
paths = list(expected)
for start in range(0, len(paths), 20):
    git('add', '-f', '--', *paths[start:start + 20])
actual_paths = [item.decode('utf-8') for item in git('diff', '--cached', '--name-only', '-z').split(b'\0') if item]
assert set(actual_paths) == set(paths)
for path, digest in expected.items():
    assert sha(git('show', ':' + path)) == digest, path
git('diff', '--cached', '--check', '--', *source_paths)
(root / 'stage-verification.json').write_text(json.dumps(dict(
    stagedExactBlobs=len(paths), productTargets=len(installation['sourceTargets']),
    rawProofFiles=len(files), frozenRawArtifacts=len(rows),
    excludedCompiledProofFiles=len(excluded), sourceWhitespacePassed=True,
    everyStagedBlobVerified=True), indent=2) + '\n', encoding='utf-8')
print(json.dumps(dict(stagedExactBlobs=len(paths), productTargets=len(installation['sourceTargets']),
    rawProofFiles=len(files))))
