package com.bilipai.desktop.appearance

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.android.purebilibili.feature.settings.AppThemeMode
import com.android.purebilibili.feature.settings.DarkThemeSchedule
import com.android.purebilibili.feature.settings.isScheduledDarkTheme
import java.time.Clock
import java.time.LocalTime
import kotlinx.coroutines.delay

/** Longest sleep of the schedule timer, so a Windows sleep/resume or a manual clock change is noticed within this time. */
internal const val DESKTOP_SCHEDULE_MAX_WAIT_MILLIS = 30_000L

private const val MILLIS_PER_MINUTE = 60_000L

/** Minutes in the current local day, the unit the original [DarkThemeSchedule] is written in. */
internal fun desktopMinuteOfDay(clock: Clock = Clock.systemDefaultZone()): Int =
    LocalTime.now(clock).let { it.hour * 60 + it.minute }

/** Sleep until just after the next minute boundary, but never longer than [DESKTOP_SCHEDULE_MAX_WAIT_MILLIS]. */
internal fun desktopScheduleWaitMillis(nowEpochMillis: Long): Long {
    val untilBoundary = MILLIS_PER_MINUTE - Math.floorMod(nowEpochMillis, MILLIS_PER_MINUTE) + 25L
    return untilBoundary.coerceIn(100L, DESKTOP_SCHEDULE_MAX_WAIT_MILLIS)
}

/**
 * The theme mode the native Miuix controller must follow. An active schedule decides the light or dark theme itself,
 * so the controller is pinned to the matching fixed mode; the ordinary mode applies again when the schedule is off.
 */
internal fun resolveDesktopEffectiveThemeMode(themeMode: AppThemeMode, scheduledDarkTheme: Boolean?): AppThemeMode =
    when (scheduledDarkTheme) {
        null -> themeMode
        true -> AppThemeMode.DARK
        false -> AppThemeMode.LIGHT
    }

/**
 * Windows counterpart of the original ScheduledDarkThemeObserver: a coroutine minute timer instead of Android time
 * broadcasts. Returns null while the schedule is off (the ordinary theme mode applies), otherwise whether the schedule
 * asks for the dark theme right now. No background worker, no service and no timer exist while the schedule is off.
 */
@Composable
internal fun rememberDesktopScheduledDarkTheme(schedule: DarkThemeSchedule): Boolean? {
    var minute by remember(schedule.enabled) { mutableIntStateOf(desktopMinuteOfDay()) }
    LaunchedEffect(schedule.enabled) {
        if (!schedule.enabled) return@LaunchedEffect
        while (true) {
            minute = desktopMinuteOfDay()
            delay(desktopScheduleWaitMillis(System.currentTimeMillis()))
        }
    }
    return if (schedule.enabled) isScheduledDarkTheme(schedule, minute) else null
}
