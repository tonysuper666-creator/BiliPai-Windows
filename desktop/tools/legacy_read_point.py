"""The pinned upstream-original read point the desktop build compiles from.

`desktop/settings.gradle.kts` materializes `desktop/upstream-core/legacy-root.commit`
at `<repo>/.legacy-root`, stamps the commit it materialized into `<root>/.legacy-commit`
and reruns the materialization whenever that marker is missing or no longer equals the
commit in the pin file. Producers that still patch originals are run with that tree as
`--repo`. After the upstream merge the repository root carries different originals, so a
test that reads the root proves nothing about the build it guards.

Tests therefore reuse the already materialized tree — never the merged root, and never a
second materialization of their own — and accept it only when its marker names the commit
the pin file names now. A tree from an older pin, or one whose marker went missing, is not
what the build would compile, so the test fails with the exact input it needs instead of
silently going green on the wrong sources.
"""
from pathlib import Path

PIN = 'desktop/upstream-core/legacy-root.commit'
MARKER = '.legacy-commit'


def pinned_commit(repo):
    pin = Path(repo) / PIN
    if not pin.is_file():
        raise RuntimeError('Missing pin file %s; it names the legacy read point this build compiles from.'
                           % pin)
    for line in pin.read_text(encoding='utf-8').splitlines():
        line = line.strip()
        if line and not line.startswith('#'):
            return line
    raise RuntimeError('Pin file %s names no commit.' % pin)


def legacy_read_point(repo, probe):
    repo = Path(repo).resolve()
    root = repo if repo.name == '.legacy-root' else repo / '.legacy-root'
    commit = pinned_commit(root.parent)
    materialize = ('`git -c core.autocrlf=false -c core.eol=lf archive --format=tar %s | tar -x -C %s '
                   '&& printf %%s %s > %s`' % (commit, root, commit, root / MARKER))
    if not (root / probe).is_file():
        raise RuntimeError(
            'Missing pinned legacy read point %s (probe %s, pin %s from %s). The desktop build reads '
            'it from %s: materialize it before running this test, e.g. %s.'
            % (root, probe, commit, PIN, PIN, materialize))
    marker = root / MARKER
    if not marker.is_file():
        raise RuntimeError(
            'Legacy read point %s has no %s marker, so it cannot prove it holds pin %s (%s). '
            'Re-materialize it the way desktop/settings.gradle.kts would, e.g. %s.'
            % (root, MARKER, commit, PIN, materialize))
    found = marker.read_text(encoding='utf-8').strip()
    if found != commit:
        raise RuntimeError(
            'Legacy read point %s holds pin %s (%s), but %s names %s. desktop/settings.gradle.kts '
            'would re-materialize it; do the same before running this test, e.g. %s.'
            % (root, found, MARKER, PIN, commit, materialize))
    return root
