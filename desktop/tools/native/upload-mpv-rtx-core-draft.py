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
    args = parser.parse_args()
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
    child_env = {key: value for key, value in os.environ.items() if key != 'GITHUB_TOKEN'}
    actual_head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True,
                                          env=child_env).strip()
    if actual_head != commit:
        raise DeliveryError('Checked-out source differs from the workflow commit')
    status_path = owned_file(directory, 'build-status.json')
    receipt_path = owned_file(directory, 'build-receipt.json')
    descriptor_path = owned_file(directory, 'runtime-descriptor.json')
    status, receipt, descriptor = [json.loads(path.read_text(encoding='utf-8'))
                                   for path in (status_path, receipt_path, descriptor_path)]
    if (status.get('success') is not True or status.get('binaryProduced') is not True
            or status.get('gpuOrDriverTested') is not False
            or receipt.get('schema') != 2 or receipt.get('variant') != VARIANT
            or receipt.get('ownrepoSourceCommit') != commit
            or receipt.get('gpuOrDriverTested') is not False
            or receipt.get('closedSdkOrRuntimeIncluded') is not False
            or descriptor.get('schema') != 2 or descriptor.get('variant') != VARIANT
            or descriptor.get('deliveryStatus') != 'LOCAL_ARTIFACT_ONLY_NOT_RELEASED'
            or descriptor.get('rtxCoreBridgeVerified') is not False
            or descriptor.get('closedSdkOrRuntimeIncluded') is not False
            or descriptor.get('buildReceiptSha256') != range_sha(receipt_path, 0, receipt_path.stat().st_size)
            or status.get('descriptorSha256') != range_sha(descriptor_path, 0, descriptor_path.stat().st_size)):
        raise DeliveryError('Only a successful unverified source candidate is eligible')
    artifact = owned_file(directory, descriptor['artifact']['fileName'])
    bundle = owned_file(directory, descriptor['sourceBundle']['fileName'])
    if (range_sha(artifact, 0, artifact.stat().st_size) != descriptor['artifact']['archiveSha256']
            or range_sha(bundle, 0, bundle.stat().st_size) != descriptor['sourceBundle']['sha256']
            or descriptor['sourceBundle']['sha256'] != receipt.get('sourceBundleSha256')):
        raise DeliveryError('Candidate or complete corresponding source hash changed')
    assets = []
    for path in (artifact, descriptor_path, receipt_path, status_path):
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
    manifest = {'schema': 1, 'sourceCommit': commit, 'sourceTag': tag,
                'delivery': 'OWN_REPOSITORY_DRAFT_ONLY', 'draft': True,
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
