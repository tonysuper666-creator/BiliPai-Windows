"""Source tests for the read-only consumed-file to design-system-symbol table
(desktop/tools/design_system_consumer_table.py). No JVM, no network, nothing is compiled.

The synthetic classes build a throw-away git repository whose single commit plays the upstream commit and whose
working tree plays the Windows tree; `CurrentTree` runs the tool on this checkout against the real v0.4.6 commit
(about ten seconds).
"""
from pathlib import Path
import contextlib
import io
import json
import os
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
TOOLS = REPO / 'desktop/tools'
sys.path.insert(0, str(TOOLS))
import design_system_consumer_table as table_tool

DS = 'design-system/src/main/java/com/android/purebilibili/core/ui/'
V046 = 'd6bf8b1655ae910999860bf13e65c8fcea5b345b'


def rmtree_force(path):
    def clear(function, target, *_):
        try:
            os.chmod(target, stat.S_IRWXU)
            function(target)
        except OSError:
            pass

    if sys.version_info >= (3, 12):
        shutil.rmtree(str(path), onexc=clear)
    else:
        shutil.rmtree(str(path), onerror=clear)


def git(root, *args):
    return subprocess.run(['git', '-c', 'core.autocrlf=false', '-c', 'user.name=t', '-c', 'user.email=t@example.invalid',
                           '-c', 'commit.gpgsign=false', '-C', str(root), *args],
                          check=True, capture_output=True).stdout.decode('utf8')


def snapshot(root):
    state = {}
    for current, folders, files in os.walk(root):
        if '.git' in folders:
            folders.remove('.git')
        for name in files:
            target = Path(current) / name
            state[target.relative_to(root).as_posix()] = (target.stat().st_size, target.stat().st_mtime_ns)
    return state


PALETTE_V2 = 'package com.x.ui\n\nobject Palette {\n    val A = 1\n    val B = 2\n    val C = 3\n}\n'
PALETTE_OLD = 'package com.x.ui\n\nobject Palette {\n    val A = 1\n    val B = 2\n}\n'
TOKENS = 'package com.x.ui\n\nobject Tokens {\n    val X = 1\n}\n\nprivate fun hidden() = 0\n'
LEAF = 'package com.x.ui\n\nfun leafFn() = 1\n'
MOVED = 'package com.x.ui\n\nclass Moved\n'
HALF = 'package com.x.ui\n\nfun half() = 1\nfun halfTwo() = 2\n'
HALF_PIECE = 'package com.x.ui\n\nfun half() = 0\n'
COLORS = 'package com.x.theme\n\nval Blue = 1\n'
OTHER = 'package com.x.feature\n\nfun other() = 1\n'
USES = ('package com.x.feature\n\nimport com.x.ui.Palette\nimport com.x.ui.Tokens\nimport com.x.ui.leafFn\n'
        'import com.x.theme.Blue as Sky\nimport kotlin.math.max\n\n'
        'fun uses() = Palette.A + Palette.C + Tokens.X + leafFn() + Sky\n')
SAME_PACKAGE = 'package com.x.ui\n\nfun samePkg() = Tokens.X + leafFn() + half()\n'
STAR = 'package com.x.feature\n\nimport com.x.ui.*\n\nfun star() = Tokens.X\n'
REFERENCE = 'package com.x.feature\n\nimport com.x.ui.Palette\n\nfun ref() = Palette.A\n'
PARTIAL = 'package com.x.feature\n\nimport com.x.ui.Palette\n\nfun partial() = Palette.B\n'
WINDOWS = 'package com.bilipai.desktop\n\nimport com.x.theme.Blue\n\nfun win() = Blue\n'


class Fixture:
    """One commit with the design-system layer; a working tree that carries it older, equal, partly or not at all."""

    def __init__(self, test):
        self.root = Path(tempfile.mkdtemp(prefix='bp-t-'))
        test.addCleanup(rmtree_force, self.root)
        git(self.root, 'init', '-q')
        self.put('design-system/src/main/java/com/x/ui/Palette.kt', PALETTE_V2)
        self.put('design-system/src/main/java/com/x/ui/Tokens.kt', TOKENS)
        self.put('design-system/src/main/java/com/x/ui/Leaf.kt', LEAF)
        self.put('design-system/src/main/java/com/x/ui/Moved.kt', MOVED)
        self.put('design-system/src/main/java/com/x/ui/Half.kt', HALF)
        self.put('design-tokens/src/main/java/com/x/theme/Colors.kt', COLORS)
        self.put('app/src/main/java/com/x/feature/Other.kt', OTHER)
        git(self.root, 'add', '-A')
        git(self.root, 'commit', '-q', '-m', 'upstream')
        self.commit = git(self.root, 'rev-parse', 'HEAD').strip()
        # the Windows tree: working-tree files only, nothing committed
        self.put('design-system/src/main/java/com/x/ui/Palette.kt', PALETTE_OLD)
        self.put('design-system/src/main/java/com/x/ui/Half.kt', HALF_PIECE)
        self.put('app/src/main/java/com/x/feature/Uses.kt', USES)
        self.put('app/src/main/java/com/x/ui/SamePkg.kt', SAME_PACKAGE)
        self.put('app/src/main/java/com/x/feature/Star.kt', STAR)
        self.put('app/src/main/java/com/x/feature/Ref.kt', REFERENCE)
        self.put('desktop/upstream-slices/s1/Moved.kt', MOVED)
        self.put('desktop/upstream-slices/s1/RefOnly.kt', REFERENCE)
        self.put('desktop/upstream-slices/s1/Partial.kt', PARTIAL)
        self.put('desktop/src/main/kotlin/com/bilipai/desktop/Win.kt', WINDOWS)
        sources = [dict(path='design-system/src/main/java/com/x/ui/Palette.kt', mode='direct'),
                   dict(path='design-system/src/main/java/com/x/ui/Tokens.kt'),          # mode defaults to direct
                   dict(path='design-system/src/main/java/com/x/ui/Half.kt', mode='policy-extract'),
                   dict(path='design-tokens/src/main/java/com/x/theme/Colors.kt', mode='direct'),
                   dict(path='app/src/main/java/com/x/feature/Uses.kt', mode='direct'),
                   dict(path='app/src/main/java/com/x/ui/SamePkg.kt', mode='direct'),
                   dict(path='app/src/main/java/com/x/feature/Star.kt', mode='direct'),
                   dict(path='app/src/main/java/com/x/feature/Ref.kt', mode='reference-only'),
                   dict(path='app/src/main/res/values/strings.xml', mode='direct')]
        inputs = [dict(path='app/src/main/java/com/x/ui/Moved.kt', localPath='desktop/upstream-slices/s1/Moved.kt',
                       kind='adaptedSource', referenceOnly=False),
                  dict(path='app/src/main/java/com/x/feature/RefOnly.kt', localPath='desktop/upstream-slices/s1/RefOnly.kt',
                       kind='adaptedSource', referenceOnly=True),
                  dict(path='app/src/main/java/com/x/feature/Partial.kt', localPath='desktop/upstream-slices/s1/Partial.kt',
                       kind='adaptedSource', referenceOnly=False, partialReuse=True),
                  dict(path='app/src/main/res/raw/a.json', localPath='desktop/upstream-slices/s1/a.json',
                       kind='adaptedResource', referenceOnly=False)]
        self.put('desktop/upstream-sources.json', json.dumps(dict(sources=sources), indent=2) + '\n')
        self.put('desktop/tools/compatibility-source-inputs.json', json.dumps(dict(inputs=inputs), indent=2) + '\n')

    def put(self, relative, text):
        target = self.root / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(text.encode('utf8'))

    def build(self):
        return table_tool.build_table(self.root, self.commit)


def by_path(table):
    return {entry['path'].rsplit('/', 1)[-1]: entry for entry in table['files']}


def rows_of(table, consumer_suffix):
    return [row for row in table['rows'] if row['consumer'].endswith(consumer_suffix)]


class SyntheticTable(unittest.TestCase):
    def setUp(self):
        self.fixture = Fixture(self)
        self.table = self.fixture.build()
        self.files = by_path(self.table)

    def test_only_the_design_system_layer_of_the_commit_is_indexed(self):
        self.assertEqual(['Colors.kt', 'Half.kt', 'Leaf.kt', 'Moved.kt', 'Palette.kt', 'Tokens.kt'], sorted(self.files))
        self.assertEqual(6, self.table['summary']['designSystemFiles'])
        # leafFn, half, halfTwo, Palette, Tokens, Moved, Blue: the private function and Other.kt are not symbols
        self.assertEqual(7, self.table['summary']['designSystemSymbols'])
        self.assertEqual(['Tokens'], self.files['Tokens.kt']['symbols'])

    def test_status_says_how_the_windows_tree_carries_each_file(self):
        status = {name: entry['status'] for name, entry in self.files.items()}
        self.assertEqual({'Colors.kt': 'current', 'Half.kt': 'partial', 'Leaf.kt': 'absent',
                          'Moved.kt': 'current', 'Palette.kt': 'older', 'Tokens.kt': 'current'}, status)
        self.assertEqual({'absent': 1, 'current': 3, 'older': 1, 'partial': 1}, self.table['summary']['filesByStatus'])

    def test_a_relocated_copy_with_the_commits_bytes_counts_as_carried(self):
        copies = self.files['Moved.kt']['carriedCopies']
        self.assertEqual([('archive', 'desktop/upstream-slices/s1/Moved.kt', 'same-bytes', 'whole')],
                         [(c['origin'], c['file'], c['bytes'], c['use']) for c in copies])
        self.assertEqual(['direct'], [c['detail'] for c in self.files['Tokens.kt']['carriedCopies']])

    def test_an_older_whole_copy_and_a_piece_are_told_apart_in_the_copy_list(self):
        self.assertEqual([('vendored', 'different-bytes', 'whole')],
                         [(c['origin'], c['bytes'], c['use']) for c in self.files['Palette.kt']['carriedCopies']])
        half = self.files['Half.kt']['carriedCopies']
        self.assertEqual([('vendored', 'policy-extract', 'different-bytes', 'partial')],
                         [(c['origin'], c['detail'], c['bytes'], c['use']) for c in half])

    def test_explicit_imports_resolve_with_aliases_and_report_members_the_carried_copy_lacks(self):
        rows = {row['symbol'].rsplit('.', 1)[-1]: row for row in rows_of(self.table, 'feature/Uses.kt')}
        self.assertEqual(['Blue', 'Palette', 'Tokens', 'leafFn'], sorted(rows))
        palette = rows['Palette']
        self.assertEqual(('import', 'older', ['A', 'C'], ['C']),
                         (palette['via'], palette['status'], palette['membersUsed'], palette['membersMissing']))
        self.assertEqual([], rows['Tokens']['membersMissing'])
        self.assertEqual(['X'], rows['Tokens']['membersUsed'])
        self.assertEqual('absent', rows['leafFn']['status'])
        self.assertEqual('com.x.theme.Blue', rows['Blue']['symbol'])
        self.assertEqual(1, self.table['summary']['membersMissingRows'])
        self.assertEqual(['Palette.C'], self.files['Palette.kt']['membersMissing'])

    def test_same_package_and_star_uses_are_read_from_tokens_and_marked_as_such(self):
        same = {row['symbol'].rsplit('.', 1)[-1]: row['via'] for row in rows_of(self.table, 'com/x/ui/SamePkg.kt')}
        self.assertEqual({'Tokens': 'same-package', 'half': 'same-package', 'leafFn': 'same-package'}, same)
        star = {row['symbol'].rsplit('.', 1)[-1]: row['via'] for row in rows_of(self.table, 'feature/Star.kt')}
        self.assertEqual({'Tokens': 'star-import'}, star)

    def test_reference_only_and_resource_inputs_are_not_consumers_and_origins_are_kept(self):
        consumers = {row['consumer'] for row in self.table['rows']}
        self.assertNotIn('app/src/main/java/com/x/feature/Ref.kt', consumers)
        self.assertNotIn('desktop/upstream-slices/s1/RefOnly.kt', consumers)
        self.assertIn('desktop/upstream-slices/s1/Partial.kt', consumers)
        self.assertIn('desktop/src/main/kotlin/com/bilipai/desktop/Win.kt', consumers)
        summary = self.table['summary']
        self.assertEqual({'archive': 2, 'desktop': 1, 'vendored': 7}, summary['consumedByOrigin'])
        self.assertEqual(2, summary['skipped']['vendoredNotConsumed'])      # Ref.kt (reference-only) and strings.xml
        self.assertEqual(2, summary['skipped']['archiveReferenceOrResource'])
        self.assertEqual([], summary['consumedFilesMissingOnDisk'])
        origins = {row['consumer']: row['origin'] for row in self.table['rows']}
        self.assertEqual('archive', origins['desktop/upstream-slices/s1/Partial.kt'])
        self.assertEqual('desktop', origins['desktop/src/main/kotlin/com/bilipai/desktop/Win.kt'])

    def test_a_partial_archive_use_is_a_consumer_but_never_a_definer(self):
        palette = [row for row in rows_of(self.table, 'Partial.kt') if row['symbol'].endswith('Palette')]
        self.assertEqual(1, len(palette))
        self.assertEqual('older', palette[0]['status'])
        self.assertEqual(['Palette.kt'], sorted({c['file'].rsplit('/', 1)[-1] for c in self.files['Palette.kt']['carriedCopies']}))

    def test_tool_is_deterministic_and_the_json_form_is_the_table(self):
        again = self.fixture.build()
        self.assertEqual(json.dumps(self.table, sort_keys=True), json.dumps(again, sort_keys=True))
        self.assertEqual(table_tool.SCHEMA_VERSION, self.table['schemaVersion'])
        self.assertEqual(self.table['summary']['rows'], len(self.table['rows']))
        self.assertEqual(sorted(self.table['rows'], key=lambda r: (r['declaring'], r['symbol'], r['consumer'])),
                         self.table['rows'])

    def test_the_run_never_writes_into_the_checkout_and_refuses_a_json_path_inside_it(self):
        before = snapshot(self.fixture.root)
        status = git(self.fixture.root, 'status', '--porcelain')
        table_tool.build_table(self.fixture.root, self.fixture.commit)
        with contextlib.redirect_stdout(io.StringIO()) as out:
            code = table_tool.main(['--repo', str(self.fixture.root), '--commit', self.fixture.commit])
        self.assertEqual(0, code)
        self.assertIn('design-system files 6, symbols 7', out.getvalue())
        self.assertEqual(before, snapshot(self.fixture.root))
        self.assertEqual(status, git(self.fixture.root, 'status', '--porcelain'))
        inside = self.fixture.root / 'desktop/consumer-table.json'
        with self.assertRaises(SystemExit) as caught, contextlib.redirect_stderr(io.StringIO()):
            table_tool.main(['--repo', str(self.fixture.root), '--commit', self.fixture.commit, '--json', str(inside)])
        self.assertEqual(2, caught.exception.code)
        self.assertFalse(inside.exists())
        outside = Path(tempfile.mkdtemp(prefix='bp-o-'))
        self.addCleanup(rmtree_force, outside)
        with contextlib.redirect_stdout(io.StringIO()):
            table_tool.main(['--repo', str(self.fixture.root), '--commit', self.fixture.commit,
                             '--json', str(outside / 'table.json'), '--quiet'])
        written = (outside / 'table.json').read_bytes()
        self.assertNotIn(b'\r', written)
        self.assertEqual(json.loads(written), self.table)

    def test_text_report_lists_unfinished_files_first_and_focus_lists_copies_and_consumers(self):
        def listed(text):
            return [line.split()[0] for line in text.splitlines()
                    if line.startswith(('absent', 'older', 'partial', 'current'))]

        self.assertEqual(['absent', 'older', 'partial'],
                         listed(table_tool.render_text(self.table, statuses=('absent', 'older', 'partial'))))
        self.assertEqual(['absent', 'older', 'partial', 'current', 'current'], listed(table_tool.render_text(self.table)))
        focus = table_tool.render_text(self.table, focus=('Palette',), consumers=1)
        self.assertIn('carried by vendored design-system/src/main/java/com/x/ui/Palette.kt (different-bytes, whole use)', focus)
        self.assertIn('[missing: Palette.C]', focus)
        self.assertIn('... and 1 more rows', focus)

    def test_unknown_commit_and_unknown_status_fail_cleanly(self):
        with contextlib.redirect_stderr(io.StringIO()) as err:
            code = table_tool.main(['--repo', str(self.fixture.root), '--commit', '0' * 40])
        self.assertEqual(2, code)
        self.assertIn('design-system consumer table', err.getvalue())
        with self.assertRaises(SystemExit) as caught, contextlib.redirect_stderr(io.StringIO()):
            table_tool.main(['--repo', str(self.fixture.root), '--commit', self.fixture.commit, '--status', 'bogus'])
        self.assertEqual(2, caught.exception.code)

    def test_a_consumed_file_missing_on_disk_is_reported_not_skipped_silently(self):
        (self.fixture.root / 'app/src/main/java/com/x/feature/Star.kt').unlink()
        table = self.fixture.build()
        self.assertEqual(['app/src/main/java/com/x/feature/Star.kt'], table['summary']['consumedFilesMissingOnDisk'])
        self.assertEqual([], rows_of(table, 'feature/Star.kt'))


class CurrentTree(unittest.TestCase):
    """The tool on this checkout against the real v0.4.6 design-system layer."""

    @classmethod
    def setUpClass(cls):
        have = subprocess.run(['git', '-C', str(REPO), 'cat-file', '-t', V046], capture_output=True).stdout.strip()
        if have != b'commit':
            raise unittest.SkipTest('the v0.4.6 upstream commit is not in this clone')
        cls.table = table_tool.build_table(REPO)
        cls.files = {entry['path']: entry for entry in cls.table['files']}

    def status(self, name, folder=DS):
        return self.files[folder + name + '.kt']['status']

    def test_the_commit_has_the_design_system_layer_the_roadmap_counts(self):
        summary = self.table['summary']
        self.assertEqual(V046, summary['commit'])
        self.assertEqual(175, summary['designSystemFiles'])
        self.assertGreater(summary['designSystemSymbols'], 800)
        self.assertGreater(summary['consumedFiles'], 2500)
        self.assertEqual({'archive', 'desktop', 'vendored'}, set(summary['consumedByOrigin']))
        self.assertEqual({'import', 'same-package', 'star-import'}, set(summary['rowsByVia']))
        self.assertEqual(175, sum(summary['filesByStatus'].values()))
        self.assertEqual([], summary['consumedFilesMissingOnDisk'])

    def test_files_already_carried_at_v046_are_reported_current(self):
        for name, folder in (('AppContentColors', DS), ('ButtonSlotColor', DS), ('AppSurfaceTokens', DS),
                             ('AppActionColors', DS + 'components/'), ('AppChromeOverflow', DS + 'components/'),
                             ('AppTextSearchHighlight', DS + 'components/')):
            with self.subTest(file=name):
                self.assertEqual('current', self.status(name, folder))

    def test_part_a_leaf_files_are_carried_at_the_v046_bytes(self):
        for name in ('MediaContrastPalette', 'AppPopupSurface', 'AppSemanticVisualPolicy', 'AppEntryIconPalette',
                     'AppChromeSizeTokens'):
            with self.subTest(file=name):
                self.assertEqual('current', self.status(name))
        for name in ('MediaContrastPalette', 'AppChromeSizeTokens'):
            self.assertEqual([], self.files[DS + name + '.kt']['membersMissing'], name)

    def test_the_consumers_the_roadmap_names_are_present_with_their_symbols(self):
        popup = self.files[DS + 'AppPopupSurface.kt']
        self.assertGreater(popup['consumerFiles'], 10)
        self.assertIn('AppPopupSurfaceType', popup['importedSymbols'])
        tokens = self.files[DS + 'AppChromeSizeTokens.kt']
        self.assertGreater(tokens['consumerFiles'], 50)
        self.assertIn('AppChromeSizeTokens', tokens['importedSymbols'])
        rows = [r for r in self.table['rows'] if r['declaring'] == DS + 'MediaContrastPalette.kt']
        self.assertTrue(any(r['consumer'].endswith('v042-player-readability/TopControlBar.kt') and
                            r['membersUsed'] == ['Error', 'Warning'] for r in rows))

    def test_every_row_points_at_a_declaring_file_of_the_commit_and_a_valid_status(self):
        for row in self.table['rows']:
            self.assertIn(row['declaring'], self.files)
            self.assertEqual(self.files[row['declaring']]['status'], row['status'])
            self.assertIn(row['via'], ('import', 'same-package', 'star-import'))
            self.assertIn(row['origin'], ('vendored', 'archive', 'desktop'))

    def test_the_tool_does_not_touch_the_checkout(self):
        before = subprocess.run(['git', '-C', str(REPO), 'status', '--porcelain'], capture_output=True).stdout
        table_tool.build_table(REPO)
        after = subprocess.run(['git', '-C', str(REPO), 'status', '--porcelain'], capture_output=True).stdout
        self.assertEqual(before, after)


class SourceHygiene(unittest.TestCase):
    def test_tool_and_test_are_lf_only_and_never_start_a_jvm_or_the_network(self):
        for path in (TOOLS / 'design_system_consumer_table.py', Path(__file__)):
            raw = path.read_bytes()
            self.assertNotIn(b'\r', raw, path.name)
        text = (TOOLS / 'design_system_consumer_table.py').read_text(encoding='utf8')
        for forbidden in ('gradle', 'urllib', 'requests', 'socket', 'kotlinc', 'javac'):
            self.assertNotIn(forbidden + '(', text)
            self.assertNotIn('import ' + forbidden, text)
        # the only external program is git, and only read-only subcommands
        subcommands = {'ls-tree', 'cat-file'}
        for line in text.splitlines():
            if '_git(repo,' in line and 'def ' not in line:
                self.assertTrue(any("'" + name + "'" in line for name in subcommands), line)


if __name__ == '__main__':
    unittest.main()
