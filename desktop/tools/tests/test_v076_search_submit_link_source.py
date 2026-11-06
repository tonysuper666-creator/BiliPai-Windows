from pathlib import Path
import hashlib
import importlib.util
import shutil
import sys
import tempfile
import unittest

REPO=Path(__file__).resolve().parents[3]
sys.path.insert(0,str(REPO/'desktop/tools'))
import v076_search_submit_link as source

NAV='com/android/purebilibili/navigation/'


def producer():
    spec=importlib.util.spec_from_file_location('search_submit_existing_linked_dock_producer',REPO/'desktop/tools/extract-upstream-linked-dock.py')
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    return module


class V076SearchSubmitLinkSourceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary=tempfile.TemporaryDirectory(prefix='bp-search-submit-output-')
        root=Path(cls.temporary.name);cls.out=root/'main';cls.tests=root/'tests'
        producer().generate(REPO,cls.out,cls.tests)

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def temporary_repo(self):
        temporary=tempfile.TemporaryDirectory(prefix='bp-search-submit-input-')
        self.addCleanup(temporary.cleanup)
        root=Path(temporary.name)
        shutil.copytree(REPO/source.ARCHIVE,root/source.ARCHIVE)
        return root

    def test_policy_and_test_are_the_complete_v076_originals(self):
        files=source.verified_raw(REPO)
        self.assertEqual('8a44e0e4092f5a7a705217d154c1fc1c856471e9c745ee7e86118bd2bc64c4fe',source.MANIFEST_SHA256)
        self.assertEqual('39ad3afe2628322aa9d3bf16ecb4a6b0f7c753fa',source.INTRODUCED_BY)
        for pin in source.PINS:
            raw=files[pin['archiveFile']]
            self.assertEqual(pin['gitBlob'],hashlib.sha1(b'blob '+str(len(raw)).encode()+b'\0'+raw).hexdigest())
        self.assertEqual(7,sum(raw.count(b'    @Test\n')for raw in source.original_tests(REPO).values()))

    def test_mutated_original_policy_cannot_be_emitted(self):
        root=self.temporary_repo();p=root/source.ARCHIVE/'SearchSubmitPolicy.kt';p.write_bytes(p.read_bytes()+b'\n')
        with self.assertRaisesRegex(ValueError,'original bytes changed'):source.original_policy(root)

    def test_mutated_original_test_cannot_be_emitted(self):
        root=self.temporary_repo();p=root/source.ARCHIVE/'SearchSubmitPolicyTest.kt';p.write_bytes(p.read_bytes()+b'\n')
        with self.assertRaisesRegex(ValueError,'original bytes changed'):source.original_tests(root)

    def test_mutated_manifest_and_unlisted_leaf_are_rejected(self):
        root=self.temporary_repo();p=root/source.ARCHIVE/'manifest.json';p.write_bytes(p.read_bytes()+b' ')
        with self.assertRaisesRegex(ValueError,'manifest pin'):source.original_policy(root)
        root=self.temporary_repo();(root/source.ARCHIVE/'extra.kt').write_bytes(b'')
        with self.assertRaisesRegex(ValueError,'archive leaves'):source.original_policy(root)

    def test_existing_producer_emits_the_original_policy_and_its_original_tests(self):
        self.assertEqual(source.original_policy(REPO).encode('utf-8'),(self.out/NAV/'SearchSubmitPolicy.kt').read_bytes())
        for path,raw in source.original_tests(REPO).items():self.assertEqual(raw,(self.tests/path).read_bytes())

    def test_desktop_submit_consumer_handles_every_original_branch(self):
        text=(self.out/NAV/'DesktopOriginalBottomSearchSubmit.kt').read_text(encoding='utf-8')
        self.assertIn('    onOpenLink: (String) -> Unit,\n',text)
        self.assertEqual(1,text.count('is SearchSubmitAction.OpenLink -> onOpenLink(action.url)'))
        self.assertEqual(1,text.count('is SearchSubmitAction.OpenSearch -> onOpenSearch(action.keyword)'))
        self.assertEqual(1,text.count('is SearchSubmitAction.OpenNativeTarget -> onOpenNativeTarget(action.target)'))
        self.assertEqual(1,text.count('SearchSubmitAction.Ignore -> Unit'))
        # Links never leak into the same-list channel; only plain searches do.
        self.assertIn('if (scopedChannel != null && resolveSearchSubmitAction(keyword) is SearchSubmitAction.OpenSearch) {',text)
        self.assertNotIn('openBilibiliLinkInNavigation3',text)

    def test_root_routes_submitted_links_to_its_existing_content_link_opener(self):
        ui=REPO/'desktop/src/main/kotlin/com/bilipai/desktop/ui'
        mount=(ui/'DesktopReadyOriginalRootMount.kt').read_text(encoding='utf-8')
        self.assertIn('{ target -> dispatchDesktopReadyNativeTarget(root, routes, target) },\n            services.openExternalLink,\n',mount)
        frosted=(ui/'DesktopOriginalFrostedAudioRoot.kt').read_text(encoding='utf-8')
        self.assertIn('    val openLink: (String) -> Unit,\n',frosted)
        self.assertIn('actions.openSearch, actions.openNativeTarget, actions.openLink, owner.isOwned)',frosted)
        dock=(ui/'DesktopOriginalLinkedDockRoot.kt').read_text(encoding='utf-8')
        self.assertIn('                onOpenLink = onOpenLink,\n',dock)
        shell=(REPO/'desktop/src/main/kotlin/com/bilipai/desktop/DesktopShell.kt').read_text(encoding='utf-8')
        self.assertIn('{error=it}, ::openOriginalContentLink,',shell)


if __name__=='__main__':
    unittest.main()
