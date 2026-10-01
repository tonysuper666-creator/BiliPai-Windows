package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import com.android.purebilibili.core.store.DesktopOriginalVideoMetadataSettings
import com.android.purebilibili.data.repository.FollowStateChange
import com.android.purebilibili.feature.video.ui.section.CreatorTeamSection
import com.android.purebilibili.feature.video.ui.section.DesktopOriginalVideoHonors
import com.android.purebilibili.feature.video.ui.section.shouldShowCreatorTeamSection
import com.bilipai.desktop.data.*
import com.bilipai.desktop.settings.LocalDesktopDynamicTimelinePreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import java.util.concurrent.atomic.AtomicBoolean

internal interface DesktopCreatorTeamBindings {
    val followStateChanges: Flow<FollowStateChange>
    suspend fun checkFollowStatus(mid: Long): Boolean
    suspend fun followUser(mid: Long, follow: Boolean): Result<Boolean>
}
internal val LocalDesktopCreatorTeamBindings = staticCompositionLocalOf<DesktopCreatorTeamBindings> {
    error("Original creator team requires its page/account/action owner")
}

@Composable
internal fun DesktopVideoMetadataHost(
    details: VideoDetails, repository: DesktopRepository, social: DesktopSocialRepository,
    onUser: (Long) -> Unit, onLink: (String) -> Unit, onFeedback: (String) -> Unit,
) {
    val info = details.raw ?: return
    val epoch by repository.sessionEpochFlow.collectAsState()
    val capturedEpoch = epoch
    val session = checkNotNull(LocalDesktopDynamicCardSession.current)
    val context = checkNotNull(LocalDesktopDynamicTimelinePreferences.current).context
    val alive = remember(details.bvid, capturedEpoch, session) { AtomicBoolean(true) }
    val latestUser by rememberUpdatedState(onUser)
    val latestLink by rememberUpdatedState(onLink)
    val feedback by rememberUpdatedState(onFeedback)
    fun owned() = alive.get() && session.matches(repository, capturedEpoch)
    val bindings = remember(alive, social) {
        val operations = DesktopDynamicCardOperations(repository, capturedEpoch, ::owned, session.emotes)
        object : DesktopCreatorTeamBindings {
            override val followStateChanges = repository.followStateEvents.changes
                .filter { owned() && it.owner.epoch == capturedEpoch && repository.followStateEvents.isCurrent(it.owner) }
                .map { it.change }
            override suspend fun checkFollowStatus(mid: Long): Boolean = operations.checkCreatorFollowStatus(mid)
            override suspend fun followUser(mid: Long, follow: Boolean): Result<Boolean> {
                currentCoroutineContext().ensureActive()
                if (!owned()) throw CancellationException("Creator team owner retired")
                return try {
                    social.setFollowing(mid, follow)
                    currentCoroutineContext().ensureActive()
                    if (!owned()) throw CancellationException("Creator team owner retired")
                    Result.success(follow)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    if (!owned()) throw CancellationException("Creator team owner retired")
                    feedback(failure.message ?: "关注操作失败")
                    Result.failure(failure)
                }
            }
        }
    }
    DisposableEffect(alive) { onDispose { alive.set(false) } }
    val argueMsgShown by DesktopOriginalVideoMetadataSettings.getVideoArgueMsgShown(context).collectAsState(true)
    if (owned()) key(alive) {
        CompositionLocalProvider(LocalDesktopCreatorTeamBindings provides bindings) {
            DesktopOriginalVideoHonors(info, argueMsgShown) { if (owned()) latestLink(it) }
            if (shouldShowCreatorTeamSection(info)) CreatorTeamSection(info.staff, info.owner.mid) { if (owned()) latestUser(it) }
        }
    }
}
