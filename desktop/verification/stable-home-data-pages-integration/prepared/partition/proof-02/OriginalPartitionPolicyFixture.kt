package com.android.purebilibili.feature.partition

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import com.android.purebilibili.core.ui.AppSemanticIconFamily
import com.android.purebilibili.core.util.resolveReplaceRefreshPage
import com.android.purebilibili.data.model.response.BangumiType
import java.io.File

class PartitionScreenStructureTest {

    fun `partition page uses side rail and feed list layout`() {
        val source = loadSource("app/src/main/java/com/android/purebilibili/feature/partition/PartitionScreen.kt")

        assertTrue(source.contains("PartitionSideRail("))
        assertTrue(source.contains("PartitionVideoList("))
        assertTrue(source.contains("HomeStyleSingleColumnVideoCard("))
        assertTrue(source.contains("items = state.videos"))
        assertFalse(source.contains("state.videos.chunked(2)"))
        assertTrue(source.contains("resolveHomeFeedCardLayout(homeFeedCardStyle)"))
        assertTrue(source.contains("coverAspectRatio = cardLayout.coverAspectRatio"))
        assertTrue(source.contains("modifier = Modifier.fillMaxWidth()"))
        assertTrue(source.contains("SettingsManager.getHomeSettings(context)"))
        assertTrue(source.contains("rememberAppChromeLiquidGlassEnabled("))
        assertFalse(source.contains("resolveSharedLiquidGlassChromeEnabled("))
        assertTrue(source.contains("BottomBarMatchedLiquidIndicator("))
        assertTrue(source.contains("DampedDragAnimation("))
        assertTrue(source.contains("dampedDragAnimation.modifier"))
        assertFalse(source.contains("rememberBottomBarMatchedLiquidChromeState("))
        assertTrue(source.contains("liquidGlassIndicatorEnabled = liquidGlassIndicatorEnabled"))
        assertTrue(source.contains("val railPageBackdrop = rememberLayerBackdrop()"))
        assertTrue(source.contains("val railContentBackdrop = rememberLayerBackdrop()"))
        assertTrue(source.contains(".layerBackdrop(railContentBackdrop)"))
        assertTrue(source.contains("rememberCombinedBackdrop(railPageBackdrop, railContentBackdrop)"))
        assertTrue(source.contains(".bottomBarMatchedCaptureOverflow(captureSafeInset)"))
        assertTrue(source.contains(".layerBackdrop(railPageBackdrop)"))
        assertTrue(source.contains(".layerBackdrop(railContentBackdrop)"))
        assertTrue(source.contains("contentBackdrop = combinedBackdrop"))
        assertTrue(source.contains("backdrop = railPageBackdrop"))
        assertFalse(source.contains("forceUnselectedColor"))
        assertFalse(source.contains("partitionSideRailSweepSelection("))
        assertFalse(source.contains("PartitionVideoRow("))
        assertFalse(source.contains("videoTitleSharedElementKey("))
        assertTrue(source.contains("sourceRoute = sharedElementSourceRoute"))
        assertTrue(source.contains("LocalVideoCardSharedElementSourceRoute.current"))
        assertTrue(source.contains("VideoRepository.getPopularVideos(page = pageToFetch)"))
        assertTrue(source.contains("VideoRepository.getRegionVideos(tid = partition.id, page = pageToFetch)"))
        assertTrue(source.contains("resolvePartitionBangumiType(partition.id)"))
        assertTrue(source.contains("onBangumiClick(bangumiType)"))
        assertFalse(source.contains("LazyVerticalGrid("))
        assertTrue(source.contains("AdaptivePullToRefreshBox("))
        assertTrue(source.contains("onRefresh = viewModel::refresh"))
        assertTrue(source.contains("fun refresh()"))
        assertTrue(source.contains("resolveReplaceRefreshPage("))
    }

    fun `partition refresh advances page to surface other videos`() {
        assertEquals(2, resolveReplaceRefreshPage(nextLoadPage = 2, hasMore = true))
        assertEquals(1, resolveReplaceRefreshPage(nextLoadPage = 5, hasMore = false))
        assertEquals(1, resolveReplaceRefreshPage(nextLoadPage = 0, hasMore = true))
    }

    fun `side rail indicator drag lives on the moving capsule like the home dock`() {
        val source = loadSource("app/src/main/java/com/android/purebilibili/feature/partition/PartitionScreen.kt")

        assertFalse(source.contains("pointerInput(partitions)"))
        assertFalse(source.contains("awaitLongPressOrCancellation("))
        assertFalse(source.contains("verticalDrag("))
        assertTrue(source.contains("dampedDragAnimation.modifier"))
        assertTrue(source.contains("interactiveHighlight?.gestureModifier"))
        assertTrue(source.contains("dragAmount.y / holder.itemSlotHeightPx"))
        assertTrue(source.contains("resolvePartitionSideRailInteractiveHighlightPosition("))
        assertTrue(source.contains("animation.value"))
        assertTrue(source.contains("shouldStartPartitionSideRailIndicatorDrag("))
    }

    fun `side rail uses md3 underline when liquid glass is disabled`() {
        val source = loadSource("app/src/main/java/com/android/purebilibili/feature/partition/PartitionScreen.kt")
        val indicator = source
            .substringAfter("private fun PartitionSideRailMovingIndicator(")
            .substringBefore("private fun PartitionSideRailItem(")

        assertTrue(indicator.contains("resolveHomeSelectionIndicatorStyle("))
        assertTrue(
            indicator.contains(
                "selectionIndicatorStyle == HomeSelectionIndicatorStyle.MD3_UNDERLINE"
            )
        )
        assertTrue(indicator.contains("PartitionSideRailMd3UnderlineWidth"))
        assertTrue(indicator.contains("PartitionSideRailMd3UnderlineHeight"))
        assertTrue(indicator.contains("indicatorOffsetPxProvider()"))
        assertTrue(indicator.contains("contentAlignment = Alignment.CenterStart"))
        assertTrue(indicator.contains("PartitionSideRailMd3UnderlineStartPadding"))
        assertTrue(indicator.contains("onVideoListPushChanged(0f)"))
        assertTrue(indicator.contains("resolveAndroidNativeIdleIndicatorSurfaceColor("))
        assertTrue(indicator.indexOf("return") < indicator.indexOf("BottomBarMatchedLiquidIndicator("))
    }

    fun `side rail drag starts only from current indicator bounds`() {
        assertTrue(
            shouldStartPartitionSideRailIndicatorDrag(
                pointerY = 64f,
                indicatorTopPx = 60f,
                indicatorHeightPx = 48f
            )
        )
        assertFalse(
            shouldStartPartitionSideRailIndicatorDrag(
                pointerY = 40f,
                indicatorTopPx = 60f,
                indicatorHeightPx = 48f
            )
        )
        assertFalse(
            shouldStartPartitionSideRailIndicatorDrag(
                pointerY = 64f,
                indicatorTopPx = 60f,
                indicatorHeightPx = 0f
            )
        )
    }

    fun `side rail indicator offset tracks lazy list scroll`() {
        assertTrue(
            resolvePartitionSideRailIndicatorOffsetPx(
                indicatorPosition = 10f,
                firstVisibleItemIndex = 8,
                firstVisibleItemScrollOffsetPx = 12,
                contentTopPaddingPx = 16f,
                itemSlotHeightPx = 52f
            ) == 108f
        )
    }

    fun `side rail indicator uses the same horizontal padding as items`() {
        val ltrPadding = resolvePartitionSideRailIndicatorHorizontalPadding(
            contentPadding = PaddingValues(start = 16.dp, end = 4.dp),
            layoutDirection = LayoutDirection.Ltr
        )
        val rtlPadding = resolvePartitionSideRailIndicatorHorizontalPadding(
            contentPadding = PaddingValues(start = 16.dp, end = 4.dp),
            layoutDirection = LayoutDirection.Rtl
        )

        assertTrue(ltrPadding.start == 16.dp)
        assertTrue(ltrPadding.end == 4.dp)
        assertTrue(rtlPadding.start == 16.dp)
        assertTrue(rtlPadding.end == 4.dp)
    }

    fun `side rail indicator uses layout offset without extra vertical drift`() {
        val source = loadSource("app/src/main/java/com/android/purebilibili/feature/partition/PartitionScreen.kt")

        assertTrue(source.contains("indicatorOffsetPxProvider: () -> Float"))
        assertTrue(source.contains("indicatorTranslationYPx = centeredIndicatorOffsetPx"))
        assertTrue(source.contains("orientation = BottomBarLiquidOrientation.VERTICAL"))
        assertTrue(source.contains("indicatorAlignment = Alignment.TopStart"))
        assertFalse(source.contains("centerLayerOnIndicatorY"))
        assertFalse(source.contains("translationY = panelOffsetPx"))
        assertFalse(source.contains("val panelOffsetPx"))
    }

    fun `side rail drag highlight follows the indicator on the vertical axis`() {
        val highlight = resolvePartitionSideRailInteractiveHighlightPosition(
            railWidthPx = 80f,
            indicatorOffsetPx = 120f,
            itemHeightPx = 48f,
        )

        assertTrue(highlight.x == 40f)
        assertTrue(highlight.y == 144f)
    }

    fun `side rail item color follows moving indicator position`() {
        assertTrue(resolvePartitionSideRailItemSelectionProgress(itemIndex = 3, indicatorPosition = 3f) == 1f)
        assertTrue(resolvePartitionSideRailItemSelectionProgress(itemIndex = 3, indicatorPosition = 3.5f) == 0.5f)
        assertTrue(resolvePartitionSideRailItemSelectionProgress(itemIndex = 3, indicatorPosition = 4.2f) == 0f)
    }

    fun `pgc partitions map to bangumi page types`() {
        assertEquals(BangumiType.ANIME.value, resolvePartitionBangumiType(13))
        assertEquals(BangumiType.GUOCHUANG.value, resolvePartitionBangumiType(167))
        assertEquals(BangumiType.MOVIE.value, resolvePartitionBangumiType(23))
        assertEquals(BangumiType.TV_SHOW.value, resolvePartitionBangumiType(11))
        assertEquals(BangumiType.DOCUMENTARY.value, resolvePartitionBangumiType(177))
        assertNull(resolvePartitionBangumiType(3))
    }

    fun `side rail label mode follows top tab display modes`() {
        assertTrue(shouldShowPartitionSideRailIcon(labelMode = 0))
        assertTrue(shouldShowPartitionSideRailText(labelMode = 0))
        assertTrue(shouldShowPartitionSideRailIcon(labelMode = 1))
        assertFalse(shouldShowPartitionSideRailText(labelMode = 1))
        assertFalse(shouldShowPartitionSideRailIcon(labelMode = 2))
        assertTrue(shouldShowPartitionSideRailText(labelMode = 2))
    }

    fun `side rail icons follow the active top chrome icon family`() {
        assertSameVectorAsset(
            Icons.Outlined.SportsEsports,
            resolvePartitionSideRailIcon(4, AppSemanticIconFamily.MATERIAL, selected = false)
        )
        assertSameVectorAsset(
            Icons.Filled.SmartToy,
            resolvePartitionSideRailIcon(188, AppSemanticIconFamily.MATERIAL, selected = true)
        )
    }

    fun `side rail item content is centered without manual left spacer`() {
        val source = loadSource("app/src/main/java/com/android/purebilibili/feature/partition/PartitionScreen.kt")
        val itemSource = source
            .substringAfter("private fun PartitionSideRailItem(")
            .substringBefore("internal fun shouldStartPartitionSideRailIndicatorDrag(")

        assertTrue(itemSource.contains("horizontalAlignment = Alignment.CenterHorizontally"))
        assertTrue(itemSource.contains("textAlign = TextAlign.Center"))
        assertFalse(itemSource.contains("Spacer(modifier = Modifier.width(14.dp))"))
    }

    fun `partition video card obeys global shared transition switch`() {
        val source = loadSource("app/src/main/java/com/android/purebilibili/feature/partition/PartitionScreen.kt")
        val listSource = source.substringAfter("private fun PartitionVideoList(")

        assertTrue(listSource.contains("val sharedTransitionEnabled = LocalSharedTransitionEnabled.current"))
        assertTrue(listSource.contains("transitionEnabled = sharedTransitionEnabled"))
        assertTrue(listSource.contains("HomeStyleSingleColumnVideoCard("))
        assertTrue(listSource.contains("showUpBadge = showUpBadges"))
        assertTrue(listSource.contains(".getHomeUpBadgesVisible(context)"))
        assertFalse(listSource.contains("spring(dampingRatio = 0.8f, stiffness = 200f)"))
        assertFalse(listSource.contains("transitionEnabled = true"))
    }

    fun `video list push follows long press drag then can return to rest`() {
        assertTrue(
            resolvePartitionVideoListPushPx(
                pressProgress = 1f,
                dragOffsetPx = 0f,
                itemSlotHeightPx = 52f,
                maxPushPx = 20f
            ) == 20f
        )
        assertTrue(
            resolvePartitionVideoListPushPx(
                pressProgress = 0f,
                dragOffsetPx = 52f,
                itemSlotHeightPx = 52f,
                maxPushPx = 20f
            ) > 16f
        )
        assertTrue(
            resolvePartitionVideoListPushPx(
                pressProgress = 0f,
                dragOffsetPx = 0f,
                itemSlotHeightPx = 52f,
                maxPushPx = 20f
            ) == 0f
        )
    }

    private fun loadSource(path: String): String {
        val normalizedPath = path.removePrefix("app/")
        val sourceFile = listOf(
            File(path),
            File(normalizedPath)
        ).firstOrNull { it.exists() }
        require(sourceFile != null) { "Cannot locate $path from ${File(".").absolutePath}" }
        return sourceFile.readText()
    }

    private fun assertSameVectorAsset(expected: ImageVector, actual: ImageVector) {
        assertEquals(expected.name, actual.name)
        assertEquals(expected.defaultWidth, actual.defaultWidth)
        assertEquals(expected.defaultHeight, actual.defaultHeight)
        assertEquals(expected.viewportWidth, actual.viewportWidth)
        assertEquals(expected.viewportHeight, actual.viewportHeight)
    }
}

private var assertionCount=0
private fun assertTrue(value:Boolean){assertionCount++;check(value)}
private fun assertFalse(value:Boolean){assertionCount++;check(!value)}
private fun assertNull(value:Any?){assertionCount++;check(value==null)}
private fun assertEquals(expected:Any?,actual:Any?){assertionCount++;check(expected==actual){"$expected != $actual"}}
private fun assertEquals(expected:Float,actual:Float,delta:Float){assertionCount++;check(kotlin.math.abs(expected-actual)<=delta)}
private fun assertEquals(expected:Double,actual:Double,delta:Double){assertionCount++;check(kotlin.math.abs(expected-actual)<=delta)}
fun main() = kotlinx.coroutines.runBlocking {
 val fixture=PartitionScreenStructureTest()
 fixture.`partition page uses side rail and feed list layout`()
 fixture.`partition refresh advances page to surface other videos`()
 fixture.`side rail indicator drag lives on the moving capsule like the home dock`()
 fixture.`side rail uses md3 underline when liquid glass is disabled`()
 fixture.`side rail drag starts only from current indicator bounds`()
 fixture.`side rail indicator offset tracks lazy list scroll`()
 fixture.`side rail indicator uses the same horizontal padding as items`()
 fixture.`side rail indicator uses layout offset without extra vertical drift`()
 fixture.`side rail drag highlight follows the indicator on the vertical axis`()
 fixture.`side rail item color follows moving indicator position`()
 fixture.`pgc partitions map to bangumi page types`()
 fixture.`side rail label mode follows top tab display modes`()
 fixture.`side rail icons follow the active top chrome icon family`()
 fixture.`side rail item content is centered without manual left spacer`()
 fixture.`partition video card obeys global shared transition switch`()
 fixture.`video list push follows long press drag then can return to rest`()
 val gates=com.bilipai.desktop.ui.provePartitionOwner()
 println("{\"passed\":true,\"adaptedOriginalPolicyMethods\":16,\"adaptedOriginalAssertions\":"+assertionCount+",\"preparedOwnerGates\":"+gates.size+",\"actualMainOrRootAccepted\":false}")
}
