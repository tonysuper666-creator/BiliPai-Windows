"""Freeze this task-only prepared source cohort; never install or edit product files."""
from pathlib import Path
import ast, hashlib, json, subprocess, sys
sys.dont_write_bytecode = True
P = Path(__file__).resolve().parent
MAIN = P.parents[2]
REPO = MAIN.parent / 'BiliPai-v023'
COMMIT = '3d5d19a2f994daccd0e2f8b5f522b6d82f43d589'
FEATURE = 'stable-video-fullscreen-pager'

def wide(p):
    s = str(Path(p).absolute())
    prefix = chr(92) * 2 + '?' + chr(92)
    return Path(s if s.startswith(prefix) else prefix + s)
def raw(p): return wide(p).read_bytes()
def lf(p): return raw(p).replace(b'\r\n', b'\n')
def sha(b): return hashlib.sha256(b).hexdigest()
def readj(p): return json.loads(raw(p).decode('utf-8'))
def put(p, value):
    p = wide(p); p.parent.mkdir(parents=True, exist_ok=True)
    p.write_bytes(value.encode('utf-8') if isinstance(value, str) else value)
def save(p, value): put(p, json.dumps(value, ensure_ascii=False, indent=2) + '\n')
def row(p):
    p = wide(p)
    b = p.read_bytes()
    return dict(path=p.relative_to(wide(P)).as_posix(), bytes=len(b), sha256Bytes=sha(b))

def main():
    assert not wide(P/'frozen-handoff.json').exists(), 'This final cohort is immutable after first freeze.'
    tool = P/'install-packet/tools/extract-upstream-video-fullscreen-pager.py'
    tree = ast.parse(raw(tool).decode('utf-8'))
    specs = next(ast.literal_eval(n.value) for n in tree.body
                 if isinstance(n, ast.Assign) and any(isinstance(t, ast.Name) and t.id == 'SPECS' for t in n.targets))
    audit = readj(P/'source-identity-output-audit.json')
    assert audit['forwardReverseExact'] and len(specs) == len(audit['outputs']) == 34
    direct = [s for s in specs if s['mode'] == 'direct']
    selected = [s for s in specs if s['mode'] != 'direct']
    assert len(direct) == 15 and len(selected) == 19
    producer_rows = []
    for s in specs:
        out = s['output']
        prepared = lf(P/'prepared/generated'/out)
        assert sha(prepared) == s['outputSHA']
        assert lf(P/'producer-proof-02/standalone'/out) == prepared
        production = wide(P/'producer-proof-02/production'/out)
        if s['mode'] == 'direct': assert not production.exists(), out
        else: assert lf(production) == prepared, out
        compiled = P/'runs/pager-07/source-inputs/prepared/generated'/out
        assert lf(compiled) == prepared, out
        producer_rows.append(dict(output=out, mode=s['mode'], sha256LF=sha(prepared),
                                  standaloneByteEqual=True, productionGenerated=s['mode'] != 'direct',
                                  finalCompileInputByteEqual=True))
    assert len(list(wide(P/'producer-proof-02/standalone').rglob('*.kt'))) == 34
    assert len(list(wide(P/'producer-proof-02/production').rglob('*.kt'))) == 19
    manual = list(wide(P/'prepared/manual').rglob('*.kt'))
    assert len(manual) == 2
    for file in manual:
        rel = file.relative_to(wide(P/'prepared/manual'))
        assert lf(file) == lf(P/'install-packet/manual'/rel)
        assert lf(file) == lf(P/'runs/pager-07/source-inputs/prepared/manual'/rel)
    save(P/'final-source-checks.json', dict(passed=True, sourceCommit=COMMIT,
        generatedOutputCount=34, selectedProductionOutputCount=19, directSyncOnlyCount=15,
        uniqueGeneratedSourceIdentities=len({s['origin'] for s in specs}),
        exactForwardReverseAudit='source-identity-output-audit.json',
        manualInputByteEqual=True, rows=producer_rows))

    # A recipe, not a replacement registry. Existing row mode/SHA/features remain Root-owned.
    registry = REPO/'desktop/upstream-sources.json'
    put(P/'registry-baseline.json', raw(registry))
    baseline = readj(registry); index = {r['path']: r for r in baseline['sources']}
    origin_specs = {}
    for spec in specs: origin_specs.setdefault(spec['origin'], []).append(spec)
    protocol_path = audit['protocolOriginalSource']
    origins = {path: rows[0]['originalSHA'] for path, rows in origin_specs.items()}
    origins[protocol_path] = audit['protocolOriginalSHA']
    merges, additions, source_rows = [], [], []
    for path, original_sha in sorted(origins.items()):
        blob = subprocess.check_output(['git', '-C', str(REPO), 'show', f'{COMMIT}:{path}'])
        blob_lf = blob.replace(b'\r\n', b'\n')
        assert sha(blob_lf) == original_sha, path
        assert lf(P/'original-stable'/path) == blob_lf, path
        source_rows.append(dict(path=path, sha256LF=original_sha,
                               gitBlob=subprocess.check_output(['git', '-C', str(REPO), 'rev-parse', f'{COMMIT}:{path}'], text=True).strip()))
        if path in index:
            before = index[path]; assert before['sha256'] == original_sha, path
            after = dict(before); after['features'] = sorted(set(before.get('features', [])) | {FEATURE})
            merges.append(dict(path=path, before=before, after=after, preserveShaAndMode=True))
        else:
            source_specs = origin_specs[path]
            mode = 'direct' if all(s['mode'] == 'direct' for s in source_specs) else 'selected'
            additions.append(dict(path=path, sha256=original_sha, features=[FEATURE], mode=mode))
    save(P/'registry-merge-recipe.json', dict(sourceCommit=COMMIT,
        baselinePath=str(registry), baselineSHA256Bytes=sha(raw(registry)), baselineIdentityCount=len(index),
        sourceIdentities=len(origins), generatedOutputSourceIdentities=len(origin_specs), protocolAppendSourceIdentities=1,
        newIdentities=len(additions), existingFeatureUnions=len(merges),
        instructions='Apply only absent additions and feature unions. Never replace the complete registry or any existing mode/SHA. DIRECT outputs are solely prepareUpstreamSources-owned.',
        additions=additions, merges=merges, originalSourcePins=source_rows))

    put(P/'root-build-proposal.kts', '''// Append source-only task wiring. Do not replace the existing build file.
val extractOriginalVideoFullscreenPager by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-video-fullscreen-pager.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/original-video-fullscreen-pager").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-video-fullscreen-pager.py", "tools/sync-upstream.py",
        "tools/extract-upstream-dynamic-reply-protocol.py", sourceManifest)
    inputs.files(sources.filter { "stable-video-fullscreen-pager" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { File(repositoryRoot, it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/original-video-fullscreen-pager"))
}
kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-video-fullscreen-pager")) }
tasks.named("compileKotlin") { dependsOn(extractOriginalVideoFullscreenPager) }
tasks.named("extractOriginalVideoStateCore") { inputs.file("tools/extract-upstream-video-fullscreen-pager.py") }
// Production omits --standalone. DIRECT15 must be copied once by registry Sync.
''')

    compile_result = readj(P/'runs/pager-07/compile-result.json')
    symbols = readj(P/'runs/pager-07/symbol-audit.json')
    assert compile_result['passed'] and symbols['passed'] and not symbols['topLevelMethodIntersections']
    assert readj(P/'runs/pager-07/pins-before.json') == readj(P/'runs/pager-07/pins-after.json')
    snap = MAIN/'desktop/.local/stable-product-snapshot-55'
    assert sha(raw(snap/'manifest.json')) == 'c26f15be9d6472c30b2a58a97d50c85cedc11d8074df2c1516620dd33334f124'
    assert sha(raw(snap/'ordered-runtime-cp.json')) == '8118ea8bb2d916b8e81ad2f6e678115b437ac458a4c94cd42dc9c1a0813ea55a'
    save(P/'install-whitelist.json', dict(sourceOnly=True,
        files=[row(tool)] + [row(p) for p in wide(P/'install-packet/manual').rglob('*.kt')],
        exactLocalHunks=[row(P/'install-packet/local-hunks/core-protocol-append.json')],
        registryRecipe=row(P/'registry-merge-recipe.json'), buildProposal=row(P/'root-build-proposal.kts'),
        neverInstall=['candidate.jar', 'proof-only whole Core source', 'parent CommentURL reference source',
                      'standalone DIRECT copies alongside registry Sync'],
        requiresRootOwnedImplementations=True, preservesSoleStoreClientCachePlayer=True))
    save(P/'result.json', dict(passed=True, preparedSourceOnly=True, productionRuntimeAcceptance=False,
        sourceCommit=COMMIT, actualDependency=dict(snapshot=55, runtimeEntries=101,
        manifestSHA256Bytes=sha(raw(snap/'manifest.json')), orderedCpSHA256Bytes=sha(raw(snap/'ordered-runtime-cp.json')),
        kotlinSHA256Bytes=symbols['actualKotlinSHA']),
        finalCompile=compile_result, symbols=symbols, generation=dict(standalone=34, production=19, directSkip=15),
        originalWholeFileInputs=29, generatedSourceIdentities=32, protocolAppendIdentities=1,
        existingPureThreadPolicies='Referenced actual two declarations; not generated twice.',
        viewport='Two complete original declarations moved from Pager into one sole shared source; Pager excludes those copies.',
        pending=['Root full-VM required facades', 'Root Window/fullscreen/PiP and source-owned Surface binding',
                 'real owned prefetchRange media-byte cache/transport capability',
                 'whole installation compile and actual native/mounted UI acceptance'],
        historicalFailuresRetained=['fullscreen-01', 'fullscreen-02', 'pager-01', 'pager-02',
            'pager-03 duplicate pure thread symbols', 'pager-04 uses pre-correction source, not final acceptance']))

    extensions = {'.py', '.kt', '.json', '.md', '.kts', '.fragment', '.log', '.args'}
    artifacts = []
    excluded = []
    for p in sorted(wide(P).rglob('*')):
        if not p.is_file(): continue
        rel = p.relative_to(wide(P)).as_posix()
        if '__pycache__' in rel or p.suffix not in extensions:
            excluded.append(dict(path=rel, reason='Compiled binary or unregistered intermediate; no product payload.'))
            continue
        artifacts.append(row(p))
    manifest = dict(frozen=True, task='full-original-stable-fullscreen-and-portrait-pager-source-closure',
        sourceCommit=COMMIT, sourceOnlyPrepared=True, productRuntimeAcceptance=False,
        artifactCount=len(artifacts), artifacts=artifacts, excluded=excluded,
        result='result.json', whitelist='install-whitelist.json',
        notes=['LF normalization is CRLF to LF only; no trimming.',
               'Failure/history sources and logs remain registered; final acceptance is pager-07 only.',
               'Candidate/proof Core JAR is not installed. Root applies source-only exact hunk to sole Core producer.',
               'No Main/Candidate source, shared Gradle, account, HTTP, native HWND or GUI operation occurred.'])
    save(P/'frozen-handoff.json', manifest)
    print(json.dumps(dict(manifest=str(P/'frozen-handoff.json'), sha256Bytes=sha(raw(P/'frozen-handoff.json')),
        artifactCount=len(artifacts), generated=34, production=19, direct=15,
        newIdentities=len(additions), unions=len(merges), sourceIdentities=len(origins)), ensure_ascii=False))

if __name__ == '__main__': main()
