"""CPU regressions for fixed-source curl/OpenSSL witnesses; no native qualification.

Optional BILIPAI_NATIVE_SOURCE_REFERENCE_DIR supplies immutable offline reference
bytes. Generated-header fixtures below are synthetic and never install evidence.
"""
import ast
import importlib.util
import json
import os
from pathlib import Path
import re
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
_PATH = Path(__file__).resolve().parents[1] / "native" / "patch-curl-openssl-asn1-compat.py"
_SPEC = importlib.util.spec_from_file_location("curl_openssl_source_under_test", _PATH)
COMPAT = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(COMPAT)
_REFERENCE = os.environ.get("BILIPAI_NATIVE_SOURCE_REFERENCE_DIR")
_REFERENCE = Path(_REFERENCE) if _REFERENCE else None

def synthetic_header():
    return (b"#ifndef OPENSSL_ASN1_H\n#define OPENSSL_ASN1_H\n"
            + COMPAT.LEGACY_DECLARATION + b"\n#endif\n")

def reference(name):
    if _REFERENCE is None:
        raise unittest.SkipTest("Supply offline fixed source references")
    return (_REFERENCE / name).read_bytes()

class CurlOpenSslSourceCompatTests(unittest.TestCase):
    def test_actual_api_requires_exact_legacy_declaration(self):
        raw = synthetic_header()
        COMPAT.check_api_header(raw)
        candidates = [raw.replace(COMPAT.LEGACY_DECLARATION, b""),
                      raw + COMPAT.LEGACY_DECLARATION,
                      raw + b"size_t ASN1_STRING_get_length(const ASN1_STRING *x);\n",
                      raw + b"{- unexpanded -}",
                      raw.replace(b"#define OPENSSL_ASN1_H\n", b""),
                      raw + b"#ifndef OPENSSL_ASN1_H\n"]
        for candidate in candidates:
            with self.subTest(candidate=candidate), self.assertRaisesRegex(
                    RuntimeError, "Unknown actual generated OpenSSL"):
                COMPAT.check_api_header(candidate)

    def test_generation_preserves_static_bytes_in_order(self):
        template = synthetic_header().replace(
            COMPAT.LEGACY_DECLARATION,
            b"FIRST\n{- synthetic expansion -}\n" + COMPAT.LEGACY_DECLARATION)
        generated = template.replace(b"{- synthetic expansion -}", b"expanded")
        COMPAT.check_template_generation(template, generated)
        for candidate in (generated.replace(b"FIRST", b"CHANGED"),
                          generated.replace(COMPAT.LEGACY_DECLARATION, b""),
                          b"UNKNOWN_PREFIX\n" + generated,
                          generated + b"UNKNOWN_SUFFIX\n",
                          generated + b"UNKNOWN_SUFFIX\n" + re.split(rb"\{\-.*?\-\}", template, flags=re.S)[-1],
                          template):
            with self.subTest(candidate=candidate), self.assertRaises(RuntimeError):
                COMPAT.check_template_generation(template, candidate)

    def test_actual_fixed_template_static_bytes_are_checked(self):
        template = reference("fixed-openssl-asn1.h.in")
        self.assertEqual(COMPAT.sha(template), COMPAT.ASN1_TEMPLATE_SHA256)
        generated = re.sub(rb"\{\-.*?\-\}", b"SYNTHETIC_EXPANSION", template, flags=re.S)
        COMPAT.check_template_generation(template, generated)
        for candidate in (generated.replace(b"#define OPENSSL_ASN1_H\n", b""),
                          generated.replace(COMPAT.LEGACY_DECLARATION, b""),
                          generated + b"ASN1_STRING_get_length",
                          b"UNKNOWN_PREFIX\n" + generated,
                          generated + b"UNKNOWN_SUFFIX\n",
                          generated + b"UNKNOWN_SUFFIX\n" + re.split(rb"\{\-.*?\-\}", template, flags=re.S)[-1],
                          template):
            with self.subTest(bytes=len(candidate)), self.assertRaises(RuntimeError):
                COMPAT.check_template_generation(template, candidate)

    def test_complete_curl_transform_and_inverse_are_immutable(self):
        before = reference("curl-openssl.c")
        after = COMPAT.patch_relation(before)
        self.assertEqual(COMPAT.sha(after), COMPAT.AFTER_SHA256)
        inverse = after
        for old, new in reversed(COMPAT.EDITS):
            self.assertEqual(inverse.count(new), 1)
            inverse = inverse.replace(new, old)
        self.assertEqual(inverse, before)
        for changed in (before[:-1], before + b"\n",
                        before.replace(b"ASN1_STRING_length(str)", b"unknown(str)")):
            with self.subTest(bytes=len(changed)), self.assertRaisesRegex(
                    RuntimeError, "Unknown complete curl OpenSSL source identity"):
                COMPAT.patch_relation(changed)

    def test_read_file_accepts_regular_bounded_bytes_and_rejects_extents(self):
        with tempfile.TemporaryDirectory(prefix="bilipai-asn1-cpu-") as value:
            root = Path(value).resolve()
            p = root / "material"
            p.write_bytes(b"actual bytes")
            self.assertEqual(COMPAT.read_file(p), b"actual bytes")
            for raw in (b"", b"x" * (COMPAT.MAX_FILE + 1)):
                p.write_bytes(raw)
                with self.assertRaisesRegex(RuntimeError, "type or extent"):
                    COMPAT.read_file(p)
            with self.assertRaisesRegex(RuntimeError, "type or extent"):
                COMPAT.read_file(root)

    def test_read_file_rejects_file_symlink(self):
        with tempfile.TemporaryDirectory(prefix="bilipai-asn1-cpu-") as value:
            root = Path(value).resolve()
            target = root / "real"
            target.write_bytes(b"actual bytes")
            alias = root / "alias"
            try:
                alias.symlink_to(target)
            except (OSError, NotImplementedError):
                self.skipTest("Host does not permit creating test symlinks")
            with self.assertRaisesRegex(RuntimeError, "type or extent"):
                COMPAT.read_file(alias)

    def test_read_file_rejects_open_identity_and_midread_changes(self):
        with tempfile.TemporaryDirectory(prefix="bilipai-asn1-cpu-") as value:
            p = Path(value).resolve() / "material"
            p.write_bytes(b"actual bytes")
            first = p.stat()
            altered = SimpleNamespace(**{key: getattr(first, key) for key in
                ("st_dev", "st_ino", "st_size", "st_mtime_ns", "st_mode")})
            altered.st_mtime_ns += 1
            with patch.object(COMPAT.os, "fstat", return_value=altered):
                with self.assertRaisesRegex(RuntimeError, "before read"):
                    COMPAT.read_file(p)
            with patch.object(COMPAT.os, "fstat", side_effect=[first, altered]):
                with self.assertRaisesRegex(RuntimeError, "during read"):
                    COMPAT.read_file(p)

    def make_materials(self, root, final=False):
        template = reference("fixed-openssl-asn1.h.in")
        generated = re.sub(rb"\{\-.*?\-\}", b"SYNTHETIC_EXPANSION", template, flags=re.S)
        applied = b"# Synthetic CPU fixture; not an actual source/install receipt.\n"
        values = {"openssl-asn1.h.in": template,
                  "openssl-VERSION.dat": reference("fixed-openssl-VERSION.dat"),
                  "openssl-generated-asn1.h": generated,
                  "openssl-installed-asn1.h": generated,
                  "openssl-applied-source.diff": applied}
        install = dict(COMPAT.common_receipt(),
            state="AFTER_REAL_OPENSSL_INSTALL_BEFORE_RECIPE_CLEANUP",
            actualOpenSslCommit=COMPAT.OPENSSL_BASE, actualOpenSslTree="0" * 40,
            generatedHeaderSha256=COMPAT.sha(generated), installedHeaderSha256=COMPAT.sha(generated),
            generatedHeaderBytes=len(generated), installedHeaderBytes=len(generated),
            appliedSourceDiffSha256=COMPAT.sha(applied))
        values["openssl-install-receipt.json"] = COMPAT.encode(install)
        if final:
            values["curl-openssl.before.c"] = reference("curl-openssl.c")
            values["curl-openssl.after.c"] = COMPAT.patch_relation(values["curl-openssl.before.c"])
            for name, state in [
                ("curl-openssl-patch-receipt.json", "CURL_PATCH_APPLIED_BEFORE_CONFIGURE"),
                ("curl-openssl-install-receipt.json",
                 "AFTER_REAL_CURL_INSTALL_BEFORE_OWN_SOURCE_RESTORE_AND_RECIPE_CLEANUP")]:
                values[name] = COMPAT.encode(dict(COMPAT.common_receipt(), state=state,
                    opensslInstallReceiptSha256=COMPAT.sha(values["openssl-install-receipt.json"])))
        for name, raw in values.items():
            (root / name).write_bytes(raw)
        return values, install

    def test_material_inventory_generated_install_match_and_canonical_receipt(self):
        with tempfile.TemporaryDirectory(prefix="bilipai-asn1-cpu-") as value:
            root = Path(value).resolve()
            values, receipt = self.make_materials(root)
            observed, raw = COMPAT.validate_materials(root, final=False)
            self.assertEqual(observed, receipt)
            self.assertEqual(raw, values)
            extra = root / "unexpected"
            extra.write_bytes(b"extra")
            with self.assertRaisesRegex(RuntimeError, "inventory"):
                COMPAT.validate_materials(root, final=False)
            extra.unlink()
            target = root / "openssl-installed-asn1.h"
            target.write_bytes(values[target.name] + b"\n")
            with self.assertRaisesRegex(RuntimeError, "headers differ"):
                COMPAT.validate_materials(root, final=False)
            target.write_bytes(values[target.name])
            receipt["runtimeTested"] = True
            (root / "openssl-install-receipt.json").write_bytes(COMPAT.encode(receipt))
            with self.assertRaisesRegex(RuntimeError, "receipt changed"):
                COMPAT.validate_materials(root, final=False)

    def test_complete_materials_reject_drift_and_noncanonical_receipts(self):
        with tempfile.TemporaryDirectory(prefix="bilipai-asn1-cpu-") as value:
            root = Path(value).resolve()
            values, _ = self.make_materials(root, final=True)
            COMPAT.validate_materials(root)
            for name in ("curl-openssl.after.c", "openssl-asn1.h.in",
                         "curl-openssl-install-receipt.json", "openssl-install-receipt.json"):
                with self.subTest(name=name):
                    (root / name).write_bytes(values[name] + b"\n")
                    with self.assertRaises(RuntimeError):
                        COMPAT.validate_materials(root)
                    (root / name).write_bytes(values[name])


    def test_producer_and_helper_digest_closure(self):
        root = Path(__file__).resolve().parents[3]
        native = root / "desktop/tools/native"
        inputs = root / "desktop/third-party/libmpv/build"
        def data(path):
            return path.read_bytes()
        def constants(path):
            return {node.value for node in ast.walk(ast.parse(data(path)))
                    if isinstance(node, ast.Constant) and isinstance(node.value, str)}
        def assignments(path):
            result = {}
            for node in ast.parse(data(path)).body:
                if isinstance(node, ast.Assign):
                    for target in node.targets:
                        if isinstance(target, ast.Name):
                            try:
                                result[target.id] = ast.literal_eval(node.value)
                            except (ValueError, TypeError):
                                pass
            return result
        fixed_path = inputs / "rtx-present-v1/fixed-inputs.json"
        edits_path = inputs / "rtx-present-v1/recipe-edits.json"
        host_path = inputs / "rtx-core-v1/host-llvm-snapshot-inputs.json"
        fixed, edits, host = [json.loads(data(p)) for p in (fixed_path, edits_path, host_path)]
        builder = constants(native / "build-mpv-rtx-core-runtime.py")
        importer_path = native / "import-host-llvm-source-snapshot.py"
        importer = assignments(importer_path)
        for path in (fixed_path, edits_path, host_path, importer_path):
            self.assertIn(COMPAT.sha(data(path)), builder)
        self.assertEqual(host["helperSha256"], COMPAT.sha(data(native / "export-host-llvm-source-snapshot.py")))
        self.assertEqual(importer["EXPORTER_SHA256"], host["helperSha256"])
        self.assertEqual(importer["INPUTS_SHA256"], COMPAT.sha(data(host_path)))
        self.assertEqual(importer["LLVM_RECIPE_SHA256"], host["recipeEdits"][0]["afterSha256Bytes"])
        self.assertIn(COMPAT.sha(data(importer_path)), constants(native / "upload-mpv-rtx-core-draft.py"))
        self.assertEqual(fixed["curlOpenSslCompatibility"]["helperSha256"], COMPAT.sha(data(_PATH)))
        self.assertEqual(fixed["expectedPatchedRecipeSha256"],
                         {row["targetPath"]: row["afterSha256Bytes"] for row in edits["targets"]})

    def test_complete_recipe_journals_and_install_order(self):
        root = Path(__file__).resolve().parents[3]
        inputs = root / "desktop/third-party/libmpv/build"
        edits = json.loads((inputs / "rtx-present-v1/recipe-edits.json").read_bytes())
        host = json.loads((inputs / "rtx-core-v1/host-llvm-snapshot-inputs.json").read_bytes())
        selected = {"build-recipes/packages/curl.cmake": "fixed-curl.cmake",
                    "build-recipes/packages/openssl.cmake": "fixed-openssl.cmake"}
        rows = [(row, selected[row["targetPath"]]) for row in edits["targets"]
                if row["targetPath"] in selected]
        rows.append((host["recipeEdits"][0], "fixed-llvm.cmake"))
        results = {}
        for row, fixture in rows:
            before = reference(fixture)
            self.assertEqual(COMPAT.sha(before), row["beforeSha256Bytes"])
            stage = before
            for edit in row["rawEdits"]:
                old, new = edit["old"].encode(), edit["new"].encode()
                self.assertEqual(stage.count(old), 1)
                self.assertEqual(stage.index(old), edit["offsetBytesAtSequentialStage"])
                if "beforeStageSha256" in edit:
                    self.assertEqual(COMPAT.sha(stage), edit["beforeStageSha256"])
                stage = stage.replace(old, new)
                if "afterStageSha256" in edit:
                    self.assertEqual(COMPAT.sha(stage), edit["afterStageSha256"])
            self.assertEqual(COMPAT.sha(stage), row["afterSha256Bytes"])
            inverse = stage
            for edit in reversed(row["rawEdits"]):
                self.assertEqual(inverse.count(edit["new"].encode()), 1)
                inverse = inverse.replace(edit["new"].encode(), edit["old"].encode())
            self.assertEqual(inverse, before)
            results[fixture] = stage
        curl = results["fixed-curl.cmake"]
        self.assertLess(curl.index(b"bilipai-curl-libssh-scp-compat.py patch-curl"),
                        curl.index(b"bilipai-curl-openssl-asn1-compat.py patch-curl"))
        self.assertLess(curl.index(b"bilipai-curl-openssl-asn1-compat.py curl-after-install"),
                        curl.index(b"bilipai-curl-libssh-scp-compat.py curl-after-install"))
        self.assertEqual(results["fixed-openssl.cmake"].count(b"--build-dir <BINARY_DIR>"), 1)
        llvm = results["fixed-llvm.cmake"]
        self.assertEqual(llvm.count(b"-DCLANG_TOOL_OFFLOAD_ARCH_BUILD=OFF\n"), 1)
        self.assertEqual(llvm.count(b"-DCLANG_BUILD_TOOLS=OFF\n"), 1)
        self.assertIn(b"GIT_TAG release/22.x\n", llvm)


    def test_duplicate_final_static_chunk_is_rejected_at_consumed_boundary(self):
        template = synthetic_header().replace(COMPAT.LEGACY_DECLARATION,
            b"{- synthetic expansion -}\n" + COMPAT.LEGACY_DECLARATION)
        generated = template.replace(b"{- synthetic expansion -}", b"expanded")
        final_chunk = re.split(rb"\{\-.*?\-\}", template, flags=re.S)[-1]
        self.assertTrue(final_chunk)
        COMPAT.check_template_generation(template, generated)
        candidate = generated + b"UNKNOWN_SUFFIX\n" + final_chunk
        self.assertTrue(candidate.startswith(template.split(b"{-")[0]))
        self.assertTrue(candidate.endswith(final_chunk))
        with self.assertRaisesRegex(RuntimeError, "header boundary differs"):
            COMPAT.check_template_generation(template, candidate)

if __name__ == "__main__":
    unittest.main()
