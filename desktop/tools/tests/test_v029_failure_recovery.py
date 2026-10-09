"""Fresh generator consumption and raw/inverse contracts; no JVM or media."""
from pathlib import Path
import contextlib
import hashlib
import importlib.util
import io
import json
import shutil
import sys
import tempfile
import unittest
from unittest import mock

TOOLS = Path(__file__).resolve().parents[1]
REPO = TOOLS.parents[1]
sys.path.insert(0, str(TOOLS))
import v029_failure_recovery as recovery


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    result = importlib.util.module_from_spec(spec)
    sys.modules[name] = result
    spec.loader.exec_module(result)
    return result


def restore(body, edits):
    for row in reversed(edits):
        index = row["offset"]
        assert body[index:index + len(row["after"])] == row["after"]
        body = body[:index] + row["before"] + body[index + len(row["after"]):]
    return body


class FailureRecoveryGeneratorTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.private = tempfile.TemporaryDirectory(prefix="bp-recovery-contract-")
        cls.root = Path(cls.private.name)
        cls.owner = module("recovery_contract_owner", TOOLS / "extract-upstream-video-full-owner.py")
        cls.core = module("recovery_contract_core", TOOLS / "extract-upstream-video-state-core.py")
        with contextlib.redirect_stdout(io.StringIO()):
            cls.owner_rows = cls.owner.generate(REPO, cls.root / "owner", True)
            cls.core.generate(REPO, cls.root / "core", True)
            with mock.patch.object(cls.core, "_typed_failure_usecase", lambda body, audit: body), \
                 mock.patch.object(cls.core, "_typed_failure_protocol", lambda body, audit: body):
                cls.core.generate(REPO, cls.root / "core-before", True)
        cls.audit = json.loads((cls.root / "core/original-video-state-core-selection.json").read_text(encoding="utf-8"))

    @classmethod
    def tearDownClass(cls):
        cls.private.cleanup()

    def test_fresh_whole_vm_inverse_and_actual_native_observer_consumption(self):
        row = next(row for row in self.owner_rows if row["path"] == recovery.VM)
        actual = (self.root / "owner" / recovery.VM).read_text(encoding="utf-8")
        self.assertEqual(row["sha256LF"], hashlib.sha256(actual.encode()).hexdigest())
        original_actual = restore(actual, row["postSameSendInverseEdits"])
        self.assertEqual(row["postSameSendBeforeSha256LF"], hashlib.sha256(original_actual.encode()).hexdigest())
        original_actual = restore(original_actual, row["sameSendExpectedSourceInverseEdits"])
        self.assertEqual(row["sameSendExpectedSourceBeforeSha256LF"], hashlib.sha256(original_actual.encode()).hexdigest())
        original_actual = restore(original_actual, row["postRecoveryInverseEdits"])
        self.assertEqual(row["failureRecoveryAfterSha256LF"], hashlib.sha256(original_actual.encode()).hexdigest())
        original_baseline = restore(original_actual, row["failureRecoveryInverseEdits"])
        self.assertEqual(row["failureRecoveryBeforeSha256LF"], hashlib.sha256(original_baseline.encode()).hexdigest())
        replay = []
        self.assertEqual(original_actual, self.owner._failure_recovery_delta(recovery.VM, original_baseline, replay))
        self.assertEqual(row["failureRecoveryInverseEdits"], replay)
        self.assertIn("desktopOriginalNativeFailureCurrent(player.nativePlayer, accepted, failure)", actual)
        self.assertNotIn("currentSourceSnapshot() == accepted.nativeSource", actual)
        self.assertEqual(1, actual.count("observeDesktopNativeFailure(error)"))
        self.assertEqual(3, actual.count("observeDesktopLoadFailure(requestToken, desktopFailure,"))
        self.assertIn("desktopExplicitStartPositionMs = if (desktopRecovering) fallbackResumePositionMs else null", actual)
        self.assertIn("if (!desktopRecovering) PlaybackCooldownManager.clearForVideo(bvid)", actual)
        self.assertIn("desktopPlaybackRecovery.ready()", actual)
        self.assertIn("desktopRecovering = desktopFailure != null", actual)

    def test_fresh_usecase_alias_consumes_three_classifiers_and_retry_with_complete_inverse(self):
        for filename, path in [
            ("DesktopOriginalVideoPlaybackUseCase.kt", "com/android/purebilibili/feature/video/usecase/DesktopOriginalVideoPlaybackUseCase.kt"),
            ("DesktopOriginalVideoLoadProtocol.kt", "com/android/purebilibili/data/repository/DesktopOriginalVideoLoadProtocol.kt"),
        ]:
            actual = (self.root / "core" / path).read_text(encoding="utf-8")
            baseline = (self.root / "core-before" / path).read_text(encoding="utf-8")
            self.assertEqual(baseline, restore(actual, self.audit["audits"][filename + ".failure-recovery-inverse.json"]))
            if "UseCase" in filename:
                self.assertEqual(3, actual.count("desktopOriginalVideoLoadError(e)"))
                self.assertEqual(1, actual.count("desktopOriginalVideoLoadCanRetry(e)"))
                self.assertNotIn("desktopWindowsVideoLoadError(e)", actual)
                self.assertEqual(baseline.count("catch (e: kotlinx.coroutines.CancellationException)"),
                                 actual.count("catch (e: kotlinx.coroutines.CancellationException)"))
                self.assertGreaterEqual(actual.count("catch (e: kotlinx.coroutines.CancellationException)"), 1)
            else:
                self.assertIn('throw com.bilipai.desktop.data.BiliApiException(viewResp.code, "视频请求失败")', actual)
                self.assertIn("listOf(-101, -404, -403, -10403, -62002)", actual)

    def test_premium_final_admission_and_cancellation_do_not_retarget_latest_source(self):
        actual = (self.root / "owner" / recovery.VM).read_text(encoding="utf-8")
        premium = actual[actual.index("    internal fun fallbackFromPremiumAudioPlaybackError("):actual.index("    //  相互作用")]
        self.assertIn("catch (cancelled: CancellationException)", premium)
        self.assertIn("throw cancelled", premium)
        self.assertEqual(2, premium.count("admitRecoveryAction { player.nativePlayer.setPaused(true) }"))
        self.assertIn("desktopPremiumFallbackIdentity === desktopFallbackIdentity", premium)
        start = actual.index("    private suspend fun refreshPlaybackAudioForSpeedCompatibility(")
        refresh = actual[start:actual.index("    //  SponsorBlock", start)]
        self.assertIn("if (!desktopRecovering) armPlaybackCdnFallback", refresh)
        self.assertIn("if (desktopRecovering) environment.invocations.admitRecoveryAction(publish) else publish()", refresh)
        self.assertLess(refresh.index("resolvePlaybackCdnCandidateSelection("), refresh.index("val publish: () -> Unit"))

    def test_original_neutral_declarations_and_all_eight_original_policy_tests_are_preserved(self):
        sources = recovery.verified_sources(REPO)
        raw = sources["SharedPlaybackSession.kt"]
        declarations = raw[raw.index("enum class PlaybackStatus"):raw.index("data class SharedPlaybackState(")]
        generated = recovery.metadata_source(REPO)
        self.assertTrue(generated.endswith(declarations))
        self.assertNotIn("ExoPlayer", generated)
        edits = []
        body = recovery.original_policy_test_source(REPO, edits)
        self.assertEqual(sources["PlayerErrorRecoveryPolicyTest.kt"], restore(body, edits))
        self.assertEqual(8, body.count("@Test"))
        checked = REPO / "desktop/src/test/kotlin/com/android/purebilibili/feature/video/state/DesktopOriginalV029RecoveryPolicyTest.kt"
        self.assertEqual(body, checked.read_text(encoding="utf-8"))

    def test_changed_fixed_raw_or_manifest_cannot_generate_metadata(self):
        with tempfile.TemporaryDirectory(prefix="bp-recovery-raw-") as root:
            target = Path(root) / "desktop" / recovery.SLICE
            shutil.copytree(REPO / "desktop" / recovery.SLICE, target)
            path = target / "SharedPlaybackSession.kt"
            raw = path.read_bytes()
            path.write_bytes(raw[:-1] + bytes([raw[-1] ^ 1]))
            with self.assertRaises(AssertionError):
                recovery.metadata_source(root)
            path.write_bytes(raw)
            manifest = json.loads((target / "manifest.json").read_text(encoding="utf-8"))
            manifest["commit"] = "0" * 40
            (target / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
            with self.assertRaises(AssertionError):
                recovery.metadata_source(root)


if __name__ == "__main__":
    unittest.main()
