package com.bilipai.desktop.settings
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.*
import androidx.compose.ui.unit.dp
internal object DesktopLinkedDockSymbols {
const val ms_home_fill_24="ms_home_fill_24"
const val ms_history_fill_24="ms_history_fill_24"
const val ms_rss_feed_24="ms_rss_feed_24"
const val bp_nav_home_outline_24="bp_nav_home_outline_24"
const val bp_nav_history_outline_24="bp_nav_history_outline_24"
}
/** Exact source XML paths, fills, strokes, caps, joins and viewports; Root icon tint owns color. */
internal object DesktopLinkedDockVectors {
 @Composable fun vector(name:String):ImageVector=remember(name){load(name)}
 fun load(name:String):ImageVector=when(name) {
"ms_home_fill_24" -> ImageVector.Builder(name="ms_home_fill_24",defaultWidth=24.0f.dp,defaultHeight=24.0f.dp,viewportWidth=960.0f,viewportHeight=960.0f,autoMirror=false).apply {
addPath(pathData=addPathNodes("M160,840L160,360L480,120L800,360L800,840L560,840L560,560L400,560L400,840L160,840Z"),fill=SolidColor(Color(0xFFFFFFFF)),fillAlpha=1.0f,pathFillType=PathFillType.NonZero)
}.build()
"ms_history_fill_24" -> ImageVector.Builder(name="ms_history_fill_24",defaultWidth=24.0f.dp,defaultHeight=24.0f.dp,viewportWidth=960.0f,viewportHeight=960.0f,autoMirror=false).apply {
addPath(pathData=addPathNodes("M480,840Q342,840 239.5,748.5Q137,657 122,520L204,520Q218,624 296.5,692Q375,760 480,760Q597,760 678.5,678.5Q760,597 760,480Q760,363 678.5,281.5Q597,200 480,200Q411,200 351,232Q291,264 250,320L360,320L360,400L120,400L120,160L200,160L200,254Q251,190 324.5,155Q398,120 480,120Q555,120 620.5,148.5Q686,177 734.5,225.5Q783,274 811.5,339.5Q840,405 840,480Q840,555 811.5,620.5Q783,686 734.5,734.5Q686,783 620.5,811.5Q555,840 480,840ZM592,648L440,496L440,280L520,280L520,464L648,592L592,648Z"),fill=SolidColor(Color(0xFFFFFFFF)),fillAlpha=1.0f,pathFillType=PathFillType.NonZero)
}.build()
"ms_rss_feed_24" -> ImageVector.Builder(name="ms_rss_feed_24",defaultWidth=24.0f.dp,defaultHeight=24.0f.dp,viewportWidth=960.0f,viewportHeight=960.0f,autoMirror=false).apply {
addPath(pathData=addPathNodes("M200,840Q167,840 143.5,816.5Q120,793 120,760Q120,727 143.5,703.5Q167,680 200,680Q233,680 256.5,703.5Q280,727 280,760Q280,793 256.5,816.5Q233,840 200,840ZM680,840Q680,723 636,621.5Q592,520 516,444Q440,368 338.5,324Q237,280 120,280L120,160Q262,160 385,213Q508,266 601,359Q694,452 747,575Q800,698 800,840L680,840ZM440,840Q440,773 415,715.5Q390,658 346,614Q302,570 244.5,545Q187,520 120,520L120,400Q212,400 291.5,434.5Q371,469 431,529Q491,589 525.5,668.5Q560,748 560,840L440,840Z"),fill=SolidColor(Color(0xFFFFFFFF)),fillAlpha=1.0f,pathFillType=PathFillType.NonZero)
}.build()
"bp_nav_home_outline_24" -> ImageVector.Builder(name="bp_nav_home_outline_24",defaultWidth=24.0f.dp,defaultHeight=24.0f.dp,viewportWidth=960.0f,viewportHeight=960.0f,autoMirror=false).apply {
addPath(pathData=addPathNodes("M200,400L480,190L760,400L760,800L570,800L570,570L390,570L390,800L200,800Z"),fill=SolidColor(Color(0x00000000)),fillAlpha=1.0f,pathFillType=PathFillType.NonZero,stroke=SolidColor(Color(0xFFFFFFFF)),strokeLineWidth=60.0f,strokeAlpha=1.0f,strokeLineCap=StrokeCap.Round,strokeLineJoin=StrokeJoin.Round,strokeLineMiter=4.0f)
}.build()
"bp_nav_history_outline_24" -> ImageVector.Builder(name="bp_nav_history_outline_24",defaultWidth=24.0f.dp,defaultHeight=24.0f.dp,viewportWidth=960.0f,viewportHeight=960.0f,autoMirror=false).apply {
addPath(pathData=addPathNodes("M200,255C265,175 365,130 480,130C673,130 830,287 830,480C830,673 673,830 480,830C303,830 156,698 132,526M120,170L120,400L350,400M480,280L480,480L620,620"),fill=SolidColor(Color(0x00000000)),fillAlpha=1.0f,pathFillType=PathFillType.NonZero,stroke=SolidColor(Color(0xFFFFFFFF)),strokeLineWidth=60.0f,strokeAlpha=1.0f,strokeLineCap=StrokeCap.Round,strokeLineJoin=StrokeJoin.Round,strokeLineMiter=4.0f)
}.build()
else->error("Unregistered original dock vector: $name")
}
}
