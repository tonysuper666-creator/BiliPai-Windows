from pathlib import Path
import hashlib, json, subprocess

HERE = Path(__file__).resolve().parent
MAIN = HERE.parents[2]
REPO = MAIN.parent / 'BiliPai-v023'
LANE = MAIN / 'desktop/.local/stable-native-media-byte-cache-actual70-proof'
OUT = REPO / 'desktop/verification/stable-native-media-byte-cache-actual70'
assert not OUT.exists()
head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=REPO, text=True).strip()
assert head.startswith('aed8fe55'), head

def wide(p):
    value = str(Path(p).absolute())
    prefix = chr(92) * 2 + '?' + chr(92)
    return Path(value if value.startswith(prefix) else prefix + value)

def read(p): return wide(p).read_bytes()
def sha(value): return hashlib.sha256(value).hexdigest()

rows = []
def put(name, value):
    path = wide(OUT / name)
    path.parent.mkdir(parents=True, exist_ok=True)
    assert not path.exists(), name
    path.write_bytes(value)
    rows.append(dict(path=name, sha256Bytes=sha(value), sizeBytes=len(value)))

raw = read(LANE / 'frozen-handoff.json')
assert sha(raw) == 'd848a493cef685e3f23dc21bcc6c8157fd009791c197c06576662076504d2b5a'
packet = json.loads(raw)
assert len(packet['raw']) == packet['rawCount'] == 34
assert packet['actualSnapshot'] == 70 and packet['productionOverrides'] == 0
assert packet['groups'] == 4 and packet['assertions'] == 21
for item in packet['raw']:
    value = read(LANE / item['path'])
    assert sha(value) == item['sha256Bytes'] and len(value) == item['size'], item['path']
    put('proof/' + item['path'], value)
put('proof/frozen-handoff.json', raw)
snapshot = MAIN / 'desktop/.local/stable-product-snapshot-70'
assert sha(read(snapshot / 'manifest.json')) == packet['snapshotManifestSha']
assert sha(read(snapshot / 'ordered-runtime-cp.json')) == packet['orderedCpSha']
ordered = json.loads(read(snapshot / 'ordered-runtime-cp.json'))
assert len(ordered) == 101
for item in ordered:
    assert sha(read(item['path'])) == item['sha256Bytes'], item['path']
for name in ('manifest.json', 'ordered-runtime-cp.json'):
    put('snapshot70/' + name, read(snapshot / name))
old = MAIN / 'desktop/.local/stable-native-media-byte-cache-parity/frozen-handoff.json'
assert sha(read(old)) == '62a15351de76f512ecb6ccae86e206f8e2fd5eeb8f0582124deff30d2af488e6'
put('root/freeze-native-media-byte-cache-actual70.py', read(__file__))
report = dict(
    sourceCoreCommit=head, actualSnapshot=70, runtimeEntries=101,
    fixtureSourceInputs=1, productionOverrides=0, actualNativeCarrierAccepted=True,
    groups=4, assertions=21, installedStoreRepositoryNativeOwnerMpvUsed=True,
    resolverNormalCompletionAccepted=True, retainedSameCarrierAdoptionAccepted=True,
    oldOwnerCloseDoesNotRevokeAdoptedCarrier=True, retiredCapabilitiesReturn410=True,
    completeMpdRetainedRepresentations=3, completeMpdAdaptationSets=2,
    actualSelectedVideoAndAudioCodecsReadBack=True,
    allRepresentationsSelectedByDemuxerClaimed=False,
    localhostCookieJarRejectionFixturePremiseFailurePreserved=True,
    prepared240Unchanged=True, fixtureExcludedArtifactRecords=21,
    mainShellMounted=False, realAccountAccepted=False, externalCdnAccepted=False,
    cacheErrorDirectFallbackAccepted=False, allMusicEdlPathsAccepted=False,
    desktopExeReplaced=False)
put('acceptance-report.json', (json.dumps(report, indent=2) + '\n').encode())
manifest = (json.dumps(dict(artifacts=rows, excludedRebuildableOutputs=[]), indent=2) + '\n').encode()
wide(OUT / 'artifact-manifest.json').write_bytes(manifest)
wide(OUT / 'README.md').write_text('''Installed native media cache acceptance, actual70
================================================

The unchanged installed actual70 product passes four groups and 21 assertions using one fixture source and zero production overrides. The fixture uses the real SessionStore, Repository, NativeOwner, byte-cache actor and MPV carrier consumption. The exact 101 runtime artifacts and native DLL remain pinned. The complete MPD retains three representations, two adaptation sets and all original range attributes; the selected video and AAC audio have actual decoder readback. Warm native reads do not issue a second origin body-range request.

The actual source load acknowledgement survives normal resolver completion. Retained handoff adopts the same source version and carrier with a new publication. Canceling the old job and closing the old owner preserves adopted reads and playback. New load and current owner close revoke old capabilities with HTTP 410. Explicitly empty typed origin headers are observed on the localhost wire. The real Bilibili-scoped CookieJar rejects HTTP localhost cookies; the earlier fixture assertion expecting otherwise is preserved as a failed run, followed by the corrected closed pass. This is no claim about replaying real Bilibili credentials.

All 34 frozen raw records, both run histories, commands, class origins and before/after pins are retained byte for byte. Twenty-one rebuildable or fixture-owned outputs have explicit exclusion hashes and reasons in the original records. The earlier prospective actual66 packet and its 240 raw records remain unchanged. Its source/core report recorded this independent proof as pending at that earlier freeze; this separate cohort supplies the subsequent installed acceptance.

Full MainShell mounting, real account/CDN behavior, cache-error direct-source fallback and every music/EDL path remain pending. No desktop EXE has been replaced and this isolated acceptance is not a claim of complete feature parity.
''', encoding='utf-8', newline='\n')
print(json.dumps(dict(rawArtifacts=len(rows), manifestSha256Bytes=sha(manifest), sourceCoreCommit=head)))
