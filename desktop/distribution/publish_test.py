import hashlib
import importlib.util
import json
import base64
import os
from pathlib import Path
import tempfile
import subprocess
import unittest

spec = importlib.util.spec_from_file_location('publisher', Path(__file__).with_name('publish.py'))
publisher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publisher)


class PublicationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name) / 'data'
        self.incoming = Path(self.temp.name) / 'incoming'
        self.incoming.mkdir()
        self.zip_name = 'BiliPai-Windows-0.2.427.201-x64.zip'
        self.installer = 'BiliPai-Windows-0.2.427.201.msi'
        for name in [self.zip_name, self.installer]:
            (self.incoming / name).write_bytes(b'fixture only')
        self.signed_fixture()

    def tearDown(self):
        self.temp.cleanup()

    def save(self):
        (self.incoming / 'publish.json').write_text(json.dumps(self.metadata))

    def signed_fixture(self, **options):
        result = subprocess.run(['node', str(Path(__file__).with_name('publication-fixture.mjs'))],
                                input=json.dumps(options), text=True, capture_output=True, check=True)
        fixture = json.loads(result.stdout)
        self.public_key = fixture['publicKey']
        self.metadata = fixture['metadata']
        for kind in ['manifest', 'config']:
            (self.incoming / f'{kind}.json').write_text(fixture[kind])
        self.save()

    def verify(self, manifest, config, metadata):
        # The production CLI has no key override. Tests call the same verifier
        # function with an ephemeral fixture's public key, never the real private key.
        script = "import{readFileSync}from'node:fs';import{verifyPublication}from'./verify-publication.mjs';const x=JSON.parse(readFileSync(0,'utf8'));console.log(JSON.stringify(verifyPublication(x.manifest,x.config,x.metadata,{publicKey:x.publicKey})));"
        result = subprocess.run(['node', '--input-type=module', '-e', script], cwd=Path(__file__).parent,
                                input=json.dumps({'manifest': manifest, 'config': config, 'metadata': metadata, 'publicKey': self.public_key}),
                                text=True, capture_output=True)
        if result.returncode:
            raise ValueError(result.stderr)
        return json.loads(result.stdout)

    def publish(self):
        return publisher.publish(self.root, self.incoming, verify=self.verify)

    def test_publish_preserves_immutable_files_and_atomically_switches_channel(self):
        self.publish()
        pointer = self.root / 'channels/dev/current.json'
        self.assertEqual(json.loads(pointer.read_text())['sequence'], 1)
        self.assertTrue((self.root / 'channels/dev/1/manifest.json').is_file())
        self.assertTrue((self.root / 'channels/dev/1/config.json').is_file())
        with self.assertRaises(ValueError):
            self.publish()
        self.signed_fixture(sequence=2, withdrawn=True)
        self.metadata['files'] = []  # Signed withdrawal reuses immutable artifacts.
        self.save()
        self.publish()
        self.assertEqual(json.loads(pointer.read_text())['sequence'], 2)
        self.assertEqual((self.root / 'public/artifacts/0.2.427.201' / self.zip_name).read_bytes(), b'fixture only')

    def test_corrupt_upload_and_immutable_conflict_leave_current_pointer_intact(self):
        self.publish()
        pointer = self.root / 'channels/dev/current.json'
        original = pointer.read_bytes()
        self.signed_fixture(sequence=2)
        (self.incoming / self.zip_name).write_bytes(b'corruption')
        self.save()
        with self.assertRaises(ValueError):
            self.publish()
        self.assertEqual(pointer.read_bytes(), original)
        self.signed_fixture(sequence=2, zip='corruption')
        with self.assertRaises(ValueError):
            self.publish()
        self.assertEqual(pointer.read_bytes(), original)

    def test_stable_requires_explicit_admission_and_paths_cannot_escape(self):
        self.signed_fixture(channel='stable', stableApproved=False)
        self.metadata['stableApproved'] = True
        self.save()
        with self.assertRaises(ValueError):
            self.publish()
        self.signed_fixture()
        self.metadata['files'][0]['name'] = '../escape.zip'
        self.save()
        with self.assertRaises(ValueError):
            self.publish()
        self.assertFalse((self.root / 'channels/dev/current.json').exists())

    def test_unsigned_or_changed_documents_cannot_become_current(self):
        for kind in ['manifest', 'config']:
            with self.subTest(kind=kind):
                self.signed_fixture()
                file = self.incoming / f'{kind}.json'
                file.write_text('{"fixture":"unsigned"}')
                with self.assertRaises(ValueError):
                    # This is the exact production entry point with the pinned key.
                    publisher.publish(self.root, self.incoming)
                self.assertFalse((self.root / 'channels/dev/current.json').exists())
                self.signed_fixture()
                signed = json.loads(file.read_text())
                body = bytearray(base64.b64decode(signed['payload']))
                body[len(body)//2] ^= 1
                signed['payload'] = base64.b64encode(body).decode()
                file.write_text(json.dumps(signed))
                with self.assertRaises(ValueError):
                    self.publish()
        self.signed_fixture(badArchiveSignature=True)
        with self.assertRaises(ValueError):
            self.publish()
        self.assertFalse((self.root / 'channels/dev/current.json').exists())

    def test_replaced_installer_with_self_reported_hash_keeps_old_pointer(self):
        self.publish()
        pointer = self.root / 'channels/dev/current.json'
        original = pointer.read_bytes()
        self.signed_fixture(sequence=2)
        (self.incoming / self.installer).write_bytes(b'malicious replacement')
        self.metadata['files'][1]['sha256'] = hashlib.sha256(b'malicious replacement').hexdigest()
        self.save()
        with self.assertRaises(ValueError):
            self.publish()
        self.assertEqual(pointer.read_bytes(), original)
        self.assertEqual((self.root / 'installers/0.2.427.201' / self.installer).read_bytes(), b'fixture only')

    def test_publication_and_server_account_own_the_same_private_files(self):
        self.publish()
        for file in [self.root / 'channels/dev/current.json', self.root / 'channels/dev/1/manifest.json',
                     self.root / 'installers/0.2.427.201' / self.installer]:
            self.assertEqual(file.stat().st_uid, os.geteuid())
            self.assertEqual(file.stat().st_mode & 0o777, 0o600)
        script = """
import {spawnSync} from 'node:child_process';
import {createDistributionServer} from './server.mjs';
const root=process.argv[1];
const invite=spawnSync('node',['invitation.mjs','create','service-test','dev'],{encoding:'utf8',env:{...process.env,BILIPAI_DISTRIBUTION_ROOT:root}});
if(invite.status) throw Error(invite.stderr);
const server=createDistributionServer({root});
await new Promise(r=>server.listen(0,'127.0.0.1',r));
const base=`http://127.0.0.1:${server.address().port}`;
try {
 const response=await fetch(base+'/unlock',{method:'POST',redirect:'manual',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams({code:invite.stdout.trim()})});
 if(response.status!==303) throw Error('Invitation writer/service mismatch: '+response.status);
 const cookie=response.headers.get('set-cookie').split(';')[0];
 for(const url of ['/updates/dev.json','/config/dev.json','/download/0.2.427.201/BiliPai-Windows-0.2.427.201.msi']) {
   const r=await fetch(base+url,{headers:{cookie}}); if(r.status!==200) throw Error(url+' returned '+r.status); await r.arrayBuffer();
 }
 console.log('Publisher/invitation/service same UID '+process.getuid()+': unlock 303, manifest/config/MSI 200');
} finally {server.closeAllConnections();await new Promise(r=>server.close(r));}
"""
        result = subprocess.run(['node', '--input-type=module', '-e', script, str(self.root)],
                                cwd=Path(__file__).parent, text=True, capture_output=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        print(result.stdout.strip())

    def test_concurrent_invitation_writers_preserve_each_code_and_lock_survives_process_death(self):
        directory = self.root / 'private'
        directory.mkdir(parents=True)
        lock = directory / 'invitations.lock'
        holder = subprocess.Popen(['python3', '-c',
            'import fcntl,sys,time;f=open(sys.argv[1],"a+");fcntl.flock(f,fcntl.LOCK_EX);print("locked",flush=True);time.sleep(30)', str(lock)],
            stdout=subprocess.PIPE, text=True)
        try:
            self.assertEqual(holder.stdout.readline().strip(), 'locked')
            holder.terminate()
            holder.wait(timeout=5)
        finally:
            if holder.poll() is None:
                holder.kill()
                holder.wait()
            holder.stdout.close()
        env = {**os.environ, 'BILIPAI_DISTRIBUTION_ROOT': str(self.root)}
        workers = [subprocess.Popen(['node', 'invitation.mjs', 'create', f'friend-{i}', 'dev'],
                                  cwd=Path(__file__).parent, env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
                   for i in range(4)]
        try:
            for worker in workers:
                _, error = worker.communicate(timeout=10)
                self.assertEqual(worker.returncode, 0, error)
        finally:
            for worker in workers:
                if worker.poll() is None:
                    worker.terminate()
                    worker.wait(timeout=5)
        registry = json.loads((directory / 'invitations.json').read_text())
        self.assertEqual({item['label'] for item in registry.values()}, {f'friend-{i}' for i in range(4)})


if __name__ == '__main__':
    unittest.main()
