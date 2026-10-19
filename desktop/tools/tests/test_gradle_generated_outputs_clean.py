"""Exec producers clear their generated output directory before they run (incremental builds must not keep a retired file).

The hook deletes every directory an Exec task declares under build/generated, so two tasks must never share or nest
output directories: the second one to run would delete what the first just wrote. The scan below pins that.
"""
import re
import unittest
from collections import defaultdict
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
BUILD = (REPO / 'desktop/build.gradle.kts').read_text(encoding='utf8')
EXEMPT = {'prepareJsWorker', 'prepareNativeDiagnosticShare', 'fetchJsWorkerJdk'}


def exec_tasks():
    starts = [(m.start(), m.group(1)) for m in re.finditer(r'val (\w+) by tasks\.registering\(Exec::class\)', BUILD)]
    for index, (start, name) in enumerate(starts):
        end = starts[index + 1][0] if index + 1 < len(starts) else len(BUILD)
        yield name, BUILD[start:end]


class GeneratedOutputsAreRebuiltFromNothing(unittest.TestCase):
    def test_the_clean_hook_exists_once_and_exempts_only_the_toolchain_producers(self):
        self.assertEqual(1, BUILD.count('tasks.withType<Exec>().configureEach {'))
        hook = BUILD[BUILD.index('val generatedSourceRoot ='):]
        self.assertIn('layout.buildDirectory.dir("generated")', hook)
        for name in EXEMPT:
            self.assertIn('"%s"' % name, hook)
        self.assertIn('doFirst {', hook)
        self.assertIn('deleteRecursively()', hook)
        self.assertIn('it != generatedSourceRoot', hook)
        self.assertIn('it.startsWith(generatedSourceRoot)', hook)

    def test_exec_tasks_never_share_or_nest_a_generated_output_directory(self):
        owners = defaultdict(list)
        for name, body in exec_tasks():
            if name in EXEMPT:
                continue
            for match in re.finditer(r'outputs\.dir\(layout\.buildDirectory\.dir\("(generated/[^"]+)"\)\)', body):
                owners[match.group(1).rstrip('/')].append(name)
        self.assertGreater(len(owners), 100)
        self.assertEqual({}, {path: names for path, names in owners.items() if len(names) > 1})
        paths = sorted(owners)
        nested = [(a, b) for a in paths for b in paths if a != b and b.startswith(a + '/')]
        self.assertEqual([], nested)

    def test_no_exec_task_writes_into_the_generated_root_itself(self):
        for name, body in exec_tasks():
            self.assertNotRegex(body, r'outputs\.dir\(layout\.buildDirectory\.dir\("generated"\)\)', name)


if __name__ == '__main__':
    unittest.main()
