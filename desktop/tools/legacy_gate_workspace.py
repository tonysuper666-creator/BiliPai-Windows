"""Temporary read point for pre-M5 Python gates; never a product/build input.

Upstream originals (including plugin examples) come from legacy-root.commit.
Desktop code, tests and publication workflows come from the live tree.
A real linked worktree keeps git history queries rooted at this read point.

Python file-system calls use the Windows extended-length form (desktop_paths.long_path):
the retained originals and the live desktop both hold paths past the 260-character
limit, so a plain scan, copy or delete fails with WinError 3. Git commands and the
build junction keep normal paths because Git already handles long paths itself.
"""
from contextlib import contextmanager
import os
import re
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile

from desktop_paths import LONG_PREFIX, UNC_LONG_PREFIX, long_path


def _plain(value):
    """``value`` without the Windows extended-length prefix, for path comparisons."""
    text = os.fspath(value)
    if text.startswith(UNC_LONG_PREFIX):
        return Path("\\\\" + text[len(UNC_LONG_PREFIX):])
    if text.startswith(LONG_PREFIX):
        return Path(text[len(LONG_PREFIX):])
    return Path(text)


def _path_key(value):
    return os.path.normcase(os.path.abspath(os.fspath(_plain(value))))


def _absolute(value):
    return os.path.abspath(os.fspath(_plain(value)))


def _remove_tree(root, target):
    """Recursively remove ``target`` inside the read point ``root``.

    Deletion needs the extended-length form because the retained originals hold
    paths past the Windows limit. A reparse point is unlinked instead of
    traversed, so a junction can never delete the tree it points at, and a
    target outside ``root`` is refused both by spelling and by resolved path: a
    parent junction can redirect a delete while the spelling still looks local.
    """
    root_key, target_key = _path_key(root), _path_key(target)
    try:
        owned = os.path.commonpath((root_key, target_key)) == root_key
    except ValueError:  # different drives
        owned = False
    if not owned or target_key == root_key:
        raise RuntimeError(f"refusing to remove {target} outside the read point {root}")
    safe = long_path(_absolute(target))
    if safe.is_junction():
        os.rmdir(safe)
        return
    if safe.is_symlink():
        os.unlink(safe)
        return
    resolved_root = _path_key(os.path.realpath(long_path(_absolute(root))))
    resolved_target = _path_key(os.path.realpath(safe))
    try:
        resolved_inside = os.path.commonpath((resolved_root, resolved_target)) == resolved_root
    except ValueError:  # different drives
        resolved_inside = False
    if not resolved_inside or resolved_target == resolved_root:
        raise RuntimeError(f"refusing to remove {target} outside the read point {root}")
    shutil.rmtree(safe)


@contextmanager
def legacy_gate_workspace(repo):
    repo = Path(repo).resolve()
    pin = repo / "desktop/upstream-core/legacy-root.commit"
    if not pin.exists():  # also permits baseline verification before M0
        yield repo
        return
    commit, = [line.strip() for line in pin.read_text(encoding="utf-8").splitlines()
               if line.strip() and not line.startswith("#")]
    subprocess.run(["git", "-C", str(repo), "cat-file", "-e", commit + "^{commit}"], check=True)
    protected = {"desktop", ".github", ".git"}
    originals = subprocess.check_output(
        ["git", "-C", str(repo), "ls-tree", "--name-only", commit], text=True).splitlines()
    originals = [name for name in originals if name not in protected]
    with tempfile.TemporaryDirectory(prefix="bilipai-gates-", dir=repo.parent) as temporary:
        view = Path(temporary) / ".legacy-root"
        build_link = view / "desktop/build"
        subprocess.run(["git", "-c", "core.autocrlf=false", "-C", str(repo),
                        "worktree", "add", "--detach", "--quiet", str(view), "HEAD"], check=True)
        try:
            # Copy live tracked areas as well as uncommitted edits. No generated
            # output, Gradle state or recursive read points enter the snapshot.
            # A copy runs on prefixed paths, so compare in plain form or the live
            # desktop/build would be recursed into instead of excluded.
            live_desktop = _path_key(repo / "desktop")

            def ignore(directory, names):
                excluded = {"__pycache__", ".gradle"}
                if _path_key(directory) == live_desktop:
                    excluded.add("build")
                return excluded.intersection(names)

            for name in ("desktop", ".github"):
                _remove_tree(view, view / name)
                shutil.copytree(long_path(repo / name), long_path(view / name),
                                ignore=ignore)
            for target in view.iterdir():
                if target.name in protected:
                    continue
                if target.is_dir() and not target.is_symlink():
                    _remove_tree(view, target)
                else:
                    os.unlink(long_path(_absolute(target)))
            archive = Path(temporary) / "originals.tar"
            subprocess.run(["git", "-c", "core.autocrlf=false", "-C", str(repo), "archive",
                            "--format=tar", "-o", str(archive), commit, "--", *originals], check=True)
            # Windows resolves "tar" to System32 bsdtar, which decodes ustar names
            # with the ANSI code page and rejects UTF-8 originals (docs/images/233娘.jpeg).
            with tarfile.open(archive) as tar:
                tar.extractall(long_path(view), filter="fully_trusted")
            # The symbol gate also models modules compiled from the merged root.
            # Keep those real inputs separate from the old generator originals.
            recipe = repo / "desktop/build.gradle.kts"
            modules = re.search(r'val migratedUpstreamModules = listOf\((.*?)\)', recipe.read_text(encoding="utf-8")) if recipe.exists() else None
            for module in re.findall(r'"([^"]+)"', modules.group(1)) if modules else []:
                relative = Path(module) / 'src/main/java'
                shutil.copytree(long_path(repo / relative), long_path(view / '.merged-root' / relative))
            live_build = repo / "desktop/build"
            if live_build.is_dir():
                if os.name == "nt":
                    subprocess.run(["cmd", "/c", "mklink", "/J", str(build_link), str(live_build)], check=True)
                else:
                    build_link.symlink_to(live_build, target_is_directory=True)
            print(f"Legacy Python read point: {commit}; live desktop: {repo}", flush=True)
            yield view
        finally:
            # Remove a junction without ever traversing the live build tree.
            if build_link.is_symlink():
                build_link.unlink()
            elif build_link.is_junction():
                build_link.rmdir()
            subprocess.run(["git", "-C", str(repo), "worktree", "remove", "--force", str(view)], check=True)
