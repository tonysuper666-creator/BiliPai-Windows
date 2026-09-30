package com.bilipai.desktop.backup

import com.android.purebilibili.feature.settings.webdav.WEBDAV_AUTO_BACKUP_INTERVAL_HOURS
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

interface DesktopBackupScheduler {
    fun install()
    fun uninstall()
}

/** Registers one task for this app's data folder; it runs under the current user's interactive session. */
class WindowsBackupScheduler(private val directory: Path,
    private val executable: Path? = ProcessHandle.current().info().command().orElse(null)?.let(Path::of)
        ?.takeIf { it.fileName.toString().equals("BiliPai Windows.exe", true) }) : DesktopBackupScheduler {
    private val taskName = "BiliPaiWindows-WebDAV-" + MessageDigest.getInstance("SHA-256")
        .digest(directory.toAbsolutePath().normalize().toString().toByteArray()).take(8).joinToString("") { "%02x".format(it) }

    override fun install() {
        val binary = requireNotNull(executable) { "请使用打包的 BiliPai Windows.exe 开启每天自动备份" }
        require(Files.isRegularFile(binary)) { "Windows 启动程序不存在" }
        invoke("Install", binary)
    }

    override fun uninstall() = invoke("Remove", executable ?: directory.resolve("BiliPai Windows.exe"))

    private fun invoke(mode: String, binary: Path) {
        require(System.getProperty("os.name").startsWith("Windows", true)) { "自动备份需要 Windows 任务计划程序" }
        Files.createDirectories(directory)
        val script = directory.resolve("webdav-task-scheduler.ps1")
        Files.writeString(script, SCRIPT)
        val process = ProcessBuilder("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
            "-File", script.toString(), "-Mode", mode, "-Executable", binary.toAbsolutePath().toString(), "-TaskName", taskName,
            "-IntervalHours", WEBDAV_AUTO_BACKUP_INTERVAL_HOURS.toString())
            .redirectErrorStream(true).start()
        if (!process.waitFor(20, TimeUnit.SECONDS)) { process.destroyForcibly(); error("Windows 自动备份任务设置超时") }
        // PowerShell diagnostics can contain user paths; expose only the exit result to the product.
        require(process.exitValue() == 0) { "Windows 自动备份任务设置失败，请检查任务计划程序是否可用" }
    }

    companion object {
        private val SCRIPT = """
            param([ValidateSet('Install','Remove')][string]${'$'}Mode, [string]${'$'}Executable,
                  [string]${'$'}TaskName, [int]${'$'}IntervalHours)
            ${'$'}ErrorActionPreference = 'Stop'
            if (${'$'}Mode -eq 'Remove') {
                ${'$'}existing = Get-ScheduledTask -TaskName ${'$'}TaskName -ErrorAction SilentlyContinue
                if (${'$'}null -ne ${'$'}existing) { Unregister-ScheduledTask -TaskName ${'$'}TaskName -Confirm:${'$'}false }
                exit 0
            }
            ${'$'}action = New-ScheduledTaskAction -Execute ${'$'}Executable -Argument '--webdav-auto-backup'
            ${'$'}trigger = New-ScheduledTaskTrigger -Daily -At ((Get-Date).AddHours(${'$'}IntervalHours))
            ${'$'}settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -RunOnlyIfNetworkAvailable `
                -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 15) -ExecutionTimeLimit (New-TimeSpan -Minutes 10)
            ${'$'}principal = New-ScheduledTaskPrincipal -UserId ([System.Security.Principal.WindowsIdentity]::GetCurrent().Name) `
                -LogonType Interactive -RunLevel Limited
            Register-ScheduledTask -TaskName ${'$'}TaskName -Action ${'$'}action -Trigger ${'$'}trigger `
                -Settings ${'$'}settings -Principal ${'$'}principal -Force | Out-Null
        """.trimIndent()
    }
}
