package com.android.purebilibili.feature.video

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.purebilibili.feature.video.screen.videoDetailWholeCardReturnLayer
import com.android.purebilibili.feature.video.screen.rememberVideoDetailWholeCardReturn
import com.android.purebilibili.feature.video.screen.VideoDetailReturnCoverImage
import com.android.purebilibili.feature.video.screen.resolveVideoDetailReturnMediaFrame
import com.android.purebilibili.core.ui.transition.VideoCardSourceLayout
import com.android.purebilibili.core.ui.transition.VideoCardTransitionBackgroundPhase
import com.android.purebilibili.core.util.CardPositionManager
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.launch

@RunWith(AndroidJUnit4::class)
class VideoDetailWholeCardReturnUiRegressionTest {
    @get:Rule
    val rule = createComposeRule()


    @Test
    fun quickPredictiveReturnKeepsResidentCoverUntilSnapshotAndRestoresItOnCancel() {
        val depth = mutableFloatStateOf(0.9f)
        val gesture = mutableStateOf(true)
        val follow = mutableStateOf(true)
        val decoded = mutableIntStateOf(0)
        val residentCover = Bitmap.createBitmap(240, 135, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.BLUE)
        }
        val snapshot = Bitmap.createBitmap(240, 180, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.GREEN)
        }.asImageBitmap()
        try {
            rule.runOnIdle {
                CardPositionManager.clear()
                recordCard("BVquick", "home")
            }
            rule.setContent {
                val context = LocalContext.current
                val request = remember(context) {
                    ImageRequest.Builder(context).data(residentCover).size(240, 135).crossfade(false).build()
                }
                val wholeCard = rememberVideoDetailWholeCardReturn(
                    bvid = "BVquick", sourceRoute = "home", ownsTransition = true,
                    committedReturn = false, keepLoadedContentForBackPreview = false,
                    sourceBounds = Rect(0f, 0f, 240f, 180f), sourceLayout = VideoCardSourceLayout.STACKED,
                    isNowPlayingBar = false, depthProvider = { depth.floatValue },
                    phaseProvider = {
                        if (gesture.value) VideoCardTransitionBackgroundPhase.RETURNING
                        else VideoCardTransitionBackgroundPhase.HELD
                    },
                    gestureProvider = { gesture.value }, followProgressProvider = { follow.value },
                )
                // Player has no decoded frame yet; the resident cover must hide its black backing.
                Box(Modifier.size(240.dp, 240.dp).background(Color.Black)
                    .then(wholeCard.modifier).testTag("quick-return")) {
                    Column {
                        VideoDetailReturnCoverImage(
                            request = request, landingLayout = null,
                            handoffProgressProvider = { 0f }, inverseScaleXProvider = { 1f },
                            inverseScaleYProvider = { 1f }, alphaProvider = {
                                resolveVideoDetailReturnMediaFrame(
                                    transitionProgress = depth.floatValue, isCommittedCardReturn = false,
                                    hasResidentCover = true, isReturnGestureInProgress = gesture.value,
                                    showResidentCoverUntilFirstFrame = true,
                                ).coverAlpha
                            },
                            modifier = Modifier.fillMaxWidth().height(135.dp),
                            onDecoded = { decoded.intValue++ },
                        )
                        Box(Modifier.fillMaxWidth().height(45.dp).background(Color.Yellow))
                    }
                }
            }
            rule.waitUntil(timeoutMillis = 5_000) { decoded.intValue > 0 }
            fun assertPixels(snapshotWeight: Float) {
                val pixels = rule.onNodeWithTag("quick-return").captureToImage().toPixelMap()
                val cover = pixels[pixels.width / 2, (pixels.height * 50 / 240)]
                val info = pixels[pixels.width / 2, (pixels.height * 150 / 240)]
                // Both groups use source-over blending above a black player backing.
                val liveWeight = (1f - snapshotWeight) * (1f - snapshotWeight)
                assertTrue("Cover disappeared at depth=${depth.floatValue}",
                    kotlin.math.abs(cover.blue - liveWeight) < 0.04f &&
                        kotlin.math.abs(cover.green - snapshotWeight) < 0.04f && cover.red < 0.04f)
                assertTrue("Source info disappeared before takeover",
                    kotlin.math.abs(info.green - (liveWeight + snapshotWeight)) < 0.04f &&
                        kotlin.math.abs(info.red - liveWeight) < 0.04f)
            }
            assertPixels(0f) // No snapshot is ready yet.
            rule.runOnIdle {
                CardPositionManager.recordWholeCardReturnBitmap(
                    snapshot, CardPositionManager.videoCardPixelOwnerFor("home:BVquick"),
                )
            }
            // Early return, full takeover, reverse/cancel, and restart all use the same depth.
            for (progress in listOf(0.9f, 0.45f, 0.25f, 0.01f, 0.25f, 0.9f, 1f)) {
                rule.runOnIdle { depth.floatValue = progress }
                val weight = com.android.purebilibili.feature.video.screen.resolveVideoDetailWholeCardReturnAlpha(
                    progress, returning = true, followProgress = true, committedIdle = false,
                )
                assertPixels(weight)
            }
            rule.runOnIdle { follow.value = false; depth.floatValue = 0.001f }
            assertPixels(0f)
            rule.runOnIdle { depth.floatValue = 0f }
            assertPixels(1f)
            rule.runOnIdle { gesture.value = false; depth.floatValue = 1f }
            assertPixels(0f)
        } finally {
            rule.runOnIdle {
                CardPositionManager.clear()
            }
        }
    }

    private fun recordCard(bvid: String, route: String, instanceId: Long = 1L) {
        CardPositionManager.recordVideoCardPosition(
            bvid = bvid, sourceRoute = route,
            bounds = Rect(0f, 0f, 240f, 180f),
            screenWidth = 1080f, screenHeight = 1920f,
            sourceLayout = VideoCardSourceLayout.STACKED,
            sourceInstanceId = instanceId,
        )
    }

    private fun solidBitmap(color: Int) =
        Bitmap.createBitmap(240, 180, Bitmap.Config.ARGB_8888).apply {
            eraseColor(color)
        }.asImageBitmap()

    @Test
    fun nestedReturnCannotRelabelChildPixelsOrOverwriteParentRetainedPixels() {
        val childVisible = mutableStateOf(false)
        val redraw = mutableIntStateOf(0)
        val parentPixels = solidBitmap(android.graphics.Color.RED)
        val childPixels = solidBitmap(android.graphics.Color.GREEN)
        var parentOwner: com.android.purebilibili.core.util.VideoCardPixelOwner? = null
        try {
            rule.runOnIdle {
                recordCard("BV_A", "home")
                parentOwner = CardPositionManager.videoCardPixelOwnerFor("home:BV_A")
                CardPositionManager.recordWholeCardReturnBitmap(
                    parentPixels, CardPositionManager.videoCardPixelOwnerFor("home:BV_A"),
                )
            }
            rule.setContent {
                redraw.intValue
                Column {
                    val parent = rememberVideoDetailWholeCardReturn(
                        bvid = "BV_A", sourceRoute = "home", ownsTransition = !childVisible.value,
                        committedReturn = false, keepLoadedContentForBackPreview = false,
                        sourceBounds = Rect(0f, 0f, 240f, 180f),
                        sourceLayout = VideoCardSourceLayout.STACKED, isNowPlayingBar = false,
                        depthProvider = { 0f },
                        phaseProvider = { VideoCardTransitionBackgroundPhase.RETURNING },
                        gestureProvider = { false }, followProgressProvider = { true },
                    )
                    Box(Modifier.size(120.dp, 90.dp).then(parent.modifier)
                        .background(Color.Blue).testTag("parent-return"))
                    if (childVisible.value) key("child-entry") {
                        val child = rememberVideoDetailWholeCardReturn(
                            bvid = "BV_B", sourceRoute = "video/BV_A", ownsTransition = true,
                            committedReturn = false, keepLoadedContentForBackPreview = false,
                            sourceBounds = Rect(0f, 0f, 240f, 180f),
                            sourceLayout = VideoCardSourceLayout.SIDE_BY_SIDE, isNowPlayingBar = false,
                            depthProvider = { 0f },
                            phaseProvider = { VideoCardTransitionBackgroundPhase.RETURNING },
                            gestureProvider = { false }, followProgressProvider = { true },
                        )
                        Box(Modifier.size(120.dp, 90.dp).then(child.modifier)
                            .background(Color.Blue).testTag("child-return"))
                    }
                }
            }
            fun assertColor(tag: String, color: Color) {
                val pixels = rule.onNodeWithTag(tag).captureToImage().toPixelMap()
                val actual = pixels[pixels.width / 2, pixels.height / 2]
                assertTrue("$tag used another entry's pixels: $actual", actual == color)
            }
            assertColor("parent-return", Color.Red)
            rule.runOnIdle {
                recordCard("BV_B", "video/BV_A", instanceId = 2L)
                CardPositionManager.recordWholeCardReturnBitmap(
                    childPixels, CardPositionManager.videoCardPixelOwnerFor("video/BV_A:BV_B"),
                )
                childVisible.value = true
            }
            assertColor("child-return", Color.Green)
            rule.runOnIdle {
                CardPositionManager.restoreVideoSource("home:BV_A", sourceOwner = parentOwner)
                childVisible.value = false
            }
            assertColor("parent-return", Color.Red)
            // Dispose the global child resource after the parent has had a chance to retain it.
            // A key-only fix would now keep showing the polluted green retained snapshot.
            rule.runOnIdle {
                CardPositionManager.clearNativeVideoCardLayers()
                redraw.intValue++
            }
            assertColor("parent-return", Color.Red)
        } finally {
            rule.runOnIdle { CardPositionManager.clear() }
        }
    }

    @Test
    fun repeatedSameKeyClickRejectsLatePixelsFromPreviousSourceInstance() {
        try {
            rule.runOnIdle {
                recordCard("BVsame", "home", instanceId = 10L)
                val first = CardPositionManager.videoCardPixelOwnerFor("home:BVsame")
                recordCard("BVsame", "home", instanceId = 11L)
                val second = CardPositionManager.videoCardPixelOwnerFor("home:BVsame")
                CardPositionManager.recordWholeCardReturnBitmap(solidBitmap(android.graphics.Color.GREEN), second)
                CardPositionManager.recordWholeCardReturnBitmap(solidBitmap(android.graphics.Color.RED), first)
                assertTrue(CardPositionManager.lastClickedWholeCardReturnSnapshot?.owner === second)
                val pixels = CardPositionManager.lastClickedWholeCardReturnSnapshot!!.bitmap.toPixelMap()
                assertTrue(pixels[120, 90] == Color.Green)
                // A second click of even the very same source instance is a different capture.
                recordCard("BVsame", "home", instanceId = 11L)
                CardPositionManager.recordWholeCardReturnBitmap(solidBitmap(android.graphics.Color.RED), second)
                assertTrue(CardPositionManager.lastClickedWholeCardReturnSnapshot == null)
                val third = CardPositionManager.videoCardPixelOwnerFor("home:BVsame")
                CardPositionManager.recordNativeCardBitmap(solidBitmap(android.graphics.Color.GREEN), third)
                assertTrue(CardPositionManager.lastClickedNativeCardBitmap != null)
                CardPositionManager.restoreVideoSource("home:BVsame", sourceOwner = second)
                assertTrue(CardPositionManager.lastClickedNativeCardBitmap == null)
            }
        } finally {
            rule.runOnIdle { CardPositionManager.clear() }
        }
    }

    @Test
    fun sameBvidFromDifferentSourceKeepsItsOriginalPixelProvenanceAfterKeyRestore() {
        try {
            rule.runOnIdle {
                recordCard("BVsame", "home", instanceId = 10L)
                val homeOwner = CardPositionManager.videoCardPixelOwnerFor("home:BVsame")
                recordCard("BVsame", "space/123", instanceId = 11L)
                val spaceOwner = CardPositionManager.videoCardPixelOwnerFor("space/123:BVsame")
                CardPositionManager.recordWholeCardReturnBitmap(
                    solidBitmap(android.graphics.Color.GREEN), spaceOwner,
                )
                CardPositionManager.recordNativeCardBitmap(
                    solidBitmap(android.graphics.Color.GREEN), spaceOwner,
                )
                assertTrue(CardPositionManager.lastClickedNativeCardBitmap != null)
                CardPositionManager.restoreVideoSource("home:BVsame", sourceOwner = homeOwner)
                assertTrue(CardPositionManager.lastClickedNativeCardBitmap == null)
                assertTrue(CardPositionManager.videoCardPixelOwnerFor("home:BVsame") == null)
                assertTrue(CardPositionManager.lastClickedWholeCardReturnSnapshot?.owner === spaceOwner)
                CardPositionManager.recordWholeCardReturnBitmap(
                    solidBitmap(android.graphics.Color.RED), homeOwner,
                )
                val captured = CardPositionManager.lastClickedWholeCardReturnSnapshot!!
                assertTrue(captured.owner.sourceKey == "space/123:BVsame")
                assertTrue(captured.bitmap.toPixelMap()[120, 90] == Color.Green)
            }
        } finally {
            rule.runOnIdle { CardPositionManager.clear() }
        }
    }

    @Test
    fun nativeCoverBadgeAndInfoKeepOneGeometryThroughReturnAndCancellation() {
        val ratio = mutableFloatStateOf(4f / 3f)
        val alpha = mutableFloatStateOf(0f)
        val frozen = mutableStateOf<ImageBitmap?>(null)
        val coverColor = mutableStateOf(Color.Blue)
        val infoColor = mutableStateOf(Color.Yellow)
        lateinit var freeze: () -> Unit
        rule.setContent {
            val layer = rememberGraphicsLayer()
            val scope = rememberCoroutineScope()
            SideEffect {
                freeze = { scope.launch { frozen.value = layer.toImageBitmap() }; Unit }
            }
            Column {
                // Record the complete stationary card, including its cover badge and info plate.
                Column(Modifier.size(120.dp, (120f / ratio.floatValue + 30f).dp).drawWithContent {
                    layer.record { this@drawWithContent.drawContent() }
                    drawLayer(layer)
                }) {
                    Box(Modifier.fillMaxWidth().height((120f / ratio.floatValue).dp).background(coverColor.value)) {
                        Box(Modifier.align(Alignment.TopEnd).size(12.dp).background(Color.Green))
                    }
                    Box(Modifier.fillMaxWidth().height(30.dp).background(infoColor.value))
                }
                Box(Modifier.size(240.dp, 280.dp).background(Color.Magenta)
                    .videoDetailWholeCardReturnLayer(bitmap = frozen.value) { alpha.floatValue }.testTag("native-return")) {
                    Box(Modifier.fillMaxSize().background(Color.Red))
                }
            }
        }
        for (cardRatio in listOf(4f / 3f, 16f / 10f, 16f / 9f)) {
            rule.runOnIdle {
                ratio.floatValue = cardRatio
                coverColor.value = Color.Blue
                infoColor.value = Color.Yellow
                frozen.value = null
            }
            rule.runOnIdle { freeze() }
            rule.waitUntil(timeoutMillis = 5_000) { frozen.value != null }
            // A later source redraw must not mutate the already captured return pixels.
            rule.runOnIdle { coverColor.value = Color.Cyan; infoColor.value = Color.Gray }
            for (opacity in listOf(1f, 0.5f, 0f, 0.5f, 1f)) {
                rule.runOnIdle { alpha.floatValue = opacity }
                val pixels = rule.onNodeWithTag("native-return").captureToImage().toPixelMap()
                val density = pixels.width / 240f
                fun pixel(x: Float, y: Float) = pixels[(x * density).toInt(), (y * density).toInt()]
                if (opacity == 1f) {
                    val coverBottom = 240f / cardRatio
                    val cover = pixel(120f, coverBottom - 4f)
                    val info = pixel(120f, coverBottom + 20f)
                    val badge = pixel(230f, 10f)
                    val below = pixel(120f, coverBottom + 70f)
                    assertTrue("Cover cropped or changed at ratio=$cardRatio",
                        cover.blue > 0.9f && cover.red < 0.1f && cover.green < 0.1f)
                    assertTrue("Info detached at ratio=$cardRatio", info.red > 0.9f && info.green > 0.9f)
                    assertTrue("Badge did not follow card", badge.green > 0.9f && badge.blue < 0.1f)
                    assertTrue("Detail leaked below card", below.red > 0.9f && below.blue > 0.9f)
                } else if (opacity == 0f) {
                    val restored = pixel(120f, 50f)
                    assertTrue("Cancelled return did not restore detail", restored.red > 0.9f && restored.blue < 0.1f)
                }
            }
        }
    }
}
