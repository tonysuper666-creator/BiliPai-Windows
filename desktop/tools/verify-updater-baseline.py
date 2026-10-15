"""Verify the exact previously delivered ZIP before executing its updater fixture.

The fixed published test fixture is fetched with read-only CI permission. This
verifier reads bytes only; extracting and starting the EXE remains the job of
the existing isolated DesktopUpdaterIntegrationTest.
"""
from pathlib import Path
import argparse
import hashlib
import json
import os
import stat

BASELINE = {'repository': 'tonysuper666-creator/BiliPai-Windows', 'provenance': 'archived-previously-delivered-local-build', 'releaseId': 413553374, 'assetId': 643148932, 'releaseTag': 'updater-fixture-0.2.427.56-aa0a627f54bc', 'sourceSha': 'aa0a627f54bc4bb9dca7f507615c30fc21b74ca6', 'version': '0.2.427.56', 'filename': 'BiliPai-Windows-0.2.427.56-aa0a627f54bc-x64.zip', 'bytes': 395600303, 'sha256': 'f7d6e2125fc4f8ab94eb2419d13f915b68da7d5676191cb2392bff6143ece803', 'sourceArchive': {'assetId': 643150522, 'filename': 'source.zip', 'bytes': 898458256, 'sha256': '1e04bcea0938f4daa1b6a25aaa89a6861df7d5ed87287674febd04d42457b64c'}}


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
