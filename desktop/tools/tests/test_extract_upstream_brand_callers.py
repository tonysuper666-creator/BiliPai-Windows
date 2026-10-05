"""Actual sole producers and fixed whole-source/inverse brand caller contracts.

Pure source checks only. No Kotlin, Compose, accounts, native or networking.
Private preparation can supply readonly BILIPAI_BRAND_CALLER_BASE_REPO and
BILIPAI_BRAND_CALLER_TOOLS; ordinary repo tests require neither variable.
"""
from pathlib import Path
import hashlib, importlib.util, json, os, shutil, subprocess, sys, tempfile, unittest

TOOLS=Path(os.environ.get('BILIPAI_BRAND_CALLER_TOOLS', str(Path(__file__).resolve().parents[1])))
REPO=Path(os.environ.get('BILIPAI_BRAND_CALLER_BASE_REPO',str(TOOLS.parents[1])))
def wide(p):
    value=str(p.absolute())
    return Path(value if value.startswith('\\\\?\\') else '\\\\?\\'+value) if os.name=='nt' else p
def sha(s):return hashlib.sha256(s.encode() if isinstance(s,str) else s).hexdigest()
def load(name,file):
    sys.path.insert(0,str(TOOLS))
    spec=importlib.util.spec_from_file_location(name,wide(TOOLS/file));module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module

class BrandCallerProducerTest(unittest.TestCase):
    def helper(self):return load('brand_caller_contract_helper','v029_brand_callers.py')
    def generated(self,script,kind):
        temporary=tempfile.TemporaryDirectory(prefix='brand-callers-');self.addCleanup(temporary.cleanup)
        output=Path(temporary.name)/'out'
        command=[sys.executable,'-X','utf8','-B',str(TOOLS/script),'--repo',str(REPO),'--output',str(output)]
        if kind=='search':command+=['--audit',str(Path(temporary.name)/'inventory.json')]
        result=subprocess.run(command,cwd=REPO,env=dict(os.environ,PYTHONDONTWRITEBYTECODE='1'),stdout=subprocess.PIPE,stderr=subprocess.STDOUT,check=False)
        self.assertEqual(result.returncode,0,result.stdout.decode('utf-8',errors='replace'))
        inventory=Path(temporary.name)/('inventory.json' if kind=='search' else 'source-inventory.json')
        return output,json.loads(inventory.read_text(encoding='utf-8'))
    def inverse(self,body,audit):
        inverse=body
        for edit in reversed(audit['edits']):
            at=edit['index'];self.assertEqual(inverse[at:at+len(edit['after'])],edit['after'])
            inverse=inverse[:at]+edit['before']+inverse[at+len(edit['after']):]
        self.assertEqual(sha(inverse),audit['beforeSha256LfUtf8'])
        self.assertEqual(sha(body),audit['afterSha256LfUtf8'])
        return inverse
    def test_actual_search_sole_producer_chained_inverse_restores_entire_canonical_original(self):
        output,rows=self.generated('extract-upstream-search-pages.py','search')
        row=next(r for r in rows if r['source'].endswith('/SearchScreen.kt'))
        body=(output/'com/android/purebilibili/feature/search/SearchScreen.kt').read_text(encoding='utf-8')
        audit=row['windowsBrandConsumerAdaptation'];self.assertEqual(audit['countedAdaptations'],8)
        self.assertTrue(row['inverseOriginalBodyExact']);self.assertTrue(audit['fullAdaptedBodyInverseExact'])
        inverse=self.inverse(body,audit)
        producer=load('search_brand_source_contract','extract-upstream-search-pages.py')
        spec=next(s for s in producer.SPEC if s['target']=='feature/search/SearchScreen.kt')
        self.assertEqual(sha(inverse),spec['outputSha256LF'])
        for operation in reversed(spec['operations']):
            at=operation['offset'];self.assertEqual(inverse[at:at+len(operation['after'])],operation['after'])
            inverse=inverse[:at]+operation['before']+inverse[at+len(operation['after']):]
        raw=self.helper()._sources()['v025/app/src/main/java/com/android/purebilibili/feature/search/SearchScreen.kt']
        self.assertEqual(inverse,raw)
        self.assertEqual(sha(inverse),spec['inputSha256LF'])
        self.assertEqual(row['generatedSha256LF'],sha(body))
    def test_actual_favorites_sole_producer_preserves_complete_current_body_except_three_original_empty_calls(self):
        output,rows=self.generated('extract-upstream-favorites.py','list')
        row=next(r for r in rows if r['path'].endswith('/CommonListScreen.kt'))
        body=(output/'com/android/purebilibili/feature/list/CommonListScreen.kt').read_text(encoding='utf-8')
        if row.get('historyGeneratedAdaptation'):
            helper=load('history_brand_caller_source_contract','v029_brand_history.py')
            audit=row['historyGeneratedAdaptation'];inverse=body
            for edit in reversed(audit['edits']):
                at=edit['index'];self.assertEqual(inverse[at:at+len(edit['after'])],edit['after'])
                inverse=inverse[:at]+edit['before']+inverse[at+len(edit['after']):]
            raw=helper.load_raw('feature/list/CommonListScreen')
            self.assertEqual(inverse,raw)
            self.assertEqual(sha(body),audit['generatedSha256LfUtf8'])
            self.assertEqual(row['upstreamCommit'],helper.COMMIT)
            for unchanged in ['historyViewModel.retryHistory()', 'historyViewModel.loadMore(retry = true)', 'onRetryLoadMore:', 'loadMoreError:']:
                self.assertEqual(body.count(unchanged),raw.count(unchanged))
            self.assertEqual(len(list((output/'com').rglob('CommonListScreen.kt'))),1)
            self.assertEqual(row['historySourceAdaptation']['count'],2)
            edits=row['historySourceAdaptation']['edits']
            self.assertEqual(len([e for e in edits if e['after']=='']),1)
            self.assertEqual(len([e for e in edits if 'requireDesktopPersonalRecapBinding().enabled' in e['after']]),1)
            self.assertIn('snapshotCache = historyViewModel?.recapSnapshots',body)
            self.assertIn('headerContent = recapHeader',body)
            return
        audit=row['windowsBrandConsumerAdaptation'];self.assertEqual(audit['countedAdaptations'],3)
        inverse=self.inverse(body,audit)
        source_before=self.helper()._sources()['v025/app/src/main/java/com/android/purebilibili/feature/list/CommonListScreen.kt']
        for edit in audit['edits']:self.assertEqual(source_before.count(edit['before']),1)
        for unchanged in ['LaunchedEffect(shouldLoadMore.value)', 'if (shouldLoadMore.value) onLoadMore()', 'headerContent:','onRetryLoadMore:','loadMoreError:']:
            self.assertEqual(body.count(unchanged),inverse.count(unchanged))
        self.assertEqual(len(audit['pendingListBranches']),1)
        self.assertEqual(row['upstreamCommit'],self.helper().BASE025)
        self.assertEqual(len(list((output/'com').rglob('CommonListScreen.kt'))),1)
    def test_selected_query_actions_and_footer_defaults_are_exact_upstream_bodies(self):
        helper=self.helper();raw=helper._sources();pairs=helper._pairs('search',raw)
        before=raw['v025/app/src/main/java/com/android/purebilibili/feature/search/SearchScreen.kt']
        after,_=helper.adapt_brand_callers(before,'search')
        current=raw['v029/app/src/main/java/com/android/purebilibili/feature/search/SearchScreen.kt']
        anchor='@Composable\nprivate fun SearchNativeMessageState('
        self.assertEqual(helper._span(after,anchor,'function'),helper._span(current,anchor,'function'))
        for action in ['viewModel.loadMoreResults()', 'viewModel.search(pageResultState.query)']:
            self.assertEqual(after.count(action),before.count(action))
        anchor='@Composable\nprivate fun SearchLoadMoreIndicator('
        self.assertEqual(helper._span(after,anchor,'function'),helper._span(before,anchor,'function'))
        for pair in pairs:
            if pair['label'].startswith('result-empty-'):
                self.assertIn('isVisible = searchPagerState.currentPage == page',pair['after'])
        list_after,_=helper.adapt_brand_callers(raw['v025/app/src/main/java/com/android/purebilibili/feature/list/CommonListScreen.kt'],'list')
        self.assertNotIn('isVisible =', '\n'.join(e['after'] for e in helper._pairs('list',raw)))
        self.assertIn('else com.android.purebilibili.core.ui.MaidAnimation.EMPTY',list_after)
    def mutated(self):
        helper=self.helper();temp=tempfile.TemporaryDirectory(prefix='brand-callers-raw-');self.addCleanup(temp.cleanup)
        source=wide(helper.ROOT);target=Path(temp.name)/'raw';shutil.copytree(source,target);helper.ROOT=target;return helper,target
    def test_each_fixed_raw_file_crlf_tampering_is_rejected(self):
        for path in self.helper().RAW_PINS:
            with self.subTest(path=path):
                helper,root=self.mutated();p=root/path;p.write_bytes(p.read_bytes().replace(b'\n',b'\r\n'))
                with self.assertRaisesRegex(ValueError,'raw pin mismatch'):helper._sources()
    def test_changed_manifest_cannot_repin_mutated_raw(self):
        helper,root=self.mutated();path=next(iter(helper.RAW_PINS));p=root/path;p.write_bytes(p.read_bytes()+b'\n')
        manifest=json.loads((root/'manifest.json').read_bytes());manifest['files'][0]['sha256Bytes']=sha(p.read_bytes())
        (root/'manifest.json').write_text(json.dumps(manifest),encoding='utf-8')
        with self.assertRaisesRegex(ValueError,'manifest pin mismatch'):helper._sources()
    def test_unknown_raw_and_changed_existing_selection_reject(self):
        helper,root=self.mutated();(root/'extra.kt').write_text('unexpected',encoding='utf-8')
        with self.assertRaisesRegex(ValueError,'raw file set'):helper._sources()
        helper=self.helper();source=helper._sources()['v025/app/src/main/java/com/android/purebilibili/feature/search/SearchScreen.kt']
        source=source.replace('title = "搜索失败",','title = "changed",',1)
        with self.assertRaisesRegex(ValueError,'not unique'):helper.adapt_brand_callers(source,'search')

if __name__=='__main__':unittest.main()
