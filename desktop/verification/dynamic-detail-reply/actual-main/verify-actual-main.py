"""Run the frozen reply/detail fixtures using only newly built Main product classes."""
from pathlib import Path
import concurrent.futures
import hashlib
import importlib.util
import json
import os
import subprocess
import sys
import tempfile
import urllib.parse
import zipfile

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
LANE = REPO / 'desktop/.local/dynamic-detail-reply-parity'
BASE = HERE / 'main-product-snapshot-01'
OUT = HERE / 'protocol-session-proof03'
assert not OUT.exists()
BASE.mkdir(exist_ok=True); OUT.mkdir()


def ext(path):
    value = str(Path(path).absolute())
    prefix = chr(92) * 2 + '?' + chr(92)
    return Path(value if value.startswith(prefix) else prefix + value)


def sha(path):
    return hashlib.sha256(ext(path).read_bytes()).hexdigest()


def save(path, value):
    ext(path).write_text(json.dumps(value, indent=2) + '\n', encoding='utf-8', newline='\n')


def argsfile(path, values):
    ext(path).write_text('\n'.join('"' + str(value).replace('\\', '/') + '"' for value in values) + '\n', encoding='utf-8', newline='\n')


assert 'BUILD SUCCESSFUL' in (HERE / 'gradle-main.log').read_text(encoding='utf-8')
assert json.loads((REPO / 'desktop/upstream-sources.json').read_bytes())['sources'].__len__() == 602
for name in ['dynamic-editor-protocol/verification.json', 'dynamic-detail-reply-verification.json']:
    assert json.loads((REPO / 'desktop/build/generated' / name).read_bytes())['passed']

artifacts, replacements = [], {}
for name, source in [('main-kotlin', 'desktop/build/classes/kotlin/main'), ('main-java', 'desktop/build/classes/java/main'), ('main-resources', 'desktop/build/resources/main')]:
    base, target = REPO / source, BASE / (name + '.jar')
    count = 0
    with zipfile.ZipFile(ext(target), 'w', zipfile.ZIP_DEFLATED) as jar:
        for directory, directories, filenames in os.walk(ext(base)):
            directories.sort()
            for filename in sorted(filenames):
                path = Path(directory) / filename
                entry = zipfile.ZipInfo(path.relative_to(ext(base)).as_posix(), (1980, 1, 1, 0, 0, 0))
                entry.compress_type = zipfile.ZIP_DEFLATED
                jar.writestr(entry, ext(path).read_bytes())
                count += 1
    row = dict(path=str(target), source=source, entries=count, sha256Bytes=sha(target))
    artifacts.append(row); replacements[str(base.resolve()).casefold()] = row
runtime_path = REPO / 'desktop/.local/native-share-main-runtime-paths.json'
rows = [replacements.get(str(Path(path).resolve()).casefold()) or dict(path=path, sha256Bytes=sha(path))
        for path in json.loads(runtime_path.read_bytes())['entries']]
assert len(rows) == 89
save(BASE / 'ordered-runtime-cp.json', rows)
sources = {'desktop/build.gradle.kts', 'desktop/upstream-sources.json'}
for root in ['desktop/src/main/kotlin', 'desktop/src/main/java', 'desktop/tools']:
    for directory, directories, filenames in os.walk(ext(REPO / root)):
        directories[:] = sorted(name for name in directories if name != '__pycache__')
        for name in sorted(filenames):
            if Path(name).suffix in {'.kt', '.java', '.py'}:
                sources.add((Path(directory) / name).relative_to(ext(REPO)).as_posix())
source_rows = [dict(path=path, sha256Lf=hashlib.sha256(ext(REPO / path).read_bytes().replace(b'\r\n', b'\n')).hexdigest()) for path in sorted(sources)]
generated = []
for directory, directories, filenames in os.walk(ext(REPO / 'desktop/build/generated')):
    directories.sort()
    for name in sorted(filenames):
        if Path(name).suffix in {'.kt', '.java'}:
            path = Path(directory) / name
            generated.append(dict(path=path.relative_to(ext(REPO)).as_posix(), sha256Bytes=sha(path)))
save(BASE / 'manifest.json', dict(frozen=True, phase='original-detail-reply-protocol-session-actual-main',
    artifacts=artifacts, sourceFiles=source_rows, generatedProductFiles=generated,
    actualRuntimeClasspath=dict(entries=89, externalEntries=86, orderedIdentitiesSha256Bytes=sha(BASE / 'ordered-runtime-cp.json')),
    compilePassed=True, mainConsumerIntegrated=False, fullDetailParity=False, packaged=False))


def verify_main():
    for row in rows:
        assert sha(row['path']) == row['sha256Bytes'], row['path']
    for row in source_rows:
        assert hashlib.sha256(ext(REPO / row['path']).read_bytes().replace(b'\r\n', b'\n')).hexdigest() == row['sha256Lf'], row['path']
    for row in generated:
        assert sha(REPO / row['path']) == row['sha256Bytes'], row['path']


spec = importlib.util.spec_from_file_location('actual_main_reply_compiler', HERE.parent / 'source9-appearance/compile-miuix.py')
compiler = importlib.util.module_from_spec(spec); spec.loader.exec_module(compiler)
editor_deps = json.loads((REPO / 'desktop/.local/dynamic-editor-detail-parity/final-production-safe/dependency-identities.json').read_bytes())
assert len(editor_deps) == 90
test_support = editor_deps[89]
assert Path(test_support['path']).name.startswith('kotlin-test-') and sha(test_support['path']) == test_support['sha256Bytes']
fixture_inputs = [
    ('session', ['ReplySessionFixture.kt', 'ReplyLifecycleFixture.kt']),
    ('reply-protocol', ['protocol/ReplyProtocolTransportFixture.kt', 'protocol/RawReplyParserFixture.kt']),
    ('detail-protocol', ['protocol/detail-prepared/DynamicDetailProtocolFixture.kt']),
]
cp = ';'.join([row['path'] for row in rows] + [test_support['path']])
base_classes = set()
for row in rows:
    with zipfile.ZipFile(ext(row['path'])) as jar:
        base_classes.update(name for name in jar.namelist() if name.endswith('.class'))
handoff = json.loads((LANE / 'raw-handoff-final/frozen-handoff.json').read_bytes())
handoff_rows = {row['path']: row for row in handoff['artifacts']}
for manifest_path, pinned_hash in [
    ('protocol/frozen-manifest.json', '7823571e997d514383efbf2faf7244b42853f47afd2bcd82149b466c9d8f9d18'),
    ('protocol/detail-prepared/frozen-manifest.json', 'c885806ab7b20245868ccca190812663afd35fcc9803469ee340512c773a33fb'),
]:
    assert sha(LANE / manifest_path) == pinned_hash
    child = json.loads((LANE / manifest_path).read_bytes())
    for row in child['frozenArtifacts']:
        full_path = str(Path(manifest_path).parent / row['path']).replace('\\', '/')
        handoff_rows[full_path] = row


def compile_fixture(job):
    name, paths = job
    frozen = []
    for path in paths:
        row = handoff_rows[path]
        assert sha(LANE / path) == row['sha256Bytes']
        target = OUT / Path(path).name
        target.write_bytes(ext(LANE / path).read_bytes())
        frozen.append(target)
    jar = OUT / (name + '-fixture-only.jar')
    values = ['-Dfile.encoding=UTF-8', '-Xmx2g', '-cp', ';'.join(map(str, compiler.COMPILER)),
        'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-jvm-target', '21', '-cp', cp,
        '-Xfriend-paths=' + str(BASE / 'main-kotlin.jar'), '-Xplugin=' + str(compiler.PLUGIN),
        '-module-name', 'com_bilipai_desktop_bilipai_windows', '-d', str(jar), *map(str, frozen)]
    args = OUT / (name + '-compile.args'); argsfile(args, values)
    result = subprocess.run([str(compiler.JAVA), '@' + str(args)], capture_output=True, encoding='utf-8', errors='replace', timeout=150)
    (OUT / (name + '-compile.log')).write_text(result.stdout + result.stderr, encoding='utf-8')
    result.check_returncode()
    with zipfile.ZipFile(ext(jar)) as z:
        emitted = {entry for entry in z.namelist() if entry.endswith('.class')}
        assert emitted and not (emitted & base_classes), f'Product override in {name}'
    return dict(name=name, path=str(jar), sha256Bytes=sha(jar), emittedClasses=len(emitted), productOverrides=0,
        frozenSources=[dict(path=str(path), sha256Bytes=sha(path)) for path in frozen])


verify_main()
with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
    fixture_jars = list(pool.map(compile_fixture, fixture_inputs))
save(OUT / 'fixture-inputs.json', dict(actualMainSnapshotSha256Bytes=sha(BASE / 'manifest.json'),
    actualMainRuntime=rows, testOnlySupport=test_support, fixtures=fixture_jars, productOverrides=0,
    originalFixtureMetadataRetained=True, mainConsumerIntegrated=False))
jobs = [
    ('session', 'com.bilipai.desktop.ui.ReplySessionFixtureKt', 'com.android.purebilibili.feature.dynamic.DesktopOriginalDynamicReplySession'),
    ('session', 'com.bilipai.desktop.ui.ReplyLifecycleFixtureKt', 'com.android.purebilibili.feature.dynamic.DesktopOriginalDynamicReplySession'),
    ('reply-protocol', 'com.bilipai.desktop.data.ReplyProtocolTransportFixtureKt', 'com.android.purebilibili.data.repository.DesktopDynamicCommentProtocol'),
    ('detail-protocol', 'com.bilipai.desktop.data.DynamicDetailProtocolFixtureKt', 'com.android.purebilibili.data.repository.DesktopDynamicDetailProtocol'),
]


def run_fixture(job):
    fixture_name, main, product_class = job
    name = main.rsplit('.', 1)[-1]
    private = Path(tempfile.mkdtemp(prefix='bp-detail-reply-main-'))
    env = os.environ.copy()
    for key in ['APPDATA', 'LOCALAPPDATA', 'USERPROFILE', 'TEMP', 'TMP']:
        target = private / key.lower(); target.mkdir(); env[key] = str(target)
    env.pop('JAVA_TOOL_OPTIONS', None)
    jar = next(row for row in fixture_jars if row['name'] == fixture_name)
    result_path = OUT / (name + '-result.json')
    class_log = OUT / (name + '-loaded-classes.log')
    values = ['-Dfile.encoding=UTF-8', '-Djava.awt.headless=true', '-Duser.home=' + str(private),
        '-Djava.io.tmpdir=' + str(private), '-Xlog:class+load=info:file=' + str(class_log).replace('\\', '/'),
        '-cp', jar['path'] + ';' + cp, main, str(result_path)]
    args = OUT / (name + '-run.args'); argsfile(args, values)
    result = subprocess.run([str(compiler.JAVA), '@' + str(args)], env=env, capture_output=True, encoding='utf-8', errors='replace', timeout=90)
    (OUT / (name + '-run.log')).write_text(result.stdout + result.stderr, encoding='utf-8')
    result.check_returncode()
    evidence = json.loads(result_path.read_bytes()); assert evidence['passed']
    loaded = class_log.read_text(encoding='utf-8')
    actual_source = (BASE / 'main-kotlin.jar').as_uri()
    candidates = [line.split(' source: ', 1)[1] for line in loaded.splitlines() if product_class + ' source: ' in line]
    expected_path = urllib.parse.unquote(urllib.parse.urlparse(actual_source).path).casefold()
    assert any(urllib.parse.unquote(urllib.parse.urlparse(source).path).casefold() == expected_path for source in candidates), product_class
    groups = evidence.get('groups', evidence.get('cases'))
    return dict(fixture=main, passed=True, groups=len(groups), resultSha256Bytes=sha(result_path),
        classLoadingLogSha256Bytes=sha(class_log), actualMainLoadedProductClass=product_class,
        actualMainLoadedProductSource=actual_source, taskPrivateStore=True, realAccount=False,
        productOverrides=0, originalFixtureMainIntegrationMetadata=evidence.get('MainIntegration'))


with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
    results = list(pool.map(run_fixture, jobs))
verify_main()
save(OUT / 'accepted-evidence.json', dict(passed=True, protocolAndSessionMainIntegrated=True,
    actualMainSnapshotSha256Bytes=sha(BASE / 'manifest.json'), orderedCpSha256Bytes=sha(BASE / 'ordered-runtime-cp.json'),
    groupCount=sum(row['groups'] for row in results), fixtures=results, productOverrides=0,
    mainConsumerIntegrated=False, mainWindowExecuted=False, fullDetailParity=False,
    actualSockets=False, realAccount=False, nativePickerOrClipboard=False, packaged=False))
print('PASS actual Main reply/detail/session fixtures:', sum(row['groups'] for row in results), 'groups; zero product overrides.', flush=True)
