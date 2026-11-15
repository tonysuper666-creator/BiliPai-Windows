"""The pinned upstream-original read point the desktop build compiles from.

`desktop/settings.gradle.kts` materializes `desktop/upstream-core/legacy-root.commit`
at `<repo>/.legacy-root` and every producer that still patches originals is run with
that tree as `--repo`. After the upstream merge the repository root carries different
originals, so a test that reads the root proves nothing about the build it guards.

Tests therefore reuse the already materialized tree — never the merged root, and
never a second materialization of their own. When this machine has not built it, the
test fails with the exact input it needs instead of silently going green or red on
the wrong sources.
"""
from pathlib import Path

PIN = 'desktop/upstream-core/legacy-root.commit'


def legacy_read_point(repo, probe):
    repo = Path(repo).resolve()
    root = repo if repo.name == '.legacy-root' else repo / '.legacy-root'
    if (root / probe).is_file():
        return root
    pin = repo / PIN
    commit = next((line.strip() for line in pin.read_text(encoding='utf-8').splitlines()
                   if line.strip() and not line.startswith('#')), '<pin>') if pin.is_file() else '<pin>'
    raise RuntimeError(
        'Missing pinned legacy read point %s (probe %s). The desktop build reads it from %s: '
        'materialize it before running this test, e.g. '
        '`git -c core.autocrlf=false archive %s | tar -x -C %s`.' % (root, probe, PIN, commit, root))
