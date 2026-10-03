"""Reuse upstream WebDAV protocol methods; bind only Windows file storage."""
from __future__ import annotations
from v025_source_paths import canonical_source as _desktop_canonical_source
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import textwrap

BASE = "app/src/main/java/com/android/purebilibili/feature/settings/webdav/"
SOURCES = {
    BASE + "WebDavBackupPolicy.kt": "direct",
    BASE + "WebDavBackupAutoSchedulePolicy.kt": "direct",
    BASE + "WebDavBackupStore.kt": "policy-extract",
    BASE + "WebDavBackupService.kt": "extracted",
}

def read(repo: Path, path: str) -> str:
    return (_desktop_canonical_source(repo, path)).read_text(encoding="utf-8").replace("\r\n", "\n")

def generate(repo: Path, output: Path) -> None:
    spec = importlib.util.spec_from_file_location("settings_source_parser", repo / "desktop/tools/extract-upstream-media.py")
    helper = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(helper)
    parser = helper.parser_for(repo)
    package = "com.android.purebilibili.feature.settings.webdav"
    path = BASE + "WebDavBackupStore.kt"
    source = read(repo, path)
    body = "package " + package + "\n\nimport kotlinx.serialization.Serializable\n\n@Serializable\n" + helper.data_class(source, "WebDavBackupConfig", parser)
    helper.write(output, package.replace(".", "/") + "/DesktopWebDavConfig.kt", path, source, body)
    path = BASE + "WebDavBackupService.kt"
    source = read(repo, path)
    methods = ["testConnection", "listBackups", "backupNow", "restoreLatest", "listBackupsInternal",
               "ensureRemoteDirectory", "probeDirectoryDepthZero", "mkcolDirectory", "validateConfig"]
    pieces = ["package " + package,
        "import kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.withContext\nimport okhttp3.*\nimport okhttp3.MediaType.Companion.toMediaType\nimport okhttp3.RequestBody.Companion.toRequestBody\nimport java.io.IOException",
        'private const val XML_CONTENT_TYPE = "application/xml; charset=utf-8"\nprivate const val ZIP_CONTENT_TYPE = "application/zip"',
        "class WebDavBackupService @JvmOverloads constructor(private val archive: com.bilipai.desktop.backup.DesktopBackupArchive, private val onSuccessfulRestore: () -> Unit = {}) {",
        "    private val httpClient = OkHttpClient.Builder().connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS).readTimeout(20, java.util.concurrent.TimeUnit.SECONDS).writeTimeout(20, java.util.concurrent.TimeUnit.SECONDS).retryOnConnectionFailure(true).followRedirects(false).followSslRedirects(false).build()",
        "    private fun buildBackupArchive(nowEpochMs: Long): ByteArray = archive.create(nowEpochMs)",
        "    private fun restoreFromBackupArchive(bytes: ByteArray): Int = archive.restore(bytes, onSuccessfulRestore)",
    ]
    for name in methods:
        declaration = helper.function(source, name, parser)
        if name == "restoreLatest":
            original = "val downloadUrl = resolveWebDavDownloadUrl(config.baseUrl, latest.href)"
            assert declaration.count(original) == 1
            declaration = declaration.replace(original, original + ".also { archive.requireSameOrigin(config.baseUrl, it) }")
            original = "response.body.bytes()"
            assert declaration.count(original) == 1
            declaration = declaration.replace(original, "archive.readDownload(response.body)")
        pieces.append(textwrap.indent(declaration, "    "))
    pieces.append("}")
    helper.write(output, package.replace(".", "/") + "/WebDavBackupService.kt", path, source, "\n\n".join(pieces))

if __name__ == "__main__":
    command = argparse.ArgumentParser(description=__doc__)
    command.add_argument("--repo", type=Path, required=True)
    command.add_argument("--output", type=Path)
    command.add_argument("--inventory", action="store_true")
    arguments = command.parse_args()
    repo = arguments.repo.resolve()
    if arguments.inventory:
        print(json.dumps([{"path": path, "mode": mode, "features": ["settings", "backup"],
            "sha256": hashlib.sha256(read(repo, path).encode()).hexdigest()} for path, mode in SOURCES.items()], indent=2))
    elif arguments.output:
        generate(repo, arguments.output.resolve())
    else:
        command.error("Pass --output or --inventory")
