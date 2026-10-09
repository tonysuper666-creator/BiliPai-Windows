#!/usr/bin/env python3
"""Offline own full-app catalog validation; signing is explicit and stdin-only.

No network/publication, DPAPI/key provider, native loading or component activation.
Missing independently hashed acceptance inputs remain PENDING without reading a
key. Reports are operator-provided evidence, not proof this tool ran their checks.
The unchanged unsigned preparer remains a separate PENDING-only entry point.
"""
from __future__ import annotations

import base64
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import zipfile
import zlib

KEY_ID = "veyra-compatible-v1-102b3faa4e9e82a5"
PREPARER_SHA256 = "f208765b7e47d10b516a98250c737d0251935fac029d01b30a32af6662c75720"
SIGNER_SHA256 = "e2e623518b236859d32eefa42cb3167bea56923454c81939932894fd6fb4ed09"
TEMPLATE_SHA256 = "03bc600238d6d691056597826216ab8b47bbbf6ea25cbf1fe10b15b0d19f7278"
VERIFIER_SHA256 = "e6eeb8d3ea223fa31d1655a9a9a5960cf9bd753a7dacb5406ad0e4d21f428927"
VERIFIED = "VALIDATED_FULL_APPLICATION_BUNDLE"
PAYLOAD_FIELDS = {"schema", "repository", "version", "releaseId", "assetId", "assetName", "size",
                  "downloadUrl", "sha256", "sourceRepository", "sourceCommit", "adapterBuildId",
                  "engineProtocolMajor", "compatibilityStatus"}
EVIDENCE_INPUTS = ("release_gate", "player_report", "updater_report", "component_receipt", "compatibility_report")
ACCEPTANCE_CHECKS = ("packagedApplicationStartup", "packagedMpvIdentity", "nativeAbiCompatibility",
                     "accountStorePreserved", "updateDeferredDuringPlayback", "lastKnownGoodRollback",
                     "privateEngineSelectionPreserved")


class CatalogError(ValueError):
    """Portable rejection code; never secret/path/report contents."""


def require(condition, code):
    if not condition:
        raise CatalogError(code)


def load_preparer():
    path = Path(__file__).with_name("prepare-veyra-compatible-payload.py")
    require(path.is_file() and hashlib.sha256(path.read_bytes()).hexdigest() == PREPARER_SHA256,
            "PENDING_PREPARER_SOURCE_MISMATCH")
    spec = importlib.util.spec_from_file_location("veyra_pending_payload", path)
    require(spec is not None and spec.loader is not None, "PENDING_PREPARER_UNAVAILABLE")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def parse_object(raw, pending):
    value = json.loads(raw.decode("utf-8"), object_pairs_hook=pending.unique_object,
                       parse_constant=pending.reject_json_constant)
    require(type(value) is dict, "CATALOG_OBJECT_REQUIRED")
    return value


def leaf(mode, payload, extra, java_executable):
    require(1 <= len(payload) <= 128 * 1024 and mode in ("sign", "verify"), "SIGNATURE_INPUT_INVALID")
    # Pin canonical Git LF; ordinary Windows checkouts may materialize CRLF.
    source = Path(__file__).with_name("VeyraCatalogEd25519.java").read_bytes().replace(b"\r\n", b"\n")
    require(hashlib.sha256(source).hexdigest() == SIGNER_SHA256, "SIGNER_SOURCE_MISMATCH")
    frame = bytearray(struct.pack(">II", 0x42564331, len(payload)) + payload + struct.pack(">I", len(extra)) + extra)
    environment = os.environ.copy()
    for name in ("GH_TOKEN", "GITHUB_TOKEN", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"):
        environment.pop(name, None)
    try:
        # Execute only the hash-pinned source copy. No key in argv, env or disk.
        with tempfile.TemporaryDirectory(prefix="bilipai-catalog-leaf-") as temporary:
            copied = Path(temporary) / "VeyraCatalogEd25519.java"
            copied.write_bytes(source)
            child = subprocess.run([str(java_executable), "--source", "21", str(copied), mode],
                                   input=frame, capture_output=True, timeout=30, env=environment)
        require(child.returncode == 0 and not child.stderr and len(child.stdout) <= 256 * 1024,
                "CATALOG_SIGNATURE_REJECTED")
        return child.stdout
    except (OSError, subprocess.SubprocessError):
        raise CatalogError("CATALOG_SIGNER_UNAVAILABLE") from None
    finally:
        frame[:] = b"\0" * len(frame)
        # Python/JDK may retain copies; this is not a secure heap erasure claim.


def verify_envelope(raw, expected, pending, java_executable):
    require(0 < len(raw) <= 256 * 1024, "CATALOG_ENVELOPE_SIZE_INVALID")
    envelope = parse_object(raw, pending)
    require(set(envelope) == {"schema", "keyId", "payloadBase64", "signatureBase64"} and
            type(envelope.get("schema")) is int and envelope["schema"] == 1 and envelope.get("keyId") == KEY_ID,
            "CATALOG_ENVELOPE_FIELDS_INVALID")
    require(type(envelope.get("payloadBase64")) is str and type(envelope.get("signatureBase64")) is str,
            "CATALOG_BASE64_REQUIRED")
    payload = base64.b64decode(envelope["payloadBase64"], validate=True)
    signature = base64.b64decode(envelope["signatureBase64"], validate=True)
    require(1 <= len(payload) <= 128 * 1024 and len(signature) == 64, "CATALOG_SIGNATURE_SIZE_INVALID")
    manifest = parse_object(payload, pending)
    require(set(manifest) == PAYLOAD_FIELDS and type(manifest.get("schema")) is int and manifest["schema"] == 1 and
            manifest.get("compatibilityStatus") == VERIFIED and manifest.get("repository") == pending.OWN_REPOSITORY and
            manifest.get("sourceRepository") == pending.SOURCE_REPOSITORY,
            "CATALOG_PAYLOAD_FIELDS_INVALID")
    for name in ("releaseId", "assetId", "size", "engineProtocolMajor"):
        pending.positive(manifest.get(name))
    pending.commit(manifest.get("sourceCommit"))
    require(manifest.get("sha256") == pending.digest(manifest.get("sha256")) and
            isinstance(manifest.get("adapterBuildId"), str) and manifest["adapterBuildId"].strip(),
            "CATALOG_SOURCE_IDENTITY_INVALID")
    require(all(manifest.get(name) == value for name, value in expected.items()), "CATALOG_EXACT_TARGET_MISMATCH")
    require(leaf("verify", payload, signature, java_executable) == b"VERIFIED\n", "CATALOG_SIGNATURE_REJECTED")
    return manifest


def signed_directory(directory, trusted_sha256, expected, java_executable="java"):
    """Publisher call: public-key verification only, no signing/key read."""
    pending = load_preparer()
    directory = pending.no_links(directory)
    require(directory.is_dir(), "CATALOG_DIRECTORY_REQUIRED")
    name = expected["assetName"] + ".veyra-compatible.json"
    require({item.name for item in directory.iterdir()} == {name, name + ".sha256"}, "CATALOG_DIRECTORY_EXACT_TWO_FILES_REQUIRED")
    path = pending.no_links(directory / name)
    checksum_path = pending.no_links(directory / (name + ".sha256"))
    require(path.is_file() and checksum_path.is_file(), "CATALOG_REGULAR_FILES_REQUIRED")
    with path.open("rb") as stream:
        raw = stream.read(256 * 1024 + 1)
    require(0 < len(raw) <= 256 * 1024 and hashlib.sha256(raw).hexdigest() == pending.digest(trusted_sha256),
            "CATALOG_TRUSTED_HASH_MISMATCH")
    expected_sidecar = (pending.digest(trusted_sha256) + "  " + name + "\n").encode("ascii")
    with checksum_path.open("rb") as stream:
        require(stream.read(512) == expected_sidecar, "CATALOG_SIDECAR_MISMATCH")
    manifest = verify_envelope(raw, expected, pending, java_executable)
    return path, raw, manifest


def acceptance(args, pending, inventory):
    reports, inputs, missing = {}, {}, []
    for name in EVIDENCE_INPUTS:
        path, digest = getattr(args, name), getattr(args, "trusted_" + name + "_sha256")
        require((path is None) == (digest is None), "REPORT_AND_INDEPENDENT_HASH_MUST_BE_PAIRED")
        if path is None:
            missing.append(name)
        else:
            reports[name], inputs[name] = pending.local_json(path, digest)
    if missing:
        return None, inputs, missing
    identity = inventory["candidateNativeIdentity"]
    package = inventory["package"]
    source = inventory["ownWindowsSourceCommit"]
    require(inventory["target"].get("draft") is True, "SIGNING_REQUIRES_UNPUBLISHED_OWN_DRAFT")
    gate = reports["release_gate"]
    require(gate.get("passed") is True and all(gate.get(name) == "passed" for name in pending.GATES) and
            gate.get("windowsSourceCommit") == source and gate.get("windowsVersion") == args.windows_version and
            pending.digest(gate.get("portableZipSha256")) == package["sha256"], "ACTUAL_RELEASE_GATE_MISMATCH")
    player, updater = reports["player_report"], reports["updater_report"]
    require(player.get("passed") is True and player.get("nativeVideoRendering") == "passed" and
            player.get("nativeFrameScreenshot") == "passed" and player.get("seekCompletionEvent") == "passed" and
            player.get("nativeClosed") == "true", "PACKAGED_PLAYER_HEALTH_NOT_PASSED")
    require(updater.get("passed") is True and updater.get("evidenceType") == "isolated-packaged-updater" and
            updater.get("windowsVersion") == args.windows_version and
            pending.digest(updater.get("portableZipSha256")) == package["sha256"], "PACKAGED_UPDATER_HEALTH_MISMATCH")
    template_path = Path(__file__).resolve().parents[1] / "native/veyra/veyra-presentation-profile-template.json"
    template, _ = pending.local_json(template_path, TEMPLATE_SHA256)
    require(identity["sourceCommit"] == template["veyraSourceCommit"], "FIXED_VEYRA_SOURCE_MISMATCH")
    receipt = reports["component_receipt"]
    checked = receipt.get("checked")
    require(type(receipt.get("schema")) is int and receipt["schema"] == 1 and receipt.get("status") == "VERIFIED" and
            receipt.get("engineStatus") == "AVAILABLE" and type(checked) is dict,
            "ACTUAL_COMPONENT_AUTHENTICATION_REQUIRED")
    require(pending.digest(checked.get("moduleBuildSha256")) == identity["adapterBuildId"] and
            pending.digest(checked.get("mpvDllSha256")) == package["packagedMpvSha256"] and
            pending.digest(checked.get("nativeBuildReceiptSha256")) == inventory["inputs"]["nativeBuildReceipt"]["sha256"] and
            checked.get("nativeVariant") == pending.NATIVE_VARIANT and
            checked.get("ngxHostEngineVersion") == template["ngxHostEngineVersion"] and
            type(checked.get("coreAbiWire")) is int and checked["coreAbiWire"] == 65536 and
            type(checked.get("coreAbi")) is int and checked["coreAbi"] == 1 and
            checked.get("producerVariant") == template["producerVariant"] and
            type(checked.get("presentationProtocolVersion")) is int and
            checked["presentationProtocolVersion"] == template["presentationProtocolVersion"], "COMPONENT_PACKAGE_PAIRING_MISMATCH")
    pending.digest(checked.get("profileSha256"))
    for name in ("coreSourceSha256", "headerSha256", "filterSourceManifestSha256", "sourcePatchHelperSha256", "upstreamEditsSha256"):
        require(pending.digest(checked.get(name)) == pending.digest(template.get(name)), "FIXED_COMPONENT_SOURCE_MISMATCH")
    shared = checked.get("sharedSourceIdentity")
    require(type(shared) is dict and type(template.get("sharedSourceIdentity")) is dict and
            set(shared) == set(template["sharedSourceIdentity"]) and
            all(pending.digest(shared[name]) == pending.digest(value) for name, value in template["sharedSourceIdentity"].items()),
            "FIXED_SHARED_BUILD_CLOSURE_MISMATCH")
    require(pending.digest(shared["sourceManifestSha256"]) == identity["sourceManifestSha256"] and
            pending.digest(shared["buildClosureManifestSha256"]) == identity["buildClosureManifestSha256"], "NATIVE_INPUT_CLOSURE_MISMATCH")
    source_path = Path(__file__).resolve().parents[2] / "third-party/libmpv/build/rtx-present-v1/bilipai-rtx-presentation-source-manifest.json"
    mpv_source, _ = pending.local_json(source_path, template["filterSourceManifestSha256"])
    require(mpv_source.get("variant") == template["producerVariant"] and
            mpv_source.get("tokenProtocol") == template["presentationProtocolVersion"] and
            pending.digest(mpv_source.get("upstreamEditsSha256")) == pending.digest(template["upstreamEditsSha256"]),
            "CURRENT_MPV_SOURCE_GRAPH_MISMATCH")
    members = {row["path"].lower(): row for row in inventory["members"]}
    native_root = package["applicationRoot"] + "app/resources/native/windows-x64/"
    package_pins = {
        native_root + "licenses/native-patch-receipt.json": pending.digest(checked.get("mpvNativeReceiptSha256")),
        native_root + "licenses/rtx-filter-source-manifest.json": pending.digest(template["filterSourceManifestSha256"]),
        native_root + "licenses/rtx-presentation-edits.json": pending.digest(template["upstreamEditsSha256"]),
        native_root + "licenses/rtx-registration-edits.json": pending.digest(mpv_source["registrationEditsSha256"]),
        package["applicationRoot"] + "app/resources/native/veyra-core/verify-veyra-runtime.ps1": VERIFIER_SHA256,
    }
    require(all(path.lower() in members and members[path.lower()]["sha256"] == value for path, value in package_pins.items()),
            "FULL_APP_FIXED_NATIVE_SOURCE_OR_VERIFIER_MISMATCH")
    runtime = checked.get("runtimeFiles")
    require(type(runtime) is list and len(runtime) == 2 and all(type(row) is dict for row in runtime), "AUTHENTICATED_RUNTIME_INVENTORY_REQUIRED")
    runtime_rows = {row.get("relativePath"): row for row in runtime}
    require(len(runtime_rows) == 2 and set(runtime_rows) == {row["relativePath"] for row in template["runtimeFiles"]}, "RUNTIME_FILE_SET_MISMATCH")
    for expected in template["runtimeFiles"]:
        actual = runtime_rows[expected["relativePath"]]
        require(pending.digest(actual.get("sha256")) == pending.digest(expected["sha256"]) and
                type(actual.get("bytes")) is int and actual["bytes"] == expected["bytes"] and
                actual.get("fileVersion") == expected["fileVersion"] and actual.get("authenticodeStatus") == "Valid" and
                actual.get("signerSubject") == expected["nvidiaSigner"]["subject"] and
                isinstance(actual.get("signerThumbprint"), str) and
                actual.get("signerThumbprint", "").upper() == expected["nvidiaSigner"]["thumbprint"].upper(), "NVIDIA_RUNTIME_PROVENANCE_MISMATCH")
    report = reports["compatibility_report"]
    require(type(report.get("schema")) is int and report["schema"] == 1 and report.get("passed") is True and
            report.get("evidenceType") == "own-packaged-veyra-compatibility" and
            report.get("windowsVersion") == args.windows_version and report.get("windowsSourceCommit") == source and
            type(report.get("engineProtocolMajor")) is int and report["engineProtocolMajor"] == identity["engineProtocolMajor"] and
            report.get("veyraSourceCommit") == identity["sourceCommit"] and
            pending.digest(report.get("portableZipSha256")) == package["sha256"] and
            pending.digest(report.get("packagedMpvSha256")) == package["packagedMpvSha256"] and
            pending.digest(report.get("moduleBuildSha256")) == identity["adapterBuildId"] and
            pending.digest(report.get("nativeBuildReceiptSha256")) == inventory["inputs"]["nativeBuildReceipt"]["sha256"],
            "EXPLICIT_FULL_APP_COMPATIBILITY_ACCEPTANCE_REQUIRED")
    evidence_hashes, checks = report.get("evidenceSha256"), report.get("checks")
    require(type(evidence_hashes) is dict and set(evidence_hashes) == set(EVIDENCE_INPUTS) - {"compatibility_report"} and
            all(pending.digest(evidence_hashes[name]) == inputs[name]["sha256"] for name in evidence_hashes) and
            type(checks) is dict and set(checks) == set(ACCEPTANCE_CHECKS) and
            all(value == "passed" for value in checks.values()), "COMPATIBILITY_ACCEPTANCE_EVIDENCE_NOT_COMPLETE")
    # These attest app compatibility only. No public ZIP may contain SDK runtimes,
    # and no result selects a private engine or certifies its GPU/presentation.
    return report, inputs, []


def write_new(path, value):
    raw = (json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode("utf-8")
    with path.open("xb") as stream:
        stream.write(raw); stream.flush(); os.fsync(stream.fileno())


def prepare(args, pending):
    pending.prepare(args)  # Original full ZIP/CRC/PE/source/asset inventory stays PENDING.
    payload = parse_object((args.output_directory / "unsigned-payload.json").read_bytes(), pending)
    inventory = parse_object((args.output_directory / "inventory.json").read_bytes(), pending)
    report, inputs, missing = acceptance(args, pending, inventory)
    result = {"schema": 1, "state": pending.PENDING, "signed": False, "offerValidated": False,
              "missingEvidence": missing, "reportedEvidence": inputs, "checksExecutedHere": False,
              "published": False, "feedWritten": False, "installed": False, "componentSelected": False,
              "privateEngineActivated": False, "gpuVerified": False,
              "localDpapiPkcs8ProviderImplemented": False}
    if report is not None:
        result["state"] = "FULL_APP_ACCEPTANCE_RECORDED_AWAITING_SIGNATURE"
        if args.sign_from_pkcs8_stdin:
            # No missing material can reach this key read. Caller/provider sends
            # raw DER only, not a DPAPI blob/base64/filename; provider is not supplied.
            private_der = bytearray(sys.stdin.buffer.read(4097))
            try:
                require(1 <= len(private_der) <= 4096, "PKCS8_STDIN_SIZE_INVALID")
                selected = {**payload, "compatibilityStatus": VERIFIED}
                encoded = (json.dumps(selected, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8")
                signed = leaf("sign", encoded, private_der, args.java_executable)
                verify_envelope(signed, selected, pending, args.java_executable)
                output = args.output_directory / "signed-catalog"
                output.mkdir()
                name = payload["assetName"] + ".veyra-compatible.json"
                with (output / name).open("xb") as stream:
                    stream.write(signed); stream.flush(); os.fsync(stream.fileno())
                catalog_sha = hashlib.sha256(signed).hexdigest()
                with (output / (name + ".sha256")).open("xb") as stream:
                    stream.write((catalog_sha + "  " + name + "\n").encode("ascii")); stream.flush(); os.fsync(stream.fileno())
                result.update(state="SIGNED_FULL_APPLICATION_CATALOG", signed=True, offerValidated=True,
                              catalogSha256=catalog_sha, catalogBytes=len(signed), catalogAssetName=name)
            finally:
                private_der[:] = b"\0" * len(private_der)
    write_new(args.output_directory / "catalog-preparation-result.json", result)
    return result


def main():
    try:
        pending = load_preparer()
        cli = pending.parser()
        cli.description = __doc__
        cli.add_argument("--sign-from-pkcs8-stdin", action="store_true")
        cli.add_argument("--java-executable", default="java")
        for name in EVIDENCE_INPUTS:
            cli.add_argument("--" + name.replace("_", "-"), type=Path)
            cli.add_argument("--trusted-" + name.replace("_", "-") + "-sha256")
        result = prepare(cli.parse_args(), pending)
    except (CatalogError, ValueError, OSError, KeyError, TypeError, RecursionError, zipfile.BadZipFile, zlib.error, NotImplementedError):
        # No raw input, key/provider/process exception or report pathname escapes.
        print(json.dumps({"state": "REJECTED", "signed": False, "offerValidated": False,
                          "failureCode": "CATALOG_INPUT_OR_SIGNATURE_REJECTED"}))
        return 2
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
