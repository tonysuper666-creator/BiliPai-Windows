from pathlib import Path
import importlib.util
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location('subtitle_policy_extractor', ROOT / 'desktop/tools/extract-subtitle-load-policy.py')
extractor = importlib.util.module_from_spec(spec)
spec.loader.exec_module(extractor)


class SubtitleLoadExtractionTest(unittest.TestCase):
    def test_original_function_and_data_bodies_survive_exactly(self):
        selector, parser = extractor.selectors(ROOT)
        source = extractor.read(ROOT)
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            extractor.generate(ROOT, output)
            result = (output / 'com/android/purebilibili/feature/video/viewmodel/SubtitleTrackLoadPolicy.kt').read_text(encoding='utf-8')
        self.assertEqual(selector.data_class(source, 'SubtitleTrackLoadDecision', parser),
                         selector.data_class(result, 'SubtitleTrackLoadDecision', parser))
        for name in extractor.FUNCTIONS:
            self.assertEqual(selector.function(source, name, parser), selector.function(result, name, parser))
        self.assertNotIn('androidx.lifecycle', result)
        self.assertNotIn('VideoPlaybackViewModel(', result)

    def test_ambiguous_original_function_fails_instead_of_picking_a_body(self):
        selector, parser = extractor.selectors(ROOT)
        source = extractor.read(ROOT)
        name = extractor.FUNCTIONS[0]
        duplicated = source + '\n' + selector.function(source, name, parser)
        with self.assertRaises(ValueError):
            selector.function(duplicated, name, parser)


if __name__ == '__main__':
    unittest.main()
