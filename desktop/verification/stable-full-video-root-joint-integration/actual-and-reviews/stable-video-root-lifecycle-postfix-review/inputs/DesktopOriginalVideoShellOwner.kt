package com.bilipai.desktop.ui

import com.android.purebilibili.feature.video.subtitle.SubtitleAutoPreference
import com.android.purebilibili.feature.video.subtitle.SubtitleDisplayMode
import com.android.purebilibili.feature.video.viewmodel.VideoPlaybackUiState
import com.bilipai.desktop.data.*
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.awt.EventQueue
import java.util.concurrent.atomic.AtomicReference

/** The physical Shell's ONE ordinary-video command owner. The original playlist
 * belongs to the app Window; the Assembly belongs to the current account epoch.
 * A covered/disposed navigation leaf cannot create, close or replace either.
 * The old DesktopPlaybackController is never constructed by a fresh Shell.
 */
internal class DesktopOriginalVideoShellOwner(
    repository: DesktopRepository,
    globalStore: DesktopPluginStore,
    private val scope: CoroutineScope,
    private val rootAlive: () -> Boolean,
    private val rootAdmission: ((() -> Unit) -> Boolean),
    private val metadata: (DesktopOriginalVideoShellEvent.Metadata) -> Unit,
    private val feedback: (String) -> Unit,
) {
    private data class Bound(val factory: DesktopOriginalVideoRootFactory,
        val windows: AtomicReference<DesktopOriginalVideoRootWindowPlatforms?>,
        val afterDrain: suspend (DesktopOriginalVideoOwnerAssembly) -> Unit)
    private val bound = AtomicReference<Bound?>()
    private data class WindowRegistration(val owner: DesktopOriginalVideoOwnerAssembly,
        val gate: DesktopOriginalVideoRootGate, val platforms: DesktopOriginalVideoRootWindowPlatforms)
    private val windowAvailability = MutableStateFlow<WindowRegistration?>(null)
    private class VideoRouteFrame(val owner: DesktopOriginalVideoOwnerAssembly,
        val state: DesktopOriginalVideoHolderRouteState)
    private val videoRouteFrame = AtomicReference<VideoRouteFrame?>()
    fun publishVideoRouteState(owner: DesktopOriginalVideoOwnerAssembly, state: DesktopOriginalVideoHolderRouteState) {
        check(EventQueue.isDispatchThread())
        if (slot.currentAssembly() === owner && owner.owns()) videoRouteFrame.set(VideoRouteFrame(owner, state))
    }
    fun videoRouteState(owner: DesktopOriginalVideoOwnerAssembly): DesktopOriginalVideoHolderRouteState? =
        videoRouteFrame.get()?.takeIf { it.owner === owner && slot.currentAssembly() === owner && owner.owns() }?.state
    val slot = DesktopOriginalVideoRootOwnerSlot(repository, scope, rootAlive)
    val background = MutableStateFlow(false)
    val playlist = DesktopOriginalVideoPlaylistBinding(globalStore, scope, rootAlive, rootAdmission) {
        feedback("播放队列未能保存，请检查本地存储")
    }
    val mini = DesktopOriginalVideoMiniBinding(slot::currentAssembly,
        { owner, success ->
            owner.native.current()?.let { expected ->
                metadata(DesktopOriginalVideoShellEvent.Metadata(owner, expected,
                    success.info.title, success.info.owner.name, success.info.pic))
            }
        }, rootAlive)

    private fun windows(value: DesktopOriginalVideoOwnerAssembly) =
        bound.get()?.windows?.get()?.takeIf { slot.currentAssembly() === value && value.owns() }

    val playback = DesktopUnifiedPlaybackFacade(scope, slot.assemblies, slot::currentAssembly,
        slot::requireAssembly, playlist, rootAlive, background, slot::retireAssembly,
        resolveCastSource = { expected, success ->
            // Original remote data only: cache loopback/carrier URLs must not be
            // exported as the independently authorized Cast media publication.
            val source = expected.nativeSource.source
            if (source.authorizationReceipt == null || success.info.bvid != expected.request.bvid ||
                success.info.cid != expected.request.cid) null
            else PlaybackSource(videoUrl = source.videoUrl, audioUrl = source.audioUrl,
                referer = source.referer, cookieHeader = source.cookieHeader,
                title = source.title, quality = success.currentQuality,
                availableQualities = success.qualityIds.mapIndexed { index, id -> PlaybackQuality(id,
                    success.qualityLabels.getOrNull(index) ?: id.toString()) },
                authorizationReceipt = source.authorizationReceipt,
                cachedDashData = success.cachedDash,
                progressiveSegments = source.progressiveSegments)
        },
        setSubtitleMode = { value, mode -> windows(value)?.subtitleMode?.set(value.playback, mode) == true },
        changeSubtitleAutoPreference = { value, preference ->
            value.environment.scope.launch {
                if (value.owns()) com.android.purebilibili.core.store.DesktopOriginalPlayerSectionSettings
                    .setSubtitleAutoPreference(value.environment.settings, preference)
            }
        },
        cancelPendingPlayback = { value -> value.playback.cancelDesktopPendingPlayback() },
        resolveQueueCard = { value, card, owns ->
            checkNotNull(bound.get()).factory.resolveQueueCard(value, card, owns)
        }, onFailure = feedback)

    /** The compositor passes its actual per-epoch Root factory, never a latest
     * credential view or optional fallback. Installation/drain occurs outside
     * Store, entry and native locks, on the existing Root coroutine scope.
     */
    suspend fun install(epoch: Long, factory: DesktopOriginalVideoRootFactory,
        windowReference: AtomicReference<DesktopOriginalVideoRootWindowPlatforms?>,
        afterDrain: suspend (DesktopOriginalVideoOwnerAssembly) -> Unit,
        afterUnconstructedDrain: suspend () -> Unit) {
        val next = Bound(factory, windowReference, afterDrain)
        slot.install(epoch, factory::create, factory::beforeRetire, { value ->
            afterDrain(value)
            factory.afterDrain(value)
        }, afterUnconstructedDrain)
        currentCoroutineContext().ensureActive()
        if (!rootAlive()) throw CancellationException("Ordinary Shell binding retired")
        bound.set(next)
    }

    /** All caller commands use the same installed references. The short read is
     * deliberately separate from actual native/Window IO and shutdown joins. */
    fun requireWindows(): DesktopOriginalVideoRootWindowPlatforms {
        check(EventQueue.isDispatchThread())
        val value = slot.requireAssembly()
        return checkNotNull(windows(value)) { "Actual original Holder Window binding is not mounted" }
    }
    fun factoryFor(owner: DesktopOriginalVideoOwnerAssembly): DesktopOriginalVideoRootFactory =
        checkNotNull(bound.get()?.factory?.takeIf { slot.currentAssembly() === owner && owner.owns() })
    fun publishWindows(owner: DesktopOriginalVideoOwnerAssembly, platforms: DesktopOriginalVideoRootWindowPlatforms) {
        check(EventQueue.isDispatchThread())
        val binding = checkNotNull(bound.get())
        if (slot.currentAssembly() !== owner || !owner.owns()) return
        val gate = binding.factory.gate(owner)
        if (!gate.owns()) return
        binding.windows.set(platforms)
        windowAvailability.value?.let { previous ->
            if (previous.owner === owner && previous.gate === gate && previous.platforms === platforms) return
        }
        windowAvailability.value = WindowRegistration(owner, gate, platforms)
    }
    suspend fun awaitNativeInitialization(gate: DesktopOriginalVideoRootGate) {
        val registration = windowAvailability.first { registration ->
            currentCoroutineContext().ensureActive()
            if (!gate.owns()) throw CancellationException("Original native Window bootstrap retired")
            registration?.gate === gate && registration.owner.owns() && slot.currentAssembly() === registration.owner
        }!!
        registration.platforms.awaitNativeInitialization()
        currentCoroutineContext().ensureActive()
        if (!gate.owns() || windowAvailability.value !== registration || !registration.owner.owns())
            throw CancellationException("Original native Window initialization replaced")
    }
    fun navigationReady() = rootAlive() && slot.factoryReady.value
    /** Root SMTC/keys are commands on THIS accepted ordinary publication. Other
     * retained media still use their existing separately admitted owner. */
    fun play(): Boolean {
        val owner = slot.currentAssembly() ?: return false
        val expected = owner.native.current() ?: return false
        return owner.native.admitPlaybackDispatch(expected) { owner.section.play() }
    }
    fun togglePause(): Boolean {
        val owner = slot.currentAssembly() ?: return false
        val expected = owner.native.current() ?: return false
        return owner.native.admitPlaybackDispatch(expected) {
            if (owner.section.playWhenReady) owner.section.pause() else owner.section.play()
        }
    }
    fun admitNative(action: (com.bilipai.desktop.player.MpvPlayer) -> Unit): Boolean {
        val owner = slot.currentAssembly() ?: return false
        val expected = owner.native.current() ?: return false
        return owner.native.admitPlaybackDispatch(expected) { action(owner.section.nativePlayer) }
    }
    fun retire() = slot.retire()
    suspend fun closeAndJoin() = withContext(NonCancellable) {
        slot.closeAndJoin()
        playback.close()
        playlist.closeAndJoin()
        bound.set(null)
        windowAvailability.value = null
        videoRouteFrame.set(null)
    }
}
