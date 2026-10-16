"""Real preflight and main orchestration with subprocesses mocked; no build/network."""
from pathlib import Path
import importlib.util
import json
import os
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location('actual_cares_prefetch_producer', ROOT / 'desktop/tools/native/build-mpv-rtx-core-runtime.py')
producer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(producer)
STAMP = producer.CARES_DOWNLOAD_STAMP


class CaresDownloadPrefetchTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='bp-cares-prefetch-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.build = self.root / 'build-x64'
        self.sources = self.root / 'sources'
        self.output = self.root / 'output'
        self.build.mkdir()
        self.output.mkdir()
        (self.sources / 'c-ares/.git').mkdir(parents=True)
        self.log = self.output / 'native-build.log'
        self.graph = self.build / 'build.ninja'
        self.valid_graph = ('build ' + STAMP + ' | $\n  ${cmake_ninja_workdir}' + STAMP + ': CUSTOM_COMMAND mkdir-stamp\n'
                            '  COMMAND = cmake -P c-ares-download-.cmake\n')
        self.graph.write_text(self.valid_graph, encoding='utf-8')
        self.receipt = self.output / 'c-ares-download-preflight.json'
        self.values = ['a' * 40 + '\n', 'b' * 40 + '\n', 'https://github.com/c-ares/c-ares.git\n', '']

    def invoke(self, values=None):
        with patch.object(producer, 'run') as run, patch.object(producer.subprocess, 'check_output', side_effect=self.values if values is None else values) as git:
            result = producer.prefetch_cares_source(self.build, self.sources, self.output, self.log)
        return result, run, git

    def test_real_relative_stamp_with_absolute_alias_records_observed_source(self):
        result, run, git = self.invoke()
        run.assert_called_once_with(['ninja', '-C', str(self.build), '-j1', STAMP], self.log)
        self.assertEqual(result, json.loads(self.receipt.read_bytes()))
        self.assertEqual(producer.sha(self.graph.read_bytes()), result['configuredBuildGraphSha256'])
        self.assertEqual('a' * 40, result['sourceCommit'])
        self.assertEqual('b' * 40, result['sourceTree'])
        self.assertTrue(result['floatingDependency'])
        self.assertFalse(result['nativeBuildSucceeded'])
        self.assertFalse(result['hostCompilerBuilt'])
        self.assertEqual(['rev-parse', '--verify', 'HEAD^{commit}'], git.call_args_list[0].args[0][-3:])

    def test_missing_graph_never_starts_ninja(self):
        self.graph.unlink()
        with patch.object(producer, 'run') as run, self.assertRaisesRegex(RuntimeError, 'graph is unavailable'):
            producer.prefetch_cares_source(self.build, self.sources, self.output, self.log)
        run.assert_not_called()

    def test_comments_commands_logs_partial_names_and_escaped_tokens_are_not_outputs(self):
        impostors = [
            '# build ' + STAMP + ': CUSTOM_COMMAND\n',
            '  COMMAND = build ' + STAMP + ': CUSTOM_COMMAND\n',
            'build ' + STAMP + '-err.log: CUSTOM_COMMAND\n',
            'build prefix-' + STAMP + ': CUSTOM_COMMAND\n',
            'build glued$ ' + STAMP + ': CUSTOM_COMMAND\n',
            'build glued$\n  ' + STAMP + ': CUSTOM_COMMAND\n',
            'build ${alias}' + STAMP + ': CUSTOM_COMMAND\n',
            'build unrelated: CUSTOM_COMMAND ' + STAMP + '\n',
        ]
        for text in impostors:
            with self.subTest(graph=text):
                self.graph.write_text(text, encoding='utf-8')
                with patch.object(producer, 'run') as run, self.assertRaisesRegex(RuntimeError, 'missing or ambiguous'):
                    producer.prefetch_cares_source(self.build, self.sources, self.output, self.log)
                run.assert_not_called()

    def test_duplicate_output_definition_is_rejected(self):
        self.graph.write_text(self.valid_graph * 2, encoding='utf-8')
        with patch.object(producer, 'run') as run, self.assertRaisesRegex(RuntimeError, 'ambiguous'):
            producer.prefetch_cares_source(self.build, self.sources, self.output, self.log)
        run.assert_not_called()

    def test_phony_alias_cannot_replace_the_actual_download_command(self):
        self.graph.write_text('build ' + STAMP + ': phony another-target\n', encoding='utf-8')
        with patch.object(producer, 'run') as run, self.assertRaisesRegex(RuntimeError, 'missing or ambiguous'):
            producer.prefetch_cares_source(self.build, self.sources, self.output, self.log)
        run.assert_not_called()

    def test_even_dollar_suffix_does_not_join_independent_build_statements(self):
        self.graph.write_text('build unrelated$$\n' + self.valid_graph, encoding='utf-8')
        result, _, _ = self.invoke()
        self.assertEqual('a' * 40, result['sourceCommit'])

    def test_unfinished_continuation_fails_before_any_command(self):
        for tail in ['build unfinished$', '$']:
            with self.subTest(tail=tail):
                self.graph.write_text(self.valid_graph + tail, encoding='utf-8')
                with patch.object(producer, 'run') as run, self.assertRaisesRegex(RuntimeError, 'unfinished continuation'):
                    producer.prefetch_cares_source(self.build, self.sources, self.output, self.log)
                run.assert_not_called()

    def test_download_failure_never_reads_git_or_writes_success_receipt(self):
        with patch.object(producer, 'run', side_effect=subprocess.CalledProcessError(1, ['ninja', STAMP])), patch.object(producer.subprocess, 'check_output') as git, self.assertRaises(subprocess.CalledProcessError):
            producer.prefetch_cares_source(self.build, self.sources, self.output, self.log)
        git.assert_not_called()
        self.assertFalse(self.receipt.exists())

    def test_missing_git_worktree_cannot_be_recorded_as_download_success(self):
        (self.sources / 'c-ares/.git').rmdir()
        with patch.object(producer, 'run'), self.assertRaisesRegex(RuntimeError, 'worktree is unavailable'):
            producer.prefetch_cares_source(self.build, self.sources, self.output, self.log)
        self.assertFalse(self.receipt.exists())

    def test_invalid_git_identity_remote_and_dirty_source_are_rejected(self):
        alternatives = [(0, 'short'), (1, 'c' * 39), (2, 'https://github.com/other/c-ares.git'), (3, ' M modified.c\n')]
        for index, value in alternatives:
            with self.subTest(field=index):
                values = list(self.values)
                values[index] = value
                with self.assertRaises(RuntimeError):
                    self.invoke(values)
                self.assertFalse(self.receipt.exists())


class ActualProducerDownloadFailureOrchestrationTest(unittest.TestCase):
    def run_main(self, valid_graph):
        temporary = tempfile.TemporaryDirectory(prefix='bp-main-cares-prefetch-')
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        workspace, output = root / 'workspace', root / 'output'
        commands = []

        def download(record, directory):
            path = directory / record['fileName']
            path.write_bytes(b'mocked download; not accepted source evidence')
            return path

        def extract(archive, directory, prefix):
            recipes = directory / prefix
            (recipes / 'packages').mkdir(parents=True)
            return recipes

        def run(command, log):
            commands.append(list(command))
            if command[0] == 'cmake':
                build = workspace / 'build-x64'
                build.mkdir()
                graph = 'build ' + STAMP + ': CUSTOM_COMMAND mkdir\n' if valid_graph else '# ' + STAMP + '\n'
                (build / 'build.ninja').write_text(graph, encoding='utf-8')
            elif command == ['ninja', '-C', str(workspace / 'build-x64'), '-j1', STAMP]:
                raise subprocess.CalledProcessError(1, command)
            else:
                raise AssertionError('No HOST/Rust/MPV command may run after the failed preflight')

        with patch.dict(os.environ, {'GITHUB_ACTIONS': 'false'}), patch.object(sys, 'argv', ['producer', '--workspace', str(workspace), '--output', str(output)]), patch.object(producer, 'download', side_effect=download), patch.object(producer, 'extract_fixed_recipe', side_effect=extract), patch.object(producer, 'apply_recipes'), patch.object(producer, 'run', side_effect=run), patch.object(producer, 'print_nested_build_failure_logs', return_value={'state': 'MOCKED_DIAGNOSTIC_COLLECTOR'}) as diagnostics:
            expected = subprocess.CalledProcessError if valid_graph else RuntimeError
            with self.assertRaises(expected):
                producer.main()
        status = json.loads((output / 'build-status.json').read_bytes())
        self.assertFalse(status['success'])
        self.assertFalse(status['binaryProduced'])
        self.assertFalse((output / 'c-ares-download-preflight.json').exists())
        return commands, diagnostics, status

    def test_actual_main_stops_at_download_and_retains_original_failure_status(self):
        commands, diagnostics, status = self.run_main(True)
        self.assertEqual(2, len(commands))
        self.assertEqual('cmake', commands[0][0])
        self.assertEqual(STAMP, commands[1][-1])
        self.assertEqual('-j1', commands[1][-2])
        self.assertEqual('CalledProcessError', status['errorType'])
        self.assertEqual({'state': 'MOCKED_DIAGNOSTIC_COLLECTOR'}, status['nestedBuildDiagnostics'])
        diagnostics.assert_called_once()

    def test_actual_main_rejects_false_graph_without_any_ninja_fallback(self):
        commands, diagnostics, status = self.run_main(False)
        self.assertEqual(['cmake'], [row[0] for row in commands])
        self.assertEqual('RuntimeError', status['errorType'])
        diagnostics.assert_not_called()


if __name__ == '__main__':
    unittest.main()
