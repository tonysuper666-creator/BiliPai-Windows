import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

DESKTOP = Path(__file__).resolve().parents[2]
REPO = Path(os.environ.get('BILIPAI_LIVE_DANMAKU_REPO', DESKTOP.parent))
sys.path.insert(0, str(DESKTOP / 'tools'))
sys.path.append(str(REPO / 'desktop/tools'))
import v030_live_danmaku as fixed

def media():
    spec = importlib.util.spec_from_file_location('actual_media_danmaku', DESKTOP/'tools/extract-upstream-media.py')
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    return module

class LiveDanmakuSourceTests(unittest.TestCase):
    def test_complete_fixed_client_and_three_original_test_bodies(self):
        raw = fixed.sources()
        self.assertEqual(6, len(raw))
        self.assertEqual([11, 9, 10], [raw[name].count('@Test') for name in [
            'LiveDanmakuClientTest.kt', 'LiveDanmakuConnectionHealthPolicyTest.kt', 'DanmakuProtocolLimitsTest.kt']])
        body, edits = fixed.adapt_client(raw['LiveDanmakuClient.kt'])
        self.assertEqual(4, len(edits))
        for row in reversed(edits):
            at = row['offset']; self.assertEqual(row['after'], body[at:at+len(row['after'])])
            body = body[:at]+row['before']+body[at+len(row['after']):]
        self.assertEqual(raw['LiveDanmakuClient.kt'], body)

    def test_any_raw_or_manifest_tamper_is_rejected(self):
        with tempfile.TemporaryDirectory(prefix='live-socket-source-') as temp:
            copied = Path(temp)/'raw'; shutil.copytree(fixed.ROOT, copied)
            with patch.object(fixed, 'ROOT', copied):
                client = copied/'LiveDanmakuClient.kt'; original = client.read_bytes()
                client.write_bytes(original.replace(b'\n', b'\r\n'))
                with self.assertRaises(AssertionError): fixed.sources()
                client.write_bytes(original)
                manifest = copied/'manifest.json'; rows = json.loads(manifest.read_bytes())
                rows['sources'][0]['path'] = '../foreign.kt'; manifest.write_bytes(json.dumps(rows).encode())
                with self.assertRaises(AssertionError): fixed.sources()

    def test_sole_media_modes_preserve_all_canonical_pins(self):
        module = media()
        baseline = json.loads((REPO/'desktop/upstream-sources.json').read_bytes())
        proposed = json.loads((DESKTOP/'upstream-sources.json').read_bytes())
        old = {row['path']:row for row in baseline['sources']}
        new = {row['path']:row for row in proposed['sources']}
        self.assertEqual(old.keys(), new.keys())
        affected = [fixed.BASE+'DanmakuProtocol.kt', fixed.BASE+'LiveDanmakuConnectionHealthPolicy.kt']
        for name in affected:
            self.assertEqual('policy-extract', module.SOURCES[name])
            self.assertEqual('policy-extract', new[name]['mode'])
            self.assertEqual({k:v for k,v in old[name].items() if k!='mode'}, {k:v for k,v in new[name].items() if k!='mode'})
        for name in old.keys()-set(affected): self.assertEqual(old[name],new[name])

    def test_actual_cli_emits_whole_socket_and_tests_once_with_inverse(self):
        with tempfile.TemporaryDirectory(prefix='live-socket-cli-') as temp:
            output=Path(temp)/'main'; tests=Path(temp)/'tests'
            env=dict(os.environ, PYTHONIOENCODING='utf-8', PYTHONDONTWRITEBYTECODE='1',
                PYTHONPATH=os.pathsep.join([str(DESKTOP/'tools'),str(REPO/'desktop/tools')]))
            result=subprocess.run([sys.executable,'-X','utf8','-B',str(DESKTOP/'tools/extract-upstream-media.py'),
                '--repo',str(REPO),'--output',str(output),'--test-output',str(tests)],
                env=env,cwd=REPO,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
            self.assertEqual(0,result.returncode,result.stderr.decode('utf8'))
            proof=json.loads((output/'v030-live-danmaku-source-proof.json').read_bytes())
            self.assertEqual(6,len(proof['sources']))
            raw=fixed.sources()
            for row in proof['sources']:
                root=tests if row['test'] else output
                target=root/row['target']; emitted=target.read_bytes()
                self.assertEqual(1,len(list(root.rglob(target.name))))
                self.assertEqual(row['generatedSha256'],hashlib.sha256(emitted).hexdigest())
                body=emitted.decode('utf8')
                for edit in reversed(row['edits']):
                    at=edit['offset']; self.assertEqual(edit['after'],body[at:at+len(edit['after'])])
                    body=body[:at]+edit['before']+body[at+len(edit['after']):]
                self.assertEqual(raw[target.name],body)
                if row['test']: self.assertEqual(row['originalTestCount'],row['retainedTestCount'])

    def test_existing_sync_excludes_policy_extract_socket_duplicates(self):
        # Actual Gradle Sync only copies direct rows; the sole media output owns all
        # three FQCNs. Sync prunes its old destination entries on the next real run.
        gradle=(DESKTOP/'build.gradle.kts').read_text('utf8')
        self.assertIn('sources.filter { (it["mode"] ?: "direct") == "direct" }',gradle)
        self.assertIn('tasks.registering(Sync::class)',gradle)
        self.assertIn('inputs.file("tools/v030_live_danmaku.py")',gradle)
        self.assertIn('inputs.dir("upstream-slices/v030-live-danmaku")',gradle)
        self.assertIn('generated/live-stream-original-tests',gradle)
        manifest=json.loads((DESKTOP/'upstream-sources.json').read_bytes())
        direct={r['path'] for r in manifest['sources'] if r['mode']=='direct'}
        self.assertFalse(set([fixed.BASE+'DanmakuProtocol.kt',fixed.BASE+'LiveDanmakuConnectionHealthPolicy.kt'])&direct)

    def test_authored_actual_owner_tests_and_no_parallel_socket_owner(self):
        authored=DESKTOP/'src/test/kotlin/com/bilipai/desktop/data/DesktopLiveDanmakuOwnedSessionTest.kt'
        text=authored.read_text('utf8')
        self.assertEqual(11,text.count('@Test'))
        self.assertEqual(11,text.count('(): Unit = runBlocking'))
        self.assertIn('chain.proceed(request.newBuilder().url(local).build())',text)
        self.assertIn('liveDanmakuClient(f.scope(), 123)',text)
        self.assertIn('resume.run(); caller.join()',text)
        session=(DESKTOP/'src/main/kotlin/com/bilipai/desktop/data/DesktopLiveSession.kt').read_text('utf8')
        self.assertIn('repository.sessionEpochFlow.collect',session)
        self.assertNotIn('playbackAuthorizationRevision',session)

if __name__ == '__main__': unittest.main()
