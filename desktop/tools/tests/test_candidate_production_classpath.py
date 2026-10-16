from pathlib import Path
import copy
import importlib.util
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import original_root_ui_runtime_binding as binding
from test_original_root_ui_runtime_binding import Fixture, jar, pin

spec = importlib.util.spec_from_file_location('candidate_classpath',
    Path(__file__).resolve().parents[1] / 'canonicalize-candidate-classpath.py')
tool = importlib.util.module_from_spec(spec); spec.loader.exec_module(tool)
SOURCE = 'a' * 40


class CandidateProductionClasspathTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='bilipai-production-classpath-')
        self.fixture = Fixture(Path(self.temp.name))
        self.cfg = self.fixture.app / 'BiliPai Windows.cfg'
        self.model = copy.deepcopy(self.fixture.model)
        native = self.model['mainArtifacts'][1]
        self.model['mainRuntimeClasspath'][-1] = native['file'] = str(self.fixture.native)
        self.model.update(schema=1, sourceSha=SOURCE, javaExecExecuted=False,
                          applicationExecuted=False, hardwareExecuted=False)

    def tearDown(self):
        self.temp.cleanup()

    def run_tool(self):
        return tool.canonicalize(self.fixture.root, self.model, SOURCE)

    def add_host(self, name, data):
        path = self.fixture.app / name
        jar(path, {'example/Duplicate.class': data})
        self.cfg.write_text(self.cfg.read_text().replace('[JavaOptions]',
            'app.classpath=$APPDIR\\' + name + '\n[JavaOptions]'))
        return dict(file='gradle/' + name, group='example', name=name, version='1', **pin(path.read_bytes()))

    def test_js_worker_and_engine_resources_leave_host_classpath_but_remain_intact(self):
        resources = self.fixture.app / 'resources'
        worker = resources / 'js-engine/worker.jar'; worker.parent.mkdir()
        jar(worker, {'example/Dependency.class': b'worker-private engine'})
        before = worker.read_bytes()
        self.fixture.ledger['applicationResources']['js-engine/worker.jar'] = pin(before)
        self.cfg.write_text(self.cfg.read_text().replace('[JavaOptions]',
            'app.classpath=$APPDIR\\resources\\js-engine\\worker.jar\n[JavaOptions]'))
        value = self.run_tool()
        self.assertTrue(value['actualGradleMainRuntimeBound'])
        self.assertEqual(['resources/js-engine/worker.jar'], [r['path'] for r in value['excludedResourceJars']])
        self.assertEqual(before, worker.read_bytes())
        self.assertNotIn('resources/js-engine/worker.jar', binding.jars.inventory(self.fixture.root)['model']['orderedClasspath'])
        binding._verify_production(self.fixture.root, self.model, self.fixture.ledger, set(), self.fixture.native)
        self.assertFalse(value['sourceQualified']); self.assertFalse(value['applicationExecuted'])

    def test_actual_gradle_order_restores_the_intended_duplicate_class_winner(self):
        b = self.add_host('b.jar', b'packager winner')
        a = self.add_host('a.jar', b'Gradle winner')
        self.model['mainArtifacts'] += [a, b]
        self.model['mainRuntimeClasspath'] += [a['file'], b['file']]
        value = self.run_tool()
        self.assertLess(value['orderedProductionClasspath'].index('a.jar'), value['orderedProductionClasspath'].index('b.jar'))
        self.assertEqual(('a.jar', 'example/Duplicate.class'),
            binding._winning_classes(self.fixture.root, value['orderedProductionClasspath'])['example/Duplicate'])

    def test_unknown_host_jar_is_rejected_without_changing_launcher(self):
        self.add_host('unknown.jar', b'not a Gradle dependency')
        before = self.cfg.read_bytes()
        with self.assertRaisesRegex(ValueError, 'dependency bytes differ'): self.run_tool()
        self.assertEqual(before, self.cfg.read_bytes())

    def test_undeclared_physical_host_jar_is_rejected(self):
        jar(self.fixture.app / 'undeclared.jar', {'example/Hidden.class': b'hidden'})
        with self.assertRaisesRegex(ValueError, 'omitted a packaged host JAR'): self.run_tool()

    def test_changed_native_projection_is_rejected_before_launcher_mutation(self):
        (self.fixture.app / 'icudtl.dat').write_bytes(b'corrupt native data')
        before = self.cfg.read_bytes()
        with self.assertRaisesRegex(ValueError, 'native asset differs'): self.run_tool()
        self.assertEqual(before, self.cfg.read_bytes())

    def test_native_original_must_have_its_actual_resolved_artifact_identity(self):
        self.model['mainArtifacts'][1]['sha256'] = '0' * 64
        with self.assertRaisesRegex(ValueError, 'Skiko artifact bytes differ'): self.run_tool()

    def test_source_identity_mismatch_cannot_rewrite_launcher(self):
        self.model['sourceSha'] = 'b' * 40
        before = self.cfg.read_bytes()
        with self.assertRaisesRegex(ValueError, 'another source'): self.run_tool()
        self.assertEqual(before, self.cfg.read_bytes())

    def test_unknown_main_runtime_entry_is_rejected(self):
        self.model['mainRuntimeClasspath'].append('gradle/unbound-library.jar')
        with self.assertRaisesRegex(ValueError, 'Unbound production runtime path'): self.run_tool()

    def test_resolved_artifact_absent_from_actual_runtime_is_rejected(self):
        self.model['mainRuntimeClasspath'].remove('ci/dependency.jar')
        with self.assertRaisesRegex(ValueError, 'does not cover every'): self.run_tool()

    def test_process_execution_cannot_be_reported_as_a_nonexecuting_snapshot(self):
        self.model['javaExecExecuted'] = True
        with self.assertRaisesRegex(ValueError, 'nonexecuting Gradle snapshot'): self.run_tool()

    def test_correction_is_idempotent_and_preserves_nonclasspath_options_and_crlf(self):
        raw = self.cfg.read_bytes().replace(b'\r\n', b'\n').replace(b'\n', b'\r\n')
        self.cfg.write_bytes(raw)
        value = self.run_tool(); first = self.cfg.read_bytes()
        self.assertEqual(raw, first)
        self.assertEqual(value['cfgSHA256'], self.run_tool()['cfgSHA256'])
        self.assertNotIn(b'\n', first.replace(b'\r\n', b''))


if __name__ == '__main__':
    unittest.main()
