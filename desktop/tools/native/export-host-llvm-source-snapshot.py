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


def complete_source_archive(source, destination, commit):
    """Keep the complete public commit archive, verifying every tracked blob.

    The sparse clone alone omits source. Fetching missing blobs one at a time
    could add thousands of requests. The public exact-commit archive is checked
    against the actual recursive Git tree, not trusted by URL or self-receipt.
    export-ignore omissions and any extra file fail this complete-source gate.
    No archive entry is extracted or executed here.
    """
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
    if not expected:
        raise RuntimeError('Complete LLVM source tree is empty')
    url = 'https://codeload.github.com/llvm/llvm-project/tar.gz/' + commit
    with urllib.request.urlopen(url, timeout=180) as response, destination.open('xb') as stream:
        if response.status != 200 or response.geturl() != url:
            raise RuntimeError('Exact public corresponding source archive URL changed')
        count = 0
        while raw := response.read(1 << 20):
            count += len(raw)
            if count > 8 << 30:
                raise RuntimeError('Complete source archive exceeds the explicit safety bound')
            stream.write(raw)
    prefix = 'llvm-project-' + commit
    entries, seen = [], set()
    with tarfile.open(destination, 'r|gz') as archive:
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
                git_sha = hashlib.sha1(b'blob ' + str(len(raw)).encode('ascii') + b'\0' + raw).hexdigest()
                entries.append({'path': name, 'kind': 'symlink', 'mode': 0o777, 'bytes': len(raw), 'target': member.linkname, 'sha256': sha(raw), 'gitBlob': object_id})
            else:
                if not member.isfile() or member.mode & 0o6000 or bool(member.mode & 0o111) != (mode == '100755'):
                    raise RuntimeError('Canonical source file kind/mode differs from Git')
                git_digest = hashlib.sha1(b'blob ' + str(member.size).encode('ascii') + b'\0')
                digest = hashlib.sha256()
                count = 0
                stream = archive.extractfile(member)
                with stream:
                    while raw := stream.read(1 << 20):
                        count += len(raw)
                        git_digest.update(raw)
                        digest.update(raw)
                if count != member.size:
                    raise RuntimeError('Incomplete canonical source file')
                git_sha = git_digest.hexdigest()
                entries.append({'path': name, 'kind': 'file', 'mode': 0o755 if mode == '100755' else 0o644, 'bytes': member.size, 'sha256': digest.hexdigest(), 'gitBlob': object_id})
            if git_sha != object_id:
                raise RuntimeError('Canonical corresponding source bytes differ from actual Git blob')
            seen.add(name)
    if seen != set(expected):
        raise RuntimeError('Corresponding source archive omitted tracked source; no incomplete snapshot is accepted')
    entries.sort(key=lambda row: row['path'])
    return entries, sha(raw_tree)


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
    final, final_entries, final_tree = source_identity(source, workspace)
    if final != identity or final_entries != entries or final_tree != raw_tree:
        raise RuntimeError('LLVM source changed while writing actual capture')
    receipt = {'schema': 2, 'kind': KIND, 'stage': stage_name, 'sourceBindingSchema': 1,
        'actualBuildDirectory': str(build_directory), 'actualSourceDirectory': str(source),
        'workspace': str(workspace), 'helperSha256': helper_sha, 'files': rows,
        'actualBuildSource': identity, 'resolvedConfigurationAssertions': assertions}
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
    full_source_entries, full_ls_tree_sha = complete_source_archive(source, canonical, commit)
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
    for row in used_entries:
        name = row['path'].removeprefix('actual-used-source-worktree/')
        original = full_by_path.get(name)
        if original and row['kind'] != 'directory':
            actual_sha = row.get('sha256') if row['kind'] == 'file' else sha(row['target'].encode('utf-8'))
            if row['kind'] != original['kind'] or actual_sha != original['sha256']:
                raise RuntimeError('Actual used LLVM tracked source differs from the complete canonical commit')
    final_source, final_entries, final_tree = source_identity(source, workspace)
    if (final_source != built_source or final_entries != postcleanup_entries
            or final_tree != postcleanup_tree or used_entries != postcleanup_entries):
        raise RuntimeError('Actual built LLVM source changed during snapshot export')
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
