"""Stage the reviewed SDK in an exclusive temporary root; never install it.

The hosted SDK's servicing release may change without changing its Include
directory version. These official packages reproduce the existing approval;
the normal producer must still verify MSVC, its actual graph, and the fresh DLL.
"""
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
import argparse
import base64
import hashlib
import json
import os
import stat
import sys
import urllib.request
import uuid
import zipfile


SDK_VERSION = "10.0.26100.0"
PACKAGE_VERSION = "10.0.26100.7705"
APPROVAL_SHA256 = "0ecca05e8510c14d03d7376d1aba492b9eac5f711bcecb653cd9e10dc33810b7"
MAX_EXPANDED_BYTES = 2 * 1024 * 1024 * 1024
MAX_ENTRIES = 12000


@dataclass(frozen=True)
class Package:
    name: str
    bytes: int
    sha512_base64: str
    kind: str
    sha256_hex: str | None = None

    @property
    def filename(self):
        return self.name.lower() + "." + PACKAGE_VERSION + ".nupkg"

    @property
    def url(self):
        return ("https://api.nuget.org/v3-flatcontainer/" + self.name.lower()
                + "/" + PACKAGE_VERSION + "/" + self.filename)


PACKAGES = (
    Package("Microsoft.Windows.SDK.CPP", 160402494,
            "A0nPv2lksYw7XoEWj5ACHaAsN0hs0/ltDRWkGh1hFV7F5jk8wN2GbQKfuULgoeoFaX6IT12BiMw8q9kmCZrEZw==", "headers"),
    Package("Microsoft.Windows.SDK.CPP.x64", 52949537,
            "kAwZIjuwwHNAIrY7Vo2b3tl/59kGVyEUVJ94gq4nvIfeWdW66h33iA3blBSKxZUEx9dOS4HM6H+zYXV1ZCHz2w==", "libraries"),
)


def file_path(path):
    if os.name == "nt":
        value = os.path.abspath(os.fspath(path))
        if not value.startswith("\\\\?\\"):
            value = ("\\\\?\\UNC\\" + value[2:]) if value.startswith("\\\\") else ("\\\\?\\" + value)
        return Path(value)
    return path


def sha256(path):
    return hashlib.sha256(file_path(path).read_bytes()).hexdigest()


def checked_path(name):
    """Validate ZIP and approval paths before any extraction."""
    if not name or "\\" in name or name.startswith("/"):
        raise ValueError("Unsafe SDK archive path: " + repr(name))
    parts = name.rstrip("/").split("/")
    reserved = {"con", "prn", "aux", "nul", *("com" + str(n) for n in range(1, 10)),
                *("lpt" + str(n) for n in range(1, 10))}
    for part in parts:
        if (part in ("", ".", "..") or part.endswith((".", " "))
                or any(ord(c) < 32 or ord(c) == 127 or c in '<>:"|?*' for c in part)
                or part.split(".", 1)[0].casefold() in reserved):
            raise ValueError("Unsafe SDK archive path: " + repr(name))
    return PurePosixPath(*parts)


def mapped_path(name, kind):
    if kind == "headers" and name.startswith("c/Include/"):
        return name[2:]
    if kind == "libraries" and name.startswith(("c/um/x64/", "c/ucrt/x64/")):
        return "Lib/" + SDK_VERSION + "/" + name[2:]
    return None


def verify_archive(path, package):
    path = file_path(path)
    if path.stat().st_size != package.bytes:
        raise ValueError("SDK package length differs: " + package.name)
    digest = hashlib.sha512()
    sha256_digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
            sha256_digest.update(chunk)
    if digest.digest() != base64.b64decode(package.sha512_base64, validate=True):
        raise ValueError("SDK package SHA512 differs: " + package.name)
    if package.sha256_hex is not None and sha256_digest.hexdigest() != package.sha256_hex:
        raise ValueError("Reviewed package SHA256 differs: " + package.name)


def download_package(package, destination):
    # Exact immutable URL and full bytes/digest, including the signed package.
    # Do not publish a partially downloaded archive or accept a short read.
    digest = hashlib.sha512()
    sha256_digest = hashlib.sha256()
    count = 0
    with file_path(destination).open("xb") as handle:
        # A short transport response can resume only its exact missing suffix.
        # This never admits cached partial bytes: the full SHA512 is mandatory.
        for attempt in range(4):
            start = count
            headers = {"Accept-Encoding": "identity"}
            if start:
                headers["Range"] = f"bytes={start}-{package.bytes - 1}"
            request = urllib.request.Request(package.url, headers=headers)
            with urllib.request.urlopen(request, timeout=60) as response:
                if response.status != (206 if start else 200) or response.geturl() != package.url:
                    raise ValueError("Unexpected SDK package response or redirect")
                if start and response.headers.get("Content-Range") != f"bytes {start}-{package.bytes - 1}/{package.bytes}":
                    raise ValueError("SDK package resume returned a different range")
                length = response.headers.get("Content-Length")
                if length is not None and int(length) != package.bytes - start:
                    raise ValueError("Unexpected SDK package Content-Length")
                while True:
                    chunk = response.read(1024 * 1024)
                    if not chunk:
                        break
                    count += len(chunk)
                    if count > package.bytes:
                        raise ValueError("SDK package exceeds its fixed length")
                    digest.update(chunk)
                    sha256_digest.update(chunk)
                    handle.write(chunk)
            if count == package.bytes:
                break
            if count == start:
                raise ValueError("SDK package truncated with no progress")
    if count != package.bytes or digest.digest() != base64.b64decode(package.sha512_base64, validate=True):
        raise ValueError("SDK package truncated or SHA512 differs: " + package.name)
    if package.sha256_hex is not None and sha256_digest.hexdigest() != package.sha256_hex:
        raise ValueError("Reviewed package SHA256 differs: " + package.name)


def extract_package(archive, package, sdk_root, *, path_mapper=mapped_path):
    verify_archive(archive, package)
    sdk_root = file_path(sdk_root.resolve())
    selected = []
    with zipfile.ZipFile(file_path(archive)) as zipped:
        entries = zipped.infolist()
        if len(entries) > MAX_ENTRIES or sum(e.file_size for e in entries) > MAX_EXPANDED_BYTES:
            raise ValueError("SDK archive resource boundary exceeded")
        seen = {}
        mapped_names = set()
        for entry in entries:
            # ZipInfo normalizes backslashes/NUL on Windows; inspect the raw
            # spelling too so unsafe names cannot hide behind that conversion.
            pure = checked_path(entry.orig_filename)
            if entry.orig_filename != entry.filename:
                raise ValueError("SDK archive path was normalized by ZIP parser")
            key = str(pure).casefold()
            if key in seen:
                raise ValueError("SDK archive has duplicate Windows paths")
            seen[key] = entry
            mode = entry.external_attr >> 16
            if entry.flag_bits & 1 or stat.S_ISLNK(mode) or stat.S_IFMT(mode) not in (0, stat.S_IFREG, stat.S_IFDIR):
                raise ValueError("Unsupported SDK archive entry type")
            mapped = path_mapper(entry.filename, package.kind)
            if mapped and not entry.is_dir():
                pure_mapped = checked_path(mapped)
                mapped_key = str(pure_mapped).casefold()
                if mapped_key in mapped_names:
                    raise ValueError("Reviewed archive has duplicate mapped Windows paths")
                mapped_names.add(mapped_key)
                selected.append((entry, pure_mapped))
        # Reject file/child collisions even for excluded package metadata.
        for key, entry in seen.items():
            for parent in PurePosixPath(key).parents:
                previous = seen.get(str(parent))
                if previous is not None and not previous.is_dir():
                    raise ValueError("SDK archive has a file/directory collision")
        for key in mapped_names:
            if any(str(parent) in mapped_names for parent in PurePosixPath(key).parents):
                raise ValueError("Reviewed archive has a mapped file/directory collision")
        if not selected:
            raise ValueError("SDK package has no selected SDK inputs")
        for entry, pure in selected:
            target = sdk_root.joinpath(*pure.parts)
            if not target.resolve().is_relative_to(sdk_root.resolve()):
                raise ValueError("SDK extraction escaped its private root")
            target.parent.mkdir(parents=True, exist_ok=True)
            with zipped.open(entry) as source, target.open("xb") as sink:
                while True:
                    chunk = source.read(1024 * 1024)
                    if not chunk:
                        break
                    sink.write(chunk)
    return dict(entries=len(entries), selectedEntries=len(selected),
                selectedBytes=sum(e.file_size for e, _ in selected))


def sdk_pins(approval_path):
    if sha256(approval_path) != APPROVAL_SHA256:
        raise ValueError("Native approval changed; review the SDK provisioner")
    approval = json.loads(approval_path.read_bytes())
    if approval["sdkVersion"] != SDK_VERSION:
        raise ValueError("Native approval has a different SDK directory version")
    rows = []
    names = set()
    for category in ("transitiveHeaders", "searchedLibrariesConservativePins", "compilerBinDirectoryConservativePins"):
        for row in approval[category]:
            if not row["path"].startswith("sdk/"):
                continue
            path = checked_path(row["path"][4:])
            key = str(path).casefold()
            if key in names or not isinstance(row["bytes"], int) or row["bytes"] < 0:
                raise ValueError("Invalid SDK approval rows")
            names.add(key)
            rows.append(row)
    if not rows:
        raise ValueError("Native approval has no SDK inputs")
    return rows


def verify_sdk(sdk_root, rows):
    sdk_root = file_path(sdk_root.resolve())
    for row in rows:
        pure = checked_path(row["path"][4:])
        target = sdk_root.joinpath(*pure.parts).resolve(strict=True)
        if (not target.is_relative_to(sdk_root.resolve()) or not target.is_file()
                or target.stat().st_size != row["bytes"] or sha256(target) != row["sha256Bytes"]):
            raise ValueError("Official SDK bytes differ from native approval: " + row["path"])


def prepare(repo, temp_root, output):
    repo = repo.resolve(strict=True)
    temp_root = temp_root.resolve(strict=True)
    output = output.resolve()
    if (not temp_root.is_dir() or output.parent != temp_root or output.exists()
            or output.is_relative_to(repo)):
        raise ValueError("SDK output must be a new direct child of an external temporary root")
    runner_temp = os.environ.get("RUNNER_TEMP")
    if runner_temp and temp_root != Path(runner_temp).resolve(strict=True):
        raise ValueError("CI SDK temporary root differs from RUNNER_TEMP")
    for fixed in ("C:/Windows", "C:/Program Files", "C:/Program Files (x86)"):
        if output.is_relative_to(Path(fixed).resolve()):
            raise ValueError("SDK provisioning must not change a system installation")
    approval_path = repo / "desktop/native/diagnostic-share/approved-development-build.json"
    rows = sdk_pins(approval_path)
    stage = temp_root / ("." + output.name + "-incomplete-" + str(uuid.uuid4()))
    stage.mkdir()
    sdk = stage / "sdk"
    sdk.mkdir()
    records = []
    for package in PACKAGES:
        archive = stage / package.filename
        download_package(package, archive)
        record = dict(package=package.name, version=PACKAGE_VERSION, url=package.url,
                      packageBytes=package.bytes, packageSha512Base64=package.sha512_base64,
                      archive=str(archive), **extract_package(archive, package, sdk))
        records.append(record)
    verify_sdk(sdk, rows)
    if sha256(approval_path) != APPROVAL_SHA256:
        raise ValueError("Native approval changed while provisioning SDK")
    receipt = dict(passed=True, sdkRoot=str(output), sdkVersion=SDK_VERSION,
                   sdkPackageVersion=PACKAGE_VERSION, approvedManifestSha256=APPROVAL_SHA256,
                   approvedSdkInputs=len(rows), sdkInputsExact=True, packages=records,
                   installedSystemSdkChanged=False, compilerExecuted=False,
                   freshProducerGraphAndDllVerificationStillRequired=True)
    file_path(sdk / "sdk-provision.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    # Windows rename refuses an existing destination; no replacement/deletion.
    # The normal CI output is exclusive to this run. No accepted root on error.
    if output.exists():
        raise ValueError("SDK output appeared while provisioning")
    sdk.rename(output)
    return receipt


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", type=Path, required=True)
    parser.add_argument("--temp-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(prepare(args.repo, args.temp_root, args.output)))


if __name__ == "__main__":
    main()
