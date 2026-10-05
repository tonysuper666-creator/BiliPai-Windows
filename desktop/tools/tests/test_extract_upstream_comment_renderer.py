from pathlib import Path
import hashlib, importlib.util, json, os, subprocess, sys, tempfile, unittest

REPO=Path(os.environ.get('BILIPAI_COMMENT_TEST_REPO',Path(__file__).resolve().parents[3]))
TOOLS=Path(os.environ.get('BILIPAI_COMMENT_TEST_TOOLS',REPO/'desktop/tools'))
sys.path.insert(0,str(TOOLS))
import v021_comment_renderer as adapter

class OriginalCommentPresentationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp=tempfile.TemporaryDirectory(prefix='bp-comment-renderer-')
        cls.outputs={}
        for label,tool in [('reply','extract-upstream-dynamic-reply.py'),('card','extract-upstream-dynamic-card.py')]:
            output=Path(cls.temp.name)/label
            result=subprocess.run([sys.executable,'-B',str(TOOLS/tool),'--repo',str(REPO),'--output',str(output)],
                capture_output=True,encoding='utf8',timeout=90,
                env=dict(os.environ,PYTHONPATH=os.pathsep.join([str(TOOLS),str(REPO/'desktop/tools')]),PYTHONDONTWRITEBYTECODE='1'))
            if result.returncode: raise AssertionError(result.stderr)
            cls.outputs[label]=output
    @classmethod
    def tearDownClass(cls): cls.temp.cleanup()
    def proofs(self):
        return json.loads((self.outputs['reply']/'windows-comment-presentation.json').read_text(encoding='utf8'))+[
            json.loads((self.outputs['card']/'windows-comment-image-presentation.json').read_text(encoding='utf8'))]
    def body(self,proof):
        matches=list(self.outputs['card' if 'ImagePreview' in proof['filename'] else 'reply'].rglob(proof['filename']))
        self.assertEqual(1,len(matches))
        text=matches[0].read_text(encoding='utf8')
        return '\n'.join(text.split('\n')[2:])
    def test_every_complete_original_body_restores_after_counted_container_edits(self):
        proofs=self.proofs();self.assertEqual(4,len(proofs))
        self.assertEqual(4,len({p['filename'] for p in proofs}))
        for proof in proofs:
            body=self.body(proof)
            self.assertEqual(proof['outputBodySha256'],adapter.sha(body))
            original=adapter.inverse(body,proof['edits'])
            self.assertEqual(proof['inputBodySha256'],adapter.sha(original))
            replay,reproof=adapter.adapt(original,proof['filename'])
            self.assertEqual(body,replay);self.assertEqual(proof['edits'],reproof['edits'])
            self.assertTrue(proof['completeInverse'])
    def test_mutated_full_body_or_ambiguous_anchor_is_rejected(self):
        for proof in self.proofs():
            body=self.body(proof);edit=proof['edits'][-1]
            tampered=body[:edit['offset']]+'!'+body[edit['offset']+1:]
            with self.assertRaises(ValueError): adapter.inverse(tampered,proof['edits'])
            original=adapter.inverse(body,proof['edits'])
            with self.assertRaises(ValueError): adapter.adapt(original+proof['edits'][0]['before'],proof['filename'])
    def test_unique_original_preview_host_content_and_pager_are_preserved(self):
        proof=next(p for p in self.proofs() if 'ImagePreview' in p['filename'])
        body=self.body(proof);before=adapter.inverse(body,proof['edits'])
        self.assertEqual(before[before.index('@Composable\nprivate fun ImagePreviewOverlayContent('):],
                         body[body.index('@Composable\nprivate fun ImagePreviewOverlayContent('):])
        self.assertEqual(1,body.count('fun ImagePreviewOverlayHost('))
        self.assertEqual(1,body.count('private object ImagePreviewOverlayController'))
        self.assertIn('presentation = request.desktopNativePresentation',body)
        self.assertIn('ImagePreviewOverlayController.dismiss(request.token)',body)
        self.assertIn('else if (desktopNativePresentation.canPresentNative()) desktopNativePresentation.dispatch {',body)
        self.assertIn('if (desktopNativePresentation.canPresentNative()) ImagePreviewOverlayController.show(capturedRequest)',body)
        self.assertIn('else null',body) # Native owner uses original null-anchor path, not Main geometry.
    def test_legacy_default_and_exact_request_owner_are_not_globalized(self):
        file=REPO/'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopWindowsCommentPresentation.kt'
        if not file.exists():
            file=Path(os.environ['BILIPAI_COMMENT_TEST_PREPARED'])/file.relative_to(REPO)
        source=file.read_text(encoding='utf8')
        self.assertIn('DesktopWindowsCommentPresentation?> { null }',source)
        self.assertIn('if (presentation == null) Dialog(',source)
        self.assertIn('owner = null',source)
        self.assertNotIn('object DesktopWindowsCommentPresentation',source)
    def test_actual_bounded_caller_keeps_one_vm_and_search_entry(self):
        root=Path(os.environ.get('BILIPAI_COMMENT_TEST_PREPARED',REPO))
        ui=root/'desktop/src/main/kotlin/com/bilipai/desktop/ui'
        section=(ui/'DesktopWindowsVideoCommentsSection.kt').read_text(encoding='utf8')
        leaf=(ui/'DesktopWindowsVideoPhysicalLeaf.kt').read_text(encoding='utf8')
        details=(ui/'DesktopWindowsVideoDetailsPanel.kt').read_text(encoding='utf8')
        self.assertIn('val vm = assembly.domains.comments',section)
        self.assertNotIn('VideoCommentViewModel(',section);self.assertNotIn('CommentDetailViewModel(',section)
        self.assertIn('VideoCommentTab(',section);self.assertIn('VideoInlineSubReplyDetailContent(',section)
        self.assertEqual(1,leaf.count('DesktopWindowsCommentSearchSection('))
        self.assertNotIn('DesktopWindowsVideoComments(',leaf)
        self.assertIn('if (selectedTab == DesktopWindowsVideoDetailsTab.COMMENTS)',details)
        self.assertIn('Box(Modifier.fillMaxWidth().weight(1f)',details)
        self.assertNotIn('COMMENTS -> item { comments() }',details)

if __name__=='__main__': unittest.main()
