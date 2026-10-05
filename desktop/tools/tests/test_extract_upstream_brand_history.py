"""Actual sole History/List producer, whole fixed raw and reversible adaptation contracts.

Private preparation may use readonly BILIPAI_BRAND_HISTORY_TOOLS/BASE_REPO.
Normal repo unittest discovery requires neither override. No Kotlin/network/UI.
"""
from pathlib import Path
import hashlib, importlib.util, json, os, shutil, subprocess, sys, tempfile, unittest

TOOLS=Path(os.environ.get('BILIPAI_BRAND_HISTORY_TOOLS', str(Path(__file__).resolve().parents[1])))
REPO=Path(os.environ.get('BILIPAI_BRAND_HISTORY_BASE_REPO', str(TOOLS.parents[1])))
def wide(p):
    s=str(p.absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s) if os.name=='nt' else p
def sha(raw):return hashlib.sha256(raw.encode() if isinstance(raw,str) else raw).hexdigest()
def load(name,file):
    sys.path.insert(0,str(TOOLS));spec=importlib.util.spec_from_file_location(name,wide(TOOLS/file))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module

class BrandHistoryProducerTest(unittest.TestCase):
    def helper(self):return load('brand_history_contract','v029_brand_history.py')
    def inverse(self,body,edits):
        for e in reversed(edits):
            at=e['index'];self.assertEqual(body[at:at+len(e['after'])],e['after'])
            body=body[:at]+e['before']+body[at+len(e['after']):]
        return body
    def generated(self):
        temp=tempfile.TemporaryDirectory(prefix='brand-history-');self.addCleanup(temp.cleanup)
        output=Path(temp.name)/'out'
        result=subprocess.run([sys.executable,'-X','utf8','-B',str(TOOLS/'extract-upstream-favorites.py'),'--repo',str(REPO),'--output',str(output)],
            cwd=REPO,env=dict(os.environ,PYTHONDONTWRITEBYTECODE='1'),stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
        self.assertEqual(result.returncode,0,result.stdout.decode('utf-8',errors='replace'))
        return output,json.loads((output.parent/'source-inventory.json').read_bytes())
    def test_actual_sole_producer_full_inverse_to_all_three_fixed_original_files(self):
        output,rows=self.generated();helper=self.helper()
        targets={'feature/list/ListViewModel':'feature/list/DesktopOriginalListViewModels','feature/list/CommonListScreen':'feature/list/CommonListScreen'}
        for rel,target in targets.items():
            with self.subTest(source=rel):
                row=next(r for r in rows if r['path']=='desktop/upstream-slices/v029-brand-history/'+helper.PREFIX+rel+'.kt')
                raw=helper.load_raw(rel);selected,audit=helper.selected_source(rel)
                self.assertEqual(self.inverse(selected,audit['historySourceAdaptation']['edits']),raw)
                body=(output/'com/android/purebilibili'/f'{target}.kt').read_text(encoding='utf-8')
                ledger=row['historyGeneratedAdaptation']
                self.assertEqual(self.inverse(body,ledger['edits']),raw)
                self.assertEqual(sha(body),ledger['generatedSha256LfUtf8'])
                self.assertEqual(sha(raw),row['rawSha256Bytes'])
                self.assertEqual(row['upstreamCommit'],helper.COMMIT)
                self.assertEqual(len(row['outputs']),1)
        reference=next(r for r in rows if r['path'].endswith('/ListLoadError.kt'))
        self.assertEqual(reference['mode'],'reference');self.assertEqual(reference['outputs'],[])
        self.assertEqual(len(list(output.rglob('ListLoadError.kt'))),0)
        sole=load('history_existing_message_error_source','v029_chat_sources.py')
        self.assertEqual(sole.read(REPO,'ListLoadError.kt'),helper.load_raw('feature/common/ListLoadError'))
        message=(REPO/'desktop/tools/extract-upstream-message-pages.py').read_text(encoding='utf-8')
        self.assertEqual(message.count("emit('com/android/purebilibili/feature/common/ListLoadError.kt'"),1)
    def test_existing_transport_owner_and_all_other_sources_remain_canonical(self):
        output,rows=self.generated();history=[r for r in rows if 'historySourceAdaptation' in r]
        self.assertEqual(len(history),3)
        catalog=load('history_canonical_contract','v025_source_paths.py')._catalog()
        # The raw new three-file slice does not change the existing canonical catalog.
        for row in rows:
            if row in history or "recapGeneratedAdaptation" in row:continue
            self.assertEqual(row['upstreamCommit'],catalog['upstreamCommit'])
            self.assertEqual(row['sha256LfUtf8'],catalog['paths'][row['path']]['sha256'])
        repository=(output/'com/android/purebilibili/data/repository/DesktopOriginalHistoryRepository.kt').read_text(encoding='utf-8')
        self.assertIn('private val api = environment.api',repository)
        self.assertIn('if (e is kotlinx.coroutines.CancellationException) throw e',repository)
        self.assertNotIn('fixture.invalid',repository)
    def test_restored_recap_preserves_original_cache_header_retry_and_only_layout_omission(self):
        helper=self.helper()
        model,model_row=helper.selected_source('feature/list/ListViewModel')
        screen,screen_row=helper.selected_source('feature/list/CommonListScreen')
        self.assertEqual(model_row['historySourceAdaptation']['count'],0)
        self.assertEqual(screen_row['historySourceAdaptation']['count'],2)
        edits=screen_row['historySourceAdaptation']['edits']
        self.assertEqual(len([e for e in edits if e['after']=='']),1)
        self.assertEqual(len([e for e in edits if 'requireDesktopPersonalRecapBinding().enabled' in e['after']]),1)
        # Full original error/pagination callbacks remain byte-identical in frequency.
        for rel,body in [('feature/list/ListViewModel',model),('feature/list/CommonListScreen',screen)]:
            raw=helper.load_raw(rel)
            for name in ['retryHistory()', 'loadMore(retry = true)', 'loadMoreError']:
                self.assertEqual(body.count(name),raw.count(name))
        self.assertIn('recapHeader',screen)
        self.assertIn('recapSnapshots',model)
        self.assertIn('headerContent: (@Composable () -> Unit)? = null',screen)
    def mutated(self):
        helper=self.helper();temp=tempfile.TemporaryDirectory(prefix='brand-history-raw-');self.addCleanup(temp.cleanup)
        target=Path(temp.name)/'raw';shutil.copytree(wide(helper.ROOT),target);helper.ROOT=target;return helper,target
    def test_every_raw_file_crlf_tamper_rejects_before_adaptation(self):
        for rel in self.helper().SUPPORTED:
            with self.subTest(source=rel):
                helper,root=self.mutated();file=root/(helper.PREFIX+rel+'.kt')
                file.write_bytes(file.read_bytes().replace(b'\n',b'\r\n'))
                with self.assertRaisesRegex(ValueError,'history raw pin mismatch'):helper.selected_source(rel)
    def test_manifest_cannot_repin_changed_original(self):
        helper,root=self.mutated();file=root/next(iter(helper.PINS));file.write_bytes(file.read_bytes()+b'\n')
        manifest=json.loads((root/'manifest.json').read_bytes());manifest['files'][0]['sha256Bytes']=sha(file.read_bytes())
        (root/'manifest.json').write_text(json.dumps(manifest),encoding='utf-8')
        with self.assertRaisesRegex(ValueError,'history manifest pin mismatch'):helper.load_raw('feature/list/ListViewModel')
    def test_unknown_raw_and_unreviewed_source_selection_reject(self):
        helper,root=self.mutated();(root/'unexpected.kt').write_bytes(b'unknown')
        with self.assertRaisesRegex(ValueError,'history raw file-set mismatch'):helper.load_raw('feature/list/ListViewModel')
        with self.assertRaisesRegex(ValueError,'unsupported history source path'):self.helper().selected_source('feature/list/OtherViewModel')

if __name__=='__main__':unittest.main()
