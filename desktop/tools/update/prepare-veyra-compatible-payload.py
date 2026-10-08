#!/usr/bin/env python3
"""Prepare an OFFLINE, unsigned, PENDING full-app compatibility inventory.

This tool cannot sign, fetch, publish, install, select a component, run a child
process or load native code. Every input is an explicit local file accompanied
by an independently trusted SHA-256. No output is accepted by the application's
VerifiedVeyraCompatibleOffer: no envelope/signature is produced, and the exact
manifest compatibilityStatus remains PENDING_FULL_APPLICATION_VALIDATION.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import struct
import sys
from urllib.parse import quote, urlsplit
import zipfile
import zlib

MAX_ARCHIVE = 512 * 1024 * 1024  # DesktopUpdater.MAX_DOWNLOAD_BYTES
MAX_EXPANDED = 1024 * 1024 * 1024  # SafeUpdateZip.MAX_EXPANDED_BYTES
MAX_ENTRY = 512 * 1024 * 1024
MAX_ENTRIES = 20_000
MAX_JSON = 1024 * 1024
PENDING = "PENDING_FULL_APPLICATION_VALIDATION"
SOURCE_REPOSITORY = "Likely7/Veyra-NRVideo"
NATIVE_VARIANT = "bilipai-veyra-shared-core-v1-v2"
OWN_REPOSITORY = "tonysuper666-creator/BiliPai-Windows"
GATES = ("kotlinUnitTests", "pythonSourceContractTests", "guestNetworkBackendSmoke",
         "packagedNativePlayerSmoke", "packagedUpdaterSmoke", "packagedNativeDownloadMuxSmoke")
EXPORTS = {"bv_create_v1", "bv_process_v1", "bv_reset_v1", "bv_destroy_v1",
           "bvd_create_v2", "bvd_process_v2", "bvd_reset_v2", "bvd_destroy_v2"}
RESERVED = re.compile(r"(?:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\..*)?", re.I)
CLOSED_RUNTIME_NAMES = {"nvngx_vsr.dll", "nvngx_truehdr.dll", "nvngx_dlss.dll", "nvofapi64.dll"}


class PreparationError(ValueError):
    """Portable error code, never an input pathname, credential or body."""


def require(condition: bool, code: str) -> None:
    if not condition:
        raise PreparationError(code)


def digest(value: object) -> str:
    require(isinstance(value, str) and re.fullmatch(r"[0-9a-fA-F]{64}", value) is not None, "INVALID_SHA256")
    return value.lower()


def commit(value: object) -> str:
    require(isinstance(value, str) and re.fullmatch(r"[0-9a-f]{40}", value) is not None, "INVALID_SOURCE_COMMIT")
    return value


def positive(value: object, maximum: int = (1 << 63) - 1) -> int:
    require(type(value) is int and 0 < value <= maximum, "INVALID_POSITIVE_INTEGER")
    return value


def no_links(path: Path) -> Path:
    require(path.is_absolute(), "EXPLICIT_ABSOLUTE_LOCAL_PATH_REQUIRED")
    require(not str(path).startswith(("\\\\", "//")) and
            (os.name != "nt" or re.fullmatch(r"[A-Za-z]:", path.drive) is not None),
            "UNC_OR_DEVICE_PATH_NOT_OFFLINE_LOCAL_INPUT")
    path = Path(os.path.abspath(path))
    current = Path(path.anchor)
    for part in path.parts[1:]:
        current /= part
        info = current.lstat()
        require(not stat.S_ISLNK(info.st_mode) and not getattr(info, "st_file_attributes", 0) & 0x400,
                "LINK_OR_REPARSE_INPUT_REJECTED")
    return path


def stream_digest(stream, limit: int) -> tuple[str, int]:
    stream.seek(0)
    hash_value = hashlib.sha256()
    size = 0
    for chunk in iter(lambda: stream.read(1024 * 1024), b""):
        size += len(chunk)
        require(size <= limit, "INPUT_SIZE_LIMIT")
        hash_value.update(chunk)
    stream.seek(0)
    return hash_value.hexdigest(), size


def unique_object(pairs):
    obj = {}
    for key, value in pairs:
        require(key not in obj, "DUPLICATE_JSON_KEY")
        obj[key] = value
    return obj


def reject_json_constant(_):
    raise PreparationError("NONFINITE_JSON_VALUE")


def local_json(path: Path, expected: str) -> tuple[dict, dict]:
    path = no_links(path)
    require(stat.S_ISREG(path.stat().st_mode), "REGULAR_INPUT_FILE_REQUIRED")
    with path.open("rb") as stream:
        actual, size = stream_digest(stream, MAX_JSON)
        require(actual == digest(expected) and size > 0, "TRUSTED_INPUT_HASH_MISMATCH")
        raw = stream.read(MAX_JSON + 1)
        require(len(raw) == size and hashlib.sha256(raw).hexdigest() == actual, "INPUT_CHANGED_DURING_READ")
    value = json.loads(raw.decode("utf-8-sig"), object_pairs_hook=unique_object,
                       parse_constant=reject_json_constant)
    require(type(value) is dict, "JSON_OBJECT_REQUIRED")
    return value, {"sha256": actual, "bytes": size}


def exact_asset_url(url: object, repository: str, tag: str, name: str) -> str:
    require(isinstance(url, str) and all(ord(ch) > 32 for ch in url), "ASSET_URL_INVALID")
    parsed = urlsplit(url)
    require(parsed.scheme == "https" and parsed.hostname == "github.com" and
            parsed.username is None and parsed.password is None and parsed.port in (None, 443) and
            not parsed.query and not parsed.fragment and
            parsed.path == "/" + repository + "/releases/download/" + quote(tag, safe="") + "/" + quote(name, safe=""),
            "ASSET_URL_OUTSIDE_EXACT_OWN_RELEASE")
    return url


def release_target(release: dict, repository: str, version: str, archive_name: str,
                   archive_size: int, archive_hash: str, source_name: str, source_hash: str, source_size: int) -> dict:
    tag = "Windows-v" + version
    require(release.get("tag_name") == tag and type(release.get("draft")) is bool and
            type(release.get("prerelease")) is bool, "NOT_OWN_FULL_APP_RELEASE")
    release_id = positive(release.get("id"))
    require(release.get("html_url") == "https://github.com/" + repository + "/releases/tag/" + quote(tag, safe=""),
            "RELEASE_REPOSITORY_MISMATCH")
    assets = release.get("assets")
    require(type(assets) is list and 3 <= len(assets) <= 128, "FULL_APP_RELEASE_ASSETS_REQUIRED")
    names, ids, rows = set(), set(), {}
    for row in assets:
        require(type(row) is dict and isinstance(row.get("name"), str), "ASSET_METADATA_INVALID")
        name = row["name"]
        asset_id = positive(row.get("id"))
        require(name not in names and asset_id not in ids and row.get("state") == "uploaded", "ASSET_METADATA_NOT_UNIQUE_OR_UPLOADED")
        names.add(name); ids.add(asset_id); rows[name] = row
    require(archive_name in rows and archive_name + ".sha256" in rows and source_name in rows, "FULL_APP_ASSETS_MISSING")
    archive = rows[archive_name]
    require(positive(archive.get("size"), MAX_ARCHIVE) == archive_size, "ZIP_ASSET_SIZE_MISMATCH")
    require(positive(rows[source_name].get("size"), MAX_JSON) == source_size, "SOURCE_ASSET_SIZE_MISMATCH")
    positive(rows[archive_name + ".sha256"].get("size"), 128 * 1024)
    for name, expected in ((archive_name, archive_hash), (source_name, source_hash)):
        exact_asset_url(rows[name].get("browser_download_url"), repository, tag, name)
        server_digest = rows[name].get("digest")
        require(server_digest is None or server_digest == "sha256:" + expected, "ASSET_SERVER_DIGEST_MISMATCH")
    checksum_url = exact_asset_url(rows[archive_name + ".sha256"].get("browser_download_url"), repository, tag, archive_name + ".sha256")
    return {"releaseTag": tag, "releaseId": release_id, "assetId": archive["id"], "downloadUrl": archive["browser_download_url"],
            "checksumUrl": checksum_url, "checksumSidecarContentsInspected": False,
            "draft": release["draft"], "prerelease": release["prerelease"]}


def safe_name(raw: str) -> str:
    require(bool(raw) and "\0" not in raw, "ZIP_PATH_INVALID")
    name = raw.replace("\\", "/").removesuffix("/")
    require(name and not name.startswith("/") and ":" not in name, "ZIP_PATH_INVALID")
    for part in name.split("/"):
        require(part not in ("", ".", "..") and not part.endswith((".", " ")) and
                RESERVED.fullmatch(part) is None and not any(ord(ch) < 32 or ch in '<>"|?*' for ch in part),
                "ZIP_PATH_INVALID")
    return name


def central_directory(stream, length: int) -> tuple[int, int, int]:
    require(length >= 22, "ZIP_INCOMPLETE")
    tail_size = min(length, 65557)
    stream.seek(length - tail_size)
    tail = stream.read(tail_size)
    for index in range(len(tail) - 22, -1, -1):
        if tail[index:index + 4] != b"PK\x05\x06":
            continue
        fields = struct.unpack_from("<4s4H2IH", tail, index)
        _, disk, directory_disk, disk_count, count, size, offset, comment_size = fields
        if index + 22 + comment_size != len(tail):
            continue
        require(disk == directory_disk == 0 and disk_count == count and 1 <= count <= MAX_ENTRIES,
                "ZIP_SPLIT_OR_COUNT_INVALID")
        require(offset != 0xffffffff and size != 0xffffffff and offset + size <= length - tail_size + index,
                "ZIP64_OR_DIRECTORY_INVALID")
        stream.seek(0)
        return count, offset, size
    raise PreparationError("ZIP_DIRECTORY_MISSING")


def x64_pe(prefix: bytes) -> bool:
    if len(prefix) < 64 or prefix[:2] != b"MZ":
        return False
    offset = struct.unpack_from("<I", prefix, 0x3c)[0]
    return offset + 26 <= len(prefix) and prefix[offset:offset + 4] == b"PE\0\0" and \
        struct.unpack_from("<H", prefix, offset + 4)[0] == 0x8664 and \
        struct.unpack_from("<H", prefix, offset + 24)[0] == 0x20b


def zip_inventory(path: Path, expected: str, archive_name: str, executable: str) -> tuple[dict, list[dict]]:
    path = no_links(path)
    require(stat.S_ISREG(path.stat().st_mode) and path.name == archive_name, "FULL_APP_ZIP_NAME_MISMATCH")
    rows, seen, file_names, directory_names, expanded, prefixes = [], set(), set(), set(), 0, {}
    with path.open("rb") as stream:
        actual, size = stream_digest(stream, MAX_ARCHIVE)
        require(size > 0 and actual == digest(expected), "TRUSTED_ZIP_HASH_MISMATCH")
        expected_count, directory_offset, _ = central_directory(stream, size)
        with zipfile.ZipFile(stream) as archive:
            entries = archive.infolist()
            require(len(entries) == expected_count and archive.start_dir == directory_offset, "ZIP_ENTRY_COUNT_OR_OFFSET_MISMATCH")
            for item in entries:
                name = safe_name(item.filename)
                key = name.lower()
                require(key not in seen, "ZIP_DUPLICATE_WINDOWS_PATH")
                seen.add(key)
                parts = key.split("/")
                parents = {"/".join(parts[:index]) for index in range(1, len(parts))}
                require(not parents.intersection(file_names), "ZIP_FILE_DIRECTORY_COLLISION")
                directory_names.update(parents)
                require(not item.flag_bits & 1 and item.compress_type in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED), "ZIP_ENCRYPTION_OR_COMPRESSION_UNSUPPORTED")
                unix_type = stat.S_IFMT(item.external_attr >> 16)
                require(unix_type in (0, stat.S_IFREG, stat.S_IFDIR) and not item.external_attr & 0x400,
                        "ZIP_LINK_OR_SPECIAL_FILE")
                require(not any(field == 0x0001 for field in extra_fields(item.extra)), "ZIP64_ENTRY_UNSUPPORTED")
                if item.is_dir():
                    require(item.file_size == 0, "ZIP_DIRECTORY_HAS_CONTENT")
                    require(key not in file_names, "ZIP_FILE_DIRECTORY_COLLISION")
                    directory_names.add(key)
                    continue
                require(key not in directory_names, "ZIP_FILE_DIRECTORY_COLLISION")
                file_names.add(key)
                require(0 <= item.file_size <= MAX_ENTRY and 0 <= item.compress_size <= size, "ZIP_ENTRY_SIZE_LIMIT")
                require(name.rsplit("/", 1)[-1].lower() not in CLOSED_RUNTIME_NAMES, "PRIVATE_NVIDIA_RUNTIME_MUST_NOT_ENTER_PUBLIC_FULL_APP")
                hash_value, read_bytes, prefix = hashlib.sha256(), 0, bytearray()
                with archive.open(item) as data:
                    for chunk in iter(lambda: data.read(1024 * 1024), b""):
                        read_bytes += len(chunk); expanded += len(chunk)
                        require(read_bytes <= MAX_ENTRY and expanded <= MAX_EXPANDED, "ZIP_EXPANSION_LIMIT")
                        hash_value.update(chunk)
                        if len(prefix) < 65536:
                            prefix.extend(chunk[:65536 - len(prefix)])
                require(read_bytes == item.file_size, "ZIP_ENTRY_LENGTH_MISMATCH")
                rows.append({"path": name, "bytes": read_bytes, "sha256": hash_value.hexdigest()})
                if name.lower().endswith((".exe", "/java.dll", "/jvm.dll", "/libmpv-2.dll")):
                    prefixes[key] = bytes(prefix)
        after, after_size = stream_digest(stream, MAX_ARCHIVE)
        require(after == actual and after_size == size, "ZIP_CHANGED_DURING_INSPECTION")
    executables = [row for row in rows if row["path"].rsplit("/", 1)[-1].lower() == executable.lower()]
    require(len(executables) == 1 and len(executables[0]["path"].split("/")) <= 5, "UNIQUE_FULL_APP_EXE_REQUIRED")
    exe_name = executables[0]["path"]
    root = exe_name[:-len(executable)]
    paths = {row["path"].lower(): row for row in rows}
    java_name = (root + "runtime/bin/java.dll").lower()
    jvm_name = (root + "runtime/bin/server/jvm.dll").lower()
    modules_name = (root + "runtime/lib/modules").lower()
    mpv_name = (root + "app/resources/native/windows-x64/libmpv-2.dll").lower()
    require(all(name in paths for name in (java_name, jvm_name, modules_name, mpv_name)) and
            paths[modules_name]["bytes"] > 0 and
            any(name.startswith((root + "app/").lower()) and name.endswith(".jar") and paths[name]["bytes"] > 0 for name in paths),
            "NATIVE_ONLY_OR_INCOMPLETE_APPLICATION_LAYOUT")
    require(all(x64_pe(prefixes[name]) for name in (exe_name.lower(), java_name, jvm_name, mpv_name)), "FULL_APP_PE_NOT_WINDOWS_X64")
    return {"sha256": actual, "bytes": size, "expandedBytes": expanded, "entryCount": len(rows),
            "applicationRoot": root, "executable": exe_name, "packagedMpvSha256": paths[mpv_name]["sha256"],
            "layoutInspectedNotExecuted": True}, sorted(rows, key=lambda row: row["path"].lower())


def extra_fields(raw: bytes):
    position = 0
    while position < len(raw):
        require(position + 4 <= len(raw), "ZIP_EXTRA_FIELD_INVALID")
        kind, length = struct.unpack_from("<HH", raw, position)
        position += 4
        require(position + length <= len(raw), "ZIP_EXTRA_FIELD_INVALID")
        yield kind
        position += length


def native_identity(manifest: dict, manifest_hash: str, receipt: dict, major: int) -> dict:
    require(type(manifest.get("schema")) is int and manifest["schema"] == 1 and
            type(receipt.get("schema")) is int and receipt["schema"] == 1 and
            manifest.get("variant") == receipt.get("variant") == NATIVE_VARIANT and
            manifest.get("architecture") == "windows-x64" and
            manifest.get("sourceRepository") == "https://github.com/" + SOURCE_REPOSITORY,
            "SHARED_SOURCE_IDENTITY_INVALID")
    source_commit = commit(manifest.get("sourceCommit"))
    require(receipt.get("sourceCommit") == source_commit and digest(receipt.get("sourceManifestSha256")) == manifest_hash and
            digest(receipt.get("buildClosureManifestSha256")) == digest(manifest.get("sharedBuildClosureSha256")) and
            receipt.get("actualNgxHostEngineVersion") == manifest.get("actualNgxHostEngineVersion") == "BiliPai-Veyra-Core-Shared-1",
            "SHARED_BUILD_SOURCE_MISMATCH")
    wires = manifest.get("wires")
    manifest_versions = manifest.get("adapterEngineVersions")
    receipt_versions = receipt.get("adapterEngineVersions")
    require(type(wires) is dict and type(manifest_versions) is dict and type(receipt_versions) is dict,
            "NATIVE_NESTED_MAPS_REQUIRED")
    require(type(major) is int and major in (1, 2) and wires.get("v" + str(major)) == major << 16,
            "UNSUPPORTED_RECORDED_PROTOCOL")
    version = manifest_versions.get("v" + str(major))
    require(isinstance(version, str) and version and receipt_versions.get("v" + str(major)) == version,
            "ADAPTER_ENGINE_IDENTITY_MISMATCH")
    module, checks = receipt.get("module"), receipt.get("checks")
    require(type(module) is dict and type(checks) is dict and module.get("architecture") == "windows-x64" and
            type(module.get("exports")) is list and len(module["exports"]) == 8 and set(module["exports"]) == EXPORTS,
            "ACTUAL_SHARED_BUILD_RECEIPT_REQUIRED")
    require(type(checks.get("actualObjectCompileExit")) is int and checks["actualObjectCompileExit"] == 0 and
            type(checks.get("actualDllLinkExit")) is int and checks["actualDllLinkExit"] == 0,
            "ACTUAL_BUILD_RESULT_NOT_SUCCESSFUL")
    module_size = positive(module.get("bytes"), 128 * 1024 * 1024)
    return {"sourceCommit": source_commit, "adapterBuildId": digest(module.get("sha256")),
            "engineProtocolMajor": major, "adapterEngineVersion": version,
            "reportedModuleBytes": module_size,
            "sourceManifestSha256": manifest_hash, "buildClosureManifestSha256": digest(receipt["buildClosureManifestSha256"]),
            "candidateBuildReceiptOnly": True, "moduleInspectedOrLoaded": False}


def prepare(args) -> dict:
    require(args.repository == OWN_REPOSITORY, "ONLY_CONFIGURED_OWN_REPOSITORY_ALLOWED")
    require(re.fullmatch(r"0\.2\.\d+\.\d+", args.windows_version) is not None, "CURRENT_WINDOWS_VERSION_FORMAT_REQUIRED")
    windows_source = commit(args.windows_source_commit)
    require(safe_name(args.executable) == args.executable and "/" not in args.executable, "EXECUTABLE_LEAF_REQUIRED")
    release, release_input = local_json(args.own_release_metadata, args.trusted_release_metadata_sha256)
    source, source_input = local_json(args.windows_source_evidence, args.trusted_source_evidence_sha256)
    native_manifest, native_input = local_json(args.native_source_manifest, args.trusted_native_source_manifest_sha256)
    native_receipt, receipt_input = local_json(args.native_build_receipt, args.trusted_native_build_receipt_sha256)
    archive_name = "BiliPai-Windows-" + args.windows_version + "-x64.zip"
    source_name = "BiliPai-Windows-" + args.windows_version + "-source.json"
    package, rows = zip_inventory(args.full_app_zip, args.trusted_zip_sha256, archive_name, args.executable)
    require(type(source.get("schemaVersion")) is int and source["schemaVersion"] == 1 and source.get("windowsVersion") == args.windows_version and
            source.get("windowsSourceCommit") == windows_source and source.get("zipAsset") == archive_name and
            source.get("checksumAsset") == archive_name + ".sha256" and digest(source.get("zipSha256")) == package["sha256"],
            "WINDOWS_SOURCE_EVIDENCE_MISMATCH")
    # Existing Windows publication evidence is recorded, never substituted for
    # Veyra pairing, private runtime authentication or enhanced presentation.
    reported_gates = source.get("releaseGate")
    require(type(reported_gates) is dict and reported_gates.get("passed") is True and
            all(reported_gates.get(name) == "passed" for name in GATES), "ORDINARY_FULL_APP_RELEASE_EVIDENCE_REQUIRED")
    target = release_target(release, args.repository, args.windows_version, archive_name,
                            package["bytes"], package["sha256"], source_name, source_input["sha256"], source_input["bytes"])
    native = native_identity(native_manifest, native_input["sha256"], native_receipt, args.engine_protocol_major)
    # Exactly the existing verifier's fourteen payload fields. There is no
    # switch that can promote this status or emit its Ed25519 envelope.
    payload = {"schema": 1, "repository": args.repository, "version": target["releaseTag"],
               "releaseId": target["releaseId"], "assetId": target["assetId"], "assetName": archive_name,
               "size": package["bytes"], "downloadUrl": target["downloadUrl"], "sha256": package["sha256"],
               "sourceRepository": SOURCE_REPOSITORY, "sourceCommit": native["sourceCommit"],
               "adapterBuildId": native["adapterBuildId"], "engineProtocolMajor": native["engineProtocolMajor"],
               "compatibilityStatus": PENDING}
    inventory = {"schemaVersion": 1, "state": PENDING, "ownWindowsSourceCommit": windows_source,
                 "inputs": {"ownReleaseMetadata": release_input, "windowsSourceEvidence": source_input,
                            "nativeSourceManifest": native_input, "nativeBuildReceipt": receipt_input},
                 "package": package, "target": target, "candidateNativeIdentity": native,
                 "reportedOrdinaryReleaseGates": reported_gates, "reportedGatesNotExecutedHere": True,
                 "members": rows, "limits": [
                     "A trusted receipt records a candidate build; this tool does not authenticate or inspect its DLL.",
                     "Full-app layout and CRC/content hashes are inspected, not execution or compatibility proof.",
                     "Known closed runtime filenames are rejected; this is not an exhaustive binary licensing audit.",
                     "Existing private profile/installed engine is not changed or certified by this app ZIP.",
                     "The input ZIP is rehashed on the same handle after inspection; this is a moment-in-time inventory, not a persistent file lease."]}
    output = args.output_directory
    require(output.is_absolute() and not output.exists(), "NEW_ABSOLUTE_OUTPUT_DIRECTORY_REQUIRED")
    no_links(output.parent)
    output.mkdir()  # A unique explicitly selected directory, never overwrite.
    files = {}
    for name, value in (("unsigned-payload.json", payload), ("inventory.json", inventory)):
        raw = (json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode("utf-8")
        with (output / name).open("xb") as stream:
            stream.write(raw); stream.flush(); os.fsync(stream.fileno())
        files[name] = {"sha256": hashlib.sha256(raw).hexdigest(), "bytes": len(raw)}
    result = {"schemaVersion": 1, "state": PENDING, "outputs": files, "offerValidated": False,
              "signed": False, "feedWritten": False, "published": False, "installed": False,
              "componentSelected": False, "engineStatus": "UNAVAILABLE", "gpuVerified": False}
    raw = (json.dumps(result, sort_keys=True, indent=2) + "\n").encode()
    with (output / "preparation-result.json").open("xb") as stream:
        stream.write(raw); stream.flush(); os.fsync(stream.fileno())
    return result


def parser() -> argparse.ArgumentParser:
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("--repository", default=OWN_REPOSITORY)
    cli.add_argument("--windows-version", required=True)
    cli.add_argument("--windows-source-commit", required=True)
    cli.add_argument("--full-app-zip", type=Path, required=True)
    cli.add_argument("--trusted-zip-sha256", required=True)
    cli.add_argument("--own-release-metadata", type=Path, required=True)
    cli.add_argument("--trusted-release-metadata-sha256", required=True)
    cli.add_argument("--windows-source-evidence", type=Path, required=True)
    cli.add_argument("--trusted-source-evidence-sha256", required=True)
    cli.add_argument("--native-source-manifest", type=Path, required=True)
    cli.add_argument("--trusted-native-source-manifest-sha256", required=True)
    cli.add_argument("--native-build-receipt", type=Path, required=True)
    cli.add_argument("--trusted-native-build-receipt-sha256", required=True)
    cli.add_argument("--engine-protocol-major", type=int, choices=(1, 2), default=1)
    cli.add_argument("--executable", default="BiliPai Windows.exe")
    cli.add_argument("--output-directory", type=Path, required=True)
    # Signing/network/feed/publication options intentionally do not exist.
    return cli


def main() -> int:
    args = parser().parse_args()
    try:
        result = prepare(args)
    except PreparationError as error:
        print(json.dumps({"state": "REJECTED", "failureCode": str(error), "offerValidated": False}))
        return 2
    except (OSError, ValueError, KeyError, TypeError, RecursionError, zipfile.BadZipFile, zlib.error, NotImplementedError):
        print(json.dumps({"state": "REJECTED", "failureCode": "INVALID_LOCAL_INPUT_OR_LAYOUT", "offerValidated": False}))
        return 2
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
