@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package com.bilipai.desktop.player

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.*
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.*
import com.android.purebilibili.core.ui.feedContentTypography
import com.android.purebilibili.core.store.HomeWallpaperEffectMode
import com.android.purebilibili.feature.home.HomeFeedSkeletonCard
import com.android.purebilibili.feature.video.ui.components.VideoCardSkeleton
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.plugins.asAndroidContext
import com.bilipai.desktop.ui.LocalDesktopUpstreamAndroidContext
import java.nio.file.Files
import java.io.File
import org.junit.jupiter.api.Test
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class DesktopSkeletonGeometryCompositionTest {
 @Test fun defaultAndCompactGeometryAcrossDensities() = verify(false)
 @Test fun videoWrapperPreservesSharedDefaultAndCompactGeometry() = verify(true)
 private fun verify(video: Boolean) {
  for(width in listOf(240,400)) for(scale in listOf(1f,2f)) for(compact in listOf(false,true)) {
   val directory=Files.createTempDirectory("skeleton-geometry")
   val context=DesktopPluginContext(DesktopPluginStore(directory))
   var measured=IntSize.Zero
   var statisticSizes=emptyList<IntSize>()
   var titleSize=IntSize.Zero
   var authorSize=IntSize.Zero
   val scene=ImageComposeScene((width*scale).toInt(),(800*scale).toInt(),Density(scale)) {
    CompositionLocalProvider(LocalDesktopUpstreamAndroidContext provides context.asAndroidContext()) { MaterialTheme {
     val measurer=rememberTextMeasurer()
     val typography=feedContentTypography()
     statisticSizes=listOf("▷ 00.0万", "▤ 0000", "00-00").map {
      measurer.measure(AnnotatedString(it), style=typography.statistic).size
     }
     titleSize=measurer.measure(AnnotatedString("推荐视频标题占位\n推荐视频标题占位"),
      style=typography.title, maxLines=2,
      constraints=Constraints.fixedWidth(((width-20)*scale).toInt())).size
     authorSize=measurer.measure(AnnotatedString("UP 主名称"), style=typography.author,
      maxLines=1, constraints=Constraints.fixedWidth(((width-44)*scale).toInt())).size
     Box {
     if (video) VideoCardSkeleton(compactMetadata=compact, coverAspectRatio=16f/9f,
      modifier=Modifier.width(width.dp).onGloballyPositioned { measured=it.size })
     else HomeFeedSkeletonCard({0.5f},false,HomeWallpaperEffectMode.OFF,true,
      compactMetadata=compact,coverAspectRatio=16f/9f,modifier=Modifier.width(width.dp).onGloballyPositioned { measured=it.size })
    } } }
   }
   try {
    Snapshot.sendApplyNotifications();repeat(4){scene.render().close()}
    assertEquals((width*scale).toInt(),measured.width)
    // Layout dimensions stay exact. Text sizes use this platform's font resolver,
    // independently of the skeleton's measured nodes (Windows and macOS resolve different fonts).
    val coverSize=IntSize((width*scale).toInt(),(width*scale*9/16).toInt())
    val verticalPadding=((if(compact)6 else 8)*scale).toInt()
    val metadataGap=((if(compact)4 else 6)*scale).toInt()
    val expectedHeight=coverSize.height+2*verticalPadding+titleSize.height+
     2*metadataGap+maxOf(statisticSizes[0].height,statisticSizes[1].height)+
     authorSize.height+statisticSizes[2].height
    assertEquals(expectedHeight, measured.height)
    val root=scene.semanticsOwners.single().unmergedRootSemanticsNode.layoutNode
    fun leaves(n:androidx.compose.ui.node.LayoutNode):List<androidx.compose.ui.node.LayoutNode> = if(n.children.isEmpty()) listOf(n) else n.children.flatMap(::leaves)
    val visibleLeaves=leaves(root).filter { it.width>0 && it.height>0 }
    val expectedSizes=listOf(coverSize,titleSize,statisticSizes[0],statisticSizes[1],
     authorSize,statisticSizes[2])
    assertEquals(expectedSizes, visibleLeaves.map { IntSize(it.width,it.height) })
    visibleLeaves.forEach { node ->
     val bounds=node.coordinates.boundsInRoot()
     assertTrue(bounds.left>=0 && bounds.right<=measured.width && bounds.top>=0 && bounds.bottom<=measured.height)
    }
    val blocks=leaves(root).map { "${it.width}x${it.height}@${it.coordinates.boundsInRoot()}" }
    val name="w${width}-d${scale.toInt()}-compact$compact"
    println("SKELETON video=$video $name card=$measured leaves=$blocks")
    (if(video) null else System.getenv("SKELETON_CAPTURE_DIR"))?.let { path ->
     val dir=File(path).apply{mkdirs()}
     File(dir,"$name.txt").writeText("card=$measured\n"+blocks.joinToString("\n"))
     scene.render().use { image -> image.encodeToData()!!.use { File(dir,"$name.png").writeBytes(it.bytes) } }
    }
   } finally {scene.close(); directory.toFile().deleteRecursively()}
  }
 }
}
