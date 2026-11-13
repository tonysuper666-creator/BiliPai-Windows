"""Path helpers for source-contract fixtures and their comparison boundaries."""
from contextlib import contextmanager
from pathlib import Path
import tempfile

import check_slice_symbol_closure as gate


@contextmanager
def scratch_directory(repo):
    """Use the user's normal temp directory and reserve space for the actual mirror name."""
    with tempfile.TemporaryDirectory(prefix='bp-') as temporary:
        scratch = Path(temporary)
        gate.check_scratch_root(gate.mirror_root(repo, scratch))
        yield scratch


def relative_inside(path, root):
    """Resolve path representations and reject actual or symlink escapes."""
    def plain_resolved(value):
        resolved = str(Path(value).resolve())
        if resolved.startswith('\\\\?\\UNC\\'):
            return Path('\\\\' + resolved[8:])
        return Path(gate._plain(resolved))

    return plain_resolved(path).relative_to(plain_resolved(root))
