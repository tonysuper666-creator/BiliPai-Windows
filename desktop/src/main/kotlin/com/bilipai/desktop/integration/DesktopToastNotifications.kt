package com.bilipai.desktop.integration

import com.bilipai.desktop.DesktopApplicationRestart
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import java.net.URI
import java.net.URLEncoder
import java.net.URLDecoder
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Windows notification-center adapter. Protocol activation survives the publishing process. */
internal object DesktopToastNotifications {
    private const val APP_ID = "BiliPai.Windows"
    private const val SCHEME = "bilipai-windows"
    @Volatile private var registered = false

    internal fun routeFromArgs(args: Array<String>): String = args.firstOrNull { it.startsWith("$SCHEME://open?") }
        ?.let { uri ->
            val raw = URI(uri).rawQuery.orEmpty().removePrefix("route=")
            URLDecoder.decode(raw, Charsets.UTF_8).take(4_000)
        }.orEmpty()

    internal fun activationUri(route: String): String = "$SCHEME://open?route=" + URLEncoder.encode(route.take(4_000), Charsets.UTF_8)
    private fun xml(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;").filter { it >= ' ' || it == '\n' }
    internal fun content(title: String, text: String, route: String): String =
        "<toast activationType=\"protocol\" launch=\"${xml(activationUri(route))}\"><visual><binding template=\"ToastGeneric\">" +
            "<text>${xml(title.take(200))}</text><text>${xml(text.take(2_000))}</text></binding></visual></toast>"

    @Synchronized private fun register() {
        if (registered) return
        check(System.getProperty("os.name").startsWith("Windows")) { "Windows 通知中心仅在 Windows 上可用" }
        val command = DesktopApplicationRestart.prepare().command
        // Windows command-line quoting; these values are our executable/classpath, never notification data.
        require(command.none { '"' in it || '\u0000' in it }) { "通知启动路径包含不支持的字符" }
        val root = WinReg.HKEY_CURRENT_USER
        val protocol = "Software\\Classes\\$SCHEME"
        Advapi32Util.registryCreateKey(root, protocol)
        Advapi32Util.registrySetStringValue(root, protocol, "", "URL:BiliPai Windows")
        Advapi32Util.registrySetStringValue(root, protocol, "URL Protocol", "")
        Advapi32Util.registryCreateKey(root, "$protocol\\shell\\open\\command")
        Advapi32Util.registrySetStringValue(root, "$protocol\\shell\\open\\command", "", command.joinToString(" ") { "\"$it\"" } + " \"%1\"")
        val identity = "Software\\Classes\\AppUserModelId\\$APP_ID"
        Advapi32Util.registryCreateKey(root, identity)
        Advapi32Util.registrySetStringValue(root, identity, "DisplayName", "BiliPai Windows")
        registered = true
    }

    /** Blocking IO boundary; callers use Dispatchers.IO. Nonzero HRESULT/process exit is surfaced. */
    fun show(title: String, text: String, route: String, tag: String, group: String) {
        register()
        val payload = Base64.getEncoder().encodeToString(content(title, text, route).toByteArray(Charsets.UTF_8))
        val safeTag = tag.hashCode().toUInt().toString(16)
        val safeGroup = group.hashCode().toUInt().toString(16)
        val script = """
            ${'$'}ErrorActionPreference = 'Stop'
            [Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType=WindowsRuntime] > ${'$'}null
            [Windows.Data.Xml.Dom.XmlDocument, Windows.Data.Xml.Dom.XmlDocument, ContentType=WindowsRuntime] > ${'$'}null
            ${'$'}xml = New-Object Windows.Data.Xml.Dom.XmlDocument
            ${'$'}xml.LoadXml([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('$payload')))
            ${'$'}toast = [Windows.UI.Notifications.ToastNotification]::new(${'$'}xml)
            ${'$'}toast.Tag = '$safeTag'; ${'$'}toast.Group = '$safeGroup'
            ${'$'}notifier = [Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier('$APP_ID')
            if ([int]${'$'}notifier.Setting -ne 0) { throw 'Windows 通知已被系统或用户禁用' }
            ${'$'}notifier.Show(${'$'}toast)
        """.trimIndent()
        runScript(script)
    }
    fun clear(group: String) {
        if (!registered) return
        val safe = group.hashCode().toUInt().toString(16)
        runScript("[Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType=WindowsRuntime] > ${'$'}null; [Windows.UI.Notifications.ToastNotificationManager]::History.RemoveGroup('$safe', '$APP_ID')")
    }
    private fun runScript(script: String) {
        val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
        val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded).redirectErrorStream(true).start()
        try {
            check(process.waitFor(15, TimeUnit.SECONDS)) { "Windows 通知中心响应超时" }
            val output = process.inputStream.bufferedReader().readText().take(2_000)
            check(process.exitValue() == 0) { "Windows 通知中心失败: $output" }
        } finally { if (process.isAlive) process.destroyForcibly() }
    }
}
