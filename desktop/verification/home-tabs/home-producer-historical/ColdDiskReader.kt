package com.bilipai.desktop.settings.proof
import com.bilipai.desktop.plugins.*
import com.bilipai.desktop.settings.*
import com.android.purebilibili.core.store.*
import java.nio.file.Path
fun main(args:Array<String>) {
 val value=DesktopHomeCardPreferences(DesktopPluginContext(DesktopPluginStore(Path.of(args[0])))).initialSettings()
 if(args.getOrNull(1)=="compact") {
  check(value.gridColumnCount==5&&value.gridColumnCountCompact==3)
  check(value.homeFeedCardWidthPreset==HomeFeedCardWidthPreset.AUTO&&value.homeFeedCardStyle==HomeFeedCardStyle.OFFICIAL)
 }else {
  check(value.gridColumnCount==0&&value.gridColumnCountCompact==0)
  check(value.homeFeedCardWidthPreset==HomeFeedCardWidthPreset.WIDE&&value.homeFeedCardStyle==HomeFeedCardStyle.CURRENT)
 }
 println("PASS actual cold JVM persisted four-key mapper ${Path.of(args[0]).parent.fileName}")
}
