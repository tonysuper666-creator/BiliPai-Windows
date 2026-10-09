# Whole fixed raw/store/UI/settings + actual sole producer contracts. No Kotlin/IO account/UI.
from pathlib import Path
import hashlib,importlib.util,json,os,shutil,subprocess,sys,tempfile,unittest
TOOLS=Path(os.environ.get('BILIPAI_HISTORY_RECAP_TOOLS',str(Path(__file__).resolve().parents[1])))
REPO=Path(os.environ.get('BILIPAI_HISTORY_RECAP_BASE_REPO',str(TOOLS.parents[1])))
sys.path.insert(0,str(TOOLS))
def wide(p):
 s=str(p.absolute());return Path(s if s.startswith('\\\\?\\') else '\\\\?\\'+s) if os.name=='nt' else p
def sha(raw):return hashlib.sha256(raw.encode() if isinstance(raw,str) else raw).hexdigest()
def load(name,file):
 spec=importlib.util.spec_from_file_location(name,wide(TOOLS/file));m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m

class HistoryRecapProducerTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):
  cls.temp=tempfile.TemporaryDirectory(prefix='history-recap-')
  cls.output=Path(cls.temp.name);cls.log=[]
  for task in ['favorites','plugins']:
   result=subprocess.run([sys.executable,'-X','utf8','-B',str(TOOLS/f'extract-upstream-{task}.py'),'--repo',str(REPO),'--output',str(cls.output/task/'out')],cwd=REPO,env=dict(os.environ,PYTHONDONTWRITEBYTECODE='1'),stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
   cls.log.append(result.stdout.decode('utf8',errors='replace'))
   if result.returncode:raise AssertionError(cls.log[-1])
  cls.rows=json.loads((cls.output/'favorites/source-inventory.json').read_bytes())
 @classmethod
 def tearDownClass(cls):cls.temp.cleanup()
 def helper(self):return load('history_recap_raw_contract','v029_history_recap.py')
 def inverse(self,body,edits):
  for e in reversed(edits):
   i=e['index'];self.assertEqual(body[i:i+len(e['after'])],e['after']);body=body[:i]+e['before']+body[i+len(e['after']):]
  return body
 def test_actual_four_full_outputs_inverse_to_fixed_original_including_verbatim_charts(self):
  rows=[r for r in self.rows if 'recapGeneratedAdaptation' in r]
  self.assertEqual(len(rows),4)
  for r in rows:
   with self.subTest(source=r['path']):
    self.assertEqual(r['upstreamCommit'],self.helper().COMMIT);self.assertEqual(len(r['outputs']),1)
    body=(self.output/'favorites/out'/r['outputs'][0]['path']).read_text(encoding='utf8')
    a=r['recapGeneratedAdaptation'];raw=self.inverse(body,a['edits'])
    self.assertEqual(sha(body),a['generatedSha256LfUtf8']);self.assertEqual(sha(raw),r['rawSha256Bytes'])
    self.assertEqual(raw,self.helper().load_raw(r['path'].split(self.helper().PREFIX,1)[1][:-3]))
    if r['path'].endswith('/PersonalRecapCharts.kt'):self.assertEqual(a['count'],0)
 def test_actual_feed_store_is_one_complete_original_with_exact_two_platform_aliases(self):
  helper=self.helper();raw,expected,row=helper.feed_store_source()
  files=list((self.output/'plugins/out').rglob('FeedReadingStore.kt'));self.assertEqual(len(files),1)
  generated=files[0].read_text(encoding='utf8');self.assertEqual(generated.split('\n',2)[2],expected.strip()+'\n')
  # Adjacent import aliases form one inverse hunk; verify both exact replacements.
  self.assertEqual(expected,raw.replace('import android.content.Context','import com.bilipai.desktop.plugins.DesktopPluginContext as Context').replace('import android.util.AtomicFile','import com.bilipai.desktop.plugins.DesktopPluginAtomicFile as AtomicFile'))
  self.assertEqual(self.inverse(expected,row['recapGeneratedAdaptation']['edits']),raw)
  self.assertEqual(generated.splitlines()[1],'// LF-normalized SHA-256: '+sha(raw))
  extractor=load('history_recap_plugin_sole','extract-upstream-plugins.py');rows=extractor.inventory(REPO)
  self.assertEqual(len(rows),len({r['path'] for r in rows}))
  old=next(r for r in rows if r['path']==extractor.BASE+'core/plugin/feed/FeedReadingStore.kt')
  self.assertEqual(old['mode'],'reference-only')
  selected=next(r for r in rows if r['path']==row['path']);self.assertEqual(selected['mode'],'extracted');self.assertEqual(selected['sha256'],sha(raw))
  self.assertNotEqual(old['sha256'],selected['sha256'])
 def test_actual_history_header_cache_retry_and_settings_consume_required_existing_binding(self):
  root=self.output/'favorites/out/com/android/purebilibili'
  screen=(root/'feature/list/CommonListScreen.kt').read_text(encoding='utf8')
  model=(root/'feature/list/DesktopOriginalListViewModels.kt').read_text(encoding='utf8')
  card=(root/'feature/list/HistoryRecapCard.kt').read_text(encoding='utf8')
  settings=(root/'core/store/DesktopPersonalRecapSettings.kt').read_text(encoding='utf8')
  self.assertIn('recapHeader',screen);self.assertIn('snapshotCache = historyViewModel?.recapSnapshots',screen);self.assertIn('recapSnapshots',model)
  self.assertIn('requireDesktopPersonalRecapBinding().enabled',screen)
  self.assertIn('snapshotCache?.set(window, refreshed)',card);self.assertIn('request.publish {',card)
  self.assertIn('request.cleanup { loading = false }',card);self.assertIn('throw cancelled',card)
  self.assertIn('subscription_recap_enabled',settings);self.assertIn('?: false',settings)
  self.assertIn('context.settingsDataStore.edit',settings)
  self.assertNotIn('PluginManager.getContext',card)
  history=load('recap_original_history_contract','v029_brand_history.py')
  for rel,body in [('feature/list/ListViewModel',model),('feature/list/CommonListScreen',screen)]:
   raw=history.load_raw(rel)
   row=next(r for r in self.rows if r['path'].endswith('/'+rel+'.kt'))
   audit=row['historyGeneratedAdaptation']
   self.assertEqual(sha(body),audit['generatedSha256LfUtf8'])
   restored=self.inverse(body,audit['edits'])
   self.assertEqual(raw,restored)
   for method in ['retryHistory()', 'loadMore(retry = true)', 'loadMoreError']:
    self.assertEqual(restored.count(method),raw.count(method))
 def mutated(self):
  helper=self.helper();temp=tempfile.TemporaryDirectory(prefix='history-recap-raw-');self.addCleanup(temp.cleanup)
  target=Path(temp.name)/'raw';shutil.copytree(wide(helper.ROOT),target);helper.ROOT=target;return helper,target
 def test_every_physical_raw_lf_pin_rejects_crlf_and_byte_tamper(self):
  for rel in self.helper().PINS:
   with self.subTest(source=rel):
    helper,target=self.mutated();p=target/rel;p.write_bytes(p.read_bytes().replace(b'\n',b'\r\n'))
    with self.assertRaisesRegex(ValueError,'recap raw pin mismatch'):helper.feed_store_source()
  helper,target=self.mutated();p=target/next(iter(helper.PINS));p.write_bytes(p.read_bytes()+b' ')
  with self.assertRaisesRegex(ValueError,'recap raw pin mismatch'):list(helper.favorite_sources())
 def test_manifest_unknown_file_and_unsupported_selection_fail_closed(self):
  helper,target=self.mutated();manifest=json.loads((target/'manifest.json').read_bytes());manifest['upstreamCommit']='0'*40
  (target/'manifest.json').write_text(json.dumps(manifest),encoding='utf8')
  with self.assertRaisesRegex(ValueError,'recap manifest pin mismatch'):helper.feed_store_source()
  helper,target=self.mutated();(target/'extra.kt').write_bytes(b'extra')
  with self.assertRaisesRegex(ValueError,'recap raw file-set mismatch'):list(helper.favorite_sources())
  with self.assertRaisesRegex(ValueError,'unsupported recap raw source'):self.helper().load_raw('feature/list/Invented')
 def test_explicit_context_and_settings_contracts_do_not_use_parallel_authorities(self):
  # The caller code is validated against the real produced bodies, not invented count mirrors.
  desktop=TOOLS.parent
  main=desktop/'src/main/kotlin/com/bilipai/desktop'
  caller=(main/'plugins/DesktopSubscriptionRepository.kt').read_text(encoding='utf8')
  self.assertIn('if (read) FeedReadingStore.recordRead(context, feedItemKey(item))',caller)
  self.assertIn('readTimestamps = _state.value.reading.readTimestamps',caller);self.assertIn('readProgress = _state.value.reading.readProgress',caller)
  binding=(main/'ui/DesktopPersonalRecapBinding.kt').read_text(encoding='utf8')
  self.assertIn('environment.history',binding);self.assertIn('context.store === preferences.store',binding)
  self.assertIn('current.get() !== this',binding);self.assertIn('current.compareAndSet(this, null)',binding)
  host=(main/'ui/DesktopOriginalPersonalListHost.kt').read_text(encoding='utf8')
  self.assertIn('LocalDesktopPersonalRecapBindings provides recap',host)
  self.assertNotIn('DesktopPluginStore(',binding);self.assertNotIn('DesktopOriginalHistoryRepository(',binding)
  privacy=(main/'settings/DesktopPrivacySettings.kt').read_text(encoding='utf8')
  self.assertIn('settings.pluginContext.store === context.store',privacy)
  self.assertIn('DesktopPersonalRecapSettings.setSubscriptionRecapEnabled(settings, enabled)',privacy)

if __name__=='__main__':unittest.main()
