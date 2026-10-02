from pathlib import Path
import argparse
import difflib
import hashlib
import json
import subprocess
import sys

sys.stdout.reconfigure(encoding='utf-8')
parser = argparse.ArgumentParser()
parser.add_argument('supplement')
parser.add_argument('contract_sha')
args = parser.parse_args()
root = Path(__file__).resolve().parent
main = root.parents[2]
candidate = main.parent / 'BiliPai-v023'
supplement = (main / args.supplement).resolve()
assert supplement.is_relative_to((main / 'desktop/.local').resolve())

def wide(path):
    return Path('\\\\?\\' + str(path.absolute()))

def read(path):
    return wide(path).read_bytes()

def sha(raw):
    return hashlib.sha256(raw).hexdigest()

def git(*arguments):
    return subprocess.check_output(['git', '-c', 'core.longpaths=true', *arguments], cwd=candidate)

assert not (root / 'installation.json').exists()
assert not git('status', '--porcelain', '-z').strip(), 'Root install requires a clean Candidate'
contract_raw = read(supplement / 'root-install-contract.json')
assert sha(contract_raw) == args.contract_sha
contract = json.loads(contract_raw)
assert git('rev-parse', 'HEAD').decode().strip() == contract['candidateBase']
frozen_packets = (
    (supplement, '3ade80a056d666ed9a15ea995e054cc46ad03d09ecfe9f6c4cb836a119abcb05'),
    (Path(contract['frozenPacket']), contract['frozenHandoffSha256']),
)
packet_checks = []
for packet, expected_sha in frozen_packets:
    manifest = read(packet / 'frozen-handoff.json')
    assert sha(manifest) == expected_sha
    rows = json.loads(manifest)['files']
    assert len({row['path'] for row in rows}) == len(rows)
    for row in rows:
        assert not Path(row['path']).is_absolute() and '..' not in Path(row['path']).parts
        content = read(packet / row['path'])
        assert len(content) == row.get('size', row.get('sizeBytes', row.get('bytes', row.get('lengthBytes')))), row['path']
        assert sha(content) == row['sha256Bytes'], row['path']
    packet_checks.append(dict(path=str(packet), manifestSha256Bytes=expected_sha, rawArtifacts=len(rows)))
names = [row['target'] for row in contract['exactHunkTargets']]
names += [row['target'] for row in contract['originalPacketCopyTargets']]
names += [contract['registry']['target']]
assert len(names) == 10 and len(set(names)) == len(names)
before = {}
for name in names:
    assert not Path(name).is_absolute() and '..' not in Path(name).parts
    path = candidate / name
    assert path.resolve().is_relative_to(candidate.resolve())
    before[name] = read(path) if wide(path).exists() else None
    if before[name] is not None:
        backup = root / 'before' / name
        wide(backup.parent).mkdir(parents=True, exist_ok=True)
        wide(backup).write_bytes(before[name])

command = [sys.executable, str(supplement / 'install.py'), '--candidate', str(candidate),
    '--contract', str(supplement / 'root-install-contract.json'), '--apply',
    '--contract-sha', args.contract_sha, '--report', str(root / 'agent-installation-report.json')]
subprocess.run(command, cwd=main, check=True)
report = json.loads(read(root / 'agent-installation-report.json'))
assert report['passed'] and report['applied'] and report['productionWrites'] == 10
expected = {row['path']: row['desiredRawSha256'] for row in report['targets']}
assert set(expected) == set(names)
targets, patch = [], []
for name in names:
    after = read(candidate / name)
    assert sha(after) == expected[name]
    targets.append(dict(path=name,
        beforeSha256Bytes=sha(before[name]) if before[name] is not None else None,
        afterSha256Bytes=sha(after), afterSha256LF=sha(after.replace(b'\r\n', b'\n'))))
    patch.extend(difflib.unified_diff(
        (before[name] or b'').replace(b'\r\n', b'\n').decode().splitlines(keepends=True),
        after.replace(b'\r\n', b'\n').decode().splitlines(keepends=True), fromfile='a/' + name, tofile='b/' + name))
registry = json.loads(read(candidate / 'desktop/upstream-sources.json'))
assert len(registry['sources']) == 1219 and len(registry['resources']) == 244
receipt = dict(baseCommit=contract['candidateBase'], sourceTargets=targets,
    sourceCount=1219, resourceCount=244, exactExistingTargets=5, newCanonicalFiles=4,
    registrySourceAppends=13, registryFeatureUnions=4, actualProductOverrides=0,
    frozenOriginalContractSha256Bytes=contract['frozenContractSha256'],
    frozenOriginalManifestSha256Bytes=contract['frozenHandoffSha256'],
    finalInstallationContractSha256Bytes=args.contract_sha,
    independentlyValidatedFrozenPackets=packet_checks,
    rootRuntimeAccepted=False, ordinaryAccountAccepted=False, desktopPackageUpdated=False)
(root / 'installation.json').write_text(json.dumps(receipt, indent=2) + '\n', encoding='utf-8')
(root / 'source-diff.patch').write_text(''.join(patch), encoding='utf-8', newline='\n')
print(json.dumps(receipt, indent=2))
