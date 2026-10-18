"""Original v041 author identity through the actual sole comment producers."""
from pathlib import Path
import importlib.util,json,shutil,sys,tempfile,unittest
sys.dont_write_bytecode=True
ROOT=Path(__file__).resolve().parents[3]
sys.path.insert(0,str(ROOT/'desktop/tools'))
import v041_dynamic_comment_identity as identity
import v036_dynamic_comment_cache as state_source

def module(name,path):
    spec=importlib.util.spec_from_file_location(name,path);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m

class DynamicCommentIdentitySourceTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary=tempfile.TemporaryDirectory(prefix='bp-v041-comment-identity-')
        cls.output=Path(cls.temporary.name)
        cls.reply=module('identity_real_reply',ROOT/'desktop/tools/extract-upstream-dynamic-reply.py')
        cls.detail=module('identity_real_detail',ROOT/'desktop/tools/extract-upstream-dynamic-detail.py')
        cls.reply.generate(ROOT,cls.output/'reply');cls.detail.generate(ROOT,cls.output/'detail')
        host=cls.reply.load(ROOT,'identity_real_host','desktop/tools/extract-upstream-plugins.py')
        cls.media=host.media_extractor(ROOT);cls.parser=cls.media.parser_for(ROOT)
    @classmethod
    def tearDownClass(cls):cls.temporary.cleanup()

    def test_actual_row_state_and_thread_outputs_keep_original_author_arguments(self):
        inline=next((self.output/'reply').rglob('DesktopOriginalDynamicInlineReplyUi.kt')).read_text(encoding='utf-8')
        thread=next((self.output/'reply').rglob('DesktopOriginalDynamicThreadContent.kt')).read_text(encoding='utf-8')
        session=next((self.output/'detail').rglob('DesktopOriginalDynamicReplySession.kt')).read_text(encoding='utf-8')
        # Recover the exact v041 predecessor through the later full v046
        # slot builder, then retain the actual current author checks below.
        from v046_favorite_editor import restore
        proof=json.loads((self.output/'reply/v046-dynamic-inline-comment-items-proof.json').read_bytes())
        previous=restore(inline.split('\n',2)[2],proof['retainedToCurrent'])
        self.assertIn(self.media.function(identity.dynamic_comment_sheet(),'dynamicInlineCommentItems',self.parser),previous)
        self.assertIn('avatarListState = avatarListState,',inline)
        self.assertEqual(1,inline.count('upMid = dynamicAuthorMid,'))
        self.assertEqual(1,thread.count('upMid = state.upMid,'))
        self.assertEqual(2,session.count('upMid = _selectedDynamic.value?.modules?.module_author?.mid ?: 0L,'))
        before={p.relative_to(self.output):p.read_bytes()for p in self.output.rglob('*')if p.is_file()}
        self.reply.generate(ROOT,self.output/'reply');self.detail.generate(ROOT,self.output/'detail')
        self.assertEqual(before,{p.relative_to(self.output):p.read_bytes()for p in self.output.rglob('*')if p.is_file()})

    def test_complete_original_state_region_has_an_exact_platform_inverse(self):
        fields,body,selected=state_source.select_comment_region()
        self.assertEqual(identity.COMMIT,selected['sourceCommit'])
        self.assertIn(fields,identity.dynamic_view_model());self.assertIn(body,identity.dynamic_view_model())
        actual_fields,actual_body,proof=state_source.adapt_comment_region(ROOT,self.media,self.parser)
        for original,actual,key in [(fields,actual_fields,'fieldsInverse'),(body,actual_body,'consumersInverse')]:
            inverse=actual
            for edit in reversed(proof[key]['indexedEdits']):
                offset=edit['afterOffset'];self.assertEqual(edit['after'],inverse[offset:offset+len(edit['after'])])
                inverse=inverse[:offset]+edit['before']+inverse[offset+len(edit['after']):]
            self.assertEqual(original,inverse)
        generated=json.loads((self.output/'detail/v036-dynamic-comment-source-proof.json').read_bytes())
        self.assertEqual(identity.COMMIT,generated['sourceCommit'])
        self.assertFalse(generated['wholeDynamicViewModelPorted'])

    def test_missing_mutated_or_relabelled_complete_original_closure_fails_closed(self):
        with tempfile.TemporaryDirectory(prefix='bp-v041-comment-tamper-')as folder:
            repo=Path(folder);archive=repo/identity.SLICE
            shutil.copytree(ROOT/identity.SLICE,archive)
            tool=repo/'desktop/tools/v041_dynamic_comment_identity.py';tool.parent.mkdir(parents=True)
            shutil.copyfile(ROOT/'desktop/tools/v041_dynamic_comment_identity.py',tool)
            checked=module('identity_tamper_source',tool)
            for leaf in ['manifest.json',*[row['archiveFile']for row in identity.PINS]]:
                path=archive/leaf;before=path.read_bytes()
                for mutation in ['changed','missing']:
                    with self.subTest(leaf=leaf,mutation=mutation):
                        if mutation=='changed':path.write_bytes(before+b'\n')
                        else:path.unlink()
                        with self.assertRaises((ValueError,FileNotFoundError)):checked.originals()
                        path.write_bytes(before)
            manifest=json.loads((archive/'manifest.json').read_bytes());manifest['commit']='0'*40
            (archive/'manifest.json').write_text(json.dumps(manifest),encoding='utf-8')
            with self.assertRaises(ValueError):checked.originals()

    def test_both_actual_gradle_producers_track_the_new_complete_raw_inputs(self):
        build=(ROOT/'desktop/build.gradle.kts').read_text(encoding='utf-8')
        for name in ['extractUpstreamDynamicReply','extractUpstreamDynamicDetail']:
            start=build.index('val '+name+' by tasks.registering(Exec::class) {')
            block=build[start:build.index('\n}\n',start)]
            self.assertIn('inputs.file("tools/v041_dynamic_comment_identity.py")',block)
            self.assertIn('inputs.dir("upstream-slices/v041-dynamic-comment-identity")',block)
        self.assertEqual(2,len(identity.PINS))
        self.assertTrue(all(row['referenceOnly']is False for row in identity.PINS))

if __name__=='__main__':unittest.main()
