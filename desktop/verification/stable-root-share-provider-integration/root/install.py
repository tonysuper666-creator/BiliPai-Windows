from pathlib import Path
import difflib
import hashlib
import json
import subprocess

root = Path(__file__).resolve().parent
main = root.parents[2]
candidate = main.parent / 'BiliPai-v023'
proposal = main / 'desktop/.local/stable-search-root-integration-acceptance/provider-fix-proposal/exact-hunk.json'

def sha(raw):
    return hashlib.sha256(raw).hexdigest()

def lf(raw):
    return raw.replace(b'\r\n', b'\n')

def git(*args):
    return subprocess.check_output(['git', '-c', 'core.longpaths=true', *args], cwd=candidate)

assert not (root / 'installation.json').exists()
assert not git('status', '--porcelain').strip()
head = git('rev-parse', 'HEAD').decode().strip()
assert head.startswith('f14e6e431')
proposal_raw = proposal.read_bytes()
assert sha(proposal_raw) == 'bdb215e1c44d360fec4e07ab0cd1d9847998c0a1f5cfba984b0e767596e5bd02'
delta = json.loads(proposal_raw)
path = delta['target']
baseline = lf(git('show', delta['baselineHEAD'] + ':' + path)).decode()
assert sha(baseline.encode()) == delta['baselineLFSHA']
assert baseline.count(delta['before']) == 1
assert sha(baseline.replace(delta['before'], delta['after'], 1).encode()) == delta['prospectiveLFSHA']
old = (candidate / path).read_bytes()
bangumi = json.loads((main / 'desktop/.local/bangumi-pages-root-integration/installation.json').read_bytes())
expected = next(row for row in bangumi['sourceTargets'] if row['path'] == path)
assert sha(old) == expected['afterSha256Bytes']
text = lf(old).decode()
assert sha(delta['before'].encode()) == delta['beforeSHA']
assert sha(delta['after'].encode()) == delta['afterSHA']
assert text.count(delta['before']) == 1
after = text.replace(delta['before'], delta['after'], 1)
assert after.count(delta['after']) == 1
assert after.replace(delta['after'], delta['before'], 1) == text
raw = after.replace('\n', '\r\n').encode() if b'\r\n' in old else after.encode()
backup = root / 'before' / path
backup.parent.mkdir(parents=True, exist_ok=True)
backup.write_bytes(old)
(root / 'exact-hunk.json').write_bytes(proposal_raw)
(candidate / path).write_bytes(raw)
assert (candidate / path).read_bytes() == raw
registry = json.loads((candidate / 'desktop/upstream-sources.json').read_bytes())
receipt = dict(baseCommit=head, exactProviderHunks=1,
    sourceCount=len(registry['sources']), resourceCount=len(registry['resources']),
    sameExistingRetainedGalleryBinding=True, newActors=0, newPools=0, newClients=0,
    noResourceOrLifetimeOwnershipChanges=True, rootRuntimeAccepted=False,
    sourceTargets=[dict(path=path, beforeSha256Bytes=sha(old),
        afterSha256Bytes=sha(raw), afterSha256LF=sha(lf(raw)))])
(root / 'source-diff.patch').write_text(''.join(difflib.unified_diff(
    text.splitlines(True), after.splitlines(True), fromfile=path, tofile=path)), encoding='utf-8', newline='\n')
(root / 'installation.json').write_text(json.dumps(receipt, indent=2) + '\n', encoding='utf-8')
print(json.dumps(dict(baseCommit=head, productTargets=1, exactHunks=1,
    sameExistingGalleryBorrowed=True, rootRuntimeAccepted=False)))
