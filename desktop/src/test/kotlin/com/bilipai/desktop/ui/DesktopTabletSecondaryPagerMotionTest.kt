@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.android.purebilibili.core.store.LocalPlaylist
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.theme.LocalAppUiStyle
import com.android.purebilibili.feature.audio.player.MusicPlayerUiState
import com.android.purebilibili.feature.audio.screen.MusicPlayerContent
import com.bilipai.desktop.settings.DesktopLiquidTabSettings
import com.bilipai.desktop.settings.LocalDesktopLiquidTabSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.jetbrains.skia.Bitmap
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import com.android.purebilibili.data.model.response.ViewInfo
import com.android.purebilibili.data.model.response.UgcSeason
import com.android.purebilibili.data.model.response.UgcSection
import com.android.purebilibili.data.model.response.UgcEpisode
import com.android.purebilibili.feature.video.screen.*
import com.android.purebilibili.feature.video.viewmodel.*
import com.bilipai.desktop.player.MpvPlayer
import java.lang.reflect.Proxy
import kotlin.test.assertEquals

/** Actual TabletSecondaryContent, with empty comments and a populated collection.
 * INTRO is injected text. No full player/layout, native source, account or network is mounted. */
class DesktopTabletSecondaryPagerMotionTest {
    @Test
    fun secondaryTabsFollowClicksRequestsRetargetingAndTouch(): Unit = runBlocking {
        DesktopPageTestFixture().use { fixture ->
            val context = DesktopOriginalPlayerSettingsContext(fixture.pluginContext, { true }, { it(); true })
            val rootSettings = DesktopOriginalHomeSettingsRootServices(fixture.home.settings,
                DesktopFavoritePreferences(fixture.pluginContext.store), { error("unexpected notice") },
                { _, _, _, _ -> error("unexpected profile") })
            val comments = Proxy.newProxyInstance(DesktopCommentPlatform::class.java.classLoader,
                arrayOf(DesktopCommentPlatform::class.java)) { proxy, method, args ->
                when (method.name) {
                    "equals" -> proxy === args?.get(0)
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "InertTabletCommentPlatform"
                    "getContext" -> fixture.pluginContext
                    "isOwned" -> true
                    else -> error("unexpected comment port ${method.name}")
                }
            } as DesktopCommentPlatform
            val native = MpvPlayer(useNullAudioOutput = true)
            val version = MutableStateFlow<Long?>(null)
            val control = DesktopOriginalMpvSectionControl(native, { null }, { true }, { it(); true }, { false },
                { error("unexpected prepare") }, { error("unexpected resume") },
                { _, _, _, _ -> error("unexpected seek") }, fixture.scope, version, { it(); true })
            val player = DesktopOriginalMpvVideoPlayerState(control, fixture.scope, version, { true },
                { it(); true }, { _, _, _ -> error("unexpected metadata") })
            val playback = VideoDetailPlaybackActions(
                changeQuality = { error("unexpected changeQuality") },
                reloadVideo = { error("unexpected reloadVideo") },
                switchCdn = { error("unexpected switchCdn") },
                switchCdnTo = { error("unexpected switchCdnTo") },
                probeCdnCandidates = { error("unexpected probeCdnCandidates") },
                setAudioMode = { error("unexpected setAudioMode") },
                setSleepTimer = { error("unexpected setSleepTimer") },
                switchPage = { error("unexpected switchPage") },
                openDownloadDialog = { error("unexpected openDownloadDialog") },
                showDanmakuSendDialog = { error("unexpected showDanmakuSendDialog") },
                skipSponsorSegment = { error("unexpected skipSponsorSegment") },
                dismissSponsorSkipButton = { error("unexpected dismissSponsorSkipButton") },
                voteSponsorSegment = { error("unexpected voteSponsorSegment") },
                markSponsorContributionBoundary = { error("unexpected markSponsorContributionBoundary") },
                markWholeVideoAsSponsor = { error("unexpected markWholeVideoAsSponsor") },
                setSponsorContributionCategory = { error("unexpected setSponsorContributionCategory") },
                setSponsorContributionActionType = { error("unexpected setSponsorContributionActionType") },
                submitSponsorContribution = { error("unexpected submitSponsorContribution") },
                cancelSponsorContribution = { error("unexpected cancelSponsorContribution") },
                notifyExplicitSeek = { error("unexpected notifyExplicitSeek") },
                setVideoCodec = { error("unexpected setVideoCodec") },
                setVideoSecondCodec = { error("unexpected setVideoSecondCodec") },
                setAudioQuality = { error("unexpected setAudioQuality") },
                applyPlaybackSpeed = { error("unexpected applyPlaybackSpeed") },
                changeAudioLanguage = { error("unexpected changeAudioLanguage") },
                saveCover = { error("unexpected saveCover") },
                downloadAudio = { error("unexpected downloadAudio") },
                selectSubtitleTrack = { error("unexpected selectSubtitleTrack") },
                showFavoriteFolderDialog = { error("unexpected showFavoriteFolderDialog") },
                toggleFavoriteFolderSelection = { error("unexpected toggleFavoriteFolderSelection") },
                saveFavoriteFolderSelection = { error("unexpected saveFavoriteFolderSelection") },
                dismissFavoriteFolderDialog = { error("unexpected dismissFavoriteFolderDialog") },
                createFavoriteFolder = { _, _, _ -> error("unexpected createFavoriteFolder") },
                retryAiSummary = { error("unexpected retryAiSummary") },
                createVideoNoteDraftFromAiSummary = { error("unexpected createVideoNoteDraftFromAiSummary") },
                openVideoNoteEditor = { error("unexpected openVideoNoteEditor") },
                closeVideoNoteEditor = { error("unexpected closeVideoNoteEditor") },
                updateVideoNoteEditorDocument = { error("unexpected updateVideoNoteEditorDocument") },
                currentVideoNoteTimestamp = { error("unexpected currentVideoNoteTimestamp") },
                seekTo = { error("unexpected seekTo") },
                saveVideoNote = { error("unexpected saveVideoNote") },
                deleteVideoNote = { error("unexpected deleteVideoNote") },
                retryVideoNote = { error("unexpected retryVideoNote") },
                loadMorePublicVideoNotes = { error("unexpected loadMorePublicVideoNotes") },
                openRootCommentComposer = { error("unexpected openRootCommentComposer") },
                replyTo = { error("unexpected replyTo") },
                markVideoNotInterested = { error("unexpected markVideoNotInterested") },
            )
            val engagement = VideoDetailEngagementActions(
                toggleFollow = { error("unexpected toggleFollow") },
                toggleFavorite = { error("unexpected toggleFavorite") },
                onFavoriteAction = { error("unexpected onFavoriteAction") },
                toggleLike = { error("unexpected toggleLike") },
                toggleDislike = { error("unexpected toggleDislike") },
                openCoinDialog = { error("unexpected openCoinDialog") },
                doTripleAction = { error("unexpected doTripleAction") },
                toggleWatchLater = { error("unexpected toggleWatchLater") },
            )
            val commentActions = VideoDetailCommentActions(
                loadComments = { error("unexpected loadComments") },
                refreshComments = { error("unexpected refreshComments") },
                setSortMode = { error("unexpected setSortMode") },
                deleteComment = { error("unexpected deleteComment") },
                startDissolve = { error("unexpected startDissolve") },
                loadMoreSubReplies = { error("unexpected loadMoreSubReplies") },
                refreshSubReplies = { error("unexpected refreshSubReplies") },
                setSubReplySortMode = { error("unexpected setSubReplySortMode") },
                openSubReply = { _, _ -> error("unexpected openSubReply") },
                openSubReplyConversation = { error("unexpected openSubReplyConversation") },
                closeSubReplyConversation = { error("unexpected closeSubReplyConversation") },
                closeSubReply = { error("unexpected closeSubReply") },
                startSubDissolve = { error("unexpected startSubDissolve") },
                deleteSubComment = { error("unexpected deleteSubComment") },
                likeComment = { error("unexpected likeComment") },
                hateComment = { error("unexpected hateComment") },
                reportComment = { _, _ -> error("unexpected reportComment") },
                toggleTopComment = { error("unexpected toggleTopComment") },
                checkCommentFraud = { error("unexpected checkCommentFraud") },
            )
            val success = VideoPlaybackUiState.Success(
                info = ViewInfo(bvid = "BVmotionCurrent", cid = 1,
                    ugc_season = UgcSeason(title = "Motion collection", sections = listOf(UgcSection(
                        episodes = listOf(UgcEpisode(bvid = "BVmotionNext", cid = 77, title = "Motion episode")))))),
                playUrl = "",
            )
            val opened = mutableListOf<Triple<String, Long, String?>>()
            val requested = mutableStateOf<String?>(null)
            var consumed = 0
            var nanos = 0L
            val liquidTabSettings = DesktopLiquidTabSettings(fixture.pluginContext.store)
            val scene = ImageComposeScene(800, 700, Density(1f), coroutineContext = coroutineContext) {
                CompositionLocalProvider(
                    LocalDesktopCommentBindings provides comments,
                    LocalDesktopOriginalHomeSettingsRootServices provides rootSettings,
                    LocalDesktopOriginalPlayerSettingsContext provides context,
                    LocalDesktopUpstreamAndroidContext provides DesktopOriginalSettingsAndroidContext(context),
                    LocalDesktopLiquidTabSettings provides liquidTabSettings,
                    LocalLifecycleOwner provides fixture.lifecycleOwner,
                    LocalAppUiStyle provides AppUiStyle.MATERIAL3,
                ) {
                    MaterialTheme(colorScheme = darkColorScheme(primary = Color.Magenta, secondaryContainer = Color.Magenta)) {
                        DesktopDetailWindow {
                            TabletSecondaryContent(
                                success = success, commentState = CommentUiState(isRepliesEnd = true),
                                subReplyState = SubReplyUiState(), playbackActions = playback,
                                engagementState = VideoEngagementUiState(), engagementActions = engagement,
                                commentActions = commentActions, playerState = player,
                                onUpClick = { error("unexpected owner") }, paneMode = TabletSecondaryPaneMode.EXPANDED,
                                onPaneModeChange = { error("unexpected pane change") },
                                onRelatedVideoClick = { bvid, cid, cover -> opened += Triple(bvid, cid, cover) },
                                showUpBadge = false, showIdentityDecorations = false,
                                onSearchKeywordClick = { error("unexpected search") }, onOpenBilibiliLink = null,
                                requestedTabName = requested.value,
                                onRequestedTabConsumed = { consumed++; requested.value = null },
                                introContent = { Text("Motion intro") }, applyStatusBarPadding = false,
                                includeRelatedTab = false, includeOwnerUploadsTab = false,
                            )
                        }
                    }
                }
            }
            fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
            fun allNodes() = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
            fun label(node: SemanticsNode, text: String) =
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true
            fun tab(text: String) = allNodes().single {
                it.config.getOrNull(SemanticsProperties.Selected) != null && nodes(it).any { child -> label(child, text) }
            }
            fun selected(text: String) = tab(text).config[SemanticsProperties.Selected]
            fun accent(pixel: Int) = (pixel ushr 16 and 255) > 200 &&
                (pixel ushr 8 and 255) < 80 && (pixel and 255) > 200
            var scanY: Int? = null
            val railPositions = mutableListOf<Float>()
            suspend fun frames(count: Int): List<Float> = buildList {
                repeat(count) {
                    nanos += 1_000_000_000L / 165
                    scene.render(nanos).use { image -> scanY?.let { y ->
                        Bitmap.makeFromImage(image).use { bitmap ->
                            val xs = (0 until 800).filter { accent(bitmap.getColor(it, y)) }
                            assertTrue(xs.isNotEmpty(), "tablet rail disappeared at $nanos")
                            railPositions += xs.average().toFloat()
                        }
                    } }
                    yield()
                }
            }
            fun pointer(event: PointerEventType, point: Offset, pressed: Boolean, type: PointerType = PointerType.Mouse) {
                scene.sendPointerEvent(event, point, timeMillis = nanos / 1_000_000,
                    buttons = PointerButtons(isPrimaryPressed = pressed), type = type)
            }
            suspend fun click(text: String) {
                val bounds = tab(text).boundsInRoot
                assertTrue(bounds.width > 10f && bounds.height > 10f && bounds.left >= 0f && bounds.right <= 800f,
                    "tablet tab must be laid out inside the scene: $text $bounds")
                pointer(PointerEventType.Move, bounds.center, false)
                pointer(PointerEventType.Press, bounds.center, true)
                frames(7)
                pointer(PointerEventType.Release, bounds.center, false)
            }
            try {
                withTimeout(6_000) {
                    while (allNodes().none { label(it, "合集") }) { frames(2); delay(5) }
                }
                frames(90)
                assertTrue(selected("评论"))
                assertTrue(allNodes().any { label(it, "暂无评论") })
                val first = tab("评论").boundsInRoot
                val last = tab("合集").boundsInRoot
                // Scan the unlettered lower part of the actual segmented indicator.
                val textBottom = nodes(tab("评论")).single { label(it, "评论") }.boundsInRoot.bottom.toInt()
                nanos += 1_000_000_000L / 165
                scene.render(nanos).use { image -> Bitmap.makeFromImage(image).use { bitmap ->
                    val rows = (textBottom until first.bottom.toInt()).map { y ->
                        y to (first.left.toInt() until last.right.toInt()).count { accent(bitmap.getColor(it, y)) }
                    }
                    val best = rows.maxBy { it.second }
                    assertTrue(best.second > 10, "no visible tablet indicator: $rows")
                    scanY = best.first
                } }
                frames(2)
                assertTrue(abs(railPositions.last() - first.center.x) < 4f)
                val start = railPositions.size
                click("合集"); frames(2)
                assertTrue(selected("合集"), "the clicked tablet tab must be selected before intermediate pages are crossed")
                frames(90)
                val rail = railPositions.drop(start)
                val spacing = (last.center.x - first.center.x) / 2f
                assertTrue(rail.any { it > first.center.x + spacing * .3f && it < last.center.x - spacing * .3f },
                    "tablet indicator skipped intermediate positions: $rail")
                assertTrue(rail.zipWithNext().all { (a, b) -> b >= a - 1f }, "tablet indicator moved backwards: $rail")
                assertTrue(rail.zipWithNext().all { (a, b) -> abs(b - a) < spacing * .5f }, "tablet indicator jumped: $rail")
                assertTrue(abs(rail.last() - last.center.x) < 4f)
                val episode = allNodes().single { label(it, "Motion episode") }.boundsInRoot
                assertTrue(episode.center.x in 0f..800f && episode.center.y in 0f..700f)
                pointer(PointerEventType.Press, episode.center, true); frames(7)
                pointer(PointerEventType.Release, episode.center, false); frames(4)
                assertEquals(listOf(Triple<String, Long, String?>("BVmotionNext", 77L, null)), opened)

                click("评论"); frames(8); click("简介"); frames(2)
                assertTrue(selected("简介"), "latest tablet click must own selection during reversal")
                frames(90)
                assertTrue(abs(railPositions.last() - tab("简介").boundsInRoot.center.x) < 4f)
                assertTrue(allNodes().any { label(it, "Motion intro") && it.boundsInRoot.left in 0f..799f })

                requested.value = "COLLECTION"; frames(90)
                assertEquals(1, consumed)
                assertTrue(selected("合集"))
                assertTrue(abs(railPositions.last() - last.center.x) < 4f)
                // From collection, real horizontal input should settle on adjacent INTRO.
                val dragStart = railPositions.size
                pointer(PointerEventType.Press, Offset(80f, 260f), true, PointerType.Touch); frames(2)
                repeat(12) { step ->
                    pointer(PointerEventType.Move, Offset(80f + (step + 1) * 50f, 260f), true, PointerType.Touch)
                    frames(2)
                }
                val dragged = railPositions.drop(dragStart)
                assertTrue(dragged.any { it < last.center.x - 15f && it > first.center.x + 15f },
                    "touch did not move actual tablet pager indicator: $dragged")
                pointer(PointerEventType.Release, Offset(680f, 260f), false, PointerType.Touch); frames(90)
                assertTrue(selected("简介"))
                assertTrue(abs(railPositions.last() - tab("简介").boundsInRoot.center.x) < 4f)
                assertTrue(allNodes().any { label(it, "Motion intro") && it.boundsInRoot.left in 0f..799f })
                assertEquals(1, consumed, "settling or touch must not replay a consumed request")
                assertEquals(listOf(Triple<String, Long, String?>("BVmotionNext", 77L, null)), opened)
            } finally { scene.close(); native.close() }
        }
    }
}
