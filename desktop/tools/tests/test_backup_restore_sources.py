from pathlib import Path
import importlib.util
import unittest


REPO = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location(
    "backup_product_source_parser", REPO / "desktop/tools/extract-upstream-media.py"
)
helper = importlib.util.module_from_spec(spec)
spec.loader.exec_module(helper)
parser = helper.parser_for(REPO)


class BackupRestoreSources(unittest.TestCase):
    def test_original_http_methods_reach_product_with_existing_windows_bounds(self):
        relative = "com/android/purebilibili/feature/settings/webdav/WebDavBackupService.kt"
        original = (REPO / "app/src/main/java" / relative).read_text(encoding="utf-8")
        generated = (REPO / "desktop/build/generated/settings" / relative).read_text(encoding="utf-8")
        for name in (
            "testConnection", "listBackups", "backupNow", "restoreLatest",
            "listBackupsInternal", "ensureRemoteDirectory", "probeDirectoryDepthZero",
            "mkcolDirectory", "validateConfig",
        ):
            expected = helper.function(original, name, parser)
            if name == "restoreLatest":
                expected = expected.replace(
                    "val downloadUrl = resolveWebDavDownloadUrl(config.baseUrl, latest.href)",
                    "val downloadUrl = resolveWebDavDownloadUrl(config.baseUrl, latest.href).also { archive.requireSameOrigin(config.baseUrl, it) }",
                ).replace("response.body.bytes()", "archive.readDownload(response.body)")
            self.assertEqual(expected.strip(), helper.function(generated, name, parser).strip(), name)

    def test_committed_restore_notification_stays_outside_rollback_path(self):
        source = (REPO / "desktop/src/main/kotlin/com/bilipai/desktop/backup/DesktopBackupArchive.kt").read_text(encoding="utf-8")
        signal = source.index("        onSuccessfulRestore()")
        self.assertGreater(signal, source.index("            throw failure"))
        self.assertLess(signal, source.index("        return replaced.size"))


if __name__ == "__main__":
    unittest.main()
