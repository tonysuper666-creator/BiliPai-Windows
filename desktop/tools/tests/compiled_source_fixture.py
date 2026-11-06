"""Fresh, shared production compile inputs for migrated source ownership checks."""
from functools import lru_cache
from pathlib import Path
import tempfile

import check_slice_symbol_closure as gate


@lru_cache(maxsize=1)
def compiled_main():
    repo = Path(__file__).resolve().parents[3]
    with tempfile.TemporaryDirectory(prefix='bp-compiled-contract-') as scratch:
        root, environment = gate.make_scratch_repo(repo, scratch)
        model = gate.parse_gradle((root / 'desktop/build.gradle.kts').read_text(encoding='utf-8'))
        tasks, _skipped = gate.select_producers(model)
        results = gate.run_producers(root, tasks, environment)
        failed = [(row['task'], row['returncode'], row['stderrTail'])
                  for row in results if row['returncode']]
        if failed or len(results) <= 100:
            raise AssertionError(('Incomplete production source generation', failed, len(results)))
        sources, notes = gate.collect_sources(root, model)
        # Keep bytes independent of the temporary repository lifetime.
        return tuple((source.logical, Path(source.real).read_text(encoding='utf-8'))
                     for source in sources if source.set == 'main' and source.logical.endswith('.kt'))
