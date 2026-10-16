"""Bind a sealed UI export to actual production classes/resources without Java.

This layer authenticates the export and proves classpath/resource byte identity.
It does not bind a JDK/image launcher, authorize execution or prove UI/hardware.
"""
from pathlib import Path
from collections import defaultdict
import hashlib
import importlib.util
import json
import re

import original_root_ui_candidate_classpath as jars

MAX_JSON = 16 * 1024 * 1024
MAX_RESOURCE = 256 * 1024 * 1024
NATIVE_ASSETS = {'skiko-windows-x64.dll', 'skiko-windows-x64.dll.sha256', 'icudtl.dat'}


def require(value, message):
    if not value:
        raise ValueError(message)


def unique(rows):
    result = {}
    for key, value in rows:
        require(key not in result, 'Duplicate binding JSON key')
        result[key] = value
    return result


def relative(name):
    require(isinstance(name, str) and name and '\\' not in name and ':' not in name and '\x00' not in name,
            'Invalid resource path')
    path = Path(name)
    require(not path.is_absolute() and all(part not in ('', '.', '..') for part in name.split('/')),
            'Resource path escapes its root')
    return name


def json_bytes(data):
    require(len(data) <= MAX_JSON, 'Binding JSON exceeds its bound')
    return json.loads(data, object_pairs_hook=unique)


def member_digest(archive, name):
    row = archive.getinfo(name)
    require(not row.is_dir() and 0 <= row.file_size <= MAX_RESOURCE, 'Invalid resource entry bound')
    digest = hashlib.sha256(); size = 0
    with archive.open(row) as source:
        while True:
            block = source.read(1024 * 1024)
            if not block:
                break
            size += len(block)
            require(size <= row.file_size, 'Resource grew while reading')
            digest.update(block)
    require(size == row.file_size, 'Truncated resource entry')
    return dict(bytes=size, sha256=digest.hexdigest())


def verify_pin(measured, pin, message):
    require(type(pin.get('bytes')) is int and 0 <= pin['bytes'] <= MAX_RESOURCE and
            re.fullmatch('[0-9a-f]{64}', str(pin.get('sha256', ''))) is not None,
            'Invalid authenticated resource pin')
    require(measured == {key: pin[key] for key in ('bytes', 'sha256')}, message)


def verify_main_output(main, ledger):
    classes = {key: row for key, row in ledger['mainOutput'].items() if key.endswith('.class')}
    jars.verify_main_class_ledger(main, classes)
    rows = {key: row for key, row in ledger['mainOutput'].items() if not key.endswith('.class')}
    for key, row in ledger['mainResources'].items():
        require(key not in rows and key not in classes, 'Main output/resource collision')
        rows[key] = row
    with jars.checked_jar(main) as (archive, _, entries):
        names = {row.filename for row in entries if not row.is_dir()}
        allowed = set(classes) | set(rows) | {'META-INF/MANIFEST.MF'}
        require(names <= allowed and set(rows) <= names, 'Unexpected or missing main resource')
        for name, pin in rows.items():
            relative(name)
            verify_pin(member_digest(archive, name), pin, 'Main resource bytes differ: ' + name)
    return dict(classes=len(classes), metadataAndResources=len(rows))


def verify_application_resources(root, ledger):
    resources = jars.paths.checked_path(root / 'app/resources')
    actual = {}
    for path in resources.rglob('*'):
        jars.paths.checked_path(path)
        if path.is_file():
            actual[path.relative_to(resources).as_posix()] = path
    expected = ledger['applicationResources']
    require(set(actual) == set(expected), 'Production application resource inventory differs')
    for name, pin in expected.items():
        relative(name)
        verify_pin(jars.file_digest(actual[name]), pin, 'Application resource bytes differ: ' + name)
    return len(expected)


def verify_native_projection(original, transformed, app, expected):
    require(expected['group'] == 'org.jetbrains.skiko' and expected['name'] == 'skiko-awt-runtime-windows-x64',
            'Only the reviewed Skiko native extraction is supported')
    verify_pin(jars.file_digest(original), expected, 'Qualified Skiko artifact bytes differ')
    mappings = []
    with jars.checked_jar(original) as (source, _, source_rows), jars.checked_jar(transformed) as (target, _, target_rows):
        source_names = {row.filename for row in source_rows}
        target_names = {row.filename for row in target_rows}
        require(source_names - target_names == NATIVE_ASSETS and target_names <= source_names,
                'Skiko packaging transformation differs')
        require(not any(row.filename.endswith('.class') for row in source_rows),
                'A native-only projection cannot remove classpath code')
        for row in source_rows:
            if row.is_dir():
                continue
            pin = member_digest(source, row.filename)
            if row.filename in target_names:
                measured = member_digest(target, row.filename)
                role = 'retained-jar-entry'
            else:
                require(row.filename in NATIVE_ASSETS, 'Unknown extracted native asset')
                measured = jars.file_digest(app / row.filename)
                role = 'extracted-native-asset'
            verify_pin(measured, pin, 'Skiko native asset differs: ' + row.filename)
            mappings.append(dict(path=row.filename, role=role, **pin))
    dll = jars.file_digest(app / 'skiko-windows-x64.dll')
    sidecar = jars.bounded_bytes(app / 'skiko-windows-x64.dll.sha256', 256).decode('ascii').strip().lower()
    require(dll['sha256'] == sidecar, 'Skiko DLL sidecar differs')
    return mappings


def _winning_classes(root, ordered):
    result = {}
    for relative_path in ordered:
        with jars.checked_jar(root / 'app' / relative_path) as (archive, _, entries):
            variants = {}
            manifest = b''
            if 'META-INF/MANIFEST.MF' in archive.namelist():
                info = archive.getinfo('META-INF/MANIFEST.MF')
                require(0 <= info.file_size <= 65536, 'Oversized JAR manifest')
                with archive.open(info) as stream: manifest = stream.read(65537)
                require(len(manifest) == info.file_size, 'JAR manifest changed')
            multi = re.search(br'(?im)^Multi-Release:\s*true\s*$', manifest) is not None
            for physical, info in jars.effective_class_entries(entries).items():
                version = int(physical.split('/')[2]) if physical.startswith('META-INF/versions/') else 0
                if version and not multi:
                    continue
                previous = variants.get(info['name'])
                if previous is None or version > previous[0]:
                    variants[info['name']] = (version, physical)
            for name, (_, physical) in variants.items():
                result.setdefault(name, (relative_path, physical))
    return result


def verify_classpath_order(root, source_order, candidate_order):
    require(set(source_order) == set(candidate_order), 'Source and candidate classpath inventories differ')
    if source_order == candidate_order:
        return dict(winnerChanges=0, equivalentChanges=0)
    source = _winning_classes(root, source_order)
    candidate = _winning_classes(root, candidate_order)
    require(set(source) == set(candidate), 'Effective production class inventories differ')
    changed = [name for name in source if source[name] != candidate[name]]
    for name in changed:
        left, right = source[name], candidate[name]
        with jars.checked_jar(root / 'app' / left[0]) as (archive, _, _): a = member_digest(archive, left[1])
        with jars.checked_jar(root / 'app' / right[0]) as (archive, _, _): b = member_digest(archive, right[1])
        require(a == b, 'Packaging changed the effective class bytes: ' + name)
    return dict(winnerChanges=len(changed), equivalentChanges=len(changed))


def production_library_bindings(root, inventory, model, qualified_native, *, exclude_resource_jars=False):
    """Use real resolved artifact identities; resources never supply host libraries."""
    resource_jars = [row for row in inventory['jars'] if row['path'].startswith('resources/')]
    require(exclude_resource_jars or not resource_jars,
            'Production dependency bytes differ: resource JARs leaked into the host classpath')
    available = [row for row in inventory['jars']
                 if row['path'] != inventory['mainJar'] and row not in resource_jars]
    bindings = {}; missing = []
    for artifact in model['mainArtifacts']:
        matches = [row for row in available if (row['sha256'], row['bytes']) == (artifact['sha256'], artifact['bytes'])]
        if not matches:
            missing.append(artifact); continue
        chosen = matches[0]; available.remove(chosen)
        require(artifact['file'] not in bindings, 'Duplicate source dependency path')
        bindings[artifact['file']] = chosen['path']
    projection = []
    if missing or available:
        require(len(missing) == len(available) == 1 and qualified_native is not None,
                'Production dependency bytes differ from the qualified source graph')
        projection = verify_native_projection(qualified_native, root / 'app' / available[0]['path'], root / 'app', missing[0])
        bindings[missing[0]['file']] = available[0]['path']
    require(projection, 'The production graphics assets lack a qualified native projection')
    source_order = []
    roots = set(model['mainClassRoots'])
    for path in model['mainRuntimeClasspath']:
        if path in bindings:
            relative_path = bindings[path]
        else:
            require(path in roots or path.endswith(('resources\\main', 'resources/main')), 'Unbound production runtime path')
            relative_path = inventory['mainJar']
        if relative_path not in source_order:
            source_order.append(relative_path)
    require(set(source_order) == {row['path'] for row in inventory['jars'] if row not in resource_jars},
            'Resolved production runtime does not cover every packaged host JAR')
    return dict(orderedClasspath=source_order, nativeProjection=projection, excludedResourceJars=resource_jars)


def _verify_production(root, model, ledger, fixture_classes, qualified_native):
    root = jars.paths.checked_path(root)
    inventory = jars.inventory(root)
    jars.require_no_fixture_shadow(inventory['productionClassNames'], fixture_classes)
    main = root / 'app' / inventory['mainJar']
    main_output = verify_main_output(main, ledger)
    resources = verify_application_resources(root, ledger)
    libraries = production_library_bindings(root, inventory, model, qualified_native)
    source_order = libraries['orderedClasspath']
    order = verify_classpath_order(root, source_order, inventory['model']['orderedClasspath'])
    return dict(mainJar=inventory['mainJar'], productionJars=inventory['jars'],
        productionClassNames=inventory['productionClassNames'], orderedProductionClasspath=inventory['model']['orderedClasspath'],
        cfgSHA256=inventory['cfgSHA256'], mainOutput=main_output, applicationResources=resources,
        nativeProjection=libraries['nativeProjection'], classpathOrder=order, bundledJavaExecutableExists=inventory['bundledJavaExecutableExists'])


def bind(export, candidate, run, jobs, log, plan_raw, source_sha, qualified_native=None):
    # Reuse the existing sealed-receipt/export verifier, then reauthenticate every
    # JSON payload used below against that same manifest snapshot.
    export = jars.paths.checked_path(export)
    candidate = jars.paths.checked_path(candidate)
    script = Path(__file__).with_name('verify-original-root-ui-harness-export.py')
    spec = importlib.util.spec_from_file_location('runtime_binding_export_guard', script)
    verifier = importlib.util.module_from_spec(spec); spec.loader.exec_module(verifier)
    proof = verifier.verify_export(export, run, jobs, log, plan_raw, source_sha)
    raw = jars.bounded_bytes(export / 'manifest.json', MAX_JSON)
    require(hashlib.sha256(raw).hexdigest() == proof['uiManifestSHA256'], 'UI manifest changed after authentication')
    manifest = json_bytes(raw)
    payload = {row['path']: row for row in manifest['payload']}
    snapshots = {}
    for name in ('production-ledger.json', 'runtime-model.json'):
        data = jars.bounded_bytes(export / name, MAX_JSON)
        verify_pin(dict(bytes=len(data), sha256=hashlib.sha256(data).hexdigest()), payload[name], 'Authenticated export payload changed')
        snapshots[name] = json_bytes(data)
    model = snapshots['runtime-model.json']; ledger = snapshots['production-ledger.json']
    require(model['sourceSha'] == source_sha and model['planSha256'] == manifest['planSha256'], 'Runtime model belongs to another source')
    bound = _verify_production(candidate, model, ledger, {row['path'][:-6] for row in manifest['classes']}, qualified_native)
    require(jars.read_windows_version(candidate / 'app' / bound['mainJar']) == manifest['windowsVersion'],
            'Candidate version differs from its qualified UI export')
    fixture = jars.class_entries(export / 'ui-fixture.jar')
    jars.require_no_fixture_shadow(bound['productionClassNames'], {row['name'] for row in fixture.values()})
    classpath = [str(export / 'ui-fixture.jar')]
    for row in manifest['dependencies']:
        path = export / relative(row['path'])
        names = {value['name'] for value in jars.class_entries(path).values()}
        jars.require_no_fixture_shadow(bound['productionClassNames'], names)
        classpath.append(str(path))
    classpath += [str(candidate / 'app' / name) for name in bound['orderedProductionClasspath']]
    for row in bound['productionJars']:
        verify_pin(jars.file_digest(candidate / 'app' / row['path']), row, 'Production JAR changed during binding')
    # A command is deliberately not emitted until the JDK/production-image layer
    # is bound. The production graph proof alone cannot authorize a Java launch.
    return dict(sourceSha=source_sha, windowsVersion=manifest['windowsVersion'],
        sealedReceiptSHA256=proof['sealedReceiptSHA256'], candidateRoot=str(candidate),
        productionClasspathAndResourcesBound=True, candidateRuntimeBound=False, actualJdkBound=False,
        classpath=classpath, mainClass=manifest['mainClass'], cfgSHA256=bound['cfgSHA256'],
        productionJars=bound['productionJars'], mainOutput=bound['mainOutput'],
        applicationResourceFiles=bound['applicationResources'], nativeProjection=bound['nativeProjection'],
        classpathOrder=bound['classpathOrder'], imageLaunchReady=False,
        applicationExecuted=False, uiRuntimeVerified=False, hardwareVerified=False, desktopReplaced=False)
