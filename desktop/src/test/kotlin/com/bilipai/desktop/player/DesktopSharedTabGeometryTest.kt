@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
package com.bilipai.desktop.player

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.*
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.unit.Density
import com.android.purebilibili.core.ui.*
import com.android.purebilibili.feature.home.TodayWatchMode
import com.bilipai.desktop.settings.*
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.ui.*
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import org.junit.jupiter.api.Test
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class DesktopSharedTabGeometryTest {
 @Test fun existingTodayWatchDefaultIndicatorGeometry() = verify(false)
 @Test fun commentGlassUsesFullSlotIndicator() = verify(true)
 private fun verify(comment: Boolean) {
  val dir=Files.createTempDirectory("tab-geometry")
  val store=DesktopPluginStore(dir)
  store.update("settings",mapOf("android_native_liquid_glass_enabled" to JsonPrimitive(true)))
  val settings=DesktopLiquidTabSettings(store)
  val background=object:DesktopHomeWindowBackgroundPort {
   override val isInBackground=false
   override fun addListener(listener:DesktopHomeWindowBackgroundPort.Listener) {}
   override fun removeListener(listener:DesktopHomeWindowBackgroundPort.Listener) {}
  }
  val platform=DesktopHomePlatform(false,true,false,false,false,false,background,0f,false)
  val method=Class.forName("com.android.purebilibili.feature.home.HomeCategoryPageKt").declaredMethods.single { it.name=="TodayWatchModeSegmentedControl" }.apply { isAccessible=true }
  val scene=ImageComposeScene(400,120,Density(1f)) {
   MaterialTheme {
    CompositionLocalProvider(LocalDesktopLiquidTabSettings provides settings,LocalDesktopHomePlatform provides platform,
     LocalAppThemeConfig provides AppThemeConfig(liquidGlassEnabled=true),LocalComponentMotionEnabled provides false) {
     val composer=currentComposer
     if (comment) com.android.purebilibili.feature.video.ui.components.CommentSortFilterBar(
      com.android.purebilibili.feature.video.viewmodel.CommentSortMode.HOT, {})
     else method.invoke(null,TodayWatchMode.RELAX,true,({ _:TodayWatchMode -> } as (TodayWatchMode)->Unit),Modifier,null,composer,0,0)
    }
   }
  }
  try {
   repeat(30) { Snapshot.sendApplyNotifications();scene.render().close();Thread.sleep(5) }
   val root=scene.semanticsOwners.single().unmergedRootSemanticsNode.layoutNode
   fun all(n:androidx.compose.ui.node.LayoutNode):List<androidx.compose.ui.node.LayoutNode> = listOf(n)+n.children.flatMap(::all)
   val indicator=all(root).single { it.modifier.toString().contains("InnerShadowElement") }
   val bounds=indicator.coordinates.boundsInRoot()
   println("INDICATOR comment=$comment bounds=$bounds")
   if (!comment) assertEquals(androidx.compose.ui.geometry.Rect(17.5f,3f,110.5f,38f),bounds)
   else assertEquals(androidx.compose.ui.geometry.Rect(4f,3f,56f,38f),bounds)

  } finally {scene.close();dir.toFile().deleteRecursively()}
 }
}
