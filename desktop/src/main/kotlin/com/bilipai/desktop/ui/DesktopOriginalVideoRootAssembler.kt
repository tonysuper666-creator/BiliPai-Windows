package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import coil3.compose.LocalPlatformContext
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.network.*
import com.android.purebilibili.core.store.*
import com.android.purebilibili.feature.video.viewmodel.*
import com.bilipai.desktop.audio.DesktopAudioRepository
import com.bilipai.desktop.cast.DesktopCastController
import com.bilipai.desktop.appearance.DesktopTextClipboard
import com.bilipai.desktop.data.*
import com.bilipai.desktop.danmaku.*
import com.bilipai.desktop.diagnostics.DesktopDiagnostics
import com.bilipai.desktop.download.DesktopDownloadManager
import com.bilipai.desktop.player.*
import com.bilipai.desktop.plugins.*
import com.bilipai.desktop.settings.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.awt.EventQueue
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

/** References to the actual ReadyApp actors, and its real UI intent setters. This
 * object owns neither a player nor a queue, credential store or native window.
 * Optional diagnostics is a real unavailable capability, never a dummy consumer. */
internal class DesktopOriginalVideoRootShellResources(
    val player: MpvPlayer,
    val overlay: DanmakuOverlay,
    val enhancement: DesktopVideoEnhancementSession,
    val pip: PictureInPictureController,
    val subtitleAssets: DesktopSubtitleAssets,
    val downloads: DesktopDownloadManager,
    val cast: DesktopCastController,
    val audioRepository: DesktopAudioRepository,
    val discovery: DesktopDiscoveryRepository,
    val community: DesktopCommunityRepository,
    val fraud: DesktopCommentFraudRoot,
    val app: DesktopOriginalVideoAppResources,
    val captureProtection: DesktopWindowsVideoCaptureOwner,
    val danmakuPreferences: DesktopOriginalDanmakuPreferences,
    val danmakuPresentation: DesktopDanmakuPresentationBinding,
    val clipboard: DesktopTextClipboard,
    val diagnostics: DesktopDiagnostics?,
    val preferences: () -> PlayerPreferences,
    val isFullscreen: () -> Boolean,
    val setFullscreen: (Boolean) -> Unit,
    val updateVolume: (Float) -> Unit,
    val rootAlive: () -> Boolean,
    val feedback: (String) -> Unit,
    val metadata: (DesktopOriginalVideoShellEvent.Metadata) -> Unit,
    val playbackAuthorizationRetired: (DesktopOriginalVideoAuthorizationRetirement) -> Unit,
    val scratchDirectory: Path,
)

/** Concrete per-Home-epoch construction. Every original domain reads the same
 * actual Repository/Runtime/Store/MPV. The small Entry table retains effect views
 * for disposal; it is not another playback state or credential authority. */
internal class DesktopOriginalVideoRootAssembler(
    private val window: DesktopOriginalVideoRootWindowEnvironment,
    private val shell: DesktopOriginalVideoShellOwner,
    private val resources: DesktopOriginalVideoRootShellResources,
    private val comments: DesktopOriginalCommentRootOwner,
    private val commentPlatform: DesktopCommentPlatform,
) {
    private class Entry(
        val gate: DesktopOriginalVideoRootGate,
        val settings: DesktopOriginalPlayerSettingsContext,
        val overlay: DesktopOriginalVideoOverlayWindowsPlatform,
        val download: DesktopOriginalVideoOwnerDownloadBinding,
        val diagnostics: DesktopOriginalVideoOwnerDiagnosticsBinding,
        val displays: DesktopWindowsVideoDisplayCapabilities,
    ) {
        var lyrics: DesktopOriginalAudioLyricsBinding? = null
        var tablet: DesktopOriginalTabletOwnerUploadsPlatform? = null
        val foregroundWindow = AtomicReference<java.awt.Window?>()
        val foregroundIdentity = AtomicReference<Any?>()
        var navigatingToVideo by mutableStateOf(false)
        var entryFromLeft by mutableStateOf(false)
    }
    private val entry = AtomicReference<Entry?>()
    private fun assembly() = checkNotNull(shell.slot.currentAssembly()) { "Original video Assembly is not published" }
    private fun owns(gate: DesktopOriginalVideoRootGate) = window.owns() && resources.rootAlive() && gate.owns()
    private fun diagnostic(message: String) { resources.diagnostics?.record("W", "OriginalVideo", message) }

    val factory = DesktopOriginalVideoRootFactory(window.runtime.context, window.repository,
        window.repository.originalVideoLogin, window.runtime, resources.player, resources.subtitleAssets,
        resources.app.mediaCache, resources.community.searchPreferences, resources.preferences,
        ::createEntry, { retirement ->
            if (shell.slot.currentAssembly() === retirement.owner && retirement.isCurrent())
                resources.playbackAuthorizationRetired(retirement)
        }, { result ->
            if (result is DesktopOriginalMediaCachePreparation.Direct) diagnostic("Media byte transport selected direct origin")
        })

    private fun createEntry(gate: DesktopOriginalVideoRootGate): DesktopOriginalVideoRootEntryPorts {
        check(EventQueue.isDispatchThread())
        fun current() = owns(gate)
        val overlay = DesktopOriginalVideoOverlayWindowsPlatform(window.runtime, window.window, gate.scope,
            ::current, gate::commitEntry, { factory.captureRequest(assembly()) }, {
                val owner = assembly()
                val accepted = owner.native.current() ?: throw CancellationException("Cast source is not accepted")
                val subject = owner.playback.subjectSnapshot.value ?: throw CancellationException("Cast subject is not confirmed")
                require(subject.bvid == accepted.request.bvid && subject.cid == accepted.request.cid)
                DesktopOriginalVideoOverlayCastSource(subject.aid, subject.cid, accepted.nativeSource.source,
                    DesktopRepositoryPlaybackPublication(window.repository),
                    { factory.isPresentationCurrent(owner, accepted) })
            }, resources.cast, window.root.environment.overlays, resources.clipboard,
            resources.diagnostics, resources.feedback)
        val settings = DesktopOriginalPlayerSettingsContext(window.runtime.context, ::current, gate::commit,
            overlay, { com.android.purebilibili.core.util.resolveLargeScreenOrFoldableConfiguration(
                window.preferencesPlatform.deviceDefault.smallestConfigurationWidthDp, hasHingeAngleSensor = false) },
            { DesktopOriginalWindowsBuildPolicy.debuggerAttached() })
        val display = DesktopWindowsVideoDisplayCapabilities(window.window)
        val capabilities = DesktopOriginalMpvPlaybackCapabilities(resources.player, display.state, ::diagnostic)
        val resolver = DesktopOriginalVideoOwnerDownloadResolver(::assembly) { binding, url -> binding.captureMediaCookieHeader(url) }
        val download = DesktopOriginalVideoOwnerDownloadBinding(resources.downloads, settings,
            window.gallery.imageAssets, gate.scope, ::current, gate::commitEntry,
            resolver::resolveCaptured, resolver::captureTask)
        val diagnostics = DesktopOriginalVideoOwnerDiagnosticsBinding(window.runtime.store,
            resources.diagnostics, ::current, gate::commit)
        val danmaku = DesktopOriginalVideoOwnerDanmakuBinding({
            (assembly().invocations.requireRequestRepository() as DesktopOriginalVideoOwnerRequestRepository).binding
        }, { assembly().native }, resources.overlay, { assembly().playback.subjectSnapshot.value }, ::current)
        val effects = DesktopOriginalVideoOwnerEffects(shell.mini, shell.playlist, download,
            DesktopOriginalVideoOwnerNetworkBinding(window.preferencesPlatform, ::current),
            window.repository.originalVideoOwnerCacheView(gate.capturedEpoch, gate::owns, gate::commitEntry),
            diagnostics, diagnostics, danmaku, shell.background)
        check(entry.compareAndSet(null, Entry(gate, settings, overlay, download, diagnostics, display)))
        val progress = resources.app.progress.forEntry({ bvid, cid ->
            val owner = assembly()
            val expected = owner.native.current()?.takeIf { it.request.bvid == bvid && it.request.cid == cid }
                ?: return@forEntry 0L
            var duration = 0L
            owner.native.admitPlaybackDispatch(expected) { duration = owner.section.duration }
            duration
        }, ::current)
        val followOwner = checkNotNull(window.repository.dynamicCacheSessionGuard.dynamicCacheOwner())
        val homeGate = window.root.entry.gate
        val feedbackWrites = DesktopTodayWatchFeedbackWriteBinding(window.root.environment.pluginContext,
            homeGate::owns, homeGate::commit)
        return DesktopOriginalVideoRootEntryPorts(settings, comments, effects, capabilities, progress,
            DesktopOriginalVideoAnalyticsBinding(window.runtime.store, resources.diagnostics, ::current),
            { change ->
                if (!current()) throw CancellationException("Follow confirmation owner retired")
                window.repository.followStateEvents.confirm(followOwner, change)
            }, resources.feedback,
            { control ->
                require(control.nativePlayer === resources.player)
                // Original attachPlayer runs before Slot publishes the Assembly.
                // Root applies its actual preferences to this same MPV at startup;
                // initial native publication also reads the current user mute.
                shell.slot.currentAssembly()?.let { owner ->
                    require(control === owner.section)
                    owner.native.current()?.let { expected -> owner.native.admitPlaybackDispatch(expected) {
                        val preferred = resources.preferences()
                        resources.player.setVolume(preferred.volume)
                        resources.player.setMuted(preferred.muted)
                    } }
                }
            }, { desktopOriginalRootDashRequestsEnabled(settings) },
            { desktopOriginalRootDiagnosticLoggingEnabled(settings) },
            { from, to, delta, duration -> resources.diagnostics?.record("I", "OriginalSeek",
                "from=$from,to=$to,delta=$delta,duration=$duration") },
            { factory.resumeEndedSource(assembly()) },
            { settings.pluginContext.store.snapshot("settings").value[playerBooleanPreferencesKey("exp_auto_1080p")] ?: true },
            { settings.pluginContext.store.snapshot("settings").value[playerBooleanPreferencesKey("bili_directed_traffic")] ?: false },
            { shell.awaitNativeInitialization(gate) }, feedbackWrites)
    }

    @Composable fun Platforms(owner: DesktopOriginalVideoOwnerAssembly): DesktopOriginalVideoRootWindowPlatforms {
        val gate = factory.gate(owner)
        val effect = checkNotNull(entry.get()?.takeIf { it.gate === gate })
        fun current() = owns(gate) && shell.slot.currentAssembly() === owner && owner.owns()
        val windows = remember(owner, effect) {
            DesktopOriginalVideoWindowsWindowPort(window.window, owner, ::current,
                resources.isFullscreen, resources.setFullscreen, resources.pip,
                window.resources.clientPolicy, window.chrome, resources.captureProtection,
                resources.overlay::clearViewportBrightness, { expected, navigationExit ->
                    // Current-entry deferred presentation cleanup only. The retained
                    // source continues when a navigation leaf covers its Holder.
                    if (navigationExit && expected != null && factory.isPresentationCurrent(owner, expected))
                        resources.pip.updateTitle(expected.nativeSource.source.title)
                }, ::diagnostic)
        }
        val presentationScope = resources.danmakuPresentation.currentPresentation().originalScope()
        val presentation by resources.danmakuPreferences.getDanmakuSettings(presentationScope)
            .collectAsState(initial = resources.danmakuPreferences.currentSettings(presentationScope))
        val cloudEnabled by resources.danmakuPreferences.getDanmakuCloudSyncEnabled().collectAsState(initial = true)
        val cloudPlatform = remember(owner, effect) { DesktopDanmakuWindowsPlatform(comments,
            gate.scope, ::current, window.gallery, window.window) }
        val cloud = rememberDesktopOriginalDanmakuCloudSyncBinding(presentation, cloudEnabled,
            owner.environment.account.hasSession, cloudPlatform)
        val latestCloud by rememberUpdatedState(cloud)
        val enhancement = window.runtime.enhancementConfiguration
        val sectionResources = remember(owner, windows, effect, enhancement) {
            DesktopOriginalVideoSectionWindowsResources(owner.native, ::current, resources.pip.active,
                { expected, action -> factory.withPresentationAdmission(owner, expected, action) },
                { expected -> factory.isPresentationCurrent(owner, expected) }, gate.scope, effect.settings,
                resources.danmakuPreferences, resources.overlay, enhancement, resources.enhancement,
                { latestCloud }, window.gallery.imageAssets, resources.scratchDirectory,
                { shell.playlist.currentIndex.value + 1 < shell.playlist.playlist.value.size },
                windows::acquireScreenshotAndOrientationLock, windows::acquireKeepAwake,
                { identity, available ->
                    if (available) effect.foregroundIdentity.set(identity)
                    else effect.foregroundIdentity.compareAndSet(identity, null)
                },
                { foreground, available ->
                    if (available) effect.foregroundWindow.set(foreground)
                    else effect.foregroundWindow.compareAndSet(foreground, null)
                    resources.captureProtection.onNativeWindowAvailability(foreground, available)
                }, { accepted ->
                    DesktopOwnedWebMaskSource(accepted.request.bvid, accepted.request.cid,
                        accepted.sourceVersion, accepted.accountEpoch,
                        { factory.isPresentationCurrent(owner, accepted) }, { bvid, cid ->
                            val request = factory.captureRequest(owner)
                            request.assertCurrent()
                            val keys = request.protocol.getWbiKeys()
                            request.assertCurrent()
                            val params = WbiUtils.sign(mapOf("bvid" to bvid, "cid" to cid.toString()), keys.first, keys.second)
                            val response = request.primaryApi.getPlayerInfo(params)
                            request.assertCurrent()
                            check(response.code == 0) { "视频遮罩元数据不可用: ${response.code}" }
                            checkNotNull(response.data) { "视频遮罩元数据不可用" }
                        })
                }, { accepted, caller ->
                    if (caller.isCancelled || !owner.native.isCurrent(accepted))
                        throw CancellationException("Original section request source retired")
                    // Same original operations/API authority; its fixed epoch and
                    // caller guard survive every actual suspend request boundary.
                    DesktopDynamicCardOperations(window.repository, gate.capturedEpoch,
                        { !caller.isCancelled && factory.isPresentationCurrent(owner, accepted) }, comments.emotes)
                }, { enabled ->
                    if (current()) resources.diagnostics?.record("I", "Danmaku", "enabled=$enabled")
                }, ::diagnostic, window.gallery.imageShare)
        }
        val section = rememberDesktopOriginalVideoSectionWindowsPlatform(sectionResources)
        val playlist = remember(owner) { DesktopOriginalVideoRootAudioPlaylist(shell.playlist, gate.scope) }
        val favorite = remember(owner) { DesktopFavoriteInteractionPreferences(window.runtime.store) }
        val account = remember(owner) { DesktopProfileAccountsBinding(window.repository, gate.capturedEpoch,
            window.repository.account.value?.mid, checkNotNull(gate.scope.coroutineContext[Job]), ::current, gate::commitEntry) }
        val blocked = remember(owner) { DesktopOriginalPortraitBlockedUpsBinding(resources.community.blockedUpRepository,
            effect.settings) { factory.captureRequest(owner) } }
        val rendering = remember(owner, section, windows) { DesktopOriginalVideoRootPortraitRendering(owner, section,
            windows, resources.pip.active, ::current, ::diagnostic,
            { expected, action -> factory.withPresentationAdmission(owner, expected, action) }) }
        val danmaku = remember(owner, section) {
            object : DesktopOriginalPortraitDanmakuPort, DesktopOriginalSectionDanmakuPort by section.danmaku {
                private fun update(action: (Long) -> Boolean) {
                    val accepted = owner.native.current() ?: return
                    factory.withPresentationAdmission(owner, accepted) { action(accepted.sourceVersion) }
                }
                override fun clearForVideoChange() = update(resources.overlay::clearOriginalPortraitDanmaku)
                override fun addLocalDanmaku(text: String, color: Int, mode: Int, fontSize: Int) = update {
                    resources.overlay.addOriginalPortraitDanmaku(it, text, color, mode, fontSize)
                }
                override fun recoverAfterForeground(positionMs: Long, playWhenReady: Boolean, playbackState: Int) = update {
                    resources.overlay.recoverOriginalPortraitDanmaku(it, positionMs, playWhenReady, playbackState)
                }
            }
        }
        val mini = remember(owner) { DesktopOriginalVideoHolderMiniBinding(owner, shell.slot::currentAssembly,
            shell.mini, { resources.pip.active.value }, { effect.navigatingToVideo },
            { _, value -> effect.navigatingToVideo = value }, { value, bvid, deferred ->
                require(value === owner && value.captureLoadState().currentRequest?.bvid == bvid)
                if (!deferred) value.playback.saveCurrentPosition()
            }, { _, left -> effect.entryFromLeft = left }, { value, state ->
                require(value.playback.uiState.value === state) { "Original Mini must retain the same VM Success" }
            }, { value, _ ->
                val accepted = value.native.current() ?: throw CancellationException("Original Mini source retired")
                if (current()) resources.pip.open(window.window, accepted.nativeSource.source.title)
            }) }
        val story = remember(owner) { DesktopOriginalVideoRootStoryFeedBinding(owner, factory::captureRequest) }
        val holder = remember(owner, section, mini, rendering) {
            DesktopOriginalVideoRootHolderPlatform(owner, gate, section, effect.settings, window.settings,
                favorite, account, effect.download, DesktopOriginalVideoConsumedViews(owner), commentPlatform,
                windows, playlist, danmaku, mini, factory.sourceVersions(owner),
                { resources.captureProtection.orientationLocked }, { resources.preferences().muted },
                factory.entryPorts(owner).applyPreferredVolume, resources.feedback,
                window.root.environment.analytics::logScreenView,
                { _, action -> if (current()) resources.diagnostics?.record("I", "VideoPip", "action=$action") },
                story::getStoryFeed, { resources.diagnostics?.let { diagnostics ->
                    gate.scope.launch { val path = withContext(Dispatchers.IO) {
                        diagnostics.exportOriginalPlayerReport(diagnostics.viewLocal(), ::current, gate::commit)
                    }; if (path != null && current()) window.gallery.shareText(path) }
                } ?: resources.feedback("本地诊断未启用") },
                { enabled -> gate.scope.launch { desktopOriginalRootSetDiagnosticLogging(effect.settings, enabled) }; Unit },
                { listener -> resources.overlay.acquireOriginalDanmakuClickListener(::current, listener,
                    effect.foregroundWindow::get, { window.repository.account.value?.mid ?: 0L },
                    { version, action ->
                        owner.native.current()?.takeIf { it.sourceVersion == version }?.let {
                            factory.withPresentationAdmission(owner, it, action)
                        } ?: false
                    }) },
                { _, _, _ ->
                    val success = owner.playback.uiState.value as? VideoPlaybackUiState.Success
                    if (success != null) shell.mini.syncCurrentVideoInfo(success)
                }, section::awaitNativeInitialization)
        }
        val portrait = remember(owner, section, rendering) { DesktopOriginalPortraitPlatformBinding(owner,
            shell.slot::currentAssembly, section, window.root.entry.requests.ports.video,
            window.root.entry.requests.environment.api,
            window.repository.ownedHomeService(SpaceApi::class.java, "https://api.bilibili.com/", gate.capturedEpoch, ::current),
            factory::captureRequest, window.repository.followStateEvents.changes
                .filter { it.owner.epoch == gate.capturedEpoch && current() && window.repository.followStateEvents.isCurrent(it.owner) }
                .map { it.change }, resources.app.mediaCache, { request, url -> request.captureMediaCookieHeader(url) },
            favorite.getQuickSaveDefaultFolder(), blocked, danmaku,
            { window.runtime.plugins.value.filter { it.enabled }.map { it.plugin }
                .filterIsInstance<com.android.purebilibili.feature.plugin.PlaybackCdnPlugin>().firstOrNull() },
            rendering::acquirePresentation, rendering::acquireDanmakuPlayer,
            rendering::releasePager, rendering::recover,
            { _, text -> window.gallery.shareText(text) }, { _, text -> resources.feedback(text) },
            rendering::Surface, rendering::Viewport, rendering::DanmakuSurface,
            { result -> if (result is DesktopOriginalMediaCachePreparation.Direct) diagnostic("Portrait byte transport selected direct origin") },
            { expected, plan, isPresenterCurrent -> factory.acceptedMedia(owner, expected, plan, isPresenterCurrent) }) }
        val storage = remember(owner) { DesktopOriginalMusicStorageBinding(effect.settings) }
        val external = remember(owner) { DesktopOriginalExternalPlaylistBinding(effect.settings, resources.audioRepository) {
            factory.captureRequest(owner)
        } }
        val reducedMotion = rememberDesktopDynamicReduceMotion()
        val latestReducedMotion by rememberUpdatedState(reducedMotion)
        val reduceMotion = remember(owner) { snapshotFlow { latestReducedMotion }
            .stateIn(gate.scope, SharingStarted.Eagerly, reducedMotion) }
        val imageContext = LocalPlatformContext.current
        val imageLoader = LocalDesktopApplicationImageLoader.current.imageLoader
        val music = remember(owner) { DesktopOriginalVideoRootMusicBindings(owner, gate, effect.settings,
            window.settings.homeSettings, reduceMotion, shell.background, storage, external,
            imageContext, imageLoader, resources.updateVolume) }
        val lyrics = remember(owner) { DesktopOriginalAudioLyricsBinding(effect.settings, resources.audioRepository.lyrics,
            gate.scope) { bvid, cid -> captureDesktopOriginalMusicSource(owner, gate, bvid, cid) }.also { effect.lyrics = it } }
        val tablet = remember(owner) { DesktopOriginalTabletOwnerUploadsPlatform(gate.scope, ::current, gate::commitEntry,
            { factory.captureRequest(owner) }, window.repository.followStateEvents.changes
                .filter { it.owner.epoch == gate.capturedEpoch && current() }.map { it.change }, resources.feedback)
            .also { effect.tablet = it } }
        val content = remember(owner) { DesktopOriginalVideoContentBindings(effect.settings, window.settings,
            window.root.environment.pluginContext, blocked, ::current, gate::commit,
            window.root.entry.requests.ports.actions::toggleWatchLater, resources.feedback,
            { _, text -> window.gallery.shareText(text) }, factory.entryPorts(owner).todayWatchFeedback) }
        val subtitle = remember(owner) { DesktopOriginalSubtitleModeBinding({ it === owner.playback && current() },
            { _, action -> gate.commit { if (current()) action() } }) }
        val fullscreen = remember(owner, section, windows) { DesktopOriginalVideoRootFullscreenPlatform(owner,
            section, windows, { player -> rendering.acquireDanmakuPlayer(owner, player) }, { resources.pip.active.value },
            { checkNotNull(shell.videoRouteState(owner)).predictiveBackCancelRecoveryGeneration }) }
        val audioSession = remember(owner) { DesktopOriginalNowPlayingSessionView(::current, gate::commit) }
        val landscape = remember(owner) { window.configuration.map { it.screenWidthDp > it.screenHeightDp }
            .stateIn(gate.scope, SharingStarted.Eagerly,
                window.configuration.value.screenWidthDp > window.configuration.value.screenHeightDp) }
        val audio = remember(owner, holder) { object : DesktopOriginalAudioModePlatform {
            override val window = object : DesktopOriginalAudioWindowPort {
                override val isLandscape = landscape
                override val pipAvailable get() = windows.supportsPictureInPicture
                override fun enterPip() {
                    val accepted = owner.native.current() ?: return
                    if (current()) resources.pip.open(this@DesktopOriginalVideoRootAssembler.window.window,
                        accepted.nativeSource.source.title)
                }
                override fun setRequestedOrientation(value: DesktopOriginalAudioOrientation) =
                    windows.presentation.requestOrientation(if (value == DesktopOriginalAudioOrientation.LANDSCAPE) 0 else 1, null)
                override fun orientationLease() = windows.captureEntryWindowChrome()
            }
            override val playlist = playlist
            override val session = audioSession
            override val mini = object : DesktopOriginalAudioMiniPort {
                override fun setVideoInfo(bvid: String, title: String, cover: String, owner: String, cid: Long, aid: Long,
                    externalPlayer: DesktopOriginalMpvSectionControl) = mini.setVideoInfo(bvid,title,cover,owner,cid,aid,externalPlayer)
            }
            override val lyrics = lyrics
            override val engagement get() = owner.domains.engagement
            override val supplement get() = owner.domains.supplement
            override val composer = object : DesktopOriginalAudioComposerPort {
                override fun bindSubject(subject: VideoSubjectSnapshot) = owner.domains.composer.bindSubject(subject)
            }
            override val comments get() = owner.domains.comments
            override val favoriteInteraction = favorite
            override fun applyPreferredVolume(player: DesktopOriginalMpvSectionControl) = holder.applyPreferredVolume(player)
            @Composable override fun standalonePlayerState(viewModel: DesktopOriginalAudioVideoOwner, bvid: String, cid: Long,
                fallbackResumePositionMs: Long): DesktopOriginalMpvVideoPlayerState =
                key(viewModel) {
                    holder.BindPlayerState(bvid, cid, fallbackResumePositionMs, false, true, true,
                        desktopLoadVideo = { autoPlay ->
                            viewModel.loadInitialAudioVideo(bvid, cid, autoPlay, fallbackResumePositionMs)
                        })
                }
        } }
        val textShare = checkNotNull(LocalDesktopTextShareBindings.current) {
            "Original PGC player requires the actual Root text share binding"
        }
        val fullscreenState = remember(owner, effect) {
            snapshotFlow { resources.isFullscreen() }.stateIn(gate.scope, SharingStarted.Eagerly, resources.isFullscreen())
        }
        val bangumiPlayer = remember(owner, section, holder, portrait, windows, textShare, fullscreenState) {
            DesktopOriginalBangumiPlayerRootOwner(owner, shell, window, factory, resources,
                section, holder, portrait, windows, textShare, fullscreenState)
        }
        return remember(owner, section, holder, tablet, music, portrait, audio, fullscreen, subtitle, bangumiPlayer) {
            DesktopOriginalVideoRootWindowPlatformsImpl(owner, section, holder, fullscreen, tablet, audio, music,
                content, portrait, subtitle, bangumiPlayer, windows, effect.displays, resources.captureProtection::refresh)
        }
    }

    suspend fun afterDrain(owner: DesktopOriginalVideoOwnerAssembly, platforms: DesktopOriginalVideoRootWindowPlatforms?) {
        val previous = entry.get()?.takeIf { it.gate === factory.gate(owner) } ?: return
        // Every existing child producer is drained before native/window resources
        // and references are released. No Store/native monitor is held here.
        previous.overlay.close()
        previous.tablet?.close()
        check(previous.lyrics?.closeCancelJoin() != false) { "Original lyric IO has not drained" }
        withContext(Dispatchers.Main) {
            (platforms as? AutoCloseable)?.close() ?: previous.displays.close()
            resources.captureProtection.refresh()
            if (!resources.rootAlive() || window.repository.sessionEpoch != window.root.capturedEpoch ||
                !window.root.isCurrentOwner()) com.android.purebilibili.feature.audio.player.AudioNowPlayingSession.dismiss()
        }
        check(entry.compareAndSet(previous, null))
    }

    /** Only called after Slot has retired and joined the failed gate. Partial
     * construction remains referenced until every child producer has drained. */
    suspend fun afterUnconstructedDrain() {
        val previous = entry.get() ?: return
        check(!previous.gate.owns())
        previous.overlay.close()
        previous.tablet?.close()
        check(previous.lyrics?.closeCancelJoin() != false) { "Original lyric IO has not drained" }
        withContext(Dispatchers.Main) {
            previous.displays.close()
            resources.captureProtection.refresh()
        }
        check(entry.compareAndSet(previous, null))
    }
}

/** Windows does not have Android BuildConfig.DEBUG. The diagnostic-default
 * mapping explicitly uses a real JVM debug session; release runs are not debug. */
internal object DesktopOriginalWindowsBuildPolicy {
    fun debuggerAttached() = java.lang.management.ManagementFactory.getRuntimeMXBean().inputArguments
        .any { it.startsWith("-agentlib:jdwp") || it.startsWith("-Xrunjdwp") }
}
