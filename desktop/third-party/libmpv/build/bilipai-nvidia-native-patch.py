#!/usr/bin/env python3
"""Build-time fixed-source patch gate; this program has not been executed in this handoff."""
import hashlib
import json
import subprocess
import sys
from pathlib import Path

SOURCE_COMMIT = '69e63f425a531f814431fba12750bdb3721357f2'
ORIGINAL_SHA256 = '9514d40109894e0bee4e4a2aa343a4e4e4d2368aee3729959180bf5f92d486c3'
PATCHED_SHA256 = '1669c96fc95cfd7276a3149aa2d76058d29b2cc2dd848c77b949d434831c6d2f'
PATCH_SHA256 = 'e3599ec5fe4326a6713093e9834514f7c2b001b41f30c03fc26fc763b3345d30'
PATCH_ID = "bilipai-nvidia-native-v1"
SOURCE_PATH = "video/filter/vf_d3d11vpp.c"
OLD = b"    if (!mp_refqueue_should_deint(p->queue) && !p->require_filtering) {\n"
NEW = (b"    if (!mp_refqueue_should_deint(p->queue) && !p->require_filtering &&\n"
       b"        p->opts->scaling_mode != SCALING_NVIDIA_RTX) {\n")

def sha(data):
    return hashlib.sha256(data).hexdigest()

def main():
    if len(sys.argv) != 2:
        raise SystemExit("Expected one isolated mpv source directory.")
    root = Path(sys.argv[1]).resolve(strict=True)
    head = subprocess.check_output(["git", "-C", str(root), "rev-parse", "HEAD"], text=True).strip()
    if head != SOURCE_COMMIT:
        raise SystemExit("Wrong mpv source commit; patch is not applied.")
    patch_file = Path(__file__).with_name("bilipai-nvidia-native-69e63f.patch")
    if sha(patch_file.read_bytes()) != PATCH_SHA256:
        raise SystemExit("Wrong native patch bytes.")
    source = root / SOURCE_PATH
    data = source.read_bytes()
    if sha(data) == ORIGINAL_SHA256:
        if data.count(OLD) != 1:
            raise SystemExit("Native patch anchor is not unique.")
        updated = data.replace(OLD, NEW, 1)
        if sha(updated) != PATCHED_SHA256 or updated.replace(NEW, OLD, 1) != data:
            raise SystemExit("Native patch failed fixed full-file forward/inverse validation.")
        source.write_bytes(updated)
    elif sha(data) != PATCHED_SHA256:
        raise SystemExit("mpv C file differs from both fixed original and this exact patch.")
    if sha(source.read_bytes()) != PATCHED_SHA256:
        raise SystemExit("Patched native source readback differs.")
    # The build copies this source receipt beside the actual DLL before upstream cleanup.
    # It intentionally carries no DLL hash, driver result or Active assertion.
    receipt = {"schema": 1, "patchId": PATCH_ID, "sourceCommit": SOURCE_COMMIT,
               "sourcePath": SOURCE_PATH, "originalSourceSha256": ORIGINAL_SHA256,
               "patchedSourceSha256": PATCHED_SHA256, "patchSha256": PATCH_SHA256,
               "nativeBinaryBuiltByThisProgram": False, "ppeNativeResolutionVerified": False}
    (root / ".bilipai-native-patch-receipt.json").write_text(
        json.dumps(receipt, sort_keys=True, indent=2) + "\n", encoding="utf-8")

if __name__ == "__main__":
    main()
