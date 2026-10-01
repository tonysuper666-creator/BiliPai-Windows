from pathlib import Path
import hashlib, importlib.util, json, subprocess, sys
sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
LANE = HERE.parent
MAIN = next(p for p in HERE.parents if (p / '.git').exists())
REPO = MAIN.parent / 'BiliPai-v023'
COMMIT = '3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
def sha(b): return hashlib.sha256(b).hexdigest()
def lf(p): return p.read_text(encoding='utf-8').replace('\r\n', '\n').replace('\r', '\n').encode('utf-8')
def save(p, v): p.parent.mkdir(parents=True, exist_ok=True); p.write_text(json.dumps(v, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
def main():
    tool = LANE / 'prepared/desktop/tools/extract-upstream-favorites.py'
    inventory_path = LANE / 'prepared/source-inventory.json'
    delta_path = LANE / 'prepared/registry-delta.json'
    registry_path = REPO / 'desktop/upstream-sources.json'
    pinned_inputs = {str(p): sha(p.read_bytes()) for p in (tool, inventory_path, delta_path)}
    inventory = json.loads(inventory_path.read_text(encoding='utf-8'))
    delta = json.loads(delta_path.read_text(encoding='utf-8'))
    registry_bytes = registry_path.read_bytes()
    registry = json.loads(registry_bytes)
    (HERE / 'registry-observed.json').write_bytes(registry_bytes)
    output = HERE / ('production-generated-' + (sys.argv[1] if len(sys.argv) > 1 else '02'))
    assert not output.exists(), 'Use a fresh audit-review directory; do not overwrite evidence'
    spec = importlib.util.spec_from_file_location('sole_favorites_producer', tool)
    producer = importlib.util.module_from_spec(spec); spec.loader.exec_module(producer)
    producer.generate(REPO, output) # Deliberately omits standalone=True.
    source_rows = []
    for row in inventory:
        path = row['path']
        raw = subprocess.check_output(['git', 'show', COMMIT + ':' + path], cwd=REPO)
        normalized = raw.replace(b'\r\n', b'\n').replace(b'\r', b'\n')
        expected = row['sha256LfUtf8']
        assert row['upstreamCommit'] == COMMIT
        assert sha(normalized) == expected == sha(lf(REPO / path)), path
        source_rows.append(dict(path=path, sha256LF=expected, gitBlob=subprocess.check_output(['git', 'rev-parse', COMMIT + ':' + path], cwd=REPO, text=True).strip(), stableBlobAndWorktreeAndInventoryEqual=True))
    selected = [out for row in inventory for out in row['outputs'] if out['mode'] == 'selected']
    direct = [out for row in inventory for out in row['outputs'] if out['mode'] == 'direct']
    assert len(inventory) == 60 and len(selected) == 34 and len(direct) == 25
    rows = []
    for row in selected:
        path = row['path']; generated = output / path; prepared = LANE / 'prepared/generated' / path
        assert lf(generated) == lf(prepared) and sha(lf(generated)) == row['sha256LfUtf8'], path
        rows.append(dict(path=path, sha256LF=sha(lf(generated)), defaultAndStandaloneLfEqual=True))
    for row in direct:
        assert not (output / row['path']).exists(), row['path']
        assert sha(lf(LANE / 'prepared/generated' / row['path'])) == row['sha256LfUtf8'], row['path']
    actual = sorted(str(p.relative_to(output)).replace('\\', '/') for p in output.rglob('*.kt'))
    assert actual == sorted(row['path'] for row in selected)
    existing = {r['path']: r for r in registry['sources']}
    merge_rows = []
    source_by_path = {r['path']: r for r in inventory}
    for row in delta:
        assert row['path'] in source_by_path and row['sha256'] == source_by_path[row['path']]['sha256LfUtf8']
        assert row['requestedFeature'] == 'stable-favorites'
        is_existing = row['path'] in existing
        if is_existing:
            assert row['operation'] == 'merge-feature' and row['preserveExistingMode'] is True, row
            assert existing[row['path']]['sha256'] == row['sha256'], row['path']
            assert existing[row['path']]['mode'] == row['mode'], row['path']
        else:
            assert row['operation'] == 'append-identity' and row['preserveExistingMode'] is False, row
        merge_rows.append(dict(path=row['path'], existingAtObservedRegistry=is_existing, operation=row['operation'], existingModePreserved=not is_existing or row['mode'] == existing[row['path']]['mode']))
    assert pinned_inputs == {str(p): sha(p.read_bytes()) for p in (tool, inventory_path, delta_path)}, 'Producer inputs changed while auditing'
    result = dict(status='PASS', scope='source-only default generation and pinned identity review; no compilation/runtime claim', upstreamCommit=COMMIT, sourceIdentityCount=60, generatedOutputCount=59, selectedDefaultOutputCount=34, directReferenceCount=25, directDefaultOutputs=0, countCorrection='Prepared inventory currently has 34 selected and 25 direct; earlier task message said 35/24. Counts are computed from actual mode fields, not reassigned.', sourceRows=source_rows, selectedByteEqualityRows=rows, directRows=direct, registryDeltaRows=merge_rows, observedRegistrySha256Bytes=sha(registry_bytes), observedRegistryCount=len(existing), registryWritten=False, inputs=pinned_inputs, noMainOrSharedSourceEdits=True, noGradle=True, noWindowOrHttp=True)
    save(HERE / 'source-checks.json', result)
    print(json.dumps(dict(status='PASS', sourceChecksSha256Bytes=sha((HERE / 'source-checks.json').read_bytes()), selected=len(selected), directOmitted=len(direct), registryWritten=False)))
if __name__ == '__main__': main()
