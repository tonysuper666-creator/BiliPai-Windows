package com.bilipai.desktop.ui

import com.android.purebilibili.core.util.BilibiliNavigationTarget
import com.android.purebilibili.core.util.BilibiliNavigationTargetParser
import com.android.purebilibili.feature.web.LinkOpenGate
import com.android.purebilibili.navigation.BilibiliLinkNavigationAction
import com.android.purebilibili.navigation.isBilibiliShortWebLink
import com.android.purebilibili.navigation.resolveBilibiliLinkNavigationAction
import com.android.purebilibili.navigation3.BiliPaiNavKey
import java.awt.EventQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*

/** Original v043 content-link policy/gate, borrowing the one actual Root dispatcher.
 * Network resolution runs outside Store admission. The first yield also keeps a
 * synchronous native target outside the caller's retained gallery/Store gate.
 * Search submissions and message reply-context parsing retain their own callers. */
internal class DesktopOriginalContentLinkBinding(
    val routes: DesktopOriginalRootRouteAssembly,
    private val scope: CoroutineScope = routes.root.entry.gate.scope,
    private val stillPresented: () -> Boolean,
    private val openExternal: (String) -> Unit,
    private val feedback: (String) -> Unit,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val resolveShortLink: suspend (String) -> BilibiliNavigationTarget? = BilibiliNavigationTargetParser::resolve,
) : AutoCloseable {
    private data class Source(val physical: BiliPaiNavKey, val content: BiliPaiNavKey,
        val revision: Long, val serial: Long)
    private val gate = LinkOpenGate()
    private val closed = AtomicBoolean()
    private val serial = AtomicLong()
    private val pending = AtomicReference<Job?>(null)

    private fun owns(source: Source) = !closed.get() && scope.isActive && routes.owns() && stillPresented() &&
        serial.get() == source.serial && routes.sourceNavigationRevision == source.revision &&
        routes.currentKey === source.physical && routes.isCurrentContent(source.content)

    fun open(raw: String, title: String = "链接"): Boolean {
        check(EventQueue.isDispatchThread()) { "Original content links require the actual Window EDT" }
        if (closed.get() || !scope.isActive || !routes.owns() || !stillPresented()) return false
        if (!gate.tryAcquire(nowMillis())) return false
        val source = Source(routes.currentKey, routes.loginReadDestination(),
            routes.sourceNavigationRevision, serial.incrementAndGet())
        val job = scope.launch(start = CoroutineStart.LAZY) {
            yield()
            ensureActive()
            check(EventQueue.isDispatchThread()) { "Original link results require their Window EDT" }
            if (!owns(source)) return@launch
            fun native(target: BilibiliNavigationTarget) = dispatchDesktopReadyNativeTarget(
                routes.root, routes, target, { owns(source) })
            fun web(url: String) = routes.pushFromSource(BiliPaiNavKey.Web(url, title), { owns(source) }, null)
            when (val action = resolveBilibiliLinkNavigationAction(raw)) {
                is BilibiliLinkNavigationAction.NativeTarget -> native(action.target)
                is BilibiliLinkNavigationAction.InAppWeb -> {
                    if (isBilibiliShortWebLink(action.url)) {
                        val target = try { resolveShortLink(action.url) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { null }
                        ensureActive()
                        if (owns(source) && (target == null || !native(target))) web(action.url)
                    } else web(action.url)
                }
                is BilibiliLinkNavigationAction.External -> if (owns(source)) {
                    try { openExternal(action.url) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { if (owns(source)) feedback("无法打开链接") }
                }
                BilibiliLinkNavigationAction.None -> Unit
            }
            Unit
        }
        pending.getAndSet(job)?.cancel()
        job.invokeOnCompletion {
            pending.compareAndSet(job, null)
            gate.release() // Also releases an already-cancelled lazy launch.
        }
        return job.start()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        serial.incrementAndGet()
        pending.getAndSet(null)?.cancel()
    }
}
