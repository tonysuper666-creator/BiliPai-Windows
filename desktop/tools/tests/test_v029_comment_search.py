"""Fixed raw sources, real sole-producer output and full inverse contracts."""
from pathlib import Path
import hashlib,importlib.util,json,os,shutil,subprocess,sys,tempfile,unittest
TOOLS=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(TOOLS))
import v029_comment_search as fixed
REPO=TOOLS.parents[1]
def wide(p):
    s=os.path.abspath(p)
    return Path('\\\\?\\'+s) if os.name=='nt' and not s.startswith('\\\\?\\') else Path(s)
def read(p): return wide(p).read_text(encoding='utf8').replace('\r\n','\n')
def digest(b): return hashlib.sha256(b).hexdigest()
def forward(before,p):
    result=before
    for e in reversed(p['indexedEdits']):
        at=e['beforeOffset'];assert result[at:at+len(e['before'])]==e['before']
        result=result[:at]+e['after']+result[at+len(e['before']):]
    return result
def inverse(after,p):
    result=after
    for e in reversed(p['indexedEdits']):
        at=e['afterOffset'];assert result[at:at+len(e['after'])]==e['after']
        result=result[:at]+e['before']+result[at+len(e['after']):]
    return result

class V029CommentSearchSourceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp=tempfile.TemporaryDirectory(prefix='comment-source-')
        cls.out=Path(cls.temp.name)
        cls.original=fixed.fixed_sources(REPO)
        cls.outputs={}
        for task in ['api','bgm-detail','video-comment-ui','dynamic-reply','dynamic-reply-protocol']:
            out=cls.out/str(len(cls.outputs));cls.outputs[task]=out
            result=subprocess.run([sys.executable,'-X','utf8','-B',str(TOOLS/('extract-upstream-'+task+'.py')),
                '--repo',str(REPO),'--output',str(out)],cwd=REPO,capture_output=True)
            if result.returncode: raise AssertionError(result.stderr.decode('utf8','replace'))
    @classmethod
    def tearDownClass(cls): cls.temp.cleanup()

    def copy_slice(self,destination):
        for row in list(fixed.PINS.values())+[dict(path='manifest.json')]:
            source=REPO/fixed.ARCHIVE/row['path'];target=destination/fixed.ARCHIVE/row['path']
            wide(target).parent.mkdir(parents=True,exist_ok=True);wide(target).write_bytes(wide(source).read_bytes())

    def test_all_fixed_original_blobs_are_complete_and_raw(self):
        self.assertEqual(5,len(self.original))
        for path,row in fixed.PINS.items():
            b=self.original[path].encode();self.assertEqual(row['bytes'],len(b));self.assertEqual(row['sha256Bytes'],digest(b))
            self.assertEqual(row['gitBlob'],hashlib.sha1(b'blob '+str(len(b)).encode()+b'\0'+b).hexdigest())

    def test_mutated_raw_or_crlf_is_rejected_without_normalization(self):
        with tempfile.TemporaryDirectory() as t:
            repo=Path(t);self.copy_slice(repo);target=repo/fixed.ARCHIVE/fixed.SEARCH;old=wide(target).read_bytes()
            for altered in [old+b' ',old.replace(b'\n',b'\r\n')]:
                wide(target).write_bytes(altered)
                with self.assertRaisesRegex(ValueError,'raw byte/blob'): fixed.fixed_sources(repo)
            wide(target).write_bytes(old)

    def test_manifest_unknown_identity_or_hash_is_rejected(self):
        with tempfile.TemporaryDirectory() as t:
            repo=Path(t);self.copy_slice(repo);p=repo/fixed.ARCHIVE/'manifest.json'
            wide(p).write_bytes(wide(p).read_bytes()+b' ')
            with self.assertRaisesRegex(ValueError,'manifest changed'): fixed.fixed_sources(repo)

    def assert_original_inverse(self,path,generated,selection):
        p=selection['originalToPlatform'];before=self.original[path];after=forward(before,p)
        self.assertEqual(before,inverse(after,p));self.assertEqual(digest(before.encode()),p['beforeSha256LF'])
        self.assertEqual(digest(after.encode()),p['afterSha256LF']);self.assertTrue(p['exactCompleteInverse'])
        self.assertTrue(generated.endswith(after))

    def test_full_original_vm_sheet_and_grpc_generate_with_exact_inverse(self):
        out=self.outputs['bgm-detail'];data=json.loads(read(out/'bgm-source-identities.json'))
        selection=next(x['v029CommentSearch'] for x in data['sourceSelection'] if 'v029CommentSearch' in x)
        self.assert_original_inverse(fixed.VM,read(out/'com/android/purebilibili/feature/video/viewmodel/VideoCommentViewModel.kt'),selection)
        out=self.outputs['video-comment-ui'];self.assert_original_inverse(fixed.SEARCH,
            read(out/'com/android/purebilibili/feature/video/ui/components/CommentSearchSheet.kt'),
            json.loads(read(out/'v029-comment-search-ui-source.json')))
        out=self.outputs['dynamic-reply-protocol'];self.assert_original_inverse(fixed.GRPC,
            read(out/'generated/com/android/purebilibili/data/repository/DesktopDynamicCommentGrpc.kt'),
            json.loads(read(out/'v029-comment-grpc-source.json')))

    def test_model_full_inverse_preserves_canonical_pin_and_field31_source(self):
        out=self.outputs['api'];body=read(out/'com/android/purebilibili/data/model/response/ResponseModels.kt')
        p=json.loads(read(out/'v029-comment-model-source-proof.json'))
        before=read(REPO/fixed.MODEL)
        self.assertEqual(fixed.CANONICAL_MODEL_SHA,digest(before.encode()))
        self.assertEqual(before,fixed.reverse(body,p['countedAdaptations']))
        marker='@Serializable\ndata class ReplyControl('
        self.assertEqual(self.original[fixed.MODEL][self.original[fixed.MODEL].index(marker):],body[body.index(marker):])
        with tempfile.TemporaryDirectory() as t:
            repo=Path(t);self.copy_slice(repo);path=repo/fixed.MODEL;wide(path).parent.mkdir(parents=True,exist_ok=True)
            wide(path).write_text(before+' ',encoding='utf8')
            # Canonical path catalog lives alongside the helper, so this call
            # rejects content before creating any alternate model output.
            with self.assertRaises(ValueError): fixed.emit_models(repo,repo/'out')

    def test_charged_original_resolver_has_one_actual_output_and_inverse(self):
        out=self.outputs['dynamic-reply'];p=json.loads(read(out/'v029-charged-reply-source.json'))
        from v029_comment_time import apply
        before,_=apply(REPO,fixed.REPLY,read(REPO/fixed.REPLY));after=before
        for e in p['countedAdaptations']: after=fixed.replace(after,e['before'],e['after'],e['label'],[],e['count'])
        self.assertEqual(before,fixed.reverse(after,p['countedAdaptations']))
        self.assertEqual(1,sum(read(x).count('fun resolveChargedReplyLabel(') for x in out.rglob('*.kt')))
        resolver=self.original[fixed.REPLY].split('internal fun resolveChargedReplyLabel(',1)[1].split('\n}',1)[0]
        self.assertIn(resolver,read(out/'com/android/purebilibili/feature/video/ui/components/DesktopOriginalReplyComponents.kt'))

    def test_model_has_only_one_generated_fqcn_and_is_excluded_from_direct_sync(self):
        model=[p for out in self.outputs.values() for p in out.rglob('*.kt') if 'data class ReplyControl(' in read(p)]
        self.assertEqual(1,len(model))
        manifest=json.loads(read(REPO/'desktop/upstream-sources.json'))
        row=next(r for r in manifest['sources'] if r['path']==fixed.MODEL)
        self.assertEqual('policy-extract',row['mode']);self.assertEqual(fixed.CANONICAL_MODEL_SHA,row['sha256'])
        self.assertNotIn(fixed.MODEL,[r['path'] for r in manifest['sources'] if r['mode']=='direct'])

if __name__=='__main__': unittest.main()
