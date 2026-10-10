#!/usr/bin/env python3
"""Download one explicitly reviewed own HOST LLVM record, without extraction.

The source-owned import catalog remains empty until independent qualification.
Only exact own draft asset IDs and frozen part bytes are accepted. The existing
importer still qualifies the complete source, environment, and extraction.
"""
import argparse
import ctypes
import hashlib
import http.client
import importlib.util
import json
import os
import re
import stat
import sys
import uuid
from pathlib import Path
from urllib.parse import quote, urlsplit

REPOSITORY = 'tonysuper666-creator/BiliPai-Windows'
BASE = '/repos/' + REPOSITORY
IMPORTER_SHA256 = '360d6daaa2f07275de57b7160ef1f5350c492e52d6e1b1cce8c1f77eb606e4bb'
API_VERSION = '2026-03-10'
API_JSON_LIMIT = 2 << 20
PART_BYTES = 1 << 30
MAX_PARTS = 64
MAX_RELEASE_ASSETS = 1000
REDIRECT_HOSTS = frozenset(('release-assets.githubusercontent.com', 'objects.githubusercontent.com'))
JSON_ASSETS = frozenset(('host-llvm-source-snapshot-descriptor.json',
                        'host-llvm-snapshot-manifest.json', 'host-llvm-original-environment.json'))
ARCHIVES = frozenset(('host-llvm-install.tar.gz', 'host-llvm-corresponding-source.tar.gz'))


class DownloadError(RuntimeError):
    """Only fixed diagnostic codes; no remote body, URL, token, or exception text."""


def reject(code):
    raise DownloadError(code)


def duplicate_safe_json(raw):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                reject('DUPLICATE_API_JSON_KEY')
            result[key] = value
        return result
    return json.loads(raw, object_pairs_hook=pairs,
                      parse_constant=lambda unused: reject('NONFINITE_API_JSON_NUMBER'))


def require_positive(value, maximum=(1 << 63) - 1):
    if type(value) is not int or not 0 < value <= maximum:
        reject('INVALID_POSITIVE_BOUND')
    return value


def require_sha(value):
    if not isinstance(value, str) or not re.fullmatch(r'[0-9a-f]{64}', value):
        reject('INVALID_FROZEN_SHA256')
    return value


def api_json(path, token):
    # Paths are constructed from fixed repository plus checked integers/tags.
    connection = http.client.HTTPSConnection('api.github.com', timeout=60)
    try:
        connection.request('GET', path, headers={
            'Authorization': 'Bearer ' + token,
            'Accept': 'application/vnd.github+json',
            'X-GitHub-Api-Version': API_VERSION,
            'User-Agent': 'BiliPai-Own-Reviewed-HOST-Download'})
        response = connection.getresponse()
        if response.status != 200:
            reject('API_METADATA_HTTP_REJECTED')
        raw = response.read(API_JSON_LIMIT + 1)
        if not 0 < len(raw) <= API_JSON_LIMIT:
            reject('API_METADATA_LENGTH_REJECTED')
        return duplicate_safe_json(raw)
    finally:
        connection.close()


def redirect_target(location):
    if (not isinstance(location, str) or not 0 < len(location) <= 16384
            or any(ord(char) < 33 or ord(char) > 126 for char in location)):
        reject('ASSET_REDIRECT_TEXT_REJECTED')
    parsed = urlsplit(location)
    if (parsed.scheme != 'https' or parsed.hostname not in REDIRECT_HOSTS
            or parsed.netloc != parsed.hostname or parsed.username is not None
            or parsed.password is not None or parsed.fragment
            or not (re.fullmatch(r'/github-production-release-asset-[A-Za-z0-9._/-]+', parsed.path)
                or (parsed.hostname == 'release-assets.githubusercontent.com'
                    and re.fullmatch(r'/github-production-release-asset/1396578755/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}', parsed.path)))
            or any(part in ('', '.', '..') for part in parsed.path.split('/')[1:])):
        reject('ASSET_REDIRECT_DESTINATION_REJECTED')
    # This signed URL is retained in memory only and never logged or persisted.
    return parsed.hostname, parsed.path + ('?' + parsed.query if parsed.query else '')


def stream_part(asset_id, token, output, expected_bytes, expected_sha, complete_hash):
    connection = http.client.HTTPSConnection('api.github.com', timeout=900)
    secondary = None
    try:
        connection.request('GET', BASE + '/releases/assets/' + str(asset_id), headers={
            'Authorization': 'Bearer ' + token,
            'Accept': 'application/octet-stream',
            'X-GitHub-Api-Version': API_VERSION,
            'User-Agent': 'BiliPai-Own-Reviewed-HOST-Download'})
        response = connection.getresponse()
        if response.status == 302:
            host, path = redirect_target(response.getheader('Location'))
            # http.client has no automatic redirects. The CDN request receives
            # no Authorization, cookies, API token, or request headers from API.
            secondary = http.client.HTTPSConnection(host, timeout=900)
            secondary.request('GET', path, headers={
                'Accept': 'application/octet-stream',
                'User-Agent': 'BiliPai-Own-Reviewed-HOST-Download'})
            response = secondary.getresponse()
        if response.status != 200:
            reject('ASSET_BINARY_HTTP_REJECTED')
        length = response.getheader('Content-Length')
        if not isinstance(length, str) or not re.fullmatch(r'[0-9]{1,20}', length) or int(length) != expected_bytes:
            reject('ASSET_BINARY_LENGTH_REJECTED')
        if response.getheader('Content-Encoding') not in (None, 'identity'):
            reject('ASSET_BINARY_ENCODING_REJECTED')
        part_hash = hashlib.sha256()
        remaining = expected_bytes
        while remaining:
            block = response.read(min(1 << 20, remaining))
            if not block:
                reject('ASSET_BINARY_TRUNCATED')
            output.write(block)
            part_hash.update(block)
            complete_hash.update(block)
            remaining -= len(block)
        if response.read(1) or part_hash.hexdigest() != expected_sha:
            reject('ASSET_BINARY_HASH_OR_TRAILING_BYTES_REJECTED')
    finally:
        if secondary is not None:
            secondary.close()
        connection.close()


def load_source_importer(root):
    path = root / 'desktop/tools/native/import-host-llvm-source-snapshot.py'
    if (path.is_symlink() or not path.is_file()
            or hashlib.sha256(path.read_bytes()).hexdigest() != IMPORTER_SHA256):
        reject('SOURCE_IMPORTER_PIN_REJECTED')
    spec = importlib.util.spec_from_file_location('bilipai_host_download_catalog', path)
    if spec is None or spec.loader is None:
        reject('SOURCE_IMPORTER_UNAVAILABLE')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def select_record(root, record_id):
    if not isinstance(record_id, str) or not re.fullmatch(r'[A-Za-z0-9._-]{1,100}', record_id):
        reject('EXPLICIT_RECORD_ID_REJECTED')
    importer = load_source_importer(root)
    catalog = importer.load_catalog(root)  # Exact source-owned catalog SHA; not a download URL.
    records = [row for row in catalog['records'] if row['id'] == record_id]
    if len(records) != 1:
        reject('EXPLICIT_RECORD_NOT_IN_REVIEWED_CATALOG')
    record = records[0]
    if set(record['assets']) != JSON_ASSETS | ARCHIVES:
        reject('FIVE_ORIGINAL_MATERIALS_REQUIRED')
    all_ids, all_names = set(), set()
    for leaf, item in record['assets'].items():
        length = require_positive(item['bytes'], importer.MAX_JSON if leaf in JSON_ASSETS else importer.MAX_ARCHIVE)
        require_sha(item['sha256'])
        parts = item.get('downloadParts')
        if not isinstance(parts, list) or not 1 <= len(parts) <= (1 if leaf in JSON_ASSETS else MAX_PARTS):
            reject('REVIEWED_DOWNLOAD_PARTS_REQUIRED')
        ids = []
        offset = 0
        for index, part in enumerate(parts, 1):
            if not isinstance(part, dict) or set(part) != {'ownDraftAssetId', 'name', 'offset', 'bytes', 'sha256'}:
                reject('REVIEWED_DOWNLOAD_PART_FIELDS_REJECTED')
            asset_id = require_positive(part['ownDraftAssetId'])
            count = require_positive(part['bytes'], importer.MAX_JSON if leaf in JSON_ASSETS else PART_BYTES)
            require_sha(part['sha256'])
            expected_name = leaf if len(parts) == 1 else leaf + '.part' + str(index).zfill(4)
            if (part['name'] != expected_name or type(part['offset']) is not int or part['offset'] != offset
                    or asset_id in all_ids or expected_name in all_names
                    or (leaf in ARCHIVES and count != min(PART_BYTES, length - offset))):
                reject('REVIEWED_DOWNLOAD_PART_ORDER_OR_IDENTITY_REJECTED')
            if len(parts) == 1 and (count != length or part['sha256'] != item['sha256']):
                reject('REVIEWED_SINGLE_PART_DIFFERS_FROM_ORIGINAL')
            ids.append(asset_id)
            all_ids.add(asset_id)
            all_names.add(expected_name)
            offset += count
        if offset != length or ids != item['ownDraftAssetIds']:
            reject('REVIEWED_COMPLETE_PART_SET_REJECTED')
    return importer, catalog, record


def require_origin_tag(record, token):
    ref = api_json(BASE + '/git/ref/tags/' + quote(record['sourceTag'], safe=''), token)
    if (not isinstance(ref, dict) or ref.get('ref') != 'refs/tags/' + record['sourceTag']
            or ref.get('object') != {'sha': record['sourceCommit'], 'type': 'commit',
                'url': 'https://api.github.com/repos/' + REPOSITORY + '/git/commits/' + record['sourceCommit']}):
        reject('ORIGINAL_LIGHTWEIGHT_SOURCE_TAG_CHANGED')


def require_remote_inventory(record, token):
    release_id = record['ownDraftReleaseId']
    release = api_json(BASE + '/releases/' + str(release_id), token)
    if (not isinstance(release, dict) or type(release.get('id')) is not int or release['id'] != release_id
            or release.get('draft') is not True or release.get('published_at') is not None
            or release.get('tag_name') != record['sourceTag']
            or release.get('url') != 'https://api.github.com' + BASE + '/releases/' + str(release_id)):
        reject('ORIGINAL_UNPUBLISHED_OWN_DRAFT_CHANGED')
    rows = []
    for page in range(1, MAX_RELEASE_ASSETS // 100 + 2):
        batch = api_json(BASE + '/releases/' + str(release_id) + '/assets?per_page=100&page=' + str(page), token)
        if not isinstance(batch, list) or len(batch) > 100:
            reject('DRAFT_ASSET_PAGE_REJECTED')
        rows.extend(batch)
        if len(rows) > MAX_RELEASE_ASSETS:
            reject('DRAFT_ASSET_INVENTORY_BOUND_REJECTED')
        if len(batch) < 100:
            break
    else:
        reject('DRAFT_ASSET_INVENTORY_NOT_COMPLETE')
    table, names = {}, set()
    for row in rows:
        if not isinstance(row, dict):
            reject('DRAFT_ASSET_METADATA_REJECTED')
        asset_id = require_positive(row.get('id'))
        name = row.get('name')
        if (not isinstance(name, str) or asset_id in table or name in names):
            reject('DRAFT_ASSET_METADATA_DUPLICATE')
        table[asset_id] = row
        names.add(name)
    for item in record['assets'].values():
        for part in item['downloadParts']:
            row = table.get(part['ownDraftAssetId'], {})
            if (row.get('name') != part['name'] or row.get('state') != 'uploaded'
                    or type(row.get('size')) is not int or row['size'] != part['bytes']
                    or row.get('digest') != 'sha256:' + part['sha256']
                    or row.get('url') != 'https://api.github.com' + BASE + '/releases/assets/' + str(part['ownDraftAssetId'])):
                reject('PINNED_ASSET_NOT_IN_ORIGINAL_DRAFT')
    return table


def require_context(environment):
    if (environment.get('GITHUB_ACTIONS') != 'true' or environment.get('GITHUB_REPOSITORY') != REPOSITORY
            or environment.get('GITHUB_EVENT_NAME') != 'workflow_dispatch'
            or environment.get('GITHUB_REF_TYPE') != 'tag'
            or not re.fullmatch(r'rtx-source-[A-Za-z0-9][A-Za-z0-9._-]{0,100}', environment.get('GITHUB_REF_NAME', ''))
            or not re.fullmatch(r'[0-9a-f]{40}', environment.get('GITHUB_SHA', ''))):
        reject('EXPLICIT_OWN_TAG_WORKFLOW_REQUIRED')
    for key in ('GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT'):
        value = environment.get(key, '')
        if not re.fullmatch(r'[0-9]{1,20}', value):
            reject('WORKFLOW_RUN_CONTEXT_REJECTED')
        require_positive(int(value))


def ordinary_directory(path):
    if not path.is_absolute():
        reject('OUTPUT_ABSOLUTE_DIRECTORY_REQUIRED')
    for part in reversed([path, *path.parents]):
        item = part.lstat()
        if (not stat.S_ISDIR(item.st_mode) or stat.S_ISLNK(item.st_mode)
                or getattr(item, 'st_file_attributes', 0) & 1024):
            reject('OUTPUT_DIRECTORY_LINK_OR_TYPE_REJECTED')


def new_output_directory(output, runner_temp):
    root = Path(runner_temp)
    ordinary_directory(root)
    if (not output.is_absolute() or output.parent != root or output.name != 'bilipai-host-llvm-input'
            or output.exists() or output.is_symlink()):
        reject('NEW_TASK_OWNED_SNAPSHOT_DIRECTORY_REQUIRED')
    return output


def new_staging_directory(output, runner_temp):
    new_output_directory(output, runner_temp)
    # The internal unpredictable leaf is not user input or archive metadata.
    stage = output.parent / (output.name + '.downloading-' + uuid.uuid4().hex)
    stage.mkdir(mode=0o700)
    ordinary_directory(stage)
    identity = stage.lstat()
    return stage, (identity.st_dev, identity.st_ino)


def require_stage_identity(stage, identity):
    ordinary_directory(stage)
    current = stage.lstat()
    if ((current.st_dev, current.st_ino) != identity
            or not re.fullmatch(r'bilipai-host-llvm-input[.]downloading-[0-9a-f]{32}', stage.name)):
        reject('TASK_STAGING_DIRECTORY_IDENTITY_CHANGED')


def cleanup_staging(stage, identity):
    # No recursive deletion, archive paths, broad directory inventory, or caller
    # computed path. Only the six exact leaves created by this invocation.
    try:
        require_stage_identity(stage, identity)
        for leaf in sorted(JSON_ASSETS | ARCHIVES | {'host-llvm-download-receipt.json'}):
            path = stage / leaf
            try:
                entry = path.lstat()
            except FileNotFoundError:
                continue
            if stat.S_ISREG(entry.st_mode) and not stat.S_ISLNK(entry.st_mode) and not getattr(entry, 'st_file_attributes', 0) & 1024:
                path.unlink()
        stage.rmdir()  # Refuses an unexpected file; never recursively removes it.
    except Exception:
        # A cleanup problem cannot make the final directory available, replace
        # the original failure, or expose opaque paths/transport/authentication.
        pass


def publish_staging(stage, output, identity):
    require_stage_identity(stage, identity)
    new_output_directory(output, str(output.parent))
    if os.name == 'nt':
        # Windows rename refuses any existing destination, including an empty dir.
        os.rename(stage, output)
    elif sys.platform == 'linux':
        directory_fd = os.open(stage, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        try:
            current = os.fstat(directory_fd)
            if (current.st_dev, current.st_ino) != identity:
                reject('TASK_STAGING_DIRECTORY_IDENTITY_CHANGED')
            os.fsync(directory_fd)
        finally:
            os.close(directory_fd)
        # Source builder runs Linux. Standard libc renameat2 gives atomic new-only
        # publication and cannot overwrite a concurrent destination directory.
        library = ctypes.CDLL(None, use_errno=True)
        rename = getattr(library, 'renameat2', None)
        if rename is None:
            reject('ATOMIC_NEW_ONLY_DIRECTORY_PUBLISH_UNAVAILABLE')
        rename.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_uint]
        rename.restype = ctypes.c_int
        if rename(-100, os.fsencode(stage), -100, os.fsencode(output), 1) != 0:  # AT_FDCWD, RENAME_NOREPLACE
            reject('ATOMIC_NEW_ONLY_DIRECTORY_PUBLISH_REJECTED')
    else:
        reject('ATOMIC_NEW_ONLY_DIRECTORY_PUBLISH_PLATFORM_REJECTED')


def download(root, record_id, output, token, runner_temp):
    importer, catalog, record = select_record(root, record_id)
    repository = api_json(BASE, token)
    if (not isinstance(repository, dict) or repository.get('full_name') != REPOSITORY
            or repository.get('private') is not False):
        reject('OWN_PUBLIC_REPOSITORY_REQUIRED')
    require_origin_tag(record, token)
    require_remote_inventory(record, token)
    stage, stage_identity = new_staging_directory(output, runner_temp)
    try:
        for leaf in sorted(record['assets']):
            item = record['assets'][leaf]
            complete_hash = hashlib.sha256()
            # Fixed original leaves are private staging files until all post-checks.
            with (stage / leaf).open('xb') as destination:
                os.chmod(stage / leaf, 0o600)
                for part in item['downloadParts']:
                    if destination.tell() != part['offset']:
                        reject('LOCAL_REASSEMBLY_OFFSET_REJECTED')
                    stream_part(part['ownDraftAssetId'], token, destination, part['bytes'], part['sha256'], complete_hash)
                destination.flush()
                os.fsync(destination.fileno())
                if destination.tell() != item['bytes'] or complete_hash.hexdigest() != item['sha256']:
                    reject('COMPLETE_ORIGINAL_MATERIAL_HASH_REJECTED')
        # Complete bytes are still private staging when any origin recheck fails.
        require_origin_tag(record, token)
        require_remote_inventory(record, token)
        receipt = {'schema': 1, 'kind': 'BILIPAI_REVIEWED_HOST_SNAPSHOT_DOWNLOADED',
            'repository': REPOSITORY, 'recordId': record['id'],
            'catalogSha256': importer.CATALOG_SHA256, 'importerSha256': IMPORTER_SHA256,
            'ownDraftReleaseId': record['ownDraftReleaseId'],
            'originalSourceCommit': record['sourceCommit'], 'originalSourceTag': record['sourceTag'],
            'originalWorkflowRunId': record['workflowRunId'], 'originalWorkflowRunAttempt': record['workflowRunAttempt'],
            'materials': {leaf: {name: item[name] for name in ('fileName', 'bytes', 'sha256', 'ownDraftAssetIds')}
                          for leaf, item in record['assets'].items()},
            'archivesExtracted': False, 'archivePayloadExecuted': False,
            'importerQualificationStillRequired': True, 'accelerationMeasured': False,
            'gpuOrDriverTested': False}
        # No URL, token, complete process environment, or remote response is stored.
        with (stage / 'host-llvm-download-receipt.json').open('x', encoding='utf-8') as stream:
            stream.write(json.dumps(receipt, sort_keys=True, indent=2) + '\n')
            stream.flush()
            os.fsync(stream.fileno())
        publish_staging(stage, output, stage_identity)
        return receipt
    except Exception:
        cleanup_staging(stage, stage_identity)
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--record-id', required=True)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    require_context(os.environ)
    token = os.environ.pop('GITHUB_TOKEN', None)
    if not isinstance(token, str) or not token or any(ord(char) < 33 or ord(char) > 126 for char in token):
        reject('DOWNLOAD_STEP_TOKEN_REQUIRED')
    # There are no subprocess calls. The token exists only in this download step.
    return download(Path(__file__).resolve().parents[3], args.record_id, args.output, token,
                    os.environ.get('RUNNER_TEMP', ''))


if __name__ == '__main__':
    try:
        main()
    except DownloadError as error:
        print('HOST snapshot download rejected: ' + str(error), file=sys.stderr)
        sys.exit(1)
    except Exception:
        # Deliberately do not dump exceptions, URLs, remote bodies, or traceback.
        print('HOST snapshot download rejected: INTERNAL_OR_TRANSPORT_FAILURE', file=sys.stderr)
        sys.exit(1)
