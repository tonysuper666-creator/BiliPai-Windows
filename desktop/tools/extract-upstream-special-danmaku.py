"""Fixed v029 whole special index/window and seekable IO with bounded Windows ports.

Canonical archives and the five BAS core files are never modified. Every platform
mapping is exact and reverses to the complete original file, including original tests.
"""
from pathlib import Path
import argparse
import hashlib
import json
import re

COMMIT = "a4b77f894d0a2dd26c0b9fc144b8adb88ac05480"
ARCHIVE = Path("desktop/upstream-slices/v029-special-danmaku")
MANIFEST_SHA256 = "2b6841c0fee87bcf79ba7bdfd82e2c385a529ece8b98491b33f10264997fc5c8"
PINS = {
    "SpecialDanmakuIndexReader.kt": "d2ad5a2b07bc0cbc41514296607e2083c8a30db9c539313faba049d370dad22e",
    "SpecialDanmakuWindow.kt": "223d7794053490c807c8da2997b4665ebf2d6cc44a8ff4df3eb7ae8d66eee86e",
    "SpecialDanmakuSource.kt": "50e277c19c3dce988363ffbdfbcbb46e982d5c5407b09d4528151973df946f96",
    "SpecialDanmakuIndexReaderTest.kt": "43f63cf8eae3e1e2726e6e5adbcbeb3c7522681118dea51e752dec1894eca49f",
    "SpecialDanmakuWindowTest.kt": "a6b93b1a88152e37c9938fd36becd0fe79e35cc996f8c69e81c2be351b1b61f6",
    "DownloadDanmakuAssetService.kt": "19ba7c2f4af4a093492fb5f04275732f097fbd00e1b4cda487243c59c84b596a",
    "DanmakuRepository.kt": "18cffbda990c2e513c4434e68219d75f96b5cdfccdfa4366bb2481005c6d03a1",
    "ApiClient.kt": "b6262b7ecd75350ec0a808e79a3daf8f38e1ef1910c4ceb4c8d9b4aa64c7d844",
}
def load_module(repo):
    import importlib.util
    spec = importlib.util.spec_from_file_location("desktop_special_danmaku", Path(repo) / "desktop/tools/extract-upstream-special-danmaku.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


PACKAGE = Path("com/android/purebilibili/danmaku/parser")


def checked_inputs(repo):
    root = Path(repo).resolve() / ARCHIVE
    raw_manifest = (root / "manifest.json").read_bytes()
    if hashlib.sha256(raw_manifest).hexdigest() != MANIFEST_SHA256:
        raise ValueError("Fixed special manifest changed")
    manifest = json.loads(raw_manifest)
    if manifest["fixedUpstreamCommit"] != COMMIT or {r["archiveFile"] for r in manifest["files"]} != set(PINS):
        raise ValueError("Unknown fixed special source set")
    result = {}
    for row in manifest["files"]:
        name = row["archiveFile"]
        path = root / name
        if root.is_symlink() or path.is_symlink() or path.resolve().parent != root.resolve():
            raise ValueError("Special archive must use regular original files")
        raw = path.read_bytes()
        if len(raw) != row["bytes"] or hashlib.sha256(raw).hexdigest() != PINS[name] or row["sha256Bytes"] != PINS[name] or hashlib.sha1(b"blob " + str(len(raw)).encode() + b"\0" + raw).hexdigest() != row["gitBlob"]:
            raise ValueError("Pinned special original bytes changed: " + name)
        if not row["originalPath"].endswith("/" + name):
            raise ValueError("Fixed special original path changed")
        result[name] = (row, raw.decode("utf8").replace("\r\n", "\n"))
    return result


def replace_exact(body, before, after, changes):
    if body.count(before) != 1:
        raise ValueError("Special platform boundary changed: " + before)
    changes.append(dict(before=before, after=after))
    return body.replace(before, after, 1)


def adapt(name, source):
    changes = []
    body = source
    if name == "SpecialDanmakuWindow.kt":
        for before, after in [
            ("import com.android.purebilibili.danmaku.engine.DanmakuItem", "import com.bilipai.desktop.danmaku.DanmakuComment as DanmakuItem\nimport com.bilipai.desktop.danmaku.DesktopSpecialParsedDanmaku as ParsedDanmaku\nimport com.bilipai.desktop.danmaku.DesktopSpecialParseScope"),
            ("    suspend fun load(", "    suspend fun load(positionMs: Long, lookAheadMs: Long = 3_000L, standardDurationMs: Long = 10_000L): SpecialDanmakuWindowResult =\n        DesktopSpecialParseScope.withWindow { loadBounded(positionMs, lookAheadMs, standardDurationMs) }\n\n    private suspend fun loadBounded("),
            ("        var index = 0\n", "        for (record in records) record.parsed?.let(DesktopSpecialParseScope::seed)\n\n        var index = 0\n"),
            ("        val bytes = record.source.readRange(entry.offset, entry.byteLength)", "        DesktopSpecialParseScope.beforeRead(entry.byteLength)\n        val bytes = record.source.readRange(entry.offset, entry.byteLength)"),
            ("                BasScriptParser.parseDurationMs(elem.content)", "                DesktopSpecialParseScope.duration(elem.content)"),
            ("        val parsed = DanmakuParser.parseElement(elem)", "        val parsed = DesktopSpecialParseScope.parse(bytes, elem)"),
        ]:
            body = replace_exact(body, before, after, changes)
    elif name == "DownloadDanmakuAssetService.kt":
        body = replace_exact(body, "import java.io.File", "import java.io.File\nimport kotlinx.coroutines.currentCoroutineContext\nimport kotlinx.coroutines.ensureActive", changes)
        body = replace_exact(body, "viewReply?.specialDms.orEmpty().mapIndexedNotNull", "viewReply?.specialDms.orEmpty().distinct().take(com.bilipai.desktop.danmaku.DesktopSpecialSourceLimits.MAX_SOURCES).mapIndexedNotNull", changes)
        body = replace_exact(body, "        val manifestFile = File(danmakuDir,", "        currentCoroutineContext().ensureActive()\n        com.bilipai.desktop.download.DownloadDanmakuTransport.currentBinding().assertCurrent()\n        val manifestFile = File(danmakuDir,", changes)
        for before, after in [('internal data class DownloadDanmakuAssetResult(\n    val segmentPaths: List<String>,\n    val metadataPath: String?\n)', 'internal data class DownloadDanmakuAssetResult(\n    val segmentPaths: List<String>,\n    val metadataPath: String?,\n    val expectedStandardSegmentCount: Int = 0\n)'), ('        val danmakuDir = File(taskDir, "danmaku").apply { mkdirs() }', '        com.bilipai.desktop.download.DownloadDanmakuTransport.currentBinding().assertCurrent()\n        val danmakuDir = File(taskDir, "danmaku").apply { mkdirs() }'), ('            file.writeBytes(bytes)', '            com.bilipai.desktop.download.DownloadDanmakuTransport.currentBinding().assertCurrent()\n            file.writeBytes(bytes)'), ('            metadataPath = manifestFile.absolutePath\n        )', '            metadataPath = manifestFile.absolutePath,\n            expectedStandardSegmentCount = resolveDanmakuSegmentCount(durationMs, viewReply?.dmSge?.total?.toInt())\n        )')]:
            body = replace_exact(body, before, after, changes)

    elif name == "SpecialDanmakuSource.kt":
        for before, after in [
            ('Log.w("DanmakuRepo", "Special danmaku index failed", e)', 'Log.w("DanmakuRepo", "Special danmaku index failed")'),
            ("import com.android.purebilibili.core.network.NetworkModule", "import com.android.purebilibili.core.network.BilibiliApi\nimport com.bilipai.desktop.danmaku.DesktopSpecialBodyRead"),
            ("internal fun indexSpecialDanmakuSources(\n", "internal fun indexSpecialDanmakuSources(\n    api: BilibiliApi,\n"),
            ("RemoteSpecialDanmakuSource.open(url)", "RemoteSpecialDanmakuSource.open(api, url)"),
            ("private class LocalSpecialDanmakuSource", "internal class LocalSpecialDanmakuSource"),
            ("private class RemoteSpecialDanmakuSource(\n", "internal class RemoteSpecialDanmakuSource(\n    private val api: BilibiliApi,\n"),
            ("suspend fun open(rawUrl: String)", "suspend fun open(api: BilibiliApi, rawUrl: String)"),
            ("NetworkModule.api.getDanmakuSpecialRange", "api.getDanmakuSpecialRange"),
            ("RemoteSpecialDanmakuSource(url, length, prefix)", "RemoteSpecialDanmakuSource(api, url, length, prefix)"),
            ("body.use {", "DesktopSpecialBodyRead.use(body) {"),
        ]:
            if before in ("NetworkModule.api.getDanmakuSpecialRange", "body.use {"):
                # The exact two upstream range calls use the same required configured port.
                if body.count(before) != 2:
                    raise ValueError("Original two range requests changed")
                changes.append(dict(before=before, after=after, count=2))
                body = body.replace(before, after)
            else:
                body = replace_exact(body, before, after, changes)
    inverse = body
    for change in reversed(changes):
        count = change.get("count", 1)
        if inverse.count(change["after"]) != count:
            raise ValueError("Ambiguous special inverse")
        inverse = inverse.replace(change["after"], change["before"], count)
    if inverse != source:
        raise ValueError("Special whole original inverse failed")
    return body, changes


def special_api_declarations(repo):
    original = checked_inputs(repo)["ApiClient.kt"][1]
    start = original.index("    @retrofit2.http.Streaming\n    @GET\n    suspend fun getDanmakuSpecialDm(")
    end = original.index("\n\n", original.index("): Response<ResponseBody>", start))
    return original[start:end]


def generate(repo, output, tests_output):
    originals = checked_inputs(repo)
    roots = {False: Path(output).resolve(), True: Path(tests_output).resolve()}
    if roots[False].is_relative_to(roots[True]) or roots[True].is_relative_to(roots[False]):
        raise ValueError("Special main and test outputs must be separate")
    archive = (Path(repo).resolve() / ARCHIVE).resolve()
    expected = {False: {}, True: {}}
    proof = []
    for name in ("SpecialDanmakuIndexReader.kt", "SpecialDanmakuWindow.kt", "SpecialDanmakuSource.kt", "SpecialDanmakuIndexReaderTest.kt", "SpecialDanmakuWindowTest.kt", "DownloadDanmakuAssetService.kt"):
        row, original = originals[name]
        body, changes = adapt(name, original)
        package = (Path("com/android/purebilibili/data/repository") if name == "SpecialDanmakuSource.kt" else
                   Path("com/android/purebilibili/feature/download") if name == "DownloadDanmakuAssetService.kt" else PACKAGE)
        relative = (package / name).as_posix()
        expected[row["testSource"]][relative] = body.encode()
        proof.append(dict(source=row["originalPath"], originalRawSha256=PINS[name], originalGitBlob=row["gitBlob"], generatedPath=relative, testSource=row["testSource"], wholeOriginalInverse=True, changes=changes))
    # Validate every output before publishing, protecting unrecognized Kotlin/source edits.
    for is_test, root in roots.items():
        if root.is_symlink() or root.is_relative_to(archive) or archive.is_relative_to(root):
            raise ValueError("Special output overlaps originals or uses a symlink")
        for file in root.rglob("*.kt") if root.exists() else ():
            relative = file.relative_to(root).as_posix()
            if file.is_symlink() or relative not in expected[is_test] or file.read_bytes() != expected[is_test][relative]:
                raise ValueError("Unknown or changed special output is protected: " + relative)
    paths = []
    for is_test, files in expected.items():
        for relative, raw in files.items():
            file = roots[is_test] / relative
            for parent in (file, *file.parents):
                if parent.is_symlink():
                    raise ValueError("Special output traverses a symlink")
                if parent == roots[is_test]:
                    break
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_bytes(raw)
            paths.append(file)
    receipt = dict(fixedUpstreamCommit=COMMIT, files=proof, originalTests=15,
        canonicalBaselineAdvanced=False, compiled=False, actualMainRendered=False,
        originalBasCoreUnchanged=True)
    (roots[False] / "source-proof.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf8")
    return paths


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--tests-output", type=Path, required=True)
    args = parser.parse_args()
    for path in generate(args.repo, args.output, args.tests_output):
        print(path)
