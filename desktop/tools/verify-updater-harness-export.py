"""Authenticate a downloaded test harness against completed own-repository CI.

This reads files only. It does not execute Java, launch a client or qualify an
actual upgrade. Fetch run/jobs/log evidence from GitHub before calling it.
"""
from pathlib import Path
import argparse
import hashlib
import importlib.util
import json
import os
import re
import stat
import struct
import zipfile

TOOLS = Path(__file__).resolve().parent


def require(value, message):
    if not value:
        raise ValueError(message)


def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(block)
    return value.hexdigest()


def regular(path):
    for item in [path, *path.parents]:
        info = item.lstat()
        require(not stat.S_ISLNK(info.st_mode) and not getattr(info, 'st_file_attributes', 0) & 0x400,
                'Linked harness input is not allowed')
    require(path.is_file(), 'Expected a regular harness file')
    return path


def unique(pairs):
    result = {}
    for name, value in pairs:
        require(name not in result, 'Duplicate manifest field')
        result[name] = value
    return result


def class_name(name):
    return re.fullmatch(r'com/bilipai/desktop/update/(?:DesktopUpdaterIntegrationTest|'
                       r'PackagedUpdaterChildEnvironment|PackagedUpdaterHarnessMain)'
                       r'(?:\$[A-Za-z0-9_$]+)?\.class', name) is not None


def verify(root, run, jobs, log, plan_bytes, source_sha):
    spec = importlib.util.spec_from_file_location('updater_export_qualification',
                                                TOOLS / 'verify-package-source-qualification.py')
    gate = importlib.util.module_from_spec(spec); spec.loader.exec_module(gate)
    plan = json.loads(plan_bytes, object_pairs_hook=unique)
    require(plan.get('exportPackagedUpdaterHarness') is True, 'Source did not opt into this export')
    proof = gate.verify(run, jobs, log, plan, source_sha)
    sealed = proof['updaterHarnessExport']
    root = Path(os.path.abspath(root))
    manifest_path = regular(root / 'manifest.json')
    require(manifest_path.stat().st_size <= 2 * 1024 * 1024, 'Oversized harness manifest')
    require(digest(manifest_path) == sealed['manifestSha256'], 'Harness manifest is not the sealed CI result')
    manifest = json.loads(manifest_path.read_bytes(), object_pairs_hook=unique)
    require(manifest.get('schema') == 1 and manifest.get('sourceSha') == source_sha and
            manifest.get('windowsVersion') == plan['windowsVersion'] and
            manifest.get('planSha256') == hashlib.sha256(plan_bytes).hexdigest(), 'Harness identity differs')
    require(all(manifest.get(key) is False for key in
                ['applicationExecuted', 'updaterE2EExecuted', 'releaseQualified']), 'Export claims execution')
    require(manifest.get('mainClass') == 'com.bilipai.desktop.update.PackagedUpdaterHarnessMain',
            'Unexpected harness entry')
    sources = manifest.get('sources', [])
    required_sources = {'desktop/src/test/kotlin/com/bilipai/desktop/update/' + name + '.kt'
                        for name in ['DesktopUpdaterIntegrationTest', 'PackagedUpdaterChildEnvironment',
                                     'PackagedUpdaterHarnessMain']}
    pins = {row['path']: row for row in plan['sources']}
    require(len(sources) == 3 and {row['path'] for row in sources} == required_sources,
            'Incomplete harness source closure')
    require(all(pins.get(row['path'], {}).get('sha256') == row['sha256'] and
                pins[row['path']].get('normalization') == 'lf' for row in sources), 'Unpinned harness source')
    payload = manifest.get('payload', [])
    require(len(payload) == sealed['payloadFiles'], 'Incomplete harness payload')
    files = {}
    total = 0
    for row in payload:
        name = row['path']
        require(isinstance(name, str) and (name == 'updater-harness.jar' or
                re.fullmatch(r'lib/[0-9]{2}-[A-Za-z0-9_-]+\.jar', name)), 'Invalid harness path')
        require(name not in files and type(row['bytes']) is int and 0 < row['bytes'] <= 16 * 1024 * 1024,
                'Duplicate or oversized harness file')
        path = regular(root / name)
        require(path.stat().st_size == row['bytes'] and digest(path) == row['sha256'], 'Harness bytes differ')
        require(zipfile.is_zipfile(path), 'Invalid harness JAR')
        total += row['bytes']; files[name] = row
    require(total == sealed['payloadBytes'] and total <= 64 * 1024 * 1024, 'Harness size differs')
    actual = set()
    for directory, folders, leaves in os.walk(root, followlinks=False):
        for folder in folders:
            item = Path(directory) / folder
            info = item.lstat()
            require(not stat.S_ISLNK(info.st_mode) and not getattr(info, 'st_file_attributes', 0) & 0x400,
                    'Linked export folder')
        for leaf in leaves:
            actual.add(regular(Path(directory) / leaf).relative_to(root).as_posix())
    require(actual == set(files) | {'manifest.json'}, 'Undeclared harness file')
    allowed = {('org.jetbrains.kotlin', 'kotlin-test'), ('org.jetbrains.kotlin', 'kotlin-test-junit5'),
               ('org.junit.jupiter', 'junit-jupiter-api'), ('org.junit.platform', 'junit-platform-commons'),
               ('org.opentest4j', 'opentest4j'), ('org.apiguardian', 'apiguardian-api')}
    dependencies = manifest.get('dependencies', [])
    identities = set(); dep_paths = set()
    for row in dependencies:
        identity = row['group'], row['name']; name = row['path']
        require(identity in allowed and identity not in identities and name not in dep_paths and
                re.fullmatch(r'lib/[0-9]{2}-' + re.escape(row['name']) + r'\.jar', name) and
                re.fullmatch(r'[A-Za-z0-9_.+-]+', row['version']) and
                name in files and files[name]['sha256'] == row['sha256'], 'Invalid dependency closure')
        identities.add(identity); dep_paths.add(name)
    require(allowed - {('org.apiguardian', 'apiguardian-api')} <= identities and
            dep_paths == set(files) - {'updater-harness.jar'}, 'Incomplete dependency closure')
    classes = manifest.get('classes', [])
    require(3 <= len(classes) <= 512, 'Invalid test class count')
    expected = {}
    for row in classes:
        name = row['path']
        require(class_name(name) and name not in expected and type(row['bytes']) is int and
                8 <= row['bytes'] <= 2 * 1024 * 1024, 'Unexpected test class')
        expected[name] = row
    require(sum(row['bytes'] for row in classes) <= 16 * 1024 * 1024, 'Oversized test classes')
    for name in ['DesktopUpdaterIntegrationTest', 'PackagedUpdaterChildEnvironment', 'PackagedUpdaterHarnessMain']:
        require('com/bilipai/desktop/update/' + name + '.class' in expected, 'Missing entry class')
    with zipfile.ZipFile(root / 'updater-harness.jar') as archive:
        entries = archive.infolist()
        require(len(entries) == len(expected) and {item.filename for item in entries} == set(expected),
                'Test JAR class closure differs')
        for item in entries:
            row = expected[item.filename]
            require(item.file_size == row['bytes'], 'Class size differs')
            raw = archive.read(item)
            require(raw[:4] == b'\xca\xfe\xba\xbe' and 52 <= struct.unpack('>H', raw[6:8])[0] <= 65 and
                    hashlib.sha256(raw).hexdigest() == row['sha256'], 'Compiled class differs')
    return dict(proof, harnessReadbackVerified=True, harnessManifestSha256=sealed['manifestSha256'],
                harnessFiles=len(files), harnessBytes=total, testClasses=len(classes),
                applicationExecuted=False, updaterE2EExecuted=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ['export', 'run', 'jobs', 'log', 'plan', 'output']:
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--source-sha', required=True)
    args = parser.parse_args()
    require(not args.output.exists(), 'Do not overwrite an existing harness qualification')
    result = verify(args.export, json.loads(args.run.read_bytes()), json.loads(args.jobs.read_bytes()),
                    args.log.read_text(encoding='utf-8-sig'), args.plan.read_bytes(), args.source_sha)
    with args.output.open('x', encoding='utf-8') as stream:
        stream.write(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result))


if __name__ == '__main__':
    main()
