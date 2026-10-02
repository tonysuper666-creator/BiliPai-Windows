from pathlib import Path
import collections, hashlib, json, zipfile

HERE = Path(__file__).resolve().parent
RUN = HERE / 'attempt02'
REPO = HERE.parents[3] / 'BiliPai-v023'
APP = REPO / 'desktop/build/compose/binaries/main/app/BiliPai Windows/app'
SNAPSHOT = HERE.parent / 'stable-product-snapshot-83'

def wide(p):
    s = str(Path(p).absolute())
    return Path(s if s.startswith('\\\\?\\') else '\\\\?\\' + s)

def sha(p):
    h = hashlib.sha256()
    with wide(p).open('rb') as f:
        while data := f.read(1024 * 1024):
            h.update(data)
    return h.hexdigest()

def read(p):
    return json.loads(wide(p).read_bytes())

def entry_digests(p):
    with zipfile.ZipFile(wide(p)) as z:
        entries = [item for item in z.infolist() if not item.is_dir()]
        assert len({item.filename for item in entries}) == len(entries), str(p)
        return {item.filename: hashlib.sha256(z.read(item)).hexdigest() for item in entries}

manifest = read(SNAPSHOT / 'manifest.json')
assert sha(SNAPSHOT / 'manifest.json') == '72934edb925c7793294bf504388dfbd1f849fe14d8ea00dd4f333befdabd4157'
ordered_path = SNAPSHOT / 'ordered-runtime-cp.json'
assert sha(ordered_path) == 'f82828347f7cfad86cd101dfb13d44677ba88f780b21a9e21df8c06a97d4828b'
ordered = read(ordered_path)
assert len(ordered) == 101
for item in ordered:
    assert sha(item['path']) == item['sha256Bytes'], item['path']
config = wide(APP / 'BiliPai Windows.cfg').read_text(encoding='utf-8')
cp = [line.partition('=')[2] for line in config.splitlines() if line.startswith('app.classpath=')]
assert all(line.startswith('$APPDIR\\') for line in cp)
paths = [APP / line.removeprefix('$APPDIR\\').replace('\\', '/') for line in cp]
assert all(wide(p).is_file() for p in paths)
assert len({str(p).casefold() for p in paths}) == len(paths)
product = [p for p in paths if p.name.startswith('bilipai-windows')]
resource = [p for p in paths if p.is_relative_to(APP / 'resources')]
runtime = [p for p in paths if p not in product + resource]
assert len(product) == 1
runtime_rows = [{'path': p.relative_to(APP).as_posix(), 'sha256Bytes': sha(p)} for p in runtime]
expected_dependency_rows = ordered[3:]
assert len(runtime) == len(expected_dependency_rows)
expected_digests = {item['sha256Bytes'] for item in expected_dependency_rows}
packaged_digests = {item['sha256Bytes'] for item in runtime_rows}
missing = [item for item in expected_dependency_rows if item['sha256Bytes'] not in packaged_digests]
extra = [item for item in runtime_rows if item['sha256Bytes'] not in expected_digests]
assert len(missing) == len(extra) == 1
assert Path(missing[0]['path']).name == 'skiko-awt-runtime-windows-x64-0.150.1.jar'
assert Path(extra[0]['path']).name.startswith('skiko-awt-runtime-windows-x64-0.150.1-')
original_skiko_entries = entry_digests(missing[0]['path'])
packaged_skiko_entries = entry_digests(APP / extra[0]['path'])
unpacked_names = sorted(set(original_skiko_entries) - set(packaged_skiko_entries))
assert set(unpacked_names) == {'icudtl.dat', 'skiko-windows-x64.dll', 'skiko-windows-x64.dll.sha256'}
assert all(original_skiko_entries.get(name) == digest for name, digest in packaged_skiko_entries.items())
unpacked_rows = []
for name in unpacked_names:
    assert sha(APP / name) == original_skiko_entries[name], name
    unpacked_rows.append({'path': name, 'sha256Bytes': sha(APP / name)})
skiko_transform = {'sourceJar': missing[0], 'packagedJar': extra[0],
                   'remainingEntriesByteEqual': True, 'extractedNativeEntriesByteEqual': True,
                   'extractedNativeEntries': unpacked_rows,
                   'producer': 'unpackDefaultComposeDesktopJvmApplicationResources'}

expected_product = {}
for item in ordered[:3]:
    entries = entry_digests(item['path'])
    for name, digest in entries.items():
        if name in expected_product:
            assert expected_product[name] == digest, name
        expected_product[name] = digest
product_entries = entry_digests(product[0])
assert all(product_entries.get(name) == digest for name, digest in expected_product.items())
extras = sorted(set(product_entries) - set(expected_product))
assert set(extras) <= {'META-INF/MANIFEST.MF'}, extras

# Describe existing duplicate classes and any new overlap from bundled resources.
# Product contents and every dependency already match the frozen graph by bytes.
owners = collections.defaultdict(list)
for p in paths:
    with zipfile.ZipFile(wide(p)) as z:
        for item in z.infolist():
            if item.filename.endswith('.class') and not item.filename.endswith('module-info.class'):
                owners[item.filename].append(p)
duplicates = []
resource_set = set(resource)
for name, jars in sorted(owners.items()):
    if len(jars) < 2:
        continue
    digests = []
    for jar in jars:
        with zipfile.ZipFile(wide(jar)) as z:
            digests.append({'jar': jar.relative_to(APP).as_posix(),
                            'sha256Bytes': hashlib.sha256(z.read(name)).hexdigest()})
    duplicates.append({'class': name, 'identicalBytes': len({item['sha256Bytes'] for item in digests}) == 1,
                       'includesBundledResourceJar': any(jar in resource_set for jar in jars), 'owners': digests})

report = {
    'passed': True, 'frozenSnapshotManifestSha256': sha(SNAPSHOT / 'manifest.json'),
    'frozenOrderedRuntimeCPSha256': sha(ordered_path), 'frozenRuntimeEntries': len(ordered),
    'frozenMainInputArtifacts': ordered[:3], 'runtimeDependencyJars': len(runtime),
    'packagedProductJars': len(product), 'bundledResourceJars': len(resource),
    'launcherClasspathEntries': len(paths), 'allLauncherFilesExist': True,
    'allRuntimeDependenciesAccountedFor': True, 'directByteMatchedRuntimeDependencyJars': len(runtime) - 1,
    'nativeRuntimeJarTransforms': [skiko_transform], 'packagedRuntimeJars': runtime_rows,
    'packagedProductJar': {'path': product[0].relative_to(APP).as_posix(), 'sha256Bytes': sha(product[0])},
    'frozenMainEntriesCompared': len(expected_product), 'packagedProductEntries': len(product_entries),
    'frozenJavaKotlinAndResourcesMatchByteForByte': True, 'permittedProductMetadataExtras': extras,
    'bundledResourceJarPaths': [p.relative_to(APP).as_posix() for p in resource],
    'explanation': '101 frozen Gradle entries comprise three main artifacts and 98 dependency JARs. Jpackage replaces the three main artifacts with one product JAR and discovers 15 existing bundled resource JARs, producing 114 launcher entries. Product main entries and 97 dependency JARs match frozen actual83 bytes. The remaining Skiko runtime JAR has three native entries moved without byte changes into APPDIR by the existing Compose unpack task; all remaining JAR entries are unchanged.',
    'duplicateClassGroups': len(duplicates),
    'nonIdenticalDuplicateClassGroups': sum(not item['identicalBytes'] for item in duplicates),
    'duplicateClassGroupsIncludingBundledResources': sum(item['includesBundledResourceJar'] for item in duplicates),
    'duplicateClasses': duplicates,
    'initialInspectionFailureWasCountAssumption': True,
    'initialInlineProvenanceDiagnosticUsedUnprefixedLongPaths': True,
    'productionOrPackagingCodeChanged': False,
}
wide(RUN / 'packaged-classpath-audit.json').write_bytes((json.dumps(report, indent=2) + '\n').encode())
print(json.dumps({key: report[key] for key in ['passed', 'frozenRuntimeEntries', 'runtimeDependencyJars',
                 'packagedProductJars', 'bundledResourceJars', 'launcherClasspathEntries',
                 'frozenMainEntriesCompared', 'packagedProductEntries', 'permittedProductMetadataExtras',
                 'duplicateClassGroups', 'nonIdenticalDuplicateClassGroups',
                 'duplicateClassGroupsIncludingBundledResources']}))
