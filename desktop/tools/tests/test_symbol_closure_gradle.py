"""Retained legacy contracts against the current symbol gate (no graph imitation).

The two live/adapter contracts also expose missing production behavior. Restoring
their historical identities must not fabricate source_files or producer traces.
"""
import json
import os
from pathlib import Path
import subprocess
import unittest
from unittest import mock

import check_slice_symbol_closure as gate
from compiled_source_fixture import relative_inside, scratch_directory, temporary_directory

REPO = Path(__file__).resolve().parents[3]
GRADLE = (REPO / 'desktop/build.gradle.kts').read_text(encoding='utf8')
PREFIX = 'com/android/purebilibili/'


class GraphFixture:
    def __init__(self, test):
        temporary = test.enterContext(temporary_directory())
        self.legacy = temporary / '.legacy-root'
        self.live = temporary / 'live'
        self.model = gate.parse_gradle(GRADLE)
        self.put(self.legacy, 'desktop/build.gradle.kts', GRADLE)
        self.put(self.legacy, 'desktop/upstream-sources.json', '{"sources": []}')
        self.put(self.legacy, 'desktop/tools/v025-canonical-sources.json',
                 json.dumps(dict(sourceRoots=[], previousPaths={}, paths=[])))
        for entries in self.model.source_dirs.values():
            for kind, relative in entries:
                target = self.live if kind.startswith('merged') else self.legacy / (
                    'desktop/build' if kind == 'build' else 'desktop')
                Path(gate._long(target / relative)).mkdir(parents=True, exist_ok=True)
                if kind.startswith('merged'):
                    Path(gate._long(self.legacy / '.merged-root' / relative)).mkdir(parents=True, exist_ok=True)
        test.enterContext(mock.patch.dict(os.environ, {'BILIPAI_LIVE_ROOT': str(self.live)}))

    @staticmethod
    def put(root, relative, text):
        file = Path(gate._long(root / relative))
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_bytes(text.encode('utf8'))
        return file

    def source(self, area, name, text='package com.android.purebilibili.core.fixture\nclass Live\n', root=None):
        return self.put(root or self.live, 'app/src/' + area + '/java/' + PREFIX + name, text)

    def collect(self):
        sources, _ = gate.collect_sources(self.legacy, self.model)
        return [source for source in sources if source.set in ('main', 'test')]

    def adapter(self, name):
        output = self.legacy / 'desktop/build' / self.model.task_outputs[name]
        gate.materialize_kotlin(self.legacy, name, output)
        return output


class GradleSourceGraphTests(unittest.TestCase):
    def test_legacy_mirror_preserves_the_pin_identity_for_python_producers(self):
        with temporary_directory() as temporary:
            original = temporary / '.legacy-root'
            original.mkdir()
            subprocess.run(['git', 'init', '-q', str(original)], check=True)
            GraphFixture.put(original, 'desktop/pin.txt', 'legacy')
            with scratch_directory(original) as scratch:
                mirror, _ = gate.make_scratch_repo(original, scratch)
                self.assertEqual('.legacy-root', mirror.name)
                self.assertEqual('legacy', (mirror / 'desktop/pin.txt').read_text())

    def test_live_originals_win_over_legacy_and_stale_merged_snapshots(self):
        f = GraphFixture(self)
        name = 'core/fixture/Selected.kt'
        live = f.source('main', name)
        f.source('main', name, 'class Legacy', f.legacy)
        f.source('main', name, 'class Stale', f.legacy / '.merged-root')
        selected = [s for s in f.collect() if s.logical.endswith(name)]
        self.assertEqual([relative_inside(live, f.live)], [relative_inside(s.real, f.live) for s in selected])
        trace = next(s for s in f.model.source_files if s['file'].endswith(name))
        self.assertEqual('live', trace['inputRoot'])


class KotlinSourceAdapterTests(unittest.TestCase):
    def test_patch_adapter_reads_live_original_then_rewrites_and_tracks_its_owner(self):
        f = GraphFixture(self)
        relative = 'app/src/main/java/' + PREFIX + 'core/fixture/Patched.kt'
        f.put(f.live, relative, 'package fixture\nval old = ImageRequest.Builder(context)\n')
        f.put(f.legacy, relative, 'legacy would not match the patch')
        f.put(f.legacy, 'desktop/upstream-core/patches/' + relative + '.patch',
              '--- a\n+++ b\n@@ -1,2 +1,2 @@\n package fixture\n-val old = ImageRequest.Builder(context)\n+val updated = ImageRequest.Builder(context)\n')
        output = f.adapter('applyDesktopUpstreamPatches')
        self.assertIn('val updated = com.bilipai.desktop.compat.coil.desktopImageRequestBuilder(context)',
                      (output / relative).read_text())
        selected = [s for s in f.collect() if s.logical.endswith(relative)]
        self.assertEqual([relative_inside(output / relative, output)],
                         [relative_inside(s.real, output) for s in selected])
        trace = next(s for s in f.model.source_files if s['file'].endswith(relative))
        self.assertEqual(['applyDesktopUpstreamPatches'], trace['producers'])
