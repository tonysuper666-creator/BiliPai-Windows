@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.android.bilipai.tv.ui

import android.animation.ValueAnimator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import com.android.purebilibili.core.theme.BiliPink
import com.android.purebilibili.core.theme.BiliPinkDark
import com.android.purebilibili.core.theme.DarkBackground
import com.android.purebilibili.core.theme.DarkSurface
import com.android.purebilibili.core.theme.DarkSurfaceVariant
import com.android.purebilibili.core.theme.TextPrimaryDark
import com.android.purebilibili.core.theme.TextSecondaryDark

@Composable
fun TvTheme(
    reduceMotion: Boolean = !ValueAnimator.areAnimatorsEnabled(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(shapes = TvUiTokens.shapes, typography = TvUiTokens.typography, colorScheme = darkColorScheme(
        primary = BiliPink, onPrimary = DarkBackground,
        background = DarkBackground, onBackground = TextPrimaryDark,
        surface = DarkBackground, onSurface = TextPrimaryDark,
        surfaceVariant = DarkSurface, onSurfaceVariant = TextPrimaryDark,
        secondary = TextSecondaryDark, secondaryContainer = DarkSurfaceVariant,
        onSecondaryContainer = TextPrimaryDark,
        border = BiliPinkDark,
    )) {
        CompositionLocalProvider(
            LocalContentColor provides MaterialTheme.colorScheme.onSurface,
            LocalTvReduceMotion provides reduceMotion,
            content = content,
        )
    }
}
