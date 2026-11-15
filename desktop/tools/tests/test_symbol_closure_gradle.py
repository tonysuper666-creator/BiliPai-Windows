"""The legacy mirror's pin identity, exercised through the production mirror."""
from pathlib import Path
import subprocess
import tempfile
import unittest

import check_slice_symbol_closure as gate
from compiled_source_fixture import scratch_directory


class GradleSourceGraphTests(unittest.TestCase):
    def test_legacy_mirror_preserves_the_pin_identity_for_python_producers(self):
        with tempfile.TemporaryDirectory(prefix='bp-legacy-source-') as temporary:
            original = Path(temporary) / '.legacy-root'
            original.mkdir()
            subprocess.run(['git', 'init', '-q', str(original)], check=True)
            (original / 'desktop').mkdir()
            (original / 'desktop/pin.txt').write_text('legacy')
            with scratch_directory(original) as scratch:
                mirror, _ = gate.make_scratch_repo(original, scratch)
                self.assertEqual('.legacy-root', mirror.name)
                self.assertEqual('legacy', (mirror / 'desktop/pin.txt').read_text())
