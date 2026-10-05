"""Verify the exact previously delivered ZIP before executing its updater fixture.

The artifact is fetched by the existing GitHub Actions download action. This
verifier reads bytes only; extracting and starting the EXE remains the job of
the existing isolated DesktopUpdaterIntegrationTest.
"""
from pathlib import Path
import argparse
import hashlib
import json
import os
import stat

BASELINE = dict(
    repository="tonysuper666-creator/BiliPai-Windows",
    runId=37290919690,
    artifactId=11337715963,
    artifactName="BiliPai-Windows-x64",
    sourceSha="3a9fe1bb6a65376f9caffc801d80f78ab22edccf",
    version="0.2.427.17",
    filename="BiliPai-Windows-0.2.427.17-x64.zip",
    bytes=376419164,
    sha256="a6429696c5b5d9a81518e8fcaf0aa811aca97be3db87ef265f868536564c284b",
)


def existing_without_links(path):
    path = Path(os.path.abspath(path))
    current = Path(path.anchor)
    for part in path.parts[1:]:
        current /= part
        attributes = current.lstat()
        if stat.S_ISLNK(attributes.st_mode) or getattr(attributes, "st_file_attributes", 0) & 0x400:
            raise ValueError("The baseline path contains a link or reparse point")
    return path


def verify(package, baseline=BASELINE):
    package = existing_without_links(package)
    attributes = package.stat()
    if not stat.S_ISREG(attributes.st_mode) or package.name != baseline["filename"]:
        raise ValueError("The baseline is not the expected regular ZIP file")
    if attributes.st_size != baseline["bytes"]:
        raise ValueError("The baseline ZIP size differs from the delivered package")
    digest = hashlib.sha256()
    size = 0
    with package.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            size += len(chunk)
            if size > baseline["bytes"]:
                raise ValueError("The baseline ZIP grew during verification")
            digest.update(chunk)
    if size != baseline["bytes"] or digest.hexdigest() != baseline["sha256"]:
        raise ValueError("The baseline ZIP hash differs from the delivered package")
    return dict(baseline, package=str(package), verified=True, applicationLaunched=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--package", type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(verify(args.package)))


if __name__ == "__main__":
    main()
