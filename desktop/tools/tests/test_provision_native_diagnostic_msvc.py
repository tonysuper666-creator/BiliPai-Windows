"""Offline contracts for the isolated, fully pinned MSVC provider."""
from contextlib import ExitStack
from dataclasses import replace
from pathlib import Path
import base64
import hashlib
import importlib.util
import io
import json
import os
import stat
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile


TOOLS = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("native_vc_provisioner", TOOLS / "provision-native-diagnostic-msvc.py")
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class MsvcProvisionTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="bilipai-vc-contract-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.repo = self.root / "repo"
        self.temp = self.root / "runner-temp"
        self.cache = self.root / "payload-cache"
        self.repo.mkdir()
        self.temp.mkdir()
        self.cache.mkdir()
        self.output = self.temp / "reviewed-vc"

    def archive(self, name, entries):
        folder = name + ",version=1"
        target = self.cache / folder / "payload.vsix"
        target.parent.mkdir()
        with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as zipped:
            for entry, content in entries:
                zipped.writestr(entry, content)
        raw = target.read_bytes()
        # On Windows writestr normalizes a backslash before serialization.
        # Deliberately put that unsafe original spelling in both ZIP headers
        # so this negative case tests the parser, not the writer's cleanup.
        for entry, _ in entries:
            if isinstance(entry, str) and "\\" in entry:
                raw = raw.replace(entry.replace("\\", "/").encode(), entry.encode())
        target.write_bytes(raw)
        sha = hashlib.sha256(raw).hexdigest()
        package = MODULE.Package(name, "1", len(raw), sha,
                                 base64.b64encode(hashlib.sha512(raw).digest()).decode(),
                                 "https://download.visualstudio.microsoft.com/download/pr/fixture/" + sha + "/" + name + ".vsix",
                                 folder)
        return package, target

    def fixture(self):
        prefix = MODULE.ARCHIVE_PREFIX
        header = b"// Exact CRT header\r\n#define REVIEWED 1\r\n"
        library = b"\x00\x01\r\n\xffLIB"
        compiler = b"MZ\x00original CL"
        atl = b"MZ\x00original ATL provider"
        concurrency = b"MZ\x00original CA DLL"
        archives = (
            self.archive("Synthetic.CRT", [(prefix + "include/reviewed.h", header), (prefix + "lib/x64/libcmt.lib", library)]),
            self.archive("Synthetic.Tools", [(prefix + "bin/Hostx64/x64/cl.exe", compiler),
                                              (prefix + "bin/Hostx64/x64/atlprov.dll", atl),
                                              (prefix + "bin/Hostx64/x64/cl.exe.config", b"original non-bin-pin config"),
                                              (prefix + "bin/Hostx64/x64/onecore/other.dll", b"original subdirectory DLL")]),
            self.archive("Synthetic.CA", [(prefix + "bin/HostX64/x64/ConcurrencyCheck.dll", concurrency)]),
            self.archive("Synthetic.English", [(prefix + "bin/Hostx64/x64/1033/clui.dll", b"English CL messages"),
                                                (prefix + "bin/Hostx64/x64/1033/linkui.dll", b"English LINK messages")]),
        )
        def row(path, raw):
            return dict(path="vc/" + path, bytes=len(raw), sha256Bytes=hashlib.sha256(raw).hexdigest())
        self.resource_pins = (row("bin/Hostx64/x64/1033/clui.dll", b"English CL messages"),
                              row("bin/Hostx64/x64/1033/linkui.dll", b"English LINK messages"))
        document = dict(msvcVersion=MODULE.MSVC_VERSION,
                        transitiveHeaders=[row("include/reviewed.h", header)],
                        searchedLibrariesConservativePins=[row("lib/x64/libcmt.lib", library)],
                        compilerBinDirectoryConservativePins=[row("bin/Hostx64/x64/cl.exe", compiler),
                                                              row("bin/Hostx64/x64/atlprov.dll", atl),
                                                              row("bin/Hostx64/x64/ConcurrencyCheck.dll", concurrency)])
        approval = self.repo / "desktop/native/diagnostic-share/approved-development-build.json"
        approval.parent.mkdir(parents=True)
        approval.write_text(json.dumps(document), encoding="utf-8")
        return archives, approval, document

    def prepared(self, archives, approval):
        stack = ExitStack()
        stack.enter_context(patch.object(MODULE, "APPROVAL_SHA256", MODULE.SDK.sha256(approval)))
        stack.enter_context(patch.object(MODULE, "PACKAGES", tuple(p for p, _ in archives)))
        stack.enter_context(patch.object(MODULE, "ENGLISH_RESOURCE_PINS", self.resource_pins))
        stack.enter_context(patch.dict(os.environ, {"RUNNER_TEMP": str(self.temp)}))
        return stack

    def test_nine_official_payloads_pin_both_digests_and_real_lengths(self):
        self.assertEqual(len(MODULE.PACKAGES), 9)
        self.assertEqual(sum(p.bytes for p in MODULE.PACKAGES), 174964671)
        for package in MODULE.PACKAGES:
            self.assertTrue(package.url.startswith("https://download.visualstudio.microsoft.com/download/pr/"))
            self.assertIn("/" + package.sha256_hex + "/", package.url)
            self.assertEqual(len(bytes.fromhex(package.sha256_hex)), 32)
            self.assertEqual(len(base64.b64decode(package.sha512_base64, validate=True)), 64)
            self.assertTrue(package.url.endswith("/" + package.filename))
            self.assertNotEqual(package.version, MODULE.MSVC_VERSION)
        english = [p for p in MODULE.PACKAGES if p.archive_filename is not None]
        self.assertEqual(len(english), 2)
        self.assertTrue(all(p.filename.endswith(".enu.vsix") for p in english))
        self.assertEqual(len(MODULE.ENGLISH_RESOURCE_PINS), 15)
        self.assertIn("vc/bin/Hostx64/x64/1033/clui.dll", {p["path"] for p in MODULE.ENGLISH_RESOURCE_PINS})
        self.assertIn("vc/bin/Hostx64/x64/1033/linkui.dll", {p["path"] for p in MODULE.ENGLISH_RESOURCE_PINS})

    def test_verified_cache_stages_all_pins_and_existing_bin_scope_without_compiler(self):
        archives, approval, document = self.fixture()
        before_cache = {p: p.read_bytes() for _, p in archives}
        with self.prepared(archives, approval):
            result = MODULE.prepare(self.repo, self.temp, self.output, cache_root=self.cache)
        self.assertTrue(result["passed"])
        self.assertTrue(result["vcInputsExact"])
        self.assertTrue(result["compilerBinSetExact"])
        self.assertEqual(result["approvedVcInputs"], 5)
        self.assertEqual(result["approvedCompilerBinInputs"], 3)
        self.assertTrue(result["englishResourcesExact"])
        self.assertEqual(result["pinnedEnglishResourceInputs"], 2)
        self.assertEqual(result["payloadOrigin"], "verifiedVsInstallerPayloadCache")
        self.assertFalse(result["compilerExecuted"])
        self.assertFalse(result["installedSystemToolchainChanged"])
        self.assertTrue(result["freshProducerGraphAndDllVerificationStillRequired"])
        for rows in (document["transitiveHeaders"], document["searchedLibrariesConservativePins"], document["compilerBinDirectoryConservativePins"]):
            for row in rows:
                path = self.output / row["path"][3:]
                self.assertEqual(path.stat().st_size, row["bytes"])
                self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), row["sha256Bytes"])
        self.assertTrue((self.output / "bin/Hostx64/x64/cl.exe.config").is_file())
        self.assertTrue((self.output / "bin/Hostx64/x64/onecore/other.dll").is_file())
        self.assertEqual(json.loads((self.output / "vc-provision.json").read_bytes()), result)
        self.assertEqual({p: p.read_bytes() for _, p in archives}, before_cache)

    def test_original_vc_pins_alone_cannot_publish_missing_message_resources(self):
        archives, approval, _ = self.fixture()
        with self.prepared(archives[:-1], approval):
            with self.assertRaises(FileNotFoundError):
                MODULE.prepare(self.repo, self.temp, self.output, cache_root=self.cache)
        self.assertFalse(self.output.exists())

    def test_private_english_archive_cache_uses_same_hash_and_resource_guards(self):
        archives, approval, _ = self.fixture()
        package, archive = archives[-1]
        package = replace(package, archive_filename="Synthetic.English.enu.vsix")
        resource_cache = self.root / "english-payloads"
        resource_cache.mkdir()
        (resource_cache / package.filename).write_bytes(archive.read_bytes())
        with self.prepared((*archives[:-1], (package, archive)), approval):
            result = MODULE.prepare(self.repo, self.temp, self.output, cache_root=self.cache,
                                    resource_cache_root=resource_cache)
        self.assertTrue(result["englishResourcesExact"])
        self.assertEqual(result["payloadOrigin"], "verifiedVsInstallerCacheAndEnglishArchives")
        self.assertEqual((self.output / "bin/Hostx64/x64/1033/clui.dll").read_bytes(), b"English CL messages")

    def test_wrong_message_resource_bytes_never_publish_root(self):
        archives, approval, _ = self.fixture()
        self.resource_pins[0]["sha256Bytes"] = "0" * 64
        with self.prepared(archives, approval):
            with self.assertRaisesRegex(ValueError, "English compiler resource bytes"):
                MODULE.prepare(self.repo, self.temp, self.output, cache_root=self.cache)
        self.assertFalse(self.output.exists())

    def test_extra_unreviewed_message_resource_never_publishes_root(self):
        archives, approval, _ = self.fixture()
        extra = self.archive("Synthetic.ExtraResource", [(MODULE.ARCHIVE_PREFIX + "bin/Hostx64/x64/1033/unreviewed.dll", b"extra")])
        with self.prepared((*archives, extra), approval):
            with self.assertRaisesRegex(ValueError, "English compiler resource set"):
                MODULE.prepare(self.repo, self.temp, self.output, cache_root=self.cache)
        self.assertFalse(self.output.exists())

    def test_wrong_sha256_rejected_even_with_correct_length_and_sha512(self):
        package, archive = self.archive("WrongSha256", [(MODULE.ARCHIVE_PREFIX + "include/a.h", b"a")])
        with self.assertRaisesRegex(ValueError, "SHA256"):
            MODULE.SDK.extract_package(archive, replace(package, sha256_hex="0" * 64), self.temp, path_mapper=MODULE.mapped_path)
        self.assertEqual(list(self.temp.iterdir()), [])

    def test_wrong_sha512_rejected_even_with_correct_sha256(self):
        package, archive = self.archive("WrongSha512", [(MODULE.ARCHIVE_PREFIX + "include/a.h", b"a")])
        with self.assertRaisesRegex(ValueError, "SHA512"):
            MODULE.SDK.extract_package(archive, replace(package, sha512_base64=base64.b64encode(b"\x00" * 64).decode()), self.temp, path_mapper=MODULE.mapped_path)
        self.assertEqual(list(self.temp.iterdir()), [])

    def test_wrong_full_length_rejected_before_extraction(self):
        package, archive = self.archive("WrongLength", [(MODULE.ARCHIVE_PREFIX + "include/a.h", b"a")])
        with self.assertRaisesRegex(ValueError, "length"):
            MODULE.SDK.extract_package(archive, replace(package, bytes=package.bytes + 1), self.temp, path_mapper=MODULE.mapped_path)

    def test_download_requires_sha256_even_if_sha512_matches(self):
        raw = b"full signed payload"
        package = MODULE.Package("Synthetic.Download", "1", len(raw), "0" * 64,
                                 base64.b64encode(hashlib.sha512(raw).digest()).decode(),
                                 "https://download.visualstudio.microsoft.com/fixed", "unused")
        class Response(io.BytesIO):
            status = 200
            headers = {"Content-Length": str(len(raw))}
            def geturl(self):
                return package.url
        with patch.object(MODULE.SDK.urllib.request, "urlopen", return_value=Response(raw)):
            with self.assertRaisesRegex(ValueError, "SHA256"):
                MODULE.SDK.download_package(package, self.root / "download.vsix")

    def test_missing_or_changed_approved_header_rejects_output(self):
        for mode in ("missing", "changed"):
            with self.subTest(mode=mode):
                archives, approval, document = self.fixture()
                document["transitiveHeaders"][0]["path"] = "vc/include/missing.h" if mode == "missing" else "vc/include/reviewed.h"
                if mode == "changed":
                    document["transitiveHeaders"][0]["sha256Bytes"] = "0" * 64
                approval.write_text(json.dumps(document), encoding="utf-8")
                with self.prepared(archives, approval):
                    with self.assertRaises((ValueError, FileNotFoundError)):
                        MODULE.prepare(self.repo, self.temp, self.output, cache_root=self.cache)
                self.assertFalse(self.output.exists())
                # Each subcase gets a fresh independent fixture directory.
                if mode == "missing":
                    self.temporary.cleanup()
                    self.setUp()

    def test_extra_direct_compiler_binary_rejected(self):
        archives, approval, _ = self.fixture()
        extra = self.archive("Synthetic.Extra", [(MODULE.ARCHIVE_PREFIX + "bin/Hostx64/x64/unreviewed.dll", b"extra")])
        with self.prepared((*archives, extra), approval):
            with self.assertRaisesRegex(ValueError, "compiler-bin set"):
                MODULE.prepare(self.repo, self.temp, self.output, cache_root=self.cache)
        self.assertFalse(self.output.exists())

    def test_changed_approval_rejected_before_creating_stage(self):
        archives, approval, _ = self.fixture()
        with self.prepared(archives, approval):
            approval.write_bytes(approval.read_bytes() + b"\n")
            with self.assertRaisesRegex(ValueError, "Native approval changed"):
                MODULE.prepare(self.repo, self.temp, self.output, cache_root=self.cache)
        self.assertEqual(list(self.temp.iterdir()), [])

    def test_approval_change_after_unpack_never_publishes_root(self):
        archives, approval, _ = self.fixture()
        real_verify = MODULE.verify_vc
        def verify_then_change(*args):
            real_verify(*args)
            approval.write_bytes(approval.read_bytes() + b"\n")
        with self.prepared(archives, approval), patch.object(MODULE, "verify_vc", side_effect=verify_then_change):
            with self.assertRaisesRegex(ValueError, "changed while provisioning"):
                MODULE.prepare(self.repo, self.temp, self.output, cache_root=self.cache)
        self.assertFalse(self.output.exists())

    def test_existing_output_is_preserved_without_stage(self):
        archives, approval, _ = self.fixture()
        self.output.mkdir()
        marker = self.output / "keep.bin"
        marker.write_bytes(b"existing")
        with self.prepared(archives, approval):
            with self.assertRaisesRegex(ValueError, "new direct child"):
                MODULE.prepare(self.repo, self.temp, self.output, cache_root=self.cache)
        self.assertEqual(marker.read_bytes(), b"existing")
        self.assertEqual(list(self.temp.iterdir()), [self.output])

    def test_output_appearing_during_verification_is_preserved(self):
        archives, approval, _ = self.fixture()
        real_verify = MODULE.verify_vc
        def verify_then_race(*args):
            real_verify(*args)
            self.output.mkdir()
            (self.output / "keep.bin").write_bytes(b"racer")
        with self.prepared(archives, approval), patch.object(MODULE, "verify_vc", side_effect=verify_then_race):
            with self.assertRaisesRegex(ValueError, "output appeared"):
                MODULE.prepare(self.repo, self.temp, self.output, cache_root=self.cache)
        self.assertEqual((self.output / "keep.bin").read_bytes(), b"racer")

    def test_repo_output_and_runner_temp_mismatch_rejected(self):
        archives, approval, _ = self.fixture()
        with self.prepared(archives, approval):
            for temp, output in ((self.repo, self.repo / "vc"), (self.root, self.root / "vc")):
                with self.subTest(output=output):
                    with self.assertRaises(ValueError):
                        MODULE.prepare(self.repo, temp, output, cache_root=self.cache)
                    self.assertFalse(output.exists())

    def test_system_output_rejected_before_stage(self):
        archives, approval, _ = self.fixture()
        # Exercise the system-directory exclusion on Ubuntu too, without
        # needing or modifying an installed Windows system directory.
        def path(value):
            return self.temp if value == "C:/Program Files" else Path(value)
        with self.prepared(archives, approval), patch.object(MODULE, "Path", side_effect=path):
            with self.assertRaisesRegex(ValueError, "system installation"):
                MODULE.prepare(self.repo, self.temp, self.output, cache_root=self.cache)
        self.assertEqual(list(self.temp.iterdir()), [])

    def test_zip_paths_types_and_parent_collisions_rejected_before_extraction(self):
        bad_cases = [
            [("../outside", b"x")], [("C:/outside", b"x")], [("metadata\\outside", b"x")],
            [("metadata/NUL.txt", b"x")], [("metadata/a ", b"x")],
            [("metadata", b"file"), ("metadata/child", b"x")],
            [("metadata/a", b"a"), ("metadata/A", b"b")],
        ]
        for index, entries in enumerate(bad_cases):
            with self.subTest(entries=entries):
                package, archive = self.archive("Unsafe" + str(index), entries + [(MODULE.ARCHIVE_PREFIX + "include/a.h", b"a")])
                target = self.temp / str(index)
                target.mkdir()
                with self.assertRaises(ValueError):
                    MODULE.SDK.extract_package(archive, package, target, path_mapper=MODULE.mapped_path)
                self.assertEqual(list(target.iterdir()), [])

    def test_fixed_host_casing_alias_cannot_hide_mapped_collision(self):
        package, archive = self.archive("MappedCollision", [(MODULE.ARCHIVE_PREFIX + "bin/HostX64/x64/cl.exe", b"a"),
                                                            (MODULE.ARCHIVE_PREFIX + "bin/Hostx64/x64/cl.exe", b"b")])
        with self.assertRaisesRegex(ValueError, "duplicate"):
            MODULE.SDK.extract_package(archive, package, self.temp, path_mapper=MODULE.mapped_path)
        self.assertEqual(list(self.temp.iterdir()), [])

    def test_distinct_archive_names_cannot_map_to_one_output(self):
        package, archive = self.archive("MappedCollision2", [("metadata/left", b"a"), ("metadata/right", b"b")])
        with self.assertRaisesRegex(ValueError, "mapped"):
            MODULE.SDK.extract_package(archive, package, self.temp, path_mapper=lambda name, kind: "include/a.h")
        self.assertEqual(list(self.temp.iterdir()), [])

    def test_overlapping_package_outputs_do_not_overwrite_first_payload(self):
        one, p1 = self.archive("First", [(MODULE.ARCHIVE_PREFIX + "include/a.h", b"first")])
        two, p2 = self.archive("Second", [(MODULE.ARCHIVE_PREFIX + "include/a.h", b"second")])
        MODULE.SDK.extract_package(p1, one, self.temp, path_mapper=MODULE.mapped_path)
        with self.assertRaises(FileExistsError):
            MODULE.SDK.extract_package(p2, two, self.temp, path_mapper=MODULE.mapped_path)
        self.assertEqual((self.temp / "include/a.h").read_bytes(), b"first")

    def test_zip_symlink_and_resource_boundary_rejected(self):
        info = zipfile.ZipInfo(MODULE.ARCHIVE_PREFIX + "include/a.h")
        info.create_system = 3
        info.external_attr = (stat.S_IFLNK | 0o777) << 16
        package, archive = self.archive("Symlink", [(info, b"outside")])
        with self.assertRaisesRegex(ValueError, "entry type"):
            MODULE.SDK.extract_package(archive, package, self.temp, path_mapper=MODULE.mapped_path)
        package, archive = self.archive("TooMany", [(MODULE.ARCHIVE_PREFIX + "include/a.h", b"a"), ("metadata/m", b"m")])
        with patch.object(MODULE.SDK, "MAX_ENTRIES", 1):
            with self.assertRaisesRegex(ValueError, "resource boundary"):
                MODULE.SDK.extract_package(archive, package, self.temp, path_mapper=MODULE.mapped_path)

    def test_both_workflows_stage_vc_and_export_existing_root_env_before_build(self):
        repo = TOOLS.parents[1]
        for filename, build_label in (("windows-desktop.yml", "Build tested portable Windows package"),
                                      ("windows-upstream-sync.yml", "Build isolated candidate with all release gates")):
            workflow = (repo / ".github/workflows" / filename).read_text(encoding="utf-8")
            sdk = workflow.index("python desktop/tools/provision-native-diagnostic-sdk.py")
            vc = workflow.index("python desktop/tools/provision-native-diagnostic-msvc.py")
            export = workflow.index('"BILIPAI_NATIVE_SHARE_VC_ROOT=$vcRoot"')
            # The actual Windows job builds only after both strict provisions.
            build = workflow.index(build_label)
            self.assertLess(sdk, vc)
            self.assertLess(vc, export)
            self.assertLess(export, build)
            self.assertIn("$vc.vcInputsExact -ne $true", workflow)
            self.assertIn("$vc.compilerBinSetExact -ne $true", workflow)
            self.assertIn("$vc.englishResourcesExact -ne $true", workflow)


if __name__ == "__main__":
    unittest.main()
