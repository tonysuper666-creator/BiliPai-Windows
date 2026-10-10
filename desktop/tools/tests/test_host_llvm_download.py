#!/usr/bin/env python3
"""CPU-only rejection/transport definitions; never contact GitHub or extract payloads."""
import hashlib
import importlib.util
import io
import json
import tempfile
import types
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
SOURCE = ROOT / 'desktop/tools/native/download-host-llvm-source-snapshot.py'
spec = importlib.util.spec_from_file_location('bilipai_host_snapshot_downloader_tests', SOURCE)
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def fixtures():
    payloads = {
        'host-llvm-source-snapshot-descriptor.json': b'{"original":1}',
        'host-llvm-snapshot-manifest.json': b'{"original":2}',
        'host-llvm-original-environment.json': b'{"original":3}',
        'host-llvm-install.tar.gz': b'NOT AN EXECUTABLE OR EXTRACTABLE ARCHIVE',
        'host-llvm-corresponding-source.tar.gz': b'COMPLETE ORIGINAL SOURCE BYTES, NOT EXTRACTED',
    }
    record = {'id': 'independently-reviewed-fixture', 'repository': m.REPOSITORY,
        'sourceTag': 'rtx-source-fixture', 'sourceCommit': '1' * 40, 'workflowRunId': 123,
        'workflowRunAttempt': 1, 'ownDraftReleaseId': 456, 'assets': {}}
    next_id = 1000
    for leaf, data in payloads.items():
        parts = []
        part_size = 16 if leaf in m.ARCHIVES else len(data)
        count = (len(data) + part_size - 1) // part_size
        for index, offset in enumerate(range(0, len(data), part_size), 1):
            block = data[offset:offset + part_size]
            parts.append({'ownDraftAssetId': next_id, 'name': leaf if count == 1 else leaf + '.part' + str(index).zfill(4),
                'offset': offset, 'bytes': len(block), 'sha256': sha(block)})
            next_id += 1
        record['assets'][leaf] = {'fileName': leaf, 'bytes': len(data), 'sha256': sha(data),
            'ownDraftAssetIds': [p['ownDraftAssetId'] for p in parts], 'downloadParts': parts}
    catalog = {'records': [record]}
    helper = types.SimpleNamespace(MAX_JSON=256 << 20, MAX_ARCHIVE=64 << 30,
        CATALOG_SHA256='2' * 64, load_catalog=lambda root: catalog)
    assets = [{'id': p['ownDraftAssetId'], 'name': p['name'], 'size': p['bytes'],
        'state': 'uploaded', 'digest': 'sha256:' + p['sha256'],
        'url': 'https://api.github.com' + m.BASE + '/releases/assets/' + str(p['ownDraftAssetId'])}
        for item in record['assets'].values() for p in item['downloadParts']]
    return payloads, record, helper, catalog, assets


class Response:
    def __init__(self, status=200, body=b'', headers=None):
        self.status = status
        self.body = io.BytesIO(body)
        self.headers = headers or {}
    def getheader(self, name):
        return self.headers.get(name)
    def read(self, count=-1):
        return self.body.read(count)


class HostSnapshotDownloadTest(unittest.TestCase):
    def setUp(self):
        self.payloads, self.record, self.helper, self.catalog, self.assets = fixtures()

    def select(self, record_id=None):
        with patch.object(m, 'load_source_importer', return_value=self.helper), patch.object(m, 'PART_BYTES', 16):
            return m.select_record(ROOT, record_id or self.record['id'])

    def remote(self, path, token):
        self.assertEqual(token, 'DOWNLOAD_ONLY_TOKEN')
        if path == m.BASE:
            return {'full_name': m.REPOSITORY, 'private': False}
        if '/git/ref/tags/' in path:
            return {'ref': 'refs/tags/' + self.record['sourceTag'],
                'object': {'sha': self.record['sourceCommit'], 'type': 'commit',
                    'url': 'https://api.github.com/repos/' + m.REPOSITORY + '/git/commits/' + self.record['sourceCommit']}}
        if path.endswith('/releases/' + str(self.record['ownDraftReleaseId'])):
            return {'id': self.record['ownDraftReleaseId'], 'draft': True, 'published_at': None,
                'tag_name': self.record['sourceTag'], 'url': 'https://api.github.com' + path}
        if '/assets?per_page=100&page=1' in path:
            return self.assets
        raise AssertionError('Unexpected fixed API path')

    def consume(self, asset_id, token, destination, count, expected_sha, complete_hash):
        for leaf, item in self.record['assets'].items():
            for part in item['downloadParts']:
                if part['ownDraftAssetId'] == asset_id:
                    data = self.payloads[leaf][part['offset']:part['offset'] + count]
                    self.assertEqual(len(data), count)
                    self.assertEqual(sha(data), expected_sha)
                    destination.write(data)
                    complete_hash.update(data)
                    return
        raise AssertionError('Unknown asset ID')

    def test_unknown_record_stops_before_network_or_directory(self):
        self.catalog['records'] = []
        with tempfile.TemporaryDirectory() as temp, patch.object(m, 'load_source_importer', return_value=self.helper), patch.object(m, 'api_json') as network:
            output = Path(temp) / 'bilipai-host-llvm-input'
            with self.assertRaisesRegex(m.DownloadError, 'EXPLICIT_RECORD_NOT_IN_REVIEWED_CATALOG'):
                m.download(ROOT, 'missing-record', output, 'DOWNLOAD_ONLY_TOKEN', temp)
            network.assert_not_called()
            self.assertFalse(output.exists())

    def test_record_id_never_accepts_url_or_shell_text(self):
        for record_id in ('https://example.com/archive', 'fixture; touch elsewhere', '../fixture', '', 'a' * 101):
            with self.subTest(record_id=record_id), self.assertRaises(m.DownloadError):
                m.select_record(ROOT, record_id)

    def test_valid_five_piece_record_and_ordered_archive_parts(self):
        importer, catalog, record = self.select()
        self.assertIs(importer, self.helper)
        self.assertIs(record, self.record)
        self.assertEqual(set(record['assets']), m.JSON_ASSETS | m.ARCHIVES)

    def test_missing_parts_cannot_infer_trust_from_asset_ids(self):
        del self.record['assets']['host-llvm-install.tar.gz']['downloadParts']
        with self.assertRaisesRegex(m.DownloadError, 'REVIEWED_DOWNLOAD_PARTS_REQUIRED'):
            self.select()

    def test_archive_offset_gap_is_rejected(self):
        self.record['assets']['host-llvm-install.tar.gz']['downloadParts'][1]['offset'] += 1
        with self.assertRaisesRegex(m.DownloadError, 'PART_ORDER_OR_IDENTITY'):
            self.select()

    def test_same_parts_with_reordered_original_ids_are_rejected(self):
        self.record['assets']['host-llvm-install.tar.gz']['ownDraftAssetIds'].reverse()
        with self.assertRaisesRegex(m.DownloadError, 'REVIEWED_COMPLETE_PART_SET_REJECTED'):
            self.select()

    def test_boolean_asset_id_and_duplicate_asset_id_are_rejected(self):
        parts = self.record['assets']['host-llvm-install.tar.gz']['downloadParts']
        original = parts[0]['ownDraftAssetId']
        parts[0]['ownDraftAssetId'] = True
        with self.assertRaises(m.DownloadError):
            self.select()
        parts[0]['ownDraftAssetId'] = original
        parts[1]['ownDraftAssetId'] = original
        with self.assertRaises(m.DownloadError):
            self.select()

    def test_noncanonical_name_and_complete_length_are_rejected(self):
        item = self.record['assets']['host-llvm-install.tar.gz']
        item['downloadParts'][0]['name'] = '../host-llvm-install.tar.gz.part0001'
        with self.assertRaises(m.DownloadError):
            self.select()
        item['downloadParts'][0]['name'] = 'host-llvm-install.tar.gz.part0001'
        item['bytes'] += 1
        with self.assertRaises(m.DownloadError):
            self.select()

    def test_five_original_bytes_are_reassembled_without_extracting(self):
        with tempfile.TemporaryDirectory() as temp, patch.object(m, 'load_source_importer', return_value=self.helper), patch.object(m, 'PART_BYTES', 16), patch.object(m, 'api_json', side_effect=self.remote), patch.object(m, 'stream_part', side_effect=self.consume):
            output = Path(temp) / 'bilipai-host-llvm-input'
            receipt = m.download(ROOT, self.record['id'], output, 'DOWNLOAD_ONLY_TOKEN', temp)
            for leaf, original in self.payloads.items():
                self.assertEqual((output / leaf).read_bytes(), original)
            self.assertEqual(len(list(output.iterdir())), 6)
            self.assertFalse(receipt['archivesExtracted'])
            self.assertFalse(receipt['archivePayloadExecuted'])
            self.assertTrue(receipt['importerQualificationStillRequired'])
            raw_receipt = (output / 'host-llvm-download-receipt.json').read_text()
            self.assertNotIn('DOWNLOAD_ONLY_TOKEN', raw_receipt)
            self.assertNotIn('https://', raw_receipt)

    def test_material_hash_failure_leaves_no_success_receipt_or_fallback(self):
        self.record['assets']['host-llvm-install.tar.gz']['sha256'] = '0' * 64
        with tempfile.TemporaryDirectory() as temp, patch.object(m, 'load_source_importer', return_value=self.helper), patch.object(m, 'PART_BYTES', 16), patch.object(m, 'api_json', side_effect=self.remote), patch.object(m, 'stream_part', side_effect=self.consume):
            output = Path(temp) / 'bilipai-host-llvm-input'
            with self.assertRaisesRegex(m.DownloadError, 'COMPLETE_ORIGINAL_MATERIAL_HASH_REJECTED'):
                m.download(ROOT, self.record['id'], output, 'DOWNLOAD_ONLY_TOKEN', temp)
            self.assertFalse(output.exists())
            self.assertEqual(list(Path(temp).iterdir()), [])

    def test_complete_material_postcheck_tag_failure_never_publishes(self):
        with tempfile.TemporaryDirectory() as temp, patch.object(m, 'load_source_importer', return_value=self.helper), patch.object(m, 'PART_BYTES', 16), patch.object(m, 'api_json', side_effect=self.remote), patch.object(m, 'stream_part', side_effect=self.consume), patch.object(m, 'require_origin_tag', side_effect=[None, m.DownloadError('ORIGINAL_LIGHTWEIGHT_SOURCE_TAG_CHANGED')]) as tags:
            output = Path(temp) / 'bilipai-host-llvm-input'
            with self.assertRaisesRegex(m.DownloadError, 'ORIGINAL_LIGHTWEIGHT_SOURCE_TAG_CHANGED'):
                m.download(ROOT, self.record['id'], output, 'DOWNLOAD_ONLY_TOKEN', temp)
            self.assertEqual(tags.call_count, 2)
            self.assertFalse(output.exists())
            self.assertEqual(list(Path(temp).iterdir()), [])

    def test_complete_material_postcheck_asset_failure_never_publishes(self):
        with tempfile.TemporaryDirectory() as temp, patch.object(m, 'load_source_importer', return_value=self.helper), patch.object(m, 'PART_BYTES', 16), patch.object(m, 'api_json', side_effect=self.remote), patch.object(m, 'stream_part', side_effect=self.consume), patch.object(m, 'require_remote_inventory', side_effect=[None, m.DownloadError('PINNED_ASSET_NOT_IN_ORIGINAL_DRAFT')]) as inventories:
            output = Path(temp) / 'bilipai-host-llvm-input'
            with self.assertRaisesRegex(m.DownloadError, 'PINNED_ASSET_NOT_IN_ORIGINAL_DRAFT'):
                m.download(ROOT, self.record['id'], output, 'DOWNLOAD_ONLY_TOKEN', temp)
            self.assertEqual(inventories.call_count, 2)
            self.assertFalse(output.exists())
            self.assertEqual(list(Path(temp).iterdir()), [])

    def test_transport_failure_never_publishes_final_material(self):
        with tempfile.TemporaryDirectory() as temp, patch.object(m, 'load_source_importer', return_value=self.helper), patch.object(m, 'PART_BYTES', 16), patch.object(m, 'api_json', side_effect=self.remote), patch.object(m, 'stream_part', side_effect=OSError('opaque transport detail must not be logged')):
            output = Path(temp) / 'bilipai-host-llvm-input'
            with self.assertRaises(OSError):
                m.download(ROOT, self.record['id'], output, 'DOWNLOAD_ONLY_TOKEN', temp)
            self.assertFalse(output.exists())
            self.assertEqual(list(Path(temp).iterdir()), [])

    def test_receipt_fsync_failure_never_publishes_or_retries(self):
        original_fsync = m.os.fsync
        fsync_count = 0
        def fail_receipt_fsync(fd):
            nonlocal fsync_count
            fsync_count += 1
            if fsync_count == 6:  # Five original files, then the receipt.
                raise OSError('receipt fsync failed')
            return original_fsync(fd)
        with tempfile.TemporaryDirectory() as temp, patch.object(m, 'load_source_importer', return_value=self.helper), patch.object(m, 'PART_BYTES', 16), patch.object(m, 'api_json', side_effect=self.remote), patch.object(m, 'stream_part', side_effect=self.consume), patch.object(m.os, 'fsync', side_effect=fail_receipt_fsync):
            output = Path(temp) / 'bilipai-host-llvm-input'
            with self.assertRaises(OSError):
                m.download(ROOT, self.record['id'], output, 'DOWNLOAD_ONLY_TOKEN', temp)
            self.assertEqual(fsync_count, 6)
            self.assertFalse(output.exists())
            self.assertEqual(list(Path(temp).iterdir()), [])

    def test_atomic_rename_failure_never_publishes_or_retries(self):
        with tempfile.TemporaryDirectory() as temp, patch.object(m, 'load_source_importer', return_value=self.helper), patch.object(m, 'PART_BYTES', 16), patch.object(m, 'api_json', side_effect=self.remote), patch.object(m, 'stream_part', side_effect=self.consume), patch.object(m, 'publish_staging', side_effect=m.DownloadError('ATOMIC_NEW_ONLY_DIRECTORY_PUBLISH_REJECTED')) as publisher:
            output = Path(temp) / 'bilipai-host-llvm-input'
            with self.assertRaisesRegex(m.DownloadError, 'ATOMIC_NEW_ONLY_DIRECTORY_PUBLISH_REJECTED'):
                m.download(ROOT, self.record['id'], output, 'DOWNLOAD_ONLY_TOKEN', temp)
            publisher.assert_called_once()
            self.assertFalse(output.exists())
            self.assertEqual(list(Path(temp).iterdir()), [])

    def test_rollback_refuses_unknown_file_or_changed_stage_identity(self):
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp) / 'bilipai-host-llvm-input'
            stage, identity = m.new_staging_directory(output, temp)
            known = stage / 'host-llvm-install.tar.gz'
            unknown = stage / 'not-created-by-this-download'
            known.write_bytes(b'partial ordinary material')
            unknown.write_bytes(b'preserve external bytes')
            m.cleanup_staging(stage, (identity[0], identity[1] + 1))
            self.assertTrue(known.exists())
            m.cleanup_staging(stage, identity)
            self.assertFalse(known.exists())
            self.assertEqual(unknown.read_bytes(), b'preserve external bytes')
            self.assertFalse(output.exists())

    def test_concurrent_final_directory_is_never_overwritten_or_removed(self):
        original_publish = m.publish_staging
        def publish_with_concurrent_owner(stage, output, identity):
            output.mkdir()
            (output / 'external-owner').write_bytes(b'preserve concurrent owner')
            original_publish(stage, output, identity)
        with tempfile.TemporaryDirectory() as temp, patch.object(m, 'load_source_importer', return_value=self.helper), patch.object(m, 'PART_BYTES', 16), patch.object(m, 'api_json', side_effect=self.remote), patch.object(m, 'stream_part', side_effect=self.consume), patch.object(m, 'publish_staging', side_effect=publish_with_concurrent_owner):
            output = Path(temp) / 'bilipai-host-llvm-input'
            with self.assertRaises(m.DownloadError):
                m.download(ROOT, self.record['id'], output, 'DOWNLOAD_ONLY_TOKEN', temp)
            self.assertEqual((output / 'external-owner').read_bytes(), b'preserve concurrent owner')
            self.assertEqual({p.name for p in output.iterdir()}, {'external-owner'})
            self.assertEqual({p.name for p in Path(temp).iterdir()}, {'bilipai-host-llvm-input'})

    def test_changed_original_tag_and_published_release_are_rejected(self):
        with patch.object(m, 'api_json', return_value={'ref': 'refs/tags/changed'}), self.assertRaises(m.DownloadError):
            m.require_origin_tag(self.record, 'DOWNLOAD_ONLY_TOKEN')
        with patch.object(m, 'api_json', return_value={'id': self.record['ownDraftReleaseId'], 'draft': False}), self.assertRaises(m.DownloadError):
            m.require_remote_inventory(self.record, 'DOWNLOAD_ONLY_TOKEN')

    def test_same_hash_asset_outside_original_release_is_rejected(self):
        self.assets.pop()
        with patch.object(m, 'api_json', side_effect=self.remote), self.assertRaisesRegex(m.DownloadError, 'PINNED_ASSET_NOT_IN_ORIGINAL_DRAFT'):
            m.require_remote_inventory(self.record, 'DOWNLOAD_ONLY_TOKEN')

    def test_redirect_must_be_exact_https_github_release_asset_host(self):
        good = 'https://release-assets.githubusercontent.com/github-production-release-asset-2e65be/123/456?sig=OPAQUE'
        self.assertEqual(m.redirect_target(good)[0], 'release-assets.githubusercontent.com')
        for bad in ('http://release-assets.githubusercontent.com/github-production-release-asset-2e65be/a',
                    'https://example.com/github-production-release-asset-2e65be/a',
                    'https://release-assets.githubusercontent.com.evil.example/github-production-release-asset-2e65be/a',
                    'https://user@release-assets.githubusercontent.com/github-production-release-asset-2e65be/a',
                    'https://release-assets.githubusercontent.com:443/github-production-release-asset-2e65be/a',
                    'https://release-assets.githubusercontent.com/github-production-release-asset-2e65be/../a',
                    good + '#fragment'):
            with self.subTest(bad=bad), self.assertRaises(m.DownloadError):
                m.redirect_target(bad)

    def test_observed_modern_own_release_asset_redirect(self):
        # Fixed own API asset 627202407, observed 2026-10-10; live signed query is never retained.
        path = '/github-production-release-asset/1396578755/2442269f-f08e-4632-b6d6-1d41465d1d3c'
        host = 'release-assets.githubusercontent.com'
        self.assertEqual(m.redirect_target('https://' + host + path + '?sig=OPAQUE'),
            (host, path + '?sig=OPAQUE'))

    def test_modern_redirect_keeps_observed_own_host_repo_and_uuid_shape(self):
        path = '/github-production-release-asset/1396578755/2442269f-f08e-4632-b6d6-1d41465d1d3c'
        host = 'release-assets.githubusercontent.com'
        rejected = (
            ('wrong own repository', host, path.replace('/1396578755/', '/1396578754/')),
            ('uppercase uuid', host, path.replace('2442269f', '2442269F')),
            ('malformed uuid', host, path[:-1]),
            ('extra path segment', host, path + '/extra'),
            ('different literal prefix', host, path.replace('release-asset/', 'release-assets/')),
            ('other legacy allowlisted host', 'objects.githubusercontent.com', path),
        )
        for reason, target_host, target_path in rejected:
            with self.subTest(reason=reason), self.assertRaisesRegex(m.DownloadError, 'ASSET_REDIRECT_DESTINATION_REJECTED'):
                m.redirect_target('https://' + target_host + target_path)

    def test_observed_modern_redirect_uses_same_single_hop_auth_free_transport(self):
        payload = b'FIXED OWN SMALL JSON BYTES'
        path = '/github-production-release-asset/1396578755/2442269f-f08e-4632-b6d6-1d41465d1d3c'
        factory, requests = self.connections([
            Response(302, headers={'Location': 'https://release-assets.githubusercontent.com' + path + '?sig=OPAQUE'}),
            Response(200, payload, {'Content-Length': str(len(payload))})])
        with patch.object(m.http.client, 'HTTPSConnection', factory):
            destination = io.BytesIO()
            total = hashlib.sha256()
            m.stream_part(627202407, 'DOWNLOAD_ONLY_TOKEN', destination, len(payload), sha(payload), total)
        self.assertEqual(destination.getvalue(), payload)
        self.assertEqual(total.hexdigest(), sha(payload))
        self.assertEqual(len(requests), 2)
        self.assertEqual(requests[0][0:3], ('api.github.com', 'GET', m.BASE + '/releases/assets/627202407'))
        self.assertEqual(requests[0][3]['Authorization'], 'Bearer DOWNLOAD_ONLY_TOKEN')
        self.assertEqual(requests[1][0:3], ('release-assets.githubusercontent.com', 'GET', path + '?sig=OPAQUE'))
        self.assertNotIn('Authorization', requests[1][3])
        self.assertNotIn('Cookie', requests[1][3])
        self.assertNotIn('DOWNLOAD_ONLY_TOKEN', repr(requests[1]))

    def connections(self, responses):
        requests = []
        class Connection:
            def __init__(self, host, timeout):
                self.host = host
                self.response = responses.pop(0)
            def request(self, method, path, headers):
                requests.append((self.host, method, path, headers))
            def getresponse(self):
                return self.response
            def close(self):
                pass
        return Connection, requests

    def test_asset_redirect_never_forwards_token_or_authorization(self):
        payload = b'ORIGINAL PART'
        redirect = 'https://release-assets.githubusercontent.com/github-production-release-asset-2e65be/a/b?sig=OPAQUE'
        factory, requests = self.connections([
            Response(302, headers={'Location': redirect}),
            Response(200, payload, {'Content-Length': str(len(payload))})])
        with patch.object(m.http.client, 'HTTPSConnection', factory):
            destination = io.BytesIO()
            total = hashlib.sha256()
            m.stream_part(123, 'DOWNLOAD_ONLY_TOKEN', destination, len(payload), sha(payload), total)
        self.assertEqual(destination.getvalue(), payload)
        self.assertEqual(total.hexdigest(), sha(payload))
        self.assertEqual(requests[0][3]['Authorization'], 'Bearer DOWNLOAD_ONLY_TOKEN')
        self.assertNotIn('Authorization', requests[1][3])
        self.assertNotIn('DOWNLOAD_ONLY_TOKEN', repr(requests[1]))

    def test_second_redirect_is_rejected_without_another_request(self):
        factory, requests = self.connections([
            Response(302, headers={'Location': 'https://objects.githubusercontent.com/github-production-release-asset-2e65be/a/b'}),
            Response(302, headers={'Location': 'https://example.com/forbidden'})])
        with patch.object(m.http.client, 'HTTPSConnection', factory), self.assertRaisesRegex(m.DownloadError, 'ASSET_BINARY_HTTP_REJECTED'):
            m.stream_part(123, 'DOWNLOAD_ONLY_TOKEN', io.BytesIO(), 1, sha(b'x'), hashlib.sha256())
        self.assertEqual(len(requests), 2)

    def test_asset_truncated_oversized_or_wrong_hash_is_rejected(self):
        for payload, length, digest in ((b'x', 2, sha(b'xx')), (b'xx', 1, sha(b'x')), (b'x', 1, sha(b'y'))):
            with self.subTest(payload=payload, length=length):
                factory, requests = self.connections([Response(200, payload, {'Content-Length': str(length)})])
                with patch.object(m.http.client, 'HTTPSConnection', factory), self.assertRaises(m.DownloadError):
                    m.stream_part(123, 'DOWNLOAD_ONLY_TOKEN', io.BytesIO(), length, digest, hashlib.sha256())

    def test_response_length_and_duplicate_json_key_are_rejected(self):
        factory, requests = self.connections([Response(200, b'x', {'Content-Length': '2'})])
        with patch.object(m.http.client, 'HTTPSConnection', factory), self.assertRaises(m.DownloadError):
            m.stream_part(123, 'DOWNLOAD_ONLY_TOKEN', io.BytesIO(), 1, sha(b'x'), hashlib.sha256())
        with self.assertRaisesRegex(m.DownloadError, 'DUPLICATE_API_JSON_KEY'):
            m.duplicate_safe_json(b'{"id":1,"id":2}')

    def test_existing_or_escaped_output_directory_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp) / 'bilipai-host-llvm-input'
            output.mkdir()
            with self.assertRaises(m.DownloadError):
                m.new_output_directory(output, temp)
            with self.assertRaises(m.DownloadError):
                m.new_output_directory(Path(temp) / 'different-owner', temp)

    def test_manual_own_source_tag_context_is_required(self):
        environment = {'GITHUB_ACTIONS': 'true', 'GITHUB_REPOSITORY': m.REPOSITORY,
            'GITHUB_EVENT_NAME': 'workflow_dispatch', 'GITHUB_REF_TYPE': 'tag',
            'GITHUB_REF_NAME': 'rtx-source-fixture', 'GITHUB_SHA': '1' * 40,
            'GITHUB_RUN_ID': '123', 'GITHUB_RUN_ATTEMPT': '1'}
        m.require_context(environment)
        for key, value in (('GITHUB_REPOSITORY', 'jay3-yy/BiliPai'), ('GITHUB_REF_TYPE', 'branch'),
                           ('GITHUB_SHA', 'short'), ('GITHUB_RUN_ID', '0')):
            with self.subTest(key=key), self.assertRaises(m.DownloadError):
                m.require_context({**environment, key: value})


if __name__ == '__main__':
    unittest.main()
