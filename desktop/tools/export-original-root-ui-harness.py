"""Export fresh existing UI fixture classes, test-only libraries and byte ledgers.

No JVM is launched. The caller must be the normal successful source qualification
and seal this manifest. A later verifier must also bind the production candidate
and actual JDK before any isolated UI acceptance can run.
"""
from pathlib import Path
import hashlib, json, math, os, re, subprocess, time, zipfile
import original_root_ui_class_closure as classes
import original_root_ui_candidate_classpath as jars

MAX_SOURCE = 2 * 1024 * 1024
MAX_SOURCE_TOTAL = 32 * 1024 * 1024
MAX_LEDGER_BYTES = 4 * 1024 * 1024 * 1024
MAX_PAYLOAD = 128 * 1024 * 1024
MAX_SOURCE_FILES = 4096
REPOSITORY = 'tonysuper666-creator/BiliPai-Windows'


def require(condition, message):
    if not condition: raise ValueError(message)


def git(repo, *args, data=None):
    return subprocess.run(['git', '-c', 'core.autocrlf=false', '-C', str(repo), *args],
                          input=data, capture_output=True, check=True).stdout


def source_path(repo, name):
    require(isinstance(name, str) and '\\' not in name and ':' not in name and
            not any(part in ('', '.', '..') for part in name.split('/')) and
            not any(character in name for character in '\x00\r\n\t'), 'Invalid source-model path')
    return classes.checked_path(repo / name)


def artifact_difference(model):
    """Shared runtime artifacts must stay byte-identical in the test graph."""
    def index(rows):
        require(isinstance(rows, list) and len(rows) <= 512, 'Artifact inventory exceeds bound')
        result = {}
        for row in rows:
            for key in ('group', 'name', 'version', 'classifier', 'type', 'file', 'sha256'):
                require(isinstance(row.get(key), str), 'Malformed artifact identity')
            require(row['type'] == 'jar' and re.fullmatch('[0-9a-f]{64}', row['sha256']), 'Unbound artifact format')
            require(type(row.get('bytes')) is int and 0 < row['bytes'] <= jars.MAX_JAR, 'Invalid artifact byte bound')
            identity = row['group'], row['name'], row['classifier'], row['type']
            require(identity not in result, 'Duplicate runtime artifact identity')
            result[identity] = row
        return result
    main, test = index(model['mainArtifacts']), index(model['testArtifacts'])
    require(main.keys() <= test.keys(), 'Test graph removed a production runtime artifact')
    for key, row in main.items():
        other = test[key]
        require((row['version'], row['sha256'], row['bytes']) ==
                (other['version'], other['sha256'], other['bytes']),
                'Test graph replaced a production runtime artifact')
    return [test[key] for key in sorted(test.keys() - main.keys())]


def test_source_snapshot(repo, rows):
    require(isinstance(rows, list) and 0 < len(rows) <= MAX_SOURCE_FILES, 'Test source inventory exceeds bound')
    result, total = {}, 0
    for row in rows:
        name = row['path']
        require(name not in result and row['normalization'] == 'lf', 'Duplicate or unnormalized test source')
        require(name.startswith(('desktop/src/test/kotlin/', 'desktop/src/test/java/', 'desktop/build/generated/')),
                'Unexpected actual test input root')
        path = source_path(repo, name)
        raw = jars.bounded_bytes(path, MAX_SOURCE).replace(b'\r\n', b'\n') if path.stat().st_size else b''
        total += len(raw)
        require(total <= MAX_SOURCE_TOTAL and len(raw) == row['bytesLf'] and
                hashlib.sha256(raw).hexdigest() == row['sha256'], 'Actual test source snapshot changed')
        result[name] = dict(row)
    # Authenticate every canonical test source against HEAD in one bounded batch.
    tree = git(repo, 'ls-tree', '-r', '-l', '-z', 'HEAD', '--', 'desktop/src/test/kotlin', 'desktop/src/test/java')
    canonical, size = [], 0
    for entry in tree.split(b'\x00'):
        if not entry: continue
        header, name = entry.split(b'\t', 1)
        name = name.decode('utf-8')
        if not name.endswith(('.kt', '.java')): continue
        mode, kind, blob, length = header.split()
        require(mode in (b'100644', b'100755') and kind == b'blob', 'Linked canonical test source')
        size += int(length)
        require(int(length) <= MAX_SOURCE and size <= MAX_SOURCE_TOTAL, 'Canonical source batch exceeds bound')
        canonical.append((name, blob.decode(), int(length)))
    require({name for name, _, _ in canonical} == {name for name in result if name.startswith('desktop/src/test/')},
            'Canonical test source inventory differs from HEAD')
    batch = git(repo, 'cat-file', '--batch', data=''.join(blob + '\n' for _, blob, _ in canonical).encode())
    offset = 0
    for name, blob, length in canonical:
        end = batch.index(b'\n', offset)
        require(batch[offset:end].decode() == f'{blob} blob {length}', 'Canonical source blob mismatch')
        offset = end + 1
        raw = batch[offset:offset + length].replace(b'\r\n', b'\n')
        offset += length + 1
        require(hashlib.sha256(raw).hexdigest() == result[name]['sha256'], 'Canonical source differs from HEAD')
    require(offset == len(batch), 'Canonical source batch has trailing bytes')
    return [result[name] for name in sorted(result)]


def output_roots(repo, model, key, kind):
    require(isinstance(model[key], list) and 0 < len(model[key]) <= 2, 'Invalid compiler output roots')
    allowed = [(repo / f'desktop/build/classes/{language}/{kind}').resolve() for language in ('kotlin', 'java')]
    result = []
    for name in model[key]:
        path = classes.absolute_path(name)
        if path.exists():
            path = classes.checked_path(path).resolve()
        else:
            path = path.resolve()
        require(path in allowed, 'Compiler output escaped the actual project roots')
        if path.exists(): result.append(classes.checked_path(path))
    require(result and len(result) == len(set(result)), 'Empty or duplicate compiler output roots')
    return result


def runtime_paths(repo, model):
    """Bind every model role to the real source-set outputs and resolved graph."""
    rows = model['classpath']
    require(isinstance(rows, list) and 0 < len(rows) <= 512, 'Invalid ordered runtime model')
    paths = [classes.absolute_path(row['path']).resolve() for row in rows]
    require(len(paths) == len(set(paths)), 'Duplicate actual runtime path')
    outputs = {}
    for key, role in (('mainClassRoots', 'mainClasses'), ('testClassRoots', 'testClasses')):
        for name in model[key]:
            path = classes.absolute_path(name).resolve()
            require(path not in outputs, 'Duplicate declared compiler output')
            outputs[path] = role
    for kind in ('main', 'test'):
        outputs[(repo / f'desktop/build/resources/{kind}').resolve()] = kind + 'Resources'
    actual_outputs, libraries = set(), {'mainLibrary': {}, 'testLibrary': {}}
    for row, path in zip(rows, paths):
        role, kind = row['role'], row['kind']
        require(role in ('mainClasses', 'testClasses', 'mainResources', 'testResources', 'mainLibrary', 'testLibrary')
                and kind in ('file', 'directory', 'missing'), 'Unknown actual runtime-model role')
        actual = 'file' if path.is_file() else 'directory' if path.is_dir() else 'missing'
        require(kind == actual, 'Runtime output kind changed')
        if actual != 'missing': classes.checked_path(path)
        if role in libraries:
            require(kind == 'file' and path.suffix == '.jar' and path not in outputs,
                    'Missing or unsupported runtime library output')
            measured = jars.file_digest(path)
            require(measured == dict(bytes=row['bytes'], sha256=row['sha256']),
                    'Actual ordered runtime library changed')
            libraries[role][path] = measured
        else:
            require(outputs.get(path) == role and kind != 'file', 'Runtime output role escaped its actual source set')
            actual_outputs.add(path)
    require(actual_outputs == set(outputs), 'Actual runtime omitted a declared source-set output')
    main_paths = model['mainRuntimeClasspath']
    require(isinstance(main_paths, list) and 0 < len(main_paths) <= 512, 'Invalid production runtime model')
    main_paths = [classes.absolute_path(name).resolve() for name in main_paths]
    require(len(main_paths) == len(set(main_paths)) and set(main_paths) ==
            {path for row, path in zip(rows, paths) if row['role'].startswith('main')},
            'Actual production runtime differs from the shared test graph')
    for key, role in (('mainArtifacts', 'mainLibrary'), ('testArtifacts', None)):
        artifacts = model[key]
        artifact_paths = [classes.absolute_path(row['file']).resolve() for row in artifacts]
        expected = libraries[role] if role else libraries['mainLibrary'] | libraries['testLibrary']
        require(len(artifact_paths) == len(set(artifact_paths)) and set(artifact_paths) == set(expected),
                'Resolved artifact inventory differs from the actual runtime paths')
        for row, path in zip(artifacts, artifact_paths):
            require(dict(bytes=row['bytes'], sha256=row['sha256']) == expected[path],
                    'Resolved artifact differs from its actual runtime bytes')
    return rows


def file_ledger(root, compiled_after=None, maximum_files=100000):
    root = classes.checked_path(root)
    require(root.is_dir(), 'Ledger root is not a directory')
    result, total = {}, 0
    for folder, directories, files in os.walk(root, followlinks=False):
        for name in directories: classes.checked_path(Path(folder) / name)
        for name in sorted(files):
            path = classes.checked_path(Path(folder) / name)
            relative = path.relative_to(root).as_posix()
            require(len(result) < maximum_files, 'Ledger file count exceeds bound')
            if compiled_after is not None and relative.endswith('.class'):
                require(path.stat().st_mtime >= compiled_after - 2, 'Stale production class')
            row = jars.file_digest(path, maximum=512 * 1024 * 1024) if path.stat().st_size else dict(bytes=0, sha256=hashlib.sha256(b'').hexdigest())
            total += row['bytes']
            require(total <= MAX_LEDGER_BYTES, 'Production ledger exceeds its byte bound')
            result[relative] = row
    return dict(sorted(result.items()))


def write_json(path, value):
    raw = (json.dumps(value, ensure_ascii=True, indent=2) + '\n').encode()
    require(len(raw) <= 32 * 1024 * 1024, 'Exported JSON exceeds bound')
    with path.open('xb') as stream: stream.write(raw)


def test_resource_snapshot(repo, classpath, test_roots, production, main_resources):
    result = {}
    for row in classpath:
        if row['role'] == 'testResources' and row['kind'] != 'missing':
            path = classes.checked_path(row['path'])
            require(path.resolve() == (repo / 'desktop/build/resources/test').resolve(), 'Test resource output escaped')
            result = {name: (path / name, value) for name, value in file_ledger(path).items()}
    for root in test_roots:
        for name, value in file_ledger(root).items():
            if name.endswith('.class'): continue
            require(re.fullmatch(r'META-INF/[^/]+\.kotlin_module', name) is not None,
                    'Unexpected compiled test metadata')
            require(name not in result, 'Duplicate test metadata/resource entry')
            result[name] = (root / name, value)
    require(not (set(result) & (set(production) | set(main_resources))) and
            not any(name.endswith('.class') for name in result), 'Test resources shadow production output')
    require(sum(value['bytes'] for _, value in result.values()) <= 16 * 1024 * 1024,
            'Test resources exceed their export bound')
    return result


def export(repo, model, destination, expected_source, compiled_after):
    repo = classes.checked_path(repo).resolve()
    require(re.fullmatch('[0-9a-f]{40}', expected_source) is not None, 'Exact source is required')
    require(math.isfinite(compiled_after) and time.time() - 3600 <= compiled_after <= time.time() + 2,
            'Fresh compilation boundary is required')
    require(git(repo, 'rev-parse', 'HEAD').decode().strip() == expected_source == model['sourceSha'], 'UI export source changed')
    plan_path = source_path(repo, '.github/windows-source-validation-plan.json')
    plan_raw = jars.bounded_bytes(plan_path, 4 * 1024 * 1024)
    plan = json.loads(plan_raw)
    require(plan['repository'] == REPOSITORY and plan.get('exportOriginalRootUiHarness') is True,
            'Source cohort did not opt into UI export')
    require(hashlib.sha256(plan_raw).hexdigest() == model['planSha256'], 'UI model plan differs')
    require(model['schema'] == 1 and model['task'] == 'windowsVideoLocalReplayUiSmoke' and
            model['mainClass'] == classes.ENTRY.replace('/', '.'), 'Unexpected original UI entry')
    require(all(model.get(key) is False for key in ('javaExecExecuted', 'applicationExecuted', 'hardwareExecuted')),
            'UI input model cannot claim runtime acceptance')
    build = source_path(repo, 'desktop/build.gradle.kts')
    require(jars.file_digest(build)['sha256'] == model['compiler']['buildScriptSha256'] and
            model['compiler']['declaredKotlinJvmPlugin'] == '2.4.0', 'Compiler model differs from the real build')
    java = classes.checked_path(model['jdk']['executable'])
    release = java.parent.parent / 'release'
    require(jars.file_digest(release)['sha256'] == model['jdk']['releaseSha256'] and
            b'JAVA_VERSION="21.0.12.1"' in jars.bounded_bytes(release, 65536), 'Pinned compiler JDK differs')
    canonical_sources = test_source_snapshot(repo, model['testSources'])
    main_roots = output_roots(repo, model, 'mainClassRoots', 'main')
    test_roots = output_roots(repo, model, 'testClassRoots', 'test')
    production = {}
    for root in main_roots:
        for name, row in file_ledger(root, compiled_after).items():
            require(name not in production, 'Duplicate production output entry')
            production[name] = row
    require(jars.MAIN in production, 'Actual production Main class is absent')
    index = classes.index_classes(test_roots)
    selected = classes.select_closure(index, [name[:-6] for name in production if name.endswith('.class')])
    require(sum(item.size for item in selected.values()) <= 32 * 1024 * 1024, 'UI fixture exceeds class byte bound')
    require(all(item.path.stat().st_mtime >= compiled_after - 2 for item in selected.values()), 'Stale UI fixture class')
    classpath = runtime_paths(repo, model)
    resources = classes.checked_path(model['systemProperties']['compose.application.resources.dir'])
    require(resources.resolve() == (repo / 'desktop/resources/common').resolve(), 'Actual application resources changed')
    app_ledger = file_ledger(resources)
    main_resources = {}
    for row in classpath:
        if row['role'] == 'mainResources' and row['kind'] != 'missing':
            path = classes.checked_path(row['path'])
            require(path.resolve() == (repo / 'desktop/build/resources/main').resolve(), 'Main resource output escaped')
            main_resources = file_ledger(path)
    test_resources = test_resource_snapshot(repo, classpath, test_roots, production, main_resources)
    require('windows-update.json' in main_resources, 'Actual production version resource is absent')
    version = json.loads(jars.bounded_bytes(repo / 'desktop/build/resources/main/windows-update.json', 65536))
    require(version['version'] == plan['windowsVersion'], 'Actual production Windows version differs')
    extra = artifact_difference(model)
    require(len(extra) <= 32, 'Test-only dependency count exceeds bound')
    test_library_paths = {classes.absolute_path(row['path']) for row in classpath if row['role'] == 'testLibrary'}
    require(test_library_paths == {classes.absolute_path(row['file']) for row in extra}, 'Actual test-only classpath differs from resolved graph')
    cache = classes.absolute_path(model['gradleUserHome']).resolve() / 'caches/modules-2/files-2.1'
    verified_dependencies = []
    for row in extra:
        path = classes.checked_path(row['file']).resolve()
        require(path.is_relative_to(cache) and path.suffix == '.jar', 'Test dependency escaped the actual Gradle cache')
        measured = jars.file_digest(path, maximum=16 * 1024 * 1024)
        require(measured == dict(bytes=row['bytes'], sha256=row['sha256']), 'Actual test dependency changed')
        verified_dependencies.append((path, row))
    destination = classes.absolute_path(destination)
    require(not destination.exists() and not destination.is_relative_to(repo) and
            not destination.is_relative_to(cache), 'UI export destination must be new and outside source/cache')
    classes.checked_path(destination.parent)
    destination.mkdir()
    classes_rows = []
    resource_rows = []
    with zipfile.ZipFile(destination / 'ui-fixture.jar', 'x', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name, item in selected.items():
            raw = classes.bounded_class_bytes(item.path, item.size)
            require(hashlib.sha256(raw).hexdigest() == item.sha256, 'UI class changed during export')
            info = zipfile.ZipInfo(name + '.class', (1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, raw)
            classes_rows.append(dict(path=name + '.class', bytes=item.size, sha256=item.sha256, sourceFileLabel=item.symbols.source))
        for name, (path, expected) in sorted(test_resources.items()):
            raw = jars.bounded_bytes(path, 16 * 1024 * 1024) if expected['bytes'] else b''
            require(len(raw) == expected['bytes'] and hashlib.sha256(raw).hexdigest() == expected['sha256'],
                    'Actual test metadata/resource changed')
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, raw)
            resource_rows.append(dict(path=name, **expected))
    (destination / 'lib').mkdir()
    dependencies = []
    for number, (path, row) in enumerate(verified_dependencies):
        require(re.fullmatch('[A-Za-z0-9_.+-]+', row['name']) is not None, 'Invalid dependency name')
        relative = 'lib/' + str(number).zfill(2) + '-' + row['name'] + '.jar'
        with jars.jar_snapshot(path) as (snapshot, measured):
            require(measured == dict(bytes=row['bytes'], sha256=row['sha256']), 'Dependency changed before export')
            with (destination / relative).open('xb') as output:
                remaining = measured['bytes']
                while remaining:
                    block = snapshot.read(min(1024 * 1024, remaining))
                    require(bool(block), 'Truncated dependency snapshot')
                    output.write(block)
                    remaining -= len(block)
        dependencies.append({key: row[key] for key in ('group', 'name', 'version', 'classifier', 'type', 'sha256')} | dict(path=relative, bytes=row['bytes']))
    write_json(destination / 'runtime-model.json', model)
    write_json(destination / 'production-ledger.json', dict(mainOutput=production, mainResources=main_resources, applicationResources=app_ledger))
    write_json(destination / 'test-sources.json', canonical_sources)
    payload = []
    for path in sorted(destination.rglob('*')):
        if path.is_file(): payload.append(dict(path=path.relative_to(destination).as_posix(), **jars.file_digest(path)))
    require(sum(row['bytes'] for row in payload) <= MAX_PAYLOAD, 'UI export payload exceeds total bound')
    classes.verify_unchanged(selected)
    production_after = {}
    for root in main_roots:
        for name, row in file_ledger(root, compiled_after).items():
            require(name not in production_after, 'Duplicate production output entry')
            production_after[name] = row
    require(production_after == production and file_ledger(resources) == app_ledger and
            file_ledger(repo / 'desktop/build/resources/main') == main_resources,
            'Actual production output/resources changed during export')
    runtime_paths(repo, model)
    require(test_resource_snapshot(repo, classpath, test_roots, production, main_resources) == test_resources,
            'Test metadata/resources changed during export')
    require(test_source_snapshot(repo, model['testSources']) == canonical_sources and
            git(repo, 'rev-parse', 'HEAD').decode().strip() == expected_source and
            jars.bounded_bytes(plan_path, 4 * 1024 * 1024) == plan_raw, 'Source graph changed during export')
    manifest = dict(schema=1, sourceSha=expected_source, planSha256=model['planSha256'],
        windowsVersion=plan['windowsVersion'], mainClass=model['mainClass'], classes=classes_rows,
        resources=resource_rows, dependencies=dependencies, payload=payload, compiledAfterEpoch=compiled_after,
        jdk=model['jdk'], compiler=model['compiler'],
        productionRuntimeBindingRequired=True, productionClassesExported=False,
        applicationExecuted=False, hardwareExecuted=False, releaseQualified=False)
    write_json(destination / 'manifest.json', manifest)
    return manifest
