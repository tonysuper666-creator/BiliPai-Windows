from pathlib import Path
import hashlib
import importlib.util
import tempfile
import unittest
import json
import zipfile
from unittest.mock import patch

HERE = Path(__file__).resolve().parent
SCRIPT = HERE / "prepare-js-worker.py"
if not SCRIPT.exists(): SCRIPT = HERE.parent / "prepare-js-worker.py"
spec = importlib.util.spec_from_file_location("worker_resources", SCRIPT)
worker = importlib.util.module_from_spec(spec); spec.loader.exec_module(worker)
fetch_spec = importlib.util.spec_from_file_location("worker_jdk_fetch", SCRIPT.with_name("fetch-js-worker-jdk.py"))
fetcher = importlib.util.module_from_spec(fetch_spec); fetch_spec.loader.exec_module(fetcher)

class WorkerJdkFetchTest(unittest.TestCase):
    def archive_fixture(self, root):
        archive = root / "jdk.zip"
        with zipfile.ZipFile(archive, "w") as source:
            source.writestr("fixture-jdk/bin/java.exe", b"fixed java fixture")
            source.writestr("fixture-jdk/release", b"fixed release fixture")
        return archive, hashlib.sha256(archive.read_bytes()).hexdigest()

    def test_gradle_empty_output_is_filled_only_after_every_archive_member_is_verified(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); archive, digest = self.archive_fixture(root)
            output = root / "fixed-jdk"; output.mkdir()
            with patch.object(fetcher.worker, "JDK_SHA256", digest):
                self.assertEqual(fetcher.fetch(archive, output, True), output.resolve())
                fetcher.worker.verify_jdk(output, archive)
            self.assertEqual((output / "bin/java.exe").read_bytes(), b"fixed java fixture")

    def test_changed_populated_jdk_fails_without_deleting_or_replacing_it(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); archive, digest = self.archive_fixture(root)
            output = root / "fixed-jdk"; output.mkdir()
            protected = output / "release"; protected.write_bytes(b"changed release")
            with patch.object(fetcher.worker, "JDK_SHA256", digest), self.assertRaises(ValueError):
                fetcher.fetch(archive, output, True)
            self.assertEqual(protected.read_bytes(), b"changed release")

    def test_concurrently_populated_placeholder_is_preserved(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); archive, digest = self.archive_fixture(root)
            output = root / "fixed-jdk"; output.mkdir()
            protected = output / "foreign.txt"
            verify = fetcher.worker.verify_jdk
            def concurrent_writer(home, source):
                verify(home, source)
                protected.write_bytes(b"must remain")
            with patch.object(fetcher.worker, "JDK_SHA256", digest), patch.object(fetcher.worker, "verify_jdk", concurrent_writer), self.assertRaises(OSError):
                fetcher.fetch(archive, output, True)
            self.assertEqual(protected.read_bytes(), b"must remain")

class WorkerResourcesTest(unittest.TestCase):
    def cache_fixture(self, root):
        inputs = {"workerSourceSha256": "approved-source", "mavenLockSha256": "approved-lock",
            "noticeCatalogSha256": hashlib.sha256(b"{}").hexdigest(), "noticeFiles": {}, "jdkArchiveSha256": worker.JDK_SHA256}
        rows, lock = [], []
        def add(name, payload, role):
            path = root / name; path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(payload)
            row = worker.record(root, path, role); rows.append(row); return row
        add("desktop-js-worker.jar", b"worker", "worker")
        for index in range(12):
            row = add(f"maven/org/graalvm/fixture{index}/24.2.2/fixture{index}-24.2.2.jar", bytes([index]), "engine")
            lock.append(dict(row))
        runtime = add("runtime/lib/modules", b"fixed linked modules fixture", "runtime")
        provenance = {"owner": worker.OWNER, "inputs": inputs}
        add("provenance.json", json.dumps(provenance).encode(), "notice")
        add("engine-artifacts.json", json.dumps(lock).encode(), "notice")
        add("notices/catalog.json", b"{}", "notice")
        catalog = {"owner": worker.OWNER, "schemaVersion": 1, "engineVersion": worker.VERSION,
            "jdkVersion": worker.JDK_VERSION, "mainClass": worker.MAIN, "runtimeModules": list(worker.MODULES),
            "classpath": rows[:13], "resources": rows[13:]}
        worker.write_json(root / "classpath.json", catalog)
        runtime_lock = {"files": [dict(runtime, file="lib/modules")]}
        return inputs, lock, runtime_lock, catalog

    def test_untrusted_resource_paths_cannot_escape_or_select_windows_drives(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            for relative in ("../escape.jar", "/absolute.jar", "C:/foreign.jar", "a\\b.jar", "maven/../../escape"):
                with self.subTest(relative=relative), self.assertRaises(ValueError): worker.safe_relative(root, relative)
            self.assertEqual(worker.safe_relative(root, "maven/fixed/engine.jar"), root / "maven/fixed/engine.jar")

    def test_changed_engine_and_notice_bytes_fail_even_when_size_is_unchanged(self):
        with tempfile.TemporaryDirectory() as temp:
            artifact = Path(temp) / "engine.jar"; artifact.write_bytes(b"trusted")
            record = {"bytes": 7, "sha256": hashlib.sha256(b"trusted").hexdigest()}
            worker.verify_file(artifact, record)
            artifact.write_bytes(b"changed")
            with self.assertRaises(ValueError): worker.verify_file(artifact, record)

    def test_unpinned_jdk_archive_fails_before_extract_or_tool_launch(self):
        with tempfile.TemporaryDirectory() as temp:
            archive = Path(temp) / "jdk.zip"; archive.write_bytes(b"untrusted archive")
            with self.assertRaises(ValueError): worker.verify_jdk(Path(temp) / "jdk", archive)
            self.assertFalse((Path(temp) / "jdk").exists())

    def test_existing_resource_directory_is_preserved_without_recursive_replacement(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); output = root / "resources"; output.mkdir()
            protected = output / "existing.jar"; protected.write_bytes(b"must remain")
            with self.assertRaises(ValueError): worker.prepare(root, output, root / "jdk", root / "archive",
                root / "cache", root / "lock", root / "notices", root / "catalog", root / "generated", True)
            self.assertEqual(protected.read_bytes(), b"must remain")

    def test_build_environment_removes_java_injection_and_classpath(self):
        from unittest.mock import patch
        with patch.dict(worker.os.environ, {"JAVA_TOOL_OPTIONS": "-javaagent:foreign.jar", "CLASSPATH": "foreign", "PATH": "foreign", "SystemRoot": "C:/Windows"}, clear=True):
            self.assertEqual({key.upper(): value for key, value in worker.clean_env().items()}, {"SYSTEMROOT": "C:/Windows"})

    def test_exact_cache_input_and_all_files_allow_verified_reuse_without_rewriting(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); inputs, lock, runtime_lock, catalog = self.cache_fixture(root)
            before = {path.name: path.read_bytes() for path in root.rglob("*") if path.is_file()}
            self.assertEqual(worker.verify_existing(root, inputs, lock, runtime_lock), catalog)
            self.assertEqual(before, {path.name: path.read_bytes() for path in root.rglob("*") if path.is_file()})

    def test_changed_source_input_rejects_cache_and_preserves_previous_files(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); inputs, lock, runtime_lock, _ = self.cache_fixture(root)
            before = (root / "desktop-js-worker.jar").read_bytes()
            with self.assertRaises(ValueError): worker.verify_existing(root, dict(inputs, workerSourceSha256="changed-source"), lock, runtime_lock)
            self.assertEqual((root / "desktop-js-worker.jar").read_bytes(), before)

    def test_changed_engine_bytes_reject_reuse_even_when_filename_and_size_match(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); inputs, lock, runtime_lock, _ = self.cache_fixture(root)
            (root / lock[0]["file"]).write_bytes(b"x")
            with self.assertRaises(ValueError): worker.verify_existing(root, inputs, lock, runtime_lock)

    def test_forged_runtime_catalog_cannot_replace_fixed_runtime_pins(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); inputs, lock, runtime_lock, catalog = self.cache_fixture(root)
            path = root / "runtime/lib/modules"; path.write_bytes(b"replaced runtime")
            catalog["resources"][0].update(worker.record(root, path, "runtime"))
            worker.write_json(root / "classpath.json", catalog)
            with self.assertRaises(ValueError): worker.verify_existing(root, inputs, lock, runtime_lock)

    def test_extra_untracked_file_rejects_reuse_and_is_not_deleted(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); inputs, lock, runtime_lock, _ = self.cache_fixture(root)
            extra = root / "foreign.jar"; extra.write_bytes(b"must remain")
            with self.assertRaises(ValueError): worker.verify_existing(root, inputs, lock, runtime_lock)
            self.assertEqual(extra.read_bytes(), b"must remain")

class WorkerJdkNoticeTest(unittest.TestCase):
    def fixture(self, root):
        payload = b"fixed synthetic Temurin NOTICE\n"
        archive = root / "jdk.zip"
        with zipfile.ZipFile(archive, "w") as packed: packed.writestr(worker.JDK_NOTICE_ENTRY, payload)
        notice_root = root / "source"; notice_root.mkdir()
        (notice_root / worker.JDK_NOTICE_FILE).write_bytes(payload)
        archive_sha = hashlib.sha256(archive.read_bytes()).hexdigest()
        row = {"url": worker.JDK_URL, "sourceType": "fixed-jdk-archive-entry", "archiveSha256": archive_sha,
            "archiveEntry": worker.JDK_NOTICE_ENTRY, "file": "licenses/" + worker.JDK_NOTICE_FILE,
            "bytes": len(payload), "sha256": hashlib.sha256(payload).hexdigest()}
        return archive, notice_root, {"complete": False, "sources": [row]}, archive_sha

    def test_fixed_archive_notice_bytes_are_consumed_by_actual_prepare_copy(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); archive, source, catalog, fixed_sha = self.fixture(root)
            with patch.object(worker, "JDK_SHA256", fixed_sha):
                receipt = worker.verify_jdk_notice(archive, catalog, source)
            catalog_path = root / "catalog.json"; worker.write_json(catalog_path, catalog)
            output = root / "out"; output.mkdir()
            worker.copy_notices(source, catalog_path, output)
            notice = output / receipt["file"]
            worker.verify_file(notice, receipt)
            self.assertEqual(notice.read_bytes(), (source / worker.JDK_NOTICE_FILE).read_bytes())
            self.assertFalse(json.loads((output / "notices/catalog.json").read_text())["complete"])

    def test_changed_notice_source_or_catalog_cannot_claim_archive_identity(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); archive, source, catalog, fixed_sha = self.fixture(root)
            with patch.object(worker, "JDK_SHA256", fixed_sha):
                row = catalog["sources"][0]
                row["sha256"] = "0" * 64
                with self.assertRaises(ValueError): worker.verify_jdk_notice(archive, catalog, source)
                row["sha256"] = hashlib.sha256((source / worker.JDK_NOTICE_FILE).read_bytes()).hexdigest()
                (source / worker.JDK_NOTICE_FILE).write_bytes(b"x" * row["bytes"])
                with self.assertRaises(ValueError): worker.verify_jdk_notice(archive, catalog, source)

    def test_missing_duplicate_or_wrong_origin_notice_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); archive, source, catalog, fixed_sha = self.fixture(root)
            row = catalog["sources"][0]
            with patch.object(worker, "JDK_SHA256", fixed_sha):
                for rows in ([], [row, row], [dict(row, archiveEntry="foreign/NOTICE")],
                             [dict(row, file="licenses/../" + worker.JDK_NOTICE_FILE)],
                             [dict(row, url="https://example.invalid/NOTICE")]):
                    with self.subTest(rows=rows), self.assertRaises(ValueError):
                        worker.verify_jdk_notice(archive, {"sources": rows}, source)

    def test_changed_archive_is_rejected_before_notice_copy(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); archive, source, catalog, fixed_sha = self.fixture(root)
            archive.write_bytes(archive.read_bytes() + b"changed")
            with patch.object(worker, "JDK_SHA256", fixed_sha), self.assertRaises(ValueError):
                worker.verify_jdk_notice(archive, catalog, source)
            self.assertFalse((root / "out").exists())

    def test_cached_provenance_cannot_omit_the_current_notice_receipt(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            inputs, lock, runtime_lock, _ = WorkerResourcesTest().cache_fixture(root)
            receipt = {"file": "notices/" + worker.JDK_NOTICE_FILE, "archiveEntry": worker.JDK_NOTICE_ENTRY,
                "bytes": 5, "sha256": "0" * 64}
            inputs["workerJdkNotice"] = receipt
            provenance = {"owner": worker.OWNER, "inputs": inputs}
            worker.write_json(root / "provenance.json", provenance)
            with self.assertRaisesRegex(ValueError, "NOTICE provenance differs"):
                worker.verify_existing(root, inputs, lock, runtime_lock)

if __name__ == "__main__": unittest.main()
