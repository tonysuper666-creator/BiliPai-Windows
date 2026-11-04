@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.bilipai.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.theme.LocalAppUiStyle
import com.android.purebilibili.core.ui.LocalSetBottomBarVisible
import com.android.purebilibili.data.model.response.VideoItem
import com.android.purebilibili.feature.home.*
import com.android.purebilibili.feature.video.share.VideoSharePayload
import com.bilipai.desktop.settings.DesktopLiquidTabSettings
import com.bilipai.desktop.settings.LocalDesktopLiquidTabSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.jetbrains.skia.Bitmap
import java.lang.reflect.Proxy
import kotlin.math.abs
import kotlin.test.*

private inline fun <reified T> homeUnavailable(): T = Proxy.newProxyInstance(
    T::class.java.classLoader, arrayOf(T::class.java)
) { _, method, _ -> error("Unexpected Home request: ${T::class.java.simpleName}.${method.name}") } as T

/** Mounts the generated production HomeScreen and its real state owner. No transport or media. */
class DesktopHomeScreenPagerMotionTest {
    @Test fun realHomeTabsAnimateAndRetarget(): Unit = runBlocking {
        DesktopPageTestFixture().use { fixture ->
            val blocked = object : DesktopHomeBlockedRequests {
                override fun getAllBlockedUps() = flowOf(emptyList<com.android.purebilibili.core.database.entity.BlockedUp>())
                override suspend fun blockUp(mid: Long, name: String, face: String) = error("block")
                override suspend fun blockUpWithBilibiliSync(mid: Long, name: String, face: String) = error("block")
            }
            val video = object : DesktopHomeVideoRequests {
                override suspend fun getHomeVideos(idx: Int): Result<List<VideoItem>> = Result.success(emptyList())
                override suspend fun getPopularVideos(page: Int): Result<List<VideoItem>> = Result.success(emptyList())
                override suspend fun getRegionVideos(tid: Int, page: Int): Result<List<VideoItem>> = Result.success(emptyList())
                override suspend fun getRankingVideos(rid: Int, type: String): Result<List<VideoItem>> = Result.success(emptyList())
                override suspend fun getWeeklyMustWatchVideos(): Result<List<VideoItem>> = Result.success(emptyList())
                override suspend fun getPreciousVideos(): Result<List<VideoItem>> = Result.success(emptyList())
                override suspend fun getNavInfo(): Result<DesktopHomeNavPublication> = Result.failure(IllegalStateException("guest"))
                override suspend fun getPreviewVideoUrl(bvid: String, cid: Long): String? = error("media")
                override suspend fun isVerticalVideo(bvid: String, aid: Long): Boolean = error("media")
            }
            val vm = DesktopOriginalHomeViewModel(DesktopHomeDataEnvironment(
                capturedEpoch = 1L, parentScope = fixture.scope, isCurrent = { true }, commitIfCurrent = { it(); true },
                isLoggedIn = { false }, isPrivacyModeEnabledSync = { false },
                analytics = object : DesktopHomeIdentityAnalytics { override fun syncUserContext(mid: Long?, isVip: Boolean, privacyModeEnabled: Boolean) = Unit },
                recommendationContext = fixture.pluginContext,
                followingCache = DesktopHomeFollowingCache(fixture.pluginContext.store) { it(); true },
                incrementalTimelineRefresh = MutableStateFlow(false), homeRefreshTipVisible = MutableStateFlow(false),
                video = video, history = homeUnavailable(), live = homeUnavailable(), messages = homeUnavailable(),
                actions = homeUnavailable(), follow = homeUnavailable(), blockedUps = blocked,
                following = homeUnavailable(), feedback = { error("feedback") },
            ))
            val existing = fixture.home
            val analytics = object : DesktopHomeAnalyticsPort {
                override fun logScreenView(name: String) = Unit
                override fun logCategoryView(categoryName: String, categoryId: Int) = Unit
                override fun logHomeReturnAnimationPerformance(actualDurationMs: Long, plannedSuppressionMs: Long, sharedTransitionEnabled: Boolean, sharedTransitionReady: Boolean, isQuickReturn: Boolean, isTabletLayout: Boolean, cardAnimationEnabled: Boolean, builtinPluginEnabledCount: Int, playerPluginEnabledCount: Int, feedPluginEnabledCount: Int, danmakuPluginEnabledCount: Int, jsonPluginEnabledCount: Int, jsonFeedPluginEnabledCount: Int, jsonDanmakuPluginEnabledCount: Int) = Unit
            }
            val overlays = object : DesktopHomeOverlayPorts {
                @Composable override fun VideoShareSheetHost(payload: VideoSharePayload?, onDismiss: () -> Unit) { assertNull(payload) }
                @Composable override fun CrashTrackingConsentDialog(onDismiss: () -> Unit) = Unit
            }
            val home = DesktopHomeEnvironment(existing.settings, existing.pluginContext, existing.lifecycleOwner,
                existing.subscriptionPluginEnabled, analytics, existing.globalNamespace, existing.wallpaperPalette,
                { uri, _ -> assertTrue(uri.isBlank(), "unexpected wallpaper") }, {}, { _, _ -> }, existing.pages,
                { _, _ -> error("save") }, { error("feedback") }, overlays)
            val background = object : DesktopHomeWindowBackgroundPort {
                override val isInBackground = false
                override fun addListener(listener: DesktopHomeWindowBackgroundPort.Listener) = Unit
                override fun removeListener(listener: DesktopHomeWindowBackgroundPort.Listener) = Unit
            }
            val platform = DesktopHomePlatform(false, false, false, false, false, false, background, 0f, false)
            val metrics = DesktopHomeMetricHolder(object : DesktopHomeMetricSink {
                override fun putState(name: String, value: String) = Unit
                override fun removeState(name: String) = Unit
            })
            val media = DesktopHomeMediaPorts(MutableStateFlow(false),
                { _, _, _, _ -> error("unexpected preview") }, { error("unexpected media feedback") },
                object : DesktopHomeWallpaperPort {
                    @Composable override fun wallpaperSurface(uri: String, imageModel: Any, playbackEnabled: Boolean, modifier: Modifier) = error("unexpected wallpaper")
                })
            val errors = mutableListOf<Throwable>()
            var nanos = 0L
            val scene = ImageComposeScene(1200, 800, Density(1f), coroutineContext = coroutineContext + CoroutineExceptionHandler { _, e -> errors += e }) {
                CompositionLocalProvider(
                    LocalDesktopHomeEnvironment provides home, LocalDesktopHomePlatform provides platform,
                    LocalDesktopHomeMediaPorts provides media, LocalDesktopHomeMetricHolder provides metrics,
                    LocalDesktopLiquidTabSettings provides DesktopLiquidTabSettings(fixture.pluginContext.store),
                    LocalLifecycleOwner provides fixture.lifecycleOwner, LocalAppUiStyle provides AppUiStyle.MATERIAL3,
                    LocalSetBottomBarVisible provides { _ -> },
                    LocalDesktopHomeErrorAnimation provides { _, _, _ -> },
                ) {
                    MaterialTheme(colorScheme = lightColorScheme(primary = Color.Magenta)) {
                        DesktopDetailWindow {
                            HomeScreen(viewModel = vm, onVideoClick = {}, onAvatarClick = {}, onProfileClick = {},
                                onSettingsClick = {}, onSearchClick = {})
                        }
                    }
                }
            }
            fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
            fun labelNode(text: String) = scene.semanticsOwners.flatMap { nodes(it.unmergedRootSemanticsNode) }
                .first { it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == text } == true }
            fun label(text: String) = labelNode(text).boundsInRoot
            fun labelWeight(text: String): FontWeight? {
                val layouts = mutableListOf<TextLayoutResult>()
                val getLayout = assertNotNull(labelNode(text).config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action)
                assertTrue(getLayout(layouts))
                return layouts.single().layoutInput.style.fontWeight
            }
            fun accent(pixel: Int) = (pixel ushr 16 and 255) > 200 && (pixel ushr 8 and 255) < 80 && (pixel and 255) > 200
            var scanY = 0
            suspend fun frames(count: Int): List<Float> = buildList {
                repeat(count) {
                    nanos += 1_000_000_000L / 165
                    scene.render(nanos).use { image -> if (scanY > 0) Bitmap.makeFromImage(image).use { bitmap ->
                        val xs = (0 until 1200).filter { accent(bitmap.getColor(it, scanY)) }
                        assertTrue(xs.isNotEmpty(), "underline missing at frame $nanos")
                        add(xs.average().toFloat())
                    } }
                    yield()
                    assertTrue(errors.isEmpty(), "composition failure: $errors")
                }
            }
            suspend fun click(text: String) {
                val point = label(text).center
                scene.sendPointerEvent(PointerEventType.Move, point, timeMillis = nanos / 1_000_000, buttons = PointerButtons())
                scene.sendPointerEvent(PointerEventType.Press, point, timeMillis = nanos / 1_000_000, buttons = PointerButtons(isPrimaryPressed = true))
                scene.sendPointerEvent(PointerEventType.Release, point, timeMillis = nanos / 1_000_000, buttons = PointerButtons())
            }
            try {
                frames(90)
                val labels = listOf("推荐", "热门")
                val centers = labels.map { label(it).center.x }
                val spacing = centers[1] - centers[0]
                assertTrue(spacing > 0f)
                nanos += 6_060_606L
                scene.render(nanos).use { image -> Bitmap.makeFromImage(image).use { bitmap ->
                    val low = labels.maxOf { label(it).bottom }.toInt()
                    val best = (low until (low + 20).coerceAtMost(800)).map { y ->
                        y to (0 until 1200).count { accent(bitmap.getColor(it, y)) }
                    }.maxBy { it.second }
                    assertTrue(best.second > 5, "missing native Home underline: $best")
                    scanY = best.first
                } }
                val start = frames(1).last()
                click("热门")
                frames(1)
                assertEquals(HomeCategory.POPULAR, vm.currentCategory.value, "target is not immediately selected")
                assertEquals(FontWeight.SemiBold, labelWeight("热门"), "visible Home tab is not immediately selected")
                val forward = listOf(start) + frames(90)
                assertTrue(forward.any { it > centers[0] + spacing * .15f && it < centers[1] - spacing * .15f }, "$forward")
                assertTrue(forward.zipWithNext().all { (a, b) -> b >= a - 1f && abs(b - a) < spacing * .5f }, "$forward")
                assertTrue(abs(forward.last() - centers[1]) < 4f)
                assertEquals(HomeCategory.POPULAR, vm.currentCategory.value)
                click("推荐")
                val backward = frames(90)
                assertTrue(backward.any { it > centers[0] + spacing * .15f && it < centers[1] - spacing * .15f }, "$backward")
                assertTrue(backward.zipWithNext().all { (a, b) -> b <= a + 1f && abs(b - a) < spacing * .5f }, "$backward")
                assertTrue(abs(backward.last() - centers[0]) < 4f)
                assertEquals(HomeCategory.RECOMMEND, vm.currentCategory.value)
            } finally { scene.close(); vm.close() }
        }
    }
}
