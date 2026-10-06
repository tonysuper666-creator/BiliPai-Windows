from pathlib import Path
import hashlib, importlib.util, json, os, shutil, subprocess, sys, tempfile, unittest

REPO=Path(os.environ.get('BILIPAI_COMPOSER_TEST_REPO',Path(__file__).resolve().parents[3]))
TOOLS=Path(os.environ.get('BILIPAI_COMPOSER_TEST_TOOLS',REPO/'desktop/tools'))
sys.path.insert(0,str(TOOLS))
import v029_comment_composer as adapter

def inverse(body,proof):
    for row in reversed(proof['indexedEdits']):
        position=row['afterOffset'];after=row['after']
        if body[position:position+len(after)]!=after:raise ValueError('Changed generated source proof')
        body=body[:position]+row['before']+body[position+len(after):]
    if hashlib.sha256(body.encode()).hexdigest()!=proof['beforeSha256LF']:raise ValueError('Incomplete source inverse')
    return body

class OriginalDomainCommentComposerTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp=tempfile.TemporaryDirectory(prefix='bp-comp-')
        cls.outputs={}
        for label,tool in [('holder','extract-upstream-video-detail-holder.py'),('comment','extract-upstream-video-comment-ui.py'),('bgm','extract-upstream-bgm-detail.py')]:
            out=Path(cls.temp.name)/label
            args=[str(REPO),str(out)] if label=='holder' else ['--repo',str(REPO),'--output',str(out)]
            done=subprocess.run([sys.executable,'-B',str(TOOLS/tool),*args],capture_output=True,timeout=90,
                env=dict(os.environ,PYTHONPATH=str(TOOLS),PYTHONDONTWRITEBYTECODE='1'))
            if done.returncode:raise AssertionError(done.stderr.decode('utf8',errors='replace'))
            cls.outputs[label]=out
    @classmethod
    def tearDownClass(cls):cls.temp.cleanup()
    def proof(self,label,name):return json.loads((self.outputs[label]/(name+'.json')).read_text(encoding='utf8'))
    def body(self,label,name):
        matches=list((self.outputs[label]/'com').rglob(name));self.assertEqual(1,len(matches))
        return matches[0].read_text(encoding='utf8')
    def fixture(self,root):
        for row in adapter.PINS:
            destination=adapter.wide(root/adapter.ARCHIVE/row['path']);destination.parent.mkdir(parents=True,exist_ok=True)
            destination.write_bytes(adapter.wide(REPO/adapter.ARCHIVE/row['path']).read_bytes())
        destination=root/adapter.ARCHIVE/'manifest.json';destination.write_bytes((REPO/adapter.ARCHIVE/'manifest.json').read_bytes())
        return root
    def test_full_raw_blob_and_manifest_are_exact_fixed_v029(self):
        sources=adapter.fixed(REPO);self.assertEqual(5,len(sources))
        self.assertEqual('a4b77f894d0a2dd26c0b9fc144b8adb88ac05480',adapter.COMMIT)
        for row in adapter.PINS:
            body=sources[row['path']].encode();self.assertEqual(row['rawSha256'],hashlib.sha256(body).hexdigest())
            self.assertEqual(row['gitBlob'],hashlib.sha1(b'blob '+str(len(body)).encode()+b'\0'+body).hexdigest())
    def test_mutated_manifest_and_raw_bytes_are_rejected(self):
        with tempfile.TemporaryDirectory(prefix='bp-comp-raw-') as temp:
            root=self.fixture(Path(temp));path=adapter.wide(root/adapter.ARCHIVE/adapter.VM)
            body=path.read_bytes();path.write_bytes(body+b' ')
            with self.assertRaises(ValueError):adapter.fixed(root)
            path.write_bytes(body);(root/adapter.ARCHIVE/'manifest.json').write_bytes(b'{}')
            with self.assertRaises(ValueError):adapter.fixed(root)
    def test_crlf_is_not_accepted_as_the_original_raw_blob(self):
        with tempfile.TemporaryDirectory(prefix='bp-comp-crlf-') as temp:
            root=self.fixture(Path(temp));path=adapter.wide(root/adapter.ARCHIVE/adapter.CORE)
            path.write_bytes(path.read_bytes().replace(b'\n',b'\r\n'))
            with self.assertRaises(ValueError):adapter.fixed(root)
    def test_real_domain_output_restores_core_and_every_original_selection(self):
        proof=self.proof('holder','v029-domain-comment-composer-source')
        body=self.body('holder','VideoComposerViewModel.kt')
        core=inverse(body,proof['canonicalDomainToGenerated'])
        tools=adapter.load_module(TOOLS/'extract-upstream-video-detail-holder.py','composer_core_recipe_test')
        spec=next(s for s in tools.SPECS if s['output'].endswith('/VideoComposerViewModel.kt'))
        self.assertEqual(spec['outputSHA256LF'],hashlib.sha256(core.encode()).hexdigest())
        original=adapter.fixed(REPO)[adapter.VM]
        selected=''.join(original[row['start']:row['end']] for row in proof['selectedRawRanges'])
        for row in proof['selectedRawRanges']:
            self.assertEqual(row['sha256'],hashlib.sha256(original[row['start']:row['end']].encode()).hexdigest())
        legacy=self.body('comment','DesktopOriginalVideoCommentComposer.kt')
        self.assertEqual(selected,inverse(legacy,proof['selectedRawToLegacyBody']))
        self.assertEqual(proof['selectedLegacyCompleteBodySha256'],hashlib.sha256(legacy.encode()).hexdigest())
        start=body.index('    private val _commentInput =');end=body.index('\n}\n',start)
        originalMembers=inverse(body[start:end],proof['legacyMemberAdaptation'])
        self.assertEqual(legacy[legacy.index('    private val _commentInput ='):legacy.index('\n}\n\ndata class CommentMentionSearchUiState')],originalMembers)
    def test_sole_domain_and_original_input_content_are_not_replaced_by_a_new_vm(self):
        matches=[p for root in self.outputs.values() for p in root.rglob('VideoComposerViewModel.kt')]
        self.assertEqual(1,len(matches))
        body=self.body('comment','DesktopOriginalVideoCommentInputOverlay.kt')
        proof=self.proof('comment','v029-domain-comment-input-source')
        canonical=inverse(body,proof['canonicalToGenerated'])
        marker='@Composable\nprivate fun VideoDetailCommentInputOverlayContent('
        self.assertEqual(canonical[canonical.index(marker):],body[body.index(marker):])
        self.assertEqual(2,body.count('fun DesktopOriginalVideoCommentInputOverlay('))
        schema=self.body('comment','VideoComposerDraftState.kt')
        self.assertEqual(adapter.fixed(REPO)[adapter.BASE+'feature/video/viewmodel/VideoComposerDraftState.kt'].replace('import android.net.Uri\n','').replace('List<Uri>','List<String>'),schema)
    def test_full_original_dialog_adapts_container_and_publishes_selected_mention_draft(self):
        proof=self.proof('bgm','v029-domain-comment-dialog-source')
        body=self.body('bgm','CommentInputDialog.kt');legacy=inverse(body,proof['platformAndDraftPublication'])
        self.assertTrue(proof['fixedCanonicalEntireBodyIdentical'])
        self.assertTrue(proof['mentionDraftPublicationRepair'])
        self.assertEqual(7,len(proof['platformAndDraftPublication']['indexedEdits']))
        mention='                                    textFieldValue = TextFieldValue(nextText, nextSelection)\n'
        self.assertEqual(1,legacy.count(mention))
        expected=legacy.replace('import androidx.compose.ui.window.Dialog\n','import com.bilipai.desktop.ui.DesktopWindowsCommentComposerWindow as Dialog\n')
        expected=expected.replace(mention,mention+'                                    onDraftChange(nextText, selectedImageUris, isForwardToDynamic)\n')
        self.assertTrue(proof['windowsNativeClientLayoutOnly'])
        expected=expected.replace('import com.bilipai.desktop.ui.DesktopWindowsCommentComposerWindow as Dialog\n','import com.bilipai.desktop.ui.DesktopWindowsCommentComposerWindow as Dialog\nimport com.bilipai.desktop.ui.desktopCommentComposerSurfaceHeight\nimport com.bilipai.desktop.ui.desktopCommentComposerColumnHeight\nimport com.bilipai.desktop.ui.desktopCommentComposerInputHeight\nimport com.bilipai.desktop.ui.desktopCommentComposerClientHeightDp\n')
        expected=expected.replace('            val availablePanelHeightDp = configuration.heightDp.value.toInt() -\n','            val availablePanelHeightDp = desktopCommentComposerClientHeightDp(configuration.heightDp.value.toInt()) -\n')
        expected=expected.replace('                        .wrapContentHeight(),\n','                        .then(desktopCommentComposerSurfaceHeight()),\n')
        expected=expected.replace('                        modifier = Modifier\n                            .padding(layoutPolicy.sheetHorizontalPaddingDp.dp)\n','                        modifier = Modifier\n                            .then(desktopCommentComposerColumnHeight())\n                            .padding(layoutPolicy.sheetHorizontalPaddingDp.dp)\n')
        expected=expected.replace('                                .heightIn(\n                                    min = layoutPolicy.inputBoxMinHeightDp.dp,\n                                    max = layoutPolicy.inputBoxMaxHeightDp.dp\n                                )\n','                                .then(desktopCommentComposerInputHeight(\n                                    min = layoutPolicy.inputBoxMinHeightDp.dp,\n                                    max = layoutPolicy.inputBoxMaxHeightDp.dp\n                                ))\n')
        self.assertEqual(expected,body)
    def test_changed_domain_body_cannot_reuse_a_complete_inverse_receipt(self):
        proof=self.proof('holder','v029-domain-comment-composer-source')
        body=self.body('holder','VideoComposerViewModel.kt')
        row=proof['canonicalDomainToGenerated']['indexedEdits'][-1]
        index=row['afterOffset'];changed=body[:index]+'!'+body[index+1:]
        with self.assertRaises(ValueError):inverse(changed,proof['canonicalDomainToGenerated'])

if __name__=='__main__':unittest.main()
