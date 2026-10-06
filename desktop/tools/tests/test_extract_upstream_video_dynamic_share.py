"""Fixed full v030 sources and actual existing sole CLI output, no JVM/network/UI."""
from pathlib import Path
import hashlib, importlib.util, json, os, shutil, subprocess, sys, tempfile, unittest
from unittest.mock import patch

TOOLS=Path(__file__).resolve().parents[1]
REPO=TOOLS.parents[1]
sys.path.insert(0,str(TOOLS))
import v030_video_dynamic_share as source
def load(name,file):
    spec=importlib.util.spec_from_file_location(name,file);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
def reverse(body,rows):
    for row in reversed(rows):
        start=row['offset'];after=row['after'];before=row['before']
        assert body[start:start+len(after)]==after
        body=body[:start]+before+body[start+len(after):]
    return body

class VideoDynamicShareSourceTest(unittest.TestCase):
    def test_all_five_raw_blobs_have_exact_path_commit_and_no_normalization(self):
        originals=source.sources(REPO)
        self.assertEqual(5,len(originals))
        self.assertEqual('0e2206a85e288ba08f361cc636fab0710c2b9ab8',source.COMMIT)
        for name,body in originals.items():
            raw=body.encode();pin=source.PINS[name]
            self.assertEqual(pin,(hashlib.sha256(raw).hexdigest(),hashlib.sha1(b'blob '+str(len(raw)).encode()+b'\0'+raw).hexdigest()))
            self.assertNotIn(b'\r',raw)

    def test_repin_or_source_path_tamper_is_rejected(self):
        for field,value in (('sha256','0'*64),('path','app/other.kt')):
            with tempfile.TemporaryDirectory() as temp:
                root=Path(temp)/'slice';shutil.copytree(source.ROOT,root)
                manifest=root/'manifest.json';data=json.loads(manifest.read_bytes());data['sources'][0][field]=value
                manifest.write_text(json.dumps(data),encoding='utf8')
                with patch.object(source,'ROOT',root),self.assertRaises(AssertionError):source.sources(REPO)

    def test_crlf_and_changed_raw_are_rejected(self):
        for transform in (lambda raw:raw.replace(b'\n',b'\r\n'),lambda raw:raw+b'\n'):
            with tempfile.TemporaryDirectory() as temp:
                root=Path(temp)/'slice';shutil.copytree(source.ROOT,root)
                file=root/'VideoShareToDynamicDialog.kt';file.write_bytes(transform(file.read_bytes()))
                with patch.object(source,'ROOT',root),self.assertRaises(AssertionError):source.dialog(REPO)

    def test_full_dialog_and_repository_inverse_restore_original_complete_bodies(self):
        originals=source.sources(REPO)
        for name,adapter in (('VideoShareToDynamicDialog.kt',source.dialog),('VideoDynamicShareRepository.kt',source.repository)):
            final,proof=adapter(REPO)
            self.assertEqual(originals[name],reverse(final,proof['edits']))
            self.assertEqual(source.sha(final),proof['generatedSha256'])
            self.assertEqual(source.COMMIT,proof['upstreamCommit'])
            self.assertEqual(source.SOURCE_PATHS[name],proof['sourcePath'])
        dialog,_=source.dialog(REPO)
        self.assertLess(dialog.index('context.sharePrepared(payload.bvid)'),dialog.index('context.showFeedback("已分享到动态")'))
        self.assertLess(dialog.index('context.showFeedback("已分享到动态")'),dialog.index('                                        onDismiss()'))

    def test_real_share_cli_emits_complete_new_types_and_full_raw_inverse(self):
        environment=os.environ.copy();environment['GIT_NO_LAZY_FETCH']='1'
        with tempfile.TemporaryDirectory(prefix='video-dynamic-share-cli-') as temp:
            out=Path(temp)/'share'
            result=subprocess.run([sys.executable,str(TOOLS/'extract-upstream-video-share-consent.py'),
                '--source-repo',str(REPO),'--output-dir',str(out)],env=environment,capture_output=True)
            self.assertEqual(0,result.returncode,result.stderr.decode('utf8','replace'))
            report=json.loads((out/'video-share-consent-selection-proof.json').read_bytes())
            originals=source.sources(REPO);checked=0
            for row in report['selectedSources']:
                stage=row['v030FullSourceAdaptation']
                if stage is None:continue
                final=(out/row['target']).read_text(encoding='utf8')
                for before,after,count in reversed(row['windowsPresentationAudit']):
                    self.assertEqual(count,final.count(after));final=final.replace(after,before,count)
                self.assertEqual(source.sha(final),stage['generatedSha256'])
                original=reverse(final,stage['edits'])
                self.assertEqual(originals[Path(stage['sourcePath']).name],original);checked+=1
            self.assertEqual(2,checked)
            additions=json.loads((out/'v030-video-dynamic-share-additions-proof.json').read_bytes())
            for row in additions['outputs']:
                final=(out/row['target']).read_text(encoding='utf8')
                self.assertEqual(originals[Path(row['sourcePath']).name],reverse(final,row['edits']))
            sheet=(out/'com/android/purebilibili/feature/video/share/VideoShareSheet.kt').read_text(encoding='utf8')
            self.assertIn('VideoShareToDynamicDialog(payload = payload, onDismiss = onDismiss)',sheet)
            self.assertNotIn('VideoShareFeedbackEvents',sheet)
            self.assertIn('iconVector = Icons.Outlined.DynamicFeed',sheet)
            self.assertIn('iconVector = Icons.Outlined.People',sheet)
            self.assertIn('.background(item.backgroundColor)',sheet)
            for android_only in ('appIcon', 'AndroidView', 'packageManager.getApplicationIcon'):
                self.assertNotIn(android_only,sheet)

    def test_real_api_cli_emits_one_complete_model_with_unchanged_canonical_inventory(self):
        with tempfile.TemporaryDirectory(prefix='video-dynamic-api-cli-') as temp:
            out=Path(temp)/'api'
            result=subprocess.run([sys.executable,str(TOOLS/'extract-upstream-api.py'),'--repo',str(REPO),'--output',str(out)],capture_output=True)
            self.assertEqual(0,result.returncode,result.stderr.decode('utf8','replace'))
            models=list(out.rglob('DynamicCreateModels.kt'));self.assertEqual(1,len(models))
            self.assertEqual(source.sources(REPO)['DynamicCreateModels.kt'].encode(),models[0].read_bytes())
            report=json.loads((out/'v030-video-dynamic-model-proof.json').read_bytes())
            self.assertTrue(report['fullSourceInverseVerified']);self.assertEqual([],report['edits'])
        rows=json.loads((TOOLS.parent/'upstream-sources.json').read_bytes())['sources']
        row=next(row for row in rows if row['path']==source.SOURCE_PATHS['DynamicCreateModels.kt'])
        self.assertEqual('policy-extract',row['mode'])
        self.assertEqual('e1311bfb5c0999d7d9aecc4399273f30d20af3e394271d2205c31f8f08054d29',row['sha256'])

if __name__=='__main__':unittest.main()
