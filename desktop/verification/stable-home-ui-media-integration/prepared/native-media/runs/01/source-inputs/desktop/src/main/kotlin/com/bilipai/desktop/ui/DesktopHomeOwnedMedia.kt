package com.bilipai.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.bilipai.desktop.player.MpvPlayer
import com.bilipai.desktop.player.MpvSoftwareFrame
import com.bilipai.desktop.player.MpvSoftwareTarget
import com.bilipai.desktop.player.PlaybackSource
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Image as SkiaImage
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One Home Root owner's media slots, using the SAME capturedEpoch/atomic admission as its data owner.
 * sourceForUrl supplies an immutable existing PlaybackSource/header policy; this adapter fetches no API.
 * It has no reference to Root's main/audio player and cannot stop or acquire either of them.
 */
internal class DesktopHomeMediaLifetime(
    private val scope: CoroutineScope,
    private val isCurrent: () -> Boolean,
    private val commitIfCurrent: ((() -> Unit) -> Boolean),
    private val sourceForUrl: (String) -> PlaybackSource,
) : AutoCloseable {
    private val gate = Any()
    private var closed = false
    private val leases = LinkedHashSet<DesktopHomeMediaLease>()
    private var watch: Job? = null
    init { watch = scope.launch {
        try { while (isCurrent()) delay(50L) }
        finally { withContext(NonCancellable + Dispatchers.IO) { close() } }
    } }

    internal fun open(url: String, muted: Boolean): DesktopHomeMediaLease? {
        var result: DesktopHomeMediaLease? = null
        var failed: DesktopHomeMediaLease? = null
        // Store/Root admission -> media lifetime -> private player/target; close NEVER retains that admission.
        try { commitIfCurrent {
            synchronized(gate) {
                if (!closed && isCurrent()) {
                    val source = sourceForUrl(url)
                    val target = MpvSoftwareTarget()
                    val player = MpvPlayer(softwareTarget = target)
                    try {
                        player.setMuted(muted)
                        player.setLoop(true)
                        player.setSubtitlesVisible(false)
                        player.setVideoPanscan(1.0) // Original preview/wallpaper ZOOM, rendered into actual viewport.
                        val version = player.loadVersioned(source)
                        val lease = DesktopHomeMediaLease(this, player, target, version)
                        failed = lease
                        leases += lease
                        player.startSoftwareTransport()
                        result = lease
                        failed = null
                    } catch (failure: Throwable) {
                        target.close()
                        // No running native session exists before startSoftwareTransport; close cleanup belongs outside admission.
                        if (failed == null) failed = DesktopHomeMediaLease(this, player, target, player.currentSourceVersion)
                        throw failure
                    }
                }
            }
        } } finally { failed?.close() }
        return result
    }

    internal fun current(lease: DesktopHomeMediaLease): Boolean = isCurrent() && synchronized(gate) {
        !closed && lease in leases && lease.player.ownsSourceVersion(lease.sourceVersion)
    }
    internal fun commit(lease: DesktopHomeMediaLease, block: () -> Unit): Boolean {
        var ran = false
        val admitted = commitIfCurrent {
            synchronized(gate) { if (!closed && lease in leases && lease.player.ownsSourceVersion(lease.sourceVersion)) { block(); ran = true } }
        }
        return admitted && ran
    }
    internal fun forget(lease: DesktopHomeMediaLease) { synchronized(gate) { leases.remove(lease) } }
    override fun close() {
        val owned = synchronized(gate) {
            if (closed) return
            closed = true; leases.toList().also { leases.clear() }
        }
        // Never join an MPV worker while holding Store/lifetime/player/target monitor.
        owned.forEach { it.close() }
        watch?.cancel()
    }
}

internal class DesktopHomeMediaLease internal constructor(private val owner: DesktopHomeMediaLifetime,
    internal val player: MpvPlayer, private val target: MpvSoftwareTarget, val sourceVersion: Long) : AutoCloseable {
    private val retired = AtomicBoolean(false)
    val frames: StateFlow<MpvSoftwareFrame?> get() = target.frames
    fun isCurrent(): Boolean = !retired.get() && owner.current(this)
    fun commit(block: () -> Unit): Boolean = !retired.get() && owner.commit(this, block)
    fun resize(width: Int, height: Int) { if (isCurrent()) target.resize(width, height) }
    fun setPlaying(playing: Boolean) { commit { player.setPaused(!playing) } }
    override fun close() {
        if (retired.compareAndSet(false, true)) {
            target.close(); owner.forget(this)
            player.stopIfSourceVersion(sourceVersion)
            player.close() // Only this lease's own private same-Mpv instance.
        }
    }
}

/** CPU render result is carried as a Compose image, so original alpha/clip/blur/Haze modifiers apply. */
@Composable
internal fun DesktopHomeMpvTexture(owner: DesktopHomeMediaLifetime, url: String, muted: Boolean,
    playbackEnabled: Boolean, onFirstFrame: (() -> Unit)?, modifier: Modifier) {
    val callback by rememberUpdatedState(onFirstFrame)
    var lease by remember(owner, url, muted) { mutableStateOf<DesktopHomeMediaLease?>(null) }
    LaunchedEffect(owner, url, muted) {
        var opened: DesktopHomeMediaLease? = null
        try {
            withContext(Dispatchers.IO) { opened = owner.open(url, muted) }
            lease = opened
            awaitCancellation()
        } finally { lease = null; withContext(NonCancellable + Dispatchers.IO) { opened?.close() } }
    }
    val own = lease
    val frame = own?.frames?.collectAsState()?.value?.takeIf { own.isCurrent() && it.sourceVersion == own.sourceVersion }
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val playing = playbackEnabled && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    LaunchedEffect(own, playing) { own?.setPlaying(playing) }
    val image = remember(frame) { frame?.let {
        SkiaImage.makeRaster(ImageInfo(it.width, it.height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE), it.opaqueBgra, it.rowBytes)
    } }
    DisposableEffect(image) { onDispose { image?.close() } }
    // This is a real successful libmpv CPU render receipt, not HWND presentation/onRenderedFirstFrame simulation.
    LaunchedEffect(own, frame?.sourceVersion) { if (image != null) own?.commit { callback?.invoke() } }
    var viewport by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    LaunchedEffect(own, viewport) { own?.resize(viewport.width, viewport.height) }
    Box(modifier.onSizeChanged { viewport = it }) {
        if (image != null && own?.isCurrent() == true) Image(image.toComposeImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
    }
}

/** Root uses this one owner for both preview and wallpaper. The URI MIME resolution remains a required real port. */
internal class DesktopHomeActualMediaPorts(
    private val owner: DesktopHomeMediaLifetime,
    private val isVideoWallpaper: (String) -> Boolean,
    private val imageWallpaper: @Composable (String, Any, Boolean, Modifier) -> Unit,
    musicOverlayVisible: StateFlow<Boolean>,
    feedback: (String) -> Unit,
) {
    val ports = DesktopHomeMediaPorts(musicOverlayVisible,
        { url, muted, firstFrame, modifier -> DesktopHomeMpvTexture(owner, url, muted, true, firstFrame, modifier) }, feedback,
        object : DesktopHomeWallpaperPort {
            @Composable override fun wallpaperSurface(uri: String, imageModel: Any, playbackEnabled: Boolean, modifier: Modifier) {
                if (isVideoWallpaper(uri)) DesktopHomeMpvTexture(owner, uri, true, playbackEnabled, null, modifier)
                else imageWallpaper(uri, imageModel, playbackEnabled, modifier)
            }
        })
}
