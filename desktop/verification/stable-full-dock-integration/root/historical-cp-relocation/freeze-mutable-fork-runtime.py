from pathlib import Path
import hashlib, json
HERE = Path(__file__).resolve().parent
MAIN = HERE.parents[2]
OUT = MAIN / 'desktop/.local/stable-miuix5157-original-runtime'
def wide(p): return Path('\\\\?\\' + str(p.absolute()))
def read(p): return wide(p).read_bytes()
def sha(b): return hashlib.sha256(b).hexdigest()
def pin(p, digest):
    b = read(p)
    assert sha(b) == digest, p
    return b
expected = {
    17: ('3b56a4a47fbd1d06b1d3b4be274bc3f69597dea669f04358eb7282292ab51e44', '25533b03785344f3a4a360a6db292775ad370824449c7a9c3a5b98702e3812d6'),
    20: ('41439c3242e279bc0dc09a20501bdcb36e13cda7f66ab2bbbe537e7538d3ecf2', '4d1499c7ddbeeddbf31427505930ab0c3d5c6a3aaaf8dc08d2a585edca720232'),
    22: ('90df091a72910d314030cac755dcc980a8fc711111b8e5d20886fbf22e182cba', 'f871605f15d537b71a0981bc556b47979f68d413447dfc1d97cad373c43374fb'),
}
OUT.mkdir(exist_ok=True)
receipts = []
for phase, (manifest_digest, cp_digest) in expected.items():
    snap = MAIN / f'desktop/.local/stable-product-snapshot-{phase}'
    pin(snap / 'manifest.json', manifest_digest)
    cp = json.loads(pin(snap / 'ordered-runtime-cp.json', cp_digest))
    changes = []
    assert len(cp) == 92
    for row in cp:
        b = pin(Path(row['path']), row['sha256Bytes'])
        if '/third-party/miuix5157/build/libs/' not in row['path'].replace('\\', '/'):
            continue
        assert row['sha256Bytes'] == '78e22c70dd152058f2f7570f59906607e253231f1db346494820bd7e9db7b215'
        target = OUT / Path(row['path']).name
        if target.exists(): pin(target, row['sha256Bytes'])
        else: target.write_bytes(b)
        changes.append(dict(originalPath=row['path'], relocatedPath=str(target), sha256Bytes=row['sha256Bytes']))
        row['path'] = str(target)
    assert len(changes) == 1
    target = OUT / f'ordered-runtime-cp-{phase}.json'
    assert not target.exists()
    raw = (json.dumps(cp, indent=2) + '\n').encode()
    target.write_bytes(raw)
    receipts.append(dict(phase=phase, originalManifestSha256Bytes=manifest_digest,
        originalOrderedCpSha256Bytes=cp_digest, relocatedOrderedCpPath=str(target),
        relocatedOrderedCpSha256Bytes=sha(raw), byteIdenticalLibraryRelocations=changes,
        productArtifactsUnchanged=True, originalSnapshotManifestsUnchanged=True,
        productionOverrides=0, classpathEntryCount=92))
raw = (json.dumps(dict(reason='Preserve byte-identical existing source-built Miuix library before its shared Gradle artifact is rebuilt with original icons', receipts=receipts), indent=2) + '\n').encode()
(OUT / 'relocation-ledger.json').write_bytes(raw)
print(json.dumps(dict(ledgerSha256Bytes=sha(raw), receipts=receipts)))
