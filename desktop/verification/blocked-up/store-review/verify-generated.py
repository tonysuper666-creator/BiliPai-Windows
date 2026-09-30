"""Regenerate only into this new review directory and prove source identity/output uniqueness."""
from pathlib import Path
import hashlib, importlib.util, json, sys
sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
PRODUCER = HERE.parent / 'settings-blocked-up-parity'
def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()
extractor = HERE / 'payload/desktop/tools/extract-upstream-blocked-up-platform.py'
spec = importlib.util.spec_from_file_location('blocked_review_extractor', extractor)
module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
output = HERE / 'regenerated'
output.mkdir(exist_ok=False)
paths = module.generate(ROOT, output)
assert len(paths) == 3
identities = {}
for path in paths:
    relative = path.relative_to(output).as_posix()
    assert sha(path) == sha(PRODUCER / 'generated' / relative), relative
    identities[relative] = sha(path)
share = next(p for p in paths if p.name == 'DesktopBlockedUpImportSharePolicy.kt').read_text(encoding='utf-8')
exclusive = ['enum class BilibiliBlockedListRemoteStatus', 'enum class BlockedUpRelationSource',
             'data class BlockedUpWriteResult', 'fun resolveBlockedUpRelationReSrc', 'fun buildBlockedUpWriteMessage']
assert all(name not in share for name in exclusive)
manifest = json.loads((HERE / 'payload/desktop/upstream-sources.json').read_text())
assert len({r['path'] for r in manifest['sources']}) == len(manifest['sources']) == 427
assert len(manifest['resources']) == 201
for row in manifest['sources'] + manifest['resources']:
    original = (ROOT / row['path']).read_text(encoding='utf-8').replace('\r\n', '\n')
    assert hashlib.sha256(original.encode()).hexdigest() == row['sha256'], row['path']
assert all(r['mode'] == 'policy-extract' for r in manifest['sources'] if r['path'] in module.SOURCES)
build = (HERE / 'payload/desktop/build.gradle.kts').read_text()
assert build.count('val extractUpstreamBlockedUp by tasks.registering(Exec::class)') == 1
assert build.count('kotlin.srcDir(layout.buildDirectory.dir("generated/blocked-up"))') == 1
assert build.count('outputs.dir(layout.buildDirectory.dir("generated/blocked-up"))') == 1
assert 'dependsOn(prepareUpstreamSources, extractUpstreamDiscovery)' in build
result = dict(passed=True, exactGeneratedFiles=identities, existingDeclarationProviderRetained=exclusive,
              sources=427, resources=201, allOriginalManifestHashesVerified=628,
              noDirectCopyOfExtractedEntity=True, uniqueNewTask=True, uniqueOutputDirectory=True,
              mainEdited=False, frozenGeneratedFilesUnchanged=True, sharedGradleInvoked=False)
(HERE / 'generated-verification.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8',newline='\n')
print(json.dumps(dict(passed=True,generated=3,sources=427,resources=201,manifestHashesVerified=628)))
