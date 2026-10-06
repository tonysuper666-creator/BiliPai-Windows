import importlib.util
import hashlib
import json
from pathlib import Path
import tempfile
import shutil
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
            self.assertEqual(16, len(generated))
            image_path = output / "com/android/purebilibili/feature/live/components/DesktopOriginalLiveChatImage.kt"
            self.assertTrue(any(path.samefile(image_path) for path in generated))
            image = image_path.read_bytes()
            proof = json.loads((output / "v030-live-chat-image-source-proof.json").read_bytes())
            from v030_live_stream import safe
            fixed_root = REPO / "desktop/upstream-slices/v030-live-chat-image"
            fixed_identity = json.loads(safe(fixed_root / "manifest.json").read_bytes())["source"]
            self.assertEqual(fixed_identity, proof["source"])
            fixed = safe(fixed_root / fixed_identity["path"]).read_bytes()
            selected = fixed[proof["selectedByteStart"]:proof["selectedByteEnd"]]
            self.assertEqual(selected, proof["selectedUtf8"].encode("utf8"))
            self.assertEqual(hashlib.sha256(selected).hexdigest(), proof["selectedSha256"])
            self.assertEqual(hashlib.sha256(image).hexdigest(), proof["generatedSha256"])
            self.assertFalse(proof["wholeOriginalComposable"])
            self.assertTrue(proof["rangeInverseVerified"])
            # Reconstruct the original bytes from the actual generated function,
            # independently of the generator's reported inverse-success flag.
            emitted = media.function(image.decode("utf8"), "DesktopOriginalLiveChatImage", media.parser_for(REPO))
            restored = textwrap.dedent(emitted[emitted.index("{") + 1:emitted.rfind("}")].strip("\n"))
            for edit in reversed(proof["adaptations"]):
                self.assertEqual(1, edit["count"])
                self.assertEqual(1, restored.count(edit["after"]))
                restored = restored.replace(edit["after"], edit["before"], 1)
            self.assertEqual(selected, textwrap.indent(restored, " " * proof["indentationRemoved"]).encode("utf8"))
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
            adapted_cache=repository.split("\n",2)[2]
            streaming_member=textwrap.indent(media.function(repository,"downloadSpecialDanmaku",media.parser_for(REPO)),"    ")
            self.assertEqual(1,adapted_cache.count(streaming_member))
            adapted_cache=adapted_cache.replace(streaming_member+"\n\n", "", 1)
            extra_imports="\n\nimport java.io.File\nimport kotlinx.coroutines.currentCoroutineContext\nimport kotlinx.coroutines.ensureActive"
            self.assertEqual(1,adapted_cache.count(extra_imports))
            adapted_cache=adapted_cache.replace(extra_imports,"",1)
            self.assertEqual(adapted_cache, expected_cache.strip()+"\n")
            live = (output / "com/android/purebilibili/core/network/socket/LiveDanmakuClient.kt").read_text(encoding="utf-8")
            import v030_live_danmaku as fixed
            live_original = fixed.sources()["LiveDanmakuClient.kt"]
            expected_client, edits = fixed.adapt_client(live_original)
            self.assertEqual(expected_client, live)
            inverse = live
            for edit in reversed(edits):
                at = edit["offset"]
                self.assertEqual(edit["after"], inverse[at:at + len(edit["after"])])
                inverse = inverse[:at] + edit["before"] + inverse[at + len(edit["after"]):]
            self.assertEqual(live_original, inverse)
            self.assertIn("webSocketFactory.newWebSocket", live)
            self.assertIn("System.nanoTime() / 1_000_000L", live)
            self.assertNotIn("NetworkModule.okHttpClient", live)
            self.assertNotIn("SystemClock", live)
            live_policy = (output / "com/android/purebilibili/data/repository/DesktopLivePolicies.kt").read_text(encoding="utf-8")
            live_policy_original = media.read(REPO, media.BASE + "data/repository/LiveRepository.kt")
            for name in ["parseLiveDanmakuPermission", "parseLiveDanmakuHistoryItems", "buildLiveHeartbeatQuery"]:
                self.assertIn(media.function(live_policy_original, name, media.parser_for(REPO)), live_policy)

    def test_live_image_source_and_identity_tampering_fail_closed(self):
        from v030_live_stream import safe
        relative = Path("desktop/upstream-slices/v030-live-chat-image")
        for corrupt in ("raw-source", "manifest-identity"):
            with self.subTest(corrupt=corrupt), tempfile.TemporaryDirectory() as temporary:
                changed_repo = Path(temporary) / "changed-original"
                fixed = safe(changed_repo / relative)
                shutil.copytree(safe(REPO / relative), fixed)
                manifest = fixed / "manifest.json"
                identity = json.loads(manifest.read_bytes())
                if corrupt == "raw-source":
                    source = safe(fixed / identity["source"]["path"])
                    raw = source.read_bytes()
                    self.assertEqual(1, raw.count(b"model = item.emoticonUrl,"))
                    source.write_bytes(raw.replace(b"model = item.emoticonUrl,", b"model = item.emoticonURL,", 1))
                    expected = "Live chat image raw source changed"
                else:
                    identity["source"]["upstreamCommit"] = "0" * 40
                    manifest.write_bytes(json.dumps(identity).encode("utf8"))
                    expected = "Live chat image source identity changed"
                output = Path(temporary) / "output"
                with self.assertRaisesRegex(ValueError, expected):
                    media.emit_live_chat_image(changed_repo, output)
                self.assertFalse(output.exists(), "Untrusted input must fail before generated output is written")

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
        import v030_live_danmaku as fixed
        with tempfile.TemporaryDirectory() as temporary:
            raw = Path(temporary) / "fixed-source"
            shutil.copytree(fixed.ROOT, raw)
            client = raw / "LiveDanmakuClient.kt"
            client.write_bytes(client.read_bytes().replace(b"NetworkModule.okHttpClient", b"NetworkModule.changedClient"))
            with patch.object(fixed, "ROOT", raw), self.assertRaises(AssertionError):
                media.generate(REPO, Path(temporary) / "output")


if __name__ == "__main__":
    unittest.main()
