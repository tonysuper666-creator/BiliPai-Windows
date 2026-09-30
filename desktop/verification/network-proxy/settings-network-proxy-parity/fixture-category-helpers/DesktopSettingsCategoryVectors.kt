// GENERATED from app/src/main/res/drawable/ms_keyboard_arrow_right_24.xml; do not edit.
// LF-normalized SHA-256: d3f53b271fdb2150775b4252bf46139c3ab5ca872802372a1089e6e1dd6f2a35
package com.bilipai.desktop.settings
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.*
import androidx.compose.ui.unit.dp
import java.util.concurrent.ConcurrentHashMap
/** Exact original XML paths/viewports. Native list icon tint remains authoritative. */
internal object DesktopSettingsCategorySymbols {
const val ms_keyboard_arrow_right_24="ms_keyboard_arrow_right_24"
}
internal object DesktopSettingsCategoryVectors {
 private val cache=ConcurrentHashMap<String,ImageVector>()
 @Composable fun vector(name:String):ImageVector=remember(name){load(name)}
 fun load(name:String):ImageVector=cache.computeIfAbsent(name){build(it)}
 private fun build(name:String):ImageVector=when(name){
"ms_keyboard_arrow_right_24" -> ImageVector.Builder(name="ms_keyboard_arrow_right_24",defaultWidth=24.0f.dp,defaultHeight=24.0f.dp,viewportWidth=960.0f,viewportHeight=960.0f,autoMirror=true).apply{
addPath(pathData=addPathNodes("M504,480L320,296L376,240L616,480L376,720L320,664L504,480Z"),fill=SolidColor(Color(0xFFFFFFFF)),fillAlpha=1.0f,pathFillType=PathFillType.NonZero)
}.build()
else->error("Unknown original settings vector: $name")
}
}
