#!/usr/bin/env python3
"""Apply the reviewed private presentation graph to one isolated fixed MPV tree.
No network, SDK, native code, UI, tests or binary execution.
All full-file identities and forward/inverse checks precede the first write.
"""
import hashlib
import json
from pathlib import Path
import subprocess
import sys

EXPECTED_MANIFEST_SHA256 = "aaf557d7ccfed93d52fb2cbd9a0a40101bdce276894fdab44cb0adff88562363"
VARIANT = "bilipai-veyra-rtx-present-v1"
SOURCE_COMMIT = "69e63f425a531f814431fba12750bdb3721357f2"

def sha(data):
    return hashlib.sha256(data).hexdigest()

def unique(pairs):
    out = {}
    for key, value in pairs:
        if key in out:
            raise ValueError("Duplicate JSON field")
        out[key] = value
    return out

def bounded(path, limit=2 * 1024 * 1024):
    if path.is_symlink() or path.stat().st_size > limit:
        raise ValueError("Unreviewed source material")
    data = path.read_bytes()
    if len(data) > limit:
        raise ValueError("Source material exceeds bound")
    return data

def safe_target(root, relative):
    parts = Path(relative).parts
    if not parts or Path(relative).is_absolute() or any(p in ("", ".", "..") for p in parts):
        raise ValueError("Unsafe source target")
    target = root.joinpath(*parts)
    for item in (root, *target.parents, target):
        if item != root and root not in item.parents:
            continue
        # lstat also rejects dangling symlinks/junctions before any write.
        try:
            attrs = item.lstat()
        except FileNotFoundError:
            continue
        if item.is_symlink() or getattr(attrs, "st_file_attributes", 0) & 0x400:
            raise ValueError("Reparse source target")
    if root not in target.resolve().parents:
        raise ValueError("Target escaped isolated source root")
    return target

def main():
    if len(sys.argv) != 2:
        raise SystemExit("One isolated fixed MPV source directory required")
    requested = Path(sys.argv[1])
    if not requested.is_absolute():
        raise ValueError("Explicit absolute source root required")
    root = requested.resolve(strict=True)
    if not root.is_dir() or root.is_symlink() or getattr(root.stat(), "st_file_attributes", 0) & 0x400:
        raise ValueError("Unreviewed source root")
    here = Path(__file__).resolve().parent
    manifest_raw = bounded(here / "bilipai-rtx-presentation-source-manifest.json")
    if sha(manifest_raw) != EXPECTED_MANIFEST_SHA256:
        raise ValueError("Presentation manifest changed")
    manifest = json.loads(manifest_raw, object_pairs_hook=unique)
    if manifest["schema"] != 2 or manifest["variant"] != VARIANT or manifest["sourceCommit"] != SOURCE_COMMIT or type(manifest.get("tokenProtocol")) is not int or manifest["tokenProtocol"] != 2:
        raise ValueError("Wrong source protocol")
    head = subprocess.check_output(
        ["git", "-c", "safe.directory=" + str(root), "rev-parse", "HEAD"],
        cwd=root, text=True).strip()
    if head != SOURCE_COMMIT:
        raise ValueError("Wrong MPV source commit")
    registration_raw = bounded(here / manifest["registrationEditsFileName"])
    upstream_raw = bounded(here / manifest["upstreamEditsFileName"])
    if sha(registration_raw) != manifest["registrationEditsSha256"] or sha(upstream_raw) != manifest["upstreamEditsSha256"]:
        raise ValueError("Reviewed source edits changed")
    registrations = json.loads(registration_raw, object_pairs_hook=unique)
    upstream = json.loads(upstream_raw, object_pairs_hook=unique)
    if len(registrations) != 3 or len(upstream) != 24 or len(manifest["sourceFiles"]) != 17:
        raise ValueError("Incomplete reviewed graph")
    nvidia = manifest["originalNvidiaPatch"]
    if sha(bounded(here / "bilipai-nvidia-native-69e63f.patch")) != nvidia["patchSha256"]:
        raise ValueError("Original NVIDIA source patch changed")
    allowed = {nvidia["sourcePath"], ".bilipai-native-patch-receipt.json"}
    allowed.update(r["path"] for r in registrations)
    allowed.update(r["path"] for r in upstream)
    allowed.update(r["targetPath"] for r in manifest["sourceFiles"])
    status = subprocess.check_output(
        ["git", "-c", "safe.directory=" + str(root), "status", "--porcelain", "-z", "--untracked-files=all"],
        cwd=root).split(b"\0")
    for item in status:
        if not item:
            continue
        if len(item) < 4 or item[:2].find(b"R") >= 0 or item[:2].find(b"C") >= 0:
            raise ValueError("Unreviewed source status")
        if item[3:].decode("utf-8", "strict") not in allowed:
            raise ValueError("Unrelated altered source")
    pending = {}
    proof = []
    def put(path, data):
        target = safe_target(root, path)
        if target in pending:
            raise ValueError("Duplicate source mutation")
        pending[target] = data
    def replace_full(path, before_sha, after_sha, operations):
        target = safe_target(root, path)
        raw = bounded(target)
        if sha(raw) == after_sha:
            result = raw
        elif sha(raw) == before_sha:
            result = raw
            for op in operations:
                old, new = op["old"].encode(), op["new"].encode()
                if op["count"] != 1 or result.count(old) != 1:
                    raise ValueError("Nonunique source anchor")
                result = result.replace(old, new, 1)
            if sha(result) != after_sha:
                raise ValueError("Whole forward source identity changed")
            inverse = result
            for op in reversed(operations):
                old, new = op["old"].encode(), op["new"].encode()
                if inverse.count(new) != 1:
                    raise ValueError("Nonunique source inverse")
                inverse = inverse.replace(new, old, 1)
            if inverse != raw:
                raise ValueError("Whole source inverse differs")
        else:
            raise ValueError("Unreviewed full source bytes")
        put(path, result)
        proof.append({"path": path, "beforeSha256": before_sha, "afterSha256": after_sha})
    replace_full(nvidia["sourcePath"], nvidia["originalSha256"], nvidia["patchedSha256"],
        [{"old": nvidia["old"], "new": nvidia["new"], "count": 1}])
    for row in registrations:
        replace_full(row["path"], row["beforeSHA256"], row["afterSHA256"],
            [{"old": row["before"], "new": row["after"], "count": 1}])
    for row in upstream:
        replace_full(row["path"], row["beforeSha256"], row["afterSha256"], row["edits"])
    for row in manifest["sourceFiles"]:
        # fileName is a fixed trusted manifest leaf, never a caller path.
        if Path(row["fileName"]).name != row["fileName"]:
            raise ValueError("Invalid reviewed source leaf")
        data = bounded(here / "bridge-source" / row["fileName"])
        target = safe_target(root, row["targetPath"])
        if sha(data) != row["sha256"] or len(data) != row["bytes"]:
            raise ValueError("Private source identity differs")
        if target.exists() and bounded(target) != data:
            raise ValueError("Existing unrelated private source")
        put(row["targetPath"], data)
    receipt_target = safe_target(root, ".bilipai-native-patch-receipt.json")
    if receipt_target.exists():
        if not receipt_target.is_file():
            raise ValueError("Existing receipt is not an ordinary source file")
        bounded(receipt_target)
    # This modifies only an isolated source tree. A failed write leaves that tree
    # incomplete; do not use it for a build until a fresh invocation fully passes.
    for target, data in pending.items():
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
    for target, data in pending.items():
        if bounded(target) != data:
            raise ValueError("Source readback differs")
    receipt = {
        "schema": 3, "patchId": VARIANT, "sourceCommit": SOURCE_COMMIT,
        "filterName": manifest["filterName"], "filterSourceManifestSha256": EXPECTED_MANIFEST_SHA256,
        "coreAbiHeaderSha256": manifest["coreAbiHeaderSha256"], "filterSourceFiles": manifest["sourceFiles"],
        "sourceGraph": proof, "presentationProperty": manifest["presentationProperty"],
        "tokenProtocol": 2, "sourcePatchHelperSha256": sha(bounded(Path(__file__))),
        "nativeBinaryBuiltByThisProgram": False, "gpuExecuted": False,
        "displayProofRuntimeVerified": False, "appConsumerIntegrated": False,
    }
    receipt_raw = (json.dumps(receipt, sort_keys=True, indent=2) + "\n").encode("utf-8")
    if safe_target(root, ".bilipai-native-patch-receipt.json") != receipt_target:
        raise ValueError("Receipt target changed")
    receipt_target.write_bytes(receipt_raw)
    if bounded(receipt_target) != receipt_raw:
        raise ValueError("Source receipt readback changed")
if __name__ == "__main__":
    main()
