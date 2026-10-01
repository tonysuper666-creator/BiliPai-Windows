package com.bilipai.desktop.download.notificationProof

import com.bilipai.desktop.download.*
import com.android.purebilibili.feature.download.DownloadTask as OriginalTask
import com.android.purebilibili.feature.download.DownloadStatus
import com.android.purebilibili.feature.download.DownloadOptions
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import kotlinx.coroutines.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.awt.Frame
import java.awt.SystemTray
import java.awt.Taskbar
import java.awt.event.ActionEvent
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities

private interface User32 : StdCallLibrary {
    fun IsWindow(hwnd: Pointer): Boolean
    fun GetWindowThreadProcessId(hwnd: Pointer, process: IntByReference): Int
}

private fun <T> edt(action: () -> T): T {
    if (SwingUtilities.isEventDispatchThread()) return action()
    var value: T? = null
    var error: Throwable? = null
    SwingUtilities.invokeAndWait { try { value = action() } catch (t: Throwable) { error = t } }
    error?.let { throw it }
    @Suppress("UNCHECKED_CAST") return value as T
}

fun main(args: Array<String>) = runBlocking {
    val scratch = Path.of(args[0]);Files.createDirectories(scratch)
    var assertions = 0
    fun verify(value: Boolean, text: String) { check(value) { text }; assertions++ }
    suspend fun await(text: String, condition: () -> Boolean) {
        withTimeout(12_000) { while (!condition()) delay(25) }; verify(true, text)
    }
    val errors = mutableListOf<String>()
    val owner = AtomicBoolean(true)
    val networkCalls = AtomicInteger()
    val getCalls = AtomicInteger()
    val firstTwo = CountDownLatch(2)
    val secondTwo = CountDownLatch(2)
    val releases = Semaphore(0)
    val client = OkHttpClient.Builder().addInterceptor { chain ->
        verify(chain.request().url.host == "offline-notification.invalid", "only terminal fixture request")
        networkCalls.incrementAndGet()
        if (chain.request().method == "GET") {
            val n = getCalls.incrementAndGet()
            if (n <= 2) firstTwo.countDown() else secondTwo.countDown()
            check(releases.tryAcquire(25, TimeUnit.SECONDS)) { "controlled terminal request release" }
        }
        // Never call chain.proceed: real manager executes this terminal application interceptor.
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
            .header("Content-Length", "1000").body(ByteArray(1000).toResponseBody()).build()
    }.build()
    fun task(id: String, title: String, progress: Float) = DownloadTask(OriginalTask(
        bvid = id, cid = 1, title = title, cover = "", ownerName = "", ownerFace = "", duration = 1,
        quality = 80, qualityDesc = "fixture", videoUrl = "https://offline-notification.invalid/$id", audioUrl = "",
        status = DownloadStatus.PAUSED, progress = progress, options = DownloadOptions(includeDanmaku = false)),
        scratch.resolve("downloads").toString())
    val stateFile = scratch.resolve("download-tasks.json")
    Files.writeString(stateFile, Json.encodeToString(ListSerializer(DownloadTask.serializer()), listOf(task("fixtureA", "任务甲", .259f), task("fixtureB", "", .759f))))
    val manager = DesktopDownloadManager(client, stateFile, DownloadMuxer { _, _, _ -> error("No completed download/mux allowed") })
    val rootScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val frame = edt { Frame("BiliPai notification proof own hidden window").apply { setSize(64, 64); addNotify() } }
    val user = Native.load("user32", User32::class.java)
    val hwnd = edt { Native.getWindowPointer(frame) }
    val process = IntByReference()
    val hwndThread = user.GetWindowThreadProcessId(hwnd, process)
    val trayBefore = edt { SystemTray.getSystemTray().trayIcons.toSet() }
    lateinit var adapter: DesktopDownloadNotifications
    var replacement: DesktopDownloadNotifications? = null
    try {
        verify(user.IsWindow(hwnd), "own HWND exists")
        verify(hwndThread != 0 && process.value.toLong() == ProcessHandle.current().pid(), "own HWND thread/process")
        verify(!edt { frame.isVisible }, "hidden HWND never shown")
        verify(Taskbar.isTaskbarSupported(), "actual Windows Taskbar supported")
        verify(SystemTray.isSupported(), "actual Windows SystemTray supported")
        verify(manager.tasks.value.size == 2, "actual manager restored sole tasks")
        val originalProjection = projectDesktopDownloadNotification(manager.tasks.value)
        verify(originalProjection.percent == 50 && originalProjection.state == Taskbar.State.PAUSED, "original truncation plus explicit Windows mean")
        verify(originalProjection.taskLines == listOf("任务甲 · 已暂停 25%", "下载中... · 已暂停 75%"), "original title fallback and percent")
        adapter = edt { DesktopDownloadNotifications(manager, frame, rootScope, owner::get, {}, errors::add) }
        await("actual taskbar API dispatch and native tray insertion") { adapter.status.value.taskbarDispatches > 0 && adapter.status.value.trayPresent }
        verify(adapter.status.value.taskbarSupported && adapter.status.value.traySupported, "actual platform support")
        val icon = edt { SystemTray.getSystemTray().trayIcons.single { it !in trayBefore } }
        verify(icon.toolTip.contains("暂停 2") && icon.toolTip.contains("25%"), "native tray per-task ongoing text")
        fun menuAction(label: String) = edt {
            val item = (0 until icon.popupMenu.itemCount).map { icon.popupMenu.getItem(it) }.single { it.label == label }
            verify(item.isEnabled, "actual native popup action enabled: $label")
            item.actionListeners.forEach { it.actionPerformed(ActionEvent(item, ActionEvent.ACTION_PERFORMED, label)) }
        }
        menuAction("全部继续")
        verify(firstTwo.await(8, TimeUnit.SECONDS), "actual manager two concurrent terminal requests")
        await("same sole tasks active") { manager.tasks.value.all { it.status == DownloadStatus.DOWNLOADING } }
        await("2-second sample actual task StateFlow") { adapter.status.value.projection?.activeCount == 2 }
        verify(adapter.status.value.projection?.state == Taskbar.State.INDETERMINATE, "zero-progress unknown projection")
        menuAction("全部暂停")
        await("actual pause-all authoritative task state") { manager.tasks.value.all { it.status == DownloadStatus.PAUSED } }
        releases.release(2)
        delay(300)
        await("paused sample reached native dispatch") { adapter.status.value.projection?.pausedCount == 2 }
        // Repeat through the same tray endpoint; no second download manager or task authority.
        menuAction("全部继续")
        verify(secondTwo.await(8, TimeUnit.SECONDS), "actual continue-all reused manager")
        await("resumed actual tasks active") { manager.tasks.value.all { it.status == DownloadStatus.DOWNLOADING } }
        edt { adapter.pauseAll() }
        releases.release(2)
        await("resumed tasks paused") { manager.tasks.value.all { it.status == DownloadStatus.PAUSED } }
        delay(300)
        // Same HWND replacement retires old tray and invalidates all already queued old updates.
        replacement = edt { DesktopDownloadNotifications(manager, frame, rootScope, owner::get, {}, errors::add) }
        await("replacement owns actual tray/native taskbar") { replacement!!.status.value.trayPresent && replacement!!.status.value.taskbarDispatches > 0 }
        verify(adapter.status.value.closed && !adapter.status.value.trayPresent, "old presentation retired")
        verify(edt { SystemTray.getSystemTray().trayIcons.count { it !in trayBefore } } == 1, "one native tray per host")
        val beforeCalls = networkCalls.get()
        edt { adapter.continueAll() }
        verify(networkCalls.get() == beforeCalls, "retired action cannot resume tasks")
        owner.set(false)
        edt { replacement!!.continueAll() }
        verify(networkCalls.get() == beforeCalls && manager.tasks.value.all { it.status == DownloadStatus.PAUSED }, "Root owner closes admission without retiring manager")
        rootScope.cancel()
        await("parent cancellation removes native tray") { replacement!!.status.value.closed && !replacement!!.status.value.trayPresent }
        verify(edt { SystemTray.getSystemTray().trayIcons.toSet() } == trayBefore, "actual tray restored")
        verify(manager.tasks.value.size == 2 && frame.isDisplayable && user.IsWindow(hwnd), "manager and host remain alive after notification retirement")
        verify(errors.isEmpty(), "no platform failure feedback")
        fun codeSource(name: String): JsonObject {
            val type = Class.forName(name)
            val bytes = type.getResourceAsStream("/" + name.replace('.', '/') + ".class")!!.use { it.readBytes() }
            return buildJsonObject {
                put("class", name);put("location", type.protectionDomain.codeSource?.location?.toString() ?: "jrt:/java.desktop")
                put("sha256ClassBytes", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
            }
        }
        val sources = listOf("com.bilipai.desktop.download.DesktopDownloadNotifications", "com.bilipai.desktop.download.DesktopDownloadManager",
            "com.bilipai.desktop.download.DownloadTask", "com.android.purebilibili.feature.download.DownloadTask",
            "com.android.purebilibili.feature.download.DownloadStatus", "com.android.purebilibili.feature.download.DownloadListNavigationPolicyKt",
            "com.android.purebilibili.feature.download.ResumableAssetDownloader", "java.awt.Taskbar", "java.awt.TrayIcon")
        verify(sources.drop(1).take(6).all { Class.forName(it).protectionDomain.codeSource.location.toString().endsWith("main-kotlin.jar") }, "zero actual manager/model/policy overrides")
        val result = buildJsonObject {
            put("status", "PASS");put("caseCount", 1);put("assertions", assertions);put("ownHiddenHwnd", Pointer.nativeValue(hwnd).toString());put("ownerNativeThread", hwndThread);put("process", process.value)
            put("taskbarNativeApiDispatches", adapter.status.value.taskbarDispatches + replacement!!.status.value.taskbarDispatches)
            put("terminalApplicationInterceptorCalls", networkCalls.get());put("realExternalHTTP", false);put("nativeTrayAddRemove", true)
            put("taskbarVisualPixelsVerified", false);put("nativeHresultAvailable", false);put("noToastEmitted", true);put("mainApplicationWindowUsed", false)
            put("codeSources", JsonArray(sources.map(::codeSource)))
        }
        Files.writeString(scratch.resolve("result.json"), Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), result))
        println(result)
    } finally {
        releases.release(8)
        rootScope.cancel()
        edt { replacement?.close(); if (::adapter.isInitialized) adapter.close(); frame.dispose() }
        manager.close()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
