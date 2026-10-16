"""Offline admission tests with structural byte fixtures, never compiled/runnable app evidence."""
from contextlib import contextmanager
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import patch
import copy
import hashlib
import importlib.util
import json
import os
import subprocess
import sys
import time
import unittest
import zipfile
TOOLS = Path(__file__).resolve().parent
if not (TOOLS / 'original_root_ui_class_closure.py').is_file(): TOOLS = TOOLS.parent
sys.path.insert(0, str(TOOLS))
import original_root_ui_class_closure as classes
import original_root_ui_candidate_classpath as jars
from test_original_root_ui_class_closure import compiled

spec = importlib.util.spec_from_file_location('ui_exporter_under_test', TOOLS / 'export-original-root-ui-harness.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class ExportAdmissionTests(unittest.TestCase):
    def setUp(self):
        self.temp = TemporaryDirectory(prefix='bp-ui-boundary-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.repo = self.root / 'source'
        self.repo.mkdir()
        self.started = time.time() - 1
        self.plan = dict(repository=module.REPOSITORY, exportOriginalRootUiHarness=True, windowsVersion='0.0.1')
        self.put('.github/windows-source-validation-plan.json', json.dumps(self.plan).encode())
        self.put('desktop/build.gradle.kts', b'// Format fixture only; never compiled.\n')
        self.canonical = ['desktop/src/test/kotlin/sample/Fixture.kt', 'desktop/src/test/java/sample/Other.java']
        for name in self.canonical: self.put(name, b'// Format fixture only.\n')
        self.git('init', '--quiet')
        self.git('add', '.')
        self.git('-c', 'user.name=Boundary Fixture', '-c', 'user.email=fixture@invalid.local',
                 '-c', 'commit.gpgsign=false', 'commit', '--quiet', '-m', 'Structural admission fixture')
        self.sha = self.git('rev-parse', 'HEAD').decode().strip()
        self.main = self.repo / 'desktop/build/classes/kotlin/main'
        self.test = self.repo / 'desktop/build/classes/kotlin/test'
        self.main_class = self.put('desktop/build/classes/kotlin/main/' + jars.MAIN,
                                  compiled(jars.MAIN[:-6], 'Main.kt'))
        self.fixture = self.put('desktop/build/classes/kotlin/test/' + classes.ENTRY + '.class',
                               compiled(classes.ENTRY, 'WindowsVideoActualRootUiFixture.kt'))
        self.metadata = self.put('desktop/build/classes/kotlin/test/META-INF/desktop_test.kotlin_module', b'metadata-fixture')
        self.put('desktop/build/resources/main/windows-update.json', b'{"version":"0.0.1"}')
        self.resource = self.put('desktop/resources/common/brand.txt', b'application resource')
        self.java = self.root / 'jdk/bin/java.exe'
        self.java.parent.mkdir(parents=True)
        self.java.write_bytes(b'not an executable; never launched')
        release = self.java.parent.parent / 'release'
        release.write_bytes(b'JAVA_VERSION="21.0.12.1"\n')
        self.cache = self.root / 'gradle-home'
        self.library = self.make_jar(self.cache / 'caches/modules-2/files-2.1/sample/runtime/1/hash/runtime.jar', 'sample/Library.class')
        self.extra = self.make_jar(self.cache / 'caches/modules-2/files-2.1/sample/test-api/1/hash/test-api.jar', 'sample/TestApi.class')
        def row(path, role):
            return dict(path=str(path), role=role, kind='file' if path.is_file() else 'directory' if path.is_dir() else 'missing',
                        **(jars.file_digest(path) if path.is_file() else {}))
        classpath = [row(self.test, 'testClasses'),
            row(self.repo / 'desktop/build/classes/java/test', 'testClasses'),
            row(self.repo / 'desktop/build/resources/test', 'testResources'),
            row(self.main, 'mainClasses'),
            row(self.repo / 'desktop/build/classes/java/main', 'mainClasses'),
            row(self.repo / 'desktop/build/resources/main', 'mainResources'),
            row(self.library, 'mainLibrary'), row(self.extra, 'testLibrary')]
        self.model = dict(schema=1, sourceSha=self.sha,
            planSha256=jars.file_digest(self.repo / '.github/windows-source-validation-plan.json')['sha256'],
            task='windowsVideoLocalReplayUiSmoke', mainClass=classes.ENTRY.replace('/', '.'),
            classpath=classpath, mainRuntimeClasspath=[item['path'] for item in classpath if item['role'].startswith('main')],
            mainClassRoots=[str(self.main), str(self.repo / 'desktop/build/classes/java/main')],
            testClassRoots=[str(self.test), str(self.repo / 'desktop/build/classes/java/test')],
            testSources=[self.source_row(name) for name in self.canonical],
            mainArtifacts=[self.artifact(self.library, 'runtime')],
            testArtifacts=[self.artifact(self.library, 'runtime'), self.artifact(self.extra, 'test-api')],
            gradleUserHome=str(self.cache), systemProperties={'compose.application.resources.dir': str(self.repo / 'desktop/resources/common'),
                'file.encoding': 'UTF-8', 'bilipai.js.workerResources': 'not-executed'}, jvmArgs=[],
            jdk=dict(executable=str(self.java), releaseSha256=jars.file_digest(release)['sha256']),
            compiler=dict(declaredKotlinJvmPlugin='2.4.0', buildScriptSha256=jars.file_digest(self.repo / 'desktop/build.gradle.kts')['sha256'], gradleVersion='fixture'),
            javaExecExecuted=False, applicationExecuted=False, hardwareExecuted=False)

    def put(self, name, raw):
        path = self.repo / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(raw)
        return path

    def git(self, *args):
        return subprocess.run(['git', '-c', 'core.autocrlf=false', '-C', str(self.repo), *args],
                              check=True, capture_output=True).stdout

    def make_jar(self, path, name):
        path.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(path, 'w') as archive: archive.writestr(name, b'format fixture')
        return path

    def artifact(self, path, name):
        return dict(group='sample', name=name, version='1', classifier='', type='jar', file=str(path), **jars.file_digest(path))

    def source_row(self, name):
        raw = (self.repo / name).read_bytes().replace(b'\r\n', b'\n')
        return dict(path=name, bytesLf=len(raw), normalization='lf', sha256=hashlib.sha256(raw).hexdigest())

    def run_export(self):
        return module.export(self.repo, self.model, self.root / 'export', self.sha, self.started)

    def reject(self, message):
        with self.assertRaisesRegex(ValueError, message): self.run_export()

    def test_valid_structural_export_contains_no_production_classes_or_acceptance_claim(self):
        result = self.run_export()
        self.assertFalse(result['productionClassesExported'])
        self.assertTrue(result['productionRuntimeBindingRequired'])
        for key in ('applicationExecuted', 'hardwareExecuted', 'releaseQualified'): self.assertIs(result[key], False)
        with zipfile.ZipFile(self.root / 'export/ui-fixture.jar') as archive:
            self.assertEqual(set(archive.namelist()), {classes.ENTRY + '.class', 'META-INF/desktop_test.kotlin_module'})
        self.assertEqual((self.root / 'export/lib/00-test-api.jar').read_bytes(), self.extra.read_bytes())
        for row in result['payload']: self.assertEqual(jars.file_digest(self.root / 'export' / row['path']), {key: row[key] for key in ('bytes', 'sha256')})

    def test_wrong_source_is_rejected(self):
        self.model['sourceSha'] = '0' * 40
        self.reject('source changed')

    def test_old_compile_boundary_is_rejected(self):
        self.started = time.time() - 3601
        self.reject('Fresh compilation')

    def test_stale_main_class_is_rejected(self):
        os.utime(self.main_class, (self.started - 10, self.started - 10))
        self.reject('Stale production')

    def test_stale_fixture_class_is_rejected(self):
        os.utime(self.fixture, (self.started - 10, self.started - 10))
        self.reject('Stale UI fixture')

    def test_canonical_edit_cannot_be_authenticated_by_a_new_model_hash(self):
        self.put(self.canonical[0], b'// changed after HEAD\n')
        self.model['testSources'][0] = self.source_row(self.canonical[0])
        self.reject('differs from HEAD')

    def test_missing_canonical_source_is_rejected(self):
        self.model['testSources'].pop()
        self.reject('inventory differs from HEAD')

    def test_crlf_canonical_working_copy_matches_lf_git_blob(self):
        path = self.repo / self.canonical[0]
        path.write_bytes(path.read_bytes().replace(b'\n', b'\r\n'))
        self.run_export()

    def test_duplicate_test_source_is_rejected(self):
        self.model['testSources'].append(copy.deepcopy(self.model['testSources'][0]))
        self.reject('Duplicate or unnormalized')

    def test_generated_test_source_is_retained(self):
        name = 'desktop/build/generated/upstream-test/sample/Generated.kt'
        self.put(name, b'// generated format fixture\n')
        self.model['testSources'].append(self.source_row(name))
        self.run_export()
        sources = json.loads((self.root / 'export/test-sources.json').read_bytes())
        self.assertIn(name, {row['path'] for row in sources})

    def test_foreign_classpath_output_cannot_differ_from_ledger_roots(self):
        row = next(row for row in self.model['classpath'] if row['role'] == 'mainClasses')
        row['path'] = str(self.root / 'old-main-output')
        row['kind'] = 'missing'
        self.reject('escaped its actual source set')

    def test_omitted_actual_java_output_is_rejected(self):
        self.model['classpath'] = [row for row in self.model['classpath'] if row['path'] != str(self.repo / 'desktop/build/classes/java/main')]
        self.reject('omitted a declared')

    def test_wrong_classpath_kind_is_rejected(self):
        self.model['classpath'][0]['kind'] = 'missing'
        self.reject('kind changed')

    def test_removed_main_runtime_path_is_rejected(self):
        self.model['mainRuntimeClasspath'].pop()
        self.reject('production runtime differs')

    def test_artifact_graph_hash_cannot_disagree_with_actual_classpath(self):
        self.model['mainArtifacts'][0]['sha256'] = '0' * 64
        self.reject('Resolved artifact differs')

    def test_test_graph_cannot_upgrade_production_runtime(self):
        self.model['testArtifacts'][0]['version'] = '2'
        self.reject('replaced a production runtime')

    def test_duplicate_artifact_identity_is_rejected(self):
        self.model['testArtifacts'].append(copy.deepcopy(self.model['testArtifacts'][0]))
        self.reject('Resolved artifact inventory|Duplicate runtime artifact')

    def test_changed_library_bytes_are_rejected(self):
        self.extra.write_bytes(b'changed')
        self.reject('runtime library changed')

    def test_unsupported_compiled_metadata_is_rejected(self):
        self.put('desktop/build/classes/kotlin/test/foreign.txt', b'not compiler metadata')
        self.reject('Unexpected compiled test metadata')

    def test_unrelated_test_class_is_not_exported(self):
        self.put('desktop/build/classes/kotlin/test/sample/OtherTest.class', compiled('sample/OtherTest', 'OtherTest.kt'))
        result = self.run_export()
        self.assertNotIn('sample/OtherTest.class', {row['path'] for row in result['classes']})

    def test_test_resources_cannot_shadow_production_resources(self):
        self.put('desktop/build/resources/test/windows-update.json', b'{"version":"wrong"}')
        next(row for row in self.model['classpath'] if row['role'] == 'testResources')['kind'] = 'directory'
        self.reject('shadow production')

    def test_wrong_generated_production_version_is_rejected(self):
        self.put('desktop/build/resources/main/windows-update.json', b'{"version":"wrong"}')
        self.reject('Windows version differs')

    def test_output_directory_cannot_be_overwritten(self):
        (self.root / 'export').mkdir()
        self.reject('destination must be new')

    def mutate_during_dependency_copy(self, path):
        original = jars.jar_snapshot
        @contextmanager
        def mutated(candidate):
            with original(candidate) as value:
                yield value
                path.write_bytes(path.read_bytes() + b'changed-during-export')
        return patch.object(jars, 'jar_snapshot', mutated)

    def test_production_class_mutation_during_export_is_rejected(self):
        with self.mutate_during_dependency_copy(self.main_class):
            self.reject('production output/resources changed')

    def test_application_resource_mutation_during_export_is_rejected(self):
        with self.mutate_during_dependency_copy(self.resource):
            self.reject('production output/resources changed')

    def test_test_metadata_mutation_after_copy_is_rejected(self):
        with self.mutate_during_dependency_copy(self.metadata):
            self.reject('Test metadata/resources changed')


if __name__ == '__main__': unittest.main(verbosity=2)
