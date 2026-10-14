from pathlib import Path
import importlib.util
import json
import os
import struct
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location("veyra_core_builder", REPO / "desktop/tools/native/build-veyra-core-runtime.py")
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)


def abi_image():
    # Minimal PE32+ fixture: separate executable text and export data sections.
    raw = bytearray(2048)
    raw[:2] = b"MZ"
    struct.pack_into("<I", raw, 60, 128)
    raw[128:132] = b"PE\0\0"
    struct.pack_into("<HH", raw, 132, 0x8664, 2)
    struct.pack_into("<HH", raw, 148, 240, 0x2000)
    struct.pack_into("<H", raw, 152, 0x20b)
    struct.pack_into("<I", raw, 260, 16)
    struct.pack_into("<II", raw, 264, 0x2000, 512)
    for at, rva, offset, flags in [(392, 0x1000, 512, 0x60000020), (432, 0x2000, 1024, 0x40000040)]:
        struct.pack_into("<IIII", raw, at+8, 512, rva, 512, offset)
        struct.pack_into("<I", raw, at+36, flags)
    names = sorted(builder.EXPORTS)
    struct.pack_into("<IIIII", raw, 1044, len(names), len(names), 0x2040, 0x2080, 0x20c0)
    cursor = 1248
    for i, name in enumerate(names):
        struct.pack_into("<I", raw, 1088+i*4, 0x1000+i*8)
        struct.pack_into("<I", raw, 1152+i*4, 0x2000+cursor-1024)
        struct.pack_into("<H", raw, 1216+i*2, i)
        text = name.encode()+b"\0"
        raw[cursor:cursor+len(text)] = text
        cursor += len(text)
    return raw


class VeyraCoreRuntimeBuildTest(unittest.TestCase):
    def test_actual_shared_host_sdk_and_source_closure_are_exact(self):
        closure, sdk, pins = builder.inputs(REPO)
        self.assertEqual(30, len(pins))
        self.assertEqual(8, len(closure["ownedSources"]))
        self.assertEqual(6, len(sdk["headers"]))
        self.assertEqual(8, len(builder.sdk_entries(sdk)))
        self.assertEqual(closure["sdkCommit"], builder.SDK_COMMIT)

    def test_raw_manifest_and_sdk_git_blob_changes_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            p = Path(directory)/"input"
            p.write_bytes(b"actual SDK bytes")
            row = {"sha256":builder.sha(p.read_bytes()),"gitBlobSha1":"0"*40}
            with self.assertRaisesRegex(ValueError, "Git blob changed"):
                builder.checked_file(p, row)
            with self.assertRaisesRegex(ValueError, "manifest changed"):
                builder.fixed_json(p, "0"*64)

    def test_unknown_or_ambiguous_paths_cannot_escape_the_sdk_layout(self):
        for value in ("../sdk", "/sdk", "C:/sdk", "a\\b", "a/../b", "a//b", "./sdk"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                builder.relative_path(value)

    def test_all_eight_real_local_abi_entrypoints_are_required(self):
        raw = abi_image()
        self.assertEqual(sorted(builder.EXPORTS), builder.pe_exports(raw))
        struct.pack_into("<I", raw, 1088, 0x2040)
        with self.assertRaisesRegex(ValueError, "local executable code"):
            builder.pe_exports(raw)

    def test_wrong_architecture_truncation_and_missing_abi_are_rejected(self):
        raw = abi_image()
        for broken in (raw[:400], bytearray(raw)):
            if len(broken) > 400:
                struct.pack_into("<H", broken, 132, 0x14c)
            with self.assertRaises(ValueError):
                builder.pe_exports(broken)
        raw[1248:1251] = b"xxx"
        with self.assertRaisesRegex(ValueError, "incomplete"):
            builder.pe_exports(raw)

    def test_linking_is_unavailable_to_normal_local_execution(self):
        self.assertFalse(builder.cloud_build_allowed({}))
        environment = dict(GITHUB_ACTIONS="true", GITHUB_REPOSITORY="jay3-yy/BiliPai", RUNNER_ENVIRONMENT="github-hosted", RUNNER_OS="Windows")
        self.assertFalse(builder.cloud_build_allowed(environment))


if __name__ == "__main__":
    unittest.main()
