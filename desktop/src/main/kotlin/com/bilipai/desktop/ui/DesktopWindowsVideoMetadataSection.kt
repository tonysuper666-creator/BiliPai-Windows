package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import com.android.purebilibili.core.store.DesktopOriginalVideoMetadataSettings
import com.android.purebilibili.data.model.response.ViewInfo
import com.android.purebilibili.data.repository.FollowStateChange
import com.android.purebilibili.feature.video.playback.loader.PlaybackRequest
import com.android.purebilibili.feature.video.playback.session.PlaybackSessionState
import com.android.purebilibili.feature.video.ui.section.CreatorTeamSection
import com.android.purebilibili.feature.video.ui.section.DesktopOriginalVideoHonors
import com.android.purebilibili.feature.video.ui.section.shouldShowCreatorTeamSection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import java.util.concurrent.atomic.AtomicBoolean

/** Original committed metadata must still belong to this exact load and part. */
internal fun desktopWindowsVideoMetadataMatchesSource(
    info: ViewInfo,
    request: PlaybackRequest,
    session: PlaybackSessionState,
    loadToken: Long,
): Boolean = info.bvid == request.bvid && info.cid == request.cid &&
    session.currentBvid == request.bvid && session.currentCid == request.cid &&
    session.currentLoadRequestToken == loadToken

/** A panel lifetime over the existing source/account authority, never a follow manager.
 * Closing the panel cannot be undone by a later route with equal metadata. */
internal class DesktopWindowsVideoMetadataLease(private val stillOwned: () -> Boolean) : AutoCloseable {
    private val alive = AtomicBoolean(true)
    fun isOwned(): Boolean = alive.get() && stillOwned()
    private fun assertOwned() {
        if (!isOwned()) throw CancellationException("Windows video metadata panel retired")
    }

    fun creatorTeam(delegate: DesktopCreatorTeamBindings): DesktopCreatorTeamBindings =
        object : DesktopCreatorTeamBindings {
            override val followStateChanges: Flow<FollowStateChange> = delegate.followStateChanges.onEach {
                currentCoroutineContext().ensureActive()
                assertOwned()
            }
            override suspend fun checkFollowStatus(mid: Long): Boolean {
                currentCoroutineContext().ensureActive()
                assertOwned()
                return delegate.checkFollowStatus(mid).also {
                    currentCoroutineContext().ensureActive()
                    assertOwned()
                }
            }
            override suspend fun followUser(mid: Long, follow: Boolean): Result<Boolean> {
                currentCoroutineContext().ensureActive()
                assertOwned()
                return delegate.followUser(mid, follow).also { result ->
                    currentCoroutineContext().ensureActive()
                    assertOwned()
                    (result.exceptionOrNull() as? CancellationException)?.let { throw it }
                }
            }
        }

    override fun close() { alive.set(false) }
}

/** Full original honors/declarations/team content in the Windows introduction panel.
 * The existing portrait facet owns requests, confirmed follow events and mutations. */
@Composable internal fun DesktopWindowsVideoMetadataSection(
    assembly: DesktopOriginalVideoOwnerAssembly,
    info: ViewInfo,
    sourceOwner: DesktopOriginalVideoAcceptedPublication,
    settings: DesktopOriginalPlayerSettingsContext,
    creatorTeam: DesktopCreatorTeamBindings,
    stillOwned: () -> Boolean,
    onMember: (Long) -> Unit,
    onHonor: (String, () -> Boolean) -> Unit,
) {
    // A full original honor snapshot, without unrelated statistics or title churn.
    // A closed lifetime remains closed even if an equal honor later reappears.
    val honorSnapshot = info.honorReply?.let { it.copy(honor = it.honor.toList()) }
    key(assembly, sourceOwner, honorSnapshot) {
        val capturedHonors = remember { honorSnapshot }
        val latestOwned by rememberUpdatedState(stillOwned)
        val latestMember by rememberUpdatedState(onMember)
        val latestHonor by rememberUpdatedState(onHonor)
        val loadToken = remember { assembly.playback.captureDesktopLoadState().currentLoadRequestToken }
        val lease = remember {
            DesktopWindowsVideoMetadataLease {
                latestOwned() && assembly.owns() && assembly.native.isCurrent(sourceOwner) &&
                    (assembly.playback.captureDesktopPlaybackState() as?
                        com.android.purebilibili.feature.video.viewmodel.VideoPlaybackUiState.Success)?.let { current ->
                        current.info.honorReply == capturedHonors &&
                            desktopWindowsVideoMetadataMatchesSource(current.info, sourceOwner.request,
                                assembly.playback.captureDesktopLoadState(), loadToken)
                    } == true
            }
        }
        val bindings = remember(lease, creatorTeam) { lease.creatorTeam(creatorTeam) }
        DisposableEffect(lease) { onDispose { lease.close() } }
        val declarationPreference = remember(settings) {
            DesktopOriginalVideoMetadataSettings.getVideoArgueMsgShown(settings.pluginContext)
        }
        val argueMsgShown by declarationPreference.collectAsState(true)
        if (lease.isOwned()) {
            DesktopOriginalVideoHonors(info, argueMsgShown) { if (lease.isOwned()) latestHonor(it, lease::isOwned) }
            if (shouldShowCreatorTeamSection(info)) {
                CompositionLocalProvider(LocalDesktopCreatorTeamBindings provides bindings) {
                    CreatorTeamSection(info.staff, info.owner.mid) { if (lease.isOwned()) latestMember(it) }
                }
            }
        }
    }
}
