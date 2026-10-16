"""Read actual jpackage classpaths and bind compiled bytes without running Java.

This is one layer of future UI-harness admission. It does not authenticate CI,
qualify a source snapshot, inventory every resource or authorize execution.
"""
from pathlib import Path, PurePosixPath
from contextlib import contextmanager
import hashlib
import json
import re
import struct
import tempfile
import zipfile
import original_root_ui_class_closure as paths

MAX_CFG = 256 * 1024
MAX_JAR = 256 * 1024 * 1024
MAX_CENTRAL_DIRECTORY = 16 * 1024 * 1024
MAX_ENTRIES = 65534
MAX_CLASS = 8 * 1024 * 1024
MAIN = 'com/bilipai/desktop/MainKt.class'


def regular_file(path):
    path = paths.checked_path(path)
    if not path.is_file():
        raise ValueError('Expected a regular candidate file')
    return path


def bounded_bytes(path, maximum):
    path = regular_file(path)
    size = path.stat().st_size
    if not 0 < size <= maximum:
        raise ValueError('Candidate file exceeds its declared bound')
    with path.open('rb') as stream:
        raw = stream.read(maximum + 1)
    if len(raw) != size or len(raw) > maximum:
        raise ValueError('Candidate file changed during bounded read')
    return raw


def file_digest(path, maximum=MAX_JAR):
    path = regular_file(path)
    size = path.stat().st_size
    if not 0 < size <= maximum:
        raise ValueError('Candidate file exceeds its declared bound')
    digest = hashlib.sha256()
    total = 0
    with path.open('rb') as stream:
        while total <= size:
            block = stream.read(min(1024 * 1024, size - total + 1))
            if not block:
                break
            total += len(block)
            digest.update(block)
    if total != size:
        raise ValueError('Candidate file changed while hashing')
    return dict(bytes=size, sha256=digest.hexdigest())


def relative_jar(value):
    if not value.startswith('$APPDIR\\'):
        raise ValueError('Classpath must use the exact APPDIR prefix')
    name = value[len('$APPDIR\\'):].replace('\\', '/')
    parts = name.split('/')
    if (not name.endswith('.jar') or any(part in ('', '.', '..') for part in parts)
            or any(character in name for character in '\x00\r\n:$%*?')
            or PurePosixPath(name).is_absolute()):
        raise ValueError('Invalid candidate classpath entry')
    return name


def parse_cfg(raw):
    if len(raw) > MAX_CFG:
        raise ValueError('Candidate configuration exceeds its bound')
    section = None
    classes, mains, options = [], [], []
    for original in raw.decode('utf-8-sig').splitlines():
        line = original.strip()
        if not line or line.startswith(('#', ';')):
            continue
        if line.startswith('[') and line.endswith(']'):
            section = line[1:-1]
            continue
        if '=' not in line:
            raise ValueError('Malformed candidate configuration')
        key, value = line.split('=', 1)
        if section == 'Application' and key == 'app.classpath':
            classes.append(relative_jar(value))
        elif section == 'Application' and key == 'app.mainclass':
            mains.append(value)
        elif section == 'JavaOptions' and key == 'java-options':
            options.append(value)
    if mains != ['com.bilipai.desktop.MainKt'] or not classes or len(classes) > 512:
        raise ValueError('Unexpected candidate main or classpath count')
    if len(classes) != len({name.casefold() for name in classes}):
        raise ValueError('Duplicate candidate classpath entry')
    for prefix, expected in (
        ('-Dcompose.application.resources.dir=', '$APPDIR\\resources'),
        ('-Dskiko.library.path=', '$APPDIR'),
    ):
        values = [option[len(prefix):] for option in options if option.startswith(prefix)]
        if values != [expected]:
            raise ValueError('Candidate graphics/resource binding changed')
    return dict(mainClass=mains[0], orderedClasspath=classes, javaOptions=options)


def zip_directory(stream, size):
    """Bound central-directory allocation *before* ZipFile parses it."""
    if not 22 <= size <= MAX_JAR:
        raise ValueError('Invalid candidate JAR size')
    stream.seek(max(0, size - 65557))
    tail = stream.read(65557)
    offset = tail.rfind(b'PK\x05\x06')
    if offset < 0 or len(tail) - offset < 22:
        raise ValueError('Missing bounded ZIP end record')
    signature, disk, directory_disk, disk_entries, entries, directory_size, directory_offset, comment = struct.unpack_from('<4s4H2IH', tail, offset)
    eocd = size - len(tail) + offset
    if eocd >= 20:
        stream.seek(eocd - 20)
        if stream.read(4) == b'PK\x06\x07':
            raise ValueError('ZIP64 locator is unsupported before ZIP parsing')
    if (disk or directory_disk or disk_entries != entries or entries > MAX_ENTRIES
            or directory_offset == 0xffffffff or directory_size == 0xffffffff
            or directory_size > MAX_CENTRAL_DIRECTORY or comment != len(tail) - offset - 22
            or directory_offset + directory_size > eocd):
        raise ValueError('Unsupported or oversized ZIP directory')
    return entries


@contextmanager
def jar_snapshot(path):
    """Hash and inspect the same bounded private snapshot, spilling above 2 MiB."""
    path = regular_file(path)
    size = path.stat().st_size
    if not 22 <= size <= MAX_JAR:
        raise ValueError('Invalid candidate JAR size')
    with tempfile.SpooledTemporaryFile(max_size=2 * 1024 * 1024, mode='w+b') as snapshot:
        digest = hashlib.sha256()
        total = 0
        with path.open('rb') as source:
            while total <= size:
                block = source.read(min(1024 * 1024, size - total + 1))
                if not block:
                    break
                total += len(block)
                if total > size:
                    raise ValueError('Candidate JAR grew during snapshot')
                snapshot.write(block)
                digest.update(block)
        if total != size:
            raise ValueError('Candidate JAR changed during snapshot')
        measured = dict(bytes=size, sha256=digest.hexdigest())
        if file_digest(path) != measured:
            raise ValueError('Candidate JAR changed before snapshot inspection')
        snapshot.seek(0)
        yield snapshot, measured
        if file_digest(path) != measured:
            raise ValueError('Candidate JAR changed during snapshot inspection')


def check_zip_directory(path):
    with jar_snapshot(path) as (snapshot, measured):
        return zip_directory(snapshot, measured['bytes'])


@contextmanager
def checked_jar(path):
    with jar_snapshot(path) as (snapshot, measured):
        count = zip_directory(snapshot, measured['bytes'])
        snapshot.seek(0)
        with zipfile.ZipFile(snapshot) as archive:
            entries = archive.infolist()
            if len(entries) != count or len({row.filename for row in entries}) != len(entries):
                raise ValueError('Ambiguous candidate JAR entries')
            if any(row.orig_filename != row.filename or '\\' in row.orig_filename or '\x00' in row.orig_filename
                   for row in entries):
                raise ValueError('Normalized or NUL-containing JAR entry alias')
            yield archive, measured, entries


def effective_class_entries(entries):
    result = {}
    for row in entries:
        name = row.filename
        if not name.endswith('.class') or name.endswith('/module-info.class') or name == 'module-info.class':
            continue
        effective = name
        if name.startswith('META-INF/versions/'):
            versioned = name.split('/', 3)
            if len(versioned) != 4 or not re.fullmatch('[1-9][0-9]*', versioned[2]):
                raise ValueError('Invalid versioned class path')
            if int(versioned[2]) > 21:
                continue
            effective = versioned[3]
        if not paths.NAME.fullmatch(effective[:-6]) or not 0 < row.file_size <= MAX_CLASS:
            raise ValueError('Invalid or oversized candidate class')
        result[name] = dict(name=effective[:-6], bytes=row.file_size)
    return result


def class_entries(path):
    with checked_jar(path) as (_, _, entries):
        return effective_class_entries(entries)


def inventory(candidate):
    root = paths.checked_path(candidate)
    app = paths.checked_path(root / 'app')
    cfg = regular_file(app / 'BiliPai Windows.cfg')
    cfg_raw = bounded_bytes(cfg, MAX_CFG)
    model = parse_cfg(cfg_raw)
    jars, all_classes, main_jars = [], set(), []
    for relative in model['orderedClasspath']:
        path = regular_file(app.joinpath(*relative.split('/')))
        with checked_jar(path) as (_, measured, entries):
            classes = effective_class_entries(entries)
        if MAIN in classes:
            main_jars.append(relative)
        all_classes.update(row['name'] for row in classes.values())
        if len(all_classes) > 300000:
            raise ValueError('Production class-name inventory exceeds its bound')
        jars.append(dict(path=relative, **measured, effectiveClassNames=len(set(row['name'] for row in classes.values()))))
    if len(main_jars) != 1:
        raise ValueError('Production Main must have exactly one origin')
    graphics = []
    for name in ('skiko-windows-x64.dll', 'icudtl.dat'):
        graphics.append(dict(path='app/' + name, **file_digest(app / name)))
    return dict(model=model, cfgSHA256=hashlib.sha256(cfg_raw).hexdigest(), jars=jars,
        mainJar=main_jars[0], productionClassNames=all_classes, graphics=graphics,
        bundledJavaExecutableExists=(root / 'runtime/bin/java.exe').is_file(),
        applicationExecuted=False, sourceQualified=False)


def require_no_fixture_shadow(production_names, fixture_names):
    overlap = set(production_names) & set(fixture_names)
    if overlap:
        raise ValueError('UI fixture shadows production classes: ' + ', '.join(sorted(overlap)[:3]))


def verify_main_class_ledger(jar, expected):
    """Compare an independently authenticated CI ledger; never derive it here."""
    with checked_jar(jar) as (archive, _, entries):
        actual = {row.filename: row for row in entries if row.filename.endswith('.class')}
        if set(actual) != set(expected):
            raise ValueError('Production main class inventory differs from compiled CI output')
        for name, row in expected.items():
            if type(row['bytes']) is not int or not 0 < row['bytes'] <= MAX_CLASS:
                raise ValueError('Invalid expected production class bound')
            with archive.open(name) as stream:
                raw = stream.read(MAX_CLASS + 1)
            if (len(raw) != row['bytes'] or hashlib.sha256(raw).hexdigest() != row['sha256']):
                raise ValueError('Production main class bytes differ from compiled CI output')


def read_windows_version(jar):
    with checked_jar(jar) as (archive, _, entries):
        rows = [row for row in entries if row.filename == 'windows-update.json']
        if len(rows) != 1 or not 0 < rows[0].file_size <= 65536:
            raise ValueError('Production version resource is missing or ambiguous')
        with archive.open(rows[0]) as stream:
            raw = stream.read(65537)
        if len(raw) != rows[0].file_size:
            raise ValueError('Production version resource exceeds its bound')
        return json.loads(raw)['version']
