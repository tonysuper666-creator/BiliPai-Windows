"""IO boundaries for source-contract fixtures, without changing production paths.

Scratch mirrors stay in the user's normal temporary directory. The legacy fixture
adapter invokes the real legacy_gate_workspace: Python IO needs extended paths,
whereas Git's worktree arguments need their ordinary Windows spelling.
"""
from contextlib import contextmanager
import os
from pathlib import Path
import shutil
import stat
import subprocess
import sys
import tempfile
from unittest import mock

import check_slice_symbol_closure as gate
import legacy_gate_workspace as legacy


def plain_path(path):
    value = str(path)
    if value.startswith('\\\\?\\UNC\\'):
        return Path('\\\\' + value[8:])
    return Path(gate._plain(value))


def relative_inside(path, root):
    """Resolve links before checking containment; retain the entire file identity."""
    actual = plain_path(Path(path).resolve(strict=True))
    boundary = plain_path(Path(root).resolve(strict=True))
    return actual.relative_to(boundary)


def remove_tree(path):
    """Clean only fixture-owned trees, including deep paths and read-only Git files."""
    def retry(function, target, error):
        if not isinstance(error, PermissionError):
            raise error
        os.chmod(target, stat.S_IRUSR | stat.S_IWUSR | stat.S_IXUSR)
        function(target)

    target = gate._long(path)
    if os.path.lexists(target):
        if sys.version_info >= (3, 12):
            shutil.rmtree(target, onexc=retry)
        else:
            shutil.rmtree(target, onerror=lambda function, target, info: retry(function, target, info[1]))


@contextmanager
def temporary_directory(prefix='bp-'):
    directory = Path(tempfile.mkdtemp(prefix=prefix))
    try:
        yield directory
    finally:
        remove_tree(directory)


@contextmanager
def scratch_directory(repo):
    with temporary_directory() as scratch:
        gate.check_scratch_root(gate.mirror_root(repo, scratch))
        yield scratch


def _io_path(value):
    path = Path(value)
    return Path(gate._long(path)) if path.is_absolute() else path


class _GitPaths:
    @staticmethod
    def command(command):
        command = [str(plain_path(arg)) if isinstance(arg, str) and arg.startswith('\\\\?\\')
                   else arg for arg in command]
        if command[0] == 'git':
            command[1:1] = ['-c', 'core.longpaths=true']
        return command

    def run(self, command, **kwargs):
        return subprocess.run(self.command(command), **kwargs)

    def check_output(self, command, **kwargs):
        return subprocess.check_output(self.command(command), **kwargs)


@contextmanager
def legacy_workspace(repo):
    """Adapt only the legacy fixture's IO; no global TEMP, Path or shutil patch."""
    with mock.patch.object(legacy, 'Path', _io_path), mock.patch.object(legacy, 'subprocess', _GitPaths()):
        with legacy.legacy_gate_workspace(Path(repo).resolve()) as view:
            yield plain_path(view)
