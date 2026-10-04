package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.android.purebilibili.core.ui.AppModalBottomSheet
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.video.playback.loader.PlaybackRequest
import com.android.purebilibili.feature.video.ui.section.DesktopOriginalInlineBgmSection
import com.android.purebilibili.feature.video.ui.section.resolveDisplayBgmList
import com.bilipai.desktop.audio.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.atomic.AtomicBoolean

internal class DesktopWindowsVideoBgmPresentation(
    val assembly: DesktopOriginalVideoOwnerAssembly,
    val sourceOwner: DesktopOriginalVideoAcceptedPublication,
    val result: DesktopOriginalVideoBgmResult,
    val stillOwned: () -> Boolean,
)

/** A matching part is insufficient: the original metadata invocation is reference owned. */
internal fun desktopWindowsVideoBgmMatchesSource(
    captured: DesktopOriginalVideoBgmResult,
    current: DesktopOriginalVideoBgmResult?,
    source: PlaybackRequest,
): Boolean = current != null && captured.request === current.request &&
    captured.bvid == source.bvid && captured.cid == source.cid && captured.cid > 0L

/** A small presentation lease over required original operations, never a second music owner. */
internal class DesktopWindowsVideoBgmRequests(
    private val stillOwned: () -> Boolean,
    private val admit: ((() -> Unit) -> Boolean),
    private val detail: suspend (String, Long, Long) -> Result<BgmDetailData?>,
    private val recommendations: suspend (String, Long, Long, Int, Int) -> Result<List<BgmRecommendVideo>>,
) : DesktopBgmDiscoveryRequests, AutoCloseable {
    private val alive = AtomicBoolean(true)
    fun isOwned(): Boolean = alive.get() && stillOwned()
    private suspend fun checkCurrent() {
        currentCoroutineContext().ensureActive()
        if (!isOwned()) throw CancellationException("Windows BGM source/panel retired")
    }
    override fun withAdmission(action: () -> Unit): Boolean {
        if (!isOwned()) return false
        var applied = false
        return admit { if (isOwned()) { action(); applied = true } } && applied
    }
    override suspend fun getBgmDetail(musicId: String, aid: Long, cid: Long): Result<BgmDetailData?> {
        checkCurrent()
        return detail(musicId, aid, cid).also {
            checkCurrent()
            (it.exceptionOrNull() as? CancellationException)?.let { failure -> throw failure }
        }
    }
    override suspend fun getBgmRecommendVideos(musicId: String, aid: Long, cid: Long, page: Int, pageSize: Int): Result<List<BgmRecommendVideo>> {
        checkCurrent()
        return recommendations(musicId, aid, cid, page, pageSize).also {
            checkCurrent()
            (it.exceptionOrNull() as? CancellationException)?.let { failure -> throw failure }
        }
    }
    override fun close() { alive.set(false) }
}

private val LocalDesktopBgmNativeSelectionWindow = staticCompositionLocalOf { false }

/** Original complete selector content; only the containing Windows surface differs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun DesktopWindowsBgmModalSheet(
    title: String, onDismissRequest: () -> Unit, sheetState: SheetState,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (LocalDesktopBgmNativeSelectionWindow.current) {
        DesktopWindowsPlayerDialog(title, onDismissRequest, preferredHeightDp = 640) {
            Column(Modifier.fillMaxSize(), content = content)
        }
    } else {
        AppModalBottomSheet(onDismissRequest = onDismissRequest, sheetState = sheetState,
            dragHandle = null, content = content)
    }
}

@Composable internal fun desktopWindowsBgmSelectionHeightFraction(): Float =
    if (LocalDesktopBgmNativeSelectionWindow.current) 1f else .68f

/** Reads the existing VM result. Detail/recommendation calls capture a fresh invocation
 * in the actual selector Job; no old completed load binding or Dynamic owner is reused. */
@Composable internal fun DesktopWindowsVideoBgmSection(
    presentation: DesktopWindowsVideoBgmPresentation,
    admit: ((() -> Unit) -> Boolean),
    onDetail: (DesktopBgmMusicTarget.Detail) -> Unit,
    onRelatedVideo: (String, Long) -> Unit,
    onExternalUrl: (String) -> Unit,
) {
    val assembly = presentation.assembly
    val source = presentation.sourceOwner
    val result = presentation.result
    key(assembly, source, result.request) {
        val latestOwned by rememberUpdatedState(presentation.stillOwned)
        val latestAdmission by rememberUpdatedState(admit)
        val latestDetail by rememberUpdatedState(onDetail)
        val latestVideo by rememberUpdatedState(onRelatedVideo)
        val latestExternal by rememberUpdatedState(onExternalUrl)
        val requests = remember {
            DesktopWindowsVideoBgmRequests(
                stillOwned = { latestOwned() && assembly.owns() && assembly.native.isCurrent(source) &&
                    desktopWindowsVideoBgmMatchesSource(result, assembly.playback.captureDesktopBgmResult(), source.request) },
                admit = { action -> latestAdmission(action) },
                detail = { musicId, aid, cid -> assembly.invocations.withInvocation {
                    assembly.environment.repository.getBgmDetail(musicId, aid, cid)
                } },
                recommendations = { musicId, aid, cid, page, pageSize -> assembly.invocations.withInvocation {
                    assembly.environment.repository.getBgmRecommendVideos(musicId, aid, cid, page, pageSize)
                } },
            )
        }
        DisposableEffect(requests) { onDispose { requests.close() } }
        val songs = resolveDisplayBgmList(result.bgmInfo, result.bgmInfoList)
        if (songs.isNotEmpty() && requests.isOwned()) {
            CompositionLocalProvider(LocalDesktopBgmDiscoveryRequests provides requests,
                LocalDesktopBgmNativeSelectionWindow provides true) {
                DesktopOriginalInlineBgmSection(songs,
                    onBgmClick = { bgm ->
                        val target = resolveDesktopBgmMusicTarget(bgm, result.bvid, result.cid)
                        when (target) {
                            is DesktopBgmMusicTarget.Detail -> requests.withAdmission { latestDetail(target) }
                            is DesktopBgmMusicTarget.Web -> {
                                var acceptedUrl: String? = null
                                val admitted = requests.withAdmission { acceptedUrl = target.url }
                                // System browser I/O follows the accepted click outside every ownership gate.
                                if (admitted) acceptedUrl?.let { latestExternal(it) }
                            }
                            null -> Unit
                        }
                    },
                    onRelatedVideoClick = { bvid, cid -> requests.withAdmission { latestVideo(bvid, cid) } },
                )
            }
        }
    }
}
