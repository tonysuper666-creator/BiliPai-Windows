"""Fixed-source repost coin propagation on real existing source producers; no API/JVM."""
import importlib.util
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
        for path, text, origin in coin.direct_outputs():
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
        expected = {"detail-full-units": {coin.VM, coin.USECASE, coin.PROTOCOL, coin.TRIPLE},
                    "state-core": {coin.SEED}, "detail-holder": {coin.COMMON}, "fullscreen-pager": {coin.PAGER}}
        for producer, paths in expected.items():
            output = self.output / producer
            self.assertEqual({Path(path).name + ".repost-coin-proof.json" for path in paths},
                             {p.name for p in coin.wide(output).glob("*.repost-coin-proof.json")})
            for path in paths:
                proof = json.loads(read(output / (Path(path).name + ".repost-coin-proof.json")))
                after = read(output / path); before = coin.undo(after, proof)
                self.assertEqual((after, proof), coin.apply(path, before))
                self.assertTrue(proof["fullPreviousOwnedInverse"])
                if path in {coin.VM, coin.PROTOCOL}:
                    for unchanged in ("ensureActive()", "CancellationException"):
                        self.assertEqual(before.count(unchanged), after.count(unchanged))
        vm = read(self.output / "detail-full-units" / coin.VM)
        self.assertIn("isInWatchLater = if (VideoEngagementField.WATCH_LATER in locallyModifiedFields) current.isInWatchLater else seed.isInWatchLater,", vm)
        self.assertIn("if (count !in 1..(state.coinLimit - state.coinCount).coerceIn(0, state.coinLimit)) return", vm)
        protocol = read(self.output / "detail-full-units" / coin.PROTOCOL)
        self.assertIn('34005 -> Result.failure(Exception("已投满该视频的硬币额度"))', protocol)
        self.assertNotIn("已投满2个硬币", protocol)
        self.assertEqual(1, protocol.count("api.coinVideo("))
        self.assertEqual(1, protocol.count("val coinResult = coinVideo(aid, coinCount, true)"))
        self.assertIn("actions.doTripleAction(targetAid, state.coinLimit)", vm)
        self.assertIn("attemptedCoinCount = state.coinLimit", vm)

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
        proof = json.loads(read(output / "VideoEngagementViewModel.kt.repost-coin-proof.json"))
        after = read(output / coin.VM); before = coin.undo(after, proof)
        with self.assertRaises(AssertionError): coin.apply(coin.VM, before.replace("actions.doTripleAction(targetAid)", "removedAction(targetAid)", 1))
        with self.assertRaises(AssertionError): coin.undo(after + "\n", proof)


if __name__ == "__main__": unittest.main()
