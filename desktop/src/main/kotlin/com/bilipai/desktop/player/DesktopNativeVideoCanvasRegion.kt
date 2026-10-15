package com.bilipai.desktop.player

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.awt.EventQueue
import kotlin.math.ceil
import kotlin.math.floor

/** Clip fractions use the complete Compose WINDOW client extent. At final EDT
 * application they map through the actual host client screen rect, then the
 * actual Canvas client screen origin. A stale Compose Canvas position cannot
 * shift the requested visible slot. MPV zoom/aspect/clock/source stay intact. */
internal data class DesktopNativeVideoCanvasClip(
    val left: Double, val top: Double, val right: Double, val bottom: Double,
) {
    init {
        require(listOf(left, top, right, bottom).all { it.isFinite() && it in 0.0..1.0 })
        require(right >= left && bottom >= top)
    }
    val empty: Boolean get() = right == left || bottom == top
    val full: Boolean get() = this == Full

    fun inWindow(metrics: DesktopNativeVideoCanvasMetrics): DesktopNativeVideoCanvasPixels? {
        if (empty) return DesktopNativeVideoCanvasPixels(0, 0, 0, 0)
        val screenLeft = metrics.hostScreenLeft + left * metrics.hostClientWidth
        val screenTop = metrics.hostScreenTop + top * metrics.hostClientHeight
        val screenRight = metrics.hostScreenLeft + right * metrics.hostClientWidth
        val screenBottom = metrics.hostScreenTop + bottom * metrics.hostClientHeight
        val windowLeft = metrics.clientScreenLeft.toLong() - metrics.clientLeft
        val windowTop = metrics.clientScreenTop.toLong() - metrics.clientTop
        if (screenLeft <= windowLeft && screenTop <= windowTop &&
            screenRight >= windowLeft + metrics.windowWidth && screenBottom >= windowTop + metrics.windowHeight) return null
        // Inward rounding never exposes a native pixel beyond the requested clip.
        val x1 = ceil(screenLeft - metrics.clientScreenLeft).toInt().coerceIn(0, metrics.clientWidth)
        val y1 = ceil(screenTop - metrics.clientScreenTop).toInt().coerceIn(0, metrics.clientHeight)
        val x2 = floor(screenRight - metrics.clientScreenLeft).toInt().coerceIn(x1, metrics.clientWidth)
        val y2 = floor(screenBottom - metrics.clientScreenTop).toInt().coerceIn(y1, metrics.clientHeight)
        if (x1 == x2 || y1 == y2) return DesktopNativeVideoCanvasPixels(0, 0, 0, 0)
        return DesktopNativeVideoCanvasPixels(metrics.clientLeft + x1, metrics.clientTop + y1,
            metrics.clientLeft + x2, metrics.clientTop + y2)
    }

    companion object {
        val Full = DesktopNativeVideoCanvasClip(0.0, 0.0, 1.0, 1.0)
        val Empty = DesktopNativeVideoCanvasClip(0.0, 0.0, 0.0, 0.0)
    }
}

/** Win32 regions use WINDOW coordinates; ClientToScreen/GetWindowRect supply
 * the client origin instead of assuming a zero-width nonclient frame or a DPI. */
internal data class DesktopNativeVideoCanvasMetrics(
    val windowWidth: Int, val windowHeight: Int,
    val clientLeft: Int, val clientTop: Int, val clientWidth: Int, val clientHeight: Int,
    val clientScreenLeft: Int = 0, val clientScreenTop: Int = 0,
    val hostScreenLeft: Int = 0, val hostScreenTop: Int = 0,
    val hostClientWidth: Int = clientWidth, val hostClientHeight: Int = clientHeight,
) {
    init {
        require(windowWidth in 1..MAX_REGION_COORDINATE && windowHeight in 1..MAX_REGION_COORDINATE)
        require(clientLeft >= 0 && clientTop >= 0 && clientWidth > 0 && clientHeight > 0)
        require(clientLeft.toLong() + clientWidth <= windowWidth && clientTop.toLong() + clientHeight <= windowHeight)
        require(hostClientWidth in 1..MAX_REGION_COORDINATE && hostClientHeight in 1..MAX_REGION_COORDINATE)
    }
    companion object { const val MAX_REGION_COORDINATE: Int = (1 shl 26) - 1 }
}

internal data class DesktopNativeVideoCanvasPixels(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** The generation belongs to one actual addNotify/removeNotify lifetime. A
 * recycled HWND value alone never identifies the retained private Canvas. */
internal class DesktopNativeVideoCanvasPeer(val handle: Long, val generation: Long) {
    init { require(handle != 0L && generation > 0L) }
    var retired: Boolean = false
        private set
    fun retire() { retired = true }
}

/** Captured before scheduling on EDT. Recovery may keep sourceVersion and the
 * same HWND while changing playbackRevision or the publication object. */
internal class DesktopNativeVideoCanvasLease(
    val owner: Any, val source: OwnedPlaybackSourceSnapshot, val playbackRevision: Long,
    val peer: DesktopNativeVideoCanvasPeer, val hostWindowHandle: Long,
)

internal interface DesktopNativeVideoCanvasRegionApi {
    fun metrics(peer: DesktopNativeVideoCanvasPeer, hostWindowHandle: Long): DesktopNativeVideoCanvasMetrics?
    /** A null rectangle means SetWindowRgn(hwnd, NULL, TRUE). */
    fun set(peer: DesktopNativeVideoCanvasPeer, rectangle: DesktopNativeVideoCanvasPixels?): Boolean
    fun read(peer: DesktopNativeVideoCanvasPeer): Pair<Int, DesktopNativeVideoCanvasPixels?>
}

/** A receipt acknowledges an actual successful SetWindowRgn call only. It is
 * not evidence of displayed video pixels or source/entry/account authority. */
internal class DesktopNativeVideoCanvasRegionReceipt(
    val owner: Any, val peer: DesktopNativeVideoCanvasPeer,
    val clip: DesktopNativeVideoCanvasClip, val pixels: DesktopNativeVideoCanvasPixels?, val hostWindowHandle: Long,
)

/** Caller confines state to its MPV lock; all native operations require EDT.
 * Delayed cleanup carries THIS receipt, so even the same owner and recycled HWND
 * cannot erase a successor installation. No AWT reparenting or MPV call occurs. */
internal class DesktopNativeVideoCanvasRegion(
    private val api: () -> DesktopNativeVideoCanvasRegionApi = { DesktopWin32VideoCanvasRegion },
    private val onEdt: () -> Boolean = EventQueue::isDispatchThread,
) {
    private var installed: DesktopNativeVideoCanvasRegionReceipt? = null
    private var applying = false
    fun captureReceipt(): DesktopNativeVideoCanvasRegionReceipt? = installed

    fun apply(owner: Any, peer: DesktopNativeVideoCanvasPeer, clip: DesktopNativeVideoCanvasClip,
        hostWindowHandle: Long = peer.handle,
        peerIsCurrent: (DesktopNativeVideoCanvasPeer) -> Boolean = { !it.retired },
        stillCurrent: () -> Boolean): Boolean {
        check(onEdt())
        if (applying || peer.retired || !stillCurrent()) return false
        applying = true
        var expired: DesktopNativeVideoCanvasRegionReceipt? = null
        try {
            val native = api()
            val metrics = native.metrics(peer, hostWindowHandle) ?: return false
            val pixels = clip.inWindow(metrics)
            if (peer.retired || !stillCurrent()) return false // Final owner/source/peer gate after reading actual geometry.
            val previous = installed
            if (previous?.owner === owner && previous.peer === peer && previous.hostWindowHandle == hostWindowHandle &&
                previous.clip == clip && previous.pixels == pixels)
                return true
            if (!native.set(peer, pixels)) return false
            // Keep a receipt even if admission retired during the synchronous OS
            // call: exact cleanup must still know which peer we actually changed.
            if (!peer.retired) installed = DesktopNativeVideoCanvasRegionReceipt(owner, peer, clip, pixels, hostWindowHandle)
            val current = !peer.retired && stillCurrent()
            if (!current) expired = installed?.takeIf { it.peer === peer && it.owner === owner }
            return current
        } finally {
            applying = false
            // Retirement may have reentered synchronously inside SetWindowRgn,
            // before the NEW receipt existed. Clean that exact receipt now.
            expired?.let { release(it, peerIsCurrent) }
        }
    }

    fun release(receipt: DesktopNativeVideoCanvasRegionReceipt, peerIsCurrent: (DesktopNativeVideoCanvasPeer) -> Boolean): Boolean {
        check(onEdt())
        if (applying || installed !== receipt || receipt.peer.retired || !peerIsCurrent(receipt.peer)) return false
        applying = true
        try {
            if (!api().set(receipt.peer, null)) return false
            if (installed === receipt) installed = null
            return true
        } finally { applying = false }
    }

    /** Called after the actual peer has been destroyed; never call its HWND. */
    fun peerRetired(peer: DesktopNativeVideoCanvasPeer) {
        check(onEdt())
        peer.retire()
        if (installed?.peer === peer) installed = null
    }

    fun readback(peer: DesktopNativeVideoCanvasPeer): Pair<Int, DesktopNativeVideoCanvasPixels?> {
        check(onEdt())
        return api().read(peer)
    }
}

/** The real player's deferred cleanup lane. Calls enter under its existing
 * native lock; dispatch never waits for EDT. At most one task and one exact
 * receipt are retained, including rapid same-source recovery/stop sequences. */
internal class DesktopNativeVideoCanvasRegionClearDelivery(
    private val region: DesktopNativeVideoCanvasRegion,
    private val withOwnerLock: ((() -> Unit) -> Unit),
    private val peerIsCurrent: (DesktopNativeVideoCanvasPeer) -> Boolean,
    private val dispatch: (() -> Unit) -> Unit = { EventQueue.invokeLater(it) },
) {
    private var pending: DesktopNativeVideoCanvasRegionReceipt? = null
    private var scheduled = false

    fun retireLocked() {
        val receipt = region.captureReceipt() ?: return
        pending = receipt
        if (scheduled) return
        scheduled = true
        dispatch {
            withOwnerLock {
                val captured = pending
                pending = null; scheduled = false
                if (captured != null) region.release(captured, peerIsCurrent)
            }
        }
    }
}

private interface DesktopVideoRegionUser32 : StdCallLibrary {
    fun GetClientRect(hwnd: Pointer, rectangle: Pointer): Boolean
    fun GetWindowRect(hwnd: Pointer, rectangle: Pointer): Boolean
    fun ClientToScreen(hwnd: Pointer, point: Pointer): Boolean
    fun SetWindowRgn(hwnd: Pointer, region: Pointer?, redraw: Boolean): Int
    fun GetWindowRgn(hwnd: Pointer, region: Pointer): Int
}

private interface DesktopVideoRegionGdi32 : StdCallLibrary {
    fun CreateRectRgn(left: Int, top: Int, right: Int, bottom: Int): Pointer?
    fun GetRgnBox(region: Pointer, rectangle: Pointer): Int
    fun DeleteObject(region: Pointer): Boolean
}

private object DesktopWin32VideoCanvasRegion : DesktopNativeVideoCanvasRegionApi {
    private val user32 = Native.load("user32", DesktopVideoRegionUser32::class.java, W32APIOptions.DEFAULT_OPTIONS)
    private val gdi32 = Native.load("gdi32", DesktopVideoRegionGdi32::class.java, W32APIOptions.DEFAULT_OPTIONS)

    override fun metrics(peer: DesktopNativeVideoCanvasPeer, hostWindowHandle: Long): DesktopNativeVideoCanvasMetrics? {
        val hwnd = Pointer(peer.handle)
        Memory(16).use { client -> Memory(16).use { window -> Memory(8).use { origin ->
            client.clear(); window.clear(); origin.clear()
            if (!user32.GetClientRect(hwnd, client) || !user32.GetWindowRect(hwnd, window) ||
                !user32.ClientToScreen(hwnd, origin)) return null
            val width = window.getInt(8).toLong() - window.getInt(0)
            val height = window.getInt(12).toLong() - window.getInt(4)
            val clientWidth = client.getInt(8).toLong() - client.getInt(0)
            val clientHeight = client.getInt(12).toLong() - client.getInt(4)
            val left = origin.getInt(0).toLong() - window.getInt(0)
            val top = origin.getInt(4).toLong() - window.getInt(4)
            val limit = DesktopNativeVideoCanvasMetrics.MAX_REGION_COORDINATE.toLong()
            if (width !in 1..limit || height !in 1..limit || clientWidth !in 1..limit || clientHeight !in 1..limit ||
                left < 0 || top < 0 || left + clientWidth > width || top + clientHeight > height) return null
            Memory(16).use { host -> Memory(8).use { hostOrigin ->
                host.clear(); hostOrigin.clear()
                val root = Pointer(hostWindowHandle)
                if (!user32.GetClientRect(root, host) || !user32.ClientToScreen(root, hostOrigin)) return null
                val hostWidth = host.getInt(8).toLong() - host.getInt(0)
                val hostHeight = host.getInt(12).toLong() - host.getInt(4)
                if (hostWidth !in 1..limit || hostHeight !in 1..limit) return null
                return DesktopNativeVideoCanvasMetrics(width.toInt(), height.toInt(), left.toInt(), top.toInt(),
                    clientWidth.toInt(), clientHeight.toInt(), origin.getInt(0), origin.getInt(4),
                    hostOrigin.getInt(0), hostOrigin.getInt(4), hostWidth.toInt(), hostHeight.toInt())
            } }
        } } }
    }

    override fun set(peer: DesktopNativeVideoCanvasPeer, rectangle: DesktopNativeVideoCanvasPixels?): Boolean {
        val hwnd = Pointer(peer.handle)
        if (rectangle == null) return user32.SetWindowRgn(hwnd, null, true) != 0
        val region = gdi32.CreateRectRgn(rectangle.left, rectangle.top, rectangle.right, rectangle.bottom) ?: return false
        if (user32.SetWindowRgn(hwnd, region, true) != 0) return true
        // https://learn.microsoft.com/windows/win32/api/winuser/nf-winuser-setwindowrgn
        // Success transfers HRGN ownership to Windows; only failure deletes it here.
        gdi32.DeleteObject(region)
        return false
    }

    override fun read(peer: DesktopNativeVideoCanvasPeer): Pair<Int, DesktopNativeVideoCanvasPixels?> {
        val region = gdi32.CreateRectRgn(0, 0, 0, 0) ?: return 0 to null
        try {
            val kind = user32.GetWindowRgn(Pointer(peer.handle), region)
            if (kind == 0) return kind to null // ERROR also denotes no region; never claim display proof from it.
            Memory(16).use { bounds ->
                bounds.clear()
                if (gdi32.GetRgnBox(region, bounds) == 0) return 0 to null
                return kind to DesktopNativeVideoCanvasPixels(bounds.getInt(0), bounds.getInt(4), bounds.getInt(8), bounds.getInt(12))
            }
        } finally { gdi32.DeleteObject(region) }
    }
}
