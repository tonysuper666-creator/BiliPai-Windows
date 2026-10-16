@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package com.bilipai.desktop.appearance

import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.NativeClipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import java.awt.datatransfer.StringSelection
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class DesktopPlatformClipboardTest {
    private class EmptyApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun onClear() = Unit
    }

    private class Platform(
        val read: suspend () -> ClipEntry? = { null },
        val write: suspend (ClipEntry?) -> Unit = {},
        val native: Any = Any(),
    ) : Clipboard {
        override suspend fun getClipEntry() = read()
        override suspend fun setClipEntry(clipEntry: ClipEntry?) = write(clipEntry)
        override val nativeClipboard: NativeClipboard get() = native
    }

    @Test fun `busy reader recovers without replacing or converting the real entry`(): Unit = runBlocking {
        val entry = ClipEntry(StringSelection("private draft"))
        var reads = 0; var pauses = 0
        val failures = mutableListOf<String>()
        val clipboard = DesktopPlatformClipboard(Platform(read = {
            if (++reads < 3) throw IllegalStateException("cannot open system clipboard")
            entry
        }), onFailure = failures::add, pause = { pauses++ })
        assertSame(entry, clipboard.getClipEntry())
        assertEquals(3, reads); assertEquals(2, pauses); assertTrue(failures.isEmpty())
    }

    @Test fun `persistent busy reader returns no paste and reports one content free notice`(): Unit = runBlocking {
        var reads = 0; var pauses = 0
        val failures = mutableListOf<String>()
        val clipboard = DesktopPlatformClipboard(Platform(read = {
            reads++; throw IllegalStateException("native private details")
        }), onFailure = failures::add, pause = { pauses++ })
        assertNull(clipboard.getClipEntry())
        assertEquals(3, reads); assertEquals(2, pauses)
        assertEquals(listOf("剪贴板暂被占用，粘贴未完成，请稍后重试"), failures)
        assertTrue(currentCoroutineContext().isActive)
    }

    @Test fun `a failed write preserves the original exception and is never replayed as a read retry`(): Unit = runBlocking {
        var writes = 0
        var pauses = 0; var failures = 0
        val expected = IllegalStateException("write unavailable")
        val clipboard = DesktopPlatformClipboard(Platform(write = {
            writes++; throw expected
        }), onFailure = { failures++ }, pause = { pauses++ })
        assertSame(expected, assertFailsWith<IllegalStateException> {
            clipboard.setClipEntry(ClipEntry(StringSelection("selected text")))
        })
        assertEquals(1, writes); assertEquals(0, pauses); assertEquals(0, failures)
    }

    @Test fun `cancellation from the platform is never treated as a busy clipboard`(): Unit = runBlocking {
        var reads = 0; var pauses = 0; var failures = 0
        val cancelled = CancellationException("page retired")
        val clipboard = DesktopPlatformClipboard(Platform(read = { reads++; throw cancelled }),
            onFailure = { failures++ }, pause = { pauses++ })
        assertSame(cancelled, assertFailsWith<CancellationException> { clipboard.getClipEntry() })
        assertEquals(1, reads); assertEquals(0, pauses); assertEquals(0, failures)
    }

    @Test fun `cancellation during the suspended retry stops all later access`(): Unit = runBlocking {
        var reads = 0; var failures = 0
        val clipboard = DesktopPlatformClipboard(Platform(read = { reads++; throw IllegalStateException("busy") }),
            onFailure = { failures++ }, pause = { throw CancellationException("closed while waiting") })
        assertFailsWith<CancellationException> { clipboard.getClipEntry() }
        assertEquals(1, reads); assertEquals(0, failures)
    }

    @Test fun `a retired window never starts a clipboard read`(): Unit = runBlocking {
        var calls = 0; var failures = 0
        val clipboard = DesktopPlatformClipboard(Platform(read = { calls++; null }, write = { calls++ }),
            isActive = { false }, onFailure = { failures++ }, pause = {})
        assertFailsWith<CancellationException> { clipboard.getClipEntry() }
        assertEquals(0, calls); assertEquals(0, failures)
    }

    @Test fun `window retirement during contention prevents a late paste`(): Unit = runBlocking {
        var owns = true; var reads = 0; var failures = 0
        val clipboard = DesktopPlatformClipboard(Platform(read = { reads++; throw IllegalStateException("busy") }),
            isActive = { owns }, onFailure = { failures++ }, pause = { owns = false })
        assertFailsWith<CancellationException> { clipboard.getClipEntry() }
        assertEquals(1, reads); assertEquals(0, failures)
    }

    @Test fun `unrelated platform failures keep their exception and native clipboard identity`(): Unit = runBlocking {
        val native = Any(); val expected = IOException("different platform failure")
        var pauses = 0; var failures = 0
        val clipboard = DesktopPlatformClipboard(Platform(read = { throw expected }, native = native),
            onFailure = { failures++ }, pause = { pauses++ })
        assertSame(native, clipboard.nativeClipboard)
        assertSame(expected, assertFailsWith<IOException> { clipboard.getClipEntry() })
        assertEquals(0, pauses); assertEquals(0, failures)
    }

    @Test fun `an explicit null entry delegates a successful clear exactly once`(): Unit = runBlocking {
        var calls = 0; var stored: ClipEntry? = ClipEntry(StringSelection("old"))
        val clipboard = DesktopPlatformClipboard(Platform(write = { calls++; stored = it }),
            onFailure = { error(it) }, pause = {})
        clipboard.setClipEntry(null)
        assertEquals(1, calls); assertNull(stored)
    }

    @Test fun `modal content restores the inherited Main boundary after a platform local replacement`(): Unit = runBlocking {
        val guarded = DesktopPlatformClipboard(Platform(), onFailure = { error(it) })
        val dialogPlatform = Platform()
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock, start = CoroutineStart.UNDISPATCHED) {
            recomposer.runRecomposeAndApplyChanges()
        }
        val composition = Composition(EmptyApplier(), recomposer)
        var observed: Clipboard? = null
        try {
            composition.setContent {
                CompositionLocalProvider(LocalDesktopPlatformClipboard provides guarded) {
                    CompositionLocalProvider(LocalClipboard provides dialogPlatform) {
                        DesktopPlatformClipboardScope {
                            val actual = LocalClipboard.current
                            SideEffect { observed = actual }
                        }
                    }
                }
            }
            assertSame(guarded, observed)
        } finally {
            composition.dispose()
            recomposer.close()
            runner.cancelAndJoin()
        }
    }
}
