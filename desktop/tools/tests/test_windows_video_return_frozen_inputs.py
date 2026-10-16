"""Whole pinned original renderer + actual production extraction seam; no Kotlin execution."""
from pathlib import Path
import importlib.util
import json
import os
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
TOOLS = ROOT / 'desktop/tools'
# A sparse Root preparation may read unchanged canonical/dependency bytes from
# the accepted checkout. CI uses its complete immutable checkout by default.
SOURCE_REPO = Path(os.environ.get('BILIPAI_RENDERER_SOURCE_REPO', str(ROOT))).resolve()
sys.path.insert(0, str(SOURCE_REPO / 'desktop/tools'))
sys.path.insert(0, str(TOOLS))
import windows_video_return_frozen_inputs as binding


def load_producer():
    spec = importlib.util.spec_from_file_location('actual_return_holder_producer',
        TOOLS / 'extract-upstream-video-detail-holder.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class FrozenReturnRendererTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.producer = load_producer()
        cls.recipe = next(row for row in cls.producer.SPECS if row['output'] == binding.OUTPUT)
        path = cls.producer._desktop_canonical_source(SOURCE_REPO, cls.recipe['source'])
        cls.canonical = Path(path).read_bytes().decode('utf-8').replace('\r\n', '\n')
        if binding.sha(cls.canonical) != cls.recipe['sourceSHA256LF']:
            raise AssertionError('Actual canonical renderer input differs')
        cls.previous = ''.join(cls.canonical[op[1]:op[2]] if op[0] == 'copy' else op[1]
            for op in cls.recipe['operations'])
        if binding.sha(cls.previous) != cls.recipe['outputSHA256LF']:
            raise AssertionError('Actual previous whole renderer differs')

    def test_complete_renderer_body_is_reversible_not_reconstructed(self):
        emitted, proof = binding.apply(binding.OUTPUT, self.previous)
        self.assertEqual(self.previous, binding.restore(emitted, proof['adaptations']))
        self.assertEqual(binding.sha(emitted), proof['emittedSHA256LF'])
        self.assertTrue(proof['completePreviousRendererInverse'])
        self.assertFalse(proof['actualRootVisualHostVerified'])
        self.assertFalse(proof['uiExecuted'])

    def test_changed_original_body_is_rejected_before_any_generation(self):
        with self.assertRaisesRegex(ValueError, 'identity changed'):
            binding.apply(binding.OUTPUT, self.previous + '\n')

    def test_other_whole_original_holder_sources_are_not_modified(self):
        text = 'not the target renderer\n'
        self.assertEqual((text, None), binding.apply('other/Original.kt', text))

    def test_modified_bound_parameter_is_rejected_by_the_inverse(self):
        emitted, proof = binding.apply(binding.OUTPUT, self.previous)
        changed = emitted.replace(binding.PARAMETER, binding.PARAMETER.replace('= null', '= broken'), 1)
        self.assertNotEqual(emitted, changed)
        with self.assertRaisesRegex(ValueError, 'inverse'):
            binding.restore(changed, proof['adaptations'])

    def test_modified_untouched_business_body_is_not_hidden_by_inverse_proof(self):
        emitted, proof = binding.apply(binding.OUTPUT, self.previous)
        changed = emitted + '// outside every binding\n'
        with self.assertRaisesRegex(ValueError, 'remaining original bytes'):
            binding.restore(changed, proof['adaptations'])

    def test_actual_production_generator_emits_the_whole_bound_renderer_and_proof(self):
        specs = self.producer.SPECS
        try:
            # Execute the real generator, restricting only its unrelated source inventory.
            self.producer.SPECS = [self.recipe]
            with tempfile.TemporaryDirectory(prefix='bilipai-return-renderer-') as directory:
                output = Path(directory)
                paths = self.producer.generate(SOURCE_REPO, output)
                self.assertEqual(1, len(paths))
                actual = (output / binding.OUTPUT).read_text(encoding='utf-8')
                expected, proof = binding.apply(binding.OUTPUT, self.previous)
                self.assertEqual(expected, actual)
                saved = json.loads((output / (Path(binding.OUTPUT).name +
                    '.windows-return-frozen-proof.json')).read_bytes())
                self.assertEqual(proof, saved)
                self.assertEqual(self.previous, binding.restore(actual, saved['adaptations']))
        finally:
            self.producer.SPECS = specs


if __name__ == '__main__':
    unittest.main()
