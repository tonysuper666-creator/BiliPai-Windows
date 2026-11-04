package com.android.purebilibili.core.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import com.bilipai.desktop.ui.modal.DesktopModalPurpose
import com.bilipai.desktop.ui.modal.DesktopPurposeModal

/** Required purpose makes this overload opt-in. Omitted purpose still resolves to the untouched generated sheet. */
@Composable
internal fun AppModalBottomSheet(
    purpose: DesktopModalPurpose,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    anchor: IntRect? = null,
    designWidth: Dp = 480.dp,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    footer: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) = DesktopPurposeModal(purpose, onDismissRequest, modifier, anchor, designWidth, containerColor, footer, content)
