package com.bilipai.desktop.diagnostics

import com.bilipai.desktop.update.UpdateStorage
import com.sun.jna.*
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.LongByReference
import com.sun.jna.win32.StdCallLibrary
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.awt.Window
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** Explicit UI text shares reuse the verified WinRT bridge, without file leases. */
internal class DesktopNativeTextShare(
    private val nativeDll: () -> Path,
    private val expectedSha256: String,
    private val window: () -> Window?,
) {
    private interface Api : StdCallLibrary {
        fun BilipaiSharePrepareText(title: WString, text: WString, result: LongByReference): Int
        fun BilipaiShareShow(token: Long, hwnd: Pointer): Int
        fun BilipaiShareState(token: Long, state: IntByReference): Int
        fun BilipaiShareRetire(token: Long, state: IntByReference, supplied: IntByReference): Int
    }
    private val api: Api by lazy {
        check(System.getProperty("os.name").startsWith("Windows") && Native.POINTER_SIZE == 8)
        val path = UpdateStorage.existingPathWithoutLinks(nativeDll().toAbsolutePath())
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.size(path) in 1..16L*1024*1024)
        val hash = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }
        check(hash == expectedSha256) { "原生分享资源校验失败" }
        Native.load(path.toString(), Api::class.java)
    }
    private val operations = Mutex()
    private val closing = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var active: Long? = null
    private var watcher: Job? = null
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    fun retire() { closing.set(true) }
    fun close() { retire(); scope.launch { shutdown() } }
    private suspend fun closeActive() = withContext(NonCancellable + Dispatchers.Swing) {
        active?.let { token ->
            check(api.BilipaiShareRetire(token, IntByReference(), IntByReference()) >= 0) { "原生分享尚未释放" }
            active = null
        }
    }
    suspend fun share(title: String, text: String, stillOwned: () -> Boolean): Boolean = operations.withLock {
        require(text.isNotBlank() && text.length <= 65536 && '\u0000' !in text)
        require(title.length <= 256 && '\u0000' !in title)
        currentCoroutineContext().ensureActive()
        if (closing.get() || !stillOwned()) return@withLock false
        mutableError.value = null
        closeActive()
        val result = LongByReference()
        // Capture native ownership even if cancellation arrives during allocation.
        withContext(NonCancellable + Dispatchers.IO) {
            check(api.BilipaiSharePrepareText(WString(title.ifBlank { "BiliPai 分享" }), WString(text), result) >= 0 && result.value != 0L)
        }
        active = result.value
        try {
            currentCoroutineContext().ensureActive()
            val shown = withContext(Dispatchers.Swing) {
                val owner = window()
                if (closing.get() || !stillOwned() || owner == null || !owner.isDisplayable || !owner.isVisible) false
                else api.BilipaiShareShow(result.value, Native.getWindowPointer(owner)) >= 0
            }
            currentCoroutineContext().ensureActive()
            if (!shown || closing.get() || !stillOwned()) { closeActive(); return@withLock false }
            watcher?.cancel()
            val token = result.value
            watcher = scope.launch {
                try {
                while (isActive && !closing.get()) {
                    delay(500)
                    val done = operations.withLock {
                        if (active != token) true else {
                            val state = IntByReference()
                            if (api.BilipaiShareState(token, state) < 0 || state.value in 3..5) { closeActive(); true } else false
                        }
                    }
                    if (done) break
                }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { mutableError.value = failure.message ?: "原生分享状态读取失败" }
            }
            true
        } catch (failure: Throwable) {
            closeActive()
            throw failure
        }
    }
    suspend fun shutdown() = withContext(NonCancellable) {
        retire()
        watcher?.cancelAndJoin(); watcher = null
        operations.withLock { closeActive() }
        scope.coroutineContext[Job]?.cancel()
    }
}
