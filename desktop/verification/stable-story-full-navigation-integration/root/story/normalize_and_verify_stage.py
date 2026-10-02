from pathlib import Path
import hashlib
import json
import re
import subprocess

root = Path(__file__).resolve().parent
candidate = root.parents[2].parent / 'BiliPai-v023'
relative = 'desktop/verification/stable-story-full-navigation-integration'
proof = candidate / relative
test_path = 'desktop/src/test/kotlin/com/bilipai/desktop/settings/DesktopFullNavigationSettingsTest.kt'

def wide(path):
    return Path('\\\\?\\' + str(path.absolute()))

def read(path):
    return wide(path).read_bytes()

def sha(raw):
    return hashlib.sha256(raw).hexdigest()

def git(*args, input=None):
    return subprocess.check_output(['git', '-c', 'core.longpaths=true', *args], cwd=candidate, input=input)

summary = json.loads(read(proof / 'integration-summary.json'))
entry = next(row for row in summary['sourceTargets'] if row['path'] == test_path)
old = read(candidate / test_path)
assert sha(old) == entry['afterSha256Bytes']
new, count = re.subn(br'(?m)^[ \t]+(?=\r?$)', b'', old)
assert count == 14 and new != old
assert len(old.splitlines()) == len(new.splitlines())
assert [line for line in old.splitlines() if line.strip()] == [line for line in new.splitlines() if line.strip()]
wide(candidate / test_path).write_bytes(new)
record = {'path': test_path, 'beforeSha256Bytes': sha(old),
          'afterSha256Bytes': sha(new),
          'afterSha256LF': sha(new.replace(b'\r\n', b'\n')),
          'blankLinesWithSpacesNormalized': count,
          'nonBlankLinesByteIdentical': True,
          'testLogicChanged': False,
          'testExecutionPredatesBlankLineNormalization': True,
          'repeatTestNeededForThisFormattingOnlyChange': False}
record_raw = (json.dumps(record, indent=2) + '\n').encode()
(root / 'test-whitespace-normalization.json').write_bytes(record_raw)
wide(proof / 'root/story/test-whitespace-normalization.json').write_bytes(record_raw)
wide(proof / 'root/story/normalize_and_verify_stage.py').write_bytes(Path(__file__).read_bytes())
entry.update(afterSha256Bytes=record['afterSha256Bytes'], afterSha256LF=record['afterSha256LF'])
summary['postBuildWhitespaceNormalization'] = record
summary_raw = (json.dumps(summary, indent=2) + '\n').encode()
wide(proof / 'integration-summary.json').write_bytes(summary_raw)
index = json.loads(read(proof / 'artifact-index.json'))
for item in index['files']:
    if item['path'] == 'integration-summary.json':
        item.update(sha256Bytes=sha(summary_raw), bytes=len(summary_raw))
for path in ['root/story/test-whitespace-normalization.json', 'root/story/normalize_and_verify_stage.py']:
    raw = read(proof / path)
    index['files'].append({'path': path, 'sha256Bytes': sha(raw), 'bytes': len(raw)})
wide(proof / 'artifact-index.json').write_text(json.dumps(index, indent=2) + '\n', encoding='utf-8', newline='\n')
for path in [test_path, relative + '/artifact-index.json', relative + '/integration-summary.json',
             relative + '/root/story/test-whitespace-normalization.json', relative + '/root/story/normalize_and_verify_stage.py']:
    git('add', '-f', '--', path)
exact = {relative + '/' + item['path']: item['sha256Bytes'] for item in index['files']}
for extra in ['README.md', 'artifact-index.json']:
    path = relative + '/' + extra
    exact[path] = sha(read(candidate / path))
source_paths = ['.gitattributes'] + [row['path'] for row in summary['sourceTargets']]
for row in summary['sourceTargets']:
    assert sha(read(candidate / row['path'])) == row['afterSha256Bytes'], row['path']
for path in source_paths:
    exact[path] = sha(read(candidate / path).replace(b'\r\n', b'\n'))
staged = set(git('diff', '--cached', '--name-only', '-z').decode().split('\0')) - {''}
assert staged == set(exact), staged.symmetric_difference(exact)
output = git('cat-file', '--batch', input=''.join(':' + path + '\n' for path in exact).encode())
position = 0
for path, expected in exact.items():
    end = output.index(b'\n', position)
    header = output[position:end].decode().split()
    assert len(header) == 3 and header[1] == 'blob'
    size = int(header[2]); position = end + 1
    raw = output[position:position + size]; position += size + 1
    assert sha(raw) == expected, path
assert position == len(output)
git('diff', '--cached', '--check', '--', *source_paths, relative + '/README.md')
print(json.dumps({'exactStagedPaths': len(exact), 'rawProofFiles': len(index['files']),
                  'allStagedBlobsVerified': True, 'productWhitespaceChecked': True,
                  'testBlankLinesNormalized': count,
                  'excludedCompiledProofFiles': len(summary['excludedCompiledProofFiles'])}, indent=2))
