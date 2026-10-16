"""Bounded, offline class-closure readback for the existing actual-Main UI fixture.

This module never compiles, loads or executes a JVM class and never exports a
runtime. A caller must still authenticate the compilation, source/plan pins,
dependency model and same-source candidate payload before exporting or running.
"""
from collections import defaultdict, deque
from dataclasses import dataclass
from pathlib import Path
import hashlib
import os
import re
import stat
import struct

ENTRY = 'com/bilipai/desktop/ui/WindowsVideoActualRootUiFixture'
MAX_CLASS_BYTES = 2 * 1024 * 1024
MAX_CLASSES = 16384
MAX_TOTAL_BYTES = 256 * 1024 * 1024
# Kotlin backtick test methods put spaces/Unicode in generated lambda class
# names. JVM names are not restricted to Java identifier characters.
NAME = re.compile(r'[^\x00.\x3b\x5b/\\]+(?:/[^\x00.\x3b\x5b/\\]+)*\Z')
SOURCE = re.compile(r'[A-Za-z0-9_$.-]+\.(?:kt|java)\Z')


class ClassFormatError(ValueError):
    pass


def modified_utf8(raw):
    if b'\x00' in raw or any(byte >= 0xf0 for byte in raw):
        raise ClassFormatError('Invalid modified UTF-8 encoding')
    try:
        units = raw.replace(b'\xc0\x80', b'\x00').decode('utf-8', errors='surrogatepass')
        return units.encode('utf-16-le', errors='surrogatepass').decode('utf-16-le', errors='surrogatepass')
    except UnicodeError as error:
        raise ClassFormatError('Invalid modified UTF-8 encoding') from error


def descriptor_references(value):
    """Single pass, including signature type arguments, with no regex backtracking."""
    result = set()
    start = None
    for position, character in enumerate(value):
        if start is None:
            if character == 'L':
                start = position + 1
        elif character in ';<':
            candidate = value[start:position]
            if NAME.fullmatch(candidate):
                result.add(candidate)
            start = None
        elif character in '\x00[>':
            start = None
    return result


class Reader:
    def __init__(self, raw):
        self.raw = raw
        self.position = 0

    def take(self, size):
        if size < 0 or size > len(self.raw) - self.position:
            raise ClassFormatError('Truncated class structure')
        value = self.raw[self.position:self.position + size]
        self.position += size
        return value

    def number(self, size):
        return int.from_bytes(self.take(size), 'big')


@dataclass(frozen=True)
class Symbols:
    name: str
    source: str | None
    references: frozenset[str]


def class_symbols(raw):
    if not 10 <= len(raw) <= MAX_CLASS_BYTES:
        raise ClassFormatError('Class size exceeds the declared bound')
    reader = Reader(raw)
    if reader.take(4) != b'\xca\xfe\xba\xbe':
        raise ClassFormatError('Invalid class header')
    minor, major = reader.number(2), reader.number(2)
    if not 52 <= major <= 65 or minor not in (0,):
        raise ClassFormatError('Unsupported class target; preview classes are forbidden')
    count = reader.number(2)
    if count < 2:
        raise ClassFormatError('Empty constant pool')
    pool = {}
    index = 1
    while index < count:
        tag = reader.number(1)
        if tag == 1:
            value = modified_utf8(reader.take(reader.number(2)))
            pool[index] = (tag, value)
        elif tag == 7:
            pool[index] = (tag, reader.number(2))
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            reader.take(4)
        elif tag in (5, 6):
            reader.take(8)
            index += 1
            if index >= count:
                raise ClassFormatError('Truncated wide constant pool entry')
        elif tag in (8, 16, 19, 20):
            reader.take(2)
        elif tag == 15:
            reader.take(3)
        else:
            raise ClassFormatError('Unknown constant pool tag')
        index += 1

    def utf(slot):
        item = pool.get(slot)
        if item is None or item[0] != 1:
            raise ClassFormatError('Invalid UTF-8 symbol reference')
        return item[1]

    def class_name(slot):
        item = pool.get(slot)
        if item is None or item[0] != 7:
            raise ClassFormatError('Invalid class symbol reference')
        return utf(item[1])

    reader.take(2)  # access flags
    own = class_name(reader.number(2))
    if not NAME.fullmatch(own):
        raise ClassFormatError('Invalid class identity: ' + repr(own))
    superclass = reader.number(2)
    if superclass:
        class_name(superclass)
    for _ in range(reader.number(2)):
        class_name(reader.number(2))

    def attributes():
        for _ in range(reader.number(2)):
            name = utf(reader.number(2))
            yield name, reader.take(reader.number(4))

    for _ in range(2):  # fields, then methods; their Code is never interpreted.
        for _ in range(reader.number(2)):
            reader.take(2)
            utf(reader.number(2))
            utf(reader.number(2))
            for _ in attributes():
                pass
    source = None
    for name, value in attributes():
        if name == 'SourceFile':
            if source is not None or len(value) != 2:
                raise ClassFormatError('Ambiguous SourceFile attribute')
            source = utf(struct.unpack('>H', value)[0])
            if not SOURCE.fullmatch(source) or '..' in source:
                raise ClassFormatError('Invalid SourceFile name')
    if reader.position != len(raw):
        raise ClassFormatError('Trailing class bytes')
    references = set()
    for tag, value in pool.values():
        if tag == 7:
            value = utf(value)
            if NAME.fullmatch(value):
                references.add(value)
            else:
                references.update(descriptor_references(value))
        elif tag == 1:
            references.update(descriptor_references(value))
            if NAME.fullmatch(value):
                references.add(value)
            # A test helper referenced by a literal Class.forName uses dots.
            if '.' in value and NAME.fullmatch(value.replace('.', '/')):
                references.add(value.replace('.', '/'))
    references.discard(own)
    return Symbols(own, source, frozenset(references))


def absolute_path(path):
    path = Path(os.path.abspath(path))
    if os.name == 'nt' and not str(path).startswith('\\\\?\\'):
        if str(path).startswith('\\\\'):
            path = Path('\\\\?\\UNC\\' + str(path)[2:])
        else:
            path = Path('\\\\?\\' + str(path))
    return path


def checked_path(path):
    path = absolute_path(path)
    for part in [*reversed(path.parents), path]:
        info = part.lstat()
        if stat.S_ISLNK(info.st_mode) or getattr(info, 'st_file_attributes', 0) & 0x400:
            raise ValueError('Linked/reparse compilation input is forbidden')
    return path


@dataclass(frozen=True)
class CompiledClass:
    path: Path
    root: Path
    size: int
    sha256: str
    symbols: Symbols


def bounded_class_bytes(path, expected_size):
    if not 10 <= expected_size <= MAX_CLASS_BYTES:
        raise ValueError('Invalid compiled class size')
    with path.open('rb') as stream:
        raw = stream.read(MAX_CLASS_BYTES + 1)
    if len(raw) != expected_size or len(raw) > MAX_CLASS_BYTES:
        raise ValueError('Compiled class changed or exceeds the read bound')
    return raw


def index_classes(roots):
    """Index only bytes present on disk; duplicate/shadow identity never wins."""
    result = {}
    total = 0
    for raw_root in roots:
        root = checked_path(raw_root)
        if not root.is_dir():
            raise ValueError('Compiled test root must be an existing directory')
        for folder, directories, files in os.walk(root, followlinks=False):
            for directory in directories:
                checked_path(Path(folder) / directory)
            for name in sorted(files):
                if not name.endswith('.class'):
                    continue
                path = checked_path(Path(folder) / name)
                info = path.stat()
                if not stat.S_ISREG(info.st_mode) or not 10 <= info.st_size <= MAX_CLASS_BYTES:
                    raise ValueError('Invalid compiled class input')
                total += info.st_size
                if total > MAX_TOTAL_BYTES or len(result) >= MAX_CLASSES:
                    raise ValueError('Compiled test index exceeds its declared bound')
                raw = bounded_class_bytes(path, info.st_size)
                symbols = class_symbols(raw)
                if path.relative_to(root).as_posix() != symbols.name + '.class':
                    raise ValueError('Class identity differs from its file path')
                if symbols.name in result:
                    raise ValueError('Duplicate compiled test class')
                result[symbols.name] = CompiledClass(path, root, len(raw), hashlib.sha256(raw).hexdigest(), symbols)
    if not result:
        raise ValueError('No compiled test classes')
    return result


def select_closure(index, production_names, entry=ENTRY):
    """Traverse actual references, exact Kotlin facades and real nested names.

    All references to other *present compiled test classes* are traversed. JDK,
    production and dependency symbols remain external and must later be bound to
    the exact real JavaExec model. This is deliberately not a runtime verifier.

    SourceFile alone is not a grouping identity: inlined Kotlin bodies may name
    Effects.kt/Comparisons.kt in unrelated callers' generated classes.
    """
    if entry not in index:
        raise ValueError('Actual UI fixture entry class is absent')
    production_names = set(production_names)
    nested = defaultdict(set)
    for name in index:
        package, _, leaf = name.rpartition('/')
        for position, character in enumerate(leaf):
            if character == '$':
                parent = (package + '/' if package else '') + leaf[:position]
                if parent in index:
                    nested[parent].add(name)
    selected = set()
    pending = deque([entry])
    queued = {entry}

    def enqueue(names):
        for candidate in sorted(names):
            if candidate not in queued:
                queued.add(candidate)
                pending.append(candidate)

    while pending:
        name = pending.popleft()
        if name in selected:
            continue
        item = index[name]
        if name in production_names:
            raise ValueError('UI fixture would shadow a production class: ' + name)
        if item.symbols.source is None:
            raise ValueError('Reached test class lacks SourceFile provenance: ' + name)
        selected.add(name)
        enqueue(nested[name])
        if item.symbols.source.endswith('.kt'):
            package = name.rpartition('/')[0]
            prefix = package + '/' if package else ''
            facade = item.symbols.source[:-3] + 'Kt'
            for peer in (prefix + facade, prefix + 'ComposableSingletons$' + facade):
                if peer in index and peer not in selected:
                    enqueue([peer])
        enqueue(item.symbols.references & index.keys())
    return {name: index[name] for name in sorted(selected)}


def verify_unchanged(selected):
    """Call after exporting; a changed class invalidates the whole export."""
    for item in selected.values():
        path = checked_path(item.path)
        raw = bounded_class_bytes(path, item.size)
        if hashlib.sha256(raw).hexdigest() != item.sha256:
            raise ValueError('Compiled UI class changed during export')
