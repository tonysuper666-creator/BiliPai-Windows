@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.android.bilipai.tv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.android.bilipai.tv.ui.LocalTvReduceMotion
import com.android.bilipai.tv.ui.TvUiTokens
import com.android.purebilibili.core.ui.ContainerLevel

/** The caller owns content, placement, and focus restoration. */
@Composable
internal fun TvAppCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = TvUiTokens.shape(ContainerLevel.Card)
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = CardDefaults.shape(shape = shape),
        scale = CardDefaults.scale(
            focusedScale = if (LocalTvReduceMotion.current) 1f else TvUiTokens.focusedCardScale
        ),
        border = CardDefaults.border(focusedBorder = Border(
            border = BorderStroke(TvUiTokens.focusBorderWidth, MaterialTheme.colorScheme.primary),
            shape = shape,
        )),
        interactionSource = interactionSource,
        content = content,
    )
}

@Composable
internal fun TvAppButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = { if (!isLoading) onClick() },
        modifier = modifier.heightIn(min = TvUiTokens.minimumButtonHeight),
        enabled = enabled,
        shape = ButtonDefaults.shape(shape = TvUiTokens.buttonShape),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        colors = ButtonDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.primary,
            focusedContentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        // Retain the focused target while its action is in progress.
        if (isLoading) Text("处理中…") else content()
    }
}

@Composable
internal fun TvNavigationItem(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Button(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = TvUiTokens.minimumButtonHeight)
            .semantics { this.selected = selected },
        shape = ButtonDefaults.shape(shape = TvUiTokens.buttonShape),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        colors = ButtonDefaults.colors(
            containerColor = if (selected) colors.primary.copy(alpha = 0.18f) else colors.surfaceVariant,
            contentColor = if (selected) colors.primary else colors.onSurface,
            focusedContainerColor = colors.surfaceVariant,
            focusedContentColor = colors.onSurface,
        ),
        border = ButtonDefaults.border(focusedBorder = Border(
            border = BorderStroke(TvUiTokens.focusBorderWidth, colors.primary),
            shape = TvUiTokens.buttonShape,
        )),
        content = content,
    )
}
