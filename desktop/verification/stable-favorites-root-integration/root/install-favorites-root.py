from pathlib import Path
import hashlib, json, subprocess

HERE = Path(__file__).resolve().parent
MAIN = HERE.parents[2]
REPO = MAIN.parent / 'BiliPai-v023'
LANE = MAIN / 'desktop/.local/stable-favorites-root-wiring'

def wide(p): return Path('\\\\?\\' + str(p.absolute()))
def read(p): return wide(p).read_bytes()
def sha(b): return hashlib.sha256(b).hexdigest()
def lf(b): return b.decode('utf-8').replace('\r\n', '\n').encode()
def pin(p, digest):
    b = read(p)
    assert sha(b) == digest, p
    return b

raw = pin(LANE / 'frozen-handoff.json', 'f936ef8fac172cf6e71d11b4cefd0bb257d99127656546d898c19e23e05ea38f')
manifest = json.loads(raw)
for row in manifest['artifacts']:
    b = pin(LANE / row['path'], row['sha256Bytes'])
    assert len(b) == row['size'], row['path']
contract = json.loads(pin(LANE / 'install-whitelist.json', '4810e58e8c5ec73839a18b2369b35023932e26198db339a405118953b53dab65'))
payloads = []
for row in contract['newFiles']:
    b = read(LANE / row['source'])
    assert sha(lf(b)) == row['sha256LF']
    assert not wide(REPO / row['target']).exists(), row['target']
    payloads.append((row['target'], b))
before = {}
for row in contract['patches']:
    b = read(REPO / row['file'])
    assert sha(lf(b)) == row['baseLF'], row['file']
    pin(LANE / row['patch'], row['patchSHA'])
    subprocess.run(['git', 'apply', '--check', '--ignore-space-change', '--ignore-whitespace', str(LANE / row['patch'])], cwd=REPO, check=True)
    before[row['file']] = b
for row in contract['patches']:
    saved = wide(HERE / 'favorite-root-install-baseline' / row['file'])
    saved.parent.mkdir(parents=True, exist_ok=True)
    saved.write_bytes(before[row['file']])
    subprocess.run(['git', 'apply', '--ignore-space-change', '--ignore-whitespace', str(LANE / row['patch'])], cwd=REPO, check=True)
    assert sha(lf(read(REPO / row['file']))) == row['desiredLF'], row['file']
for target, b in payloads:
    p = wide(REPO / target)
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_bytes(b)
report = dict(frozenManifestSha256Bytes=sha(raw), allFrozenArtifactsVerified=42, exactWhitelistInstalled=True,
              newManualFiles=[p for p, _ in payloads], localPatches=contract['patches'],
              controllerOrApiOrRegistryOrBuildOverwritten=False, originalRootSourceWired=True,
              wholeCandidateCompileAccepted=False, actualRootMountedUiAccepted=False,
              realAccountOrDecodingAccepted=False, desktopExeReplaced=False)
wide(HERE / 'favorites-root-install.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
print(json.dumps(dict(installed=True, newFiles=len(payloads), localPatches=len(contract['patches']))))
