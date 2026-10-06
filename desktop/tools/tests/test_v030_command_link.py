"""Raw pins, counted whole-body inverse and actual sole CLI output; no Kotlin/native execution."""
import hashlib, importlib.util, json, os
from pathlib import Path
import shutil, subprocess, sys, tempfile, unittest

REPO = Path(__file__).resolve().parents[3]
TOOLS = REPO / 'desktop/tools'
sys.path.insert(0, str(TOOLS))
import v030_command_link as link
import v029_command_vote as vote

class V030CommandLinkExtractionTest(unittest.TestCase):
    def test_fixed_raw_sources_and_full_overlay_inverse(self):
        for path in link.PINS:
            self.assertTrue(link.read(REPO, path))
        original = link.read(REPO, link.OVERLAY)
        text = vote.adapt_overlay(original, edits := [])
        self.assertEqual(original, vote.inverse(text, edits))
        self.assertEqual(1, text.count('Modifier.clickable(role = Role.Button) { onLinkClick(item) }'))
        self.assertEqual(1, text.count('.desktopCommandHitRegion(item.id)'))

    def test_original_click_dialog_confirm_complete_bodies_round_trip(self):
        selections = link.link_bodies(link.read(REPO, link.SECTION))
        self.assertEqual(['click', 'dialog', 'confirm'], [s[0] for s in selections])
        for name, original, adapted, edits in selections:
            self.assertEqual(original, vote.inverse(adapted, edits), name)
        dialog = selections[1][2]
        for text in ('跳转关联视频', '是否跳转到「', 'AppText("跳转")', 'AppText("取消")'):
            self.assertIn(text, dialog)
        self.assertIn('commandState.dismiss(item.id)', selections[2][2])
        self.assertIn('onNavigate(dialogTargetBvid)', selections[2][2])

    def test_raw_crlf_tampering_and_manifest_identity_are_rejected(self):
        with tempfile.TemporaryDirectory(prefix='link-raw-') as temp:
            repo = Path(temp)
            target = repo / 'desktop/upstream-slices/v030-command-link'
            shutil.copytree(link.wide(REPO / 'desktop/upstream-slices/v030-command-link'), link.wide(target))
            raw_file = link.wide(target / link.OVERLAY)
            raw = raw_file.read_bytes()
            raw_file.write_bytes(raw.replace(b'\n', b'\r\n'))
            with self.assertRaises(AssertionError): link.read(repo, link.OVERLAY)
            raw_file.write_bytes(raw)
            manifest_path = target / 'manifest.json'
            manifest = json.loads(manifest_path.read_text(encoding='utf-8'))
            manifest['upstreamCommit'] = '0' * 40
            manifest_path.write_text(json.dumps(manifest), encoding='utf-8')
            with self.assertRaises(AssertionError): link.read(repo, link.OVERLAY)

    def test_actual_cli_has_single_overlay_and_original_link_callbacks_with_exact_proofs(self):
        with tempfile.TemporaryDirectory(prefix='link-cli-') as temp:
            output = Path(temp)
            subprocess.run([sys.executable, '-B', str(TOOLS / 'extract-stable-video-votes.py'), str(REPO), str(output)],
                           check=True, capture_output=True, env=dict(os.environ, PYTHONIOENCODING='cp1252:strict', PYTHONUTF8='0'))
            overlays = list(output.rglob('DesktopOriginalCommandDanmakuOverlay.kt'))
            self.assertEqual(1, len(overlays))
            overlay = overlays[0].read_text(encoding='utf-8')
            self.assertIn(link.COMMIT, overlay)
            self.assertIn('onLinkClick = onLinkClick', overlay)
            proof = json.loads((output / 'v029-command-vote-proof.json').read_text(encoding='utf-8'))
            self.assertEqual(vote.COMMIT, proof['upstreamCommit'])
            self.assertEqual(link.COMMIT, proof['overlayUpstreamCommit'])
            self.assertTrue(proof['overlayFullInverse'])
            self.assertTrue(proof['submissionFullInverse']); self.assertTrue(proof['gradeFullInverse'])
            links = json.loads((output / 'v030-command-link-proof.json').read_text(encoding='utf-8'))
            generated = (output / 'com/android/purebilibili/feature/video/ui/overlay/DesktopOriginalCommandLinkConfirmation.kt').read_text(encoding='utf-8')
            for selection in links['selections']:
                self.assertTrue(selection['fullInverse'])
                self.assertIn(selection['adaptedBody'], generated)
                self.assertEqual(selection['rawBody'], vote.inverse(selection['adaptedBody'], selection['edits']))
            # The preexisting grade/state owners are unchanged fixed v029 bodies, not repinned.
            for path in (vote.POLICY, vote.STATE):
                actual = (output / path.split('/java/', 1)[1]).read_text(encoding='utf-8')
                self.assertEqual(vote.read(REPO, path), '\n'.join(actual.split('\n')[2:]))

if __name__ == '__main__': unittest.main()
