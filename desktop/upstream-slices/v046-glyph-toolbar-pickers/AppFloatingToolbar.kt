package com.android.purebilibili.core.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalFloatingToolbar
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.theme.LocalAppUiStyle
import top.yukonga.miuix.kmp.basic.FloatingToolbar

/** Placement and reserved list space belong to the caller. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppFloatingToolbar(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (LocalAppUiStyle.current == AppUiStyle.MIUIX) {
        FloatingToolbar(modifier = modifier, outSidePadding = PaddingValues()) { content() }
    } else {
        HorizontalFloatingToolbar(modifier = modifier, expanded = true) { content() }
    }
}
