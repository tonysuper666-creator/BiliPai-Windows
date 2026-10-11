"""Physical fixed pins and actual sole API/media CLI contracts, without Kotlin execution."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

REPO = Path(os.environ.get("BILIPAI_TEST_SOURCE_REPO", str(Path(__file__).resolve().parents[3])))
TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO / "desktop/tools"))
sys.path.insert(0, str(TOOLS))
import v030_live_stream as live
from v025_source_paths import canonical_source


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


class V030LiveStreamSourceTests(unittest.TestCase):
    def test_raw_blobs_and_manifest_are_physical_exact(self):
        sources = live.fixed_sources()
        self.assertEqual(set(live.PINS), set(sources))
        with tempfile.TemporaryDirectory() as temporary:
            archive = Path(temporary)
            for path in ["manifest.json", *live.PINS]:
                target = archive / path
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(live.safe(live.ARCHIVE / path).read_bytes())
            changed = archive / live.POLICY
            changed.write_bytes(changed.read_bytes() + b"\n")
            with patch.object(live, "ARCHIVE", archive):
                with self.assertRaisesRegex(ValueError, "raw bytes changed"):
                    live.fixed_sources()
            raw = live.safe(live.ARCHIVE / live.POLICY).read_bytes()
            changed.write_bytes(raw.replace(b"\r\n", b"\n") if b"\r\n" in raw else raw.replace(b"\n", b"\r\n"))
            with patch.object(live, "ARCHIVE", archive):
                with self.assertRaisesRegex(ValueError, "raw bytes changed"):
                    live.fixed_sources()

    def test_manifest_cannot_repin_changed_blob(self):
        with tempfile.TemporaryDirectory() as temporary:
            archive = Path(temporary)
            manifest = json.loads(live.safe(live.ARCHIVE / "manifest.json").read_bytes())
            manifest["files"][0]["sha256Bytes"] = "0" * 64
            (archive / "manifest.json").write_text(json.dumps(manifest), encoding="utf8")
            with patch.object(live, "ARCHIVE", archive):
                with self.assertRaisesRegex(ValueError, "identity changed"):
                    live.fixed_sources()

    def test_actual_api_and_model_have_one_producer_and_complete_inverse(self):
        api = module("v030_actual_api", TOOLS / "extract-upstream-api.py")
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            target = api.generate(REPO, output)
            actual = target.read_text(encoding="utf8")
            declaration = live.api_method(live.fixed_sources()[live.API])
            self.assertEqual(1, actual.count(declaration))
            self.assertIn("@QueryMap params: Map<String, String>", declaration)
            models = list(output.rglob("LiveModels.kt"))
            self.assertEqual(1, len(models))
            generated_model = models[0].read_text(encoding="utf8")
            canonical = canonical_source(REPO, live.MODEL).read_text(encoding="utf8")
            proof = json.loads((output / "v030-live-model-source-proof.json").read_text(encoding="utf8"))
            edits = proof["completeBody"]["indexedEdits"]
            self.assertEqual(canonical, live.replay(generated_model, edits, True))
            self.assertEqual(generated_model, live.replay(canonical, edits, False))
            self.assertEqual(live.model_declaration(live.fixed_sources()[live.MODEL]), live.model_declaration(generated_model))
            joined_proof = json.loads((output / "v030-live-api-source-proof.json").read_text(encoding="utf8"))
            actual_body = actual.split("import okhttp3.ResponseBody\n\n", 1)[1].removesuffix("\n")
            edits = joined_proof["completeBody"]["indexedEdits"]
            before = live.replay(actual_body, edits, True)
            self.assertEqual(joined_proof["completeBody"]["beforeSha256LF"], live.sha(before.encode()))
            self.assertEqual(actual_body, live.replay(before, edits, False))
            selected = joined_proof["countedAdaptations"][0]
            self.assertEqual(1, actual.count(selected["after"]))
            self.assertNotIn("signedParams: Map<String, String>", selected["after"])

    def test_actual_media_full_request_and_policy_inverse(self):
        media = module("v030_actual_media", TOOLS / "extract-upstream-media.py")
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "main"
            tests = Path(temporary) / "tests"
            generated = media.generate(REPO, output, tests)
            # The sole media output is 23 main files plus three complete socket
            # tests; the existing stream test is emitted separately as before.
            main_names = {
                'com/android/purebilibili/data/repository/DesktopMediaPgcPolicies.kt',
                'com/android/purebilibili/data/repository/DesktopDownloadDanmakuRepository.kt',
                'com/android/purebilibili/danmaku/parser/DesktopDanmakuMetadataParser.kt',
                'com/android/purebilibili/feature/download/DesktopOfflinePositionPolicy.kt',
                'com/android/purebilibili/data/repository/DesktopLiveHosts.kt',
                'com/android/purebilibili/core/network/socket/LiveDanmakuClient.kt',
                'com/android/purebilibili/core/network/socket/LiveDanmakuConnectionHealthPolicy.kt',
                'com/android/purebilibili/core/network/socket/DanmakuProtocol.kt',
                'com/android/purebilibili/data/repository/DesktopLivePolicies.kt',
                'com/android/purebilibili/feature/live/DesktopLiveDanmakuItem.kt',
                'com/android/purebilibili/feature/bangumi/DesktopFollowPolicies.kt',
                'com/android/purebilibili/data/repository/DesktopOriginalLiveStreamRequest.kt',
                'com/android/purebilibili/feature/live/DesktopOriginalLiveStreamPolicy.kt',
                'com/android/purebilibili/feature/live/components/LiveStreamSourceSheet.kt',
                'com/android/purebilibili/feature/live/DesktopLiveReloadBudget.kt',
                'com/android/purebilibili/feature/live/components/DesktopOriginalLiveChatImage.kt',
                'com/android/purebilibili/data/repository/DesktopLiveVoteSnapshot.kt',
                'com/android/purebilibili/feature/live/components/LiveVotePanel.kt',
                'com/android/purebilibili/data/repository/LiveInteractionModels.kt',
                'com/android/purebilibili/feature/live/LiveRealtimeMessagePolicy.kt',
                'com/android/purebilibili/feature/live/LiveDanmakuFilterPolicy.kt',
                'com/android/purebilibili/data/repository/DesktopLiveModerationRequests.kt',
                'com/android/purebilibili/feature/live/components/DesktopOriginalLiveModerationUi.kt',
            }
            socket_test_names = {
                'com/android/purebilibili/core/network/socket/LiveDanmakuClientTest.kt',
                'com/android/purebilibili/core/network/socket/LiveDanmakuConnectionHealthPolicyTest.kt',
                'com/android/purebilibili/core/network/socket/DanmakuProtocolLimitsTest.kt',
            }
            # emit_media uses extended Windows paths; compare their exact same
            # physical identities without changing any generated source bytes.
            normal = lambda path: str(path).removeprefix("\\\\?\\").replace("\\", "/")
            expected = {normal(output / path) for path in main_names} | {normal(tests / path) for path in socket_test_names}
            actual = [normal(path) for path in generated]
            self.assertEqual(23, len(main_names)); self.assertEqual(3, len(socket_test_names))
            self.assertEqual(expected, set(actual))
            self.assertEqual(len(expected), len(actual))
            sources = live.fixed_sources()
            request = (output / "com/android/purebilibili/data/repository/DesktopOriginalLiveStreamRequest.kt").read_text(encoding="utf8")
            proof = json.loads((output / "v030-live-request-source-proof.json").read_text(encoding="utf8"))
            self.assertEqual(sources[live.REPOSITORY], live.replay(request, proof["completeBody"]["indexedEdits"], True))
            self.assertEqual(live.selected_function(REPO, sources[live.REPOSITORY], "buildLivePlayUrlQuery"), proof["queryOriginalExact"])
            self.assertIn(proof["queryOriginalExact"], request)
            self.assertEqual(proof["requestSelectionOriginal"],
                live.replay(proof["requestSelectionAdapted"], proof["selectedRequestInverse"]["indexedEdits"], True))
            policy = (output / "com/android/purebilibili/feature/live/DesktopOriginalLiveStreamPolicy.kt").read_text(encoding="utf8")
            policy_proof = json.loads((output / "v030-live-policy-source-proof.json").read_text(encoding="utf8"))
            self.assertEqual(sources[live.POLICY], live.replay(policy, policy_proof["completeBody"]["indexedEdits"], True))
            for name in ["resolveLivePlayback", "advanceLivePlayback", "resolveLiveQualityList",
                         "streamProtocolPriority", "streamFormatPriority", "streamCodecPriority"]:
                self.assertEqual(live.selected_function(REPO, sources[live.POLICY], name), live.selected_function(REPO, policy, name))
            original_tests = (tests / "com/android/purebilibili/feature/live/LivePlaybackPolicyTest.kt").read_text(encoding="utf8")
            test_proof = json.loads((tests / "v030-live-original-test-source-proof.json").read_text(encoding="utf8"))
            self.assertEqual(14, test_proof["originalTestCount"])
            self.assertEqual(12, original_tests.count("@Test"))
            self.assertEqual(sources[live.TEST], live.replay(original_tests, test_proof["completeBody"]["indexedEdits"], True))
            self.assertNotIn("import androidx.media3", original_tests)

            # New named outputs still restore the complete pinned original bodies.
            import v034_live_moderation as moderation
            parser = media.parser_for(REPO)
            fixed = moderation.fixed_inputs(REPO)
            fixed_hashes = {
                'policy': 'b8209ccf31596fcf89dfe38024e6d3d9bf65e797577cefac05ac14cd440e2d4e',
                'models': '3a546bd71fd792388ce7d969051a9bff4f0db2c3e301e9499e2f13d806247871',
                'realtime': '002016da3e66429de054b92581e375db865b67d07fd6e1c6791b1d939696762d',
                'api': '1ed0da47deb801a5a2f6f2a63e6bf2e9e8674bb861e573b45956f1bd5ed2febb',
                'repo': '554ca3c32522c88148144c4221879701657aab296519ea0da4162661039d4857',
                'vm': 'e86a019aa43d9d5aca595ff6073bb0cf50d00436142bb5a0bda50655b2507e74',
            }
            self.assertEqual(set(fixed_hashes), set(fixed))
            for key, digest in fixed_hashes.items():
                raw = live.safe(REPO / moderation.PINS[key]['archive']).read_bytes()
                self.assertEqual(digest, live.sha(raw))
                self.assertEqual(fixed[key], raw.decode('utf8').replace('\r\n', '\n'))
            vote = (output / 'com/android/purebilibili/data/repository/DesktopLiveVoteSnapshot.kt').read_text(encoding='utf8')
            vote_proof = json.loads((output / 'v034-live-vote-source-proof.json').read_bytes())
            self.assertEqual('b2091b1de636e499df80af7e700796b4f52b7f92', vote_proof['fixedUpstreamCommit'])
            self.assertEqual('062465cc3e445b911c095c8cf82ec2554d1105ecff39b1e40f84f90903e43520', live.sha(vote.encode()))
            vote_method = media.function(vote, 'requestOriginalDesktopLiveVoteSnapshot', parser)
            original_method = media.function(fixed['repo'], 'getLiveVoteSnapshot', parser)
            self.assertEqual(original_method, live.replay(vote_method, vote_proof['snapshotMethod']['indexedEdits'], True))
            projected = live.replay(fixed['repo'], vote_proof['wholeRepository']['indexedEdits'], False)
            self.assertEqual(fixed['repo'], live.replay(projected, vote_proof['wholeRepository']['indexedEdits'], True))
            self.assertIn(vote_proof['originalMergeRange'], fixed['vm'])
            self.assertEqual(vote_proof['generatedMergeMethod'], media.function(vote, 'applyOriginalDesktopLiveVoteUpdate', parser))
            self.assertIs(True, vote_proof['exactWholeViewModelExtractionInverse'])
            proof = json.loads((output / 'v034-live-moderation-source-proof.json').read_bytes())
            self.assertEqual('b2091b1de636e499df80af7e700796b4f52b7f92', proof['fixedCommit'])
            self.assertEqual(['getLiveShieldInfo', 'setLiveSilentRule', 'addLiveShieldKeyword', 'deleteLiveShieldKeyword',
                              'setLiveShieldUser', 'reportLiveDanmaku', 'reportSuperChat'],
                             [row['name'] for row in proof['fullRequestMethods']])
            requests = (output / 'com/android/purebilibili/data/repository/DesktopLiveModerationRequests.kt').read_text(encoding='utf8')
            for row in proof['fullRequestMethods']:
                emitted_name = row['mappings'][0][1].split('fun ', 1)[1].split('(', 1)[0]
                actual_method = media.function(requests, emitted_name, parser)
                self.assertEqual(row['adaptedSha256LF'], live.sha(actual_method.encode()))
                inverse = actual_method
                for old, new in reversed(row['mappings']):
                    self.assertEqual(1, inverse.count(new)); inverse = inverse.replace(new, old, 1)
                self.assertEqual(media.function(fixed['repo'], row['name'], parser), inverse)
                self.assertEqual(row['originalSha256LF'], live.sha(inverse.encode()))
            models = (output / 'com/android/purebilibili/data/repository/LiveInteractionModels.kt').read_text(encoding='utf8')
            models = models[models.index('package '):]
            for old, new in reversed(proof['schemaMappings']):
                self.assertEqual(1, models.count(new)); models = models.replace(new, old, 1)
            self.assertEqual(media.read(REPO, moderation.PINS['models']['path']), models)
            realtime = (output / 'com/android/purebilibili/feature/live/LiveRealtimeMessagePolicy.kt').read_text(encoding='utf8')
            realtime = realtime[realtime.index('package '):]
            system = media.function(realtime, 'systemMessage', parser)
            self.assertEqual(media.function(fixed['realtime'], 'systemMessage', parser), system)
            original_realtime = media.read(REPO, moderation.PINS['realtime']['path'])
            self.assertEqual(1, realtime.count(system))
            self.assertEqual(original_realtime, realtime.replace(system, media.function(original_realtime, 'systemMessage', parser), 1))
            filtered = (output / 'com/android/purebilibili/feature/live/LiveDanmakuFilterPolicy.kt').read_text(encoding='utf8')
            self.assertEqual(fixed['policy'].strip() + '\n', filtered[filtered.index('package '):])
            item = (output / 'com/android/purebilibili/feature/live/DesktopLiveDanmakuItem.kt').read_text(encoding='utf8')
            self.assertEqual(media.data_class(fixed['vm'], 'LiveDanmakuItem', parser), media.data_class(item, 'LiveDanmakuItem', parser))

    def test_exact_input_adaptation_drift_rejects(self):
        with tempfile.TemporaryDirectory() as temporary:
            original = canonical_source(REPO, live.API).read_text(encoding="utf8")
            with self.assertRaisesRegex(ValueError, "Counted live-stream adaptation changed"):
                live.api_delta(original.replace("suspend fun getLivePlayUrl(", "suspend fun changedLivePlayUrl(", 1), REPO, Path(temporary))
            selected = live.fixed_sources()[live.POLICY].replace("import androidx.media3.common.Player\n", "")
            with self.assertRaisesRegex(ValueError, "Counted live-stream adaptation changed"):
                live.stream_policy(selected)

    def test_actual_cli_clean_generation_is_byte_reproducible(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            env = dict(os.environ, PYTHONDONTWRITEBYTECODE="1", PYTHONIOENCODING="cp1252:strict", PYTHONUTF8="0",
                       PYTHONPATH=os.pathsep.join([str(TOOLS), str(REPO / "desktop/tools")]))
            for attempt in [1, 2]:
                api = root / str(attempt) / "api"
                media = root / str(attempt) / "media"
                tests = root / str(attempt) / "tests"
                for script, args in [("extract-upstream-api.py", ["--output", str(api)]),
                    ("extract-upstream-media.py", ["--output", str(media), "--test-output", str(tests)])]:
                    result = subprocess.run([sys.executable, "-B", str(TOOLS / script), "--repo", str(REPO), *args],
                        capture_output=True, env=env, timeout=30)
                    self.assertEqual(0, result.returncode, result.stderr.decode("utf8", errors="replace"))
            def pins(folder):
                return {p.relative_to(folder).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
                        for p in folder.rglob("*") if p.is_file()}
            self.assertEqual(pins(root / "1"), pins(root / "2"))


if __name__ == "__main__":
    unittest.main()
