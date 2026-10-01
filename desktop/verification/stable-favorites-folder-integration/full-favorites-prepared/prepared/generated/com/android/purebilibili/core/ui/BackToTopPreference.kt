package com.android.purebilibili.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.bilipai.desktop.ui.LocalDesktopFavoriteBindings
import androidx.compose.runtime.collectAsState as collectAsStateWithLifecycle

@Composable
fun rememberBackToTopButtonEnabled(): Boolean {
    val context = LocalDesktopFavoriteBindings.current
    val preferenceFlow = remember(context) {
        context.backToTopEnabled
    }
    val enabled by preferenceFlow.collectAsStateWithLifecycle(
        initial = context.initialBackToTopEnabled,
    )
    return enabled
}
