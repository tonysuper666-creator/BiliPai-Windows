"""Network and disk admission for a real previous-EXE fixture; no execution."""
from pathlib import Path
import copy
import hashlib
import importlib.util
import io
import json
import tempfile
import unittest

TOOLS=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('fixture_fetch',TOOLS/'fetch-updater-baseline.py')
fetcher=importlib.util.module_from_spec(spec);spec.loader.exec_module(fetcher)

class Response(io.BytesIO):
    def __init__(self,data=b'',status=200,headers=None):
        super().__init__(data);self.status=status;self.headers=headers or {}

class FetchUpdaterBaselineTest(unittest.TestCase):
    def setUp(self):
        tmp=tempfile.TemporaryDirectory();self.addCleanup(tmp.cleanup);self.output=Path(tmp.name)
        self.data=b'Synthetic transport admission bytes; never extracted or executed.'
        self.pin=copy.deepcopy(fetcher.baseline.BASELINE)
        self.pin.update(bytes=len(self.data),sha256=hashlib.sha256(self.data).hexdigest())
        self.root='https://api.github.com/repos/'+self.pin['repository']
        self.repo=dict(full_name=self.pin['repository'],private=False)
        self.release=dict(id=self.pin['releaseId'],draft=False,published_at='2026-10-15T00:00:00Z',
            tag_name=self.pin['releaseTag'],target_commitish=self.pin['sourceSha'],assets=[])
        for pin in (dict(assetId=self.pin['assetId'],filename=self.pin['filename'],bytes=self.pin['bytes'],sha256=self.pin['sha256']),self.pin['sourceArchive']):
            self.release['assets'].append(dict(id=pin['assetId'],name=pin['filename'],state='uploaded',
                size=pin['bytes'],digest='sha256:'+pin['sha256'],url=self.root+'/releases/assets/'+str(pin['assetId'])))
        self.target='https://release-assets.githubusercontent.com/pinned-test-fixture?signature=synthetic'
        self.body=self.data;self.calls=[]

    def transport(self,url,headers):
        self.calls.append((url,dict(headers)))
        if url==self.root:return Response(json.dumps(self.repo).encode())
        if url==self.root+'/releases/'+str(self.pin['releaseId']):return Response(json.dumps(self.release).encode())
        if url==self.root+'/releases/assets/'+str(self.pin['assetId']):return Response(status=302,headers={'Location':self.target})
        if url==self.target:return Response(self.body,headers={'Content-Length':str(len(self.body))})
        raise AssertionError('Unexpected network destination')

    def fetch(self):return fetcher.fetch(self.output,'synthetic-repository-token',self.pin,self.transport)

    def test_verified_archive_is_published_and_storage_never_receives_repository_token(self):
        result=self.fetch();self.assertTrue(result['verified']);self.assertFalse(result['applicationLaunched'])
        self.assertEqual(self.data,(self.output/self.pin['filename']).read_bytes())
        self.assertEqual([self.pin['filename']],[p.name for p in self.output.iterdir()])
        self.assertTrue(all(h.get('Authorization')=='Bearer synthetic-repository-token'for _,h in self.calls[:-1]))
        self.assertNotIn('Authorization',self.calls[-1][1])

    def test_changed_or_unpublished_release_is_rejected_before_archive_request(self):
        original=copy.deepcopy(self.release)
        for change in (dict(draft=True),dict(target_commitish='0'*40),dict(tag_name='v999.0'),dict(published_at=None)):
            self.release=dict(original,**change);self.calls.clear()
            with self.assertRaisesRegex(ValueError,'release identity'):self.fetch()
            self.assertEqual(2,len(self.calls));self.assertEqual([],list(self.output.iterdir()))

    def test_missing_or_changed_corresponding_source_asset_is_rejected(self):
        original=copy.deepcopy(self.release['assets'])
        for mutate in ('missing','digest','duplicate'):
            self.release['assets']=copy.deepcopy(original)
            if mutate=='missing':self.release['assets'].pop()
            elif mutate=='digest':self.release['assets'][1]['digest']='sha256:'+'0'*64
            else:self.release['assets'][1]['id']=self.release['assets'][0]['id']
            self.calls.clear()
            with self.assertRaises(ValueError):self.fetch()
            self.assertEqual(2,len(self.calls));self.assertEqual([],list(self.output.iterdir()))

    def test_untrusted_redirects_are_never_contacted(self):
        for target in ('http://release-assets.githubusercontent.com/a','https://attacker.invalid/a',
                       'https://release-assets.githubusercontent.com.attacker.invalid/a',
                       'https://user@release-assets.githubusercontent.com/a'):
            self.target=target;self.calls.clear()
            with self.assertRaisesRegex(ValueError,'Untrusted'):self.fetch()
            self.assertEqual(3,len(self.calls));self.assertEqual([],list(self.output.iterdir()))

    def test_wrong_bytes_of_correct_size_leave_no_baseline_or_partial_file(self):
        self.body=b'x'*len(self.data)
        with self.assertRaisesRegex(ValueError,'hash or size differs'):self.fetch()
        self.assertEqual([],list(self.output.iterdir()))

    def test_truncated_or_oversized_transfer_is_rejected_without_publishing(self):
        for data in (self.data[:-1],self.data+b'extra'):
            self.body=data
            with self.assertRaises(ValueError):self.fetch()
            self.assertEqual([],list(self.output.iterdir()))

    def test_existing_verified_archive_is_reused_without_transfer_or_overwrite(self):
        destination=self.output/self.pin['filename'];destination.write_bytes(self.data);before=destination.stat().st_mtime_ns
        self.assertTrue(self.fetch()['verified']);self.assertEqual(2,len(self.calls))
        self.assertEqual(before,destination.stat().st_mtime_ns)

    def test_existing_bad_archive_is_preserved_and_refused(self):
        destination=self.output/self.pin['filename'];destination.write_bytes(b'x'*len(self.data))
        with self.assertRaisesRegex(ValueError,'hash differs'):self.fetch()
        self.assertEqual(b'x'*len(self.data),destination.read_bytes());self.assertEqual(2,len(self.calls))

if __name__=='__main__':unittest.main()
