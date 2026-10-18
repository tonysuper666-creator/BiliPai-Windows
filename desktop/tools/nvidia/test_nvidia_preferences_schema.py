"""Source checks for the NVIDIA preferences schema and the settings migration (design sections 7.2, 10 and 16).

The checks read the Kotlin source as data. They never re-implement the migration: the JVM tests carry the
behaviour, and these checks pin the shape that makes the migration safe. The store is carried by backup and
restore and read by older builds, so every existing key and strict rule stays, the new profile version stays
lenient, and nothing is rewritten on load."""
import re
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import nvidia_core_source as source  # noqa: E402

CONFIGURATION = source.K / "plugins/DesktopVideoEnhancementConfiguration.kt"
PREFERENCES = source.K / "player/DesktopNvidiaVideoPreferences.kt"
BACKUP_ARCHIVE = source.K / "backup/DesktopBackupArchive.kt"
CONFIGURATION_TEST = source.ST / "kotlin/com/bilipai/desktop/plugins/DesktopVideoEnhancementConfigurationTest.kt"
HEALTH_STORE = source.KN / "DesktopNvidiaHealthStore.kt"

EXISTING_KEYS = {
    "NAMESPACE": "windows_video_enhancement",
    "ENABLED_KEY": "enabled",
    "MIGRATION_KEY": "migration_version",
    "QUALITY_KEY": "quality_level",
    "HDR_MODE_KEY": "hdr_mode",
    "SR_ENABLED_KEY": "sr_enabled",
    "INTENSITY_KEY": "effect_intensity_percent",
}

# The strict messages: a bad stored value must keep throwing exactly as before.
STRICT_MESSAGES = (
    "NVIDIA 设置版本无效", "NVIDIA 自动增强设置无效", "NVIDIA 处理质量设置无效", "NVIDIA HDR 设置无效",
    "NVIDIA 增强内容设置无效", "NVIDIA HDR 独立模式设置无效", "NVIDIA 效果强度设置无效",
)


def member(text, marker):
    """Source from the marker through the closing brace of its body; comments, strings and characters are skipped."""
    assert text.count(marker) == 1, marker
    start = text.index(marker)
    index = text.index("{", start)
    depth = 0
    length = len(text)
    while index < length:
        pair = text[index:index + 2]
        char = text[index]
        if pair == "//":
            while index < length and text[index] != "\n":
                index += 1
            continue
        if pair == "/*":
            index = text.index("*/", index + 2) + 2
            continue
        if char in "\"'":
            quote = char
            index += 1
            while text[index] != quote:
                index += 2 if text[index] == "\\" else 1
            index += 1
            continue
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return text[start:index + 1]
        index += 1
    raise AssertionError("unbalanced body for " + marker)


def expression_member(text, marker):
    """Source of an expression-bodied member: from the marker to the next member of the same class."""
    assert text.count(marker) == 1, marker
    start = text.index(marker)
    follow = re.search(r"\n    (?:private |internal |override )?(?:fun|val|var) ", text[start + len(marker):])
    assert follow is not None, marker
    return text[start:start + len(marker) + follow.start()]


class ConfigurationSchemaTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text = source.read(CONFIGURATION)
        cls.code, cls.literals = source.lex_kotlin(cls.text)

    def test_every_existing_key_constant_is_unchanged(self):
        for name, value in EXISTING_KEYS.items():
            self.assertEqual(1, len(re.findall(r'const val %s = "%s"' % (name, re.escape(value)), self.text)), name)

    def test_the_profile_version_constants_exist_and_the_migration_version_is_not_moved(self):
        self.assertIn('const val PROFILE_VERSION_KEY = "profile_version"', self.text)
        self.assertIn("const val PROFILE_VERSION_CURRENT = 2", self.text)
        # The strict validator still demands migration_version == 1 exactly.
        validated = member(self.text, "private fun validatedEnabled(snapshot: DesktopPreferenceSnapshot): Boolean")
        self.assertIn("version.intOrNull == 1", validated)
        self.assertIn("!version.isString", validated)
        self.assertEqual(1, len(re.findall(r"MIGRATION_KEY to JsonPrimitive\(1\)", self.text)))

    def test_the_profile_version_is_read_leniently_and_nowhere_else(self):
        body = member(self.text, "private fun profileVersion(snapshot: DesktopPreferenceSnapshot): Int")
        code, _ = source.lex_kotlin(body)
        for strict in ("check(", "checkNotNull(", "require(", "error(", "throw ", "!!"):
            self.assertNotIn(strict, code)
        self.assertGreaterEqual(code.count("return 1"), 3)
        # Anything that is not a whole number of at least 1 counts as version 1.
        self.assertIn("value.isString", code)
        self.assertIn("value.intOrNull ?: return 1", code)
        self.assertIn("version >= 1", code)
        # The key object is declared once and used only by that function.
        uses = re.findall(r"\[profileVersionKey\]", self.code)
        self.assertEqual(1, len(uses))
        self.assertEqual(1, len(re.findall(r"private val profileVersionKey = ", self.code)))

    def test_validated_preferences_reads_only_known_keys(self):
        known = {"migrationKey", "enabledKey", "qualityKey", "hdrModeKey", "srEnabledKey", "intensityKey", "profileVersionKey"}
        used = set(re.findall(r"(?:snapshot|fresh)\[(\w+)\]", self.code))
        self.assertTrue(used <= known, used - known)
        declared = set(re.findall(r"private val (\w+Key) = DesktopPreferenceKey\(", self.code))
        self.assertEqual(known, declared)
        names = re.findall(r"DesktopPreferenceKey\((\w+)\)", self.code)
        self.assertEqual(7, len(names))
        self.assertEqual(set(EXISTING_KEYS) - {"NAMESPACE"} | {"PROFILE_VERSION_KEY"}, set(names))

    def test_the_strict_rules_and_their_messages_are_unchanged(self):
        for message in STRICT_MESSAGES:
            self.assertEqual(1, self.text.count('"%s"' % message), message)
        body = member(self.text, "private fun validatedPreferences(snapshot: DesktopPreferenceSnapshot): DesktopNvidiaVideoPreferences")
        for expected in ("check(srEnabled || hdrMode == DesktopNvidiaVideoHdrMode.AUTO)",
                         "checkNotNull(DesktopNvidiaVideoQuality.entries.singleOrNull { it.nativeLevel == level })",
                         "checkNotNull(DesktopNvidiaVideoIntensity.entries.singleOrNull { it.nativePercent == percent })"):
            self.assertIn(expected.replace(" ", ""), body.replace(" ", "").replace("\n", ""))
        # The profile version never appears in a check: it can neither throw nor disable the feature.
        for line in body.splitlines():
            if "check(" in line or "checkNotNull(" in line:
                self.assertNotIn("profile", line.lower())

    def test_the_fresh_seed_carries_automatic_hdr_and_the_profile_version(self):
        self.assertEqual(1, len(re.findall(r"internal const val SEED_FRESH_STORE_HDR_AUTO: Boolean = true\b", self.code)))
        ensure = member(self.text, "private suspend fun ensureLoaded()")
        self.assertIn("if (SEED_FRESH_STORE_HDR_AUTO) DesktopNvidiaVideoHdrMode.AUTO else DesktopNvidiaVideoHdrMode.OFF", ensure)
        self.assertIn("DesktopNvidiaVideoPreferences(enabled = true,", ensure)
        saved = member(self.text, "private fun savedValues(preferences: DesktopNvidiaVideoPreferences)")
        self.assertIn("PROFILE_VERSION_KEY to JsonPrimitive(PROFILE_VERSION_CURRENT)", saved)
        for key in ("ENABLED_KEY", "QUALITY_KEY", "HDR_MODE_KEY", "SR_ENABLED_KEY", "INTENSITY_KEY"):
            self.assertIn(key + " to JsonPrimitive(", saved)
        self.assertEqual(1, len(re.findall(r"savedValues\(", ensure)))

    def test_the_effective_hdr_rule_is_guarded_by_its_switch_and_is_in_memory_only(self):
        self.assertEqual(1, len(re.findall(r"internal const val EFFECTIVE_HDR_AUTO_FOR_UNTOUCHED_SEED: Boolean = true\b", self.code)))
        body = member(self.text, "private fun validatedPreferences(snapshot: DesktopPreferenceSnapshot): DesktopNvidiaVideoPreferences")
        self.assertIn("EFFECTIVE_HDR_AUTO_FOR_UNTOUCHED_SEED && untouchedOldSeed(snapshot, stored)", body)
        self.assertIn("stored.copy(hdrMode = DesktopNvidiaVideoHdrMode.AUTO)", body)
        signature = expression_member(self.text, "private fun untouchedOldSeed(snapshot: DesktopPreferenceSnapshot, stored: DesktopNvidiaVideoPreferences): Boolean")
        code, _ = source.lex_kotlin(signature)
        compact = "".join(code.split())
        for expected in ("profileVersion(snapshot)<PROFILE_VERSION_CURRENT", "stored.quality==DesktopNvidiaVideoQuality.HIGHEST",
                         "stored.hdrMode==DesktopNvidiaVideoHdrMode.OFF", "stored.srEnabled",
                         "stored.intensity==DesktopNvidiaVideoIntensity.FULL"):
            self.assertIn(expected, compact)
        # The switch alone never makes a store look touched, and the rule never writes anything.
        self.assertNotIn("enabled", re.sub(r"srEnabled", "", code.replace("untouchedOldSeed", "")))
        self.assertNotIn("update(", code)
        self.assertNotIn("savedValues(", code)

    def test_loading_never_rewrites_the_store_and_the_switch_is_changed_only_by_its_setter(self):
        ensure = member(self.text, "private suspend fun ensureLoaded()")
        # An existing store is validated and left alone; only the first migration writes.
        self.assertIn("validatedPreferences(fresh)\n                        emptyMap()", ensure)
        self.assertEqual(1, len(re.findall(r"updateFromSnapshot\(NAMESPACE\)", ensure)))
        self.assertEqual(1, len(re.findall(r"copy\(enabled = ", self.code)))
        setter = re.search(r"fun setAutomaticEnabled\(enabled: Boolean\): Deferred<Unit> = setPreference \{ it\.copy\(enabled = enabled\) \}", self.code)
        self.assertIsNotNone(setter)
        # No second place writes the enabled key: only savedValues does, from the preferences it is given.
        self.assertEqual(1, len(re.findall(r"(?<![A-Z_])ENABLED_KEY to JsonPrimitive\(", self.code)))

    def test_no_manual_mode_and_no_force_attempt_exist_anywhere_in_the_sources(self):
        for root in (source.REPO / "desktop/src/main", source.REPO / "desktop/src/test"):
            for path in sorted(root.rglob("*.kt")):
                text = path.read_text(encoding="utf-8")
                for forbidden in ("force_attempt", "forceAttempt", "FORCE_ATTEMPT", "仍要尝试", "恢复自动"):
                    self.assertNotIn(forbidden, text, "%s: %s" % (path.name, forbidden))
        preferences = source.read(PREFERENCES)
        self.assertNotIn("MANUAL", preferences)
        self.assertNotIn("enum class DesktopNvidiaMode", preferences)

    def test_the_store_is_not_pushed_into_the_backup_by_the_health_file(self):
        backup = source.read(BACKUP_ARCHIVE)
        for word in ("nvidia-enhancement-health", "BiliPaiWindows\\\\gpu", "LOCALAPPDATA", "health.json"):
            self.assertNotIn(word, backup, word)
        if HEALTH_STORE.exists():
            text = source.read(HEALTH_STORE)
            self.assertIn("LOCALAPPDATA", text)
            self.assertNotIn("plugin-settings", text)
            self.assertNotIn("DesktopPluginStore", text)

    def test_the_configuration_source_keeps_lf_only(self):
        for path in (CONFIGURATION, PREFERENCES, CONFIGURATION_TEST):
            self.assertNotIn(b"\r", path.read_bytes(), path.name)


class OptionsForSchemaTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text = source.read(PREFERENCES)

    def test_options_for_takes_the_plan_and_the_guard_with_defaults_so_existing_callers_compile(self):
        self.assertIn(
            "internal fun DesktopNvidiaVideoPreferences.optionsFor(decision: DesktopNvidiaVideoDecision,\n"
            "    backend: NvidiaVideoBackend, plan: NvidiaPlan? = null, guard: NvidiaGuardLimits = NvidiaGuardLimits.None): NvidiaVideoOptions {",
            self.text)
        self.assertIn("import com.bilipai.desktop.nvidia.NvidiaPlan", self.text)
        for caller in (source.K / "player/DesktopVideoEnhancementSession.kt",):
            self.assertIn("optionsFor(decision, backend, plan, guardLimits)", source.read(caller))
        # The tests keep calling the two-argument form.
        legacy_callers = source.read(source.ST / "kotlin/com/bilipai/desktop/player/PlayerNvidiaVideoTest.kt")
        self.assertIn("optionsFor(decision, NvidiaVideoBackend.DRIVER)", legacy_callers)

    def test_the_quality_is_the_lowest_of_ceiling_tier_and_guard_and_the_driver_route_cannot_change(self):
        body = member(self.text, "internal fun DesktopNvidiaVideoPreferences.optionsFor(")
        compact = "".join(body.split())
        self.assertIn("minOf(quality.nativeLevel,plan?.tierQuality?:4,guard.qualityCap).coerceIn(1,4)", compact)
        self.assertIn("if(sharedCore&&srEnabled)", compact)
        self.assertIn("}else4", compact)
        self.assertIn("intensityPercent=if(sharedCore)intensity.nativePercent else100".replace(" ", ""), compact)
        self.assertIn("decision.hdr&&hdrMode==DesktopNvidiaVideoHdrMode.AUTO&&hdrAllowed", compact)
        self.assertIn("(plan?.hdrAllowedFor(srEnabled)?:true)&&guard.hdrAllowed", compact)

    def test_the_request_identity_gains_no_field(self):
        player = source.read(source.K / "player/PlayerNvidiaVideo.kt")
        start = player.index("data class NvidiaVideoOptions(")
        header = " ".join(player[start:player.index(") {", start) + 3].split())
        self.assertEqual(
            "data class NvidiaVideoOptions(val scale: Double = 1.0, val hdr: Boolean = false, "
            "val nativeResolutionProcessing: Boolean = false, val backend: NvidiaVideoBackend = NvidiaVideoBackend.DRIVER, "
            "val qualityLevel: Int = 4, val srEnabled: Boolean = true, val intensityPercent: Int = 100) {", header)

    def test_the_guard_limits_default_to_no_change(self):
        self.assertIn("internal data class NvidiaGuardLimits(val qualityCap: Int = 4, val hdrAllowed: Boolean = true) {", self.text)
        self.assertIn("val None: NvidiaGuardLimits = NvidiaGuardLimits()", self.text)


if __name__ == "__main__":
    unittest.main()
