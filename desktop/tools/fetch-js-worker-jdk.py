#!/usr/bin/env python3
"""Fetch and extract the exact standalone Windows worker JDK; no system Java changes."""
from pathlib import Path, PurePosixPath
import argparse
import importlib.util
import shutil
import stat
import tempfile
import urllib.request
import zipfile

spec = importlib.util.spec_from_file_location("worker_resources", Path(__file__).with_name("prepare-js-worker.py"))
worker = importlib.util.module_from_spec(spec); spec.loader.exec_module(worker)

def fetch(archive: Path, output: Path, offline: bool):
    archive, output = archive.resolve(), output.resolve()
    if not archive.exists():
        if offline: raise ValueError("Fixed worker JDK archive missing in offline mode")
        archive.parent.mkdir(parents=True, exist_ok=True)
        with urllib.request.urlopen(worker.JDK_URL, timeout=60) as response, tempfile.NamedTemporaryFile(dir=archive.parent, delete=False) as target:
            temp = Path(target.name)
            shutil.copyfileobj(response, target, length=1024 * 1024)
        if worker.digest(temp) != worker.JDK_SHA256:
            temp.unlink(); raise ValueError("Fixed worker JDK publisher checksum differs")
        temp.replace(archive)
    if worker.digest(archive) != worker.JDK_SHA256: raise ValueError("Fixed worker JDK archive checksum differs")
    if output.exists():
        if not output.is_dir(): raise ValueError("Worker JDK output is not a directory")
        # Gradle creates declared output directories before running Exec.
        # A populated checkout must still match every publisher archive member.
        if any(output.iterdir()):
            worker.verify_jdk(output, archive); return output
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="worker-jdk-", dir=output.parent) as temporary:
        staging = Path(temporary).resolve()
        if not staging.is_relative_to(output.parent): raise ValueError("JDK staging path escaped target directory")
        with zipfile.ZipFile(archive) as source:
            members = source.infolist()
            if len(members) > 20_000 or sum(row.file_size for row in members) > 2_000_000_000: raise ValueError("Unexpected worker JDK archive size")
            roots = {PurePosixPath(row.filename).parts[0] for row in members}
            if len(roots) != 1: raise ValueError("Unexpected worker JDK archive root")
            for row in members:
                if row.is_dir(): continue
                if stat.S_ISLNK(row.external_attr >> 16): raise ValueError("Worker JDK archive contains symlinks")
                relative = "/".join(PurePosixPath(row.filename).parts[1:])
                path = worker.safe_relative(staging, relative); path.parent.mkdir(parents=True, exist_ok=True)
                with source.open(row) as original, path.open("wb") as target: shutil.copyfileobj(original, target)
        worker.verify_jdk(staging, archive)
        # Remove only a still-empty Gradle placeholder after verification. rmdir
        # refuses to replace files which another process created while extracting.
        if output.exists(): output.rmdir()
        # Move only this verified staging checkout to the explicit target.
        staging.rename(output)
    return output

if __name__ == "__main__":
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("--archive", type=Path, required=True); cli.add_argument("--output", type=Path, required=True)
    cli.add_argument("--offline", action="store_true")
    args = cli.parse_args(); print(fetch(args.archive, args.output, args.offline))
