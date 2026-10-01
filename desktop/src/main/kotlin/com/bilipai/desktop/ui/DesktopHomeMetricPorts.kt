package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import kotlinx.coroutines.CoroutineScope
import androidx.compose.foundation.gestures.ScrollableState
import com.bilipai.desktop.appearance.LocalDesktopStrings

@Composable internal fun desktopHomeStringResource(key:String):String=LocalDesktopStrings.current[key]
/** Root binds one actual window metric sink. No Android JankStats or disabled default is invented. */
interface DesktopHomeMetricSink { fun putState(name:String,value:String); fun removeState(name:String) }
class DesktopHomeMetricHolder(val state:DesktopHomeMetricSink)
internal val LocalDesktopHomeMetricHolder=staticCompositionLocalOf<DesktopHomeMetricHolder>{error("Home requires the actual current-window metric sink")}
