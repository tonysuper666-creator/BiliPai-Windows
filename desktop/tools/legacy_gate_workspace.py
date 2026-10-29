"""Temporary read point for pre-M5 Python gates; never a product/build input.

Only canonical upstream source roots come from legacy-root.commit. Everything
else (including desktop code, tests and publication workflows) is the live tree.
A real linked worktree keeps git history queries rooted at this read point.
"""
from contextlib import contextmanager
import json
import os
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
    commit, = [line.strip() for line in pin.read_text().splitlines()
               if line.strip() and not line.startswith("#")]
    subprocess.run(["git", "-C", str(repo), "cat-file", "-e", commit + "^{commit}"], check=True)
    roots = json.loads((repo / "desktop/tools/v025-canonical-sources.json").read_bytes())["sourceRoots"]
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
            for root in roots:
                target = view / root
                if target.exists():
                    shutil.rmtree(target)
            archive = Path(temporary) / "originals.tar"
            subprocess.run(["git", "-c", "core.autocrlf=false", "-C", str(repo), "archive",
                            "--format=tar", "-o", str(archive), commit, "--", *roots], check=True)
            subprocess.run(["tar", "-xf", str(archive), "-C", str(view)], check=True)
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
