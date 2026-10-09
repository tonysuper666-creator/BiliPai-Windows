#!/usr/bin/env python3
"""Export a genuine own-source host LLVM snapshot; no import or native execution.

The producer calls export_snapshot only after its real llvm target succeeds.
The CMake recipe captures actual source/config after configure before build,
then verifies the same source/config after install before deleting the build.
Export verifies the prebuild identity again after cleanup; postcleanup identity
is never substituted for the actual compiler build source.
Both corresponding-source trees are retained; Git internals and secrets are not.
"""
import argparse
import hashlib
import json
import os
import re
import stat
import subprocess
import tarfile
import tempfile
import urllib.request
from pathlib import Path, PurePosixPath

KIND = 'BILIPAI_HOST_LLVM_SOURCE_SNAPSHOT'
REPOSITORY = 'tonysuper666-creator/BiliPai-Windows'
LLVM_REMOTE = 'https://github.com/llvm/llvm-project.git'
CONFIG_FILES = ('CMakeCache.txt', 'build.ninja', 'CMakeFiles/rules.ninja', 'install_manifest.txt')
SOURCE_FILES = ('actual-source-worktree.json', 'actual-source-ls-tree.bin')
SOURCE_BINDING_STATE = 'PREBUILD_INSTALL_POSTCLEANUP_SOURCE_AND_CONFIG_BOUND'


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def file_sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def json_bytes(value):
    return (json.dumps(value, sort_keys=True, indent=2) + '\n').encode('utf-8')


def write_json(path, value):
    if path.exists() or path.is_symlink():
        raise RuntimeError('Snapshot output already exists')
    path.write_bytes(json_bytes(value))


def owned(path, root, *, directory=False):
    root = root.resolve(strict=True)
    path = Path(path)
    if path.is_symlink():
        raise RuntimeError('Owned snapshot root/file cannot be a symlink')
    resolved = path.resolve(strict=True)
    if resolved != root and root not in resolved.parents:
        raise RuntimeError('Snapshot input escaped owned workspace')
    current = path.absolute()
    while current != root:
        if current.is_symlink():
            raise RuntimeError('Snapshot input has a symlink ancestor')
        if current.parent == current:
            raise RuntimeError('Snapshot root is not an ancestor')
        current = current.parent
    if directory and not resolved.is_dir():
        raise RuntimeError('Expected owned directory')
    if not directory and not resolved.is_file():
        raise RuntimeError('Expected owned regular file')
    return resolved


def relative_name(value):
    if not isinstance(value, str) or not value or '\x00' in value or '\\' in value:
        raise RuntimeError('Unsafe snapshot relative path')
    path = PurePosixPath(value)
    if path.is_absolute() or value != path.as_posix() or any(x in ('', '.', '..') for x in path.parts):
        raise RuntimeError('Snapshot path is not canonical and relative')
    return value


def info(name, mode, size=0, *, kind=tarfile.REGTYPE, link=''):
    row = tarfile.TarInfo(relative_name(name))
    row.uid = row.gid = row.mtime = 0
    row.uname = row.gname = ''
    row.mode, row.size, row.type, row.linkname = mode, size, kind, link
    return row


class HashReader:
    def __init__(self, stream, length):
        self.stream, self.remaining = stream, length
        self.digest = hashlib.sha256()

    def read(self, size=-1):
        if not self.remaining:
            return b''
        size = self.remaining if size < 0 else min(size, self.remaining)
        raw = self.stream.read(size)
        if not raw:
            raise RuntimeError('Snapshot file ended before declared length')
        self.remaining -= len(raw)
        self.digest.update(raw)
        return raw


def add_regular(archive, path, name):
    before = path.lstat()
    if not stat.S_ISREG(before.st_mode) or before.st_mode & 0o6000:
        raise RuntimeError('Snapshot input is not an ordinary regular file')
    with path.open('rb') as stream:
        opened = os.fstat(stream.fileno())
        if (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns) != (opened.st_dev, opened.st_ino, opened.st_size, opened.st_mtime_ns):
            raise RuntimeError('Snapshot input changed while opening')
        reader = HashReader(stream, before.st_size)
        if archive is None:
            while reader.read(1024 * 1024):
                pass
        else:
            archive.addfile(info(name, stat.S_IMODE(before.st_mode), before.st_size), reader)
        after = os.fstat(stream.fileno())
    final = path.lstat()
    identity = (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns)
    if reader.remaining or identity != (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns) or identity != (final.st_dev, final.st_ino, final.st_size, final.st_mtime_ns):
        raise RuntimeError('Snapshot input changed while reading')
    return {'path': name, 'kind': 'file', 'mode': stat.S_IMODE(before.st_mode), 'bytes': before.st_size, 'sha256': reader.digest.hexdigest()}


def pack_tree(archive, root, prefix, *, skip_git=False):
    root = root.resolve(strict=True)
    root_stat = root.stat()
    if root_stat.st_mode & 0o6000:
        raise RuntimeError('Setuid/setgid tree root rejected')
    if archive is not None:
        archive.addfile(info(prefix, stat.S_IMODE(root_stat.st_mode), kind=tarfile.DIRTYPE))
    entries = [{'path': prefix, 'kind': 'directory', 'mode': stat.S_IMODE(root_stat.st_mode), 'bytes': 0}]
    def visit(directory, suffix):
        for path in sorted(directory.iterdir(), key=lambda p: p.name):
            if skip_git and path.name == '.git':
                continue
            relative = suffix + '/' + path.name if suffix else path.name
            name = relative_name(prefix + '/' + relative)
            data = path.lstat()
            if data.st_mode & 0o6000:
                raise RuntimeError('Setuid/setgid source/install entry rejected')
            if stat.S_ISLNK(data.st_mode):
                target = os.readlink(path)
                destination = path.resolve(strict=False)
                if destination != root and root not in destination.parents:
                    raise RuntimeError('Install/worktree symlink escaped its owned tree')
                if '\x00' in target or not target:
                    raise RuntimeError('Unsafe symlink target')
                if archive is not None:
                    archive.addfile(info(name, stat.S_IMODE(data.st_mode), kind=tarfile.SYMTYPE, link=target))
                entries.append({'path': name, 'kind': 'symlink', 'mode': stat.S_IMODE(data.st_mode), 'bytes': 0, 'target': target})
            elif stat.S_ISDIR(data.st_mode):
                if archive is not None:
                    archive.addfile(info(name, stat.S_IMODE(data.st_mode), kind=tarfile.DIRTYPE))
                entries.append({'path': name, 'kind': 'directory', 'mode': stat.S_IMODE(data.st_mode), 'bytes': 0})
                visit(path, relative)
            elif stat.S_ISREG(data.st_mode):
                entries.append(add_regular(archive, path, name))
            else:
                raise RuntimeError('Devices/sockets/special source entries rejected')
    visit(root, '')
    if len(entries) < 2 or len({e['path'] for e in entries}) != len(entries):
        raise RuntimeError('Empty or duplicate snapshot tree')
    return entries


def git(source, *arguments):
    # Source is a freshly cloned public dependency, never a user Git repository.
    environment = {k: v for k, v in os.environ.items() if k not in ('GITHUB_TOKEN', 'GH_TOKEN')}
    return subprocess.check_output(['git', '-C', str(source), *arguments], env=environment)


# This narrow relation is a declared Git materialization, never normalization
# of captured build source. Only ordinary LF-only blobs <=1MiB may qualify.
CRLF_BLOB_BOUND = 1 << 20
CRLF_ATTRIBUTES_BOUND = 64 << 20


def raw_git_blob(raw):
    return hashlib.sha1(b'blob ' + str(len(raw)).encode('ascii') + b'\0' + raw).hexdigest()


def fixed_git_environment():
    environment = {key: value for key, value in os.environ.items()
                   if not key.startswith('GIT_') and key not in ('GITHUB_TOKEN', 'GH_TOKEN')}
    environment.update({'GIT_ATTR_NOSYSTEM': '1', 'GIT_CONFIG_NOSYSTEM': '1',
                        'GIT_CONFIG_GLOBAL': os.devnull, 'GIT_CONFIG_SYSTEM': os.devnull,
                        'GIT_CONFIG_COUNT': '0'})
    return environment


def source_attribute_state(source):
    # No source clone config/attribute modifications; foreign common-dir and
    # info rules cannot qualify the independent fixed-commit EOL relation.
    directory = owned(source / '.git', source, directory=True)
    command = ['git', '-C', str(source), '-c', 'core.attributesFile=' + os.devnull]
    common = subprocess.check_output(command + ['rev-parse', '--path-format=absolute',
                                               '--git-common-dir'], env=fixed_git_environment())
    if Path(common.decode('utf-8').strip()).resolve(strict=True) != directory:
        raise RuntimeError('Fixed EOL attributes require the actual isolated LLVM clone')
    path = directory / 'info/attributes'
    if path.parent.exists() or path.parent.is_symlink():
        owned(path.parent, source, directory=True)
    if path.is_symlink() or not hasattr(os, 'O_NOFOLLOW'):
        raise RuntimeError('Unsafe source info attributes cannot qualify EOL')
    try:
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    except FileNotFoundError:
        return None
    with os.fdopen(fd, 'rb') as stream:
        state = os.fstat(stream.fileno())
        if not stat.S_ISREG(state.st_mode) or state.st_size:
            raise RuntimeError('Nonempty source info attributes cannot qualify EOL')
        return (state.st_dev, state.st_ino, state.st_size, state.st_mtime_ns)


def bounded_source_blob(source, blob):
    command = ['git', '-C', str(source)]
    environment = fixed_git_environment()
    size = subprocess.check_output(command + ['cat-file', '-s', blob], env=environment)
    if not re.fullmatch(rb'(0|[1-9][0-9]{0,6})\n', size) or int(size) > CRLF_BLOB_BOUND:
        raise RuntimeError('Raw materialization blob exceeds 1MiB bound')
    raw = subprocess.check_output(command + ['cat-file', 'blob', blob], env=environment)
    if len(raw) != int(size) or raw_git_blob(raw) != blob:
        raise RuntimeError('Raw materialization Git object differs')
    return raw


def source_raw_attributes(source, expected):
    before = source_attribute_state(source)
    attributes, total = [], 0
    for name, (mode, blob) in sorted(expected.items()):
        if PurePosixPath(name).name != '.gitattributes':
            continue
        if mode not in ('100644', '100755'):
            raise RuntimeError('Nonregular fixed Git attributes cannot qualify EOL')
        raw = bounded_source_blob(source, blob)
        total += len(raw)
        if total > CRLF_ATTRIBUTES_BOUND or len(attributes) >= 10000:
            raise RuntimeError('Complete fixed attributes exceed explicit bound')
        attributes.append((name, mode, blob, raw))
    if source_attribute_state(source) != before:
        raise RuntimeError('Source info attributes changed during fixed EOL lookup')
    return attributes


class FixedCrLfAttributes:
    """Independent attrs-only index; no source checkout/filter driver is used."""
    def __init__(self, loader, scratch, commit):
        if not re.fullmatch(r'[0-9a-f]{40}', commit):
            raise RuntimeError('Invalid fixed attributes commit')
        self.loader, self.scratch, self.commit = loader, scratch, commit
        self.temporary = None

    def __enter__(self):
        return self

    def __exit__(self, kind, value, traceback):
        if self.temporary is not None:
            self.temporary.cleanup()

    def run(self, *arguments, raw=None):
        command = ['git', '-C', str(self.directory), '-c', 'core.attributesFile=' + os.devnull,
                   '-c', 'core.autocrlf=false', '-c', 'core.safecrlf=true', *arguments]
        return subprocess.check_output(command, input=raw, env=fixed_git_environment(), stderr=subprocess.PIPE)

    def initialize(self):
        if self.temporary is not None:
            return
        attributes = self.loader()
        total, rows, names = 0, [], set()
        for name, mode, blob, raw in attributes:
            relative_name(name)
            if (PurePosixPath(name).name != '.gitattributes' or name in names
                    or mode not in ('100644', '100755') or len(raw) > CRLF_BLOB_BOUND
                    or raw_git_blob(raw) != blob):
                raise RuntimeError('Invalid complete raw Git attributes')
            names.add(name)
            total += len(raw)
            if total > CRLF_ATTRIBUTES_BOUND or len(names) > 10000:
                raise RuntimeError('Complete attribute index exceeds explicit bound')
            rows.append({'path': name, 'gitMode': mode, 'gitBlob': blob,
                         'bytes': len(raw), 'sha256': sha(raw)})
        rows.sort(key=lambda row: row['path'])
        self.attributes_sha256 = sha(json_bytes(rows))
        self.temporary = tempfile.TemporaryDirectory(prefix='bilipai-fixed-eol-', dir=self.scratch)
        self.directory = Path(self.temporary.name)
        # Empty template, SHA1 objects, sanitized environment, no original
        # clone/global/system/config/alternates/info attributes/filter drivers.
        self.run('init', '--quiet', '--template=', '--object-format=sha1')
        index = bytearray()
        for name, mode, blob, raw in sorted(attributes):
            stored = self.run('hash-object', '--no-filters', '-w', '--stdin', raw=raw)
            if stored != blob.encode('ascii') + b'\n':
                raise RuntimeError('Scratch raw attribute object changed')
            index.extend(mode.encode('ascii') + b' ' + blob.encode('ascii') + b'\t' + name.encode('utf-8') + b'\0')
        self.run('update-index', '-z', '--index-info', raw=bytes(index))
        self.index_entries = b''.join(row['gitMode'].encode('ascii') + b' ' + row['gitBlob'].encode('ascii')
                                     + b' 0\t' + row['path'].encode('utf-8') + b'\0' for row in rows)
        self.unchanged_index()

    def unchanged_index(self):
        if (self.run('ls-files', '--stage', '-z') != self.index_entries
                or set(path.name for path in self.directory.iterdir()) != {'.git'}
                or (self.directory / '.git/info/attributes').exists()
                or (self.directory / '.git/info/attributes').is_symlink()):
            raise RuntimeError('Independent attribute index/worktree changed')

    def qualify(self, name, mode, blob, raw, observed):
        relative_name(name)
        if (mode not in ('100644', '100755') or len(raw) > CRLF_BLOB_BOUND
                or b'\0' in raw or b'\r' in raw or b'\n' not in raw
                or raw_git_blob(raw) != blob or observed != raw.replace(b'\n', b'\r\n')):
            raise RuntimeError('Not an exact bounded LF-to-CRLF Git materialization')
        self.initialize()
        self.unchanged_index()
        returned = self.run('check-attr', '--cached', '-z', '--all', '--', name)
        fields = returned.split(b'\0')
        if fields[-1] or (len(fields) - 1) % 3:
            raise RuntimeError('Malformed fixed attribute response')
        attributes = {}
        for offset in range(0, len(fields) - 1, 3):
            path, key, value = fields[offset:offset + 3]
            key = key.decode('utf-8')
            if path != name.encode('utf-8') or key in attributes:
                raise RuntimeError('Wrong or duplicate fixed attribute response')
            attributes[key] = value.decode('utf-8')
        # --all omits true unspecified. Attribute key presence, including a
        # literal value "unset"/"unspecified", is never treated as disabled.
        forbidden = {'ident', 'filter', 'working-tree-encoding', 'crlf', 'export-subst', 'binary'}
        if (attributes.get('text') != 'set' or attributes.get('eol') != 'crlf'
                or forbidden.intersection(attributes)):
            raise RuntimeError('Not isolated text=set/eol=crlf without other transforms')
        stored = self.run('hash-object', '--no-filters', '-w', '--stdin', raw=raw)
        if stored != blob.encode('ascii') + b'\n':
            raise RuntimeError('Scratch source object changed')
        # The fresh empty working tree falls back to the COMPLETE index attrs.
        # Builtin conversion must prove these exact bytes under the effective
        # attributes; the string "set" alone does not prove a boolean state.
        # No filter/ident/encoding driver can be active in this scratch repo.
        converted = self.run('cat-file', '--filters', '--path=' + name, blob)
        self.unchanged_index()
        if converted != observed:
            raise RuntimeError('Git builtin materialization differs from exact CRLF bytes')
        return {'path': name, 'kind': 'DECLARED_GIT_TEXT_EOL_CRLF', 'gitMode': mode,
                'attributeSourceCommit': self.commit, 'attributes': attributes,
                'attributeFilesSha256': self.attributes_sha256, 'gitBlob': blob,
                'rawBytes': len(raw), 'rawSha256': sha(raw), 'lfCount': raw.count(b'\n'),
                'observedBytes': len(observed), 'observedSha256': sha(observed),
                'observedGitBlob': raw_git_blob(observed)}


def verify_used_source_relationship(bundle, commit, used, canonical, actual_blobs, *, scratch=None):
    """Recompute bounded CRLF witnesses from retained archive bytes, not labels.

    The caller still verifies every outer/canonical payload and the complete
    captured recursive Git tree. This independent relation rechecks ALL raw
    attributes and each differing actual-used file against that full tree.
    Raw Git canonical source and original captured worktree remain unchanged.
    """
    if set(actual_blobs) != set(canonical):
        raise RuntimeError('EOL relation requires the complete real recursive Git tree')
    for name, (mode, blob) in actual_blobs.items():
        relative_name(name)
        row = canonical[name]
        expected_mode = 0o777 if mode == '120000' else 0o755 if mode == '100755' else 0o644
        if (mode not in ('100644', '100755', '120000') or row.get('gitBlob') != blob
                or row.get('mode') != expected_mode
                or row.get('kind') != ('symlink' if mode == '120000' else 'file')):
            raise RuntimeError('EOL relation canonical blob/mode differs from captured tree')
    differences = {}
    for row in used:
        path = relative_name(row['path'])
        prefix = 'actual-used-source-worktree/'
        if not path.startswith(prefix):
            if path == prefix[:-1] and row['kind'] == 'directory':
                continue
            raise RuntimeError('EOL relation used source has wrong root')
        name = path[len(prefix):]
        original = canonical.get(name)
        if original is None or row['kind'] == 'directory':
            continue
        actual_sha = row.get('sha256') if row['kind'] == 'file' else sha(row['target'].encode('utf-8'))
        if row['kind'] == original['kind'] and actual_sha == original['sha256']:
            continue
        mode, blob = actual_blobs[name]
        if (name in differences or mode not in ('100644', '100755')
                or row['kind'] != 'file' or original['kind'] != 'file'
                or row['mode'] & 0o6000
                or bool(row['mode'] & 0o111) != (mode == '100755')
                or row['bytes'] > CRLF_BLOB_BOUND * 2 or original['bytes'] > CRLF_BLOB_BOUND):
            raise RuntimeError('Actual used tracked source differs beyond bounded regular CRLF relation')
        differences[name] = row
    if not differences:
        return []
    # No extracted source files or original clone filter/config are used.
    # Load every fixed raw .gitattributes, including ancestors outside sparse
    # checkout, plus each candidate raw file from the complete canonical tar.
    required = set(differences) | {name for name in canonical if PurePosixPath(name).name == '.gitattributes'}
    attributes, raw_candidates, seen_required = [], {}, set()
    retained_bytes = 0
    if len(required) > 10000:
        raise RuntimeError('Materialization candidate inventory exceeds bound')
    before = file_sha(bundle)
    with tarfile.open(bundle, 'r:gz') as outer:
        member = outer.getmember('canonical-source/complete-llvm-source.tar.gz')
        if not member.isfile():
            raise RuntimeError('Canonical EOL input is not an ordinary archive member')
        prefix = 'llvm-project-' + commit
        directories = {prefix}
        for name in canonical:
            directories.update(parent.as_posix() for parent in PurePosixPath(prefix + '/' + name).parents
                               if parent.as_posix() != '.')
        seen = set()
        with tarfile.open(fileobj=outer.extractfile(member), mode='r|gz') as archive:
            for item in archive:
                path = relative_name(item.name.rstrip('/') if item.isdir() else item.name)
                if path in seen:
                    raise RuntimeError('Duplicate canonical EOL source member')
                seen.add(path)
                if item.isdir():
                    if path not in directories:
                        raise RuntimeError('Unexpected canonical EOL directory')
                    continue
                if not path.startswith(prefix + '/') or path[len(prefix) + 1:] not in canonical:
                    raise RuntimeError('Unexpected canonical EOL source path')
                name = path[len(prefix) + 1:]
                if name not in required:
                    continue
                row = canonical[name]
                mode, blob = actual_blobs[name]
                if (not item.isfile() or row['kind'] != 'file'
                        or mode not in ('100644', '100755') or item.size != row['bytes']
                        or item.size > CRLF_BLOB_BOUND or item.mode & 0o6000
                        or bool(item.mode & 0o111) != (mode == '100755')):
                    raise RuntimeError('Raw attribute/materialization input length/mode differs')
                raw = archive.extractfile(item).read(CRLF_BLOB_BOUND + 1)
                if len(raw) != item.size or sha(raw) != row['sha256'] or raw_git_blob(raw) != blob:
                    raise RuntimeError('Raw attribute/materialization bytes differ from real Git blob')
                retained_bytes += len(raw)
                if retained_bytes > CRLF_ATTRIBUTES_BOUND:
                    raise RuntimeError('Retained raw attribute/materialization inputs exceed 64MiB')
                seen_required.add(name)
                if name in differences:
                    raw_candidates[name] = raw
                if PurePosixPath(name).name == '.gitattributes':
                    attributes.append((name, mode, blob, raw))
        if (seen_required != required
                or not {prefix + '/' + name for name in canonical} <= seen):
            raise RuntimeError('Full canonical EOL source/attributes omitted')
        witnesses = []
        with FixedCrLfAttributes(lambda: attributes, scratch, commit) as fixed_crlf:
            for name, row in sorted(differences.items()):
                actual = outer.getmember(row['path'])
                if (not actual.isfile() or actual.size != row['bytes'] or actual.mode != row['mode']
                        or actual.size > CRLF_BLOB_BOUND * 2):
                    raise RuntimeError('Actual retained used-source materialization shape differs')
                observed = outer.extractfile(actual).read(CRLF_BLOB_BOUND * 2 + 1)
                if len(observed) != actual.size or sha(observed) != row['sha256']:
                    raise RuntimeError('Actual retained used-source materialization bytes differ')
                mode, blob = actual_blobs[name]
                witnesses.append(fixed_crlf.qualify(name, mode, blob, raw_candidates[name], observed))
    if file_sha(bundle) != before:
        raise RuntimeError('Corresponding-source bundle changed during EOL verification')
    return witnesses


def complete_source_archive(source, destination, commit):
    """Rebuild complete raw Git source; archive substitutions are never trusted.

    The exact-commit public archive supplies bulk bytes. Every retained byte
    must match the actual recursive Git tree. Only fixed export-subst or the
    isolated declared text/eol=crlf relation may rebuild an ordinary raw blob.
    Original archive bytes and actual build captures are never rewritten.
    Extra files, omissions, links/modes and every other mismatch still reject.
    No archive is extracted and no source code is executed here.
    """
    if not re.fullmatch(r'[0-9a-f]{40}', commit):
        raise RuntimeError('Invalid actual LLVM source commit')
    raw_tree = git(source, 'ls-tree', '-r', '-z', '--full-tree', commit)
    expected = {}
    expected_directories = {''}
    for value in raw_tree.split(b'\0'):
        if not value:
            continue
        fields, raw_name = value.split(b'\t', 1)
        mode, kind, object_id = fields.decode('ascii').split(' ')
        name = relative_name(raw_name.decode('utf-8'))
        if mode not in ('100644', '100755', '120000') or kind != 'blob' or not re.fullmatch(r'[0-9a-f]{40}', object_id) or name in expected:
            raise RuntimeError('Unsupported or duplicate complete source Git entry; no source is trimmed')
        expected[name] = (mode, object_id)
        expected_directories.update(x.as_posix() for x in PurePosixPath(name).parents if x.as_posix() != '.')
    if not expected or len(expected) > 1000000:
        raise RuntimeError('Empty or oversized complete LLVM source tree')

    def mismatch(name, expected_id, actual_id, detail):
        # JSON escaping prevents control characters in Git paths becoming log commands.
        label = json.dumps(name, ensure_ascii=True)[:1024]
        return RuntimeError('Canonical source blob mismatch commit=' + commit + ' path=' + label
                            + ' expectedGitBlob=' + expected_id
                            + ' actualGitBlob=' + actual_id + ' ' + detail)

    def fixed_export_subst(name):
        # --source alone still reads info/global/system attributes. Isolate those
        # without writing any configuration or attributes in the real clone.
        environment = {k: v for k, v in os.environ.items()
                       if not k.startswith('GIT_') and k not in ('GITHUB_TOKEN', 'GH_TOKEN')}
        environment.update({'GIT_ATTR_NOSYSTEM': '1', 'GIT_CONFIG_NOSYSTEM': '1',
                            'GIT_CONFIG_GLOBAL': os.devnull, 'GIT_CONFIG_SYSTEM': os.devnull,
                            'GIT_CONFIG_COUNT': '0'})
        command = ['git', '-C', str(source), '-c', 'core.attributesFile=' + os.devnull]
        git_directory = owned(source / '.git', source, directory=True)
        common = subprocess.check_output(command + ['rev-parse', '--path-format=absolute',
                                                     '--git-common-dir'], env=environment)
        if Path(common.decode('utf-8').strip()).resolve(strict=True) != git_directory:
            raise RuntimeError('Attribute lookup requires the actual isolated fresh LLVM clone')
        attribute_path = git_directory / 'info/attributes'
        def info_state():
            if attribute_path.parent.exists() or attribute_path.parent.is_symlink():
                owned(attribute_path.parent, source, directory=True)
            if attribute_path.is_symlink() or not hasattr(os, 'O_NOFOLLOW'):
                raise RuntimeError('Unsafe source info attributes cannot qualify export-subst')
            try:
                fd = os.open(attribute_path, os.O_RDONLY | os.O_NOFOLLOW)
            except FileNotFoundError:
                return None
            with os.fdopen(fd, 'rb') as stream:
                data = os.fstat(stream.fileno())
                if not stat.S_ISREG(data.st_mode) or data.st_size:
                    raise RuntimeError('Nonempty source info attributes cannot qualify export-subst')
                return (data.st_dev, data.st_ino, data.st_size, data.st_mtime_ns)
        before = info_state()
        attribute = subprocess.check_output(command + ['check-attr', '-z', '--source=' + commit,
                                                       'export-subst', '--', name], env=environment)
        if info_state() != before:
            raise RuntimeError('Source info attributes changed during fixed-commit lookup')
        return attribute == name.encode('utf-8') + b'\0export-subst\0set\0'

    entries, seen, corrections, crlf_corrections = [], set(), [], []
    source_bound = 8 << 30
    prefix = 'llvm-project-' + commit
    url = 'https://codeload.github.com/llvm/llvm-project/tar.gz/' + commit
    downloaded_digest = hashlib.sha256()
    expanded_count = 0
    exported_count = 0
    # Both anonymous scratch streams are confined to the existing owned stage.
    with (FixedCrLfAttributes(lambda: source_raw_attributes(source, expected), destination.parent, commit) as fixed_crlf,
          tempfile.TemporaryFile(dir=destination.parent) as downloaded):
        with urllib.request.urlopen(url, timeout=180) as response:
            if response.status != 200 or response.geturl() != url:
                raise RuntimeError('Exact public corresponding source archive URL changed')
            count = 0
            while raw := response.read(1 << 20):
                count += len(raw)
                if count > source_bound:
                    raise RuntimeError('Complete source archive exceeds the explicit safety bound')
                downloaded_digest.update(raw)
                downloaded.write(raw)
        downloaded.seek(0)
        with tarfile.open(fileobj=downloaded, mode='r|gz') as archive, destination.open('xb') as output:
            with tarfile.open(fileobj=output, mode='w|gz') as canonical:
                canonical.addfile(info(prefix, 0o755, kind=tarfile.DIRTYPE))
                for directory in sorted(expected_directories - {''}):
                    canonical.addfile(info(prefix + '/' + directory, 0o755, kind=tarfile.DIRTYPE))
                for member in archive:
                    original_name = member.name.rstrip('/')
                    if original_name == prefix and member.isdir():
                        continue
                    if not original_name.startswith(prefix + '/'):
                        raise RuntimeError('Canonical LLVM archive path escaped its commit prefix')
                    name = relative_name(original_name.removeprefix(prefix + '/'))
                    if member.isdir():
                        if name not in expected_directories:
                            raise RuntimeError('Unexpected corresponding source archive directory')
                        continue
                    if name not in expected or name in seen:
                        raise RuntimeError('Extra or duplicate corresponding source archive file')
                    mode, object_id = expected[name]
                    if mode == '120000':
                        if not member.issym():
                            raise RuntimeError('Canonical source symlink kind differs from Git')
                        raw = member.linkname.encode('utf-8')
                        if not raw or b'\0' in raw or len(raw) > 8192:
                            raise RuntimeError('Unsupported canonical source symlink')
                        if expanded_count + len(raw) > source_bound or exported_count + len(raw) > source_bound:
                            raise RuntimeError('Complete symlink source exceeds the explicit safety bound')
                        expanded_count += len(raw)
                        exported_count += len(raw)
                        git_sha = hashlib.sha1(b'blob ' + str(len(raw)).encode('ascii') + b'\0' + raw).hexdigest()
                        if git_sha != object_id:
                            raise mismatch(name, object_id, git_sha, 'symlink bytes rejected')
                        canonical.addfile(info(prefix + '/' + name, 0o777, kind=tarfile.SYMTYPE, link=member.linkname))
                        entries.append({'path': name, 'kind': 'symlink', 'mode': 0o777, 'bytes': len(raw), 'target': member.linkname, 'sha256': sha(raw), 'gitBlob': object_id})
                    else:
                        if not member.isfile() or member.mode & 0o6000 or bool(member.mode & 0o111) != (mode == '100755'):
                            raise RuntimeError('Canonical source file kind/mode differs from Git')
                        if member.size < 0 or exported_count + member.size > source_bound or expanded_count + member.size > source_bound:
                            raise RuntimeError('Complete expanded source exceeds the explicit safety bound')
                        git_digest = hashlib.sha1(b'blob ' + str(member.size).encode('ascii') + b'\0')
                        digest = hashlib.sha256()
                        count = 0
                        with tempfile.SpooledTemporaryFile(max_size=8 << 20, dir=destination.parent) as material:
                            with archive.extractfile(member) as stream:
                                while raw := stream.read(1 << 20):
                                    count += len(raw)
                                    if count > member.size:
                                        raise RuntimeError('Canonical source file exceeds declared length')
                                    git_digest.update(raw)
                                    digest.update(raw)
                                    material.write(raw)
                            if count != member.size:
                                raise RuntimeError('Incomplete canonical source file')
                            exported_count += count
                            expanded_count += count
                            git_sha = git_digest.hexdigest()
                            length = member.size
                            if git_sha != object_id:
                                export_subst = fixed_export_subst(name)
                                raw_blob = bounded_source_blob(source, object_id)
                                if export_subst:
                                    corrections.append({'path': name, 'attribute': 'export-subst',
                                                        'attributeSourceCommit': commit,
                                                        'exportedGitBlob': git_sha, 'gitBlob': object_id,
                                                        'exportedBytes': count, 'rawBytes': len(raw_blob),
                                                        'rawSha256': sha(raw_blob)})
                                else:
                                    if count > 2 * CRLF_BLOB_BOUND:
                                        raise mismatch(name, object_id, git_sha, 'EOL materialization exceeds 2MiB bound')
                                    material.seek(0)
                                    observed = material.read(2 * CRLF_BLOB_BOUND + 1)
                                    try:
                                        crlf_corrections.append(fixed_crlf.qualify(name, mode, object_id, raw_blob, observed))
                                    except RuntimeError as failure:
                                        raise mismatch(name, object_id, git_sha, 'not an exact isolated declared CRLF materialization') from failure
                                expanded_count += len(raw_blob) - count
                                if expanded_count > source_bound:
                                    raise RuntimeError('Complete raw Git source exceeds the explicit safety bound')
                                material.seek(0)
                                material.truncate()
                                material.write(raw_blob)
                                length = len(raw_blob)
                                digest = hashlib.sha256(raw_blob)
                            material.seek(0)
                            canonical.addfile(info(prefix + '/' + name, 0o755 if mode == '100755' else 0o644, length), material)
                            entries.append({'path': name, 'kind': 'file', 'mode': 0o755 if mode == '100755' else 0o644, 'bytes': length, 'sha256': digest.hexdigest(), 'gitBlob': object_id})
                    seen.add(name)
    if seen != set(expected):
        raise RuntimeError('Corresponding source archive omitted tracked source; no incomplete snapshot is accepted')
    entries.sort(key=lambda row: row['path'])
    corrections.sort(key=lambda row: row['path'])
    crlf_corrections.sort(key=lambda row: row['path'])
    materialization = {'method': 'RAW_GIT_BLOB_VERIFIED_CANONICAL_SOURCE',
                       'publicArchiveUrl': url, 'publicArchiveSha256': downloaded_digest.hexdigest(),
                       'sourceCommit': commit, 'sourceLsTreeSha256': sha(raw_tree),
                       'exportSubstRawGitCorrections': corrections,
                       'declaredCrLfRawGitCorrections': crlf_corrections}
    return entries, sha(raw_tree), materialization

def source_identity(source, workspace):
    source = owned(source, workspace, directory=True)
    if source != workspace / 'sources/llvm':
        raise RuntimeError('Source capture requires the actual fixed LLVM checkout')
    def metadata():
        commit = git(source, 'rev-parse', 'HEAD').decode('ascii').strip()
        tree = git(source, 'rev-parse', 'HEAD^{tree}').decode('ascii').strip()
        remote = git(source, 'remote', 'get-url', 'origin').decode('utf-8').strip()
        status = git(source, 'status', '--porcelain', '-z', '--untracked-files=all')
        if (not re.fullmatch(r'[0-9a-f]{40}', commit) or not re.fullmatch(r'[0-9a-f]{40}', tree)
                or remote != LLVM_REMOTE or status):
            raise RuntimeError('LLVM source is dirty or is not the actual public commit')
        raw_tree = git(source, 'ls-tree', '-r', '-z', '--full-tree', commit)
        if not raw_tree:
            raise RuntimeError('LLVM source recursive Git tree is missing')
        return {'sourceCommit': commit, 'sourceTree': tree, 'sourceRemote': remote,
            'gitStatusPorcelainZHex': status.hex(), 'completeSourceLsTreeSha256': sha(raw_tree)}, raw_tree
    measured, raw_tree = metadata()
    # Hash the actual sparse worktree as well as the Git tree. This catches
    # skip-worktree/ignored changes that a clean porcelain alone cannot prove.
    entries = pack_tree(None, source, 'actual-used-source-worktree', skip_git=True)
    final, final_tree = metadata()
    if final != measured or final_tree != raw_tree:
        raise RuntimeError('LLVM source Git identity changed during capture')
    measured.update({'schema': 1, 'actualSourceDirectory': str(source),
        'actualSourceWorktreeManifestSha256': sha(json_bytes(entries)),
        'actualSourceWorktreeEntries': len(entries)})
    return measured, entries, raw_tree


def configuration_assertions(build_directory, source, install, cache_raw):
    cache_values = {}
    for line in cache_raw.decode('utf-8').splitlines():
        if line and not line.startswith(('#', '//')) and '=' in line and ':' in line.split('=', 1)[0]:
            key, value = line.split('=', 1)
            name = key.split(':', 1)[0]
            if name in cache_values:
                raise RuntimeError('Duplicate actual CMake cache key')
            cache_values[name] = value
    expected = {'CMAKE_BUILD_TYPE': 'Release', 'CMAKE_INSTALL_PREFIX': str(install),
        'CMAKE_HOME_DIRECTORY': str(source / 'llvm'), 'CMAKE_CACHEFILE_DIR': str(build_directory),
        'LLVM_INSTALL_TOOLCHAIN_ONLY': 'ON', 'LLVM_TARGETS_TO_BUILD': 'AArch64;X86;NVPTX',
        'LLVM_ENABLE_PROJECTS': 'clang;lld', 'CLANG_DEFAULT_RTLIB': 'compiler-rt',
        'CLANG_DEFAULT_UNWINDLIB': 'libunwind', 'CLANG_DEFAULT_CXX_STDLIB': 'libc++',
        'CLANG_DEFAULT_LINKER': 'lld', 'LLD_DEFAULT_LD_LLD_IS_MINGW': 'ON',
        'LLVM_ENABLE_LTO': 'OFF', 'LLVM_ENABLE_ASSERTIONS': 'OFF', 'LLVM_ENABLE_PIC': 'OFF',
        'LLVM_LINK_LLVM_DYLIB': 'OFF', 'LLVM_BUILD_LLVM_DYLIB': 'OFF', 'BUILD_SHARED_LIBS': 'OFF'}
    if any(cache_values.get(key) != value for key, value in expected.items()):
        raise RuntimeError('Actual LLVM source/build configuration differs from the fixed cold build')
    return expected


def verify_source_preflight(preflight, identity, *, canonical_entries=None, used_materializations=None):
    """Validate an earlier stage observation; never replace actual byte gates."""
    required = {'schema', 'stage', 'actualBuildSourceSha256', 'sourceCommit', 'sourceTree',
        'completeSourceLsTreeSha256', 'actualUsedSourceTreeManifestSha256',
        'completeCanonicalSourceManifestSha256', 'canonicalArchiveSha256Observed',
        'usedBundleSha256Observed', 'materializationSha256Observed',
        'actualUsedSourceCrLfMaterializations', 'temporaryMaterialsRetained', 'canonicalCacheReused'}
    if (not isinstance(preflight, dict) or set(preflight) != required
            or type(preflight.get('schema')) is not int or preflight['schema'] != 1
            or preflight.get('stage') != 'ACTUAL_CONFIGURED_PREBUILD_CANONICAL_AND_USED_BYTES_CHECKED'
            or preflight.get('actualBuildSourceSha256') != sha(json_bytes(identity))
            or preflight.get('sourceCommit') != identity.get('sourceCommit')
            or preflight.get('sourceTree') != identity.get('sourceTree')
            or preflight.get('completeSourceLsTreeSha256') != identity.get('completeSourceLsTreeSha256')
            or preflight.get('actualUsedSourceTreeManifestSha256') != identity.get('actualSourceWorktreeManifestSha256')
            or not isinstance(preflight.get('actualUsedSourceCrLfMaterializations'), list)
            or len(preflight['actualUsedSourceCrLfMaterializations']) > 10000
            or preflight.get('temporaryMaterialsRetained') is not False
            or preflight.get('canonicalCacheReused') is not False):
        raise RuntimeError('Actual prebuild source preflight missing or bound to another source')
    for key in ('actualBuildSourceSha256', 'completeSourceLsTreeSha256',
                'actualUsedSourceTreeManifestSha256', 'completeCanonicalSourceManifestSha256',
                'canonicalArchiveSha256Observed', 'usedBundleSha256Observed', 'materializationSha256Observed'):
        if not isinstance(preflight.get(key), str) or not re.fullmatch(r'[0-9a-f]{64}', preflight[key]):
            raise RuntimeError('Prebuild source observation digest shape differs')
    if canonical_entries is not None:
        if preflight['completeCanonicalSourceManifestSha256'] != sha(json_bytes(canonical_entries)):
            raise RuntimeError('Actual final canonical source manifest differs from prebuild preflight')
    if used_materializations is not None:
        if preflight['actualUsedSourceCrLfMaterializations'] != used_materializations:
            raise RuntimeError('Actual final used-source byte relations differ from prebuild preflight')


def preflight_configured_source(source, workspace, identity, entries, raw_tree):
    """Run at the actual configure->capture->build dependency before LLVM build.

    Temporary canonical/used archives are discarded; these are observations at
    prebuild, not retained future byte-proof assets or a reusable cache. Actual
    postinstall/export and independent importer/upload gates all run again.
    """
    with tempfile.TemporaryDirectory(prefix='bilipai-host-preflight-', dir=workspace) as temporary:
        stage = owned(Path(temporary), workspace, directory=True)
        canonical = stage / 'complete-llvm-source.tar.gz'
        full_entries, tree_sha, materialization = complete_source_archive(source, canonical, identity['sourceCommit'])
        if (tree_sha != identity['completeSourceLsTreeSha256'] or tree_sha != sha(raw_tree)
                or sha(json_bytes(entries)) != identity['actualSourceWorktreeManifestSha256']):
            raise RuntimeError('Configured source preflight differs from actual prebuild tree/worktree')
        actual_blobs = {}
        for value in raw_tree.split(b'\0'):
            if value:
                fields, raw_name = value.split(b'\t', 1)
                mode, kind, blob = fields.decode('ascii').split(' ')
                name = relative_name(raw_name.decode('utf-8'))
                if kind != 'blob' or name in actual_blobs:
                    raise RuntimeError('Prebuild recursive tree contains unsupported/duplicate entries')
                actual_blobs[name] = (mode, blob)
        bundle = stage / 'prebuild-canonical-and-used-source.tar.gz'
        with tarfile.open(bundle, 'w:gz') as archive:
            add_regular(archive, canonical, 'canonical-source/complete-llvm-source.tar.gz')
            used = pack_tree(archive, source, 'actual-used-source-worktree', skip_git=True)
        if used != entries:
            raise RuntimeError('Actual configured worktree changed during prebuild source preflight')
        witnesses = verify_used_source_relationship(bundle, identity['sourceCommit'], used,
            {row['path']: row for row in full_entries}, actual_blobs, scratch=stage)
        observed = {'schema': 1, 'stage': 'ACTUAL_CONFIGURED_PREBUILD_CANONICAL_AND_USED_BYTES_CHECKED',
            'actualBuildSourceSha256': sha(json_bytes(identity)),
            'sourceCommit': identity['sourceCommit'], 'sourceTree': identity['sourceTree'],
            'completeSourceLsTreeSha256': tree_sha,
            'actualUsedSourceTreeManifestSha256': sha(json_bytes(used)),
            'completeCanonicalSourceManifestSha256': sha(json_bytes(full_entries)),
            'canonicalArchiveSha256Observed': file_sha(canonical),
            'usedBundleSha256Observed': file_sha(bundle),
            'materializationSha256Observed': sha(json_bytes(materialization)),
            'actualUsedSourceCrLfMaterializations': witnesses,
            'temporaryMaterialsRetained': False, 'canonicalCacheReused': False}
        verify_source_preflight(observed, identity, canonical_entries=full_entries, used_materializations=witnesses)
    return observed


def verify_capture(directory, workspace, helper_sha, stage, names):
    directory = owned(directory, workspace, directory=True)
    receipt_path = owned(directory / 'config-capture-receipt.json', workspace)
    receipt = json.loads(receipt_path.read_text(encoding='utf-8'))
    if (receipt.get('schema') != 2 or receipt.get('kind') != KIND
            or receipt.get('stage') != stage or receipt.get('sourceBindingSchema') != 1
            or receipt.get('helperSha256') != helper_sha
            or receipt.get('workspace') != str(workspace)
            or receipt.get('actualBuildDirectory') != str(workspace / 'build-x64/toolchain/llvm-prefix/src/llvm-build')
            or receipt.get('actualSourceDirectory') != str(workspace / 'sources/llvm')
            or len(receipt.get('files', [])) != len(names)
            or {row.get('path') for row in receipt['files']} != set(names)):
        raise RuntimeError('Actual prebuild/install source capture receipt is missing or mismatched')
    for row in receipt['files']:
        path = owned(directory / relative_name(row['path']), workspace)
        if path.stat().st_size != row['bytes'] or file_sha(path) != row['sha256']:
            raise RuntimeError('Captured actual source/config files changed')
    identity = receipt.get('actualBuildSource', {})
    entries = json.loads((directory / SOURCE_FILES[0]).read_text(encoding='utf-8'))
    if (identity.get('schema') != 1 or identity.get('gitStatusPorcelainZHex') != ''
            or identity.get('actualSourceDirectory') != str(workspace / 'sources/llvm')
            or identity.get('actualSourceWorktreeManifestSha256') != sha(json_bytes(entries))
            or identity.get('actualSourceWorktreeEntries') != len(entries)
            or identity.get('completeSourceLsTreeSha256') != file_sha(directory / SOURCE_FILES[1])):
        raise RuntimeError('Actual build source identity is not bound to captured source bytes')
    assertions = configuration_assertions(Path(receipt['actualBuildDirectory']),
        workspace / 'sources/llvm', workspace / 'clang-root', (directory / 'CMakeCache.txt').read_bytes())
    if assertions != receipt.get('resolvedConfigurationAssertions'):
        raise RuntimeError('Captured source/build configuration assertions changed')
    verify_source_preflight(receipt.get('sourcePreflight'), identity)
    return receipt


def capture_config(build_directory, source, workspace, output, stage):
    workspace = workspace.resolve(strict=True)
    expected = workspace / 'build-x64/toolchain/llvm-prefix/src/llvm-build'
    build_directory = owned(build_directory, workspace, directory=True)
    source = owned(source, workspace, directory=True)
    if (build_directory != expected or source != workspace / 'sources/llvm'
            or output.absolute() != workspace / 'build-x64/bilipai-host-llvm-config'):
        raise RuntimeError('Config/source capture requires the actual fixed LLVM paths')
    helper_sha = file_sha(Path(__file__))
    before = None
    if stage == 'prebuild':
        if output.exists() or output.is_symlink():
            raise RuntimeError('Prebuild capture refuses an existing destination')
        output.mkdir()
        destination_root = output / 'prebuild'
        destination_root.mkdir()
        names = CONFIG_FILES[:-1]
        stage_name = 'AFTER_CONFIGURATION_BEFORE_REAL_LLVM_BUILD'
    elif stage == 'postinstall':
        output = owned(output, workspace, directory=True)
        before = verify_capture(output / 'prebuild', workspace, helper_sha,
            'AFTER_CONFIGURATION_BEFORE_REAL_LLVM_BUILD', CONFIG_FILES[:-1] + SOURCE_FILES)
        destination_root = output
        if any((output / name).exists() or (output / name).is_symlink()
               for name in CONFIG_FILES + SOURCE_FILES + ('config-capture-receipt.json',)):
            raise RuntimeError('Postinstall capture refuses an existing destination')
        names = CONFIG_FILES
        stage_name = 'AFTER_REAL_LLVM_INSTALL_BEFORE_RECIPE_CLEANUP'
    else:
        raise RuntimeError('Unsupported actual source capture stage')
    identity, entries, raw_tree = source_identity(source, workspace)
    assertions = configuration_assertions(build_directory, source, workspace / 'clang-root',
        owned(build_directory / 'CMakeCache.txt', workspace).read_bytes())
    if before and (identity != before['actualBuildSource']
            or assertions != before['resolvedConfigurationAssertions']):
        raise RuntimeError('LLVM install source/config differs from the actual prebuild capture')
    rows = []
    for name in names:
        path = owned(build_directory / name, workspace)
        raw = path.read_bytes()
        if before and name in CONFIG_FILES[:-1]:
            previous = next(row for row in before['files'] if row['path'] == name)
            if sha(raw) != previous['sha256'] or len(raw) != previous['bytes']:
                raise RuntimeError('LLVM build/config changed between prebuild and actual install')
        destination = destination_root / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(raw)
        rows.append({'path': name, 'bytes': len(raw), 'sha256': sha(raw)})
    for name, raw in zip(SOURCE_FILES, (json_bytes(entries), raw_tree)):
        (destination_root / name).write_bytes(raw)
        rows.append({'path': name, 'bytes': len(raw), 'sha256': sha(raw)})
    if before:
        preflight = before['sourcePreflight']
    else:
        preflight = preflight_configured_source(source, workspace, identity, entries, raw_tree)
        # The potentially long byte preflight must not bind stale configure
        # payloads; compare the actual build files with their retained capture.
        for row in rows:
            if row['path'] in CONFIG_FILES[:-1]:
                path = owned(build_directory / row['path'], workspace)
                if path.stat().st_size != row['bytes'] or file_sha(path) != row['sha256']:
                    raise RuntimeError('Actual configured LLVM files changed during source preflight')
    verify_source_preflight(preflight, identity)
    final, final_entries, final_tree = source_identity(source, workspace)
    if final != identity or final_entries != entries or final_tree != raw_tree:
        raise RuntimeError('LLVM source changed while writing actual capture')
    receipt = {'schema': 2, 'kind': KIND, 'stage': stage_name, 'sourceBindingSchema': 1,
        'actualBuildDirectory': str(build_directory), 'actualSourceDirectory': str(source),
        'workspace': str(workspace), 'helperSha256': helper_sha, 'files': rows,
        'actualBuildSource': identity, 'resolvedConfigurationAssertions': assertions,
        'sourcePreflight': preflight}
    if before:
        receipt['prebuildCaptureReceiptSha256'] = file_sha(output / 'prebuild/config-capture-receipt.json')
    write_json(destination_root / 'config-capture-receipt.json', receipt)


def export_snapshot(*, workspace, output, repository_root, fixed, snapshot_inputs, container_image, build_command, log):
    workspace = workspace.resolve(strict=True)
    output = output.resolve(strict=True)
    repository_root = repository_root.resolve(strict=True)
    source = owned(workspace / 'sources/llvm', workspace, directory=True)
    install = owned(workspace / 'clang-root', workspace, directory=True)
    config = owned(workspace / 'build-x64/bilipai-host-llvm-config', workspace, directory=True)
    recipe = owned(workspace / fixed['recipeArchivePrefix'], workspace, directory=True)
    original_archive = owned(workspace / 'archives' / next(row['fileName'] for row in fixed['archives'] if row['kind'] == 'recipes'), workspace)
    if file_sha(original_archive) != next(row['sha256'] for row in fixed['archives'] if row['kind'] == 'recipes'):
        raise RuntimeError('Fixed complete recipe archive bytes changed')
    capture = verify_capture(config, workspace, snapshot_inputs['helperSha256'],
        'AFTER_REAL_LLVM_INSTALL_BEFORE_RECIPE_CLEANUP', CONFIG_FILES + SOURCE_FILES)
    prebuild = verify_capture(config / 'prebuild', workspace, snapshot_inputs['helperSha256'],
        'AFTER_CONFIGURATION_BEFORE_REAL_LLVM_BUILD', CONFIG_FILES[:-1] + SOURCE_FILES)
    prebuild_sha = file_sha(config / 'prebuild/config-capture-receipt.json')
    if (capture.get('prebuildCaptureReceiptSha256') != prebuild_sha
            or capture['sourcePreflight'] != prebuild['sourcePreflight']
            or capture['actualBuildSource'] != prebuild['actualBuildSource']
            or capture['resolvedConfigurationAssertions'] != prebuild['resolvedConfigurationAssertions']):
        raise RuntimeError('Installed LLVM provenance is not the actual prebuild source/config')
    for name in CONFIG_FILES[:-1] + SOURCE_FILES:
        before_row = next(row for row in prebuild['files'] if row['path'] == name)
        after_row = next(row for row in capture['files'] if row['path'] == name)
        if before_row != after_row:
            raise RuntimeError('Captured installed source/config bytes differ from prebuild')
    postcleanup, postcleanup_entries, postcleanup_tree = source_identity(source, workspace)
    if postcleanup != prebuild['actualBuildSource']:
        raise RuntimeError('Recipe cleanup changed LLVM source; no postcleanup identity substitution is allowed')
    expected_cache = capture['resolvedConfigurationAssertions']
    binding = {'schema': 1, 'state': SOURCE_BINDING_STATE,
        'prebuild': prebuild['actualBuildSource'], 'postinstall': capture['actualBuildSource'],
        'postcleanup': postcleanup, 'prebuildCaptureReceiptSha256': prebuild_sha,
        'postinstallCaptureReceiptSha256': file_sha(config / 'config-capture-receipt.json'),
        'actualBuildDirectory': capture['actualBuildDirectory'],
        'actualSourceDirectory': str(source), 'resolvedConfigurationAssertions': expected_cache}
    for value in (config / 'install_manifest.txt').read_text(encoding='utf-8').splitlines():
        path = Path(value)
        if not path.is_absolute() or (path != install and install not in path.parents) or not path.exists():
            raise RuntimeError('Actual LLVM install manifest escaped or has missing outputs')
    # Build provenance comes from the actual prebuild/install capture, never
    # from the source identity left behind by reset/restore cleanup.
    built_source = prebuild['actualBuildSource']
    commit, tree, remote = (built_source[key] for key in ('sourceCommit', 'sourceTree', 'sourceRemote'))
    dirty = built_source['gitStatusPorcelainZHex']
    source_tag, producer_commit = os.environ.get('GITHUB_REF_NAME', ''), os.environ.get('GITHUB_SHA', '')
    run_id, run_attempt = os.environ.get('GITHUB_RUN_ID', ''), os.environ.get('GITHUB_RUN_ATTEMPT', '')
    if (os.environ.get('GITHUB_ACTIONS') != 'true' or os.environ.get('GITHUB_REPOSITORY') != REPOSITORY
            or os.environ.get('GITHUB_EVENT_NAME') != 'workflow_dispatch' or os.environ.get('GITHUB_REF_TYPE') != 'tag'
            or not re.fullmatch(r'rtx-source-[A-Za-z0-9][A-Za-z0-9._-]{0,100}', source_tag)
            or not re.fullmatch(r'[0-9a-f]{40}', producer_commit)
            or not run_id.isdigit() or int(run_id) <= 0 or not run_attempt.isdigit() or int(run_attempt) <= 0):
        raise RuntimeError('Only the explicit own source-tag build may export a reusable host snapshot')
    if sha((repository_root / snapshot_inputs['helperSourcePath']).read_bytes()) != snapshot_inputs['helperSha256']:
        raise RuntimeError('Reviewed exporter helper changed')
    patched_recipe = recipe / 'toolchain/llvm/llvm.cmake'
    if file_sha(patched_recipe) != snapshot_inputs['recipeEdits'][0]['afterSha256Bytes']:
        raise RuntimeError('Config capture recipe identity changed')
    # All names are fixed and outputs are exclusively created; no overwrite/retry.
    names = ('host-llvm-install.tar.gz', 'host-llvm-corresponding-source.tar.gz',
             'host-llvm-snapshot-manifest.json', 'host-llvm-source-snapshot-descriptor.json')
    if any((output / name).exists() or (output / name).is_symlink() for name in names):
        raise RuntimeError('Host snapshot output already exists')
    stage = workspace / 'host-llvm-snapshot-materials'
    if stage.exists() or stage.is_symlink():
        raise RuntimeError('Host source staging output already exists')
    stage.mkdir()
    canonical = stage / 'complete-llvm-source.tar.gz'
    full_source_entries, full_ls_tree_sha, materialization = complete_source_archive(source, canonical, commit)
    if full_ls_tree_sha != built_source['completeSourceLsTreeSha256']:
        raise RuntimeError('Complete canonical source is not the actual prebuild Git tree')
    full_by_path = {row['path']: row for row in full_source_entries}
    with tarfile.open(output / names[0], 'w:gz') as archive:
        install_entries = pack_tree(archive, install, 'host-install')
    stage_log = stage / 'host-llvm-stage.log'
    stage_log.write_bytes(log.read_bytes())
    source_entries = []
    with tarfile.open(output / names[1], 'w:gz') as archive:
        source_entries.append(add_regular(archive, canonical, 'canonical-source/complete-llvm-source.tar.gz'))
        used_entries = pack_tree(archive, source, 'actual-used-source-worktree', skip_git=True)
        source_entries.extend(used_entries)
        source_entries.extend(pack_tree(archive, config, 'actual-config-capture'))
        source_entries.extend(pack_tree(archive, recipe, 'actual-modified-build-recipes', skip_git=True))
        source_entries.append(add_regular(archive, original_archive, 'fixed-recipe-archive/' + original_archive.name))
        source_entries.append(add_regular(archive, stage_log, 'actual-host-build-log/host-llvm-stage.log'))
        for path in ('desktop/tools/native/build-mpv-rtx-core-runtime.py', snapshot_inputs['helperSourcePath'],
                     'desktop/third-party/libmpv/build/rtx-core-v1/fixed-inputs.json',
                     'desktop/third-party/libmpv/build/rtx-core-v1/host-llvm-snapshot-inputs.json'):
            source_entries.append(add_regular(archive, owned(repository_root / path, repository_root), 'own-producing-source/' + path))
    actual_blobs = {}
    for value in postcleanup_tree.split(b'\0'):
        if value:
            fields, raw_name = value.split(b'\t', 1)
            mode, kind, blob = fields.decode('ascii').split(' ')
            name = relative_name(raw_name.decode('utf-8'))
            if kind != 'blob' or name in actual_blobs:
                raise RuntimeError('Actual captured source tree contains unsupported/duplicate entries')
            actual_blobs[name] = (mode, blob)
    used_materializations = verify_used_source_relationship(output / names[1], commit,
        used_entries, full_by_path, actual_blobs, scratch=stage)
    final_source, final_entries, final_tree = source_identity(source, workspace)
    if (final_source != built_source or final_entries != postcleanup_entries
            or final_tree != postcleanup_tree or used_entries != postcleanup_entries):
        raise RuntimeError('Actual built LLVM source changed during snapshot export')
    # Compare deterministic full inventories/byte relations, never separately
    # generated gzip hashes. Final retained source assets remain authoritative.
    verify_source_preflight(prebuild['sourcePreflight'], built_source,
        canonical_entries=full_source_entries, used_materializations=used_materializations)
    licenses = [row for row in full_source_entries if row['kind'] == 'file'
                and (PurePosixPath(row['path']).name.upper().startswith(('LICENSE', 'COPYING', 'NOTICE')))]
    if not any(row['path'] in ('LICENSE.TXT', 'llvm/LICENSE.TXT') for row in licenses):
        raise RuntimeError('Complete LLVM corresponding source license is missing')
    manifest = {'schema': 2, 'kind': KIND, 'snapshotStatus': 'BUILT_FROM_SOURCE_THIS_RUN_EXPORT_ONLY',
        'sourceBindingSchema': 1, 'actualSourceBuildBinding': binding,
        'actualSourceBuildBindingSha256': sha(json_bytes(binding)),
        'sourceCommit': commit, 'sourceTree': tree, 'sourceRemote': remote,
        'actualSourcePostBuildGitStatus': dirty, 'completeSourceLsTreeSha256': full_ls_tree_sha,
        'completeCanonicalSourceEntries': full_source_entries, 'canonicalSourceArchiveSha256': file_sha(canonical),
        'canonicalSourceMaterialization': materialization,
        'actualUsedSourceCrLfMaterializations': used_materializations,
        'actualInstallEntries': install_entries, 'actualCorrespondingSourceBundleEntries': source_entries,
        'actualUsedSourceTreeManifestSha256': sha(json_bytes(used_entries)),
        'actualInstallTreeManifestSha256': sha(json_bytes(install_entries)),
        'completeCanonicalSourceManifestSha256': sha(json_bytes(full_source_entries)),
        'completeLicenseEntries': licenses, 'configCaptureReceipt': capture,
        'prebuildConfigCaptureReceipt': prebuild,
        'resolvedConfigurationAssertions': expected_cache,
        'pathSemantics': 'POSIX case-sensitive paths; exact names retained. No archive is extracted here. An importer must validate all entries/links and require the original exact absolute prefix.',
        'originalAbsoluteWorkspace': str(workspace), 'originalAbsoluteInstallPrefix': str(install),
        'containerImage': container_image, 'recipeCommit': fixed['recipeCommit'],
        'recipeArchiveSha256': next(row['sha256'] for row in fixed['archives'] if row['kind'] == 'recipes'),
        'actualPatchedLlvmRecipeSha256': file_sha(patched_recipe), 'helperSha256': snapshot_inputs['helperSha256'],
        'ownrepoSourceCommit': producer_commit, 'ownrepoSourceTag': source_tag,
        'workflowRunId': int(run_id), 'workflowRunAttempt': int(run_attempt),
        'workflowJobName': os.environ.get('GITHUB_JOB'),
        'actualInitialBuildCommand': build_command, 'actualHostTargetCommand': ['ninja', '-C', str(workspace / 'build-x64'), '-j2', 'llvm'],
        'hostTargetExitCode': 0, 'buildLogPrefixBytes': stage_log.stat().st_size, 'buildLogPrefixSha256': file_sha(stage_log),
        'closedSdkOrRuntimeIncluded': False, 'hostCompilerOrNativeTestedByExporter': False,
        'toolchainImported': False, 'reproducible': False, 'gpuOrDriverTested': False}
    write_json(output / names[2], manifest)
    descriptor = {'schema': 2, 'kind': KIND, 'snapshotStatus': 'BUILT_FROM_SOURCE_THIS_RUN_EXPORT_ONLY',
        'sourceBindingSchema': 1, 'actualSourceBuildBinding': binding,
        'actualSourceBuildBindingSha256': manifest['actualSourceBuildBindingSha256'],
        'deliveryStatus': 'LOCAL_SNAPSHOT_ONLY_NOT_RELEASED', 'reuseReady': False,
        'trustRequirement': 'Independently review and freeze this manifest SHA256 plus exact own draft/tag/assets in consumer source before any import; self-declared hashes alone are not a trust anchor.',
        'ownrepoSourceCommit': producer_commit, 'ownrepoSourceTag': source_tag,
        'workflowRunId': int(run_id), 'workflowRunAttempt': int(run_attempt),
        'workflowJobName': os.environ.get('GITHUB_JOB'),
        'sourceCommit': commit, 'sourceTree': tree, 'containerImage': container_image,
        'recipeCommit': fixed['recipeCommit'], 'recipeArchiveSha256': manifest['recipeArchiveSha256'],
        'originalAbsoluteWorkspace': str(workspace), 'originalAbsoluteInstallPrefix': str(install),
        'snapshotManifest': {'fileName': names[2], 'bytes': (output / names[2]).stat().st_size, 'sha256': file_sha(output / names[2])},
        'installArchive': {'fileName': names[0], 'bytes': (output / names[0]).stat().st_size, 'sha256': file_sha(output / names[0])},
        'correspondingSourceArchive': {'fileName': names[1], 'bytes': (output / names[1]).stat().st_size, 'sha256': file_sha(output / names[1])},
        'actualInstallTreeManifestSha256': manifest['actualInstallTreeManifestSha256'],
        'completeCanonicalSourceManifestSha256': manifest['completeCanonicalSourceManifestSha256'],
        'helperSha256': snapshot_inputs['helperSha256'], 'hostTargetExitCode': 0,
        'toolchainImported': False, 'reproducible': False, 'gpuOrDriverTested': False,
        'closedSdkOrRuntimeIncluded': False, 'hostCompilerOrNativeTestedByExporter': False}
    write_json(output / names[3], descriptor)
    return output / names[3]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('mode', choices=('capture-config',))
    parser.add_argument('--stage', choices=('prebuild', 'postinstall'), required=True)
    parser.add_argument('--build-dir', type=Path, required=True)
    parser.add_argument('--source-dir', type=Path, required=True)
    parser.add_argument('--workspace', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    capture_config(args.build_dir, args.source_dir, args.workspace, args.output, args.stage)


if __name__ == '__main__':
    main()
