package com.android.purebilibili.feature.home
import androidx.compose.runtime.*
val LocalHomeScrollOffset = compositionLocalOf { androidx.compose.runtime.mutableFloatStateOf(0f) }

val LocalHomeFeedScrollInProgress = compositionLocalOf { mutableStateOf(false) }

