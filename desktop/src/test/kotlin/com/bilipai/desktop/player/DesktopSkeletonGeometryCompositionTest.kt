@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package com.bilipai.desktop.player

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.*
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.*
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
   val scene=ImageComposeScene((width*scale).toInt(),(800*scale).toInt(),Density(scale)) {
    CompositionLocalProvider(LocalDesktopUpstreamAndroidContext provides context.asAndroidContext()) { MaterialTheme { Box {
     if (video) VideoCardSkeleton(compactMetadata=compact, coverAspectRatio=16f/9f,
      modifier=Modifier.width(width.dp).onGloballyPositioned { measured=it.size })
     else HomeFeedSkeletonCard({0.5f},false,HomeWallpaperEffectMode.OFF,true,
      compactMetadata=compact,coverAspectRatio=16f/9f,modifier=Modifier.width(width.dp).onGloballyPositioned { measured=it.size })
    } } }
   }
   try {
    Snapshot.sendApplyNotifications();repeat(4){scene.render().close()}
    assertEquals((width*scale).toInt(),measured.width)
    // Exact Mac/Skia font measurements: density 2 title/author rounding is not a doubled density-1 result.
    val expectedHeight = when {
     scale == 1f -> (if(width==240)253 else 343) - (if(compact)8 else 0)
     else -> (if(width==240)505 else 685) - (if(compact)16 else 0)
    }
    assertEquals(expectedHeight, measured.height)
    val root=scene.semanticsOwners.single().unmergedRootSemanticsNode.layoutNode
    fun leaves(n:androidx.compose.ui.node.LayoutNode):List<androidx.compose.ui.node.LayoutNode> = if(n.children.isEmpty()) listOf(n) else n.children.flatMap(::leaves)
    val visibleLeaves=leaves(root).filter { it.width>0 && it.height>0 }
    val expectedSizes = if(scale==1f) listOf(
     IntSize(width,width*9/16), IntSize(width-20,40), IntSize(52,16), IntSize(44,16),
     IntSize(width-44,18), IntSize(37,16))
    else listOf(IntSize(width*2,width*2*9/16), IntSize((width-20)*2,78), IntSize(103,32),
     IntSize(88,32), IntSize((width-44)*2,37), IntSize(73,32))
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
