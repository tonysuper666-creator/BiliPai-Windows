#!/usr/bin/env python3
"""Save one successful source-built candidate in an own-repository draft.

No direct tag API, publication, Actions artifact, cache, SDK or native execution.
The pre-existing source tag must be a lightweight tag at the actual build commit.
The owner must keep that tag fixed: create-release has no existing-tag-only CAS.
Source parts are ranges of the complete original tar, not trimmed source trees.
"""
import argparse
import hashlib
import http.client
import importlib.util
import json
import os
import re
import subprocess
import sys
from pathlib import Path
from urllib.parse import quote, urlencode

REPOSITORY = 'tonysuper666-creator/BiliPai-Windows'
VARIANT = 'bilipai-veyra-rtx-core-v1'
PART_BYTES = 1 << 30
RELEASE_ASSET_LIMIT = 2 << 30  # GitHub requires each asset strictly below 2 GiB.
MAX_ASSETS = 1000
API_VERSION = '2026-03-10'


class DeliveryError(RuntimeError):
    pass


class RangeReader:
    def __init__(self, source, offset, length):
        self.source, self.remaining = source, length
        source.seek(offset)

    def read(self, count=-1):
        if not self.remaining:
            return b''
        count = self.remaining if count < 0 else min(count, self.remaining)
        result = self.source.read(count)
        if not result:
            raise DeliveryError('Local asset ended before its declared range')
        self.remaining -= len(result)
        return result


def range_sha(path, offset, length):
    digest = hashlib.sha256()
    with path.open('rb') as source:
        reader = RangeReader(source, offset, length)
        while block := reader.read(1 << 20):
            digest.update(block)
    return digest.hexdigest()


def owned_file(directory, name):
    if not isinstance(name, str) or not re.fullmatch(r'[A-Za-z0-9_.-]+', name):
        raise DeliveryError('Invalid local asset name')
    path = directory / name
    if path.is_symlink() or not path.is_file() or path.resolve().parent != directory:
        raise DeliveryError('Asset must be a regular file in the build output')
    return path


def api(method, path, token, *, value=None, source=None, length=None, upload=False):
    host = 'uploads.github.com' if upload else 'api.github.com'
    headers = {'Authorization': 'Bearer ' + token, 'Accept': 'application/vnd.github+json',
               'X-GitHub-Api-Version': API_VERSION, 'User-Agent': 'BiliPai-Own-Draft-Candidate'}
    body = source
    if value is not None:
        body = json.dumps(value).encode('utf-8')
        headers['Content-Type'] = 'application/json'
        length = len(body)
    if upload:
        headers['Content-Type'] = 'application/octet-stream'
    if length is not None:
        headers['Content-Length'] = str(length)
    connection = http.client.HTTPSConnection(host, timeout=900 if upload else 60)
    try:
        # http.client does not follow redirects or print request headers.
        connection.request(method, path, body=body, headers=headers)
        response = connection.getresponse()
        raw = response.read((2 << 20) + 1)
        if len(raw) > 2 << 20 or response.status not in (200, 201, 204):
            raise DeliveryError('GitHub request rejected with HTTP ' + str(response.status))
        return json.loads(raw) if raw else None
    except DeliveryError:
        raise
    except Exception as error:
        # No response body, Authorization header, environment or token is logged.
        raise DeliveryError('GitHub request failed: ' + type(error).__name__) from None
    finally:
        connection.close()


def require_tag(base, tag, commit, token):
    ref = api('GET', base + '/git/ref/tags/' + quote(tag, safe=''), token)
    if ref.get('ref') != 'refs/tags/' + tag or ref.get('object') != {
            'sha': commit, 'type': 'commit',
            'url': 'https://api.github.com/repos/' + REPOSITORY + '/git/commits/' + commit}:
        raise DeliveryError('Pre-existing lightweight source tag does not identify this build')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--variant', choices=(VARIANT, 'bilipai-veyra-rtx-present-v1'), default='bilipai-veyra-rtx-present-v1',
                        help='Upload the matching current presentation candidate by default; core-v1 must be selected explicitly')
    args = parser.parse_args()
    variant = args.variant
    directory = args.output.resolve(strict=True)
    tag, commit = os.environ.get('GITHUB_REF_NAME', ''), os.environ.get('GITHUB_SHA', '')
    if (os.environ.get('GITHUB_ACTIONS') != 'true'
            or os.environ.get('GITHUB_REPOSITORY') != REPOSITORY
            or os.environ.get('GITHUB_EVENT_NAME') != 'workflow_dispatch'
            or os.environ.get('GITHUB_REF_TYPE') != 'tag'
            or not re.fullmatch(r'rtx-source-[A-Za-z0-9][A-Za-z0-9._-]{0,100}', tag)
            or not re.fullmatch(r'[0-9a-f]{40}', commit)):
        raise DeliveryError('Only an explicit own-repository source-tag build can save a draft')
    # The step token stays in this process; even the read-only Git child does not receive it.
    repository_root = Path(__file__).resolve().parents[3]
    child_env = {key: value for key, value in os.environ.items() if key != 'GITHUB_TOKEN'}
    actual_head = subprocess.check_output(
        ['git', '-c', 'safe.directory=' + str(repository_root), 'rev-parse', 'HEAD'],
        cwd=repository_root, text=True, env=child_env).strip()
    if actual_head != commit:
        raise DeliveryError('Checked-out source differs from the workflow commit')
    status_path = owned_file(directory, 'build-status.json')
    receipt_path = owned_file(directory, 'build-receipt.json')
    descriptor_path = owned_file(directory, 'runtime-descriptor.json')
    status, receipt, descriptor = [json.loads(path.read_text(encoding='utf-8'))
                                   for path in (status_path, receipt_path, descriptor_path)]
    if (status.get('success') is not True or status.get('binaryProduced') is not True
            or status.get('gpuOrDriverTested') is not False
            or receipt.get('schema') != 2 or receipt.get('variant') != variant
            or receipt.get('ownrepoSourceCommit') != commit
            or receipt.get('gpuOrDriverTested') is not False
            or receipt.get('closedSdkOrRuntimeIncluded') is not False
            or descriptor.get('schema') != 2 or descriptor.get('variant') != variant
            or descriptor.get('deliveryStatus') != 'LOCAL_ARTIFACT_ONLY_NOT_RELEASED'
            or descriptor.get('rtxCoreBridgeVerified') is not False
            or descriptor.get('closedSdkOrRuntimeIncluded') is not False
            or descriptor.get('buildReceiptSha256') != range_sha(receipt_path, 0, receipt_path.stat().st_size)
            or status.get('descriptorSha256') != range_sha(descriptor_path, 0, descriptor_path.stat().st_size)):
        raise DeliveryError('Only a successful unverified source candidate is eligible')
    if status.get('variant') != variant or descriptor['artifact']['fileName'] != variant + '-x64.zip' or descriptor['sourceBundle']['fileName'] != variant + '-source-materials.tar.gz':
        raise DeliveryError('Selected source variant and actual output asset names differ')
    if variant == 'bilipai-veyra-rtx-present-v1':
        expected_presentation = {'filterSourceManifestSha256': '7aa01708316eb55a9a4fb7106f02d67932e1d8ae47480c7e19b920951611e338', 'presentationProtocolVersion': 1, 'presentationProperty': 'bilipai-rtx-presentation', 'upstreamEditsSha256': '9c4b625ca178a34d67099234863a093a097cdb38f68bed25cc15715a07ce4bea', 'sourcePatchHelperSha256': '8cae5dc860f06c86a101f1bbba77d9822e4dd6f6b75a86bf8f483e44d2e37870'}
        if any(receipt.get(key) != value or descriptor.get(key) != value for key, value in expected_presentation.items()) or len(receipt.get('sourceGraph', [])) != 17 or len(receipt.get('filterSourceFiles', [])) != 5:
            raise DeliveryError('Future presentation delivery needs its actual complete source graph and protocol')
        # Compare every actual receipt target with whole-byte pinned source inputs.
        inputs = repository_root / 'desktop/third-party/libmpv/build/rtx-present-v1'
        materials = {'manifest': ('bilipai-rtx-presentation-source-manifest.json', '7aa01708316eb55a9a4fb7106f02d67932e1d8ae47480c7e19b920951611e338'), 'upstream': ('presentation-edits.json', '9c4b625ca178a34d67099234863a093a097cdb38f68bed25cc15715a07ce4bea'), 'registration': ('filter-registration-edits.json', '59d1c4ffbb4506d9d81586d6146ba4a54a0882557f1c8861a858cbe24cd2c5cf')}
        measured = {}
        for label, (leaf, digest) in materials.items():
            data = owned_file(inputs.resolve(strict=True), leaf).read_bytes()
            if hashlib.sha256(data).hexdigest() != digest:
                raise DeliveryError('Pinned presentation delivery source input changed')
            measured[label] = json.loads(data)
        manifest, upstream, registration = measured['manifest'], measured['upstream'], measured['registration']
        nvidia = manifest['originalNvidiaPatch']
        graph = [{'path': nvidia['sourcePath'], 'beforeSha256': nvidia['originalSha256'], 'afterSha256': nvidia['patchedSha256']}] + [{'path': row['path'], 'beforeSha256': row['beforeSHA256'], 'afterSha256': row['afterSHA256']} for row in registration] + [{'path': row['path'], 'beforeSha256': row['beforeSha256'], 'afterSha256': row['afterSha256']} for row in upstream]
        if len(graph) != 17 or len({row['path'] for row in graph}) != 17 or receipt.get('sourceGraph') != graph or receipt.get('filterSourceFiles') != manifest['sourceFiles'] or type(receipt.get('presentationProtocolVersion')) is not int:
            raise DeliveryError('Actual presentation delivery source graph or private source closure differs')
    artifact = owned_file(directory, descriptor['artifact']['fileName'])
    bundle = owned_file(directory, descriptor['sourceBundle']['fileName'])
    if (range_sha(artifact, 0, artifact.stat().st_size) != descriptor['artifact']['archiveSha256']
            or range_sha(bundle, 0, bundle.stat().st_size) != descriptor['sourceBundle']['sha256']
            or descriptor['sourceBundle']['sha256'] != receipt.get('sourceBundleSha256')):
        raise DeliveryError('Candidate or complete corresponding source hash changed')
    host_descriptor_path = owned_file(directory, 'host-llvm-source-snapshot-descriptor.json')
    host_descriptor = json.loads(host_descriptor_path.read_text(encoding='utf-8'))
    host_manifest_path = owned_file(directory, 'host-llvm-snapshot-manifest.json')
    host_manifest = json.loads(host_manifest_path.read_text(encoding='utf-8'))
    snapshot_inputs = json.loads((repository_root / 'desktop/third-party/libmpv/build/rtx-core-v1/host-llvm-snapshot-inputs.json').read_text(encoding='utf-8'))
    host_kind = 'BILIPAI_HOST_LLVM_SOURCE_SNAPSHOT'
    import_helper_path = repository_root / 'desktop/tools/native/import-host-llvm-source-snapshot.py'
    if range_sha(import_helper_path, 0, import_helper_path.stat().st_size) != '766d0c2602f97c187bec03992b5417530d7a82f3d1604a45b2a05fb7d38f5bb4':
        raise DeliveryError('Shared reviewed importer/collector source changed')
    import_spec = importlib.util.spec_from_file_location('bilipai_host_import_delivery', import_helper_path)
    if import_spec is None or import_spec.loader is None:
        raise DeliveryError('Reviewed host snapshot verifier unavailable')
    import_module = importlib.util.module_from_spec(import_spec)
    import_spec.loader.exec_module(import_module)
    import_module.load_catalog(repository_root)
    import_module.load_exporter(repository_root)
    imported = receipt.get('hostLlvmSnapshotStatus') == import_module.IMPORT_STATUS
    origin_commit, origin_tag = commit, tag
    import_assets = []
    if imported:
        original_record = import_module.verify_delivery_import(directory, repository_root, receipt, commit, tag)
        origin_commit, origin_tag = original_record['sourceCommit'], original_record['sourceTag']
        import_assets = [owned_file(directory, name) for name in ('host-llvm-import-control.json',
                         'host-llvm-import-target-validation.json', 'host-llvm-import-receipt.json')]
    elif (receipt.get('hostLlvmToolchainImported') is not False
            or receipt.get('hostLlvmFreshCompileExecuted') is not True
            or receipt.get('hostLlvmImportReceiptSha256') is not None):
        raise DeliveryError('Cold build must retain genuine LLVM compilation provenance')
    if (receipt.get('hostLlvmToolchainImported') is not imported
            or receipt.get('hostLlvmFreshCompileExecuted') is not (not imported)
            or receipt.get('hostLlvmAccelerationMeasured') is not False):
        raise DeliveryError('Host LLVM import/fresh compilation provenance differs')
    environment_path = owned_file(directory, 'host-llvm-original-environment.json')
    import_module.verify_delivery_environment(directory, repository_root, receipt, host_descriptor, imported)
    if (host_descriptor.get('schema') != 2 or host_descriptor.get('kind') != host_kind
            or host_manifest.get('schema') != 2 or host_manifest.get('kind') != host_kind
            or snapshot_inputs.get('schema') != 2 or snapshot_inputs.get('sourceBindingSchema') != 1
            or host_descriptor.get('snapshotStatus') != 'BUILT_FROM_SOURCE_THIS_RUN_EXPORT_ONLY'
            or host_manifest.get('snapshotStatus') != 'BUILT_FROM_SOURCE_THIS_RUN_EXPORT_ONLY'
            or host_descriptor.get('deliveryStatus') != 'LOCAL_SNAPSHOT_ONLY_NOT_RELEASED'
            or host_descriptor.get('reuseReady') is not False
            or receipt.get('hostLlvmSnapshotStatus') != (import_module.IMPORT_STATUS if imported else 'BUILT_FROM_SOURCE_THIS_RUN_EXPORT_ONLY')
            or receipt.get('hostLlvmSnapshotDescriptorSha256') != range_sha(host_descriptor_path, 0, host_descriptor_path.stat().st_size)
            or host_descriptor.get('snapshotManifest') != {'fileName': host_manifest_path.name,
                'bytes': host_manifest_path.stat().st_size, 'sha256': range_sha(host_manifest_path, 0, host_manifest_path.stat().st_size)}
            or any(item.get('ownrepoSourceCommit') != origin_commit or item.get('ownrepoSourceTag') != origin_tag
                   or item.get('containerImage') != receipt.get('containerImage')
                   or item.get('recipeCommit') != receipt.get('recipeCommit')
                   or item.get('recipeArchiveSha256') != receipt.get('recipeArchiveSha256')
                   or item.get('helperSha256') != snapshot_inputs.get('helperSha256')
                   or item.get('hostTargetExitCode') != 0 or item.get('toolchainImported') is not False
                   or item.get('reproducible') is not False or item.get('gpuOrDriverTested') is not False
                   or item.get('closedSdkOrRuntimeIncluded') is not False
                   or item.get('hostCompilerOrNativeTestedByExporter') is not False
                   for item in (host_descriptor, host_manifest))
            or not re.fullmatch(r'[0-9a-f]{40}', host_descriptor.get('sourceCommit', ''))
            or host_manifest.get('sourceCommit') != host_descriptor.get('sourceCommit')
            or host_manifest.get('sourceTree') != host_descriptor.get('sourceTree')
            or not host_manifest.get('actualInstallEntries')
            or not host_manifest.get('completeCanonicalSourceEntries')
            or not host_manifest.get('completeLicenseEntries')):
        raise DeliveryError('Only the genuine untested own-source host snapshot may accompany this successful build')
    binding = host_manifest.get('actualSourceBuildBinding', {})
    built_source = binding.get('prebuild', {})
    binding_sha = hashlib.sha256((json.dumps(binding, sort_keys=True, indent=2) + '\n').encode('utf-8')).hexdigest()
    absolute_workspace = host_manifest.get('originalAbsoluteWorkspace', '')
    expected_source = absolute_workspace + '/sources/llvm'
    expected_build = absolute_workspace + '/build-x64/toolchain/llvm-prefix/src/llvm-build'
    expected_config = binding.get('resolvedConfigurationAssertions', {})
    capture = host_manifest.get('configCaptureReceipt', {})
    prebuild_capture = host_manifest.get('prebuildConfigCaptureReceipt', {})
    if (binding.get('schema') != 1 or binding.get('state') != 'PREBUILD_INSTALL_POSTCLEANUP_SOURCE_AND_CONFIG_BOUND'
            or host_descriptor.get('sourceBindingSchema') != 1 or host_manifest.get('sourceBindingSchema') != 1
            or host_descriptor.get('actualSourceBuildBinding') != binding
            or host_descriptor.get('actualSourceBuildBindingSha256') != binding_sha
            or host_manifest.get('actualSourceBuildBindingSha256') != binding_sha
            or receipt.get('hostLlvmSnapshotSourceBindingSha256') != binding_sha
            or built_source.get('schema') != 1 or not built_source
            or binding.get('postinstall') != built_source or binding.get('postcleanup') != built_source
            or built_source.get('sourceCommit') != host_manifest.get('sourceCommit')
            or built_source.get('sourceTree') != host_manifest.get('sourceTree')
            or built_source.get('sourceRemote') != 'https://github.com/llvm/llvm-project.git'
            or built_source.get('gitStatusPorcelainZHex') != ''
            or built_source.get('actualSourceDirectory') != expected_source
            or binding.get('actualSourceDirectory') != expected_source
            or binding.get('actualBuildDirectory') != expected_build
            or not re.fullmatch(r'[0-9a-f]{40}', built_source.get('sourceTree', ''))
            or not re.fullmatch(r'[0-9a-f]{64}', built_source.get('actualSourceWorktreeManifestSha256', ''))
            or built_source.get('completeSourceLsTreeSha256') != host_manifest.get('completeSourceLsTreeSha256')
            or not re.fullmatch(r'[0-9a-f]{64}', built_source.get('completeSourceLsTreeSha256', ''))
            or type(built_source.get('actualSourceWorktreeEntries')) is not int or built_source['actualSourceWorktreeEntries'] <= 1
            or built_source.get('actualSourceWorktreeManifestSha256') != host_manifest.get('actualUsedSourceTreeManifestSha256')
            or any(not re.fullmatch(r'[0-9a-f]{64}', binding.get(key, ''))
                   for key in ('prebuildCaptureReceiptSha256', 'postinstallCaptureReceiptSha256'))
            or binding.get('prebuildCaptureReceiptSha256')
                != hashlib.sha256((json.dumps(prebuild_capture, sort_keys=True, indent=2) + '\n').encode('utf-8')).hexdigest()
            or prebuild_capture.get('schema') != 2 or prebuild_capture.get('sourceBindingSchema') != 1
            or prebuild_capture.get('stage') != 'AFTER_CONFIGURATION_BEFORE_REAL_LLVM_BUILD'
            or prebuild_capture.get('helperSha256') != snapshot_inputs.get('helperSha256')
            or prebuild_capture.get('actualBuildSource') != built_source
            or prebuild_capture.get('actualBuildDirectory') != expected_build
            or prebuild_capture.get('actualSourceDirectory') != expected_source
            or prebuild_capture.get('resolvedConfigurationAssertions') != expected_config
            or binding.get('postinstallCaptureReceiptSha256')
                != hashlib.sha256((json.dumps(capture, sort_keys=True, indent=2) + '\n').encode('utf-8')).hexdigest()
            or capture.get('schema') != 2 or capture.get('sourceBindingSchema') != 1
            or capture.get('stage') != 'AFTER_REAL_LLVM_INSTALL_BEFORE_RECIPE_CLEANUP'
            or capture.get('helperSha256') != snapshot_inputs.get('helperSha256')
            or capture.get('actualBuildSource') != built_source
            or capture.get('prebuildCaptureReceiptSha256') != binding.get('prebuildCaptureReceiptSha256')
            or capture.get('actualBuildDirectory') != expected_build or capture.get('actualSourceDirectory') != expected_source
            or capture.get('resolvedConfigurationAssertions') != expected_config
            or host_manifest.get('resolvedConfigurationAssertions') != expected_config
            or expected_config.get('CMAKE_HOME_DIRECTORY') != expected_source + '/llvm'
            or expected_config.get('CMAKE_CACHEFILE_DIR') != expected_build
            or expected_config.get('CMAKE_INSTALL_PREFIX') != host_manifest.get('originalAbsoluteInstallPrefix')):
        raise DeliveryError('Actual compiler prebuild/install/cleanup source and configuration identity is not fully bound')
    pre_files = {row.get('path'): row for row in prebuild_capture.get('files', [])}
    post_files = {row.get('path'): row for row in capture.get('files', [])}
    expected_pre_files = {'CMakeCache.txt', 'build.ninja', 'CMakeFiles/rules.ninja',
                          'actual-source-worktree.json', 'actual-source-ls-tree.bin'}
    if (set(pre_files) != expected_pre_files or len(prebuild_capture.get('files', [])) != 5
            or set(post_files) != expected_pre_files | {'install_manifest.txt'} or len(capture.get('files', [])) != 6
            or any(pre_files[name] != post_files[name] for name in expected_pre_files)
            or prebuild_capture.get('workspace') != absolute_workspace or capture.get('workspace') != absolute_workspace):
        raise DeliveryError('Captured prebuild and installed source/config file identities differ')
    host_bundles = []
    for key, expected_name in (('installArchive', 'host-llvm-install.tar.gz'),
                               ('correspondingSourceArchive', 'host-llvm-corresponding-source.tar.gz')):
        record = host_descriptor.get(key, {})
        path = owned_file(directory, expected_name)
        if (record.get('fileName') != expected_name or record.get('bytes') != path.stat().st_size
                or record.get('sha256') != range_sha(path, 0, path.stat().st_size) or path.stat().st_size <= 0):
            raise DeliveryError('Complete host toolchain/source archive hash changed')
        host_bundles.append((key, path))
    assets = []
    for path in (artifact, descriptor_path, receipt_path, status_path, host_descriptor_path, host_manifest_path, environment_path, *import_assets):
        size = path.stat().st_size
        if not 0 < size < RELEASE_ASSET_LIMIT:
            raise DeliveryError('Release asset must be nonempty and strictly below 2 GiB')
        assets.append({'name': path.name, 'path': path, 'offset': 0, 'bytes': size,
                       'sha256': range_sha(path, 0, size)})
    parts = []
    size = bundle.stat().st_size
    for index, offset in enumerate(range(0, size, PART_BYTES), 1):
        length = min(PART_BYTES, size - offset)
        name = bundle.name if size <= PART_BYTES else bundle.name + '.part' + str(index).zfill(4)
        row = {'name': name, 'offset': offset, 'bytes': length,
               'sha256': range_sha(bundle, offset, length)}
        parts.append(row)
        assets.append({**row, 'path': bundle})
    host_delivery_bundles = {}
    for key, path in host_bundles:
        host_parts = []
        host_size = path.stat().st_size
        for index, offset in enumerate(range(0, host_size, PART_BYTES), 1):
            length = min(PART_BYTES, host_size - offset)
            name = path.name if host_size <= PART_BYTES else path.name + '.part' + str(index).zfill(4)
            row = {'name': name, 'offset': offset, 'bytes': length,
                   'sha256': range_sha(path, offset, length)}
            host_parts.append(row)
            assets.append({**row, 'path': path})
        host_delivery_bundles[key] = {'fileName': path.name, 'bytes': host_size,
                                     'sha256': range_sha(path, 0, host_size), 'parts': host_parts}
    manifest = {'schema': 1, 'sourceCommit': commit, 'sourceTag': tag,
                'delivery': 'OWN_REPOSITORY_DRAFT_ONLY', 'draft': True,
                'hostToolchain': {'kind': host_kind, 'snapshotStatus': receipt['hostLlvmSnapshotStatus'],
                    'originalSnapshotStatus': host_descriptor['snapshotStatus'],
                    'originalOwnrepoSourceCommit': origin_commit, 'originalOwnrepoSourceTag': origin_tag,
                    'toolchainImported': imported, 'freshLlvmCompileExecuted': not imported,
                    'originalEnvironmentSha256': receipt['hostLlvmOriginalEnvironmentSha256'],
                    'importReceiptSha256': receipt['hostLlvmImportReceiptSha256'],
                    'accelerationMeasured': False,
                    'reuseReady': False, 'sourceCommit': host_descriptor['sourceCommit'],
                    'sourceBindingSchema': 1, 'actualSourceBuildBindingSha256': binding_sha,
                    'actualSourceBuildBinding': binding,
                    'descriptorSha256': range_sha(host_descriptor_path, 0, host_descriptor_path.stat().st_size),
                    'snapshotManifestSha256': range_sha(host_manifest_path, 0, host_manifest_path.stat().st_size),
                    'archives': host_delivery_bundles,
                    'trustRequirement': 'Future import requires independently reviewed manifest and exact own asset identity frozen in consumer source; this draft does not activate reuse.'},
                'sourceBundle': {'fileName': bundle.name, 'bytes': size,
                                 'sha256': descriptor['sourceBundle']['sha256'], 'parts': parts},
                'reassembly': 'Concatenate parts in listed offset order; verify every part and then the complete SHA256 before using the original sourceBundle file.',
                'assets': [{key: row[key] for key in ('name', 'bytes', 'sha256')} for row in assets]}
    manifest_path = directory / 'draft-delivery-manifest.json'
    if manifest_path.exists():
        raise DeliveryError('Draft delivery manifest already exists; no implicit retry')
    manifest_path.write_text(json.dumps(manifest, sort_keys=True, indent=2) + '\n', encoding='utf-8')
    assets.append({'name': manifest_path.name, 'path': manifest_path, 'offset': 0,
                   'bytes': manifest_path.stat().st_size,
                   'sha256': range_sha(manifest_path, 0, manifest_path.stat().st_size)})
    if not parts or len(assets) > MAX_ASSETS or len({row['name'] for row in assets}) != len(assets):
        raise DeliveryError('Release asset inventory exceeds its documented bounds')
    # GITHUB_TOKEN is provided only to this successful post-build step, in memory.
    # It is never copied to a file or passed to a child command.
    token = os.environ.get('GITHUB_TOKEN')
    if not token:
        raise DeliveryError('Draft delivery requires the job-scoped token')
    base = '/repos/' + REPOSITORY
    repository = api('GET', base, token)
    if repository.get('full_name') != REPOSITORY or repository.get('private') is not False:
        raise DeliveryError('Draft destination must remain the exact own public repository')
    require_tag(base, tag, commit, token)
    release_id = None
    try:
        release = api('POST', base + '/releases', token, value={
            'tag_name': tag, 'name': 'UNVERIFIED RTX core source candidate ' + tag,
            'body': 'Source-built candidate at ' + commit + '. No GPU/visible-effect verification; no NVIDIA SDK/runtime included. Complete corresponding source is delivered as the ordered source bundle parts and checksummed manifest. This draft is not a product release.',
            'draft': True, 'prerelease': True, 'make_latest': 'false',
            'generate_release_notes': False})
        if (not isinstance(release.get('id'), int) or release.get('draft') is not True
                or release.get('tag_name') != tag or release.get('published_at') is not None):
            raise DeliveryError('GitHub did not create the required unpublished draft')
        release_id = release['id']
        # The API has no atomic existing-tag-only precondition. Detect any tag
        # mutation after draft creation before sending candidate/source bytes.
        require_tag(base, tag, commit, token)
        uploaded = {}
        for row in assets:
            with row['path'].open('rb') as source:
                asset = api('POST', base + '/releases/' + str(release_id) + '/assets?'
                            + urlencode({'name': row['name']}), token,
                            source=RangeReader(source, row['offset'], row['bytes']),
                            length=row['bytes'], upload=True)
            if (asset.get('name') != row['name'] or asset.get('state') != 'uploaded'
                    or asset.get('size') != row['bytes']
                    or asset.get('digest') != 'sha256:' + row['sha256']
                    or type(asset.get('id')) is not int or asset['id'] <= 0):
                raise DeliveryError('Uploaded draft asset differs from its local range')
            uploaded[row['name']] = {key: asset[key] for key in
                                     ('id', 'name', 'state', 'size', 'digest')}
        require_tag(base, tag, commit, token)
        final = api('GET', base + '/releases/' + str(release_id), token)
        final_assets = final.get('assets')
        if (final.get('draft') is not True or final.get('published_at') is not None
                or final.get('tag_name') != tag
                or not isinstance(final_assets, list) or len(final_assets) != len(assets)
                or {a.get('name') for a in final_assets} != set(uploaded)
                or any({key: a.get(key) for key in ('id', 'name', 'state', 'size', 'digest')}
                       != uploaded[a['name']] for a in final_assets)):
            raise DeliveryError('Draft or exact asset inventory changed during delivery')
        print('Saved unverified own-repository draft ' + str(release_id)
              + ' with ' + str(len(assets)) + ' checksummed assets; no publication API called.')
    except Exception:
        if release_id is not None:
            # GET draft then DELETE has no documented atomic draft-only precondition.
            # A human could publish between those calls. Leave only our draft id for
            # explicit owner cleanup instead of deleting a possibly public release.
            print('Delivery incomplete for newly created draft ' + str(release_id)
                  + '; explicit owner cleanup may be needed. No retry or publication.',
                  file=sys.stderr)
        raise


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        message = str(error) if isinstance(error, DeliveryError) else type(error).__name__
        print('Draft candidate delivery stopped: ' + message, file=sys.stderr)
        raise SystemExit(1)
