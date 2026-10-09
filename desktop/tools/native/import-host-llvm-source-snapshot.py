#!/usr/bin/env python3
"""Explicit local own-source HOST LLVM reuse; no remote fetch or copied build stamps.

The pinned catalog is empty until actual own draft assets and genuine original
host/tool evidence receive independent review. A source candidate is not a usable
snapshot. Every accepted import retains its original source tag, commit and bytes.
"""
import argparse
import hashlib
import importlib.util
import json
import os
import platform
import re
import shutil
import stat
import struct
import subprocess
import sys
import tarfile
from pathlib import Path, PurePosixPath

REPOSITORY = 'tonysuper666-creator/BiliPai-Windows'
KIND = 'BILIPAI_HOST_LLVM_SOURCE_SNAPSHOT'
BINDING_STATE = 'PREBUILD_INSTALL_POSTCLEANUP_SOURCE_AND_CONFIG_BOUND'
IMPORT_STATUS = 'IMPORTED_OWN_SOURCE_SNAPSHOT_REAL_LLVM_TARGET_VALIDATED'
CATALOG_PATH = 'desktop/third-party/libmpv/build/rtx-core-v1/host-llvm-import-trust.json'
CATALOG_SHA256 = '1c3f838050e5d30295eae23361bac3059e91b88a53d3e164778829889aa2c7aa'
EXPORTER_SHA256 = '3ac2ad74af044f2004c461737a6eed4e511b7d9aec5c94227360861bf9d2e171'
INPUTS_SHA256 = '2cdaea2ab2ffa26ea058bf2ac84f681e11eda6be410140a10bab04a23e6fbc62'
LLVM_RECIPE_SHA256 = '32e9dc394790ba6a95c4ac5a829a5063460ee893736328441cfc22dc610db36b'
RECIPE_COMMIT = 'cd1edc11dc6887a50f705717619d879f5a93a488'
RECIPE_ARCHIVE_SHA256 = '8b92a254771496b0dcc23017c2734bfa7545441d3e6a37958b063d6e7814a657'
CONTAINER_IMAGE = 'ghcr.io/shinchiro/archlinux@sha256:2b81f07c567b051455b9539770a71b53dd3add5f9ef52eca95ac01a2272e9fdf'
MAX_JSON = 256 << 20
MAX_ARCHIVE = 64 << 30
MAX_ENTRIES = 1000000
ASSETS = ('host-llvm-source-snapshot-descriptor.json', 'host-llvm-snapshot-manifest.json',
          'host-llvm-install.tar.gz', 'host-llvm-corresponding-source.tar.gz',
          'host-llvm-original-environment.json')
BUILD_ENV = ('CC', 'CXX', 'CFLAGS', 'CXXFLAGS', 'LDFLAGS', 'LD_LIBRARY_PATH',
             'LD_PRELOAD', 'LIBRARY_PATH', 'CPATH', 'C_INCLUDE_PATH', 'CPLUS_INCLUDE_PATH',
             'PATH', 'AS', 'AR', 'RANLIB', 'MAKEFLAGS', 'CMAKE_PREFIX_PATH', 'CMAKE_TOOLCHAIN_FILE')


def fail(message):
    raise RuntimeError('HOST_LLVM_IMPORT_REJECTED: ' + message)


def encoded(value):
    return (json.dumps(value, sort_keys=True, indent=2) + '\n').encode('utf-8')


def sha(value):
    return hashlib.sha256(value).hexdigest()


def digest(value):
    if not isinstance(value, str) or not re.fullmatch(r'[0-9a-f]{64}', value):
        fail('Invalid required SHA256')
    return value


def revision(value):
    if not isinstance(value, str) or not re.fullmatch(r'[0-9a-f]{40}', value):
        fail('Invalid actual Git revision')
    return value


def positive(value, maximum=MAX_ARCHIVE):
    if type(value) is not int or not 0 < value <= maximum:
        fail('Invalid positive bounded integer')
    return value


def safe_name(value):
    if (not isinstance(value, str) or not value or '\0' in value or '\\' in value
            or value.startswith('/') or str(PurePosixPath(value)) != value
            or any(part in ('', '.', '..') for part in value.split('/'))):
        fail('Unsafe archive relative path')
    return value


def owned(path, root, directory=False):
    path, root = Path(os.path.abspath(path)), Path(os.path.abspath(root))
    if path != root and root not in path.parents:
        fail('Path escaped the explicit owner root')
    for item in reversed([path, *path.parents]):
        item_stat = item.lstat()
        mode = item_stat.st_mode
        if stat.S_ISLNK(mode) or getattr(item_stat, 'st_file_attributes', 0) & 1024 or (item != path and not stat.S_ISDIR(mode)):
            fail('Owner path has a link or non-directory ancestor')
    mode = path.lstat().st_mode
    if not (stat.S_ISDIR(mode) if directory else stat.S_ISREG(mode)):
        fail('Expected ordinary owned path')
    return path


def file_sha(path, root=None):
    path = owned(path, root or Path(path).parent)
    first = path.stat()
    with path.open('rb') as stream:
        opened = os.fstat(stream.fileno())
        result = hashlib.file_digest(stream, 'sha256').hexdigest()
        last_opened = os.fstat(stream.fileno())
    last = path.stat()
    key = lambda item: (item.st_dev, item.st_ino, item.st_size, item.st_mtime_ns)
    if any(key(item) != key(first) for item in (opened, last_opened, last)):
        fail('Material changed while hashing')
    return result


def json_file(path, expected_sha=None, root=None):
    path = owned(path, root or Path(path).parent)
    if not 0 < path.stat().st_size <= MAX_JSON:
        fail('JSON length outside bound')
    data = path.read_bytes()
    if expected_sha is not None and sha(data) != digest(expected_sha):
        fail('Pinned JSON changed')
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                fail('Duplicate JSON key')
            result[key] = value
        return result
    return json.loads(data, object_pairs_hook=pairs,
                      parse_constant=lambda value: fail('Nonfinite JSON number'))


def write_new(path, value):
    with path.open('xb') as stream:
        stream.write(encoded(value))
        stream.flush()
        os.fsync(stream.fileno())


def load_catalog(root):
    catalog = json_file(root / CATALOG_PATH, CATALOG_SHA256, root)
    if (catalog.get('schema') != 1 or catalog.get('kind') != 'BILIPAI_HOST_LLVM_IMPORT_TRUST'
            or catalog.get('repository') != REPOSITORY
            or catalog.get('activation') != 'EXPLICIT_LOCAL_MATERIAL_ONLY'
            or type(catalog.get('snapshotAvailable')) is not bool
            or not isinstance(catalog.get('records'), list)
            or catalog['snapshotAvailable'] != bool(catalog['records'])):
        fail('Wrong source-owned import catalog')
    ids = set()
    for row in catalog['records']:
        if (not isinstance(row, dict) or not re.fullmatch(r'[A-Za-z0-9._-]{1,100}', row.get('id', ''))
                or row['id'] in ids or row.get('repository') != REPOSITORY
                or not re.fullmatch(r'rtx-source-[A-Za-z0-9][A-Za-z0-9._-]{0,100}', row.get('sourceTag', ''))
                or row.get('draft') is not True or row.get('published') is not False):
            fail('Invalid immutable original own draft origin')
        ids.add(row['id'])
        revision(row.get('sourceCommit'))
        positive(row.get('workflowRunId'))
        positive(row.get('workflowRunAttempt'))
        positive(row.get('ownDraftReleaseId'))
        digest(row.get('independentReviewSha256'))
        digest(row.get('actualSourceBuildBindingSha256'))
        if (set(row.get('assets', {})) != set(ASSETS)
                or not row.get('originalHostEnvironment')
                or not row.get('originalInstalledHostTools')
                or row['originalHostEnvironment'].get('runnerImageProofAvailable') is not True
                or set(row['originalHostEnvironment'].get('runnerImage', {})) != {'ImageOS', 'ImageVersion'}
                or any(not isinstance(value, str) or not value for value in row['originalHostEnvironment']['runnerImage'].values())):
            fail('Missing actual independently reviewed environment/assets')
        for leaf, item in row['assets'].items():
            if item.get('fileName') != leaf:
                fail('Wrong original asset name')
            digest(item.get('sha256'))
            positive(item.get('bytes'), MAX_JSON if leaf.endswith('.json') else MAX_ARCHIVE)
            asset_ids = item.get('ownDraftAssetIds')
            if not isinstance(asset_ids, list) or not asset_ids or len(set(asset_ids)) != len(asset_ids):
                fail('Missing exact original own draft asset IDs')
            for asset_id in asset_ids:
                positive(asset_id)
    return catalog


def load_exporter(root):
    path = root / 'desktop/tools/native/export-host-llvm-source-snapshot.py'
    if (file_sha(path, root) != EXPORTER_SHA256
            or file_sha(root / 'desktop/third-party/libmpv/build/rtx-core-v1/host-llvm-snapshot-inputs.json', root)
               != INPUTS_SHA256):
        fail('Common original exporter/lifecycle source changed')
    spec = importlib.util.spec_from_file_location('bilipai_host_export_for_verified_inventory', path)
    if spec is None or spec.loader is None:
        fail('Original inventory source unavailable')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def inventory(rows):
    if not isinstance(rows, list) or not 1 < len(rows) <= MAX_ENTRIES:
        fail('Missing/bounded complete inventory')
    result = {}
    total = 0
    for row in rows:
        name = safe_name(row.get('path'))
        if name in result or row.get('kind') not in ('file', 'directory', 'symlink'):
            fail('Duplicate or unsupported inventory entry')
        mode = row.get('mode')
        if type(mode) is not int or not 0 <= mode <= 0o777 or mode & 0o6000:
            fail('Invalid archive mode')
        if row['kind'] == 'file':
            length = row.get('bytes')
            if type(length) is not int or not 0 <= length <= MAX_ARCHIVE:
                fail('Invalid file length')
            digest(row.get('sha256'))
            total += length
        elif row['kind'] == 'symlink':
            target = row.get('target')
            if not isinstance(target, str) or not target or '\0' in target or '\\' in target:
                fail('Invalid link target')
        result[name] = row
    if total > MAX_ARCHIVE:
        fail('Archive unpacked data exceeds bound')
    for name in result:
        for ancestor in PurePosixPath(name).parents:
            if str(ancestor) != '.' and str(ancestor) in result and result[str(ancestor)]['kind'] != 'directory':
                fail('Archive entry has non-directory ancestor')
    return result


def subtree(rows, prefix):
    return [row for row in rows if row['path'] == prefix or row['path'].startswith(prefix + '/')]


def validate_links(rows, prefix, absolute_prefix=None):
    table = inventory(rows)
    def mapped(link, target):
        if target.startswith('/'):
            if not absolute_prefix or (target != absolute_prefix and not target.startswith(absolute_prefix + '/')):
                fail('Absolute symlink escaped original prefix')
            parts = [prefix] + target[len(absolute_prefix):].strip('/').split('/')
        else:
            parts = link.split('/')[:-1] + target.split('/')
        normalized = []
        for part in parts:
            if part in ('', '.'):
                continue
            if part == '..':
                if len(normalized) <= 1:
                    fail('Relative symlink escaped archive root')
                normalized.pop()
            else:
                normalized.append(part)
        if not normalized or normalized[0] != prefix:
            fail('Symlink escaped selected tree')
        return normalized
    for name, row in table.items():
        if row['kind'] != 'symlink':
            continue
        pending, visited = mapped(name, row['target']), {name}
        for unused in range(len(table) + 1):
            redirected = False
            for size in range(1, len(pending) + 1):
                path = '/'.join(pending[:size])
                item = table.get(path)
                if item is None:
                    fail('Dangling symlink path component')
                if item['kind'] == 'symlink':
                    if path in visited:
                        fail('Symlink cycle')
                    visited.add(path)
                    pending = mapped(path, item['target']) + pending[size:]
                    redirected = True
                    break
                if size < len(pending) and item['kind'] != 'directory':
                    fail('Symlink traversed non-directory')
            if not redirected:
                break
        else:
            fail('Symlink traversal exceeded complete inventory')


def consume(stream, length, expected_sha, blob=None):
    actual = hashlib.sha256()
    git = hashlib.sha1(b'blob ' + str(length).encode() + b'\0') if blob else None
    remaining = length
    while remaining:
        block = stream.read(min(65536, remaining))
        if not block:
            fail('Truncated corresponding source/output')
        actual.update(block)
        if git is not None:
            git.update(block)
        remaining -= len(block)
    if stream.read(1) or actual.hexdigest() != digest(expected_sha):
        fail('Complete archive payload changed')
    if git is not None and git.hexdigest() != revision(blob):
        fail('Canonical file differs from actual Git blob')


def verify_canonical(stream, manifest):
    rows = inventory(manifest['completeCanonicalSourceEntries'])
    prefix = 'llvm-project-' + revision(manifest['sourceCommit'])
    seen = set()
    directories = {prefix}
    for name in rows:
        directories.update(str(parent) for parent in PurePosixPath(prefix + '/' + name).parents if str(parent) != '.')
    with tarfile.open(fileobj=stream, mode='r|gz') as archive:
        for member in archive:
            name = safe_name(member.name.rstrip('/') if member.isdir() else member.name)
            if name in seen:
                fail('Duplicate full canonical source member')
            seen.add(name)
            if member.isdir():
                if name not in directories:
                    fail('Unexpected canonical directory')
                continue
            if not name.startswith(prefix + '/'):
                fail('Wrong original canonical Git prefix')
            row = rows.get(name[len(prefix) + 1:])
            if row is None:
                fail('Extra canonical source blob')
            if member.isfile() and row['kind'] == 'file':
                if member.size != row['bytes'] or member.mode & 0o6000 or bool(member.mode & 0o111) != bool(row['mode'] & 0o111):
                    fail('Canonical blob length/mode differs')
                consume(archive.extractfile(member), row['bytes'], row['sha256'], row['gitBlob'])
            elif member.issym() and row['kind'] == 'symlink':
                if member.linkname != row['target'] or member.mode & 0o6000:
                    fail('Canonical symlink differs')
                consume(__import__('io').BytesIO(member.linkname.encode()), row['bytes'], row['sha256'], row['gitBlob'])
            else:
                fail('Canonical source contains unsupported member')
    expected_files = {prefix + '/' + name for name, row in rows.items() if row['kind'] != 'directory'}
    if not expected_files <= seen or any(rows.get(row['path']) != row for row in manifest['completeLicenseEntries']):
        fail('Canonical source/licences incomplete')
    if not any(row['path'] in ('LICENSE.TXT', 'llvm/LICENSE.TXT') for row in manifest['completeLicenseEntries']):
        fail('Full LLVM license missing')


def verify_archive(path, rows, manifest=None):
    expected = inventory(rows)
    seen = set()
    before = file_sha(path)
    with tarfile.open(path, 'r:gz') as archive:
        for member in archive:
            name = safe_name(member.name.rstrip('/') if member.isdir() else member.name)
            row = expected.get(name)
            if row is None or name in seen or member.mode != row['mode']:
                fail('Extra/duplicate/member mode differs from complete inventory')
            seen.add(name)
            if member.isdir() and row['kind'] == 'directory':
                pass
            elif member.issym() and row['kind'] == 'symlink' and member.linkname == row['target']:
                pass
            elif member.isfile() and row['kind'] == 'file' and member.size == row['bytes']:
                consume(archive.extractfile(member), row['bytes'], row['sha256'])
                if manifest is not None and name == 'canonical-source/complete-llvm-source.tar.gz':
                    verify_canonical(archive.extractfile(member), manifest)
            else:
                fail('Archive member kind/size/link differs')
    if seen != set(expected) or file_sha(path) != before:
        fail('Complete archive inventory or bytes changed')


def verify_used_tracked_source(root, bundle, manifest, used, canonical, actual_blobs):
    # The same hash-pinned raw-byte verifier closes import and upload gates.
    # Independent full archive/tree verification remains mandatory before this.
    module = load_exporter(root)
    witnesses = module.verify_used_source_relationship(bundle, manifest['sourceCommit'],
        used, canonical, actual_blobs)
    if witnesses != manifest.get('actualUsedSourceCrLfMaterializations'):
        fail('Actual used source CRLF relations are missing, changed or unproved')


def lifecycle(manifest, descriptor, record, workspace, fixed, command):
    origin = {'ownrepoSourceCommit': record['sourceCommit'], 'ownrepoSourceTag': record['sourceTag'],
              'workflowRunId': record['workflowRunId'], 'workflowRunAttempt': record['workflowRunAttempt']}
    for item in (manifest, descriptor):
        if (item.get('schema') != 2 or item.get('kind') != KIND
                or item.get('snapshotStatus') != 'BUILT_FROM_SOURCE_THIS_RUN_EXPORT_ONLY'
                or any(item.get(key) != value for key, value in origin.items())
                or item.get('sourceBindingSchema') != 1 or item.get('helperSha256') != EXPORTER_SHA256
                or item.get('recipeCommit') != fixed['recipeCommit']
                or item.get('recipeArchiveSha256') != next(row['sha256'] for row in fixed['archives'] if row['kind'] == 'recipes')
                or item.get('originalAbsoluteWorkspace') != str(workspace)
                or item.get('originalAbsoluteInstallPrefix') != str(workspace / 'clang-root')
                or item.get('hostTargetExitCode') != 0
                or any(item.get(key) is not False for key in ('toolchainImported', 'reproducible',
                    'gpuOrDriverTested', 'closedSdkOrRuntimeIncluded', 'hostCompilerOrNativeTestedByExporter'))):
            fail('Original source/environment/lifecycle contract differs')
    if descriptor.get('reuseReady') is not False or descriptor.get('deliveryStatus') != 'LOCAL_SNAPSHOT_ONLY_NOT_RELEASED':
        fail('Original descriptor was relabelled ready')
    binding = manifest.get('actualSourceBuildBinding', {})
    built = binding.get('prebuild', {})
    binding_sha = sha(encoded(binding))
    expected_source = str(workspace / 'sources/llvm')
    expected_build = str(workspace / 'build-x64/toolchain/llvm-prefix/src/llvm-build')
    if (binding.get('schema') != 1 or binding.get('state') != BINDING_STATE
            or not built or built.get('schema') != 1
            or binding.get('postinstall') != built or binding.get('postcleanup') != built
            or built.get('gitStatusPorcelainZHex') != ''
            or built.get('sourceRemote') != 'https://github.com/llvm/llvm-project.git'
            or built.get('actualSourceDirectory') != expected_source
            or binding.get('actualSourceDirectory') != expected_source
            or binding.get('actualBuildDirectory') != expected_build
            or built.get('sourceCommit') != revision(manifest.get('sourceCommit'))
            or built.get('sourceTree') != revision(manifest.get('sourceTree'))
            or descriptor.get('sourceCommit') != manifest['sourceCommit']
            or descriptor.get('sourceTree') != manifest['sourceTree']
            or descriptor.get('actualSourceBuildBinding') != binding
            or any(item.get('actualSourceBuildBindingSha256') != binding_sha for item in (manifest, descriptor, record))
            or built.get('completeSourceLsTreeSha256') != manifest.get('completeSourceLsTreeSha256')
            or manifest.get('actualSourcePostBuildGitStatus') != ''
            or manifest.get('actualInitialBuildCommand') != command
            or manifest.get('actualHostTargetCommand') != ['ninja', '-C', str(workspace / 'build-x64'), '-j2', 'llvm']
            or manifest.get('actualPatchedLlvmRecipeSha256') != LLVM_RECIPE_SHA256):
        fail('Actual prebuild/install/cleanup source identity is not exact')
    digest(built.get('completeSourceLsTreeSha256'))
    positive(built.get('actualSourceWorktreeEntries'), MAX_ENTRIES)
    captures = []
    for key, hash_key, stage in (
            ('prebuildConfigCaptureReceipt', 'prebuildCaptureReceiptSha256', 'AFTER_CONFIGURATION_BEFORE_REAL_LLVM_BUILD'),
            ('configCaptureReceipt', 'postinstallCaptureReceiptSha256', 'AFTER_REAL_LLVM_INSTALL_BEFORE_RECIPE_CLEANUP')):
        capture = manifest.get(key, {})
        if (capture.get('schema') != 2 or capture.get('sourceBindingSchema') != 1
                or capture.get('stage') != stage or capture.get('helperSha256') != EXPORTER_SHA256
                or capture.get('actualBuildSource') != built
                or capture.get('actualSourceDirectory') != expected_source
                or capture.get('actualBuildDirectory') != expected_build or capture.get('workspace') != str(workspace)
                or capture.get('resolvedConfigurationAssertions') != binding.get('resolvedConfigurationAssertions')
                or sha(encoded(capture)) != binding.get(hash_key)):
            fail('Original real stage capture differs')
        captures.append(capture)
    pre, post = ({row['path']: row for row in capture['files']} for capture in captures)
    names = {'CMakeCache.txt', 'build.ninja', 'CMakeFiles/rules.ninja', 'actual-source-worktree.json', 'actual-source-ls-tree.bin'}
    assertions = binding.get('resolvedConfigurationAssertions', {})
    if (set(pre) != names or len(captures[0]['files']) != 5
            or set(post) != names | {'install_manifest.txt'} or len(captures[1]['files']) != 6
            or any(pre[name] != post[name] for name in names)
            or captures[1].get('prebuildCaptureReceiptSha256') != binding.get('prebuildCaptureReceiptSha256')
            or assertions.get('CMAKE_HOME_DIRECTORY') != expected_source + '/llvm'
            or assertions.get('CMAKE_CACHEFILE_DIR') != expected_build
            or assertions.get('CMAKE_INSTALL_PREFIX') != str(workspace / 'clang-root')
            or manifest.get('resolvedConfigurationAssertions') != assertions):
        fail('Original actual configuration/source capture incomplete')
    for key, leaf in (('snapshotManifest', ASSETS[1]), ('installArchive', ASSETS[2]), ('correspondingSourceArchive', ASSETS[3])):
        expected = record['assets'][leaf]
        if descriptor.get(key) != {name: expected[name] for name in ('fileName', 'bytes', 'sha256')}:
            fail('Original descriptor/archive identity differs')
    return binding_sha


def inspect_optional(directory, root, workspace, fixed, image, command):
    catalog = load_catalog(root)
    if directory is None:
        return None
    expected_command = ['cmake', '-DTARGET_ARCH=x86_64-w64-mingw32', '-DCOMPILER_TOOLCHAIN=clang',
        '-DGCC_ARCH=x86-64', '-DMAKEJOBS=2', '-DCLANG_PACKAGES_LTO=ON',
        '-DCMAKE_INSTALL_PREFIX=' + str(workspace / 'clang-root'),
        '-DMINGW_INSTALL_PREFIX=' + str(workspace / 'build-x64/x86_64-w64-mingw32'),
        '-DSINGLE_SOURCE_LOCATION=' + str(workspace / 'sources'),
        '-DRUSTUP_LOCATION=' + str(workspace / 'rustup'),
        '-G', 'Ninja', '--fresh', '-B', str(workspace / 'build-x64'),
        '-S', str(workspace / ('mpv-winbuild-cmake-' + RECIPE_COMMIT))]
    recipe_archives = [row for row in fixed.get('archives', []) if row.get('kind') == 'recipes']
    if (image != CONTAINER_IMAGE or fixed.get('recipeCommit') != RECIPE_COMMIT
            or fixed.get('recipeArchivePrefix') != 'mpv-winbuild-cmake-' + RECIPE_COMMIT
            or len(recipe_archives) != 1 or recipe_archives[0].get('sha256') != RECIPE_ARCHIVE_SHA256
            or command != expected_command):
        fail('Import cannot cross fixed recipe/container/real initial build contract')
    directory = Path(os.path.abspath(directory))
    if not directory.exists() and not directory.is_symlink():
        fail('Explicit snapshot directory is missing; no implicit cold retry')
    owned(directory, directory, directory=True)
    descriptor_sha = file_sha(directory / ASSETS[0], directory)
    records = [row for row in catalog['records'] if row['assets'][ASSETS[0]]['sha256'] == descriptor_sha]
    if len(records) != 1:
        fail('Present snapshot has no unique independently frozen own-source trust record')
    record = records[0]
    paths = {}
    for leaf in ASSETS:
        path = owned(directory / leaf, directory)
        item = record['assets'][leaf]
        if path.stat().st_size != item['bytes'] or file_sha(path, directory) != item['sha256']:
            fail('Present snapshot immutable asset hash/size differs')
        paths[leaf] = path
    descriptor, manifest, environment = [json_file(paths[leaf], record['assets'][leaf]['sha256']) for leaf in (ASSETS[0], ASSETS[1], ASSETS[4])]
    binding_sha = lifecycle(manifest, descriptor, record, workspace, fixed, command)
    if (manifest.get('containerImage') != image or descriptor.get('containerImage') != image
            or environment.get('schema') != 1 or environment.get('kind') != 'BILIPAI_HOST_LLVM_ORIGINAL_ENVIRONMENT'
            or environment.get('status') != 'REAL_COLD_LLVM_TARGET_SUCCEEDED'
            or environment.get('originalContext') != {key: record[key] for key in ('sourceCommit', 'sourceTag', 'workflowRunId', 'workflowRunAttempt')}
            or environment.get('containerImage') != image
            or environment.get('snapshotDescriptorSha256') != descriptor_sha
            or environment.get('snapshotManifestSha256') != record['assets'][ASSETS[1]]['sha256']
            or environment.get('actualSourceBuildBindingSha256') != binding_sha
            or environment.get('freshLlvmCompileExecuted') is not True or environment.get('llvmTargetExitCode') != 0
            or environment.get('toolchainImported') is not False or environment.get('gpuOrDriverTested') is not False
            or environment.get('originalHostEnvironment') != record['originalHostEnvironment']
            or environment.get('originalInstalledHostTools') != record['originalInstalledHostTools']):
        fail('Missing true original host/tools witness; old exports cannot fabricate it')
    install, source = manifest['actualInstallEntries'], manifest['actualCorrespondingSourceBundleEntries']
    used = subtree(source, 'actual-used-source-worktree')
    if (sha(encoded(install)) != manifest.get('actualInstallTreeManifestSha256')
            or descriptor.get('actualInstallTreeManifestSha256') != manifest.get('actualInstallTreeManifestSha256')
            or sha(encoded(used)) != manifest.get('actualUsedSourceTreeManifestSha256')
            or len(used) != manifest['actualSourceBuildBinding']['prebuild']['actualSourceWorktreeEntries']
            or manifest['actualSourceBuildBinding']['prebuild']['actualSourceWorktreeManifestSha256'] != sha(encoded(used))
            or sha(encoded(manifest['completeCanonicalSourceEntries'])) != manifest.get('completeCanonicalSourceManifestSha256')
            or descriptor.get('completeCanonicalSourceManifestSha256') != manifest.get('completeCanonicalSourceManifestSha256')):
        fail('Whole install/used/canonical inventory identity differs')
    validate_links(install, 'host-install', str(workspace / 'clang-root'))
    validate_links(used, 'actual-used-source-worktree', str(workspace / 'sources/llvm'))
    verify_archive(paths[ASSETS[2]], install)
    verify_archive(paths[ASSETS[3]], source, manifest)
    source_table = inventory(source)
    canonical = {row['path']: row for row in manifest['completeCanonicalSourceEntries']}
    # The canonical archive must represent every blob in the actual captured
    # recursive Git tree, including source omitted by the sparse build worktree.
    with tarfile.open(paths[ASSETS[3]], 'r:gz') as archive:
        tree_member = archive.getmember('actual-config-capture/actual-source-ls-tree.bin')
        if tree_member.size > MAX_JSON:
            fail('Actual recursive Git tree exceeds explicit metadata bound')
        raw_tree = archive.extractfile(tree_member).read()
        actual_blobs = {}
        for item in raw_tree.split(b'\0'):
            if not item:
                continue
            fields, raw_name = item.split(b'\t', 1)
            mode, kind, blob = fields.decode('ascii').split(' ')
            name = safe_name(raw_name.decode('utf-8'))
            if name in actual_blobs or kind != 'blob' or mode not in ('100644', '100755', '120000'):
                fail('Captured actual recursive Git tree contains unsupported/duplicate source')
            actual_blobs[name] = (mode, revision(blob))
        if sha(raw_tree) != manifest['completeSourceLsTreeSha256'] or set(actual_blobs) != set(canonical):
            fail('Full canonical source omitted/added actual tracked Git blobs')
        for name, (mode, blob) in actual_blobs.items():
            row = canonical[name]
            expected_mode = 0o777 if mode == '120000' else 0o755 if mode == '100755' else 0o644
            if row.get('gitBlob') != blob or row.get('mode') != expected_mode or row['kind'] != ('symlink' if mode == '120000' else 'file'):
                fail('Canonical source blob/mode differs from real prebuild Git tree')
        module = load_exporter(root)
        for folder, key in (('actual-config-capture/prebuild', 'prebuildConfigCaptureReceipt'), ('actual-config-capture', 'configCaptureReceipt')):
            cache = archive.extractfile(archive.getmember(folder + '/CMakeCache.txt')).read()
            actual_assertions = module.configuration_assertions(Path(manifest['actualSourceBuildBinding']['actualBuildDirectory']),
                workspace / 'sources/llvm', workspace / 'clang-root', cache)
            if actual_assertions != manifest[key]['resolvedConfigurationAssertions']:
                fail('Full original compiler flags/source configuration differs')
    verify_used_tracked_source(root, paths[ASSETS[3]], manifest, used, canonical, actual_blobs)
    for folder, key in (('actual-config-capture/prebuild', 'prebuildConfigCaptureReceipt'), ('actual-config-capture', 'configCaptureReceipt')):
        capture = manifest[key]
        embedded = source_table.get(folder + '/config-capture-receipt.json', {})
        if embedded.get('sha256') != sha(encoded(capture)):
            fail('Capture metadata not present in corresponding source')
        for row in capture['files']:
            item = source_table.get(folder + '/' + row['path'], {})
            if any(item.get(name) != row[name] for name in ('bytes', 'sha256')):
                fail('Original captured source/config payload differs')
    if (source_table.get('actual-modified-build-recipes/toolchain/llvm/llvm.cmake', {}).get('sha256') != LLVM_RECIPE_SHA256
            or source_table.get('actual-modified-build-recipes/packages/bilipai-host-llvm-import.py', {}).get('sha256')
               != environment.get('collectorSourceSha256')
            or source_table.get('canonical-source/complete-llvm-source.tar.gz', {}).get('sha256')
               != manifest.get('canonicalSourceArchiveSha256')):
        fail('Full original modified recipe/collector/canonical source missing')
    return {'record': record, 'paths': paths, 'descriptor': descriptor, 'manifest': manifest, 'environment': environment}


def context():
    result = {'sourceCommit': os.environ.get('GITHUB_SHA'), 'sourceTag': os.environ.get('GITHUB_REF_NAME'),
              'workflowRunId': int(os.environ.get('GITHUB_RUN_ID', '0')),
              'workflowRunAttempt': int(os.environ.get('GITHUB_RUN_ATTEMPT', '0'))}
    if (os.environ.get('GITHUB_ACTIONS') != 'true' or os.environ.get('GITHUB_REPOSITORY') != REPOSITORY
            or os.environ.get('GITHUB_EVENT_NAME') != 'workflow_dispatch' or os.environ.get('GITHUB_REF_TYPE') != 'tag'
            or not re.fullmatch(r'rtx-source-[A-Za-z0-9][A-Za-z0-9._-]{0,100}', result['sourceTag'] or '')):
        fail('Only actual own pre-existing source-tag workflow is accepted')
    revision(result['sourceCommit'])
    positive(result['workflowRunId'])
    positive(result['workflowRunAttempt'])
    return result


def probe(command):
    environment = {key: value for key, value in os.environ.items() if key not in ('GITHUB_TOKEN', 'GH_TOKEN')}
    process = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=30, env=environment)
    if process.returncode or not 0 < len(process.stdout) <= 65536:
        fail('Actual host tool probe failed')
    return process.stdout.decode('utf-8', errors='strict').strip()


def host_environment():
    if platform.system() != 'Linux' or platform.machine() not in ('x86_64', 'AMD64') or struct.calcsize('P') != 8:
        fail('Exact Linux AMD64 host required')
    runner = {key: os.environ.get(key) for key in ('RUNNER_OS', 'RUNNER_ARCH', 'RUNNER_ENVIRONMENT')}
    if runner != {'RUNNER_OS': 'Linux', 'RUNNER_ARCH': 'X64', 'RUNNER_ENVIRONMENT': 'github-hosted'}:
        fail('Only own standard GitHub-hosted Linux runner allowed')
    runner_image = {key: os.environ.get(key) for key in ('ImageOS', 'ImageVersion')}
    if any(value is not None and (not isinstance(value, str) or len(value) > 256) for value in runner_image.values()):
        fail('Actual runner image witness exceeds source capture bounds')
    tools = {}
    for executable in ('cmake', 'ninja', 'python3', 'clang', 'clang++', 'ld.lld'):
        logical = shutil.which(executable)
        if not logical:
            fail('Original actual host tool unavailable')
        resolved = Path(logical).resolve(strict=True)
        tools[executable] = {'invocationPath': logical, 'resolvedPath': str(resolved),
                             'sha256': file_sha(resolved), 'version': probe([logical, '--version'])}
    flags = sorted({line.split(':', 1)[1].strip() for line in Path('/proc/cpuinfo').read_text().splitlines() if line.startswith('flags')})
    variables = {key: os.environ.get(key, '') for key in BUILD_ENV}
    if not flags or variables['LD_PRELOAD'] or any(len(value) > 4096 or '\0' in value for value in variables.values()):
        fail('Unsupported actual host CPU/build environment')
    return {'system': 'Linux', 'architecture': platform.machine(), 'pointerBits': 64,
            'kernelRelease': platform.release(), 'kernelVersion': platform.version(), 'runnerImage': runner_image,
            'runnerImageProofAvailable': all(isinstance(value, str) and bool(value) for value in runner_image.values()),
            'libc': list(platform.libc_ver()), 'osReleaseSha256': file_sha(Path('/etc/os-release').resolve()),
            'cpuFlags': flags, 'runner': runner, 'buildEnvironment': variables, 'tools': tools}


def installed_tools(install):
    result = {}
    for leaf, commands in (('bin/clang', ('--version', '-dumpmachine')), ('bin/ld.lld', ('--version',))):
        logical = install / leaf
        resolved = logical.resolve(strict=True)
        if install not in resolved.parents:
            fail('Installed host compiler escaped original prefix')
        with resolved.open('rb') as stream:
            header = stream.read(20)
        if len(header) < 20 or header[:6] != b'\x7fELF\x02\x01' or struct.unpack_from('<H', header, 18)[0] != 62:
            fail('Installed compiler is not actual Linux AMD64 ELF')
        result[leaf] = {'invocationPath': str(logical), 'resolvedPath': str(resolved),
                       'sha256': file_sha(resolved, install),
                       'probes': {flag: probe([str(logical), flag]) for flag in commands}}
    return result


def capture_cold_environment():
    return {'originalContext': context(), 'originalHostEnvironment': host_environment()}


def finish_cold_environment(output, descriptor_path, pending):
    descriptor = json_file(descriptor_path)
    if pending['originalContext'] != context() or pending['originalHostEnvironment'] != host_environment():
        fail('Actual cold host environment changed during LLVM target')
    manifest_path = output / ASSETS[1]
    evidence = {'schema': 1, 'kind': 'BILIPAI_HOST_LLVM_ORIGINAL_ENVIRONMENT',
                'status': 'REAL_COLD_LLVM_TARGET_SUCCEEDED', 'originalContext': pending['originalContext'],
                'containerImage': descriptor['containerImage'], 'snapshotDescriptorSha256': file_sha(descriptor_path),
                'snapshotManifestSha256': file_sha(manifest_path),
                'actualSourceBuildBindingSha256': descriptor['actualSourceBuildBindingSha256'],
                'originalHostEnvironment': pending['originalHostEnvironment'],
                'originalInstalledHostTools': installed_tools(Path(descriptor['originalAbsoluteInstallPrefix'])),
                'collectorSourceSha256': file_sha(Path(__file__)),
                'llvmTargetExitCode': 0, 'freshLlvmCompileExecuted': True,
                'toolchainImported': False, 'gpuOrDriverTested': False}
    write_new(output / ASSETS[4], evidence)
    return output / ASSETS[4]


def copy_new(source, destination, expected):
    before = file_sha(source)
    if before != expected:
        fail('Immutable source changed before copying')
    with source.open('rb') as src, destination.open('xb') as dst:
        shutil.copyfileobj(src, dst)
        dst.flush()
        os.fsync(dst.fileno())
    if file_sha(source) != before or file_sha(destination) != expected:
        fail('Immutable source changed during copying')


def extract_tree(archive_path, rows, prefix, destination):
    expected = inventory(subtree(rows, prefix))
    before = file_sha(archive_path)
    destination.mkdir()
    links, directories, seen = [], [], set()
    with tarfile.open(archive_path, 'r:gz') as archive:
        for member in archive:
            name = member.name.rstrip('/') if member.isdir() else member.name
            if name not in expected:
                continue
            row = expected[name]
            if name in seen or member.mode != row['mode']:
                fail('Extraction metadata changed')
            seen.add(name)
            suffix = name[len(prefix):].lstrip('/')
            path = destination.joinpath(*suffix.split('/')) if suffix else destination
            if row['kind'] == 'directory' and member.isdir():
                path.mkdir(parents=True, exist_ok=True)
                directories.append((path, row['mode']))
            elif row['kind'] == 'file' and member.isfile() and member.size == row['bytes']:
                path.parent.mkdir(parents=True, exist_ok=True)
                with archive.extractfile(member) as src, path.open('xb') as dst:
                    shutil.copyfileobj(src, dst)
                if file_sha(path, destination) != row['sha256']:
                    fail('Restored actual file differs')
                path.chmod(row['mode'])
            elif row['kind'] == 'symlink' and member.issym() and member.linkname == row['target']:
                links.append((path, row['target']))
            else:
                fail('Restored kind/link/size changed')
    if seen != set(expected) or file_sha(archive_path) != before:
        fail('Complete restoration/source archive changed')
    for path, target in links:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.symlink_to(target)
    for path, mode in reversed(directories):
        path.chmod(mode)


def prepare_import(plan, workspace, output, root, fixed, image, command):
    manifest = plan['manifest']
    install, source = workspace / 'clang-root', workspace / 'sources/llvm'
    if any(path.exists() or path.is_symlink() for path in (install, source)):
        fail('Import requires absent source/install destinations')
    # Archive verification happened before workspace creation; repeat all byte
    # identities before restoration. This is not source download or a cache.
    for leaf, path in plan['paths'].items():
        if file_sha(path) != plan['record']['assets'][leaf]['sha256']:
            fail('Snapshot changed after preflight')
    stage = workspace / 'verified-host-llvm-import'
    stage.mkdir()
    extract_tree(plan['paths'][ASSETS[2]], manifest['actualInstallEntries'], 'host-install', stage / 'install')
    extract_tree(plan['paths'][ASSETS[3]], manifest['actualCorrespondingSourceBundleEntries'], 'actual-used-source-worktree', stage / 'source')
    install.parent.mkdir(parents=True, exist_ok=True)
    source.parent.mkdir(parents=True, exist_ok=True)
    (stage / 'install').rename(install)
    (stage / 'source').rename(source)
    module = load_exporter(root)
    if (module.pack_tree(None, install, 'host-install') != manifest['actualInstallEntries']
            or module.pack_tree(None, source, 'actual-used-source-worktree', skip_git=True)
               != subtree(manifest['actualCorrespondingSourceBundleEntries'], 'actual-used-source-worktree')):
        fail('Whole restored source/install inventory differs')
    for leaf, path in plan['paths'].items():
        copy_new(path, output / leaf, plan['record']['assets'][leaf]['sha256'])
    control = {'schema': 1, 'kind': 'BILIPAI_HOST_LLVM_IMPORT_CONTROL', 'recordId': plan['record']['id'],
               'catalogSha256': CATALOG_SHA256, 'snapshotDirectory': str(output), 'workspace': str(workspace),
               'repositoryRoot': str(root), 'fixedInputs': fixed, 'containerImage': image,
               'originalInitialBuildCommand': command, 'currentContext': context(),
               'actualSourceBuildBindingSha256': manifest['actualSourceBuildBindingSha256'],
               'validationReceipt': str(output / 'host-llvm-import-target-validation.json')}
    path = output / 'host-llvm-import-control.json'
    write_new(path, control)
    return path, file_sha(path)


def validate_target(control_path, control_sha, workspace, source, install, root):
    control = json_file(control_path, digest(control_sha))
    if (control.get('schema') != 1 or control.get('kind') != 'BILIPAI_HOST_LLVM_IMPORT_CONTROL'
            or control.get('catalogSha256') != CATALOG_SHA256 or control.get('currentContext') != context()
            or control.get('workspace') != str(workspace) or control.get('repositoryRoot') != str(root)
            or source != workspace / 'sources/llvm' or install != workspace / 'clang-root'):
        fail('Actual validation target/control/prefix differs')
    output = Path(control['snapshotDirectory'])
    if control.get('validationReceipt') != str(output / 'host-llvm-import-target-validation.json'):
        fail('Actual validation receipt escaped explicit output')
    plan = inspect_optional(output, root, workspace, control['fixedInputs'], control['containerImage'], control['originalInitialBuildCommand'])
    if plan is None or plan['record']['id'] != control.get('recordId'):
        fail('Actual target lost original trusted snapshot')
    manifest, module = plan['manifest'], load_exporter(root)
    if (module.pack_tree(None, owned(install, workspace, directory=True), 'host-install') != manifest['actualInstallEntries']
            or module.pack_tree(None, owned(source, workspace, directory=True), 'actual-used-source-worktree', skip_git=True)
               != subtree(manifest['actualCorrespondingSourceBundleEntries'], 'actual-used-source-worktree')
            or host_environment() != plan['record']['originalHostEnvironment']
            or installed_tools(install) != plan['record']['originalInstalledHostTools']):
        fail('Actual restored trees or host/compiler witnesses differ')
    cache = owned(workspace / 'build-x64/CMakeCache.txt', workspace)
    actual = {}
    for line in cache.read_text(encoding='utf-8').splitlines():
        if line and not line.startswith(('#', '//')) and '=' in line and ':' in line.split('=', 1)[0]:
            key, value = line.split('=', 1)
            key = key.split(':', 1)[0]
            if key in actual:
                fail('Duplicate actual outer CMake cache key')
            actual[key] = value
    expected = {'TARGET_ARCH': 'x86_64-w64-mingw32', 'COMPILER_TOOLCHAIN': 'clang', 'GCC_ARCH': 'x86-64',
                'MAKEJOBS': '2', 'CLANG_PACKAGES_LTO': 'ON', 'CMAKE_GENERATOR': 'Ninja',
                'CMAKE_INSTALL_PREFIX': str(install), 'MINGW_INSTALL_PREFIX': str(workspace / 'build-x64/x86_64-w64-mingw32'),
                'SINGLE_SOURCE_LOCATION': str(workspace / 'sources'), 'RUSTUP_LOCATION': str(workspace / 'rustup'),
                'CMAKE_HOME_DIRECTORY': str(workspace / control['fixedInputs']['recipeArchivePrefix'])}
    if any(actual.get(key) != value for key, value in expected.items()):
        fail('Actual outer build configuration differs from restored host contract')
    # The fixed OpenMP recipe uses git am on LLVM_SRC. Files restored without
    # Git objects cannot satisfy it. Scope this import to the producer's actual
    # four targets and reject if the real generated dependency graph reaches it.
    graph_command = ['ninja', '-C', str(workspace / 'build-x64'), '-t', 'graph',
                     'llvm', 'rustup', 'llvm-clang', 'mpv']
    environment = {key: value for key, value in os.environ.items() if key not in ('GITHUB_TOKEN', 'GH_TOKEN')}
    graph = subprocess.run(graph_command, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                           timeout=30, env=environment)
    if graph.returncode or not 0 < len(graph.stdout) <= MAX_JSON or b'llvm-openmp' in graph.stdout:
        fail('Imported actual target graph requires unsupported original LLVM Git patch history')
    receipt = {'schema': 1, 'kind': 'BILIPAI_HOST_LLVM_IMPORT_TARGET_VALIDATION',
               'status': 'REAL_LLVM_VALIDATION_TARGET_SUCCEEDED', 'recordId': plan['record']['id'],
               'catalogSha256': CATALOG_SHA256, 'controlSha256': control_sha, 'currentContext': context(),
               'originalSnapshotDescriptorSha256': plan['record']['assets'][ASSETS[0]]['sha256'],
               'actualSourceBuildBindingSha256': manifest['actualSourceBuildBindingSha256'],
               'actualInstallTreeManifestSha256': manifest['actualInstallTreeManifestSha256'],
               'actualUsedSourceTreeManifestSha256': manifest['actualUsedSourceTreeManifestSha256'],
               'actualOuterCMakeCacheSha256': file_sha(cache), 'wholeTreesVerified': True,
               'actualTargetGraphSha256': sha(graph.stdout), 'actualTargetGraphCommand': graph_command,
               'llvmOpenmpNotReachableFromActualTargets': True,
               'hostAndInstalledToolsMatched': True, 'hostCompilerVersionAndTargetProbesExecuted': True,
               'freshLlvmCompileExecuted': False, 'copiedBuildStamps': False, 'gpuOrDriverTested': False}
    write_new(Path(control['validationReceipt']), receipt)


def finish_import(plan, output, control_sha, command):
    path = output / 'host-llvm-import-target-validation.json'
    target = json_file(path)
    if (target.get('status') != 'REAL_LLVM_VALIDATION_TARGET_SUCCEEDED'
            or target.get('controlSha256') != control_sha or target.get('currentContext') != context()
            or target.get('recordId') != plan['record']['id'] or target.get('freshLlvmCompileExecuted') is not False):
        fail('Real LLVM validation target receipt missing')
    receipt = {'schema': 1, 'kind': 'BILIPAI_HOST_LLVM_IMPORT_RECEIPT', 'status': IMPORT_STATUS,
               'recordId': plan['record']['id'], 'catalogSha256': CATALOG_SHA256,
               'controlSha256': control_sha, 'targetValidationReceiptSha256': file_sha(path),
               'currentContext': context(), 'actualLlvmTargetCommand': command, 'llvmTargetExitCode': 0,
               'originalSnapshotSourceCommit': plan['record']['sourceCommit'],
               'originalSnapshotSourceTag': plan['record']['sourceTag'],
               'originalSnapshotDescriptorSha256': plan['record']['assets'][ASSETS[0]]['sha256'],
               'originalEnvironmentSha256': plan['record']['assets'][ASSETS[4]]['sha256'],
               'actualSourceBuildBindingSha256': plan['manifest']['actualSourceBuildBindingSha256'],
               'toolchainImported': True, 'freshLlvmCompileExecuted': False,
               'accelerationMeasured': False, 'reproducible': False, 'gpuOrDriverTested': False}
    write_new(output / 'host-llvm-import-receipt.json', receipt)
    return output / 'host-llvm-import-receipt.json'


def verify_delivery_environment(directory, root, current, descriptor, imported=False):
    evidence = json_file(directory / ASSETS[4], digest(current.get('hostLlvmOriginalEnvironmentSha256')))
    if (evidence.get('schema') != 1 or evidence.get('kind') != 'BILIPAI_HOST_LLVM_ORIGINAL_ENVIRONMENT'
            or evidence.get('status') != 'REAL_COLD_LLVM_TARGET_SUCCEEDED'
            or evidence.get('originalContext') != {'sourceCommit': descriptor['ownrepoSourceCommit'],
                'sourceTag': descriptor['ownrepoSourceTag'], 'workflowRunId': descriptor['workflowRunId'],
                'workflowRunAttempt': descriptor['workflowRunAttempt']}
            or evidence.get('snapshotDescriptorSha256') != file_sha(directory / ASSETS[0])
            or evidence.get('snapshotManifestSha256') != file_sha(directory / ASSETS[1])
            or evidence.get('actualSourceBuildBindingSha256') != descriptor['actualSourceBuildBindingSha256']
            or evidence.get('containerImage') != descriptor['containerImage']
            or evidence.get('freshLlvmCompileExecuted') is not True or evidence.get('llvmTargetExitCode') != 0
            or evidence.get('toolchainImported') is not False or evidence.get('gpuOrDriverTested') is not False
            or not evidence.get('originalHostEnvironment') or not evidence.get('originalInstalledHostTools')
            or (not imported and (evidence.get('originalContext') != context()
                or evidence.get('collectorSourceSha256') != file_sha(Path(__file__))))):
        fail('Actual original environment evidence is not bound to source/export/build receipt')
    digest(evidence.get('collectorSourceSha256'))
    with tarfile.open(directory / ASSETS[3], 'r:gz') as archive:
        member = archive.getmember('actual-modified-build-recipes/packages/bilipai-host-llvm-import.py')
        if not member.isfile() or member.size > MAX_JSON:
            fail('Original source collector is missing from complete source bundle')
        consume(archive.extractfile(member), member.size, evidence['collectorSourceSha256'])
    return evidence


def verify_delivery_import(directory, root, current, commit, tag):
    receipt = json_file(directory / 'host-llvm-import-receipt.json', digest(current.get('hostLlvmImportReceiptSha256')))
    control = json_file(directory / 'host-llvm-import-control.json', digest(receipt.get('controlSha256')))
    target = json_file(directory / 'host-llvm-import-target-validation.json', digest(receipt.get('targetValidationReceiptSha256')))
    workspace = Path(control['workspace'])
    plan = inspect_optional(directory, root, workspace, control['fixedInputs'], control['containerImage'], control['originalInitialBuildCommand'])
    if plan is None:
        fail('Import delivery lost trusted immutable snapshot')
    record, manifest = plan['record'], plan['manifest']
    expected_context = receipt.get('currentContext', {})
    if (expected_context.get('sourceCommit') != commit or expected_context.get('sourceTag') != tag
            or expected_context != context() or control.get('currentContext') != expected_context
            or target.get('currentContext') != expected_context
            or receipt.get('schema') != 1 or receipt.get('kind') != 'BILIPAI_HOST_LLVM_IMPORT_RECEIPT'
            or receipt.get('status') != IMPORT_STATUS or current.get('hostLlvmSnapshotStatus') != IMPORT_STATUS
            or current.get('hostLlvmToolchainImported') is not True
            or control.get('schema') != 1 or control.get('kind') != 'BILIPAI_HOST_LLVM_IMPORT_CONTROL'
            or control.get('repositoryRoot') != str(root) or control.get('snapshotDirectory') != str(directory)
            or control.get('validationReceipt') != str(directory / 'host-llvm-import-target-validation.json')
            or any(item.get('recordId') != record['id'] or item.get('catalogSha256') != CATALOG_SHA256
                or item.get('actualSourceBuildBindingSha256') != manifest['actualSourceBuildBindingSha256']
                for item in (receipt, control, target))
            or receipt.get('controlSha256') != target.get('controlSha256')
            or receipt.get('actualLlvmTargetCommand') != ['ninja', '-C', str(workspace / 'build-x64'), '-j2', 'llvm']
            or receipt.get('llvmTargetExitCode') != 0 or receipt.get('toolchainImported') is not True
            or receipt.get('originalSnapshotSourceCommit') != record['sourceCommit']
            or receipt.get('originalSnapshotSourceTag') != record['sourceTag']
            or receipt.get('originalEnvironmentSha256') != record['assets'][ASSETS[4]]['sha256']
            or current.get('hostLlvmOriginalEnvironmentSha256') != record['assets'][ASSETS[4]]['sha256']
            or any(item.get('originalSnapshotDescriptorSha256') != record['assets'][ASSETS[0]]['sha256'] for item in (receipt, target))
            or target.get('schema') != 1 or target.get('kind') != 'BILIPAI_HOST_LLVM_IMPORT_TARGET_VALIDATION'
            or target.get('status') != 'REAL_LLVM_VALIDATION_TARGET_SUCCEEDED'
            or target.get('actualInstallTreeManifestSha256') != manifest['actualInstallTreeManifestSha256']
            or target.get('actualUsedSourceTreeManifestSha256') != manifest['actualUsedSourceTreeManifestSha256']
            or target.get('wholeTreesVerified') is not True or target.get('hostAndInstalledToolsMatched') is not True
            or target.get('hostCompilerVersionAndTargetProbesExecuted') is not True
            or target.get('copiedBuildStamps') is not False
            or target.get('llvmOpenmpNotReachableFromActualTargets') is not True
            or target.get('actualTargetGraphCommand') != ['ninja', '-C', str(workspace / 'build-x64'),
                '-t', 'graph', 'llvm', 'rustup', 'llvm-clang', 'mpv']
            or any(item.get('freshLlvmCompileExecuted') is not False or item.get('gpuOrDriverTested') is not False for item in (receipt, target))
            or receipt.get('reproducible') is not False or receipt.get('accelerationMeasured') is not False):
        fail('Delivery import/current build/original snapshot closure differs')
    digest(target.get('actualOuterCMakeCacheSha256'))
    digest(target.get('actualTargetGraphSha256'))
    return record


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('mode', choices=('validate-target',))
    parser.add_argument('--control', type=Path, required=True)
    parser.add_argument('--control-sha256', required=True)
    parser.add_argument('--workspace', type=Path, required=True)
    parser.add_argument('--source-dir', type=Path, required=True)
    parser.add_argument('--install-dir', type=Path, required=True)
    parser.add_argument('--repository-root', type=Path, required=True)
    args = parser.parse_args()
    validate_target(args.control, args.control_sha256, args.workspace.resolve(), args.source_dir.resolve(),
                    args.install_dir.resolve(), args.repository_root.resolve())


if __name__ == '__main__':
    main()
