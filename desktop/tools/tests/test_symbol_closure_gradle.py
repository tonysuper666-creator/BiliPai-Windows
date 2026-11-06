"""Independent contracts for Gradle source selection and bounded Kotlin source adapters.

Fixtures deliberately give live and legacy originals different declarations. No JVM, Windows
machine, network, or existing symbol-gate test is needed by this module.
"""
from pathlib import Path
import hashlib
import json
import os
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'desktop/tools'))
import check_slice_symbol_closure as gate

GRADLE = (REPO / 'desktop/build.gradle.kts').read_text(encoding='utf8')
PREFIX = 'com/android/purebilibili/'


class GraphFixture:
    def __init__(self, test):
        temporary = tempfile.TemporaryDirectory(prefix='bp-graph-')
        test.addCleanup(temporary.cleanup)
        self.legacy = Path(temporary.name) / '.legacy-root'
        self.live = Path(temporary.name) / 'live'
        self.model = gate.parse_gradle(GRADLE)
        self.put(self.legacy, 'desktop/build.gradle.kts', GRADLE)
        self.put(self.legacy, '.git/objects/info/alternates', str(REPO / '.git/objects'))
        self.put(self.legacy, 'desktop/upstream-sources.json', '{"sources": []}')
        self.put(self.legacy, 'desktop/tools/v025-canonical-sources.json',
                 json.dumps(dict(sourceRoots=[], previousPaths={}, paths=[])))
        for entries in self.model.source_dirs.values():
            for kind, relative in entries:
                target = self.live if kind.startswith('merged') else self.legacy / ('desktop/build' if kind == 'build' else 'desktop')
                (target / relative).mkdir(parents=True, exist_ok=True)
        self.put(self.live, 'app/build.gradle.kts',
                 'android { namespace = "com.android.purebilibili"\nversionCode = 42\nversionName = "1.2.3"\n}')
        environment = mock.patch.dict(os.environ, {'BILIPAI_LIVE_ROOT': str(self.live)})
        environment.start()
        test.addCleanup(environment.stop)

    @staticmethod
    def put(root, relative, text):
        file = root / relative
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_bytes(text.encode('utf8'))
        return file

    def source(self, area, name, text='package com.android.purebilibili.core.fixture\nclass Live\n', root=None):
        return self.put(root or self.live, 'app/src/' + area + '/java/' + PREFIX + name, text)

    def collect(self):
        sources, _ = gate.collect_sources(self.legacy, self.model)
        return [s for s in sources if s.set in ('main', 'test')]

    def adapter(self, name):
        output = self.legacy / 'desktop/build' / self.model.task_outputs[name]
        gate.materialize_kotlin(self.legacy, name, output)
        return output


class GradleSourceGraphTests(unittest.TestCase):
    def test_current_wiring_resolves_every_source_root_and_kotlin_owner(self):
        model = gate.parse_gradle(GRADLE)
        self.assertEqual([], model.unresolved_source_dirs)
        self.assertTrue(model.filters)
        self.assertIn(('merged', 'app/src/main/java'), model.source_dirs['main'])
        self.assertIn(('merged', 'app/src/test/java'), model.source_dirs['test'])
        self.assertIn(('merged', 'core-player/src/main/java'), model.source_dirs['main'])
        for task in gate.KOTLIN_TASKS:
            self.assertIn(task, model.tasks)
            self.assertIn(task, model.output_owners[model.task_outputs[task]])

    def test_built_by_must_name_a_real_producer_that_owns_the_directory(self):
        template = '''val generated = layout.buildDirectory.dir("generated/example")
val produce by tasks.registering(Exec::class) {
    commandLine("python3", "tools/produce.py")
    outputs.dir(generated)
}
kotlin.sourceSets.named("main") {
    kotlin.srcDir(files(layout.buildDirectory.dir("%s")).builtBy(%s))
}'''
        model = gate.parse_gradle(template % ('generated/example/kotlin', 'produce'))
        self.assertEqual('produce', model.source_owners[('main', 'build', 'generated/example/kotlin')])
        unknown = gate.parse_gradle(template % ('generated/example', 'unimplemented'))
        self.assertTrue(unknown.unresolved_source_dirs)
        with self.assertRaisesRegex(gate.ToolError, 'does not own'):
            gate.parse_gradle(template % ('generated/unrelated', 'produce'))

    def test_unknown_source_expressions_and_unimplemented_tasks_are_not_empty_successes(self):
        for expression in ('computedRoot()', 'unknownTask', 'root', 'directory'):
            with self.subTest(expression=expression):
                model = gate.parse_gradle('kotlin.sourceSets.named("main") { kotlin.srcDir(' + expression + ') }')
                self.assertEqual(1, len(model.unresolved_source_dirs))
        with self.assertRaisesRegex(gate.ToolError, 'source directory collection'):
            gate.parse_gradle('kotlin.sourceSets.named("main") { kotlin.srcDirs("one", "two") }')

    def test_named_task_configuration_can_declare_a_sibling_test_output(self):
        model = gate.parse_gradle('''val produce by tasks.registering(Exec::class) {
    commandLine("python3", "tools/produce.py")
    outputs.dir(layout.buildDirectory.dir("generated/main"))
}
tasks.named("produce") { outputs.dir(layout.buildDirectory.dir("generated/tests")) }
kotlin.sourceSets.named("test") {
    kotlin.srcDir(files(layout.buildDirectory.dir("generated/tests")).builtBy(produce))
}''')
        self.assertEqual({'produce'}, model.output_owners['generated/tests'])
        self.assertEqual('produce', model.source_owners[('test', 'build', 'generated/tests')])

    def test_dynamic_collection_tails_and_unknown_kotlin_producers_are_rejected(self):
        for before, after in (
                ('listOf("design-tokens/", "design-system/", "plugin-sdk/")',
                 'listOf("design-tokens/", "design-system/", "plugin-sdk/") + computeMore()'),
                ('val migratedAppSingleFiles: Set<String> = setOf(',
                 'val migratedAppSingleFiles: Set<String> = computedSet('),
                ('val migratedAppSingleFiles:', 'val removedRequiredCollection:')):
            with self.subTest(before=before), self.assertRaises(gate.ToolError):
                model = gate.parse_gradle(GRADLE.replace(before, after, 1))
                gate._resolve_live_graph(REPO, model)
        recipe = '''val generated = layout.buildDirectory.dir("generated/example")
val unknown by tasks.registering {
    outputs.dir(generated)
    doLast { }
}
kotlin.sourceSets.named("main") { kotlin.srcDir(generated) }
'''
        with self.assertRaisesRegex(gate.ToolError, 'Unmodelled source producer'):
            gate.select_producers(gate.parse_gradle(recipe))

    def test_legacy_mirror_preserves_the_pin_identity_for_python_producers(self):
        with tempfile.TemporaryDirectory(prefix='bp-legacy-') as temporary:
            original = Path(temporary) / '.legacy-root'
            original.mkdir()
            subprocess.run(['git', 'init', '-q', str(original)], check=True)
            GraphFixture.put(original, 'desktop/pin.txt', 'legacy')
            mirror, _ = gate.make_scratch_repo(original, Path(temporary) / 'scratch')
            self.assertEqual('.legacy-root', mirror.name)
            self.assertEqual('legacy', (mirror / 'desktop/pin.txt').read_text())

    def test_legacy_graph_cannot_silently_fall_back_when_live_root_is_missing(self):
        f = GraphFixture(self)
        with mock.patch.dict(os.environ, {}, clear=True):
            with self.assertRaisesRegex(gate.ToolError, 'explicit BILIPAI_LIVE_ROOT'):
                f.collect()

    def test_new_predicates_and_changed_dynamic_recipes_fail_closed(self):
        for before, after in (
                ('kotlin.srcDir(appJavaRoot)', 'kotlin.srcDir(appJavaRoot)\n kotlin.include("**/Only.kt")'),
                ('path.removePrefix("com/android/purebilibili/") !in appM4dLaterSteps', 'false'),
                ('var lines = original.replace', 'var lines = original.trim().replace'),
                ('"ldpi" to 120', '"ldpi" to 121'),
                ('val appJavaRoot = mergedRepositoryRoot.resolve("app/src/main/java")',
                 'val appJavaRoot = mergedRepositoryRoot.resolve("different")'),
                ('val generated = layout.buildDirectory.dir("generated")',
                 'val generated = layout.buildDirectory.dir("different")'),
                ('Regex("""(?<![\\w.])(?:coil3\\.request\\.)?ImageRequest\\.Builder\\(""")',
                 'Regex(dynamicPattern())')):
            with self.subTest(before=before):
                self.assertIn(before, GRADLE)
                with self.assertRaisesRegex(gate.ToolError, 'Unmodelled Gradle recipe'):
                    gate.parse_gradle(GRADLE.replace(before, after, 1))

    def test_changed_loop_binding_is_unresolved_instead_of_reusing_the_old_root(self):
        text = GRADLE.replace('val root = mergedRepositoryRoot.resolve(relativeRoot)',
                              'val root = file("different")')
        self.assertIn(('main', 'unknown loop binding root'), gate.parse_gradle(text).unresolved_source_dirs)

    def test_live_originals_win_over_legacy_and_stale_merged_snapshots(self):
        f = GraphFixture(self)
        name = 'core/fixture/Selected.kt'
        live = f.source('main', name)
        f.source('main', name, 'class Legacy', f.legacy)
        f.source('main', name, 'class Stale', f.legacy / '.merged-root')
        selected = [s for s in f.collect() if s.logical.endswith(name)]
        self.assertEqual([live.resolve()], [s.real.resolve() for s in selected])
        trace = next(s for s in f.model.source_files if s['file'].endswith(name))
        self.assertEqual('live', trace['inputRoot'])

    def test_migrated_generated_copy_is_removed_and_duplicate_remains_observable(self):
        f = GraphFixture(self)
        name = 'core/fixture/Selected.kt'
        f.source('main', name, 'package com.android.purebilibili.core.fixture\nclass Selected')
        f.put(f.legacy, 'desktop/build/generated/api/' + PREFIX + name,
              'package com.android.purebilibili.core.fixture\nclass Selected')
        sources = f.collect()
        self.assertEqual(1, sum(s.logical.endswith(name) for s in sources))
        f.put(f.legacy, 'desktop/src/main/kotlin/Duplicate.kt',
              'package com.android.purebilibili.core.fixture\nclass Selected')
        result = gate.analyze(f.collect(), [], gate.empty_expected(),
                              gate.Options(names_scope='none', carried=False, checks=['duplicates']))
        self.assertTrue(any('Selected' in finding.symbol for finding in result.unexpected))

    def test_feature_later_steps_and_retired_generated_tables_select_actual_files(self):
        f = GraphFixture(self)
        held = 'feature/message/Fixture.kt'
        f.source('main', held)
        retired = 'api/Retired.kt'
        f.put(f.legacy, 'desktop/upstream-core/m4d/later-steps.tsv', held + '\twait\n')
        f.put(f.legacy, 'desktop/upstream-core/m4d/retired-generated.tsv', retired + '\tmigrated\n')
        f.put(f.legacy, 'desktop/build/generated/' + retired, 'class Retired')
        logical = [s.logical for s in f.collect()]
        self.assertFalse(any(p.endswith(held) or p.endswith(retired) for p in logical))

    def test_excluding_a_definer_does_not_hide_a_consumers_missing_import(self):
        f = GraphFixture(self)
        f.source('main', 'feature/message/Provider.kt',
                 'package com.android.purebilibili.feature.message\nclass Provider')
        f.put(f.legacy, 'desktop/src/main/kotlin/Consumer.kt',
              'package consumer\nimport com.android.purebilibili.feature.message.Provider\nval provider: Provider? = null')
        options = gate.Options(names_scope='none', carried=False, checks=['imports'])
        before = gate.analyze(f.collect(), [], gate.empty_expected(), options)
        self.assertEqual([], before.unexpected)
        f.put(f.legacy, 'desktop/upstream-core/m4d/later-steps.tsv', 'feature/message/Provider.kt\twait\n')
        after = gate.analyze(f.collect(), [], gate.empty_expected(), options)
        self.assertTrue(any(finding.check == 'unresolved-import' and finding.symbol.endswith('.Provider')
                            for finding in after.unexpected))

    def test_test_filters_remove_not_run_later_subject_junit4_and_corrected_originals(self):
        f = GraphFixture(self)
        names = ['AllowedTest.kt', 'NotRunTest.kt', 'LaterTest.kt', 'JUnit4Test.kt']
        for name in names:
            f.source('test', 'feature/message/' + name,
                     ('import org.junit.Test\n' if name == 'JUnit4Test.kt' else
                      'import org.junit.jupiter.api.Test\n') + 'class Fixture')
        f.put(f.legacy, 'desktop/upstream-core/m4d/tests-not-run.tsv', 'feature/message/NotRunTest.kt\treason\n')
        f.put(f.legacy, 'desktop/upstream-core/m4d/later-steps.tsv', 'feature/message/Later.kt\treason\n')
        corrected = next(p for p in gate._gradle_collection(GRADLE, 'correctedUpstreamTestPaths') if p.startswith('app/'))
        f.put(f.live, corrected, 'class OriginalCorrected')
        f.put(f.legacy, 'desktop/build/patched-upstream-tests/' + corrected, 'class Corrected')
        selected = f.collect()
        tests = [s.logical for s in selected if s.set == 'test']
        self.assertTrue(any(p.endswith('/AllowedTest.kt') for p in tests))
        for gone in names[1:]:
            self.assertFalse(any(p.endswith('/' + gone) for p in tests), gone)
        self.assertEqual(['build/patched-upstream-tests/' + corrected], [p for p in tests if p.endswith(corrected)])

    def test_partial_module_selects_only_registered_main_and_test_paths(self):
        f = GraphFixture(self)
        for source_set, collection in [('main', 'migratedPlaybackModulePaths'), ('test', 'migratedPlaybackModuleTests')]:
            root, paths = next(iter(gate._gradle_path_map(GRADLE, collection).items()))
            allowed = sorted(paths)[0]
            f.put(f.live, root + allowed, 'class Allowed')
            f.put(f.live, root + 'Unregistered.kt', 'class Excluded')
            selected = [s.logical for s in f.collect() if s.set == source_set]
            self.assertIn(root + allowed, selected)
            self.assertNotIn(root + 'Unregistered.kt', selected)

    def test_python_producer_keeps_legacy_repo_argument_when_live_root_is_set(self):
        f = GraphFixture(self)
        f.put(f.legacy, 'marker', 'legacy')
        f.put(f.live, 'marker', 'live')
        f.put(f.legacy, 'desktop/tools/observe.py',
              'import sys\nfrom pathlib import Path\nroot = Path(sys.argv[1])\n(root/"observed").write_bytes((root/"marker").read_bytes())\n')
        task = gate.Task('observe', 'tools/observe.py',
                         [('python', ''), ('literal', 'tools/observe.py'), ('repo', '')], [], None)
        result, = gate.run_producers(f.legacy, [task], {})
        self.assertEqual(0, result['returncode'])
        self.assertEqual('legacy', (f.legacy / 'observed').read_text())

    def test_actual_writes_trace_undeclared_sibling_output_and_stale_files_are_rejected(self):
        f = GraphFixture(self)
        relative = 'generated/original-video-interaction-retry-tests/Fixture.kt'
        output = f.put(f.legacy, 'desktop/build/' + relative, 'class Stale')
        with self.assertRaisesRegex(gate.ToolError, 'Unmodelled producer for compiled source'):
            f.collect()
        f.put(f.legacy, 'desktop/tools/emit.py',
              'from pathlib import Path\nPath(' + repr(str(output)) + ').write_text("class Fresh")\n')
        task = gate.Task('emitSibling', 'tools/emit.py',
                         [('python', ''), ('literal', 'tools/emit.py')], [], None)
        f.model.tasks[task.name] = task
        result, = gate.run_producers(f.legacy, [task], {})
        self.assertEqual(0, result['returncode'])
        self.assertIn(relative, result['writtenSources'])
        f.collect()
        trace = next(row for row in f.model.source_files if row['file'] == 'build/' + relative)
        self.assertEqual(['emitSibling'], trace['producers'])


class KotlinSourceAdapterTests(unittest.TestCase):
    def test_transition_adapter_uses_current_lf_helper_and_rejects_unknown_producer(self):
        f = GraphFixture(self)
        relative = 'api/fixture/Transition.kt'
        f.put(f.legacy, 'desktop/build/generated/' + relative, 'old\r\n')
        patch = '--- a\r\n+++ b\r\n@@ -1 +1 @@\r\n-old\r\n+new\r\n'
        f.put(f.legacy, 'desktop/upstream-core/transition/patches/' + relative + '.patch', patch)
        output = f.legacy / 'desktop/build' / f.model.task_outputs['applyTransitionPatches']
        gate.materialize_transition(f.legacy, output)
        self.assertTrue((output / 'fixture/Transition.kt').read_bytes().endswith(b'new\n'))
        self.assertNotIn(b'\r', (output / 'fixture/Transition.kt').read_bytes())
        changed = GRADLE.replace('"api" to "extractUpstreamApi"', '"api" to "unknownProducer"')
        f.put(f.legacy, 'desktop/build.gradle.kts', changed)
        with self.assertRaisesRegex(gate.ToolError, 'Unknown transition producer task'):
            gate.collect_sources(f.legacy, gate.parse_gradle(changed))
        f.put(f.legacy, 'desktop/upstream-core/transition/patches/unknown/Fixture.kt.patch', patch)
        with self.assertRaisesRegex(gate.ToolError, 'without a known producer task'):
            f.collect()

    def test_patch_requires_exactly_one_hunk_match_and_normalizes_crlf(self):
        patch = '--- a\n+++ b\n@@ -1 +1 @@\n-old\n+new\n'
        self.assertEqual('new\n', gate._unified_patch('old\r\n', patch, 'fixture'))
        for original in ('absent\n', 'old\nold\n'):
            with self.assertRaises(gate.ToolError):
                gate._unified_patch(original, patch, 'fixture')

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
        self.assertEqual([output / relative], [s.real for s in selected])
        trace = next(s for s in f.model.source_files if s['file'].endswith(relative))
        self.assertEqual(['applyDesktopUpstreamPatches'], trace['producers'])

    def test_test_correction_rejects_an_edit_outside_the_pinned_hunk(self):
        f = GraphFixture(self)
        corrected = gate._gradle_collection(GRADLE, 'correctedUpstreamTestPaths')
        for relative in corrected:
            original = 'package fixture\nval expected = 1\n'
            f.put(f.live, relative, original)
            digest = hashlib.sha256(original.encode()).hexdigest()
            f.put(f.legacy, 'desktop/upstream-core/patches/' + relative + '.patch',
                  'Upstream-source-sha256: ' + digest + '\n--- a\n+++ b\n@@ -2 +2 @@\n-val expected = 1\n+val expected = 2\n')
        output = f.adapter('applyUpstreamTestCorrections')
        self.assertIn('expected = 2', (output / corrected[0]).read_text())
        f.put(f.live, corrected[0], 'package changedOutsideHunk\nval expected = 1\n')
        with self.assertRaisesRegex(gate.ToolError, 'digest changed'):
            f.adapter('applyUpstreamTestCorrections')

    def test_resource_and_buildconfig_sources_expose_the_real_declared_names(self):
        f = GraphFixture(self)
        f.put(f.live, 'app/src/main/res/values/strings.xml', '<resources><string name="hello">Hello</string></resources>')
        f.put(f.live, 'app/src/main/res/drawable/icon.xml', '<vector/>')
        strings = f.adapter('generateUpstreamStringResources')
        r = (strings / 'kotlin/com/android/purebilibili/R.kt').read_text()
        self.assertIn('const val hello:', r)
        self.assertIn('const val icon:', r)
        config = f.adapter('generateUpstreamBuildConfig')
        text = (config / 'com/android/purebilibili/BuildConfig.kt').read_text()
        self.assertIn('VERSION_CODE: Int = 42', text)
        self.assertIn('VERSION_NAME: String = "1.2.3"', text)

    def test_new_resource_qualifier_and_dynamic_numeric_preference_are_rejected(self):
        f = GraphFixture(self)
        f.put(f.live, 'app/src/main/res/values-fr/strings.xml', '<resources/>')
        with self.assertRaisesRegex(gate.ToolError, 'qualifier'):
            f.adapter('generateUpstreamStringResources')
        f.source('main', 'core/fixture/Preference.kt', 'val key = intPreferencesKey(dynamicName)')
        with self.assertRaisesRegex(gate.ToolError, 'built at runtime'):
            f.adapter('generateUpstreamPreferenceKeyTypes')

    def test_adapter_refuses_outputs_outside_declared_scratch_directory(self):
        f = GraphFixture(self)
        with self.assertRaisesRegex(gate.ToolError, 'declared output'):
            gate.materialize_kotlin(f.legacy, 'generateUpstreamBuildConfig', f.live)
        (f.legacy / '.git/objects/info/alternates').unlink()
        with self.assertRaisesRegex(gate.ToolError, 'scratch mirror'):
            f.adapter('generateUpstreamBuildConfig')

    def test_drawable_alias_qualifiers_are_guarded_like_regular_drawables(self):
        f = GraphFixture(self)
        f.put(f.live, 'app/src/main/res/values-fr/aliases.xml',
              '<resources><item type="drawable" name="alias">@drawable/icon</item></resources>')
        with self.assertRaisesRegex(gate.ToolError, 'qualifier'):
            f.adapter('generateUpstreamDrawableResources')


if __name__ == '__main__':
    unittest.main()
