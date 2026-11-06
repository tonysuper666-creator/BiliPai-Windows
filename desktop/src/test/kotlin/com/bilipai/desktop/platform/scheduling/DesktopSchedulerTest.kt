package com.bilipai.desktop.platform.scheduling

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DesktopSchedulerTest {
    private val scheduler = WindowsScheduledTaskScheduler()
    private val scripts = mutableListOf<String>()
    private val executable: Path = Files.createTempFile("BiliPai Windows", ".exe").also { it.toFile().deleteOnExit() }
    private val originalOs = WindowsScheduledTaskScheduler.osName
    private val originalShell = WindowsScheduledTaskScheduler.powershell

    @BeforeTest fun pretendToBeWindows() {
        WindowsScheduledTaskScheduler.osName = { "Windows 11" }
        WindowsScheduledTaskScheduler.powershell = { script -> scripts += script; PowerShellOutcome(0) }
    }

    @AfterTest fun restoreSeams() {
        WindowsScheduledTaskScheduler.osName = originalOs
        WindowsScheduledTaskScheduler.powershell = originalShell
    }

    @Test fun registersOneTaskAtTheExactRequestedMoment() {
        scheduler.scheduleOneShot("reserve-42", 1_762_147_600_000L, executable, "--scheduled-reminder 42")
        val script = scripts.single()

        // The shell owns the timer after this returns: once, at this millisecond, in this user's session.
        assertTrue(script.contains("[DateTimeOffset]::FromUnixTimeMilliseconds(1762147600000).LocalDateTime"), script)
        assertTrue(script.contains("New-ScheduledTaskTrigger -Once -At \$fireAt"), script)
        assertTrue(script.contains("New-ScheduledTaskAction -Execute '${executable.toAbsolutePath()}'"), script)
        assertTrue(script.contains("-Argument '--scheduled-reminder 42'"), script)
        assertTrue(script.contains("-LogonType Interactive"), script)
        assertTrue(script.contains("-StartWhenAvailable"), script)
        assertTrue(script.contains("Register-ScheduledTask -TaskName 'BiliPaiWindows-Reminder-0afe31eb621d4a75'"), script)
        // Same key must replace its earlier registration instead of stacking a second reminder.
        assertTrue(script.contains("-Force"), script)
    }

    @Test fun oneKeyAlwaysMapsToOneTaskAndDistinctKeysNeverCollide() {
        scheduler.scheduleOneShot("reserve-42", 1L, executable, "--scheduled-reminder 42")
        scheduler.scheduleOneShot("reserve-42", 2L, executable, "--scheduled-reminder 42")
        scheduler.scheduleOneShot("reserve-43", 3L, executable, "--scheduled-reminder 43")
        val names = scripts.map { it.substringAfter("Register-ScheduledTask -TaskName '").substringBefore("'") }
        assertEquals("BiliPaiWindows-Reminder-0afe31eb621d4a75", names[0])
        assertEquals(names[0], names[1])
        assertEquals("BiliPaiWindows-Reminder-e485e534c69b7566", names[2])
    }

    @Test fun cancelWithdrawsExactlyTheTaskTheRegistrationCreated() {
        scheduler.scheduleOneShot("reserve-42", 1_762_147_600_000L, executable, "--scheduled-reminder 42")
        scheduler.cancel("reserve-42")
        val registered = scripts[0].substringAfter("Register-ScheduledTask -TaskName '").substringBefore("'")
        val cancellation = scripts[1]
        assertTrue(cancellation.contains("Get-ScheduledTask -TaskName '$registered'"), cancellation)
        assertTrue(cancellation.contains("Unregister-ScheduledTask -TaskName '$registered' -Confirm:\$false"), cancellation)
    }

    @Test fun cancelOnlyIsNotAnErrorWhenNothingWasRegistered() {
        // A reminder that already fired must still be cancellable without a task left behind.
        scheduler.cancel("reserve-42")
        assertTrue(scripts.single().contains("if (\$null -ne \$existing) { Unregister-ScheduledTask"), scripts.single())
    }

    @Test fun aTimedOutRegistrationIsLoud() {
        WindowsScheduledTaskScheduler.powershell = { PowerShellOutcome(-1, timedOut = true) }
        val failure = assertFailsWith<IllegalStateException> {
            scheduler.scheduleOneShot("reserve-42", 1L, executable, "--scheduled-reminder 42")
        }
        assertEquals("Windows 任务计划设置超时", failure.message)
    }

    @Test fun aRejectedRegistrationIsLoud() {
        WindowsScheduledTaskScheduler.powershell = { PowerShellOutcome(exitCode = 1) }
        val failure = assertFailsWith<IllegalArgumentException> {
            scheduler.scheduleOneShot("reserve-42", 1L, executable, "--scheduled-reminder 42")
        }
        assertEquals("Windows 任务计划设置失败，请检查任务计划程序是否可用", failure.message)
        // A cancellation that the shell refused must fail too; silently keeping the task would hide a reminder.
        assertFailsWith<IllegalArgumentException> { scheduler.cancel("reserve-42") }
    }

    @Test fun aNonWindowsHostRefusesInsteadOfPretendingToSchedule() {
        WindowsScheduledTaskScheduler.osName = { "Mac OS X" }
        val failure = assertFailsWith<IllegalArgumentException> {
            scheduler.scheduleOneShot("reserve-42", 1L, executable, "--scheduled-reminder 42")
        }
        assertEquals("后台提醒需要 Windows 任务计划程序", failure.message)
        assertFailsWith<IllegalArgumentException> { scheduler.cancel("reserve-42") }
        assertEquals(emptyList(), scripts, "nothing may be scheduled outside Windows")
    }

    @Test fun aMissingBinaryIsLoudInsteadOfSchedulingNothing() {
        val failure = assertFailsWith<IllegalArgumentException> {
            scheduler.scheduleOneShot("reserve-42", 1L, Path.of("/definitely/not/BiliPai Windows.exe"), "--scheduled-reminder 42")
        }
        assertEquals("Windows 启动程序不存在", failure.message)
        assertEquals(emptyList(), scripts)
    }

    @Test fun theConvenienceOverloadNeedsThePackagedApp() {
        // Under tests the process is java, not the packaged app: scheduling must refuse rather than
        // register a task that would launch java without the reminder argument.
        val failure = assertFailsWith<IllegalArgumentException> {
            scheduler.scheduleOneShot("reserve-42", 1L, "--scheduled-reminder 42")
        }
        assertEquals("请使用打包的 BiliPai Windows.exe 安排后台提醒", failure.message)
    }
}
