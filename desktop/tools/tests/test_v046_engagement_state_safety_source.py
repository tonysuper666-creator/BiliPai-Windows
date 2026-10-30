"""Source checks for roadmap slice vid-engagement-state-safety. No Gradle, no JVM, no network.

Covers the carried unfollow confirmation and its seven generated call sites, the desktop-owned follow buttons, the
like write guard (a Windows-owned deviation from the v0.4.6 pin) and the phone-width engagement flags.
"""
from pathlib import Path
import hashlib
import importlib.util
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

REPO = Path(__file__).resolve().parents[3]
TOOLS = REPO / 'desktop/tools'
sys.path.insert(0, str(TOOLS))
import v046_follow_confirmation as follow
import v046_like_write_guard as guard
import v037_player_known_presentation as known

UI = REPO / 'desktop/src/main/kotlin/com/bilipai/desktop/ui'
PHONE = 'com/android/purebilibili/feature/video/screen/VideoDetailPhoneContent.kt'
VM = guard.OUTPUT_RELATIVE
PHONE_BEFORE = ('                                    isFollowing = engagementState.isFollowing,\n'
                '                                    isFavorited = engagementState.isFavorited,\n'
                '                                    isLiked = engagementState.isLiked,\n'
                '                                    isDisliked = engagementState.isDisliked,')
PHONE_AFTER = ('                                    isFollowing = engagementState.isFollowing,\n'
               '                                    followStatusKnown = engagementState.followStatusKnown,\n'
               '                                    isFavorited = engagementState.isFavorited,\n'
               '                                    isLiked = engagementState.isLiked,\n'
               '                                    likeStatusKnown = engagementState.likeStatusKnown,\n'
               '                                    isDisliked = engagementState.isDisliked,')


def load(name, label):
    spec = importlib.util.spec_from_file_location(label, TOOLS / name)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def sha(value):
    return hashlib.sha256(value.encode('utf8') if isinstance(value, str) else value).hexdigest()


def text_of(path):
    return Path(path).read_text(encoding='utf-8')


def commit_present(commit):
    return subprocess.run(['git', '-C', str(REPO), 'cat-file', '-e', commit + '^{commit}'], capture_output=True).returncode == 0


def git_text(*args):
    return subprocess.check_output(['git', '-C', str(REPO), *args], text=True, encoding='utf-8')


def body_of(relative, text):
    """The metadata producer prepends a two-line GENERATED header after the proof was taken."""
    if relative == follow.METADATA:
        first, second, rest = text.split('\n', 2)
        assert first.startswith('// GENERATED from ') and second.startswith('// LF-normalized SHA-256: ')
        return rest
    return text


def without_cover_edit(relative, text):
    """lst-episode-selector-collection appends the v0.4.6 cover argument of the player overlay's part selector call as the
    LAST layer of that output, after the follow confirmation, so the receipt of this module describes the text without it.
    Peel exactly that one edit (and prove it is there once) before the receipt is checked; every other output is unchanged.
    plr-control-center-motion applies its thirteen end drawer hunks after that edit (the very last layer of the overlay),
    so they are peeled first, each proven present exactly once."""
    if relative != follow.OVERLAY:
        return text
    import v046_control_center_motion as control_center
    for hunk in reversed(control_center.OVERLAY_HUNKS):
        assert text.count(hunk['after']) == 1
        text = text.replace(hunk['after'], hunk['before'], 1)
    import v046_episode_selector as episode
    assert text.count(episode.OVERLAY_PAGES_AFTER) == 1 and text.count(episode.OVERLAY_PAGES_BEFORE) == 0
    return text.replace(episode.OVERLAY_PAGES_AFTER, episode.OVERLAY_PAGES_BEFORE)


def peel(text, proof):
    restored = text
    for edit in reversed(proof['edits']):
        at = edit['offset']
        assert restored[at:at + len(edit['after'])] == edit['after']
        restored = restored[:at] + edit['before'] + restored[at + len(edit['after']):]
    return restored


def tree(root):
    return {p.relative_to(root).as_posix(): p.read_bytes() for p in Path(root).rglob('*') if p.is_file()}


# A skipped check is not a passed check. A clone without the upstream history fails these tests instead of skipping
# them; BP_SYMBOL_GATE_ALLOW_SHALLOW=1 skips them on purpose, as in test_symbol_closure.require_history.
ALLOW_SHALLOW = os.environ.get('BP_SYMBOL_GATE_ALLOW_SHALLOW') == '1'


def require_history(case, *revisions):
    missing = [revision for revision in revisions if not commit_present(revision)]
    if not missing:
        return
    if ALLOW_SHALLOW:
        case.skipTest('BP_SYMBOL_GATE_ALLOW_SHALLOW=1 and these upstream commits are not in this clone: ' + ', '.join(missing))
    raise AssertionError(
        'upstream commits missing from this clone: ' + ', '.join(missing) + '. A missing commit is a failure, not a skip: '
        'fetch them (the full history of the integration branch, or git fetch origin <commit>), or set '
        'BP_SYMBOL_GATE_ALLOW_SHALLOW=1 to skip these checks on purpose.')


class CarriedConfirmationTests(unittest.TestCase):
    def test_pins_match_the_manifest_the_bytes_and_the_registration(self):
        manifest = json.loads((REPO / follow.ARCHIVE / 'manifest.json').read_bytes())
        self.assertEqual(follow.MANIFEST_SHA256, sha((REPO / follow.ARCHIVE / 'manifest.json').read_bytes()))
        self.assertEqual(follow.COMMIT, manifest['commit'])
        body = follow.fixed(REPO)
        self.assertEqual((follow.LEAF_BYTES, follow.LEAF_SHA256), (len(body), sha(body)))
        self.assertEqual(follow.LEAF_BLOB, hashlib.sha1(b'blob %d\0' % len(body) + body).hexdigest())
        self.assertNotIn(b'\r', body)
        text = body.decode('utf-8')
        self.assertEqual(1, text.count('internal fun rememberFollowActionWithConfirmation('))
        self.assertTrue(text.startswith('package com.android.purebilibili.core.ui.components\n'))
        catalog = json.loads((TOOLS / 'compatibility-source-inputs.json').read_bytes())
        rows = [row for row in catalog['inputs'] if row['localPath'].startswith(follow.ARCHIVE + '/')]
        self.assertEqual(1, len(rows))
        self.assertEqual((follow.LEAF_PATH, follow.COMMIT, follow.LEAF_BLOB, follow.LEAF_SHA256, False),
                         (rows[0]['path'], rows[0]['upstreamCommit'], rows[0]['blob'], rows[0]['sha256'], rows[0]['referenceOnly']))
        self.assertEqual(['v046-follow-confirmation'], rows[0]['features'])
        self.assertIn(follow.ARCHIVE, catalog['buildInputRoots'])
        archive = next(a for a in catalog['archives'] if a['directory'] == follow.ARCHIVE)
        self.assertEqual({'FollowActionConfirmation.kt', 'LICENSE', 'manifest.json'},
                         {Path(f['path']).name for f in archive['files']})
        for item in archive['files']:
            self.assertEqual(sha((REPO / item['path']).read_bytes()), item['sha256'])
        sync = load('sync-upstream.py', 'ess_sync')
        self.assertEqual(sha((TOOLS / 'compatibility-source-inputs.json').read_bytes().replace(b'\r\n', b'\n')),
                         sync.COMPATIBILITY_CATALOG_SHA256)
        self.assertEqual(1, len([r for r in sync.adapted_compatibility_inputs(REPO) if r['localPath'].startswith(follow.ARCHIVE + '/')]))

    def test_tampered_extra_or_missing_leaves_are_rejected(self):
        for name in [follow.LEAF, 'manifest.json', 'LICENSE', 'unexpected.kt']:
            with self.subTest(name=name), tempfile.TemporaryDirectory(prefix='bp-ess-') as temp:
                root = Path(temp) / follow.ARCHIVE
                shutil.copytree(REPO / follow.ARCHIVE, root)
                target = root / name
                target.write_bytes((target.read_bytes() if target.exists() else b'') + b'\n')
                with self.assertRaises(ValueError):
                    follow.fixed(Path(temp))
        with tempfile.TemporaryDirectory(prefix='bp-ess-') as temp:
            root = Path(temp) / follow.ARCHIVE
            shutil.copytree(REPO / follow.ARCHIVE, root)
            (root / 'LICENSE').unlink()
            with self.assertRaises(ValueError):
                follow.fixed(Path(temp))
            shutil.rmtree(root)
            with self.assertRaises(ValueError):
                follow.fixed(Path(temp))

    def test_a_missing_upstream_commit_fails_instead_of_skipping_unless_explicitly_allowed(self):
        missing = '0' * 40
        here = sys.modules[__name__]
        self.assertFalse(commit_present(missing))
        with patch.object(here, 'ALLOW_SHALLOW', False):
            with self.assertRaisesRegex(AssertionError, 'missing from this clone: ' + missing):
                require_history(self, missing)
        with patch.object(here, 'ALLOW_SHALLOW', True):
            with self.assertRaises(unittest.SkipTest):
                require_history(self, missing)

    def test_the_carried_file_is_the_upstream_blob_unchanged_since_its_commit(self):
        require_history(self, follow.COMMIT, follow.INTRODUCED_BY)
        for commit in (follow.COMMIT, follow.INTRODUCED_BY):
            self.assertEqual(follow.LEAF_BLOB, git_text('rev-parse', commit + ':' + follow.LEAF_PATH).strip())
        shown = subprocess.check_output(['git', '-C', str(REPO), 'cat-file', 'blob', follow.COMMIT + ':' + follow.LEAF_PATH])
        self.assertEqual(shown, follow.fixed(REPO))

    def test_every_pinned_call_site_matches_the_upstream_arguments_and_blob(self):
        require_history(self, follow.COMMIT, follow.INTRODUCED_BY)
        for relative, sites in follow.SITES.items():
            for site in sites:
                path, blob = follow.UPSTREAM[site['upstream']]
                self.assertEqual(blob, git_text('rev-parse', follow.COMMIT + ':' + path).strip())
                upstream = git_text('show', follow.COMMIT + ':' + path)
                found = re.findall(r'rememberFollowActionWithConfirmation\(\s*targetKey = (.+?),\s*userName = (.+?),'
                                   r'\s*isFollowing = (.+?),\s*onFollowClick', upstream)
                self.assertIn(tuple(site['args']), found, site['label'])
                pattern = ('targetKey = %s,\n' % site['args'][0], 'userName = %s,\n' % site['args'][1],
                           'isFollowing = %s,\n' % site['args'][2])
                for part in pattern:
                    self.assertIn(part, site['after'], site['label'])
        listing = git_text('grep', '-c', 'rememberFollowActionWithConfirmation(', follow.COMMIT, '--', 'app/src/main')
        calls = {Path(line.split(':')[1]).name: int(line.split(':')[2]) for line in listing.splitlines()
                 if not line.split(':')[1].endswith('FollowActionConfirmation.kt')}
        self.assertEqual(8, sum(calls.values()))
        windows = {name: sum(1 for sites in follow.SITES.values() for site in sites if site['upstream'] == name)
                   for name in follow.UPSTREAM}
        self.assertEqual(4, sum(windows.values()))
        # Four of the eight upstream sites are not Windows hunks: the uncompiled VideoPlayerComponents site, the video
        # detail owner row (upstream text since the v046-video-info-section re-pin, vid-info-section-repin) and the two
        # portrait overlay buttons (upstream text since the whole v0.4.6 overlay, plr-portrait-story-pager-repin).
        self.assertEqual({'VideoPlayerComponents.kt': 1, 'VideoInfoSection.kt': 1, 'PortraitFullscreenOverlay.kt': 2},
                         {name: count - windows.get(name, 0)
                          for name, count in calls.items() if count != windows.get(name, 0)},
                         'only the uncompiled site and the upstream-text owner row and portrait buttons have no hunk')


class GeneratedOutputTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='bp-ess-')
        cls.root = Path(cls.temp.name)
        cls.pager = load('extract-upstream-video-fullscreen-pager.py', 'ess_pager')
        cls.rows = cls.pager.generate(REPO, cls.root / 'pager')
        load('extract-upstream-video-detail-full-units.py', 'ess_units').generate(REPO, cls.root / 'units')
        load('extract-stable-video-metadata.py', 'ess_meta').generate(REPO, cls.root / 'meta')
        load('extract-upstream-video-player-full-controls.py', 'ess_controls').generate(
            REPO, cls.root / 'controls', False, cls.root / 'controls-tests')
        load('extract-upstream-video-detail-holder.py', 'ess_holder').generate(REPO, cls.root / 'holder')
        cls.roots = {follow.UP_PREVIEW: 'pager', follow.METADATA: 'meta', follow.OVERLAY: 'controls'}

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def test_each_generated_button_keeps_every_original_line_and_gains_exactly_the_pinned_call(self):
        self.assertEqual({follow.SPACE}, set(follow.SITES) - set(self.roots), 'space pages need the whole build graph')
        for relative, sites in follow.SITES.items():
            if relative not in self.roots:
                continue
            with self.subTest(output=relative):
                base = self.root / self.roots[relative]
                generated = without_cover_edit(relative, body_of(relative, text_of(base / relative)))
                proof = json.loads((base / follow.PROOF_DIRECTORY / (Path(relative).name + '.json')).read_bytes())
                self.assertEqual(len(sites), generated.count(follow.FUNCTION + '('))
                self.assertEqual(1, generated.count(follow.IMPORT_LINE))
                self.assertEqual(sha(generated), proof['outputSHA256'])
                self.assertTrue(proof['fullInputInverseRecovered'])
                self.assertEqual(len(sites) + 1, len(proof['edits']))
                self.assertEqual([1] * (len(sites) + 1), [edit['count'] for edit in proof['edits']])
                original = peel(generated, proof)
                self.assertEqual(sha(original), proof['inputSHA256'])
                self.assertNotIn(follow.FUNCTION, original)
                for site in sites:
                    self.assertIn(site['after'], generated)
                    self.assertEqual(1, original.count(site['before']))
                    self.assertNotIn(site['before'], generated)
                self.assertEqual(follow.COMMIT, proof['upstreamCommit'])
                self.assertFalse(proof['mainCompiled'])

    def test_the_whole_upstream_file_is_emitted_by_the_pager_only_with_the_one_alias_adaptation(self):
        emitted = self.root / 'pager' / follow.OUTPUT_RELATIVE
        data = emitted.read_bytes()
        self.assertNotIn(b'\r', data)
        self.assertEqual((follow.EMITTED_BYTES, follow.EMITTED_SHA256), (len(data), sha(data)))
        original = follow.fixed(REPO).decode('utf-8')
        text = data.decode('utf-8')
        # The alias is the only difference: the upstream import becomes an alias of the desktop-owned adapter.
        self.assertEqual(1, original.count(follow.ALERT_IMPORT))
        self.assertEqual(0, text.count(follow.ALERT_IMPORT))
        self.assertEqual(1, text.count(follow.ADAPTER_IMPORT))
        self.assertEqual('import com.bilipai.desktop.ui.DesktopWindowsFollowConfirmationDialog as AppAlertDialog\n',
                         follow.ADAPTER_IMPORT)
        self.assertEqual(original, text.replace(follow.ADAPTER_IMPORT, follow.ALERT_IMPORT, 1))
        self.assertEqual(1, text.count('AppAlertDialog('), 'the upstream call keeps its name; only the import binds it')
        self.assertEqual(1, text.count('internal fun rememberFollowActionWithConfirmation('))
        row = next(r for r in self.rows if r['path'] == follow.OUTPUT_RELATIVE)
        self.assertEqual((follow.LEAF_PATH, follow.OUTPUT_MODE, follow.EMITTED_SHA256, True),
                         (row['origin'], row['mode'], row['sha256LF'], row['generated']))
        inventory = json.loads((self.root / 'pager/source-inventory.json').read_bytes())
        self.assertEqual(1, [r['path'] for r in inventory['outputs']].count(follow.OUTPUT_RELATIVE))
        for name in ('units', 'meta', 'controls', 'holder'):
            self.assertEqual([], list((self.root / name).rglob('FollowActionConfirmation.kt')))
        proof = json.loads((self.root / 'pager' / follow.PROOF_DIRECTORY / 'FollowActionConfirmation.kt.json').read_bytes())
        self.assertFalse(proof['wholeOriginalEmittedByteForByte'])
        self.assertTrue(proof['fullInputInverseRecovered'])
        self.assertEqual((follow.LEAF_BLOB, follow.LEAF_SHA256, follow.EMITTED_SHA256, follow.ADAPTER),
                         (proof['upstreamBlob'], proof['inputSHA256'], proof['outputSHA256'], proof['adapter']))
        self.assertEqual(follow.EMITTED_SHA256, proof['emittedSHA256'])
        self.assertEqual(1, len(proof['edits']))
        self.assertEqual(original, peel(text, proof))
        self.assertEqual(sha(original), proof['inputSHA256'])
        self.assertEqual(follow.ADAPTER_PATH, proof['adapterPath'])
        # The archive itself still holds the untouched upstream bytes.
        self.assertEqual((REPO / follow.ARCHIVE / follow.LEAF).read_bytes(), follow.fixed(REPO))
        self.assertEqual(follow.LEAF_SHA256, sha((REPO / follow.ARCHIVE / follow.LEAF).read_bytes()))

    def test_the_alias_points_at_one_desktop_owned_adapter_that_presents_in_the_owned_window(self):
        adapter = text_of(REPO / follow.ADAPTER_PATH)
        self.assertEqual(follow.ADAPTER_PATH, 'desktop/src/main/kotlin/com/bilipai/desktop/ui/' + follow.ADAPTER + '.kt')
        self.assertTrue(adapter.startswith('package com.bilipai.desktop.ui\n'))
        main = '\n'.join(text_of(p) for p in (REPO / 'desktop/src/main/kotlin').rglob('*.kt'))
        self.assertEqual(1, len(re.findall(r'(?m)^internal fun ' + follow.ADAPTER + r'\(', main)))
        for name in ('DESKTOP_FOLLOW_CONFIRMATION_TITLE', 'DESKTOP_FOLLOW_CONFIRMATION_HEIGHT_DP',
                     'desktopFollowConfirmationUsesOwnedWindow'):
            self.assertEqual(1, len(re.findall(r'(?m)^internal (const val|fun) ' + name + r'\b', main)), name)
        self.assertEqual(1, adapter.count('internal const val DESKTOP_FOLLOW_CONFIRMATION_TITLE = "取消关注"'))
        self.assertEqual(1, adapter.count('internal const val DESKTOP_FOLLOW_CONFIRMATION_HEIGHT_DP = 300'))
        # Same wrapping shape as the sibling owned-window dialogs: the host window, the owned player dialog around the
        # original alert, and the plain alert when there is no showing host window.
        self.assertEqual(1, adapter.count('val mainWindow = LocalDesktopWindowsPlayerWindow.current'))
        self.assertEqual(1, adapter.count('if (desktopFollowConfirmationUsesOwnedWindow(mainWindow?.isShowing)) {'))
        self.assertEqual(1, adapter.count('DesktopWindowsPlayerDialog(DESKTOP_FOLLOW_CONFIRMATION_TITLE, onDismissRequest,'))
        self.assertEqual(1, adapter.count('preferredHeightDp = DESKTOP_FOLLOW_CONFIRMATION_HEIGHT_DP, content = original)'))
        self.assertEqual(1, adapter.count('    } else original()\n'))
        self.assertEqual(1, adapter.count('AppAlertDialog(onDismissRequest, modifier, icon, title, text, confirmButton,\n'
                                          '            dismissButton, shape, containerColor, tonalElevation, properties, contentLayout)'))
        self.assertEqual(1, adapter.count('fun desktopFollowConfirmationUsesOwnedWindow(mainWindowShowing: Boolean?): Boolean = '
                                          'mainWindowShowing == true'))
        # Every parameter of the real AppAlertDialog, in its order, so the alias is a drop-in for the upstream call.
        signature = re.search(r'fun AppAlertDialog\((.*?)\n\) =', text_of(
            REPO / 'design-system/src/main/java/com/android/purebilibili/core/ui/AppDialogComponents.kt'), re.S).group(1)
        original = [line.strip().split(':')[0] for line in signature.splitlines() if line.strip()]
        mine = re.search(r'fun ' + follow.ADAPTER + r'\((.*?)\n\) \{', adapter, re.S).group(1)
        self.assertEqual(original, [line.strip().split(':')[0] for line in mine.splitlines() if line.strip()])
        # The adapter cannot be reached by anything but the alias: no other caller, no producer wiring.
        users = [p.name for p in (REPO / 'desktop/src/main/kotlin').rglob('*.kt')
                 if follow.ADAPTER in text_of(p) and p.name != follow.ADAPTER + '.kt']
        self.assertEqual([], users)

    def test_the_alias_adaptation_and_the_adapter_fail_closed(self):
        original = follow.fixed(REPO)
        data, edit = follow.adapt_confirmation(original)
        self.assertEqual(follow.EMITTED_SHA256, sha(data))
        self.assertEqual((1, follow.ALERT_IMPORT, follow.ADAPTER_IMPORT), (edit['count'], edit['before'], edit['after']))
        text = original.decode('utf-8')
        for broken in (text.replace(follow.ALERT_IMPORT, '', 1),
                       text + follow.ALERT_IMPORT,
                       text.replace('AppAlertDialog(', 'AppAlertDialog (', 1) + '\nAppAlertDialog(',
                       text.replace('showConfirmation && isFollowing', 'showConfirmation', 1),
                       follow.ADAPTER_IMPORT + text):
            with self.assertRaises(ValueError):
                follow.adapt_confirmation(broken.encode('utf-8'))
        with self.assertRaises(ValueError):
            follow.adapt_confirmation(data)
        with tempfile.TemporaryDirectory(prefix='bp-ess-') as temp:
            with self.assertRaises(ValueError):
                follow.adapter_declared(Path(temp))
            target = Path(temp) / follow.ADAPTER_PATH
            target.parent.mkdir(parents=True)
            source = text_of(REPO / follow.ADAPTER_PATH)
            for broken in ('', source + '\n' + source[source.index('@Composable'):],
                           source.replace('internal fun ' + follow.ADAPTER + '(', 'fun ' + follow.ADAPTER + '(', 1),
                           source.replace('package com.bilipai.desktop.ui', 'package com.bilipai.desktop', 1)):
                target.write_bytes(broken.encode('utf-8'))
                with self.assertRaises(ValueError):
                    follow.adapter_declared(Path(temp))
            target.write_bytes(source.encode('utf-8'))
            follow.adapter_declared(Path(temp))
            with self.assertRaises(ValueError):
                follow.validate_outputs(REPO, REPO / follow.ARCHIVE / 'child')
            with self.assertRaises(ValueError):
                follow.apply_and_record(REPO, follow.METADATA, peel(
                    body_of(follow.METADATA, text_of(self.root / 'meta' / follow.METADATA)),
                    json.loads((self.root / 'meta' / follow.PROOF_DIRECTORY / 'DesktopOriginalVideoMetadata.kt.json').read_bytes())),
                    REPO / follow.ARCHIVE / 'child')
        self.assertEqual({follow.LEAF, 'LICENSE', 'manifest.json'}, {p.name for p in (REPO / follow.ARCHIVE).iterdir()})

    def test_without_the_layer_only_the_pager_sheet_differs_and_the_leaf_disappears(self):
        peeled = self.root / 'pager-peeled'
        with patch.object(follow, 'apply_and_record', lambda repo, relative, text, output: text), \
                patch.object(follow, 'emit_confirmation', lambda repo, output: dict(
                    path='unused', origin='unused', mode='unused', sha256LF='0', generated=False)):
            self.pager.generate(REPO, peeled)
        current, before = tree(self.root / 'pager'), tree(peeled)
        changed = {name for name in before if name in current and current[name] != before[name]}
        self.assertEqual({follow.UP_PREVIEW, 'source-inventory.json'}, changed)
        added = set(current) - set(before)
        self.assertEqual({follow.OUTPUT_RELATIVE, follow.PROOF_DIRECTORY + '/FollowActionConfirmation.kt.json',
                          follow.PROOF_DIRECTORY + '/UpPreviewSheet.kt.json'}, added)
        self.assertEqual(set(), set(before) - set(current))
        inventory = {r['path']: r for r in json.loads(current['source-inventory.json'])['outputs']}
        base = {r['path']: r for r in json.loads(before['source-inventory.json'])['outputs']}
        self.assertIn(follow.OUTPUT_RELATIVE, inventory)
        self.assertNotIn(follow.OUTPUT_RELATIVE, base)
        for path, row in base.items():
            if path in inventory:
                self.assertEqual(path == follow.UP_PREVIEW, row['sha256LF'] != inventory[path]['sha256LF'], path)

    def test_the_pager_overlap_is_rejected_and_a_second_run_is_byte_identical(self):
        before = {p.name: p.read_bytes() for p in (REPO / follow.ARCHIVE).iterdir()}
        with self.assertRaisesRegex(ValueError, 'overlaps'):
            self.pager.generate(REPO, REPO / follow.ARCHIVE / 'child')
        self.assertEqual(before, {p.name: p.read_bytes() for p in (REPO / follow.ARCHIVE).iterdir()})
        snapshot = tree(self.root / 'pager')
        self.pager.generate(REPO, self.root / 'pager')
        self.assertEqual(snapshot, tree(self.root / 'pager'))

    def test_a_moved_duplicated_applied_or_foreign_anchor_fails_before_any_output(self):
        out = self.root / 'rejected'
        for relative, sites in follow.SITES.items():
            if relative not in self.roots:
                continue
            base = self.root / self.roots[relative]
            proof = json.loads((base / follow.PROOF_DIRECTORY / (Path(relative).name + '.json')).read_bytes())
            original = peel(without_cover_edit(relative, body_of(relative, text_of(base / relative))), proof)
            for site in sites:
                with self.subTest(output=relative, site=site['label']):
                    moved = original.replace(site['before'], site['before'][:-3] + 'Z' + site['before'][-2:], 1)
                    with self.assertRaises(ValueError):
                        follow.apply_and_record(REPO, relative, moved, out)
                    with self.assertRaises(ValueError):
                        follow.apply_and_record(REPO, relative, original + site['before'], out)
            with self.subTest(output=relative, case='applied'):
                with self.assertRaises(ValueError):
                    follow.apply_and_record(REPO, relative, without_cover_edit(relative, body_of(relative, text_of(base / relative))), out)
            self.assertEqual(original, follow.apply_and_record(REPO, relative + '.other', original, out))
        self.assertFalse(out.exists())

    def test_the_video_detail_owner_row_is_upstream_text_and_has_no_windows_hunk(self):
        # vid-info-section-repin: the detail units producer emits the owner row from the whole v0.4.6 VideoInfoSection.kt,
        # which already guards its follow button. This module therefore owns no site of that file's owner row.
        info = 'com/android/purebilibili/feature/video/ui/section/DesktopOriginalVideoInfoSection.kt'
        self.assertNotIn(info, follow.SITES)
        self.assertFalse(hasattr(follow, 'INFO'))
        text = text_of(self.root / 'units' / info)
        self.assertEqual(1, text.count(follow.IMPORT_LINE))
        self.assertEqual(1, text.count(follow.FUNCTION + '('))
        self.assertIn('onClick = rememberFollowActionWithConfirmation(\n'
                      '                    targetKey = info.owner.mid,\n'
                      '                    userName = info.owner.name,\n'
                      '                    isFollowing = isFollowing,\n'
                      '                    onFollowClick = onFollowClick,\n'
                      '                ),\n', text)
        self.assertEqual([], list((self.root / 'units').rglob('DesktopOriginalVideoInfoSection.kt.json')))
        # A foreign body for that output is not silently edited either: there is nothing to edit.
        self.assertEqual(text, follow.apply_and_record(REPO, info, text, self.root / 'rejected-info'))
        self.assertFalse((self.root / 'rejected-info').exists())

    def test_the_portrait_overlay_buttons_are_upstream_text_and_have_no_windows_hunk(self):
        # plr-portrait-story-pager-repin: the pager producer emits the overlay from the whole v0.4.6
        # PortraitFullscreenOverlay.kt, which already guards both of its follow buttons (info row and co-creator sheet).
        overlay = 'com/android/purebilibili/feature/video/ui/overlay/PortraitFullscreenOverlay.kt'
        self.assertNotIn(overlay, follow.SITES)
        self.assertFalse(hasattr(follow, 'PORTRAIT'))
        text = text_of(self.root / 'pager' / overlay)
        self.assertEqual(1, text.count(follow.IMPORT_LINE))
        self.assertEqual(2, text.count(follow.FUNCTION + '('))
        self.assertIn('onClick = rememberFollowActionWithConfirmation(\n'
                      '                                targetKey = ownerMid,\n'
                      '                                userName = authorName,\n'
                      '                                isFollowing = isFollowing,\n'
                      '                                onFollowClick = onFollowClick,\n'
                      '                            ),\n', text)
        self.assertIn('targetKey = member.mid,\n', text)
        self.assertFalse((self.root / 'pager' / follow.PROOF_DIRECTORY / 'PortraitFullscreenOverlay.kt.json').exists())
        # A body for that output is not silently edited either: there is nothing to edit.
        self.assertEqual(text, follow.apply_and_record(REPO, overlay, text, self.root / 'rejected-overlay'))
        self.assertFalse((self.root / 'rejected-overlay').exists())

    def test_the_space_header_edit_applies_once_to_the_pinned_anchor_through_the_producer_edit_closure(self):
        site = follow.SITES[follow.SPACE][0]
        text = 'package x\n\nimport a.b.C\nimport a.b.D\n\nfun header() {\n' + site['before'] + '}\n'
        edits = follow.site_edits(follow.SPACE, text)
        self.assertEqual(2, len(edits))
        for edit in edits:
            self.assertEqual(1, text.count(edit['before']))
            text = text.replace(edit['before'], edit['after'], 1)
        self.assertEqual(1, text.count(follow.IMPORT_LINE))
        self.assertTrue(text.index(follow.IMPORT_LINE) < text.index('import a.b.C\n'))
        self.assertIn('isFollowing = userInfo.isFollowed && !isOwner,', text)
        self.assertEqual(1, text.count('desktopSpacePlatform.feedback('))
        with self.assertRaises(ValueError):
            follow.site_edits(follow.SPACE, 'package x\n\nimport a.b.C\n\nfun header() {}\n')
        producer = text_of(TOOLS / 'extract-upstream-space-pages.py')
        hook = ("            from v046_follow_confirmation import site_edits as follow_confirmation_edits\n"
                "            for follow_edit in follow_confirmation_edits(pkg+'SpaceScreen.kt',s):\n"
                "                s=edit(s,follow_edit['before'],follow_edit['after'],1)\n"
                "            return logs(s)\n")
        self.assertEqual(1, producer.count(hook))
        self.assertTrue(producer.index(hook) > producer.index('        def screen(s,edit,logs):'))
        self.assertTrue(producer.index(hook) < producer.index("produce(BASE+'feature/space/SpaceScreen.kt',pkg+'SpaceScreen.kt',screen)"))

    def test_the_like_request_is_the_only_edit_to_the_view_model_and_goes_through_the_guard(self):
        generated = text_of(self.root / 'units' / VM)
        proof = json.loads((self.root / 'units' / guard.PROOF_DIRECTORY / 'VideoEngagementViewModel.kt.json').read_bytes())
        self.assertTrue(proof['deviationFromPin'] and proof['fullInputInverseRecovered'])
        self.assertEqual((guard.ADDED_BY, guard.REMOVED_BY), (proof['addedUpstreamBy'], proof['removedUpstreamBy']))
        self.assertEqual(sha(generated), proof['outputSHA256'])
        restored = generated[:proof['offset']] + proof['before'] + generated[proof['offset'] + len(proof['after']):]
        self.assertEqual(sha(restored), proof['inputSHA256'])
        self.assertEqual(1, generated.count(guard.GUARD + '({ environment.assertOwned() }) {'))
        self.assertEqual(1, generated.count('actions.toggleLike('))
        self.assertEqual(0, generated.count('NonCancellable'))
        self.assertLess(generated.index('likeToggleMutex.withLock'), generated.index(guard.GUARD))
        after = generated[generated.index(guard.GUARD):]
        self.assertLess(after.index('actions.toggleLike('), after.index('.onSuccess { liked ->'))
        self.assertLess(after.index('.onSuccess { liked ->'), after.index('environment.assertOwned()', after.index('.onSuccess { liked ->')))
        with self.assertRaises(ValueError):
            guard.apply_and_record(REPO, VM, generated, self.root / 'rejected-guard')
        with self.assertRaises(ValueError):
            guard.apply_and_record(REPO, VM, restored.replace('actions.toggleLike(targetAid', 'actions.toggleLike(otherAid', 1),
                                   self.root / 'rejected-guard')
        with self.assertRaises(ValueError):
            guard.apply_and_record(REPO, VM, restored + '\nactions.toggleLike(1, false, "")', self.root / 'rejected-guard')
        self.assertEqual('same', guard.apply_and_record(REPO, VM + '.other', 'same', self.root / 'rejected-guard'))
        self.assertFalse((self.root / 'rejected-guard').exists())

    def test_the_phone_width_content_receives_both_confirmed_flags(self):
        generated = text_of(self.root / 'holder' / PHONE)
        self.assertEqual(1, generated.count(PHONE_AFTER))
        self.assertEqual(0, generated.count(PHONE_BEFORE))
        self.assertEqual(1, generated.count('VideoContentEngagementState('))
        proof = json.loads((self.root / 'holder' / 'VideoDetailPhoneContent.kt.v037-known-presentation-proof.json').read_bytes())
        self.assertEqual(PHONE, proof['path'])
        self.assertFalse(proof['defaultKnownStatusIntroduced'])
        before = generated.replace(PHONE_AFTER, PHONE_BEFORE, 1)
        out = self.root / 'known-direct'
        applied = known.apply(PHONE, before, REPO, out)
        self.assertEqual(before.replace(PHONE_BEFORE, PHONE_AFTER, 1), applied)
        with self.assertRaises(AssertionError):
            known.apply(PHONE, applied, REPO, out)
        with self.assertRaises(AssertionError):
            known.apply(PHONE, before.replace('isFollowing = engagementState.isFollowing,', 'removed = engagementState.isFollowing,', 1), REPO, out)
        self.assertEqual('untouched', known.apply('Other.kt', 'untouched', REPO, out))


class WindowsOwnedWiringTests(unittest.TestCase):
    sync = None

    @classmethod
    def setUpClass(cls):
        cls.sync = load('sync-upstream.py', 'ess_sync_tokens')

    def calls(self, text, name):
        tokens = self.sync.kotlin_tokens(text)
        spans = []
        for i, (word, first, _) in enumerate(tokens):
            if word == name and i + 1 < len(tokens) and tokens[i + 1][0] == '(':
                depth, j = 0, i + 1
                while True:
                    depth += (tokens[j][0] == '(') - (tokens[j][0] == ')')
                    if depth == 0:
                        break
                    j += 1
                spans.append(text[first:tokens[j][2]])
        return spans

    def test_every_desktop_owned_follow_button_asks_before_it_unfollows(self):
        support = text_of(UI / 'CommunityUiSupport.kt')
        self.assertEqual(1, support.count('internal class CommunityFollowTarget(val mid: Long, val name: String, val isFollowing: Boolean)'))
        self.assertEqual(1, len(re.findall(r'internal fun CommunityAction\(label: String, onLogin: \(\) -> Unit, action: suspend \(\) -> Unit,\n'
                                           r'    followTarget: CommunityFollowTarget\? = null, onSuccess: \(\) -> Unit = \{\}\)', support)))
        self.assertEqual(1, support.count('import com.android.purebilibili.core.ui.components.rememberFollowActionWithConfirmation\n'))
        self.assertEqual(1, support.count('val click = if (followTarget == null) start else rememberFollowActionWithConfirmation('))
        self.assertEqual(1, support.count('TextButton(enabled = !busy, onClick = click)'))
        guarded = {'CommunitySearchScreens.kt': ('user.mid', 'user.name', 'user.isFollowed'),
                   'PersonalScreens.kt': ('user.mid', 'user.uname', 'true'),
                   'VideoEngagementPanel.kt': ('details.authorMid', 'details.author', 'followed')}
        seen = 0
        for path in sorted((REPO / 'desktop/src/main/kotlin').rglob('*.kt')):
            for span in self.calls(text_of(path), 'CommunityAction'):
                if span.startswith('CommunityAction(label: String'):
                    continue
                label = span.split(',', 1)[0]
                if '关注' in label or 'resolveSpaceFollowActionLabel' in label:
                    seen += 1
                    self.assertIn('followTarget = CommunityFollowTarget(', span, path.name)
                    self.assertIn('CommunityFollowTarget(%s, %s, %s)' % guarded[path.name], span, path.name)
                else:
                    self.assertNotIn('followTarget', span, path.name)
        self.assertEqual(len(guarded), seen)
        for name in guarded:
            self.assertEqual(1, text_of(UI / name).count('followTarget = CommunityFollowTarget('), name)

    def test_the_windows_video_detail_button_asks_through_the_same_confirmation(self):
        leaf = text_of(UI / 'DesktopWindowsVideoPhysicalLeaf.kt')
        self.assertEqual(1, leaf.count('import com.android.purebilibili.core.ui.components.rememberFollowActionWithConfirmation\n'))
        self.assertEqual(1, leaf.count('rememberFollowActionWithConfirmation('))
        self.assertEqual(0, leaf.count('TextButton(onClick = { engagementBinding?.toggleFollow() }'))
        call = leaf[leaf.index('rememberFollowActionWithConfirmation('):]
        call = call[:call.index(') { Text(if(engagement.isFollowing)')]
        for part in ('targetKey = success.info.owner.mid', 'userName = success.info.owner.name',
                     'isFollowing = engagement.isFollowing', 'onFollowClick = { engagementBinding?.toggleFollow() }'):
            self.assertIn(part, call)
        self.assertEqual(1, leaf.count('engagementBinding?.toggleFollow()'))

    def test_new_top_level_names_have_one_declaration_and_the_guard_is_ordered(self):
        main = '\n'.join(text_of(p) for p in (REPO / 'desktop/src/main/kotlin').rglob('*.kt'))
        self.assertEqual(1, len(re.findall(r'(?m)^internal suspend fun <T> desktopCompleteAdmittedWrite\(', main)))
        self.assertEqual(1, len(re.findall(r'(?m)^internal class CommunityFollowTarget\(', main)))
        self.assertEqual(0, len(re.findall(r'fun rememberFollowActionWithConfirmation\(', main)), 'only the carried file declares it')
        helper = text_of(UI / 'DesktopAdmittedWrite.kt')
        order = [helper.index(marker) for marker in (
            'currentCoroutineContext().ensureActive()\n    assertOwned()\n    val outcome = withContext(NonCancellable) { write() }',
            'currentCoroutineContext().ensureActive()\n    assertOwned()\n    return outcome')]
        self.assertEqual(sorted(order), order)
        self.assertEqual(1, helper.count('NonCancellable) {'))

    def test_every_changed_and_new_kotlin_file_keeps_balanced_delimiters_and_the_jvm_counts(self):
        files = [UI / name for name in ('DesktopAdmittedWrite.kt', 'DesktopWindowsFollowConfirmationDialog.kt',
                                        'CommunityUiSupport.kt', 'CommunitySearchScreens.kt', 'PersonalScreens.kt',
                                        'VideoEngagementPanel.kt', 'DesktopWindowsVideoPhysicalLeaf.kt')]
        tests = REPO / 'desktop/src/test/kotlin/com/bilipai/desktop/ui'
        files += [tests / 'DesktopAdmittedWriteTest.kt', tests / 'DesktopFollowConfirmationEmissionTest.kt',
                  tests / 'DesktopWindowsFollowConfirmationDialogTest.kt', tests / 'DesktopWindowsVideoFeedbackLifetimeTest.kt',
                  REPO / follow.ARCHIVE / follow.LEAF]
        for file in files:
            stack = []
            for token, first, _ in self.sync.kotlin_tokens(text_of(file)):
                if token in ['(', '[', '{']:
                    stack.append(token)
                elif token in [')', ']', '}']:
                    self.assertTrue(stack, (file.name, first))
                    self.assertEqual({')': '(', ']': '[', '}': '{'}[token], stack.pop(), (file.name, first))
            self.assertEqual([], stack, file.name)
        self.assertEqual(5, text_of(tests / 'DesktopAdmittedWriteTest.kt').count('@Test'))
        self.assertEqual(3, text_of(tests / 'DesktopFollowConfirmationEmissionTest.kt').count('@Test'))
        self.assertEqual(3, text_of(tests / 'DesktopWindowsFollowConfirmationDialogTest.kt').count('@Test'))
        # The integration case that fails when the view model sends the like without desktopCompleteAdmittedWrite.
        lifetime = text_of(tests / 'DesktopWindowsVideoFeedbackLifetimeTest.kt')
        self.assertEqual(12, lifetime.count('@Test'))
        case = lifetime[lifetime.index('@Test fun anAdmittedLikeSurvivesItsCancelledCallerAndIsNeverPublishedIntoTheRetiredOwner'):]
        case = case[:case.index('@Test fun aRetiredPresentation')]
        for part in ('h.like = { reply.await().also { completed = true } }', 'assertTrue(h.ordinary.like()); assertEquals(1, h.likeCalls)',
                     'h.scope.coroutineContext.job.children.single().cancel()', 'reply.complete(Result.success(true)); h.drain()',
                     'assertTrue(completed,', 'assertFalse(h.vm.uiState.value.isLiked,',
                     'assertNull(h.vm.uiState.value.desktopLikeFeedbackOrigin)', 'assertEquals(1, h.likeCalls,'):
            self.assertEqual(1, case.count(part), part)
        self.assertLess(case.index('.children.single().cancel()'), case.index('reply.complete('))

    def test_gradle_and_the_producers_are_wired_once_each(self):
        build = text_of(REPO / 'desktop/build.gradle.kts')
        owners = ('extractOriginalVideoFullscreenPager', 'extractOriginalVideoDetailUnits',
                  'extractStableVideoMetadata', 'extractOriginalPlayerFullControls')
        for name in (*owners, 'extractOriginalSpacePages'):
            self.assertEqual(1, build.count('val %s by tasks.registering(' % name), name)
        block = ('tasks.matching { it.name in setOf(\n'
                 '    "extractOriginalVideoFullscreenPager", "extractOriginalVideoDetailUnits",\n'
                 '    "extractStableVideoMetadata", "extractOriginalPlayerFullControls",\n'
                 ') }.configureEach {\n'
                 '    inputs.file("tools/v046_follow_confirmation.py")\n'
                 '    inputs.dir("upstream-slices/v046-follow-confirmation")\n'
                 '}\n')
        self.assertEqual(1, build.count(block))
        self.assertEqual(2, build.count('inputs.file("tools/v046_follow_confirmation.py")'))
        self.assertEqual(1, build.count('tasks.named("extractOriginalSpacePages") { inputs.file("tools/v046_follow_confirmation.py") }'))
        self.assertEqual(1, build.count('tasks.named("extractOriginalVideoDetailUnits") { inputs.file("tools/v046_like_write_guard.py") }'))
        self.assertEqual(1, build.count('upstream-slices/v046-follow-confirmation'))
        self.assertEqual(1, build.count('inputs.file("tools/v037_player_known_presentation.py")', build.index('val extractOriginalVideoDetailHolderFull by')))
        hooks = {
            'extract-upstream-video-fullscreen-pager.py': [
                "from v046_follow_confirmation import validate_outputs as validate_follow_confirmation\n validate_follow_confirmation(repo,output)\n",
                "  from v046_follow_confirmation import apply_and_record as apply_follow_confirmation\n  text=apply_follow_confirmation(repo,spec['output'],text,output)\n",
                " from v046_follow_confirmation import emit_confirmation as emit_follow_confirmation\n rows.append(emit_follow_confirmation(repo,output))\n"],
            'extract-upstream-video-detail-full-units.py': [
                "    from v046_follow_confirmation import apply_and_record as apply_follow_confirmation\n    t=apply_follow_confirmation(REPO,path,t,OUTPUT)\n"
                "    from v046_like_write_guard import apply_and_record as apply_like_write_guard\n    t=apply_like_write_guard(REPO,path,t,OUTPUT)\n"],
            'extract-stable-video-metadata.py': [
                "        from v046_follow_confirmation import apply_and_record as apply_follow_confirmation\n"
                "        body=apply_follow_confirmation(repo,package.replace('.','/')+'/'+name,body,output)\n"],
            'extract-upstream-video-player-full-controls.py': [
                "def emit(rel,t,origin,mode):\n from v046_follow_confirmation import apply_and_record as apply_follow_confirmation\n"
                " t=apply_follow_confirmation(REPO,'com/android/purebilibili/'+rel,t,OUTPUT)\n"]}
        for name, lines in hooks.items():
            producer = text_of(TOOLS / name)
            for line in lines:
                self.assertEqual(1, producer.count(line), (name, line))
        pager = text_of(TOOLS / 'extract-upstream-video-fullscreen-pager.py')
        self.assertLess(pager.index('apply_fullscreen_tap_gestures(repo'), pager.index('apply_follow_confirmation(repo'))
        units = text_of(TOOLS / 'extract-upstream-video-detail-full-units.py')
        self.assertLess(units.index('apply_owner_follow(path,t,globals())'), units.index('apply_follow_confirmation(REPO'))
        self.assertLess(units.index('apply_follow_confirmation(REPO'), units.index('apply_like_write_guard(REPO'))

    def test_the_like_guard_is_a_documented_deviation_and_the_phone_flags_are_the_upstream_hunk(self):
        require_history(self, follow.COMMIT, guard.ADDED_BY, guard.REMOVED_BY)
        path = guard.UPSTREAM_PATH
        added = git_text('show', guard.ADDED_BY, '--format=', '--', path)
        for line in guard.ANDROID_030_BLOCK.splitlines():
            self.assertIn('+' + line, added)
        self.assertEqual(0, git_text('show', guard.UPSTREAM_COMMIT + ':' + path).count('NonCancellable'))
        removed = git_text('show', guard.REMOVED_BY, '--format=', '--', path)
        self.assertIn('-                withContext(kotlinx.coroutines.NonCancellable) {', removed)
        phone = 'app/src/main/java/com/android/purebilibili/feature/video/screen/VideoDetailPhoneContent.kt'
        diff = git_text('show', 'a614d2eba6712456e1256fd0697ed5c51e98f067', '--format=', '--', phone)
        for line in ('+                                    followStatusKnown = engagementState.followStatusKnown,',
                     '+                                    likeStatusKnown = engagementState.likeStatusKnown,'):
            self.assertEqual(1, diff.count(line))
        upstream = git_text('show', follow.COMMIT + ':' + phone)
        self.assertEqual(1, upstream.count(PHONE_AFTER))


if __name__ == '__main__':
    unittest.main()
