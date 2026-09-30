from pathlib import Path
import hashlib
import importlib.util
import tempfile
import unittest
import json

HERE = Path(__file__).resolve().parent
SCRIPT = HERE / "prepare-js-worker.py"
if not SCRIPT.exists(): SCRIPT = HERE.parent / "prepare-js-worker.py"
spec = importlib.util.spec_from_file_location("worker_resources", SCRIPT)
worker = importlib.util.module_from_spec(spec); spec.loader.exec_module(worker)

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

if __name__ == "__main__": unittest.main()
