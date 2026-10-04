"""Offline contracts for the isolated, byte-pinned SDK provisioner."""
from contextlib import ExitStack
from pathlib import Path
import argparse
import base64
import hashlib
import importlib.util
import io
import json
import os
import shutil
import stat
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile


TOOLS = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("native_sdk_provisioner", TOOLS / "provision-native-diagnostic-sdk.py")
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class SdkProvisionTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="bilipai-sdk-contract-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.repo = self.root / "repo"
        self.temp = self.root / "runner-temp"
        self.temp.mkdir()
        self.repo.mkdir()
        self.output = self.temp / "reviewed-sdk"

    def archive(self, name, kind, entries):
        target = self.root / (name + ".zip")
        with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as zipped:
            for entry, content in entries:
                zipped.writestr(entry, content)
        raw = target.read_bytes()
        package = MODULE.Package(name, len(raw), base64.b64encode(hashlib.sha512(raw).digest()).decode(), kind)
        return package, target

    def fixture(self, missing=False):
        header = b"#define REVIEWED 1\r\n// Preserve raw CRLF.\r\n"
        library = b"\x00\x01\x02\x03\r\n\xff"
        entries = [("c/Include/10.0.26100.0/cppwinrt/winrt/base.h", header)]
        if missing:
            entries = [("c/Include/10.0.26100.0/cppwinrt/winrt/other.h", header)]
        first = self.archive("Synthetic.Common", "headers", entries)
        second = self.archive("Synthetic.x64", "libraries", [("c/um/x64/runtimeobject.lib", library)])
        rows = [dict(path="sdk/Include/10.0.26100.0/cppwinrt/winrt/base.h", bytes=len(header),
                     sha256Bytes=hashlib.sha256(header).hexdigest())]
        libs = [dict(path="sdk/Lib/10.0.26100.0/um/x64/runtimeobject.lib", bytes=len(library),
                     sha256Bytes=hashlib.sha256(library).hexdigest())]
        approval = self.repo / "desktop/native/diagnostic-share/approved-development-build.json"
        approval.parent.mkdir(parents=True)
        approval.write_text(json.dumps(dict(sdkVersion=MODULE.SDK_VERSION, transitiveHeaders=rows,
                                            searchedLibrariesConservativePins=libs,
                                            compilerBinDirectoryConservativePins=[])), encoding="utf-8")
        return (first, second), approval, header, library

    def prepared(self, archives, approval):
        stack = ExitStack()
        stack.enter_context(patch.object(MODULE, "APPROVAL_SHA256", MODULE.sha256(approval)))
        stack.enter_context(patch.object(MODULE, "PACKAGES", tuple(row[0] for row in archives)))
        sources = {row[0].name: row[1] for row in archives}
        def download(package, destination):
            shutil.copyfile(sources[package.name], destination)
        stack.enter_context(patch.object(MODULE, "download_package", download))
        stack.enter_context(patch.dict(os.environ, {"RUNNER_TEMP": str(self.temp)}))
        return stack

    def test_official_urls_and_fixed_package_hashes_are_not_runtime_options(self):
        self.assertEqual([p.bytes for p in MODULE.PACKAGES], [160402494, 52949537])
        self.assertEqual([len(base64.b64decode(p.sha512_base64, validate=True)) for p in MODULE.PACKAGES], [64, 64])
        self.assertTrue(all(p.url.startswith("https://api.nuget.org/v3-flatcontainer/") for p in MODULE.PACKAGES))
        self.assertTrue(all("10.0.26100.7705" in p.url for p in MODULE.PACKAGES))

    def test_two_packages_stage_exact_binary_and_crlf_without_touching_system_sdk(self):
        archives, approval, header, library = self.fixture()
        with self.prepared(archives, approval):
            result = MODULE.prepare(self.repo, self.temp, self.output)
        self.assertEqual((self.output / "Include/10.0.26100.0/cppwinrt/winrt/base.h").read_bytes(), header)
        self.assertEqual((self.output / "Lib/10.0.26100.0/um/x64/runtimeobject.lib").read_bytes(), library)
        self.assertEqual(result["approvedSdkInputs"], 2)
        self.assertTrue(result["sdkInputsExact"])
        self.assertFalse(result["installedSystemSdkChanged"])
        self.assertFalse(result["compilerExecuted"])
        self.assertTrue(result["freshProducerGraphAndDllVerificationStillRequired"])
        self.assertEqual(json.loads((self.output / "sdk-provision.json").read_bytes()), result)

    def test_missing_approved_input_does_not_publish_sdk_root(self):
        archives, approval, _, _ = self.fixture(missing=True)
        with self.prepared(archives, approval):
            with self.assertRaises(FileNotFoundError):
                MODULE.prepare(self.repo, self.temp, self.output)
        self.assertFalse(self.output.exists())

    def test_wrong_approved_bytes_do_not_publish_sdk_root(self):
        archives, approval, _, _ = self.fixture()
        document = json.loads(approval.read_bytes())
        document["transitiveHeaders"][0]["sha256Bytes"] = "0" * 64
        approval.write_text(json.dumps(document), encoding="utf-8")
        with self.prepared(archives, approval):
            with self.assertRaisesRegex(ValueError, "Official SDK bytes differ"):
                MODULE.prepare(self.repo, self.temp, self.output)
        self.assertFalse(self.output.exists())

    def test_modified_archive_rejected_before_extraction(self):
        package, archive = self.archive("BadDigest", "headers", [("c/Include/good.h", b"good")])
        contents = bytearray(archive.read_bytes())
        contents[0] ^= 1
        archive.write_bytes(contents)
        sdk = self.temp / "sdk"
        sdk.mkdir()
        with self.assertRaisesRegex(ValueError, "SHA512"):
            MODULE.extract_package(archive, package, sdk)
        self.assertEqual(list(sdk.iterdir()), [])

    def test_truncated_archive_rejected_before_extraction(self):
        package, archive = self.archive("Short", "headers", [("c/Include/good.h", b"good")])
        archive.write_bytes(archive.read_bytes()[:-1])
        sdk = self.temp / "sdk"
        sdk.mkdir()
        with self.assertRaisesRegex(ValueError, "length"):
            MODULE.extract_package(archive, package, sdk)
        self.assertEqual(list(sdk.iterdir()), [])

    def test_all_entries_checked_even_excluded_metadata_paths(self):
        bad_paths = ["../escape", "/absolute", "C:/drive", "c\\Include\\escape", "meta/../escape",
                     "metadata/CON.txt", "metadata/a.", "metadata/a ", "metadata/a:b", "metadata/a\x00b"]
        for index, name in enumerate(bad_paths):
            with self.subTest(name=name):
                # ZipFile truncates NUL names when writing; directly check that case.
                if "\x00" in name:
                    with self.assertRaises(ValueError):
                        MODULE.checked_path(name)
                    continue
                entry = zipfile.ZipInfo("placeholder")
                entry.filename = name
                package, archive = self.archive("Unsafe" + str(index), "headers",
                                                [("c/Include/good.h", b"good"), (entry, b"bad")])
                sdk = self.temp / ("sdk" + str(index))
                sdk.mkdir()
                with self.assertRaises(ValueError):
                    MODULE.extract_package(archive, package, sdk)
                self.assertEqual(list(sdk.iterdir()), [])

    def test_case_collision_or_file_child_collision_rejected_before_extraction(self):
        for index, names in enumerate((("meta/A", "meta/a"), ("meta/a", "meta/a/b"))):
            package, archive = self.archive("Collision" + str(index), "headers",
                                            [("c/Include/good.h", b"good"), *( (n, b"bad") for n in names )])
            sdk = self.temp / ("collision" + str(index))
            sdk.mkdir()
            with self.assertRaisesRegex(ValueError, "collision|duplicate"):
                MODULE.extract_package(archive, package, sdk)
            self.assertEqual(list(sdk.iterdir()), [])

    def test_symlink_rejected_even_outside_sdk_prefix(self):
        entry = zipfile.ZipInfo("metadata/link")
        entry.create_system = 3
        entry.external_attr = (stat.S_IFLNK | 0o777) << 16
        package, archive = self.archive("Symlink", "headers", [("c/Include/good.h", b"good"), (entry, b"../secret")])
        sdk = self.temp / "link-sdk"
        sdk.mkdir()
        with self.assertRaisesRegex(ValueError, "entry type"):
            MODULE.extract_package(archive, package, sdk)
        self.assertEqual(list(sdk.iterdir()), [])

    def test_expansion_resource_limit_rejected_before_extraction(self):
        package, archive = self.archive("Large", "headers", [("c/Include/good.h", b"too large")])
        sdk = self.temp / "large-sdk"
        sdk.mkdir()
        with patch.object(MODULE, "MAX_EXPANDED_BYTES", 1), self.assertRaisesRegex(ValueError, "resource boundary"):
            MODULE.extract_package(archive, package, sdk)
        self.assertEqual(list(sdk.iterdir()), [])

    def test_output_admission_precedes_download_and_preserves_existing_files(self):
        self.output.mkdir()
        (self.output / "keep").write_bytes(b"unchanged")
        with patch.object(MODULE, "download_package") as download:
            with self.assertRaisesRegex(ValueError, "new direct child"):
                MODULE.prepare(self.repo, self.temp, self.output)
        download.assert_not_called()
        self.assertEqual((self.output / "keep").read_bytes(), b"unchanged")

    def test_output_outside_declared_temp_or_inside_repo_rejected_before_download(self):
        for output, temp in ((self.root / "escaped", self.temp), (self.repo / "sdk", self.repo)):
            with patch.object(MODULE, "download_package") as download, self.assertRaises(ValueError):
                MODULE.prepare(self.repo, temp, output)
            download.assert_not_called()
            self.assertFalse(output.exists())

    def test_runner_temp_mismatch_rejected_before_download(self):
        with patch.dict(os.environ, {"RUNNER_TEMP": str(self.repo)}), patch.object(MODULE, "download_package") as download:
            with self.assertRaisesRegex(ValueError, "RUNNER_TEMP"):
                MODULE.prepare(self.repo, self.temp, self.output)
        download.assert_not_called()

    def test_changed_native_approval_rejected_before_download(self):
        _, approval, _, _ = self.fixture()
        with patch.object(MODULE, "download_package") as download, patch.dict(os.environ, {"RUNNER_TEMP": str(self.temp)}):
            with self.assertRaisesRegex(ValueError, "Native approval changed"):
                MODULE.prepare(self.repo, self.temp, self.output)
        download.assert_not_called()

    def test_download_reads_multiple_short_chunks_to_eof(self):
        raw = b"fixed complete package"
        package = MODULE.Package("Chunked", len(raw), base64.b64encode(hashlib.sha512(raw).digest()).decode(), "headers")
        class Response:
            status = 200
            headers = {"Content-Length": str(len(raw))}
            stream = io.BytesIO(raw)
            def geturl(self): return package.url
            def read(self, _): return self.stream.read(3)
            def __enter__(self): return self
            def __exit__(self, *_): pass
        target = self.temp / "chunked.nupkg"
        with patch.object(MODULE.urllib.request, "urlopen", return_value=Response()):
            MODULE.download_package(package, target)
        self.assertEqual(target.read_bytes(), raw)

    def test_download_short_response_never_accepted(self):
        raw = b"fixed complete package"
        package = MODULE.Package("Truncated", len(raw), base64.b64encode(hashlib.sha512(raw).digest()).decode(), "headers")
        class Response:
            status = 200
            headers = {"Content-Length": str(len(raw))}
            stream = io.BytesIO(raw[:-2])
            def geturl(self): return package.url
            def read(self, _): return self.stream.read(3)
            def __enter__(self): return self
            def __exit__(self, *_): pass
        with patch.object(MODULE.urllib.request, "urlopen", return_value=Response()), self.assertRaises(ValueError):
            MODULE.download_package(package, self.temp / "short.nupkg")

    def test_exact_bounded_range_resume_still_verifies_full_sha512(self):
        raw = b"fixed complete package"
        package = MODULE.Package("Resumed", len(raw), base64.b64encode(hashlib.sha512(raw).digest()).decode(), "headers")
        requests = []
        class Response:
            def __init__(self, start):
                self.status = 206 if start else 200
                self.headers = {"Content-Length": str(len(raw) - start)}
                if start: self.headers["Content-Range"] = f"bytes {start}-{len(raw) - 1}/{len(raw)}"
                self.stream = io.BytesIO(raw[start:] if start else raw[:7])
            def geturl(self): return package.url
            def read(self, _): return self.stream.read(3)
            def __enter__(self): return self
            def __exit__(self, *_): pass
        def open_response(request, **_):
            requests.append(request)
            return Response(7 if len(requests) == 2 else 0)
        target = self.temp / "resumed.nupkg"
        with patch.object(MODULE.urllib.request, "urlopen", side_effect=open_response):
            MODULE.download_package(package, target)
        self.assertEqual(target.read_bytes(), raw)
        self.assertEqual(len(requests), 2)
        self.assertEqual(requests[1].get_header("Range"), f"bytes=7-{len(raw) - 1}")

    def test_producer_default_accepts_sdk_env_and_keeps_explicit_cli_precedence(self):
        producer_spec = importlib.util.spec_from_file_location("sdk_native_producer", TOOLS / "prepare-native-diagnostic-share.py")
        producer = importlib.util.module_from_spec(producer_spec)
        producer_spec.loader.exec_module(producer)
        parse_args = argparse.ArgumentParser.parse_args
        captured = []
        class Parsed(Exception): pass
        def stop_after_parse(parser, *args, **kwargs):
            captured.append(parse_args(parser, *args, **kwargs))
            raise Parsed()
        required = ["producer", "--repo", "nonexistent", "--output", "nonexistent", "--asset-dir", "nonexistent"]
        with patch.dict(os.environ, {"BILIPAI_NATIVE_SHARE_SDK_ROOT": str(self.temp)}), patch.object(argparse.ArgumentParser, "parse_args", stop_after_parse):
            with patch.object(sys, "argv", required), self.assertRaises(Parsed): producer.main()
            with patch.object(sys, "argv", required + ["--sdk-root", str(self.output)]), self.assertRaises(Parsed): producer.main()
        self.assertEqual(captured[0].sdk_root, self.temp)
        self.assertEqual(captured[1].sdk_root, self.output)

    def test_both_ci_build_paths_provision_after_policies_before_build(self):
        root = TOOLS.parents[1]
        cases = (("windows-desktop.yml", "Build tested portable Windows package"),
                 ("windows-upstream-sync.yml", "Build isolated candidate with all release gates"))
        for filename, build in cases:
            source = (root / ".github/workflows" / filename).read_text(encoding="utf-8")
            if filename == "windows-upstream-sync.yml":
                source = source.split("\n  candidate:\n", 1)[1].split("\n  recover_publication:\n", 1)[0]
            policy = source.index("python desktop/tools/run-tool-tests.py --stage policies")
            provisioning = source.index("python desktop/tools/provision-native-diagnostic-sdk.py")
            self.assertLess(policy, provisioning)
            self.assertLess(provisioning, source.index(build))
            self.assertEqual(source.count("python desktop/tools/provision-native-diagnostic-sdk.py"), 1)
            self.assertIn('"BILIPAI_NATIVE_SHARE_SDK_ROOT=$sdkRoot" | Add-Content -LiteralPath $env:GITHUB_ENV', source)
            self.assertIn('--temp-root "$env:RUNNER_TEMP" --output "$sdkRoot"', source)
        driver = (TOOLS / "run-tool-tests.py").read_text(encoding="utf-8")
        self.assertIn('"test_provision_native_diagnostic_sdk.py") if args.stage == "policies"', driver)

    def test_windows_long_sdk_path_preserves_exact_bytes(self):
        # The CI root is short; a developer's private verification root may not be.
        long_root = self.temp / ("a" * 90)
        self.assertTrue(long_root.resolve().is_relative_to(self.root.resolve()))
        self.addCleanup(shutil.rmtree, MODULE.file_path(long_root))
        sdk = long_root / ("b" * 90) / ("c" * 40)
        MODULE.file_path(sdk).mkdir(parents=True)
        header = b"#define REVIEWED 1\r\n"
        name = "Include/10.0.26100.0/cppwinrt/winrt/windows.ui.shell.companionwindows.h"
        package, archive = self.archive("LongPath", "headers", [("c/" + name, header)])
        MODULE.extract_package(archive, package, sdk)
        rows = [dict(path="sdk/" + name, bytes=len(header), sha256Bytes=hashlib.sha256(header).hexdigest())]
        MODULE.verify_sdk(sdk, rows)
        self.assertEqual(MODULE.file_path(sdk / name).read_bytes(), header)


if __name__ == "__main__":
    unittest.main()
