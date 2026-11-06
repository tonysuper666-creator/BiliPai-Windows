package com.bilipai.desktop.platform.scheduling

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * OS-level one-shot timer: the desktop counterpart of `WorkManager`'s delayed work that must still
 * fire after the client is closed. Implementations schedule the real system, never an in-process
 * timer that dies with the app.
 */
interface DesktopScheduler {
    /** Registers (or replaces) the task [taskName] to run [executable] with [argument] once at [fireAtMillis]. */
    fun scheduleOneShot(taskName: String, fireAtMillis: Long, executable: Path, argument: String)

    /** Removes the task [taskName]; cancelling a task that never registered is not an error. */
    fun cancel(taskName: String)
}

/** What the PowerShell boundary reported: a timeout is not an exit code. */
internal data class PowerShellOutcome(val exitCode: Int, val timedOut: Boolean = false)

/**
 * Schedules through Windows Task Scheduler, so a reminder still fires with the client closed (the
 * shell runs the task itself). The task runs only in the current user's interactive session, which is
 * what a desktop reminder means; a locked-out or signed-out user is not woken.
 *
 * Every failure the task scheduler can report is loud: a missing packaged binary, a non-Windows host,
 * a timeout, and a nonzero exit all throw, because a reminder that was not registered must never be
 * reported as scheduled. Task names are hashed so one reminder key always maps to one task, and
 * `-Force` replaces a previous registration for the same key.
 */
class WindowsScheduledTaskScheduler : DesktopScheduler {
    override fun scheduleOneShot(taskName: String, fireAtMillis: Long, executable: Path, argument: String) {
        require(Files.isRegularFile(executable)) { "Windows 启动程序不存在" }
        invoke(registerScript(taskKey(taskName), fireAtMillis, executable, argument))
    }

    override fun cancel(taskName: String) = invoke(cancelScript(taskKey(taskName)))

    /** Schedules against the packaged app: the only binary that recognises the reminder argument. */
    fun scheduleOneShot(taskName: String, fireAtMillis: Long, argument: String) =
        scheduleOneShot(taskName, fireAtMillis, packagedExecutable(), argument)

    private fun packagedExecutable(): Path = requireNotNull(
        ProcessHandle.current().info().command().orElse(null)?.let(Path::of)
            ?.takeIf { it.fileName.toString().equals(PACKAGED_EXECUTABLE, ignoreCase = true) },
    ) { "请使用打包的 BiliPai Windows.exe 安排后台提醒" }

    private fun invoke(script: String) {
        require(osName().startsWith("Windows", ignoreCase = true)) { "后台提醒需要 Windows 任务计划程序" }
        val outcome = powershell(script)
        if (outcome.timedOut) error("Windows 任务计划设置超时")
        require(outcome.exitCode == 0) { "Windows 任务计划设置失败，请检查任务计划程序是否可用" }
    }

    companion object {
        private const val PACKAGED_EXECUTABLE = "BiliPai Windows.exe"

        /** Seams for tests: the host identity and the PowerShell boundary. Production uses both defaults. */
        internal var osName: () -> String = { System.getProperty("os.name").orEmpty() }
        internal var powershell: (String) -> PowerShellOutcome = ::runPowerShell
    }
}

/** The task identity the shell stores a reminder under; distinct keys never share a task. */
internal fun taskKey(key: String): String = "BiliPaiWindows-Reminder-" +
    MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8)).take(8)
        .joinToString("") { "%02x".format(it) }

/**
 * One registration covering a single fire time; `-StartWhenAvailable` still runs it after a missed
 * moment (machine asleep at the reminder time), which is why a desktop reminder is not a timer.
 */
internal fun registerScript(taskName: String, fireAtMillis: Long, executable: Path, argument: String): String = """
    ${'$'}ErrorActionPreference = 'Stop'
    ${'$'}fireAt = [DateTimeOffset]::FromUnixTimeMilliseconds($fireAtMillis).LocalDateTime
    ${'$'}action = New-ScheduledTaskAction -Execute ${quote(executable.toAbsolutePath().normalize().toString())} -Argument ${quote(argument)}
    ${'$'}trigger = New-ScheduledTaskTrigger -Once -At ${'$'}fireAt
    ${'$'}settings = New-ScheduledTaskSettingsSet -StartWhenAvailable
    ${'$'}principal = New-ScheduledTaskPrincipal -UserId ([System.Security.Principal.WindowsIdentity]::GetCurrent().Name) -LogonType Interactive -RunLevel Limited
    Register-ScheduledTask -TaskName ${quote(taskName)} -Action ${'$'}action -Trigger ${'$'}trigger -Settings ${'$'}settings -Principal ${'$'}principal -Force | Out-Null
""".trimIndent()

/** Withdrawing an already-fired or never-registered task is a no-op, not a failure. */
internal fun cancelScript(taskName: String): String = """
    ${'$'}ErrorActionPreference = 'Stop'
    ${'$'}existing = Get-ScheduledTask -TaskName ${quote(taskName)} -ErrorAction SilentlyContinue
    if (${'$'}null -ne ${'$'}existing) { Unregister-ScheduledTask -TaskName ${quote(taskName)} -Confirm:${'$'}false }
""".trimIndent()

/** PowerShell single-quoted literal: the only escape it has is doubling the quote itself. */
private fun quote(value: String) = "'" + value.replace("'", "''") + "'"

/** Blocking IO boundary. Output is drained but never surfaced: it can contain the user's paths. */
private fun runPowerShell(script: String): PowerShellOutcome {
    val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
    val process = ProcessBuilder(
        "powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded,
    ).redirectErrorStream(true).start()
    return try {
        if (process.waitFor(20, TimeUnit.SECONDS)) PowerShellOutcome(process.exitValue()) else PowerShellOutcome(-1, timedOut = true)
    } finally {
        if (process.isAlive) process.destroyForcibly()
    }
}
