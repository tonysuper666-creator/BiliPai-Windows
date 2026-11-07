@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.ui

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.theme.LocalAppUiStyle
import com.android.purebilibili.data.model.response.CreatorCardStats
import com.android.purebilibili.data.model.response.ViewInfo
import com.android.purebilibili.feature.video.note.VideoNoteUiState
import com.android.purebilibili.feature.video.screen.*
import com.android.purebilibili.feature.video.viewmodel.CommentSortMode
import com.bilipai.desktop.settings.DesktopLiquidTabSettings
import com.bilipai.desktop.settings.LocalDesktopLiquidTabSettings
import java.lang.reflect.Proxy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import org.jetbrains.skia.Bitmap
import kotlin.math.abs
import kotlin.test.*

/** The compiled VideoContentSection, not a copy of its tab/pager wiring.
 * Empty comments and a synthetic ViewInfo avoid transport, media and account IO.
 * The virtual 165 Hz clock does not measure a physical display. */
class DesktopVideoContentPagerMotionTest {
    @Test
    fun videoTabsFollowClicksRetargetingAndTouch(): Unit = runBlocking {
        DesktopPageTestFixture().use { fixture ->
            fun unexpected(): Nothing = error("unexpected video action")
            val context = DesktopOriginalPlayerSettingsContext(fixture.pluginContext, { true }, { it(); true })
            val preferences = DesktopFavoritePreferences(fixture.pluginContext.store)
            val rootSettings = DesktopOriginalHomeSettingsRootServices(fixture.home.settings, preferences,
                { unexpected() }, { _, _, _, _ -> unexpected() })
            // Use the shared BackToTop port with the same real settings owner as Root.
            // This slice never drags that control; unexpected preference writes fail.
            val backToTop = DesktopBackToTopBindings(
                preferences.backToTopEnabled, preferences.initialBackToTopEnabled(),
                preferences.backToTopOffset, preferences.initialBackToTopOffset(),
                { _, _ -> unexpected() }, { _, _ -> unexpected() }, DesktopFavoriteViewport(900, 700),
            )
            // The synthetic owner's mid is zero, so the real UpInfoSection skips card IO.
            // Supply its settings context; all transport and action ports stay strict.
            val videoInfo = object : DesktopOriginalVideoInfoBindings {
                override val context = fixture.pluginContext
                override suspend fun getCreatorCardStats(mid: Long): Result<CreatorCardStats> = unexpected()
                override fun copyText(text: String, label: String) = unexpected()
                override fun showFeedback(message: String) = unexpected()
            }
            val blocked = Proxy.newProxyInstance(DesktopHomeBlockedRequests::class.java.classLoader,
                arrayOf(DesktopHomeBlockedRequests::class.java)) { _, method, _ ->
                when (method.name) { "getAllBlockedUps" -> flowOf(emptyList<Any>()); else -> unexpected() }
            } as DesktopHomeBlockedRequests
            val content = DesktopOriginalVideoContentBindings(context, fixture.home.settings, fixture.pluginContext,
                blocked, { true }, { it(); true }, { _, _ -> unexpected() }, { unexpected() },
                { _, _ -> unexpected() }, DesktopTodayWatchFeedbackWriteBinding(fixture.pluginContext, { true }, { it(); true }),
                { _, _, _, _ -> unexpected() }, { _, _ -> unexpected() })
            val comments = Proxy.newProxyInstance(DesktopCommentPlatform::class.java.classLoader,
                arrayOf(DesktopCommentPlatform::class.java)) { proxy, method, args ->
                when (method.name) {
                    "equals" -> proxy === args?.get(0)
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "InertVideoContentCommentPlatform"
                    "getContext" -> fixture.pluginContext
                    "isOwned" -> true
                    else -> error("unexpected comment port ${method.name}")
                }
            } as DesktopCommentPlatform
            val selections = mutableListOf<Int>()
            val metricStates = mutableMapOf<String, String>()
            val metrics = DesktopHomeMetricHolder(object : DesktopHomeMetricSink {
                override fun putState(name: String, value: String) { metricStates[name] = value }
                override fun removeState(name: String) { metricStates.remove(name) }
            })
            val scene = ImageComposeScene(900, 700, Density(1f), coroutineContext = coroutineContext) {
                CompositionLocalProvider(
                    LocalDesktopHomeEnvironment provides fixture.home,
                    LocalDesktopHomeMetricHolder provides metrics,
                    LocalDesktopFeedGridColumnSettings provides fixture.home.settings,
                    LocalDesktopOriginalHomeSettingsRootServices provides rootSettings,
                    LocalDesktopBackToTopBindings provides backToTop,
                    LocalDesktopOriginalPlayerSettingsContext provides context,
                    LocalDesktopUpstreamAndroidContext provides DesktopOriginalSettingsAndroidContext(context),
                    LocalDesktopOriginalVideoContentBindings provides content,
                    LocalDesktopOriginalVideoInfoBindings provides videoInfo,
                    LocalDesktopCommentBindings provides comments,
                    LocalDesktopLiquidTabSettings provides DesktopLiquidTabSettings(fixture.pluginContext.store),
                    LocalLifecycleOwner provides fixture.lifecycleOwner,
                    LocalAppUiStyle provides AppUiStyle.MATERIAL3,
                ) {
                    MaterialTheme(colorScheme = darkColorScheme(primary = Color.Magenta, secondaryContainer = Color.Magenta)) {
                        val pager = rememberPagerState { 2 }
                        VideoContentSection(
                            data = VideoContentData(ViewInfo(bvid = "BVfixture1", title = "Known video", desc = "Known description"),
                                rememberLazyListState(), rememberLazyListState(), pager, emptyList(), emptyList(), 0,
                                emptyMap(), emptySet(), emptyList(), null, emptyList()),
                            engagementState = VideoContentEngagementState(isLoggedIn = false, isFollowing = false,
                                isFavorited = false, isLiked = false, coinCount = 0, currentPageIndex = 0,
                                downloadProgress = 0f, isInWatchLater = false),
                            commentState = VideoContentCommentState(false, false, null, true, null,
                                CommentSortMode.HOT, 0L, false, false, emptySet(), likedComments = emptySet(), hatedComments = emptySet()),
                            noteState = VideoContentNoteState(null, null, VideoNoteUiState()),
                            presentationState = VideoContentPresentationState("", false, false, false, null, false,
                                "", false, null, null, false, false, false, 0.dp, commentPullToRefreshEnabled = false),
                            primaryActions = VideoContentPrimaryActions(
                                onFollowClick = { unexpected() }, onFavoriteClick = { unexpected() },
                                onLikeClick = { unexpected() }, onCoinClick = { unexpected() }, onTripleClick = { unexpected() },
                                onPageSelect = { unexpected() }, onUpClick = { unexpected() },
                                onRelatedVideoClick = { _, _, _ -> unexpected() }, onDownloadClick = { unexpected() },
                                onWatchLaterClick = { unexpected() }, onShareClick = { unexpected() }, onTimestampClick = { unexpected() },
                                onDanmakuSendClick = { unexpected() }, onDanmakuToggle = { unexpected() },
                                onFavoriteLongClick = { unexpected() }, onBgmClick = { unexpected() }),
                            commentActions = VideoContentCommentActions(
                                onSortModeChange = { unexpected() }, onSubReplyClick = { _, _ -> unexpected() },
                                onCommentReplyClick = { unexpected() }, onLoadMoreReplies = { unexpected() },
                                onRefreshReplies = { unexpected() }, onDeleteComment = { unexpected() },
                                onDissolveStart = { unexpected() }, onCommentLike = { unexpected() }, onCommentHate = { unexpected() },
                                onCommentUrlClick = { unexpected() }, onDescriptionUrlClick = { unexpected() },
                                onSearchKeywordClick = { unexpected() }, onReportComment = { _, _ -> unexpected() },
                                onToggleTopComment = { unexpected() }, onCheckCommentFraud = { unexpected() }),
                            noteActions = VideoContentNoteActions(
                                onRetryAiSummary = { unexpected() }, onCreateNoteDraftFromAiSummary = { unexpected() },
                                onOpenVideoNoteEditor = { unexpected() }, onCloseVideoNoteEditor = { unexpected() },
                                onVideoNoteDocumentChange = { unexpected() }, onInsertVideoNoteTimestamp = { unexpected() },
                                onVideoNoteTimestampClick = { unexpected() }, onSaveVideoNote = { unexpected() },
                                onDeleteVideoNote = { unexpected() }, onRetryVideoNote = { unexpected() },
                                onLoadMorePublicVideoNotes = { unexpected() }, onPublicVideoNoteClick = { _, _ -> unexpected() }),
                            uiActions = VideoContentUiActions({ selections += it }, {}, { _, _ -> }),
                        )
                    }
                }
            }
            var nanos = 0L
            var scanY: Int? = null
            fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
            fun allNodes() = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
            fun tab(text: String): SemanticsNode {
                val matches = allNodes().filter { node ->
                    node.config.getOrNull(SemanticsProperties.Selected) != null &&
                        nodes(node).any { child -> child.config.getOrNull(SemanticsProperties.Text)?.any {
                            it.text == text || (text == "评论" && it.text.matches(Regex("评论 [0-9]+")))
                        } == true } &&
                        node.boundsInRoot.width > 10f && generateSequence(node) { it.parent }.all {
                            it.boundsInRoot.contains(node.boundsInRoot.center)
                        }
                }
                check(matches.size == 1) { "ambiguous video tab $text: " + matches.map { it.config } }
                return matches.single()
            }
            fun accent(pixel: Int) = (pixel ushr 16 and 255) > 200 &&
                (pixel ushr 8 and 255) < 80 && (pixel and 255) > 200
            suspend fun frames(count: Int): List<Float> = buildList {
                repeat(count) {
                    nanos += 1_000_000_000L / 165
                    scene.render(nanos).use { image -> scanY?.let { y ->
                        Bitmap.makeFromImage(image).use { bitmap ->
                            val xs = (0 until 900).filter { accent(bitmap.getColor(it, y)) }
                            assertTrue(xs.isNotEmpty(), "video rail disappeared at $nanos")
                            add(xs.average().toFloat())
                        }
                    } }
                    yield()
                }
            }
            fun pointer(event: PointerEventType, point: Offset, pressed: Boolean, type: PointerType = PointerType.Mouse) {
                scene.sendPointerEvent(event, point, timeMillis = nanos / 1_000_000,
                    buttons = PointerButtons(isPrimaryPressed = pressed), type = type)
            }
            suspend fun click(text: String): List<Float> {
                val point = tab(text).boundsInRoot.center
                assertTrue(point.x in 0f..900f && point.y in 0f..700f)
                pointer(PointerEventType.Move, point, false)
                pointer(PointerEventType.Press, point, true)
                val held = frames(7)
                pointer(PointerEventType.Release, point, false)
                return held
            }
            fun continuous(values: List<Float>, spacing: Float) = assertTrue(
                values.zipWithNext().all { (a, b) -> abs(b - a) < spacing * .5f },
                "video rail jumped half a tab in one virtual 165 Hz frame: $values")
            try {
                frames(90)
                val first = tab("简介").boundsInRoot
                val last = tab("评论").boundsInRoot
                val spacing = last.center.x - first.center.x
                assertTrue(spacing > 0f)
                nanos += 6_060_606L
                scene.render(nanos).use { image -> Bitmap.makeFromImage(image).use { bitmap ->
                    val rows = (first.top.toInt() until first.bottom.toInt()).map { y ->
                        y to (first.left.toInt() until first.right.toInt()).count { accent(bitmap.getColor(it, y)) }
                    }
                    val best = rows.maxBy { it.second }
                    assertTrue(best.second > 10, "no rendered video rail: $rows")
                    scanY = best.first
                } }
                val start = frames(2).last()
                val held = click("评论")
                val early = frames(2)
                assertTrue(tab("评论").config[SemanticsProperties.Selected],
                    "the clicked video tab must be selected before the pager crosses the midpoint")
                assertEquals(1, selections.last(), "the outer detail owner must receive the clicked target")
                val travel = listOf(start) + held + early + frames(90)
                assertTrue(travel.any { it > first.center.x + spacing * .2f && it < last.center.x - spacing * .2f },
                    "video rail must cross intermediate positions: $travel")
                assertTrue(travel.zipWithNext().all { (a, b) -> b >= a - 1f }, "video rail reversed: $travel")
                continuous(travel, spacing)
                assertTrue(abs(travel.last() - last.center.x) < 3f)

                val reverse = click("简介") + frames(8)
                val redirect = click("评论") + frames(2)
                assertTrue(tab("评论").config[SemanticsProperties.Selected])
                assertEquals(1, selections.last())
                val retargeted = reverse + redirect + frames(90)
                continuous(retargeted, spacing)
                assertTrue(abs(retargeted.last() - last.center.x) < 3f)

                // Comment-page horizontal input is handled by the real tab swipe modifier.
                val touchY = tab("评论").boundsInRoot.center.y
                pointer(PointerEventType.Press, Offset(150f, touchY), true, PointerType.Touch)
                frames(2)
                val swipe = mutableListOf<Float>()
                repeat(12) { step ->
                    pointer(PointerEventType.Move, Offset(150f + (step + 1) * 50f, touchY), true, PointerType.Touch)
                    swipe += frames(2)
                }
                assertTrue(swipe.any { it > first.center.x + spacing * .1f && it < last.center.x - spacing * .1f },
                    "touch input did not move video rail through intermediate positions: $swipe")
                pointer(PointerEventType.Release, Offset(750f, touchY), false, PointerType.Touch)
                val settled = frames(90)
                continuous(swipe + settled, spacing)
                assertTrue(tab("简介").config[SemanticsProperties.Selected])
                assertEquals(0, selections.last())
                assertTrue(abs(settled.last() - first.center.x) < 3f)
                assertTrue(allNodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "Known video" } == true })
            } finally {
                scene.close()
            }
        }
    }
}
