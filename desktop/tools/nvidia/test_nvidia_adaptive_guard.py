"""Source and data checks for the adaptive guard and the machine-local failure memory (design 9, 15 slice S6, 16).

The checks read the GUARD-CONSTANTS, GUARD-TAXONOMY and HEALTH-CONSTANTS blocks as data and pin the shape of the guard
and of the store: pure, injected clock, never the presentation tracker, a machine-local file that the plugin store and
the backup never touch, an atomic write on a daemon thread, a tolerant parser. They never re-implement the Kotlin logic:
the JVM tests are the drift detector."""
import re
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import nvidia_core_source as source  # noqa: E402

GUARD = source.KN / "DesktopNvidiaAdaptiveGuard.kt"
STORE = source.KN / "DesktopNvidiaHealthStore.kt"
MONITOR = source.KN / "DesktopNvidiaCapabilityMonitor.kt"
BACKUP = source.K / "backup/DesktopBackupArchive.kt"
PLUGIN_STORE = source.K / "plugins/DesktopPluginStore.kt"
CONFIGURATION = source.K / "plugins/DesktopVideoEnhancementConfiguration.kt"
TESTS = source.ST / "kotlin/com/bilipai/desktop/nvidia"

DAY = 86_400_000


def constants(block):
    """Name to value of every 'internal const val NAME: Type = value' in the block."""
    result = {}
    for name, value in re.findall(r"internal const val (\w+): \w+ = ([\d_.]+L?)", block):
        result[name] = source.kotlin_value(value)
    return result


class GuardConstantsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text = source.read(GUARD)
        cls.block = source.marked_block(cls.text, "GUARD-CONSTANTS")
        cls.values = constants(cls.block)

    def test_the_documented_numbers(self):
        values = self.values
        self.assertEqual(5_000, values["GUARD_HOLD_MILLIS"])
        self.assertEqual(3, values["GUARD_MAX_TRANSITIONS"])
        self.assertEqual(3, values["GUARD_BUSY_MAX_RETRIES"])
        self.assertEqual(10_000, values["GUARD_OVERLOAD_WINDOW_MILLIS"])
        self.assertEqual(10_000, values["GUARD_OVERLOAD_SETTLE_MILLIS"])
        self.assertEqual(6, values["GUARD_OVERLOAD_MIN_DROPS"])
        self.assertEqual(0.06, values["GUARD_OVERLOAD_DROP_FRACTION"])
        self.assertEqual(0.25, values["GUARD_OVERLOAD_AVSYNC_SECONDS"])
        self.assertEqual(2, values["GUARD_OVERLOAD_BAD_WINDOWS"])
        self.assertEqual(2, values["GUARD_OVERLOAD_MAX_STEPS"])
        self.assertEqual(0.30, values["GUARD_OVERLOAD_MIN_IMPROVEMENT"])
        self.assertEqual(30_000, values["GUARD_HEALTHY_CLEAR_MILLIS"])
        self.assertEqual(DAY, values["GUARD_REMEMBER_MILLIS"])
        self.assertEqual(7 * DAY, values["GUARD_REMEMBER_MAX_MILLIS"])
        self.assertEqual(7 * DAY, values["GUARD_LEARNED_TTL_MILLIS"])
        self.assertEqual(14 * DAY, values["GUARD_ENTRY_TTL_MILLIS"])

    def test_the_busy_delays_are_one_two_and_four_seconds_and_match_the_retry_count(self):
        match = re.search(r"GUARD_BUSY_RETRY_DELAYS_MILLIS: List<Long> = listOf\(([^)]*)\)", self.block)
        self.assertIsNotNone(match)
        delays = [source.kotlin_value(item) for item in match.group(1).split(",")]
        self.assertEqual([1_000, 2_000, 4_000], delays)
        self.assertEqual(self.values["GUARD_BUSY_MAX_RETRIES"], len(delays))
        # Every retry fits inside the step hold, so a retry can never be mistaken for a step.
        self.assertTrue(all(delay < self.values["GUARD_HOLD_MILLIS"] for delay in delays))

    def test_the_constants_keep_their_documented_relations(self):
        values = self.values
        self.assertGreaterEqual(values["GUARD_OVERLOAD_SETTLE_MILLIS"], 7_300, "cold NGX creation took 7.3 s")
        self.assertGreaterEqual(values["GUARD_OVERLOAD_WINDOW_MILLIS"], values["GUARD_SAMPLE_GAP_MILLIS"])
        self.assertLessEqual(values["GUARD_REMEMBER_MILLIS"], values["GUARD_REMEMBER_MAX_MILLIS"])
        self.assertLessEqual(values["GUARD_REMEMBER_MAX_MILLIS"], values["GUARD_ENTRY_TTL_MILLIS"])
        self.assertLessEqual(values["GUARD_LEARNED_TTL_MILLIS"], values["GUARD_ENTRY_TTL_MILLIS"])
        self.assertGreater(values["GUARD_HOLD_MILLIS"], 3_000, "longer than the busy quarantine between reconfigurations")
        self.assertTrue(0.0 < values["GUARD_OVERLOAD_MIN_IMPROVEMENT"] < 1.0)
        self.assertLessEqual(values["GUARD_MAX_TRANSITIONS"], 3)

    def test_the_block_declares_constants_only(self):
        for line in self.block.splitlines():
            stripped = line.strip()
            if not stripped or stripped.startswith("//") or stripped.startswith("/**") or stripped.startswith("*"):
                continue
            self.assertTrue(stripped.startswith("internal const val ") or stripped.startswith("internal val GUARD_BUSY_RETRY_DELAYS_MILLIS"),
                            stripped)


class TaxonomyTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text = source.read(GUARD)
        block = source.marked_block(cls.text, "GUARD-TAXONOMY")
        cls.rules = source.constructor_calls(block, "NvidiaFailureRule", "code")

    def test_every_native_code_from_one_to_eleven_has_a_rule_and_eleven_has_three_stages(self):
        codes = [rule["code"] for rule in self.rules]
        self.assertEqual(set(range(1, 12)), set(codes))
        stages = {rule["stage"] for rule in self.rules if rule["code"] == 11}
        self.assertEqual({'"bridge-unavailable"', '"process-bypass"', '"seek-reset-retired"'}, stages)
        for rule in self.rules:
            if rule["code"] != 11:
                self.assertIsNone(rule["stage"], rule)
        self.assertEqual(len(self.rules), len({(rule["code"], rule["stage"]) for rule in self.rules}), "no duplicate rule")

    def test_the_classes_follow_the_design_table(self):
        by_code = {}
        for rule in self.rules:
            by_code.setdefault(rule["code"], {})[rule["stage"]] = rule["failureClass"].split(".")[-1]
        expected = {1: "CONTRACT", 2: "ABI_MISMATCH", 3: "BUSY", 4: "STALE_OR_RESET", 5: "COLOR",
                    6: "DEVICE_OR_FEATURE", 7: "DEVICE_OR_FEATURE", 8: "DEVICE_OR_FEATURE", 9: "TIMEOUT",
                    10: "STALE_OR_RESET"}
        for code, name in expected.items():
            self.assertEqual({None: name}, by_code[code], code)
        self.assertEqual({'"bridge-unavailable"': "DEVICE_OR_FEATURE", '"process-bypass"': "TIMEOUT",
                          '"seek-reset-retired"': "STALE_OR_RESET"}, by_code[11])

    def test_the_stages_are_the_three_the_native_diagnostic_accepts(self):
        player = source.read(source.K / "player/PlayerNvidiaVideo.kt")
        accepted = set(re.findall(r'"([a-z-]+)"', player[player.index("init { require(stage in setOf("):][:140]))
        wired = {rule["stage"].strip('"') for rule in self.rules if rule["stage"]}
        self.assertEqual(accepted, wired)

    def test_the_failure_enum_covers_the_classes_the_decisions_handle(self):
        enum = re.search(r"internal enum class NvidiaFailureClass \{(.*?)\n\}", self.text, re.S)
        self.assertIsNotNone(enum)
        names = set(re.findall(r"^    ([A-Z_]+),?$", enum.group(1), re.M))
        self.assertEqual({"DEVICE_OR_FEATURE", "TIMEOUT", "BUSY", "COLOR", "CONTRACT", "ABI_MISMATCH", "STALE_OR_RESET", "UNKNOWN"}, names)
        classify = self.text[self.text.index("internal fun classifyNvidiaFailure("):]
        self.assertIn("return NvidiaFailureClass.UNKNOWN", classify[:600], "an unnamed code is unknown, never success")


class GuardShapeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text = source.read(GUARD)
        cls.code, cls.literals = source.lex_kotlin(cls.text)

    def test_the_file_is_pure_and_only_reads_the_clock_through_its_injected_function(self):
        self.assertTrue(self.text.startswith("package com.bilipai.desktop.nvidia\n"))
        imports = re.findall(r"^import ([\w.]+)", self.text, re.M)
        self.assertEqual(["kotlin.math.abs", "kotlin.math.max", "kotlin.math.min"], imports)
        for forbidden in ("Thread", "kotlinx", "com.sun.jna", "Native", "Files", "File(", "Path", "synchronized", "@Volatile",
                          "launch", "delay(", "Dispatchers", "StateFlow", "com.bilipai.desktop.player"):
            self.assertNotIn(forbidden, self.code, forbidden)
        self.assertEqual(1, self.code.count("System.nanoTime()"), "only the default of the injected clock")
        self.assertEqual(1, self.code.count("System."))
        self.assertIn("class DesktopNvidiaAdaptiveGuard(private val clock: () -> Long = { System.nanoTime() / 1_000_000L })", self.text)

    def test_the_guard_never_names_the_proof_machinery_or_writes_an_active_flag(self):
        for forbidden in ("VeyraPresentationTracker", "veyraReceipt", "MpvVeyraPresentation", "NvidiaVideoState",
                          "mutableNvidiaVideo", "hdrConversionActive", "NvidiaVideoOptions", "NvidiaVideoBackend",
                          source.SHADER_LIST_OPTION, "interpolation", "video-sync", "d3d11-flip", "target-colorspace-hint"):
            self.assertNotIn(forbidden, self.text, forbidden)
        self.assertIsNone(re.search(r"(?<![A-Za-z0-9_])active\s*=(?!=)", self.code))
        self.assertNotIn("proved", self.text)

    def test_the_guard_only_ever_lowers(self):
        # The one place the current rung changes is the commit, and it only takes a rung the decision chose.
        assignments = re.findall(r"(?<![A-Za-z0-9_.])rung = ([^\n]+)", self.code)
        self.assertEqual(["null", "target"], [item.strip() for item in assignments])
        # The learned ceiling is reduce-only.
        self.assertIn("existing in 1..3 -> min(existing, proposed)", self.text)
        self.assertNotIn("max(existing", self.text)

    def test_no_string_literal_is_user_visible_text(self):
        for literal in self.literals:
            self.assertIsNone(re.search(r"[一-鿿]", literal), literal)
        # Keys of the health identity and nothing else.
        self.assertTrue(all(len(literal) < 100 for literal in self.literals), self.literals)

    def test_every_decision_kind_and_memory_effect_exists_for_the_session_to_handle(self):
        decisions = re.search(r"internal sealed interface NvidiaGuardDecision \{(.*?)\n\}", self.text, re.S)
        self.assertIsNotNone(decisions)
        kinds = re.findall(r"^    (?:data object|data class) (\w+)", decisions.group(1), re.M)
        self.assertEqual(["NoChange", "Retry", "StepDown", "GiveUp"], kinds)
        effects = re.search(r"internal enum class NvidiaMemoryEffect \{([^}]*)\}", self.text)
        self.assertEqual(["NONE", "DEVICE_FAILURE", "REMEMBER_NOW", "EXTENDED_RANGE_DENIED"],
                         [item.strip() for item in effects.group(1).split(",")])
        reasons = re.search(r"internal enum class NvidiaGiveUpReason\(val overload: Boolean = false\) \{(.*?)\n\}", self.text, re.S)
        self.assertIsNotNone(reasons)
        overload = re.findall(r"(OVERLOAD_\w+)\(true\)", reasons.group(1))
        self.assertEqual(["OVERLOAD_NO_IMPROVEMENT", "OVERLOAD_EXHAUSTED", "OVERLOAD_NO_LOWER_RUNG"], overload)
        # Only the contract mismatch is remembered at once, and an overload never writes the memory.
        self.assertEqual(1, self.code.count("NvidiaMemoryEffect.REMEMBER_NOW"))
        for reason in overload:
            for call in re.findall(r"giveUp\(NvidiaGiveUpReason\.%s, ([\w.]+)\)" % reason, self.code):
                self.assertEqual("NvidiaMemoryEffect.NONE", call)

    def test_a_failed_token_is_handled_once_and_a_given_up_source_decides_nothing_more(self):
        start = self.text.index("fun onFailure(token: Long, code: Int, stage: String, nowMillis: Long = clock()): NvidiaGuardDecision {")
        body = " ".join(self.text[start:start + 700].split())
        self.assertIn("if (gaveUpReason != null || token == handledToken) return NvidiaGuardDecision.NoChange handledToken = token", body)

    def test_the_busy_retry_is_uncounted_and_the_color_failure_changes_nothing(self):
        busy = " ".join(self.text[self.text.index("private fun decideBusy("):self.text.index("private fun decideDevice(")].split())
        self.assertNotIn("transitions", busy)
        self.assertNotIn("lastStepAtMillis", busy)
        self.assertIn("pending = Pending(true, null, nowMillis + delay)", busy)
        # A colour failure decides nothing (NoChange, no rung, no memory) but marks the source, which the session then does not
        # request again (S6 fix). The mark is its own flag and not a give-up, so the colour line stays the colour line.
        flat = " ".join(self.text.split())
        self.assertIn("NvidiaFailureClass.COLOR -> { colorRejected = true NvidiaGuardDecision.NoChange }", flat)
        self.assertEqual(1, flat.count("colorRejected = true"))
        self.assertEqual(1, flat.count("private var colorRejected: Boolean = false"))
        self.assertEqual(1, flat.count("colorRejected = false"), "cleared with the rest of the per-source state, and only there")
        self.assertIn("val colorFailed: Boolean get() = colorRejected", flat)

    def test_the_extended_range_never_produces_a_remembered_failure(self):
        for name in ("private fun decideDevice(", "private fun decideTimeout("):
            body = " ".join(self.text[self.text.index(name):][:420].split())
            self.assertIn("if (inExtendedRange) return giveUp(NvidiaGiveUpReason.EXTENDED_RANGE, NvidiaMemoryEffect.EXTENDED_RANGE_DENIED)", body)
        tail = self.text[self.text.index("internal fun nvidiaHealthAfterExtendedRange("):]
        extended = " ".join(tail[:tail.index("\n}\n") + 3].split())
        self.assertNotIn("rememberedUntilMillis =", extended)
        self.assertNotIn("streak", extended)
        self.assertIn("extendedRangeDeniedUntilMillis = nowMillis + GUARD_LEARNED_TTL_MILLIS", extended)


class StoreShapeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text = source.read(STORE)
        cls.code, cls.literals = source.lex_kotlin(cls.text)
        cls.block = source.marked_block(cls.text, "HEALTH-CONSTANTS")
        cls.values = constants(cls.block)

    def test_the_documented_bounds(self):
        self.assertEqual(1, self.values["HEALTH_SCHEMA_VERSION"])
        self.assertEqual(8, self.values["HEALTH_MAX_ENTRIES"])
        self.assertEqual(16_384, self.values["HEALTH_MAX_BYTES"])
        self.assertEqual(5_000, self.values["HEALTH_WRITE_MIN_INTERVAL_MILLIS"])
        self.assertEqual("BiliPaiWindows", re.search(r'HEALTH_DIRECTORY_NAME: String = "([^"]+)"', self.block).group(1))
        self.assertEqual("gpu", re.search(r'HEALTH_SUBDIRECTORY_NAME: String = "([^"]+)"', self.block).group(1))
        self.assertEqual("nvidia-enhancement-health.json", re.search(r'HEALTH_FILE_NAME: String = "([^"]+)"', self.block).group(1))

    def test_the_path_derives_from_the_local_application_data_directory_only(self):
        machine = self.text[self.text.index("fun machineLocal(): DesktopNvidiaHealthStore {"):]
        machine = machine[:machine.index("        /** A store backed by this file.")]
        self.assertIn('System.getenv("LOCALAPPDATA")', machine)
        self.assertIn("Path.of(base, HEALTH_DIRECTORY_NAME, HEALTH_SUBDIRECTORY_NAME, HEALTH_FILE_NAME)", machine)
        self.assertIn("return inMemory()", machine)
        self.assertNotIn("java.io.tmpdir", self.text)
        self.assertNotIn("user.home", self.text)
        self.assertEqual(1, self.code.count('System.getenv("LOCALAPPDATA")'))

    def test_the_file_is_never_part_of_the_plugin_store_the_configuration_or_the_backup(self):
        for path in (BACKUP, PLUGIN_STORE, CONFIGURATION, source.K / "backup/DesktopBackupStore.kt",
                     source.K / "backup/DesktopBackupCoordinator.kt", source.K / "backup/DesktopAndroidSettingsBackup.kt"):
            text = source.read(path)
            for forbidden in ("nvidia-enhancement-health", "DesktopNvidiaHealthStore", "HEALTH_FILE_NAME", "NvidiaHealthEntry",
                              "healthStore", "BiliPaiWindows/gpu", "gpu\\\\"):
                self.assertNotIn(forbidden, text, "%s: %s" % (path.name, forbidden))
        # The file name has one owner in all main sources.
        owners = [path.name for path in source.K.rglob("*.kt") if "nvidia-enhancement-health" in path.read_text(encoding="utf-8")]
        self.assertEqual([STORE.name], owners)

    def test_the_write_is_atomic_flushed_off_thread_and_rate_limited(self):
        self.assertIn("Files.createTempFile(parent, \"nvidia-health-\", \".tmp\")", self.text)
        self.assertIn("channel.force(true)", self.text)
        self.assertIn("StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING", self.text)
        self.assertIn("catch (_: AtomicMoveNotSupportedException)", self.text)
        self.assertIn("finally {\n                Files.deleteIfExists(temporary)", self.text)
        self.assertIn('Thread(task, "BiliPai-gpu-health")', self.text)
        self.assertIn("thread.isDaemon = true", self.text)
        self.assertIn("pool.schedule(Runnable { runScheduledWrite() }, wait, TimeUnit.MILLISECONDS)", self.text)
        # S6 fix: the wait is clamped to one interval, so a wall clock that went backwards cannot postpone the write (and, with
        # it, every later change, which does not schedule while a write waits) by the size of the jump.
        self.assertIn("val wait = if (last == null) 0L else (last + writeIntervalMillis - clock()).coerceIn(0L, maxOf(0L, writeIntervalMillis))",
                      self.text)
        self.assertNotIn("maxOf(0L, last + writeIntervalMillis - clock())", self.text)
        # The state lock is never held while the disk is written; a flush waits for a running write.
        persist = " ".join(self.text[self.text.index("private fun persistNow(): Boolean {"):][:1400].split())
        self.assertLess(persist.index("synchronized(writeLock)"), persist.index("synchronized(lock)"))
        self.assertLess(persist.index("synchronized(lock)"), persist.index("writeAtomically(path,"))
        snapshot_end = persist.index("} catch (_: Throwable) { null }")
        self.assertLess(snapshot_end, persist.index("writeAtomically(path,"))
        # The in-memory store has no executor, so it never starts a thread.
        self.assertIn("DesktopNvidiaHealthStore(null, clock, HEALTH_WRITE_MIN_INTERVAL_MILLIS, null)", self.text)

    def test_a_change_that_meets_a_running_write_re_arms_it_and_a_failed_write_is_retried_a_bounded_number_of_times(self):
        run = " ".join(self.text[self.text.index("private fun runScheduledWrite() {"):].split())
        run = run[:run.index("/** The snapshot text")]
        # The task writes first, then clears the handle under the state lock, and only then looks at the dirty flag.
        self.assertLess(run.index("persistNow()"), run.index("synchronized(lock)"))
        self.assertLess(run.index("scheduled = null"), run.index("if (dirty && failedWrites <= HEALTH_WRITE_MAX_RETRIES) markDirtyLocked()"))
        self.assertEqual(2, run.count("catch (_: Throwable)"), "neither the write nor the re-arm can end the writer thread")
        # The handle is cleared in this task and nowhere else, so a waiting write is never replaced.
        self.assertEqual(["try {", "null"], [item.strip() for item in re.findall(r"(?<![A-Za-z0-9_.])scheduled = ([^\n]+)", self.code)])
        persist = " ".join(self.text[self.text.index("private fun persistNow(): Boolean {"):].split())
        persist = persist[:persist.index("companion object")]
        self.assertIn("synchronized(lock) { failedWrites = 0 }", persist)
        self.assertIn("failedWrites++ dirty = true", persist)
        self.assertEqual(3, self.values["HEALTH_WRITE_MAX_RETRIES"])

    def test_the_file_is_read_ahead_on_the_probe_thread_and_never_on_the_interface_thread_by_the_monitor(self):
        # A read of at most 16 KB happens once, lazily; the monitor warms it on its own probe thread after the verdict is out.
        warm = " ".join(self.text[self.text.index("fun warmUp() {"):].split())
        warm = warm[:warm.index("/** Writes the pending snapshot")]
        self.assertIn("synchronized(lock) { ensureLoadedLocked() }", warm)
        self.assertIn("catch (_: Throwable)", warm)
        monitor = source.read(MONITOR)
        worker = monitor[monitor.index("private fun startWorker("):monitor.index("private fun publish(")]
        self.assertLess(worker.index("run.result.complete(verdict)"), worker.index("healthStore.warmUp()"))
        self.assertEqual(1, monitor.count("healthStore.warmUp()"))

    def test_nothing_in_the_store_throws_into_playback(self):
        # Every public entry point answers with a neutral value or does nothing on any failure.
        for name in ("fun view(key: String): NvidiaHealthView {", "fun clearKey(key: String) {",
                     "private fun mutate(key: String, transform:"):
            start = self.text.index(name)
            body = self.text[start:self.text.index("\n    }\n", start)]
            self.assertIn("catch (_: Throwable)", body, name)
        self.assertIn("fun snapshot(): List<NvidiaHealthEntry> = try {", self.text)
        parse = self.text[self.text.index("internal fun parseNvidiaHealthFile("):self.text.index("private fun parseNvidiaHealthEntry(")]
        self.assertIn("catch (_: Throwable) {\n        return emptyList()", parse)
        self.assertNotIn("throw ", self.code.replace('throw IllegalStateException("The health file has no directory.")', ""))

    def test_the_codec_writes_exactly_the_keys_the_parser_reads(self):
        encode =self.text[self.text.index("internal fun encodeNvidiaHealthFile("):self.text.index("internal fun parseNvidiaHealthFile(")]
        written = set(re.findall(r'\\"(\w+)\\":', encode))
        written.discard("version")
        written.discard("entries")
        parse = self.text[self.text.index("private fun parseNvidiaHealthEntry("):self.text.index("// ---- Store")]
        read = set(re.findall(r'number\("(\w+)"', parse)) | {"key"}
        self.assertEqual(read, written)
        self.assertEqual({"key", "streak", "remembered_until", "learned_quality", "learned_until", "failed_quality",
                          "failed_count", "extended_denied_until", "updated"}, written)
        self.assertIn('"{\\"version\\":"', encode)
        self.assertIn("HEALTH_SCHEMA_VERSION.toLong()", self.text)

    def test_the_store_is_pure_of_the_proof_machinery_and_the_forbidden_apis(self):
        for forbidden in ("VeyraPresentationTracker", "veyraReceipt", "NvidiaVideoState", "mutableNvidiaVideo",
                          "hdrConversionActive", source.SHADER_LIST_OPTION, "RegSetValue", "RegCreateKey", "ProcessBuilder",
                          "Runtime.getRuntime", "SetThreadPriority", "SetPriorityClass", "nvidia-smi"):
            self.assertNotIn(forbidden, self.text, forbidden)
        self.assertIsNone(re.search(r"(?<![A-Za-z0-9_])active\s*=(?!=)", self.code))
        self.assertNotIn("com.bilipai.desktop.player", self.text)

    def test_the_monitor_owns_the_one_file_backed_store_and_every_other_monitor_stays_in_process(self):
        monitor = source.read(MONITOR)
        self.assertIn("val healthStore: DesktopNvidiaHealthStore = DesktopNvidiaHealthStore.inMemory(),", monitor)
        self.assertEqual(1, monitor.count("DesktopNvidiaHealthStore.machineLocal()"))
        process = monitor[monitor.index("private val processMonitor: DesktopNvidiaCapabilityMonitor by lazy {"):]
        enabled, disabled = process.split("} else {", 1)
        self.assertIn("healthStore = DesktopNvidiaHealthStore.machineLocal()", enabled)
        self.assertNotIn("healthStore", disabled)
        self.assertEqual(1, len(re.findall(r"DesktopNvidiaHealthStore\.machineLocal\(\)", "".join(
            path.read_text(encoding="utf-8") for path in source.K.rglob("*.kt")))))


class KotlinTestFilesTest(unittest.TestCase):
    def test_the_slice_tests_exist_use_kotlin_test_and_are_lf_only(self):
        for name in ("DesktopNvidiaAdaptiveGuardTest.kt", "DesktopNvidiaHealthStoreTest.kt"):
            path = TESTS / name
            source.assert_lf_only(self, path)
            text = source.read(path)
            self.assertTrue(text.startswith("package com.bilipai.desktop.nvidia\n"), name)
            self.assertIn("import kotlin.test.Test", text, name)
            self.assertNotIn("org.junit.Test", text, name)
            self.assertGreaterEqual(len(re.findall(r"@Test fun ", text)), 10, name)

    def test_the_guard_test_covers_every_taxonomy_row_the_hold_the_cap_and_the_overload_rules(self):
        text = (TESTS / "DesktopNvidiaAdaptiveGuardTest.kt").read_text(encoding="utf-8")
        for needle in ("everyNativeCodeFromOneToElevenIsClassified", "aBusyFailureRetriesTheSameConfigurationAfterOneTwoAndFourSecondsUncounted",
                       "aDeviceFailureWithHdrDropsHdrFirstAtTheSameQualityAndTheSecondFailureGivesUp",
                       "timeoutStepsWalkTheWholeLadderAndAtMostThreeStepsAreTaken", "aTimeoutTakesTheNextRungInOrderAndTheHoldSpacesTheSteps",
                       "aFailureBeyondTheSuperResolutionBoxIsContainedAndNeverRemembered",
                       "afterAnOverloadStepTheNextWindowMustShowThirtyPercentLessLoadOrTheSourceIsBypassed",
                       "atMostTwoOverloadStepsAreTakenThenTheSourceIsBypassed",
                       "aPauseASeekACacheStallAnotherSpeedOrUnreadableCountersNeverCountAsOverload",
                       "nothingIsJudgedDuringTheTenSecondsAfterAConfigurationChange",
                       "anEntryFromTheFutureOrOlderThanFourteenDaysIsExpired",
                       "theKeyIsSixteenLowercaseHexDigitsAndMatchesTheDocumentedDigest",
                       "aColourFailureMarksTheSourceSoItIsNotRequestedAgainUntilANewSourceOrAFreshStart"):
            self.assertIn(needle, text, needle)

    def test_the_store_test_covers_corrupt_files_atomic_writes_bounds_and_the_clock(self):
        text = (TESTS / "DesktopNvidiaHealthStoreTest.kt").read_text(encoding="utf-8")
        for needle in ("theParserNeverThrowsAndAnythingUnreadableIsAnEmptyMemory", "aCorruptOrOversizedFileIsAnEmptyMemory",
                       "theFileIsWrittenAtomicallyAndAFreshStoreReadsItBack", "theLeastRecentlyUpdatedEntryGoesFirst",
                       "aWriteThatCannotHappenNeverThrows", "writesAreCoalescedOffThreadAndAtMostOneRunsPerInterval",
                       "aSkewedOrAdvancedClockExpiresEntriesWithoutAnError", "aFileOfAnotherMachineWithAForeignKey",
                       "aClockThatStepsBackAfterAWriteDoesNotPostponeTheNextWriteBeyondOneInterval",
                       "aChangeThatMeetsARunningWriteIsWrittenByTheWriterWithoutAFlush",
                       "aFailedWriteIsTriedAgainByItselfAndSucceedsOnceTheDirectoryCanBeCreated"):
            self.assertIn(needle, text, needle)


if __name__ == "__main__":
    unittest.main()
