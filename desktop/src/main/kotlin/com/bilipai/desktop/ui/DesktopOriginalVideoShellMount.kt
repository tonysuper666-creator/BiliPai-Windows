package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.purebilibili.navigation3.BiliPaiNavKey
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicReference

/** Mounted above every physical NavDisplay branch, including MainHost. Required
 * construction functions assemble the already existing concrete effects; an
 * absent function cannot be replaced with a nullable/default platform. Leaf
 * coverage does not cancel the original producer or reconstruct its platform.
 */
@Composable internal fun DesktopOriginalVideoShellMount(
    environment: DesktopOriginalVideoRootWindowEnvironment,
    shell: DesktopOriginalVideoShellOwner,
    createFactory: (DesktopOriginalVideoRootWindowEnvironment) -> DesktopOriginalVideoRootFactory,
    createPlatforms: @Composable (DesktopOriginalVideoRootWindowEnvironment,
        DesktopOriginalVideoOwnerAssembly) -> DesktopOriginalVideoRootWindowPlatforms,
    afterDrain: suspend (DesktopOriginalVideoOwnerAssembly,
        DesktopOriginalVideoRootWindowPlatforms?) -> Unit,
    afterUnconstructedDrain: suspend () -> Unit,
    content: @Composable () -> Unit,
) {
    val factory = remember(environment.root, shell) { createFactory(environment) }
    val windows = remember(environment.root, shell) { AtomicReference<DesktopOriginalVideoRootWindowPlatforms?>() }
    LaunchedEffect(environment.root, shell, factory) {
        shell.install(environment.root.capturedEpoch, factory, windows, { owner ->
            // All VM/native/plugin/request producers are already drained. Window
            // close and IO cleanup run in Root's external scope outside gates.
            val previousWindows = windows.get()
            afterDrain(owner, previousWindows)
            windows.compareAndSet(previousWindows, null)
        }, afterUnconstructedDrain)
    }
    val assembly by shell.slot.assemblies.collectAsState()
    val current = assembly?.takeIf { it.owns() && environment.owns() }
    val platforms = current?.let { createPlatforms(environment, it) }
    SideEffect {
        if (current != null && current === shell.slot.currentAssembly() && environment.owns())
            shell.publishWindows(current, checkNotNull(platforms))
    }
    DisposableEffect(environment.root, shell) { onDispose {
        // Account/root replacement closes admission now; the installing Root
        // effect performs the actual drain before it publishes another factory.
        shell.retire()
    } }
    CompositionLocalProvider(LocalDesktopOriginalVideoRootWindowEnvironment provides environment,
        LocalDesktopOriginalVideoShellOwner provides shell,
        LocalDesktopOriginalVideoRootPlatforms provides platforms,
        LocalDesktopOriginalVideoNativeCarrierActive provides false) {
        Box {
            if (current != null && platforms != null) {
                val nativeState by current.section.nativePlayer.state.collectAsState()
                val destination = environment.currentKey()
                val sourceLeaf = destination is BiliPaiNavKey.VideoDetail || destination is BiliPaiNavKey.AudioMode ||
                    destination is BiliPaiNavKey.Story || destination is BiliPaiNavKey.OfflineVideoPlayer ||
                    destination is BiliPaiNavKey.BangumiPlayer || destination is BiliPaiNavKey.Live ||
                    destination is BiliPaiNavKey.ExternalMedia
                // The same remembered carrier keeps an ordinary source attached
                // while its Holder is covered. It never competes with an active
                // source leaf or with another retained native publication.
                if (!sourceLeaf && (current.native.current() != null ||
                        current.section.nativePlayer.currentSourceSnapshot() == null)) {
                    CompositionLocalProvider(LocalDesktopOriginalVideoNativeCarrierActive provides true) {
                        platforms.InitialNativeSurface(Modifier.size(1.dp))
                    }
                }
            }
            content()
        }
    }
}

internal val LocalDesktopOriginalVideoShellOwner = staticCompositionLocalOf<DesktopOriginalVideoShellOwner> {
    error("Physical Root requires its single ordinary-video Shell owner")
}
internal val LocalDesktopOriginalVideoRootPlatforms = staticCompositionLocalOf<DesktopOriginalVideoRootWindowPlatforms?> { null }
