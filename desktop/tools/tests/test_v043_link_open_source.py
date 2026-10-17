from pathlib import Path
import hashlib
import importlib.util
import shutil
import sys
import tempfile
import unittest

REPO=Path(__file__).resolve().parents[3]
sys.path.insert(0,str(REPO/'desktop/tools'))
import v043_link_open as source


class V043LinkOpenSourceTest(unittest.TestCase):
    def temporary_repo(self):
        temporary=tempfile.TemporaryDirectory(prefix='bp-link-open-input-')
        self.addCleanup(temporary.cleanup)
        root=Path(temporary.name)
        shutil.copytree(REPO/source.ARCHIVE,root/source.ARCHIVE)
        return root

    def test_gate_and_policy_are_the_two_complete_original_files(self):
        files=source.original_sources(REPO)
        self.assertEqual(2,len(files))
        self.assertEqual('7b9f020fa7887da655c734727a39c79fae85a9214e33382ae422f645fd3bab7f',source.MANIFEST_SHA256)
        for path,raw in files.items():self.assertEqual((REPO/source.ARCHIVE/Path(path).name).read_bytes(),raw)

    def test_all_eight_original_test_bodies_and_imports_are_unchanged(self):
        files=source.original_tests(REPO)
        self.assertEqual(8,sum(raw.count(b'    @Test\n')for raw in files.values()))
        for path,raw in files.items():self.assertEqual((REPO/source.ARCHIVE/Path(path).name).read_bytes(),raw)

    def test_mutated_original_gate_cannot_be_emitted(self):
        root=self.temporary_repo();p=root/source.ARCHIVE/'LinkOpenGate.kt';p.write_bytes(p.read_bytes()+b'\n')
        with self.assertRaisesRegex(ValueError,'original bytes changed'):source.original_sources(root)

    def test_mutated_original_policy_cannot_be_emitted(self):
        root=self.temporary_repo();p=root/source.ARCHIVE/'BilibiliLinkNavigationPolicy.kt';p.write_bytes(p.read_bytes()+b'\n')
        with self.assertRaisesRegex(ValueError,'original bytes changed'):source.original_sources(root)

    def test_mutated_original_test_cannot_be_emitted(self):
        root=self.temporary_repo();p=root/source.ARCHIVE/'LinkOpenGateTest.kt';p.write_bytes(p.read_bytes()+b'\n')
        with self.assertRaisesRegex(ValueError,'original bytes changed'):source.original_tests(root)

    def test_mutated_manifest_is_rejected(self):
        root=self.temporary_repo();p=root/source.ARCHIVE/'manifest.json';p.write_bytes(p.read_bytes()+b' ')
        with self.assertRaisesRegex(ValueError,'manifest pin'):source.original_sources(root)

    def test_unlisted_archive_leaf_is_rejected(self):
        root=self.temporary_repo();(root/source.ARCHIVE/'extra.kt').write_bytes(b'')
        with self.assertRaisesRegex(ValueError,'archive leaves'):source.original_sources(root)

    def test_all_four_original_git_blob_ids_match_exact_bytes(self):
        files=source.verified_raw(REPO)
        for pin in source.PINS:
            raw=files[pin['archiveFile']]
            self.assertEqual(pin['gitBlob'],hashlib.sha1(b'blob '+str(len(raw)).encode()+b'\0'+raw).hexdigest())

    def test_existing_sole_producer_emits_the_real_shared_sources_and_original_tests(self):
        spec=importlib.util.spec_from_file_location('link_open_existing_space_producer',REPO/'desktop/tools/extract-space-platform.py')
        producer=importlib.util.module_from_spec(spec);spec.loader.exec_module(producer)
        with tempfile.TemporaryDirectory(prefix='bp-link-open-output-')as temporary:
            out=Path(temporary)/'main';tests=Path(temporary)/'tests';producer.generate(REPO,out,True,tests)
            for path,raw in source.original_sources(REPO).items():self.assertEqual(raw,(out/path).read_bytes())
            for path,raw in source.original_tests(REPO).items():self.assertEqual(raw,(tests/path).read_bytes())

    def test_gradle_declares_the_shared_archive_and_module_on_the_existing_producer(self):
        text=(REPO/'desktop/build.gradle.kts').read_text(encoding='utf-8')
        block=text.split('val extractUpstreamSpace by tasks.registering',1)[1].split('val extractUpstreamSpaceImagePreviews',1)[0]
        self.assertIn('"tools/v043_link_open.py"',block)
        self.assertIn('inputs.dir("upstream-slices/v043-link-open")',block)

    def test_actual_root_service_uses_the_content_link_binding_without_changing_search_submissions(self):
        shell=(REPO/'desktop/src/main/kotlin/com/bilipai/desktop/DesktopShell.kt').read_text(encoding='utf-8')
        self.assertIn('{error=it}, ::openOriginalContentLink,',shell)
        self.assertIn('originalContentLinks.getAndSet(it)?.close()',shell)
        source_text=(REPO/'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalContentLinkBinding.kt').read_text(encoding='utf-8')
        self.assertIn('resolveBilibiliLinkNavigationAction(raw)',source_text)
        self.assertIn('BilibiliNavigationTargetParser::resolve',source_text)
        self.assertIn('routes.sourceNavigationRevision == source.revision',source_text)
        self.assertIn('dispatchDesktopReadyNativeTarget(',source_text)
        self.assertNotIn('SearchScreen',source_text)

    def test_explicit_browser_button_keeps_external_opening_and_the_original_gate(self):
        shell=(REPO/'desktop/src/main/kotlin/com/bilipai/desktop/DesktopShell.kt').read_text(encoding='utf-8')
        block=shell.split('entryKey is BiliPaiNavKey.Web -> {',1)[1].split('section == DesktopSection.BGM',1)[0]
        self.assertIn('com.android.purebilibili.feature.web.LinkOpenGate()',block)
        self.assertIn('messageRoutes.isCurrentContent(entryKey)',block)
        self.assertIn('openDynamicWeb(entryKey.url, entryKey.title)',block)
        self.assertIn('finally { browserOpenGate.release() }',block)


if __name__=='__main__':unittest.main()
