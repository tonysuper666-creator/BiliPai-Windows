"""Read downloaded same-compilation UI exports; never launch Java or the client.

The Root caller must supply freshly acquired real GitHub run/jobs/log evidence.
Synthetic unit evidence exercises parsing only and cannot establish CI execution.
Candidate production bytes and the actual fixed JDK remain a separate binding.
"""
from pathlib import Path, PurePosixPath
from types import SimpleNamespace
import hashlib
import argparse
import importlib.util
import json
import os
import re
import sys

TOOLS = Path(__file__).resolve().parent
sys.path.insert(0, str(TOOLS))
import original_root_ui_class_closure as classes
import original_root_ui_candidate_classpath as jars


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, TOOLS / filename)
    result = importlib.util.module_from_spec(spec); spec.loader.exec_module(result)
    return result


gate = load('ui_readback_source_qualification', 'verify-package-source-qualification.py')
exporter = load('ui_readback_export_protocol', 'export-original-root-ui-harness.py')
MAX_JSON = 32 * 1024 * 1024
MAX_PAYLOAD = 128 * 1024 * 1024


def require(condition, message):
    if not condition: raise ValueError(message)


def unique(pairs):
    result = {}
    for name, value in pairs:
        require(name not in result, 'Duplicate export JSON field')
        result[name] = value
    return result


def digest(raw): return hashlib.sha256(raw).hexdigest()


def relative(name):
    require(isinstance(name, str) and name and not PurePosixPath(name).is_absolute() and
        not any(part in ('', '.', '..') for part in name.split('/')) and
        not any(character in name for character in '\\\x00\r\n\t:'), 'Invalid export entry path')
    return name


def stat_row(row, maximum, empty=False):
    require(isinstance(row, dict) and type(row.get('bytes')) is int and
        (0 if empty else 1) <= row['bytes'] <= maximum and
        re.fullmatch('[0-9a-f]{64}', str(row.get('sha256', ''))) is not None,
        'Invalid export entry byte identity')
    return {key: row[key] for key in ('bytes', 'sha256')}


def json_snapshot(root, name, expected, maximum=MAX_JSON):
    raw = jars.bounded_bytes(root / name, maximum)
    require(dict(bytes=len(raw), sha256=digest(raw)) == stat_row(expected, maximum), 'Exported JSON bytes differ')
    return json.loads(raw, object_pairs_hook=unique)


def files_under(root):
    root = classes.checked_path(root)
    result = set()
    for folder, directories, files in os.walk(root, followlinks=False):
        for name in directories: classes.checked_path(Path(folder) / name)
        for name in files:
            path = jars.regular_file(Path(folder) / name)
            require(len(result) < 37, 'Export contains too many files')
            result.add(path.relative_to(root).as_posix())
    return result


def ledger_inventory(data):
    require(isinstance(data, dict) and set(data) == {'mainOutput', 'mainResources', 'applicationResources'},
            'Unexpected production ledger sections')
    for section, rows in data.items():
        require(isinstance(rows, dict) and len(rows) <= 100000, 'Production ledger file inventory exceeds bound')
        total = 0
        for name, row in rows.items():
            relative(name)
            value = stat_row(row, 512 * 1024 * 1024, empty=True)
            if name.endswith('.class'): require(value['bytes'] > 0, 'Empty production class')
            total += value['bytes']
        require(total <= 8 * 1024 * 1024 * 1024, 'Production ledger byte inventory exceeds bound')
    require(jars.MAIN in data['mainOutput'] and 'windows-update.json' in data['mainResources'],
            'Production Main/version ledger is absent')


def verify_export(root, run, jobs, log, plan_raw, source_sha):
    require(len(plan_raw) <= 4 * 1024 * 1024, 'Oversized source plan')
    plan = json.loads(plan_raw, object_pairs_hook=unique)
    require(plan.get('exportOriginalRootUiHarness') is True, 'Source did not opt into original UI export')
    proof = gate.verify(run, jobs, log, plan, source_sha)
    sealed = proof['originalRootUiHarnessExport']
    root = classes.checked_path(root)
    manifest_raw = jars.bounded_bytes(root / 'manifest.json', MAX_JSON)
    require(digest(manifest_raw) == sealed['manifestSha256'], 'UI manifest is not the sealed CI result')
    manifest = json.loads(manifest_raw, object_pairs_hook=unique)
    require(type(manifest.get('schema')) is int and manifest['schema'] == 1 and
        manifest.get('sourceSha') == source_sha and manifest.get('planSha256') == digest(plan_raw) and
        manifest.get('windowsVersion') == plan['windowsVersion'], 'UI export source/version/plan differs')
    require(manifest.get('mainClass') == classes.ENTRY.replace('/', '.') and
        manifest.get('productionRuntimeBindingRequired') is True and
        all(manifest.get(key) is False for key in ('productionClassesExported', 'applicationExecuted', 'hardwareExecuted', 'releaseQualified')),
        'UI export cannot claim runtime acceptance')
    payload = manifest.get('payload')
    require(isinstance(payload, list) and len(payload) == sealed['payloadFiles'], 'UI payload count differs')
    files, total = {}, 0
    for row in payload:
        name = relative(row['path'])
        require(name not in files and (name in ('ui-fixture.jar', 'runtime-model.json', 'production-ledger.json', 'test-sources.json')
            or re.fullmatch(r'lib/[0-9]{2}-[A-Za-z0-9_.+-]+\.jar', name)), 'Unexpected or duplicate UI payload path')
        expected = stat_row(row, MAX_PAYLOAD)
        require(jars.file_digest(root / name, maximum=MAX_PAYLOAD) == expected, 'UI payload bytes differ')
        files[name] = expected; total += expected['bytes']
    require(total == sealed['payloadBytes'] and total <= MAX_PAYLOAD and files_under(root) == set(files) | {'manifest.json'},
        'UI payload size or actual inventory differs')
    require({'ui-fixture.jar', 'runtime-model.json', 'production-ledger.json', 'test-sources.json'} <= set(files),
        'Required UI export sections are absent')
    model = json_snapshot(root, 'runtime-model.json', files['runtime-model.json'])
    production = json_snapshot(root, 'production-ledger.json', files['production-ledger.json'])
    sources = json_snapshot(root, 'test-sources.json', files['test-sources.json'])
    require(type(model.get('schema')) is int and model['schema'] == 1 and model.get('sourceSha') == source_sha and model.get('planSha256') == digest(plan_raw) and
        model.get('task') == 'windowsVideoLocalReplayUiSmoke' and model.get('mainClass') == manifest['mainClass'] and
        all(model.get(key) is False for key in ('javaExecExecuted', 'applicationExecuted', 'hardwareExecuted')),
        'Actual runtime model identity differs')
    pins = {row['path']: row for row in plan['sources']}
    compiler, jdk = manifest.get('compiler'), manifest.get('jdk')
    require(isinstance(compiler, dict) and isinstance(jdk, dict) and compiler == model.get('compiler') and jdk == model.get('jdk') and
        compiler.get('declaredKotlinJvmPlugin') == '2.4.0' and
        re.fullmatch('[0-9a-f]{64}', str(compiler.get('buildScriptSha256', ''))) is not None and
        pins.get('desktop/build.gradle.kts', {}).get('normalization') == 'lf' and
        compiler.get('buildScriptSha256') == pins.get('desktop/build.gradle.kts', {}).get('sha256') and
        re.fullmatch('[0-9a-f]{64}', str(jdk.get('releaseSha256', ''))) is not None,
        'Compiler/JDK model differs from authenticated source')
    require(isinstance(sources, list) and 0 < len(sources) <= 4096 and isinstance(model.get('testSources'), list) and
        sources == sorted(model['testSources'], key=lambda row: row['path']),
        'Actual test source snapshot differs')
    seen, source_bytes = set(), 0
    for row in sources:
        name = relative(row['path'])
        require(name not in seen and name.endswith(('.kt', '.java')) and
            name.startswith(('desktop/src/test/kotlin/', 'desktop/src/test/java/', 'desktop/build/generated/')) and
            row.get('normalization') == 'lf' and type(row.get('bytesLf')) is int and 0 <= row['bytesLf'] <= 2 * 1024 * 1024 and
            re.fullmatch('[0-9a-f]{64}', str(row.get('sha256', ''))) is not None, 'Unexpected test source snapshot entry')
        if name in pins: require(pins[name]['normalization'] == 'lf' and pins[name]['sha256'] == row['sha256'], 'Test source differs from cohort pin')
        seen.add(name); source_bytes += row['bytesLf']
    require(source_bytes <= 32 * 1024 * 1024, 'Test source snapshot exceeds total bound')
    ledger_inventory(production)
    extra = exporter.artifact_difference(model)
    dependencies = manifest.get('dependencies')
    require(isinstance(dependencies, list) and len(dependencies) == len(extra) <= 32, 'Test-only dependency graph differs')
    dependency_files, dependency_classes = set(), set()
    for number, (row, actual) in enumerate(zip(dependencies, extra)):
        name = 'lib/' + str(number).zfill(2) + '-' + actual['name'] + '.jar'
        require(row.get('path') == name and all(row.get(key) == actual[key] for key in
            ('group', 'name', 'version', 'classifier', 'type', 'bytes', 'sha256')) and
            files.get(name) == stat_row(row, 16 * 1024 * 1024), 'Exported test dependency identity differs')
        with jars.checked_jar(root / name) as (_, measured, entries):
            require(measured == files[name], 'Test dependency changed during readback')
            dependency_classes.update(item['name'] for item in jars.effective_class_entries(entries).values())
        dependency_files.add(name)
    require(set(files) == dependency_files | {'ui-fixture.jar', 'runtime-model.json', 'production-ledger.json', 'test-sources.json'},
        'UI export contains an undeclared dependency')
    class_rows, resource_rows = manifest.get('classes'), manifest.get('resources')
    require(isinstance(class_rows, list) and 0 < len(class_rows) <= classes.MAX_CLASSES and
            isinstance(resource_rows, list) and len(resource_rows) <= 100000, 'UI JAR inventory exceeds bound')
    expected_classes, expected_resources = {}, {}
    for rows, target, is_class in ((class_rows, expected_classes, True), (resource_rows, expected_resources, False)):
        for row in rows:
            name = relative(row['path'])
            require(name not in target and name.endswith('.class') == is_class, 'Ambiguous UI JAR entry')
            stat_row(row, classes.MAX_CLASS_BYTES if is_class else 16 * 1024 * 1024, empty=not is_class)
            target[name] = row
    require(sum(row['bytes'] for row in class_rows) <= 32 * 1024 * 1024 and
        sum(row['bytes'] for row in resource_rows) <= 16 * 1024 * 1024 and
        not (set(expected_resources) & (set(production['mainOutput']) | set(production['mainResources']))),
        'UI JAR exceeds bound or shadows production output')
    index = {}
    with jars.checked_jar(root / 'ui-fixture.jar') as (archive, measured, entries):
        require(measured == files['ui-fixture.jar'] and {row.filename for row in entries} == set(expected_classes) | set(expected_resources),
            'UI JAR differs from exported closure')
        for entry in entries:
            row = (expected_classes if entry.filename.endswith('.class') else expected_resources)[entry.filename]
            require(entry.file_size == row['bytes'], 'UI JAR entry byte count differs')
            with archive.open(entry) as stream: raw = stream.read(row['bytes'] + 1)
            require(len(raw) == row['bytes'] and digest(raw) == row['sha256'], 'UI JAR entry bytes differ')
            if entry.filename.endswith('.class'):
                symbols = classes.class_symbols(raw)
                require(symbols.name + '.class' == entry.filename and symbols.source == row.get('sourceFileLabel'),
                    'UI class identity/source label differs')
                index[symbols.name] = SimpleNamespace(symbols=symbols)
    production_names = {name[:-6] for name in production['mainOutput'] if name.endswith('.class')}
    require(set(classes.select_closure(index, production_names)) == set(index), 'UI JAR contains unreachable fixture classes')
    jars.require_no_fixture_shadow(dependency_classes, index)
    for name, expected in files.items(): require(jars.file_digest(root / name, maximum=MAX_PAYLOAD) == expected, 'UI payload changed during readback')
    require(jars.bounded_bytes(root / 'manifest.json', MAX_JSON) == manifest_raw and files_under(root) == set(files) | {'manifest.json'},
        'UI export changed during readback')
    verified = dict(proof, originalRootUiHarnessReadbackVerified=True, uiManifestSHA256=sealed['manifestSha256'],
        uiFixtureClasses=len(index), uiPayloadFiles=len(files), uiPayloadBytes=total,
        candidateRuntimeBound=False, actualJdkBound=False, applicationExecuted=False, uiRuntimeVerified=False)
    return dict(proof=verified, manifest=manifest, model=model, production=production,
        fixtureClassNames=set(index), dependencyClassNames=dependency_classes)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('export', 'run', 'jobs', 'log', 'plan', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--source-sha', required=True)
    args = parser.parse_args()
    output = classes.absolute_path(args.output)
    require(not output.exists() and not output.is_relative_to(classes.absolute_path(args.export)),
        'Readback proof must be new and outside the immutable export')
    classes.checked_path(output.parent)
    read_json = lambda path: json.loads(jars.bounded_bytes(path, MAX_JSON), object_pairs_hook=unique)
    result = verify_export(args.export, read_json(args.run), read_json(args.jobs),
        jars.bounded_bytes(args.log, 64 * 1024 * 1024).decode('utf-8-sig'),
        jars.bounded_bytes(args.plan, 4 * 1024 * 1024), args.source_sha)
    with output.open('x', encoding='utf-8', newline='\n') as stream:
        json.dump(result['proof'], stream, indent=2); stream.write('\n')
    print(json.dumps(result['proof']))


if __name__ == '__main__': main()
