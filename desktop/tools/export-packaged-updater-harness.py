"""Export already-compiled updater tests; this tool never compiles or starts a JVM.

The completed source run and sealed receipt must subsequently authenticate this
export. An export alone proves neither updater behavior nor release readiness.
"""
from pathlib import Path
import argparse, hashlib, json, math, os, re, shutil, stat, struct, subprocess, time, zipfile

CLASS_PREFIX = 'com/bilipai/desktop/update/'
CLASS_BASES = ('DesktopUpdaterIntegrationTest', 'PackagedUpdaterChildEnvironment',
               'PackagedUpdaterHarnessMain')
SOURCE_FILES = tuple('desktop/src/test/kotlin/' + CLASS_PREFIX + name + '.kt'
                     for name in CLASS_BASES)
ALLOWED_LIBRARIES = {
    ('org.jetbrains.kotlin', 'kotlin-test'),
    ('org.jetbrains.kotlin', 'kotlin-test-junit5'),
    ('org.junit.jupiter', 'junit-jupiter-api'),
    ('org.junit.platform', 'junit-platform-commons'),
    ('org.opentest4j', 'opentest4j'),
    ('org.apiguardian', 'apiguardian-api'),
}
REQUIRED_LIBRARIES = ALLOWED_LIBRARIES - {('org.apiguardian', 'apiguardian-api')}
MAX_CLASS_BYTES = 2 * 1024 * 1024
MAX_CLASSES_BYTES = 16 * 1024 * 1024
MAX_DEPENDENCY_BYTES = 16 * 1024 * 1024
MAX_TOTAL_BYTES = 64 * 1024 * 1024


def digest(path):
    result = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            result.update(chunk)
    return result.hexdigest()


def regular_beneath(path, root):
    """Reject symlinks/junctions before resolution, including their ancestors."""
    root = Path(os.path.abspath(root)); path = Path(os.path.abspath(path))
    if not path.is_relative_to(root) or path == root:
        raise ValueError('Input escaped its declared root')
    for part in [root, *reversed(path.parents[:len(path.relative_to(root).parts) - 1]), path]:
        info = part.lstat()
        if stat.S_ISLNK(info.st_mode) or getattr(info, 'st_file_attributes', 0) & 0x400:
            raise ValueError('Reparse/link input is not permitted')
    if not path.is_file() or not path.resolve().is_relative_to(root.resolve()):
        raise ValueError('Expected a regular input under its declared root')
    return path


def class_name(path):
    if path.suffix != '.class':
        return False
    return any(re.fullmatch(re.escape(name) + r'(?:\$[A-Za-z0-9_$]+)?\.class', path.name)
               for name in CLASS_BASES)


def export(repo, inputs, destination, expected_source, compiled_after):
    repo = Path(repo).resolve(strict=True)
    if not math.isfinite(compiled_after) or not time.time() - 3600 <= compiled_after <= time.time() + 2:
        raise ValueError('A current compilation boundary is required')
    if not re.fullmatch('[0-9a-f]{40}', expected_source):
        raise ValueError('A full source commit is required')
    actual = subprocess.check_output(['git', '-C', str(repo), 'rev-parse', 'HEAD']).decode().strip()
    if actual != expected_source or inputs['sourceSha'] != actual:
        raise ValueError('Source head changed')
    plan_path = regular_beneath(repo / '.github/windows-source-validation-plan.json', repo)
    plan = json.loads(plan_path.read_bytes())
    source_pins = {row['path']: row for row in plan['sources']}
    sources = []
    for name in SOURCE_FILES:
        path = regular_beneath(repo / name, repo)
        raw = path.read_bytes().replace(b'\r\n', b'\n')
        tracked = subprocess.check_output(['git', '-C', str(repo), 'show', actual + ':' + name]).replace(b'\r\n', b'\n')
        row = source_pins.get(name)
        if raw != tracked or not row or row['normalization'] != 'lf' or hashlib.sha256(raw).hexdigest() != row['sha256']:
            raise ValueError('Harness source is unpinned or modified')
        sources.append({'path': name, 'sha256': row['sha256']})
    classes = {}; class_bytes = 0
    for root_name in inputs['classRoots']:
        root = Path(root_name)
        if root.absolute() not in [(repo / 'desktop/build/classes/kotlin/test').absolute(),
                                   (repo / 'desktop/build/classes/java/test').absolute()]:
            raise ValueError('Unexpected test class root')
        folder = root / CLASS_PREFIX
        if not folder.exists():
            continue
        for leaf in folder.iterdir():
            if not class_name(leaf):
                continue
            leaf = regular_beneath(leaf, repo)
            if leaf.stat().st_mtime < compiled_after - 2 or leaf.stat().st_size > MAX_CLASS_BYTES:
                raise ValueError('Stale or oversized compiled class')
            raw = leaf.read_bytes()
            if len(raw) < 8 or raw[:4] != b'\xca\xfe\xba\xbe' or not 52 <= struct.unpack('>H', raw[6:8])[0] <= 65:
                raise ValueError('Invalid or unsupported compiled class header')
            name = CLASS_PREFIX + leaf.name
            if name in classes:
                raise ValueError('Duplicate test class')
            class_bytes += len(raw)
            if class_bytes > MAX_CLASSES_BYTES or len(classes) >= 512:
                raise ValueError('Compiled harness exceeds its bounded buffer')
            classes[name] = (leaf, raw)
    required = {CLASS_PREFIX + name + '.class' for name in CLASS_BASES}
    if not required <= classes.keys() or len(classes) > 512 or sum(len(raw) for _, raw in classes.values()) > MAX_CLASSES_BYTES:
        raise ValueError('Incomplete or oversized compiled harness')
    cache = Path(inputs['gradleUserHome']).absolute() / 'caches/modules-2/files-2.1'
    libraries = {}; total = sum(len(raw) for _, raw in classes.values())
    for row in inputs['artifacts']:
        identity = row['group'], row['name']
        if identity not in ALLOWED_LIBRARIES or identity in libraries:
            raise ValueError('Unknown or duplicate test dependency')
        if not re.fullmatch('[A-Za-z0-9_.+-]+', row['version']):
            raise ValueError('Invalid dependency version')
        root = cache / row['group'] / row['name'] / row['version']
        leaf = regular_beneath(Path(row['file']), root)
        if leaf.suffix != '.jar' or leaf.stat().st_size > MAX_DEPENDENCY_BYTES or not zipfile.is_zipfile(leaf):
            raise ValueError('Invalid dependency JAR')
        measured = digest(leaf)
        if measured != row['sha256']:
            raise ValueError('Resolved dependency bytes changed')
        total += leaf.stat().st_size
        if total > MAX_TOTAL_BYTES:
            raise ValueError('Harness export is oversized')
        libraries[identity] = (leaf, row, measured)
    if not REQUIRED_LIBRARIES <= libraries.keys():
        raise ValueError('Incomplete test dependency closure')
    destination = Path(destination).absolute()
    if destination.exists() or destination.is_relative_to(repo) or destination.is_relative_to(cache):
        raise ValueError('Export destination must be new and outside source/cache')
    for parent in [destination.parent, *destination.parent.parents]:
        info = parent.lstat()
        if stat.S_ISLNK(info.st_mode) or getattr(info, 'st_file_attributes', 0) & 0x400:
            raise ValueError('Linked export parent is not permitted')
    destination.mkdir(parents=False)
    class_rows = []
    archive = destination / 'updater-harness.jar'
    with zipfile.ZipFile(archive, 'x', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as jar:
        for name, (_, raw) in sorted(classes.items()):
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0)); info.compress_type = zipfile.ZIP_DEFLATED
            jar.writestr(info, raw)
            class_rows.append({'path': name, 'bytes': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()})
    payload = [{'path': archive.name, 'bytes': archive.stat().st_size, 'sha256': digest(archive)}]
    dependencies = []
    (destination / 'lib').mkdir()
    for index, (_, (leaf, row, measured)) in enumerate(sorted(libraries.items())):
        relative = 'lib/' + str(index).zfill(2) + '-' + row['name'] + '.jar'
        output = destination / relative
        with leaf.open('rb') as source, output.open('xb') as target:
            shutil.copyfileobj(source, target, 1024 * 1024)
        if digest(output) != measured or digest(leaf) != measured:
            raise ValueError('Dependency changed during export')
        payload.append({'path': relative, 'bytes': output.stat().st_size, 'sha256': measured})
        dependencies.append({k: row[k] for k in ['group', 'name', 'version']} | {'path': relative, 'sha256': measured})
    # Check source/class bytes once more after copying; no successful manifest on drift.
    if subprocess.check_output(['git', '-C', str(repo), 'rev-parse', 'HEAD']).decode().strip() != actual:
        raise ValueError('Source changed during export')
    for name, (path, raw) in classes.items():
        if digest(path) != hashlib.sha256(raw).hexdigest():
            raise ValueError('Compiled class changed during export')
    for row in sources:
        raw = regular_beneath(repo / row['path'], repo).read_bytes().replace(b'\r\n', b'\n')
        if hashlib.sha256(raw).hexdigest() != row['sha256']:
            raise ValueError('Harness source changed during export')
    if digest(plan_path) != inputs['planSha256']:
        raise ValueError('Validation plan changed during export')
    manifest = dict(schema=1, sourceSha=actual, planSha256=inputs['planSha256'],
        windowsVersion=plan['windowsVersion'], mainClass='com.bilipai.desktop.update.PackagedUpdaterHarnessMain',
        sources=sources, classes=class_rows, dependencies=dependencies, payload=payload,
        compiledAfterEpoch=compiled_after, jdk=inputs['jdk'], compiler=inputs['compiler'],
        applicationExecuted=False, updaterE2EExecuted=False, releaseQualified=False)
    (destination / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8', newline='\n')
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', type=Path, required=True); parser.add_argument('--inputs', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True); parser.add_argument('--source-sha', required=True)
    parser.add_argument('--compiled-after', type=float, required=True)
    args = parser.parse_args()
    result = export(args.repo, json.loads(args.inputs.read_bytes()), args.output, args.source_sha, args.compiled_after)
    print(json.dumps({'sourceSha': result['sourceSha'], 'classes': len(result['classes']),
        'files': len(result['payload']), 'manifestSha256': digest(args.output / 'manifest.json'),
        'updaterE2EExecuted': False}))


if __name__ == '__main__':
    main()
