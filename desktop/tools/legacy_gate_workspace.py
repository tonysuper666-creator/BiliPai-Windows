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
import tempfile


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
            def ignore(directory, names):
                excluded = {"__pycache__", ".gradle"}
                if Path(directory) == repo / "desktop":
                    excluded.add("build")
                return excluded.intersection(names)

            for name in ("desktop", ".github"):
                shutil.rmtree(view / name)
                shutil.copytree(repo / name, view / name,
                                ignore=ignore)
            for target in view.iterdir():
                if target.name in protected:
                    continue
                if target.is_dir() and not target.is_symlink():
                    shutil.rmtree(target)
                else:
                    target.unlink()
            archive = Path(temporary) / "originals.tar"
            subprocess.run(["git", "-c", "core.autocrlf=false", "-C", str(repo), "archive",
                            "--format=tar", "-o", str(archive), commit, "--", *originals], check=True)
            subprocess.run(["tar", "-xf", str(archive), "-C", str(view)], check=True)
            # The symbol gate also models modules compiled from the merged root.
            # Keep those real inputs separate from the old generator originals.
            recipe = repo / "desktop/build.gradle.kts"
            modules = re.search(r'val migratedUpstreamModules = listOf\((.*?)\)', recipe.read_text(encoding="utf-8")) if recipe.exists() else None
            for module in re.findall(r'"([^"]+)"', modules.group(1)) if modules else []:
                relative = Path(module) / 'src/main/java'
                shutil.copytree(repo / relative, view / '.merged-root' / relative)
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
