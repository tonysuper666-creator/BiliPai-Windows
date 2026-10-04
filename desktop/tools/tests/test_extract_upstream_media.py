import importlib.util
from pathlib import Path
import tempfile
import textwrap
import unittest
from unittest.mock import patch
from v025_source_paths import canonical_source

REPO = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location("extract_media", REPO / "desktop/tools/extract-upstream-media.py")
media = importlib.util.module_from_spec(spec)
spec.loader.exec_module(media)


class ExtractMediaTests(unittest.TestCase):
    def test_default_lambda_and_braces_in_literals_do_not_truncate_function(self):
        source = '''internal fun payload(
    json: Json = Json { ignoreUnknownKeys = true },
): String {
    val text = "{quoted}"
    // { comment
    return text
}

internal fun next(): Int { return 1 }
'''
        value = media.function(source, "payload", media.parser_for(REPO))
        self.assertTrue(value.endswith("return text\n}"))
        self.assertIn("Json { ignoreUnknownKeys = true }", value)
        self.assertNotIn("fun next", value)

    def test_ambiguous_original_function_fails_closed(self):
        with self.assertRaises(ValueError):
            media.function("fun changed(): Int { return 1 }\nfun changed(): Int { return 2 }", "changed", media.parser_for(REPO))

    def test_actual_generation_retains_original_algorithms_and_windows_binding(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            generated = media.generate(REPO, output)
            self.assertEqual(9, len(generated))
            pgc = (output / "com/android/purebilibili/data/repository/DesktopMediaPgcPolicies.kt").read_text(encoding="utf-8")
            original = media.read(REPO, media.BASE + "data/repository/BangumiRepository.kt")
            for name in ["decodeBangumiPlayUrlPayload", "mergeBangumiDetailSections", "validateBangumiPlayableVideoInfo"]:
                self.assertIn(media.function(original, name, media.parser_for(REPO)), pgc)
            repository = (output / "com/android/purebilibili/data/repository/DesktopDownloadDanmakuRepository.kt").read_text(encoding="utf-8")
            self.assertIn("DownloadDanmakuTransport.api", repository)
            self.assertIn("Semaphore(MAX_SEGMENT_PARALLELISM)", repository)
            self.assertIn("catch (e: CancellationException)", repository)
            self.assertNotIn("normalizeDanmakuDisplayArea", repository)
            # v025 moved the cache to core-data. Verify its complete original
            # schema/body, rather than the retired app repository field subset.
            cache_path = "core-data/src/main/java/com/android/purebilibili/data/repository/DanmakuContentRepository.kt"
            expected_cache = media.read(REPO, cache_path)
            for original_binding, windows_binding in (
                ("import com.android.purebilibili.core.network.NetworkModule\n", ""),
                ("object DanmakuContentRepository {", "object DanmakuRepository {"),
                ("    private val api = NetworkModule.api",
                 "    private val api get() = com.bilipai.desktop.download.DownloadDanmakuTransport.api"),
            ):
                self.assertEqual(expected_cache.count(original_binding), 1)
                expected_cache = expected_cache.replace(original_binding, windows_binding, 1)
            account_source = media.read(REPO, media.BASE + "data/repository/DanmakuRepository.kt")
            account_members = [textwrap.indent(media.function(account_source, name, media.parser_for(REPO)), "    ")
                               for name in ("getDanmakuView", "getSpecialDanmakuSegments")]
            account_members.append("    fun clearDanmakuCache() = clearCache()")
            closing = expected_cache.rfind("}")
            expected_cache = expected_cache[:closing] + "\n" + "\n\n".join(account_members) + "\n}" + expected_cache[closing + 1:]
            self.assertEqual(repository.split("\n", 2)[2], expected_cache.strip() + "\n")
            live = (output / "com/android/purebilibili/core/network/socket/LiveDanmakuClient.kt").read_text(encoding="utf-8")
            live_original = media.read(REPO, media.BASE + "core/network/socket/LiveDanmakuClient.kt")
            for name in ["sendAuthPacket", "startHeartbeat", "startHealthCheck", "scheduleReconnect", "handleMessage"]:
                self.assertEqual(media.function(live_original, name, media.parser_for(REPO)), media.function(live, name, media.parser_for(REPO)))
            self.assertIn("httpClient.newWebSocket", live)
            self.assertIn("System.nanoTime() / 1_000_000L", live)
            self.assertNotIn("NetworkModule.okHttpClient", live)
            self.assertNotIn("SystemClock", live)
            live_policy = (output / "com/android/purebilibili/data/repository/DesktopLivePolicies.kt").read_text(encoding="utf-8")
            live_policy_original = media.read(REPO, media.BASE + "data/repository/LiveRepository.kt")
            for name in ["parseLiveDanmakuPermission", "parseLiveDanmakuHistoryItems", "buildLiveHeartbeatQuery"]:
                self.assertIn(media.function(live_policy_original, name, media.parser_for(REPO)), live_policy)

    def test_new_cache_dependency_requires_explicit_review(self):
        real_read = media.read
        cache_path = "core-data/src/main/java/com/android/purebilibili/data/repository/DanmakuContentRepository.kt"
        original = canonical_source(REPO, cache_path).read_bytes()
        anchor = b"    private var danmakuSegmentCacheBytes = 0L"
        self.assertEqual(original.count(anchor), 1)
        with tempfile.TemporaryDirectory() as temporary:
            changed_repo = Path(temporary) / "changed-original"
            changed_source = changed_repo / cache_path
            changed_source.parent.mkdir(parents=True)
            changed_source.write_bytes(original.replace(anchor, anchor + b"\n    private val addedDependency = 1", 1))
            def changed(repo, path):
                # Retain the real canonical digest gate. Only this original file
                # comes from the changed fixture; other production reads remain real.
                return real_read(changed_repo if path == cache_path else repo, path)
            with patch.object(media, "read", side_effect=changed):
                with self.assertRaisesRegex(ValueError, "Canonical original source digest mismatch"):
                    media.generate(REPO, Path(temporary) / "output")

    def test_live_client_binding_change_requires_explicit_review(self):
        real_read = media.read
        def changed(repo, path):
            value = real_read(repo, path)
            if path.endswith("socket/LiveDanmakuClient.kt"):
                value = value.replace("NetworkModule.okHttpClient.newWebSocket", "NetworkModule.changedClient.newWebSocket")
            return value
        with tempfile.TemporaryDirectory() as temporary, patch.object(media, "read", side_effect=changed):
            with self.assertRaisesRegex(ValueError, "Live client platform binding changed"):
                media.generate(REPO, Path(temporary))


if __name__ == "__main__":
    unittest.main()
