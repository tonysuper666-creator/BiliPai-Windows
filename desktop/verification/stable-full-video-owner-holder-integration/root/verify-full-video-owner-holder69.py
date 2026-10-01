from pathlib import Path
import ast, hashlib, json, os

HERE = Path(__file__).resolve().parent; MAIN = HERE.parents[2]; REPO = MAIN.parent / 'BiliPai-v023'
OUT = HERE / 'full-video-owner-holder-production69.json'; assert not OUT.exists()
def wide(p):
    s = str(Path(p).absolute()); prefix = chr(92)*2 + '?' + chr(92)
    return Path(s if s.startswith(prefix) or os.name != 'nt' else prefix+s)
def read(p): return wide(p).read_bytes()
def digest(b): return hashlib.sha256(b.replace(b'\r\n', b'\n')).hexdigest()
def assignments(path):
    return {n.targets[0].id: n.value for n in ast.parse(read(path).decode()).body
            if isinstance(n, ast.Assign) and len(n.targets) == 1 and isinstance(n.targets[0], ast.Name)}

registry = json.loads(read(REPO/'desktop/upstream-sources.json'))
assert len(registry['sources']) == 1169 and len(registry['resources']) == 213
by_path = {r['path']: r for r in registry['sources']}
owner = assignments(REPO/'desktop/tools/extract-upstream-video-full-owner.py')
owner_rows = json.loads(ast.literal_eval(owner['RECIPES'].args[0]))
holder = assignments(REPO/'desktop/tools/extract-upstream-video-detail-holder.py')
holder_rows = ast.literal_eval(holder['SPECS'])
assert len(owner_rows) == 16 and len(holder_rows) == 53
rows, direct = [], []
for family, directory, recipes in [('owner','original-video-full-owner',owner_rows),
                                   ('holder','original-video-detail-holder-full',holder_rows)]:
    root = REPO/'desktop/build/generated'/directory
    selected_count = 0; direct_count = 0
    for r in recipes:
        origin = r.get('originalPath',r.get('source'))
        expected = r.get('outputSha256LF',r.get('outputSHA256LF'))
        source_hash = r.get('originalSha256LF',r.get('sourceSHA256LF'))
        assert by_path[origin]['sha256'] == source_hash and digest(read(REPO/origin)) == source_hash
        is_direct = r.get('direct',r.get('mode')=='direct')
        output = root/r['output']
        if is_direct:
            assert by_path[origin]['mode'] == 'direct' and not wide(output).exists(), r['output']
            synced = REPO/'desktop/build/generated/upstream'/origin
            assert digest(read(synced)) == expected, origin
            direct.append(dict(family=family,source=origin,output=r['output'],sha256LF=expected))
            direct_count += 1
        else:
            assert by_path[origin]['mode'] != 'direct', origin
            assert digest(read(output)) == expected, r['output']
            rows.append(dict(family=family,path=r['output'],sha256LF=expected))
            selected_count += 1
    assert selected_count == {'owner':6,'holder':32}[family]
    assert direct_count == {'owner':10,'holder':21}[family]
    assert len(list(wide(root).rglob('*.kt'))) == selected_count
assert len(rows) == 38 and len(direct) == 31
assert len({r['path'] for r in rows}) == 38
assert not set(r['path'] for r in rows) & set(r['output'] for r in direct)
report = dict(passed=True,selectedProductionBodiesCompared=38,directPoliciesSyncOnly=31,
    uniqueDirectOriginalIdentities=len({r['source'] for r in direct}),
    exactFrozenLFSourceMatches=rows,directSourceChecks=direct,
    sourceIdentityCount=1169,resourceCount=213,wholeRootMounted=False,
    sourceInventoryIsNotFunctionalOrReusePercentage=True)
OUT.write_text(json.dumps(report,indent=2)+'\n',encoding='utf8')
print(json.dumps({k:report[k] for k in ('passed','selectedProductionBodiesCompared','directPoliciesSyncOnly','uniqueDirectOriginalIdentities')}))
