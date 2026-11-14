"""Temporary read point for pre-M5 Python gates; never a product/build input.

Upstream originals (including plugin examples) come from legacy-root.commit.
Desktop code, tests and publication workflows come from the live tree.
A real linked worktree keeps git history queries rooted at this read point.
"""
from contextlib import contextmanager
import os
import re
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile


def _io_path(path):
    value = os.path.abspath(str(path))
    if os.name == "nt" and not value.startswith("\\\\?\\"):
        value = "\\\\?\\UNC\\" + value[2:] if value.startswith("\\\\") else "\\\\?\\" + value
    return value


def _plain_path(path):
    value = str(path)
    if value.startswith("\\\\?\\UNC\\"):
        return "\\\\" + value[8:]
    return value[4:] if value.startswith("\\\\?\\") else value


_IO_REPARSE_TAG_MOUNT_POINT = 0xA0000003  # stat.IO_REPARSE_TAG_MOUNT_POINT, Windows-only


def _is_junction(path):
    """Path.is_junction() only exists from Python 3.12; read the reparse tag directly."""
    try:
        status = os.lstat(path)
    except OSError:
        return False
    return getattr(status, "st_reparse_tag", 0) == _IO_REPARSE_TAG_MOUNT_POINT


def _contains(root, path):
    root, path = (os.path.normcase(_plain_path(os.path.realpath(value))) for value in (root, path))
    try:
        return os.path.commonpath([root, path]) == root
    except ValueError:  # different drives
        return False


def _refuse_escaping_links(repo, directory, names):
    # copytree follows symlinks and treats junctions as plain directories.
    # A link leaving the live repository, or looping back over itself, is
    # refused before anything behind it is read.
    for name in names:
        path = os.path.join(directory, name)
        if (os.path.islink(path) or _is_junction(path)) and (
                not _contains(repo, path) or _contains(path, directory)):
            raise ValueError(f"refusing to copy link {_plain_path(path)} -> "
                             f"{_plain_path(os.path.realpath(path))}: outside {repo} or cyclic")


@contextmanager
def legacy_gate_workspace(repo):
    repo = Path(repo).resolve()
    pin = repo / "desktop/upstream-core/legacy-root.commit"
    if not pin.exists():  # also permits baseline verification before M0
        yield repo
        return
    if not hasattr(tarfile, "data_filter"):  # extractall(filter=...) below
        raise RuntimeError("legacy gate workspace needs Python 3.12+ "
                           "(or 3.8.17/3.9.17/3.10.12/3.11.4+ with tarfile filters)")
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
        subprocess.run(["git", "-c", "core.autocrlf=false", "-c", "core.longpaths=true", "-C", str(repo),
                        "worktree", "add", "--detach", "--quiet", str(view), "HEAD"], check=True)
        try:
            # Copy live tracked areas as well as uncommitted edits. No generated
            # output, Gradle state or recursive read points enter the snapshot.
            def ignore(directory, names):
                excluded = {"__pycache__", ".gradle"}
                if Path(_plain_path(directory)) == repo / "desktop":
                    excluded.add("build")
                _refuse_escaping_links(repo, directory, [name for name in names if name not in excluded])
                return excluded.intersection(names)

            def guard(directory, names):
                _refuse_escaping_links(repo, directory, names)
                return set()

            for name in ("desktop", ".github"):
                _refuse_escaping_links(repo, str(repo), [name])
                shutil.rmtree(_io_path(view / name))
                shutil.copytree(_io_path(repo / name), _io_path(view / name),
                                ignore=ignore)
            for target in view.iterdir():
                if target.name in protected:
                    continue
                if target.is_dir() and not target.is_symlink():
                    shutil.rmtree(_io_path(target))
                else:
                    target.unlink()
            archive = Path(temporary) / "originals.tar"
            subprocess.run(["git", "-c", "core.autocrlf=false", "-C", str(repo), "archive",
                            "--format=tar", "-o", str(archive), commit, "--", *originals], check=True)
            # Windows resolves "tar" to System32 bsdtar, which decodes ustar names
            # with the ANSI code page and rejects UTF-8 originals (docs/images/233娘.jpeg).
            with tarfile.open(archive) as tar:
                tar.extractall(_io_path(view), filter="fully_trusted")
            # The symbol gate also models modules compiled from the merged root.
            # Keep those real inputs separate from the old generator originals.
            recipe = repo / "desktop/build.gradle.kts"
            modules = re.search(r'val migratedUpstreamModules = listOf\((.*?)\)', recipe.read_text(encoding="utf-8")) if recipe.exists() else None
            for module in re.findall(r'"([^"]+)"', modules.group(1)) if modules else []:
                relative = Path(module) / 'src/main/java'
                _refuse_escaping_links(repo, str((repo / relative).parent), [relative.name])
                shutil.copytree(_io_path(repo / relative), _io_path(view / '.merged-root' / relative),
                                ignore=guard)
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
            elif _is_junction(build_link):
                build_link.rmdir()
            subprocess.run(["git", "-c", "core.longpaths=true", "-C", str(repo), "worktree", "remove", "--force", str(view)], check=True)
