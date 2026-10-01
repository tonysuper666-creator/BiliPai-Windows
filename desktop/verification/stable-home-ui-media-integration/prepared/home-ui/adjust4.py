from pathlib import Path
import re
HERE=Path(__file__).parent
p=HERE/'prepare.py';s=p.read_text(encoding='utf-8-sig')
s=s.replace("('Build.VERSION.SDK_INT','com.bilipai.desktop.ui.LocalDesktopHomePlatform.current.androidRenderEffectApiLevel'),", "('shouldAllowHomeChromeLiquidGlass(Build.VERSION.SDK_INT)','com.bilipai.desktop.ui.LocalDesktopHomePlatform.current.supportsHomeChromeLiquidGlass'),\n  ('shouldAllowDirectHazeLiquidGlassFallback(Build.VERSION.SDK_INT)','com.bilipai.desktop.ui.LocalDesktopHomePlatform.current.supportsDirectHazeLiquidGlassFallback'),")
s=s.replace(" if name=='SettingsManager':", " if name=='SettingsManager':")
s=s.replace("import com.android.purebilibili.core.plugin.skin.BottomBarVisibilityMode\\n", "import com.bilipai.desktop.ui.DesktopFavoriteNavigationTypes\\n")
s=s.replace(" if name=='HomeHeroCarousel':", " text=text.replace('SettingsManager.BottomBarVisibilityMode','com.bilipai.desktop.ui.DesktopFavoriteNavigationTypes.BottomBarVisibilityMode')\n text=text.replace('com.android.purebilibili.core.store.com.bilipai.desktop.ui.DesktopFavoriteNavigationTypes','com.bilipai.desktop.ui.DesktopFavoriteNavigationTypes')\n text=text.replace('com.android.purebilibili.core.store.SettingsManager.TopTabLabelMode.TEXT_ONLY','2')\n if name=='HomeHeroCarousel':")
p.write_text(s,encoding='utf-8',newline='\n')
p=HERE/'prepared/manual/com/bilipai/desktop/ui/DesktopHomeMediaPorts.kt';s=p.read_text(encoding='utf-8-sig').replace('internal class DesktopHomePlatform(val androidRenderEffectApiLevel:Int)','internal class DesktopHomePlatform(val supportsHomeChromeLiquidGlass:Boolean,val supportsDirectHazeLiquidGlassFallback:Boolean,val legacyTopChromeSafetyGapRequired:Boolean)').replace('onFirstFrame:()->Unit','onFirstFrame:(()->Unit)?');p.write_text(s,encoding='utf-8',newline='\n')
