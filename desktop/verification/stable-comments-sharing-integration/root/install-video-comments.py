from pathlib import Path
import hashlib, json, subprocess

HERE = Path(__file__).resolve().parent
MAIN = HERE.parents[2]
REPO = MAIN.parent / 'BiliPai-v023'
LANE = REPO / 'desktop/.local/stable-bgm-detail/video-comment-parity'
FIX = REPO / 'desktop/.local/stable-bgm-detail/video-comment-search-root-fix'

def sha(b): return hashlib.sha256(b).hexdigest()
def safe(p):
    s = str(Path(p).absolute())
    return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s)
def read(p): return safe(p).read_bytes()
def load(p): return json.loads(read(p).decode('utf-8'))
def pin(p, digest):
    b = read(p)
    assert sha(b) == digest, p
    return b

manifest = load(LANE / 'frozen-handoff.json')
pin(LANE / 'frozen-handoff.json', '55e8b1bf787aa1066f8465f284b7f7c6cf97b4ba3221c5370a0bb2f955dc793a')
for r in manifest['frozenFiles']: pin(r['path'], r['sha256Bytes'])
fixed = load(FIX / 'frozen-handoff.json')
pin(FIX / 'frozen-handoff.json', '1e4ff84b411c9744ad1c8bd6adbf2288786c359d484290c43003d4d837f59643')
for r in fixed['sourceInputs'] + fixed['orderedRuntimeCP']: pin(r['path'], r['sha256Bytes'])
pin(fixed['exactHunkPath'], fixed['exactHunkSha256'])
pending = {}
records = []
for r in manifest['payloads']:
    p = REPO / r['path']
    assert not p.exists(), p
    pending[r['path']] = pin(r['preparedPath'], r['sha256Bytes'])
    records.append(dict(path=r['path'], newSource=True, installedSha256Bytes=r['sha256Bytes']))

def hunks(path, changes, base=None, allow_shell_delta=False, desired=None):
    before = pending.get(path, read(REPO / path) if (REPO / path).exists() else b'').decode().replace('\r\n', '\n')
    if base and not allow_shell_delta: assert sha(before.encode()) == base, path
    after = before
    for row in changes:
        a = row.get('old', row.get('before')); b = row.get('new', row.get('after'))
        assert after.count(a) == 1, (path, a[:120])
        after = after.replace(a, b, 1)
    if desired: assert sha(after.encode()) == desired, path
    pending[path] = after.encode()
    records.append(dict(path=path, baseSha256LF=sha(before.encode()), installedSha256LF=sha(after.encode()),
        changes=changes, shellNativeSharingDeltaPreserved=allow_shell_delta))

for r in manifest['exactHunkFiles']:
    plan = load(r['preparedPath'])
    changes = plan.get('hunks', [plan])
    hunks(plan['path'], changes, plan.get('baseLfSha256'), allow_shell_delta=plan['path'].endswith('/DesktopShell.kt'))
fix = load(FIX / 'exact-hunk.json')
hunks(fix['path'], [fix], fix['baseLfSha256'], desired=fix['fixedLfSha256'])

recipe = load(LANE / 'registry-recipe.json')
pin(LANE / 'registry-recipe.json', manifest['registryRecipeSha256'])
registry = load(REPO / 'desktop/upstream-sources.json')
assert registry['upstreamCommit'] == recipe['upstreamCommit']
rows = {r['path']: r for r in registry['sources']}
assert len(rows) == len(registry['sources'])
for r in recipe['appendRows']:
    assert r['path'] not in rows, r['path']
    original = subprocess.check_output(['git', 'show', recipe['upstreamCommit']+':'+r['path']], cwd=REPO).decode().replace('\r\n', '\n')
    assert sha(original.encode()) == r['sha256'], r['path']
    rows[r['path']] = dict(r)
    registry['sources'].append(rows[r['path']])
for r in recipe['existingFeatureMerge']:
    current = rows[r['path']]
    assert current['sha256'] == r['sha256'] and current['mode'] == r['preserveMode'], r['path']
    current['features'] = list(dict.fromkeys(current['features'] + r['appendFeatures']))
pending['desktop/upstream-sources.json'] = (json.dumps(registry, indent=2, ensure_ascii=False)+'\n').encode()

gradle_task = '''val extractVideoCommentUi by tasks.registering(Exec::class) {
    dependsOn(prepareUpstreamSources)
    workingDir(projectDir)
    commandLine(System.getenv("PYTHON_EXECUTABLE") ?: "python", "tools/extract-upstream-video-comment-ui.py",
        "--repo", repositoryRoot.absolutePath,
        "--output", layout.buildDirectory.dir("generated/video-comment-ui").get().asFile.absolutePath)
    inputs.files("tools/extract-upstream-video-comment-ui.py", "tools/extract-upstream-dynamic-reply-protocol.py",
        "tools/extract-upstream-dynamic-reply.py", "tools/sync-upstream.py")
    inputs.file(sourceManifest)
    inputs.files(sources.filter { "stable-video-original-comments" in ((it["features"] as? List<*>) ?: emptyList<Any>()) }
        .map { File(repositoryRoot, it["path"].toString()) })
    outputs.dir(layout.buildDirectory.dir("generated/video-comment-ui"))
}
tasks.named("compileKotlin") { dependsOn(extractVideoCommentUi) }

'''
hunks('desktop/build.gradle.kts', [
    {'old': 'val extractCommentFraudProtocol by tasks.registering(Exec::class) {',
     'new': gradle_task+'val extractCommentFraudProtocol by tasks.registering(Exec::class) {'},
    {'old': '    kotlin.srcDir(layout.buildDirectory.dir("generated/bgm-detail/com"))',
     'new': '    kotlin.srcDir(layout.buildDirectory.dir("generated/bgm-detail/com"))\n'
            '    kotlin.srcDir(layout.buildDirectory.dir("generated/video-comment-ui/com"))'}
])

for path, data in pending.items():
    target = REPO / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(data)
result = dict(installedFiles=len(pending), sourceIdentityCount=len(rows), resourceCount=len(registry['resources']),
    frozenCommentManifestSha256=sha(read(LANE / 'frozen-handoff.json')),
    searchRootFixManifestSha256=sha(read(FIX / 'frozen-handoff.json')), installed=records,
    originalFullCommentTabThreadSearchComposer=True, legacySimplifiedRestPanelRemoved=True,
    sameRootNativeTextShare=True, originalAndroidFullscreenCommentSheetNotClaimed=True, fullRootRuntimeAccepted=False)
(HERE / 'video-comment-install.json').write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
print(json.dumps({k:result[k] for k in ('installedFiles','sourceIdentityCount','resourceCount')}))
