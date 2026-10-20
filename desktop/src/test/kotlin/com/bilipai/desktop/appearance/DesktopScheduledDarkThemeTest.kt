package com.bilipai.desktop.appearance

import androidx.compose.ui.graphics.Color
import com.android.purebilibili.core.theme.SeedPresetColorNames
import com.android.purebilibili.core.theme.SeedPresetColors
import com.android.purebilibili.core.theme.ThemeColorNames
import com.android.purebilibili.core.theme.ThemeColors
import com.android.purebilibili.core.theme.formatMd3CustomColorHex
import com.android.purebilibili.core.theme.parseMd3CustomColorHex
import com.android.purebilibili.feature.settings.AppThemeMode
import com.android.purebilibili.feature.settings.DarkThemeSchedule
import com.android.purebilibili.feature.settings.DarkThemeStyle
import com.android.purebilibili.feature.settings.Md3ColorSource
import com.android.purebilibili.feature.settings.isValidMd3CustomColorHex
import com.bilipai.desktop.plugins.DesktopPluginStore
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** No window, GPU, network or Windows API: the preference keys, the palette override, the timer maths and the preset lists. */
class DesktopScheduledDarkThemeTest {
    @TempDir lateinit var directory: Path

    @Test fun absentKeysLeaveTheScheduleDisabledAtTheOriginalDefaultTimes() {
        val schedule = decodeDesktopThemeSettings(JsonObject(emptyMap())).darkThemeSchedule
        assertEquals(DarkThemeSchedule(), schedule)
        assertFalse(schedule.enabled)
        assertEquals(22 * 60, schedule.startMinute)
        assertEquals(7 * 60, schedule.endMinute)
    }

    @Test fun theScheduleIsWrittenUnderTheOriginalThreeKeysInOneStoreWrite(): Unit = runBlocking {
        val store = DesktopPluginStore(directory)
        val prefs = DesktopThemePrefs(store)
        prefs.setDarkThemeSchedule(DarkThemeSchedule(enabled = true, startMinute = 21 * 60 + 30, endMinute = 6 * 60 + 15))
        val stored = store.preferences("settings")
        assertEquals(true, stored["dark_theme_schedule_enabled"]?.jsonPrimitive?.boolean)
        assertEquals(21 * 60 + 30, stored["dark_theme_schedule_start_minute"]?.jsonPrimitive?.int)
        assertEquals(6 * 60 + 15, stored["dark_theme_schedule_end_minute"]?.jsonPrimitive?.int)
        val restored = DesktopThemePrefs(DesktopPluginStore(directory)).initialSettings().darkThemeSchedule
        assertEquals(DarkThemeSchedule(true, 21 * 60 + 30, 6 * 60 + 15), restored)
        prefs.setDarkThemeSchedule(restored.copy(enabled = false))
        val disabled = DesktopThemePrefs(DesktopPluginStore(directory)).initialSettings().darkThemeSchedule
        assertFalse(disabled.enabled)
        assertEquals(restored.startMinute, disabled.startMinute)
        assertEquals(restored.endMinute, disabled.endMinute)
    }

    @Test fun anInvalidScheduleIsRefusedBeforeAnythingIsWritten(): Unit = runBlocking {
        val store = DesktopPluginStore(directory)
        val prefs = DesktopThemePrefs(store)
        for (bad in listOf(DarkThemeSchedule(true, 300, 300), DarkThemeSchedule(true, -1, 100),
            DarkThemeSchedule(true, 0, 1440), DarkThemeSchedule(true, 1440, 0))) {
            assertFailsWith<IllegalArgumentException>(bad.toString()) { prefs.setDarkThemeSchedule(bad) }
        }
        assertFalse("dark_theme_schedule_enabled" in store.preferences("settings"))
        assertFalse("dark_theme_schedule_start_minute" in store.preferences("settings"))
    }

    @Test fun storedGarbageAndOutOfRangeTimesNormalizeLikeTheOriginalStore() {
        val equal = buildJsonObject {
            put("dark_theme_schedule_enabled", true); put("dark_theme_schedule_start_minute", 300); put("dark_theme_schedule_end_minute", 300)
        }
        assertEquals(DarkThemeSchedule(), decodeDesktopThemeSettings(equal).darkThemeSchedule)
        val outOfRange = buildJsonObject {
            put("dark_theme_schedule_enabled", true); put("dark_theme_schedule_start_minute", -1); put("dark_theme_schedule_end_minute", 9999)
        }
        assertEquals(DarkThemeSchedule(true, 0, 1439), decodeDesktopThemeSettings(outOfRange).darkThemeSchedule)
        val wrongTypes = buildJsonObject {
            put("dark_theme_schedule_enabled", "yes"); put("dark_theme_schedule_start_minute", "22:00"); put("dark_theme_schedule_end_minute", 7.5)
        }
        val decoded = decodeDesktopThemeSettings(wrongTypes).darkThemeSchedule
        assertFalse(decoded.enabled)
        assertEquals(22 * 60, decoded.startMinute)
    }

    @Test fun anActiveScheduleDecidesLightOrDarkOverTheManualModeAndTheSystem() {
        val settings = DesktopThemeSettings(themeMode = AppThemeMode.LIGHT)
        assertTrue(buildDesktopAppearancePalette(settings, systemDark = false, scheduledDark = true).dark)
        assertFalse(buildDesktopAppearancePalette(DesktopThemeSettings(themeMode = AppThemeMode.DARK), systemDark = true, scheduledDark = false).dark)
        // No schedule: the ordinary mode and the system setting apply exactly as before.
        assertFalse(buildDesktopAppearancePalette(settings, systemDark = true).dark)
        assertTrue(buildDesktopAppearancePalette(DesktopThemeSettings(themeMode = AppThemeMode.DARK), systemDark = false, scheduledDark = null).dark)
        assertTrue(buildDesktopAppearancePalette(DesktopThemeSettings(), systemDark = true, scheduledDark = null).dark)
        // The dark theme style still applies to a scheduled dark theme.
        val amoled = buildDesktopAppearancePalette(
            DesktopThemeSettings(themeMode = AppThemeMode.LIGHT, darkThemeStyle = DarkThemeStyle.AMOLED), systemDark = false, scheduledDark = true)
        assertTrue(amoled.dark)
        assertTrue(amoled.amoled)
    }

    @Test fun theNativeMiuixControllerFollowsTheScheduleNotTheStoredMode() {
        assertEquals(AppThemeMode.FOLLOW_SYSTEM, resolveDesktopEffectiveThemeMode(AppThemeMode.FOLLOW_SYSTEM, null))
        assertEquals(AppThemeMode.LIGHT, resolveDesktopEffectiveThemeMode(AppThemeMode.LIGHT, null))
        assertEquals(AppThemeMode.DARK, resolveDesktopEffectiveThemeMode(AppThemeMode.LIGHT, true))
        assertEquals(AppThemeMode.LIGHT, resolveDesktopEffectiveThemeMode(AppThemeMode.DARK, false))
        assertEquals(AppThemeMode.DARK, resolveDesktopEffectiveThemeMode(AppThemeMode.FOLLOW_SYSTEM, true))
    }

    @Test fun theMinuteOfTheDayIsTheLocalClockOfTheInjectedZone() {
        val noon = Instant.parse("2026-10-20T13:07:59Z")
        assertEquals(13 * 60 + 7, desktopMinuteOfDay(Clock.fixed(noon, ZoneOffset.UTC)))
        assertEquals(21 * 60 + 7, desktopMinuteOfDay(Clock.fixed(noon, ZoneOffset.ofHours(8))))
        assertEquals(0, desktopMinuteOfDay(Clock.fixed(Instant.parse("2026-10-20T00:00:00Z"), ZoneOffset.UTC)))
        assertEquals(23 * 60 + 59, desktopMinuteOfDay(Clock.fixed(Instant.parse("2026-10-20T23:59:59Z"), ZoneOffset.UTC)))
    }

    @Test fun theTimerSleepsToTheNextMinuteButNeverLongerThanItsCap() {
        assertEquals(DESKTOP_SCHEDULE_MAX_WAIT_MILLIS, desktopScheduleWaitMillis(0L))
        assertEquals(15_025L, desktopScheduleWaitMillis(45_000L))
        assertEquals(100L, desktopScheduleWaitMillis(59_990L))
        assertEquals(100L, desktopScheduleWaitMillis(-1L))
        for (now in listOf(0L, 1L, 29_999L, 30_000L, 59_999L, 1_760_000_000_123L, -61_000L)) {
            val wait = desktopScheduleWaitMillis(now)
            assertTrue(wait in 100L..DESKTOP_SCHEDULE_MAX_WAIT_MILLIS, "$now -> $wait")
        }
    }

    @Test fun theSeedStyleListHasNineteenPresetsAndTheOriginalListKeepsItsTwentyFive() {
        val seed = resolveDesktopThemePresetList(true)
        assertEquals(19, seed.colors.size)
        assertEquals(seed.colors.size, seed.names.size)
        assertEquals(SeedPresetColors, seed.colors)
        assertEquals(SeedPresetColorNames, seed.names)
        assertEquals(Color(0xFF5CB67B), seed.colors.first())
        val original = resolveDesktopThemePresetList(false)
        assertEquals(ThemeColors, original.colors)
        assertEquals(ThemeColorNames, original.names)
        assertEquals(original.colors.size, original.names.size)
    }

    @Test fun everyPresetRoundTripsThroughTheCustomColourHex() {
        for (color in SeedPresetColors + ThemeColors) {
            val hex = formatMd3CustomColorHex(color)
            assertTrue(isValidMd3CustomColorHex(hex), hex)
            assertEquals(color, parseMd3CustomColorHex(hex), hex)
        }
        assertEquals(SeedPresetColors.size, SeedPresetColors.toSet().size)
    }

    @Test fun choosingAPresetWritesTheIndexOnlyForTheOriginalListInPresetMode() {
        assertEquals(DesktopPresetPick.THEME_COLOR_INDEX, resolveDesktopPresetPick(false, Md3ColorSource.FOLLOW_WALLPAPER))
        assertEquals(DesktopPresetPick.CUSTOM_COLOR, resolveDesktopPresetPick(true, Md3ColorSource.FOLLOW_WALLPAPER))
        assertEquals(DesktopPresetPick.CUSTOM_COLOR, resolveDesktopPresetPick(false, Md3ColorSource.CUSTOM))
        assertEquals(DesktopPresetPick.CUSTOM_COLOR, resolveDesktopPresetPick(true, Md3ColorSource.CUSTOM))
        assertEquals(DesktopPresetPick.CUSTOM_COLOR, resolveDesktopPresetPick(false, Md3ColorSource.WALLPAPER_RETONED))
    }

    @Test fun theDisabledScheduleLeavesTheNullOverrideAndTheDefaultsUntouched() {
        val settings = DesktopThemeSettings()
        assertFalse(settings.darkThemeSchedule.enabled)
        assertEquals(buildDesktopAppearancePalette(settings, systemDark = false).dark,
            buildDesktopAppearancePalette(settings, systemDark = false, scheduledDark = null).dark)
    }
}
