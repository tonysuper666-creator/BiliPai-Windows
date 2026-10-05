"""Fresh sole storage producer/full-original inverse, without Kotlin/cache/GUI.

Private prep may set BILIPAI_CACHE_CLEAR_BASE_REPO to the readonly canonical repo
and BILIPAI_CACHE_CLEAR_TOOLS to its prepared tools. All output remains private.
"""
import hashlib, importlib.util, json, os
from pathlib import Path
import subprocess, sys, tempfile, unittest
from unittest.mock import patch

REPO=Path(__file__).resolve().parents[3]
BASE=Path(os.environ.get('BILIPAI_CACHE_CLEAR_BASE_REPO',str(REPO)))
TOOLS=Path(os.environ.get('BILIPAI_CACHE_CLEAR_TOOLS',str(REPO/'desktop/tools')))
sys.path.insert(0,str(TOOLS))
import v029_brand_cache_clear as brand

def cli(script,output,tests=None):
    env=os.environ.copy();env['PYTHONDONTWRITEBYTECODE']='1'
    env['PYTHONPATH']=str(TOOLS)+os.pathsep+str(BASE/'desktop/tools')
    args=[sys.executable,'-X','utf8','-B',str(script),'--repo',str(BASE),'--output',str(brand.wide(output))]
    if tests is not None: args+=['--tests-output',str(brand.wide(tests))]
    result=subprocess.run(args,env=env,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
    if result.returncode: raise AssertionError(result.stdout.decode('utf-8'))

class BrandCacheClearProducerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        parent=os.environ.get('BILIPAI_CACHE_CLEAR_TEST_OUTPUT')
        cls.temp=tempfile.TemporaryDirectory(prefix='cache-clear-contract-',dir=str(brand.wide(Path(parent))) if parent else None)
        cls.root=brand.wide(Path(cls.temp.name));cls.before=cls.root/'before';cls.after=cls.root/'after';cls.tests=cls.root/'tests'
        cls.manifest,cls.raw=brand.checked_sources()
        cli(BASE/'desktop/tools/extract-upstream-storage-settings.py',cls.before)
        cli(TOOLS/'extract-upstream-storage-settings.py',cls.after,cls.tests)

    @classmethod
    def tearDownClass(cls): cls.temp.cleanup()

    def test_actual_sole_cli_emits_complete_fixed_ui_with_four_counted_ports_and_full_inverse(self):
        relative='com/android/purebilibili/feature/settings/CacheClearAnimation.kt'
        text=(self.after/relative).read_text(encoding='utf-8')
        proofs=json.loads((self.after/'cache-clear-source-inventory.json').read_text(encoding='utf-8'))
        proof=proofs[0]
        self.assertEqual(4,len(proof['countedAdaptations']))
        self.assertEqual(self.raw[brand.ANIMATION],brand.reverse(text,proof['countedAdaptations']))
        self.assertTrue(proof['exactFullSourceInverse'])
        self.assertEqual(proof['generatedSha256LF'],brand.digest(text.encode()))
        for required in ['kotlinx.coroutines.delay(2000L)','progress.isComplete','MaidAnimation.CLEANING','MaidAnimation.CLEAN_COMPLETE',
            'CacheClearConfirmDialog','hazeState: dev.chrisbanes.haze.HazeState? = null','AppCircularProgressIndicator','AdaptiveLoadingIndicator']:
            self.assertIn(required,text)
        self.assertNotIn('import android.os.Build',text)
        self.assertNotIn('androidx.activity.compose.BackHandler',text)
        self.assertNotIn('owner.clear',text)
        omissions=[row for row in proof['countedAdaptations'] if row['label']=='unsupportedAndroidWindowBlur']
        self.assertEqual(1,len(omissions))
        self.assertIn('ModalWindowBlurBehindEffect(enabled = true)',omissions[0]['before'])
        self.assertIn('original dialog scrim/AppPopupSurface fallback',omissions[0]['after'])
        self.assertNotIn('ModalWindowBlurBehindEffect',text)
        self.assertIn('import androidx.compose.ui.window.Dialog',text)
        self.assertIn('AppPopupSurface(',text)
        self.assertIn('type = AppPopupSurfaceType.DIALOG',text)
        self.assertIn('containerColor = MaterialTheme.colorScheme.surface',text)

    def test_other_storage_families_remain_identical_and_no_duplicate_policy_is_emitted(self):
        # Only these four original non-cache products are an unchanged-family
        # comparison. The applied producer already emits cache UI proof; its
        # --tests-output proof is deliberately additional, not a stale baseline.
        unchanged={
            'com/android/purebilibili/feature/settings/DesktopOriginalDataStorageSection.kt',
            'com/android/purebilibili/core/store/DesktopOriginalStorageSettings.kt',
            'com/android/purebilibili/core/util/DesktopOriginalStorageCachePolicy.kt',
            'com/bilipai/desktop/settings/DesktopStorageSettingsVectors.kt',
        }
        known=unchanged|{'storage-source-inventory.json','cache-clear-source-inventory.json',
            'com/android/purebilibili/feature/settings/CacheClearAnimation.kt'}
        for output in (self.before,self.after):
            self.assertEqual(known,{p.relative_to(output).as_posix() for p in output.rglob('*') if p.is_file()})
        for path in unchanged:
            self.assertEqual((self.before/path).read_bytes(),(self.after/path).read_bytes(),path)
        before_proofs=json.loads((self.before/'cache-clear-source-inventory.json').read_text(encoding='utf-8'))
        after_proofs=json.loads((self.after/'cache-clear-source-inventory.json').read_text(encoding='utf-8'))
        self.assertEqual([brand.ANIMATION],[row['path'] for row in before_proofs])
        self.assertEqual([brand.ANIMATION,brand.TEST],[row['path'] for row in after_proofs])
        before_ui=(self.before/'com/android/purebilibili/feature/settings/CacheClearAnimation.kt').read_text(encoding='utf-8')
        self.assertEqual(self.raw[brand.ANIMATION],brand.reverse(before_ui,before_proofs[0]['countedAdaptations']))
        self.assertEqual(brand.digest(before_ui.encode()),before_proofs[0]['generatedSha256LF'])
        self.assertEqual(brand.PINS[brand.ANIMATION][0],before_proofs[0]['originalSha256Bytes'])
        self.assertFalse((self.after/'com/android/purebilibili/feature/settings/CacheClearUiPolicy.kt').exists())
        inventory=json.loads((self.after/'storage-source-inventory.json').read_text(encoding='utf-8'))
        legacy=next(row for row in inventory if row['path']==brand.ANIMATION and 'upstreamCommit' not in row)
        self.assertEqual('reference-only',legacy['mode'])
        actual=next(row for row in inventory if row['path']==brand.ANIMATION and row.get('upstreamCommit')==brand.COMMIT)
        self.assertEqual('complete-original-adapted',actual['mode'])
        self.assertEqual(brand.PINS[brand.POLICY][0],brand.digest(self.raw[brand.POLICY].encode()))

    def test_original_policy_cases_keep_full_inverse_with_explicit_android_test_scope(self):
        generated=(self.tests/'com/android/purebilibili/feature/settings/DesktopV029CacheClearUiPolicyTest.kt').read_text(encoding='utf-8')
        expected,proof=brand.test_source()
        self.assertEqual(expected,generated)
        self.assertEqual(self.raw[brand.TEST],brand.reverse(generated,proof['countedAdaptations']))
        self.assertEqual(13,self.raw[brand.TEST].count('@Test'))
        self.assertEqual(11,generated.count('@Test'))
        omitted=[row for row in proof['countedAdaptations'] if row['label']=='android-settings-screen-only-tests-not-compiled']
        self.assertEqual(1,len(omitted))
        self.assertEqual(2,omitted[0]['before'].count('@Test'))
        self.assertIn('markAnimationCompleteOnlyWhenClearSucceeded',omitted[0]['before'])
        self.assertIn('resolveFailureMessageWithFallback',omitted[0]['before'])
        self.assertNotIn('shouldMarkCacheClearAnimationComplete',generated)
        self.assertNotIn('resolveCacheClearFailureMessage',generated)
        self.assertIn('substringAfter("fun CacheClearAnimationDialog(")',generated)
        self.assertNotIn('decorFitsSystemWindows = false',self.raw[brand.ANIMATION],
            'Pinned upstream test references an option removed by that same original UI commit')

    def test_source_tamper_is_rejected_before_any_generated_body(self):
        target=self.root/'tampered';target.mkdir(exist_ok=True)
        for path in [*brand.PINS,'manifest.json']:
            dest=target/path;dest.parent.mkdir(parents=True,exist_ok=True)
            dest.write_bytes((brand.wide(brand.ROOT)/path).read_bytes())
        file=target/brand.ANIMATION;file.write_bytes(file.read_bytes()+b'\n// drift\n')
        with patch.object(brand,'ROOT',target):
            with self.assertRaisesRegex(ValueError,'source pin mismatch'):brand.animation_source()

    def test_fixed_raw_git_blob_rejects_crlf_rewriting_in_no_text_slice(self):
        target=self.root/'crlf';target.mkdir(exist_ok=True)
        for path in [*brand.PINS,'manifest.json']:
            dest=target/path;dest.parent.mkdir(parents=True,exist_ok=True)
            raw=(brand.wide(brand.ROOT)/path).read_bytes()
            dest.write_bytes(raw if path=='manifest.json' else raw.replace(b'\n',b'\r\n'))
        with patch.object(brand,'ROOT',target):
            with self.assertRaisesRegex(ValueError,'source pin mismatch'):brand.animation_source()

    def test_real_caller_keeps_clear_progress_failure_algorithms_and_host_is_presentation_only(self):
        caller=(REPO/'desktop/src/main/kotlin/com/bilipai/desktop/settings/DesktopStorageSettings.kt').read_text(encoding='utf-8')
        host=(REPO/'desktop/src/main/kotlin/com/bilipai/desktop/settings/DesktopWindowsCacheClearProgressHost.kt').read_text(encoding='utf-8')
        self.assertEqual(1,caller.count('owner.clear(captured)'))
        self.assertIn('progress=CacheClearProgress(0,1)',caller)
        self.assertIn('catch(failure: Throwable) {progress=null;throw failure}',caller)
        self.assertIn('progress === capturedProgress && capturedProgress.isComplete',caller)
        self.assertIn('stillOwned = owner::isActive',caller)
        self.assertIn('progress = capturedProgress,',caller)
        self.assertIn('progress.isComplete && stillOwned() && isCurrent()',host)
        self.assertIn('dismissOnEscape = progress.isComplete',host)
        self.assertIn('DesktopWindowsPlayerDialog(',host)
        self.assertIn('DesktopDetailWindow {',host)
        self.assertNotIn('owner.clear',host)
        self.assertNotIn('DesktopStorageSettingsOwner(',host)

if __name__=='__main__': unittest.main(verbosity=2)
