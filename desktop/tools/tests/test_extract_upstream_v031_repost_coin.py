"""Fixed-source repost coin propagation on real existing source producers; no API/JVM."""
import importlib.util
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

REPO = Path(__file__).resolve().parents[3]
TOOLS = REPO / "desktop/tools"
sys.path.insert(0, str(TOOLS))
import v031_repost_coin as coin
import v046_portrait_pager as portrait_pager


def load(name):
    spec = importlib.util.spec_from_file_location("repost_" + name, TOOLS / ("extract-upstream-video-" + name + ".py"))
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module); return module


def read(path):
    return coin.wide(path).read_bytes().decode("utf8").replace("\r\n", "\n")


class RepostCoinExtractionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="bilipai-repost-coin-")
        cls.addClassCleanup(cls.temp.cleanup)
        cls.output = Path(cls.temp.name)
        cls.producers = {}
        for name in ("detail-full-units", "state-core", "detail-holder", "fullscreen-pager"):
            cls.producers[name] = load(name)
            cls.producers[name].generate(REPO, cls.output / name, True)

    def test_complete_fixed_sources_are_verified_and_have_single_output_owners(self):
        manifest, fixed = coin.sources()
        self.assertEqual("10c08edadc07c56842402e27048855f4f3c95c38", manifest["upstreamCommit"])
        self.assertEqual(6, len(fixed))
        output = self.output / "detail-full-units"
        import v046_episode_selector as episode
        for path, text, origin in coin.direct_outputs():
            if path == episode.DETAIL_RESPONSE_OUTPUT:
                # lst-episode-selector-collection advances this one output to the whole v0.4.6 file (Page.firstFrame);
                # the advance asserts that `text` is exactly the v0.3.1 body this test already pins.
                self.assertEqual(episode.advance_video_detail_response(REPO, text), read(output / path), origin)
                continue
            self.assertEqual(text, read(output / path), origin)
        self.assertEqual(fixed["TripleActionVisualStatePolicy.kt"], read(output / coin.TRIPLE))
        rows = json.loads(read(output / "source-bindings.json"))["outputs"]
        self.assertEqual(len(rows), len({row["path"] for row in rows}))
        gradle = read(REPO / "desktop/build.gradle.kts")
        for identity in ("core-data/src/main/java/com/android/purebilibili/data/model/response/VideoDetailResponse.kt",
                         "app/src/main/java/com/android/purebilibili/feature/video/ui/components/CoinDialog.kt",
                         "app/src/main/java/com/android/purebilibili/feature/video/ui/feedback/TripleActionVisualStatePolicy.kt"):
            self.assertEqual(1, gradle.count('exclude(canonicalOriginalIdentity("' + identity + '"))'))

    def test_every_owned_delta_reverses_exactly_and_retains_protocol_guards(self):
        expected = {"detail-full-units": {coin.USECASE, coin.TRIPLE},
                    "state-core": {coin.SEED}, "detail-holder": {coin.COMMON}, "fullscreen-pager": {coin.PAGER}}
        for producer, paths in expected.items():
            output = self.output / producer
            self.assertEqual({Path(path).name + ".repost-coin-proof.json" for path in paths},
                             {p.name for p in coin.wide(output).glob("*.repost-coin-proof.json")})
            for path in paths:
                proof = json.loads(read(output / (Path(path).name + ".repost-coin-proof.json")))
                after = read(output / path)
                if producer == "fullscreen-pager" and path == coin.PAGER:
                    # plr-portrait-story-pager-repin is the newest layer of the pager: unwind its fourteen counted hunks first.
                    portrait = json.loads(read(output / "v046-portrait-pager-proofs/PortraitVideoPager.kt.json"))
                    after = portrait_pager.peel_pager(after, portrait)
                    # 38512320c comment selection and daff05d71 owner-follow rewrite the pager
                    # after the v035 story proof. Unwind those recorded proofs first.
                    follow = json.loads(read(output / "PortraitVideoPager.kt.v042-owner-follow-ui-proof.json"))
                    self.assertTrue(follow["wholePreviousInputInverseRecovered"])
                    self.assertEqual(follow["outputSHA256"], coin.sha(after))
                    for edit in reversed(follow["edits"]):
                        at = edit["offset"]
                        self.assertEqual(edit["after"], after[at:at + len(edit["after"])])
                        after = after[:at] + edit["before"] + after[at + len(edit["after"]):]
                    self.assertEqual(follow["inputSHA256"], coin.sha(after))
                    selection = json.loads(read(output / "v029-comment-selection-PortraitVideoPager.json"))
                    self.assertTrue(selection["exactCompleteInverse"])
                    self.assertEqual(selection["afterSha256LF"], coin.sha(after))
                    for edit in reversed(selection["inverseEdits"]):
                        at = edit["offset"]
                        self.assertEqual(edit["after"], after[at:at + len(edit["after"])])
                        after = after[:at] + edit["before"] + after[at + len(edit["after"]):]
                    self.assertEqual(selection["beforeSha256LF"], coin.sha(after))
                    later = json.loads(read(output / "PortraitVideoPager.kt.v035-story-source.json"))["recipe"]
                    self.assertEqual(later["afterSha256LF"], coin.sha(after))
                    data = after.encode()
                    for edit in reversed(later["edits"]):
                        self.assertEqual(edit["afterWholeSha256"], hashlib.sha256(data).hexdigest())
                        at = edit["offsetBytes"]; new = edit["after"].encode(); old = edit["before"].encode()
                        self.assertEqual(new, data[at:at + len(new)])
                        data = data[:at] + old + data[at + len(new):]
                        self.assertEqual(edit["beforeWholeSha256"], hashlib.sha256(data).hexdigest())
                    after = data.decode()
                    self.assertEqual(later["beforeSha256LF"], coin.sha(after))
                    later = json.loads(read(output / "PortraitVideoPager.kt.portrait-native-preparation-proof.json"))
                    self.assertEqual(later["afterSha256LF"], coin.sha(after))
                    for edit in reversed(later["indexedEdits"]):
                        at = edit["afterOffset"]
                        self.assertEqual(edit["after"], after[at:at + len(edit["after"])])
                        after = after[:at] + edit["before"] + after[at + len(edit["after"]):]
                    self.assertEqual(later["beforeSha256LF"], coin.sha(after))
                if producer == "state-core" and path == coin.SEED:
                    later = self.producers[producer].AUDITS["v037-original-known-state-seed-functions"]["wholePreviousRootInverse"]
                    self.assertEqual(later["afterSha256LF"], coin.sha(after))
                    for edit in reversed(later["indexedEdits"]):
                        at = edit["afterOffset"]
                        self.assertEqual(edit["after"], after[at:at + len(edit["after"])])
                        after = after[:at] + edit["before"] + after[at + len(edit["after"]):]
                    self.assertEqual(later["beforeSha256LF"], coin.sha(after))
                self.assertEqual(proof["afterSha256LF"], coin.sha(after), (producer, path))
                before = coin.undo(after, proof)
                self.assertEqual((after, proof), coin.apply(path, before))
                self.assertTrue(proof["fullPreviousOwnedInverse"])
                if path in {coin.VM, coin.PROTOCOL}:
                    for unchanged in ("ensureActive()", "CancellationException"):
                        self.assertEqual(before.count(unchanged), after.count(unchanged))
        vm = read(self.output / "detail-full-units" / coin.VM)
        latest = json.loads(read(self.output / "detail-full-units/v037-repost-contract-source-proof.json"))
        from v037_player_interaction import selected_source_identity
        identity = selected_source_identity(latest["originalPath"])
        self.assertEqual("8b47769d22c0d5bc88ae5248cee4aa04ff10d62c", latest["fixedCommit"])
        self.assertEqual(hashlib.sha256(identity["text"].encode()).hexdigest(), latest["originalSha256LF"])
        # daff05d71 owner-follow rewrites the VM after this repost-contract snapshot.
        engagement = json.loads(read(self.output / "detail-full-units/v042-owner-follow-projection-proof.json"))
        self.assertTrue(engagement["wholePreviousInputInverseRecovered"])
        self.assertEqual(engagement["outputSHA256"], hashlib.sha256(vm.encode()).hexdigest())
        for edit in reversed(engagement["edits"]):
            at = edit["offset"]
            self.assertEqual(edit["after"], vm[at:at + len(edit["after"])])
            vm = vm[:at] + edit["before"] + vm[at + len(edit["after"]):]
        self.assertEqual(engagement["inputSHA256"], hashlib.sha256(vm.encode()).hexdigest())
        self.assertEqual(hashlib.sha256(vm.encode()).hexdigest(), latest["generatedSha256LF"])
        self.assertTrue(latest["wholeLatestOriginalRepostContractAlreadySelected"])
        self.assertTrue(latest["previousSemanticDeltaNotReapplied"])
        self.assertIn("val coinLimit: Int get() = if (isRepost) 1 else 2", identity["text"])
        self.assertIn("isInWatchLater = if (VideoEngagementField.WATCH_LATER in locallyModifiedFields) current.isInWatchLater else seed.isInWatchLater,", vm)
        self.assertIn("count in 1..(current.coinLimit - current.coinCount).coerceIn(0, current.coinLimit)", vm)
        protocol = read(self.output / "detail-full-units" / coin.PROTOCOL)
        from v037_player_interaction import fixed_sources
        protocol_proof = json.loads(read(self.output / "detail-full-units/v037-original-interaction-protocol-proof.json"))
        raw = fixed_sources()["ActionRepository.kt"]
        # ea3b67f1d projects confirmed follow onto the pinned repository before v037 hashes the selected methods.
        confirmed = json.loads(read(self.output / "detail-full-units/v042-confirmed-follow-source-proof.json"))
        self.assertTrue(confirmed["wholePreviousInputInverseRecovered"])
        self.assertEqual(confirmed["previousSourceSHA256"], hashlib.sha256(raw.encode()).hexdigest())
        projected = raw
        for edit in confirmed["edits"]:
            at = edit["offset"]
            self.assertEqual(edit["before"], projected[at:at + len(edit["before"])])
            projected = projected[:at] + edit["after"] + projected[at + len(edit["before"]):]
        self.assertEqual(confirmed["projectedSourceSHA256"], hashlib.sha256(projected.encode()).hexdigest())
        interior = projected[projected.index("object ActionRepository {") + len("object ActionRepository {"):projected.rfind("}")]
        original_methods = self.producers["detail-full-units"].selected(interior, protocol_proof["originalDeclarations"])
        self.assertEqual(hashlib.sha256(original_methods.encode()).hexdigest(), protocol_proof["beforeSha256LF"])
        methods = original_methods
        for edit in reversed(protocol_proof["indexedEdits"]):
            at = edit["beforeOffset"]
            self.assertEqual(edit["before"], methods[at:at + len(edit["before"])])
            methods = methods[:at] + edit["after"] + methods[at + len(edit["before"]):]
        self.assertEqual(hashlib.sha256(methods.encode()).hexdigest(), protocol_proof["afterSha256LF"])
        self.assertEqual(1, protocol.count(methods))
        for edit in reversed(protocol_proof["indexedEdits"]):
            at = edit["afterOffset"]
            self.assertEqual(edit["after"], methods[at:at + len(edit["after"])])
            methods = methods[:at] + edit["before"] + methods[at + len(edit["after"]):]
        self.assertEqual(original_methods, methods)
        self.assertIn('34005 -> Result.failure(Exception("已投满该视频的硬币额度"))', protocol)
        self.assertNotIn("已投满2个硬币", protocol)
        self.assertEqual(1, protocol.count("api.coinVideo("))
        self.assertEqual(1, protocol.count("val coinResult = coinVideo(aid, coinCount, true)"))
        self.assertIn("actions.doTripleAction(targetAid, actionState.coinLimit)", vm)
        self.assertIn("attemptedCoinCount = actionState.coinLimit", vm)

    def test_existing_actual_member_gate_accepts_the_same_single_mutation_wrapper(self):
        spec = importlib.util.spec_from_file_location("repost_existing_member_gate", TOOLS / "verify-upstream-video-detail-full-units.py")
        verifier = importlib.util.module_from_spec(spec); spec.loader.exec_module(verifier)
        verifier.verify(REPO, self.output / "detail-full-units", self.output / "member-proof.json")
        fragment = read(self.output / "detail-full-units/video-operations-members.fragment")
        self.assertEqual(1, fragment.count("override suspend fun doTripleAction(aid:Long,coinCount:Int)=result { mutate { original.doTripleAction(aid,coinCount).getOrThrow() } }"))
        for producer, path in (("detail-holder", coin.COMMON), ("fullscreen-pager", coin.PAGER)):
            self.assertEqual(1, read(self.output / producer / path).count("maxCoins = engagementState.coinLimit"))
        self.assertIn("maxCoins=engagement.coinLimit", read(REPO / "desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalStoryRoot.kt"))

    def test_changed_manifest_owned_anchor_and_inverse_payload_are_rejected(self):
        with patch.object(coin, "MANIFEST_SHA256", "0" * 64):
            with self.assertRaises(AssertionError): coin.sources()
        output = self.output / "detail-full-units"
        proof = json.loads(read(output / "VideoInteractionUseCase.kt.repost-coin-proof.json"))
        after = read(output / coin.USECASE); before = coin.undo(after, proof)
        anchor = proof["edits"][0]["before"]
        with self.assertRaises(AssertionError): coin.apply(coin.USECASE, before.replace(anchor, "removedOwnedAnchor", 1))
        with self.assertRaises(AssertionError): coin.undo(after + "\n", proof)


if __name__ == "__main__": unittest.main()
