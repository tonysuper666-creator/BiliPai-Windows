from pathlib import Path
import collections
import difflib
import hashlib
import json
import re
import zipfile

HERE = Path(__file__).resolve().parent
MAIN = next(p for p in HERE.parents if (p / '.git').exists())
SIBLING = MAIN.parent / 'BiliPai-v023'
ARCHIVE = MAIN / 'desktop/.local/stable-miuix5c91-source-audit/official/miuix-5c91-5c91d5e5ce1a2fc7e8bdc1258a881c555102bbca.zip'
FORK = SIBLING / 'desktop/third-party/miuix5157'
SNAP = MAIN / 'desktop/.local/stable-product-snapshot-30'
COMMIT = '5c91d5e5ce1a2fc7e8bdc1258a881c555102bbca'


def safe(p):
    s = str(p.resolve())
    return Path(s if s.startswith('\\\\?\\') else '\\\\?\\' + s)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def bytes_(p):
    return safe(p).read_bytes()


def pin(p):
    b = bytes_(p)
    return {'path': str(p), 'sha256Bytes': sha(b), 'bytes': len(b)}


def save(name, value):
    p = safe(HERE / name)
    assert not p.exists(), p
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')


def main():
    assert sha(bytes_(ARCHIVE)) == 'fe87684eed8a9aac494b5405d00b01121273d83462772b56cb055dbb785269dc'
    assert sha(bytes_(SNAP / 'manifest.json')) == 'e69b7b08805fc7a6aaec8557e8ba1cfd2368b701f99ecb94edc672efd22b6b69'
    assert sha(bytes_(SNAP / 'ordered-runtime-cp.json')) == 'dcab5cb81f9c62929c07966f76fe3bfd54ee0e7b93614296c576b24f218071c0'
    cp = json.loads(bytes_(SNAP / 'ordered-runtime-cp.json').decode('utf-8'))
    provenance = json.loads(bytes_(FORK / 'upstream-provenance.json').decode('utf-8'))
    assert provenance['commit'] == COMMIT
    assert not any(x['path'].startswith('miuix-nav/') for x in provenance['files'])
    nav = []
    imports = collections.defaultdict(list)
    aliases = []
    sources = {}
    with zipfile.ZipFile(safe(ARCHIVE)) as archive:
        root = archive.namelist()[0].split('/')[0] + '/'
        names = [n for n in archive.namelist() if n.startswith(root + 'miuix-nav/src/') and n.endswith('.kt') and
                 any('/' + s + '/' in n for s in ('commonMain', 'skikoMain', 'desktopMain'))]
        assert len(names) == 30
        for n in sorted(names):
            relative = n[len(root):]
            raw = archive.read(n)
            s = raw.decode('utf-8')
            storage = relative.replace('/kotlin/top/yukonga/miuix/kmp/', '/kotlin/')
            path = safe(HERE / 'source-inputs/upstream' / storage)
            assert not path.exists(), path
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(raw)
            sources[relative] = s
            rows = []
            for line, text in enumerate(s.splitlines(), 1):
                if text.startswith('import '):
                    value = text[7:]
                    imports[value].append({'path': relative, 'line': line})
                    rows.append(value)
                if re.search(r'\b(expect|actual) fun ', text):
                    aliases.append({'path': relative, 'line': line, 'declaration': text.strip()})
            nav.append({'path': relative, 'storagePath': storage, 'sha256Bytes': sha(raw),
                        'sha256LF': sha(raw.replace(b'\r\n', b'\n')), 'lines': len(s.splitlines()),
                        'sourceSet': relative.split('/')[2], 'imports': rows})
        for n in ('miuix-nav/build.gradle.kts', 'gradle/libs.versions.toml', 'LICENSE'):
            p = safe(HERE / 'source-inputs' / n)
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_bytes(archive.read(root + n))
    common_count = sum(x['sourceSet'] == 'commonMain' for x in nav)
    assert common_count == 28
    declared = [{'path': x['path'], 'storagePath': x['storagePath'], 'sha256': x['sha256Bytes']} for x in nav]
    save('nav-provenance-append-only-rows.json', declared)
    save('source-inventory.json', {'archive': pin(ARCHIVE), 'commit': COMMIT, 'productionFiles': nav,
                                  'counts': {'commonMain': 28, 'skikoMain': 2, 'desktopMain': 0, 'totalKotlin': 30},
                                  'originalExpectedActualPairs': aliases})
    save('external-import-closure.json', [{'import': n, 'callSites': sites} for n, sites in sorted(imports.items())
         if not n.startswith(('kotlin.', 'kotlinx.coroutines.', 'androidx.compose.', 'top.yukonga.'))])
    runtime_dependencies = [x for x in cp if any(q in Path(x['path']).name for q in
        ('navigationevent', 'lifecycle-', 'savedstate-', 'kotlinx-serialization-', 'kotlinx-collections-immutable', 'miuix5157'))]
    current_nav_classes = []
    representative = ('androidx/lifecycle/Lifecycle.class', 'androidx/lifecycle/ViewModel.class',
                      'androidx/lifecycle/ViewModelStoreOwner.class', 'androidx/savedstate/SavedStateRegistryOwner.class',
                      'androidx/navigationevent/NavigationEventDispatcher.class', 'androidx/navigationevent/DirectNavigationEventInput.class',
                      'kotlinx/serialization/KSerializer.class', 'kotlinx/serialization/json/Json.class')
    owners = collections.defaultdict(list)
    for row in cp:
        assert sha(bytes_(Path(row['path']))) == row['sha256Bytes']
        with zipfile.ZipFile(safe(Path(row['path']))) as z:
            names = set(z.namelist())
            current_nav_classes.extend({'path': row['path'], 'class': n} for n in names if n.startswith('top/yukonga/miuix/kmp/nav/') and n.endswith('.class'))
            for name in representative:
                if name in names:
                    owners[name].append({'path': row['path'], 'jarSha256Bytes': row['sha256Bytes'], 'classSha256Bytes': sha(z.read(name))})
    assert not current_nav_classes
    assert all(owners[x] for x in representative)
    save('actual30-runtime-class-presence.json', {'status': 'READ_ONLY_JAR_INVENTORY_NOT_COMPILE',
        'snapshot': pin(SNAP / 'manifest.json'), 'ordered92Classpath': pin(SNAP / 'ordered-runtime-cp.json'),
        'runtimeDependenciesAlreadyPresent': runtime_dependencies, 'representativeRequiredTypes': dict(owners),
        'existingNavPackageClassOverlap': [],
        'limits': 'Class presence does not verify every extension-function descriptor or binary compatibility. No new dependency artifact was downloaded or installed.'})
    current_build = bytes_(FORK / 'build.gradle.kts').decode('utf-8').replace('\r\n', '\n')
    modules = 'val originalModules = listOf("miuix-core", "miuix-shader", "miuix-squircle", "miuix-ui", "miuix-preference", "miuix-blur", "miuix-icons")'
    assert current_build.count(modules) == 1
    candidate_build = current_build.replace(modules, modules[:-1] + ', "miuix-nav")')
    plugin = '    id("org.jetbrains.kotlin.plugin.compose")\n'
    assert candidate_build.count(plugin) == 1
    candidate_build = candidate_build.replace(plugin, plugin + '    kotlin("plugin.serialization")\n')
    anchor = '                implementation("com.materialkolor:material-color-utilities:5.0.1")\n'
    assert candidate_build.count(anchor) == 1
    dependencies = ('                implementation("org.jetbrains.androidx.lifecycle:lifecycle-runtime:2.11.0")\n'
                    '                implementation("org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.11.0")\n'
                    '                implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel:2.11.0")\n'
                    '                implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")\n'
                    '                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")\n')
    candidate_build = candidate_build.replace(anchor, anchor + dependencies)
    patch = ''.join(difflib.unified_diff(current_build.splitlines(True), candidate_build.splitlines(True),
        fromfile='a/desktop/third-party/miuix5157/build.gradle.kts', tofile='b/desktop/third-party/miuix5157/build.gradle.kts'))
    safe(HERE / 'same-project-nav-build-proposal.patch').write_text(patch, encoding='utf-8', newline='\n')
    existing_compile_count = sum(x['path'].endswith('.kt') and any('/src/' + s + '/' in x['path'] for s in provenance['compiledSourceSets']) for x in provenance['files'])
    save('build-and-platform-contract.json', {
        'status': 'SOURCE_ONLY_PROPOSAL_NOT_INSTALLED_COMPILED_OR_RUN',
        'sameProject': ':miuix5157', 'newForkOrArtifact': False,
        'baseBuild': pin(FORK / 'build.gradle.kts'), 'baseBuildSha256LF': sha(current_build.encode('utf-8')),
        'proposedBuildSha256LF': sha(candidate_build.encode('utf-8')),
        'baseProvenance': pin(FORK / 'upstream-provenance.json'), 'verifier': pin(FORK / 'verify-source.py'),
        'sourceCounts': {'existingCanonicalFiles': len(provenance['files']), 'existingCompiledOriginalKotlin': existing_compile_count,
                         'additionalCanonicalOriginalKotlin': 30, 'additionalCompiledOriginalKotlin': 30},
        'recipe': [
            'Append exactly these 30 full original Kotlin sources to the same fork upstream directory and append their exact archive rows to provenance; retain all existing rows and SDF adapter.',
            'Add miuix-nav to originalModules, so its 28 common plus 2 skiko sources compile in the same JVM target; no Android source or new Gradle subproject.',
            'Declare official lifecycle2.11 and serialization1.11 dependencies already present in actual30 runtime; serialization plugin uses the existing root Kotlin version.',
            'Official nav build also declares collections-immutable0.5.1, but none of these 30 Kotlin files imports it; actual30 retains0.4.0. Do not silently upgrade the application collection graph; decide explicitly if reproducing unused upstream dependency declaration.',
            'SavedState1.4 is required by NavEntryViewModel and already present transitively through lifecycle-viewmodel-compose; verify resolved compile graph without relying on the parent app classpath.',
            'Run the existing pinned-source verifier with appended provenance then the same sole project build serially. This review performs neither step.',
        ],
        'platform': [
            {'path': 'miuix-nav/src/skikoMain/kotlin/top/yukonga/miuix/kmp/nav/core/NavSystemCornerRadius.skiko.kt', 'line': 12, 'contract': 'Original actual 0.dp OS corner fallback; not a fabricated Android radius.'},
            {'path': 'miuix-nav/src/skikoMain/kotlin/top/yukonga/miuix/kmp/nav/gesture/WindowNavigationEventBridge.skiko.kt', 'line': 9, 'contract': 'Original actual no-op for Skiko; does not itself install an Escape or separate native-dialog owner.'},
            {'path': 'miuix-nav/src/commonMain/kotlin/top/yukonga/miuix/kmp/nav/gesture/PredictiveBackHandler.kt', 'line': 104, 'contract': 'Without a provided actual NavigationEventDispatcher owner the handler is inert. Real desktop discrete Escape invokes commit; it does not create synthetic OS predictive progress.'},
            {'path': 'miuix-nav/src/commonMain/kotlin/top/yukonga/miuix/kmp/nav/core/NavDisplay.kt', 'line': 259, 'contract': 'Nested dispatcher parents the actual caller dispatcher; original entry lifecycle/store/saveable state and input ordering should remain whole.'},
        ],
        'existingWindowsPattern': pin(SIBLING / 'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopCommentDialogNavigation.kt'),
        'remainingAcceptance': ['Compile complete nav closure in the same fork, not a helper alias.', 'Verify serialized real route keys and per-entry owner retirement/lifecycle/saveable restoration.', 'Bind Root actual window/each real separate modal Escape input and verify original drag/predictive dispatcher semantics; importing the module is not caller acceptance.'],
        'excluded': 'Android/iOS/web actuals, tests, publishing convention plugins and baseline profiles are reference-only and not compiled by this Windows proposal.',
    })
    rows = []
    for p in sorted(HERE.rglob('*')):
        if p.is_file():
            rows.append({'relativePath': p.relative_to(HERE).as_posix(), 'sha256Bytes': sha(bytes_(p)), 'bytes': len(bytes_(p))})
    save('evidence-manifest.json', {'status': 'FROZEN_SOURCE_ONLY_NAV_CLOSURE_REVIEW', 'commit': COMMIT,
        'archive': pin(ARCHIVE), 'artifactCount': len(rows), 'artifactRows': rows, 'runtimeOrCompilerClaim': False,
        'sharedSourceWrites': 0, 'newJars': 0})
    print(json.dumps({'manifest': pin(HERE / 'evidence-manifest.json'), 'artifacts': len(rows), 'productionKotlin': 30}, ensure_ascii=False))


if __name__ == '__main__':
    main()
