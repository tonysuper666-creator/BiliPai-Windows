package com.android.purebilibili.core.ui.motion

import androidx.compose.runtime.Composable
/** Share the real Root Windows SPI_GETCLIENTAREAANIMATION binding; no second OS observer contract. */
@Composable
fun rememberSystemReduceMotion():Boolean=com.bilipai.desktop.ui.rememberDesktopDynamicReduceMotion()
