package com.bilipai.desktop.ui

import com.android.purebilibili.navigation3.BiliPaiNavKey
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Optional in-process test observation of the already drawn, currently owned Root. */
internal object DesktopOriginalRootValidationTap {
    internal data class Frame(
        val serial: Long,
        val key: BiliPaiNavKey,
        val pagerHosted: Boolean,
        val handle: DesktopReadyOriginalRootHandle,
        val routes: DesktopOriginalRootRouteAssembly,
    )

    private val observer = AtomicReference<((Frame) -> Unit)?>(null)
    private val serial = AtomicLong()

    fun install(callback: (Frame) -> Unit): AutoCloseable {
        val token = requireNotNull(System.getProperty("bilipai.rootValidationToken"))
        UUID.fromString(token)
        val local = Path.of(requireNotNull(System.getenv("LOCALAPPDATA"))).toRealPath(LinkOption.NOFOLLOW_LINKS)
        val temp = Path.of(System.getProperty("java.io.tmpdir")).toRealPath(LinkOption.NOFOLLOW_LINKS)
        require(local.startsWith(temp) && local.fileName.toString().startsWith("BiliPai-v025-root-routes-"))
        val marker = local.resolve(".bilipai-root-validation")
        require(!Files.isSymbolicLink(marker) && Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS))
        require(Files.readString(marker) == token)
        check(observer.compareAndSet(null, callback)) { "A Root validation observer is already installed" }
        return AutoCloseable { observer.compareAndSet(callback, null) }
    }

    fun frame(key: BiliPaiNavKey, pagerHosted: Boolean,
        handle: DesktopReadyOriginalRootHandle, routes: DesktopOriginalRootRouteAssembly) {
        val callback = observer.get() ?: return
        if (!handle.isActive() || !routes.owns() || handle.route.get() !== routes) return
        callback(Frame(serial.incrementAndGet(), key, pagerHosted, handle, routes))
    }
}
