from pathlib import Path
HERE=Path(__file__).parent
p=HERE/'compile_category.py';s=p.read_text(encoding='utf-8-sig').replace("'HomeScreen','SettingsManager'","'HomeNavigationIconPolicy','HomeScreen','SettingsManager'")
p.write_text(s,encoding='utf-8',newline='\n')
p=HERE/'prepare.py';s=p.read_text(encoding='utf-8-sig').replace("text=text.replace('Holder','DesktopHomeMetricHolder').replace('DesktopHomeMetricDesktopHomeMetricHolder','DesktopHomeMetricHolder')", "text=re.sub(r'\\bHolder\\b','DesktopHomeMetricHolder',text)")
s=s.replace("import androidx.compose.ui.composed\\nimport androidx.compose.ui.graphics.graphicsLayer", "import androidx.compose.ui.composed\\nimport com.android.purebilibili.core.theme.*\\nimport androidx.compose.ui.graphics.graphicsLayer")
p.write_text(s,encoding='utf-8',newline='\n')
p=HERE/'prepared/manual/com/bilipai/desktop/ui/DesktopHomeMetricPorts.kt';s=p.read_text(encoding='utf-8-sig').replace('internal interface DesktopHomeMetricSink','interface DesktopHomeMetricSink').replace('internal class DesktopHomeMetricHolder','class DesktopHomeMetricHolder');p.write_text(s,encoding='utf-8',newline='\n')
